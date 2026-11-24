# ADR-004: Runtime Fallback (DEC-05, AC-05) и Durable Outbox + Async Retry (15-min SLA)

## Статус
Принято (обновлено 2026-11-24)

## Контекст
Сбой инференса или его зависание (> 500 мс) не должны останавливать приём отзывов (`DEC-05`, `AC-05`). При этом сетевые ошибки и таймауты внешней Службы качества не должны задерживать ответ `POST /api/v1/reviews/classify` или приводить к потере критичных карточек при перезапуске JVM (`FR-11`, `FR-12`, `DEC-06`).

## Решение
1. **Runtime Fallback (`DEC-05`, `AC-05`):**
   - Скоринг в `ClassifyReviewUseCase` выполняется с бюджетом `INFERENCE_TIMEOUT_MS` (по умолчанию `500` мс).
   - При ошибке или таймауте `sentiment = null`, `fallbackUsed = true`, инкрементируется `fallback_total`; критичность при `rating <= 2` остаётся `true`, при `3..5` — `false`.
2. **Durable Outbox + Async Retry (`RetryableQaAdapter` + `FileOutboxStore`):**
   - При заданном `QA_SERVICE_URL` критичный инцидент (`IncidentCardDto` с нижнерегистровым `"positive"`/`"negative"`/`null`) сначала **синхронно и с `FileChannel.force(true)`** сохраняется в `OUTBOX_LOG_PATH` и память, после чего HTTP-запрос сразу отвечает `200 OK`, не блокируясь на сетевых ретраях.
   - Доставка и повторные попытки выполняются асинхронно фоновым воркером (исключения внутри воркера перехватываются и не останавливают планировщик).
   - При рестарте сервиса неподтверждённые записи автоматически поднимаются из `OUTBOX_LOG_PATH`; при успешной доставке с ключом `X-Idempotency-Key` запись удаляется из памяти и файла (нет утечки `slaBreachedIds`).
   - Независимая проверка 15-минутного SLA пробегает по всей очереди ожидания: просрочка фиксируется однократно флагом в записи, счётчиком `sla_breach_total` и структурированным JSON-событием `SLA_BREACH_EXTERNAL` в асинхронном `AUDIT_ASYNC` потоке.

## Последствия
- Нулевая потеря критичных инцидентов даже при падении QA и рестарте контейнера.
- Предсказуемое время ответа API без ожидания сетевых таймаутов QA.
- Экспозиция гистограмм (`http_request_duration_ms`, `qa_delivery_latency_seconds`) и backlog gauges (`qa_outbox_pending`, `qa_outbox_oldest_age_seconds`) для Prometheus и правил алертинга ([docs/monitoring/prometheus-alerts.yml](../monitoring/prometheus-alerts.yml)).
