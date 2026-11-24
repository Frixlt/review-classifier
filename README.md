# Review Classifier (`review-classifier`)

<p>
  <a href="https://github.com/Frixlt/review-classifier/actions/workflows/ci.yml"><img src="https://github.com/Frixlt/review-classifier/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI Quality Gate"></a>
  <a href="https://github.com/Frixlt/review-classifier/commits/main"><img src="https://img.shields.io/github/last-commit/Frixlt/review-classifier?style=flat-square&logo=git&logoColor=white&color=informational" alt="Last Commit"></a>
  <img src="https://img.shields.io/github/languages/top/Frixlt/review-classifier?style=flat-square&color=ED8B00" alt="Top Language">
  <img src="https://img.shields.io/github/repo-size/Frixlt/review-classifier?style=flat-square&color=blue" alt="Repo Size">
</p>
<p>
  <img src="https://img.shields.io/badge/Java-25%20LTS-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25 LTS">
  <img src="https://img.shields.io/badge/Maven-3.10.0-C71A36?style=flat-square&logo=apachemaven&logoColor=white" alt="Maven 3.10.0">
  <img src="https://img.shields.io/badge/Jackson-2.23.0-2B5B84?style=flat-square&logo=json&logoColor=white" alt="Jackson 2.23.0">
  <img src="https://img.shields.io/badge/Docker-Multi--Stage-2496ED?style=flat-square&logo=docker&logoColor=white" alt="Docker Multi-Stage">
  <img src="https://img.shields.io/badge/OpenAPI-3.0.3-6BA539?style=flat-square&logo=openapiinitiative&logoColor=white" alt="OpenAPI 3.0.3">
  <img src="https://img.shields.io/badge/Prometheus-Metrics-E6522C?style=flat-square&logo=prometheus&logoColor=white" alt="Prometheus Metrics">
  <img src="https://img.shields.io/badge/Architecture-Clean%20%2F%20Hexagonal-6E4AFF?style=flat-square" alt="Clean / Hexagonal Architecture">
</p>
<p>
  <img src="https://img.shields.io/badge/JUnit%205-77%20tests%20passing-25A162?style=flat-square&logo=junit5&logoColor=white" alt="JUnit 5 — 77 tests passing">
  <img src="https://img.shields.io/badge/JaCoCo%20Core-97.3%25%20line-25A162?style=flat-square" alt="JaCoCo Core — 97.3% line">
  <img src="https://img.shields.io/badge/Spotless-Palantir%20Format-4B32C3?style=flat-square" alt="Spotless — Palantir Format">
  <img src="https://img.shields.io/badge/Checkstyle-0%20violations-25A162?style=flat-square" alt="Checkstyle — 0 violations">
  <img src="https://img.shields.io/badge/PMD%207-CC%20%3C%3D%2010-25A162?style=flat-square" alt="PMD 7 — CC <= 10">
  <img src="https://img.shields.io/badge/SpotBugs-0%20bugs-25A162?style=flat-square" alt="SpotBugs — 0 bugs">
  <img src="https://img.shields.io/badge/152--%D0%A4%D0%97-PII%20Masking-0A7BBB?style=flat-square" alt="152-ФЗ — PII Masking">
</p>

Микросервис классификации тональности и выявления критичных отзывов интернет-магазина на **OpenJDK 25 LTS** (Вариант 1, курс «Основы программной инженерии», ПР-3 / ДЗ-4).

Автор: **Бердников Максим Александрович** (`MFAI-01`, `m.berdnikov@innopolis.university`).

## Архитектура и ключевые особенности

Проект построен по правилам **Clean / Hexagonal Architecture** в четырёх строго изолированных слоях:

- **`domain`** (`model`, `exception`, `port`, `service`) — чистая предметная логика на Java 25 без фреймворков и I/O:
  - Строгая валидация входного контракта (`ReviewValidator`: канонический UUID, `orderId`, рейтинг `1..5`, текст `1..5000`, ISO-8601);
  - Детерминированное rule-based маскирование ПДн (`PiiMasker` заменяет телефоны, email, включая IDN, и типовые формы ФИО/имён на `[PHONE]`, `[EMAIL]`, `[NAME]` **до** скоринга, аудита и QA без искажения смысла отзыва);
  - Токенизация и нормализация русского текста (`TextPreprocessor`: lowercase, `ё -> е`);
  - Скоринг тональности (`SentimentScoringService`, `score == 0.0 -> "negative"` по `DEC-02`);
  - Выявление маркеров брака/доставки (`IssueCategoryDetector`: `DEFECT` включая `полом*`, `DELIVERY_ISSUE`, `OTHER`) и оценка критичности (`CriticalityEvaluator`).
- **`application`** (`ClassifyReviewUseCase`) — оркестрация сценария классификации, **Runtime Fallback** при ошибке или превышении бюджета инференса `INFERENCE_TIMEOUT_MS` (`500` мс по `AC-05`: `sentiment = null`, `fallbackUsed = true`, критичность по рейтингу `<= 2`), маршрутизация инцидентов и учёт метрик.
- **`adapters`** (`inbound/http`, `outbound`):
  - HTTP-сервер на **Java 25 Virtual Threads** (`ReviewHttpServer`) со строгой проверкой маршрутов (`404`), заголовком `Allow` на `405`, лимитом тела (`65 536` байт), лимитом конкурентности (`Semaphore`), таймаутами `maxReqTime`/`maxRspTime` и строгим Jackson (отклоняет `null`, float/string coercion и лишние токены, не отражает сырые значения в `400`-ответах);
  - Безопасная загрузка JSON-словаря весов (`JsonModelWeightsLoader`: SHA-256 через `MessageDigest.isEqual`, лимит `10 МБ`, таймаут `<= 10 с`, без `ObjectInputStream`);
  - **Durable Outbox + Async Retry** к QA (`RetryableQaAdapter` + `FileOutboxStore` + `HttpIncidentSender`): при заданном `QA_SERVICE_URL` карточка с нижнерегистровым `sentiment` и ключом `X-Idempotency-Key` сначала надёжно записывается на диск (с восстановлением при рестарте), а сетевые ретраи выполняются асинхронно без задержки HTTP-ответа и с контролем 15-минутного SLA по всей очереди ожидания;
  - Асинхронный JSON-аудит в stdout (`AUDIT_ASYNC` в `logback.xml`) и Prometheus (`PrometheusMetricsAdapter`: счётчики, гистограммы `http_request_duration_ms`, `qa_delivery_latency_seconds`, backlog gauges `qa_outbox_pending`, `qa_outbox_oldest_age_seconds`).
- **`infrastructure`** (`AppConfig`, `AppConfigParser`, `DotEnvLoader`, `ApplicationWiring`, `Main`) — загрузка конфигурации, ручное DI-связывание, graceful shutdown и корректная readiness-проба при сбое холодного старта (`AC-23`).

## Документация и проектирование (ПР-3)

Обязательные артефакты ПР-3 (строго по `task3.md`):

- [docs/layered-architecture.md](docs/layered-architecture.md) — 4-слойная архитектура (`flowchart LR`) с привязкой к проблемам прототипа `№01`–`№07`
- [docs/openapi.yaml](docs/openapi.yaml) — контракт API (`OpenAPI 3.0.3`, `200`/`400`/`404`/`405`/`500`/`503` и примеры)
- [docs/c4-container.md](docs/c4-container.md) — диаграмма контейнеров (`C4Container`)
- [docs/c4-component.md](docs/c4-component.md) — компонентная диаграмма контейнера API Service (`C4Component`) и порядок вызовов `1..9`
- [docs/rationale.md](docs/rationale.md) — пояснительная записка (проблемы прототипа → 10 quality attributes → trade-offs)

Дополнительные проектные и эксплуатационные материалы:

- Правила алертинга Prometheus: [docs/monitoring/prometheus-alerts.yml](docs/monitoring/prometheus-alerts.yml)
- Диаграммы Use Cases & Sequence (Mermaid):
  - [Use Cases (UC-01 .. UC-04)](docs/diagrams/03_use_cases.md)
  - [Sequence Diagram (`/api/v1/reviews/classify`)](docs/diagrams/04_sequence_classify.md)
  - [Legacy Diagrams (C4/Layers)](docs/diagrams/01_c4_container.md), [Components/Layers](docs/diagrams/02_components_layers.md)
- Решения (ADR):
  - [ADR-001: JDK HttpServer, Virtual Threads и OpenJDK 25 LTS](docs/adr/ADR-001-http-server-and-java25-lts.md)
  - [ADR-002: Формат словаря (JSON + SHA-256), лимиты и v1.0/v1.1](docs/adr/ADR-002-model-format-and-cold-start.md)
  - [ADR-003: Детерминированное маскирование ПДн](docs/adr/ADR-003-pii-masking-before-model.md)
  - [ADR-004: Runtime Fallback и Durable Outbox + Async Retry](docs/adr/ADR-004-runtime-fallback-and-outbox-sla.md)
- Исходные артефакты ПР-1/ПР-2 и прототип: [docs/artifacts/](docs/artifacts/), [legacy/ReviewClassifier.java](legacy/ReviewClassifier.java).

## Быстрый старт

Требования: **OpenJDK 25 LTS** (`/usr/lib/jvm/java-25-openjdk`), Maven Wrapper (`./mvnw`) или Maven, GNU Make.

```bash
make help          # Список всех команд
make setup         # Установка git-хука, .env из .env.example и кеширование зависимостей
make check         # Полный гейт: компиляция, Spotless, Checkstyle, PMD, SpotBugs, тесты, JaCoCo >= 80%, fat-JAR
make coverage      # Отчёт и проверка порога JaCoCo (>= 80% LINE для domain/application)
make run           # Запуск HTTP-сервера на порту 8080 (или make run PORT=18080)
make smoke         # Проверка /health/live, /health/ready, classify (200/400), /metrics
```

## HTTP Endpoints

| Метод | Путь | Описание |
| --- | --- | --- |
| `POST` | `/api/v1/reviews/classify` | Классификация отзыва (`200`, `400`, `404`, `405` + `Allow`, `503`) |
| `GET` | `/health/live` | Liveness probe (`200 UP`) |
| `GET` | `/health/ready` | Readiness probe (`200 UP` или `503 DOWN`) |
| `GET` | `/metrics`, `/actuator/prometheus` | Метрики и гистограммы в формате Prometheus |
| `*` | Любой другой путь | JSON `404 Not Found` |

## Переменные окружения

См. [.env.example](.env.example):

- `APP_HOST` (`0.0.0.0`), `APP_PORT` (`8080`), `MAX_CONCURRENT_REQUESTS` (`128`), `HTTP_MAX_REQ_TIME_SECONDS` (`10`)
- `MODEL_PATH` (`data/sentiment_v1.json`), `MODEL_SHA256_PATH` (`data/sentiment_v1.json.sha256`), `MODEL_LOAD_TIMEOUT_MS` (`10000`), `INFERENCE_TIMEOUT_MS` (`500`)
- `QA_SERVICE_URL` (пусто — запись в локальный JSONL `OUTBOX_LOG_PATH`; если задан — durable файл-очередь + async HTTP retry), `OUTBOX_LOG_PATH` (`logs/qa_outbox.jsonl`), `SLA_LIMIT_SECONDS` (`900`), `QA_MAX_RETRIES` (`3`), `QA_RETRY_DELAY_MS` (`100`), `QA_BACKGROUND_INTERVAL_SECONDS` (`30`), `QA_HTTP_TIMEOUT_SECONDS` (`5`)
