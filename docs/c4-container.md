# C4 Container Diagram

Контейнерная диаграмма уровня **C4 Container** показывает внешние акторы, внешние системы и внутренние логические контейнеры (подсистемы) решения, а также протоколы их взаимодействия.

```mermaid
C4Container
    title Review Sentiment Classifier — C4 Container Diagram

    Person(customerPortal, "E-Commerce Platform", "Отправляет отзывы покупателей сразу после публикации")
    Person(sreOps, "SRE / Data Science", "Отслеживает SLA 15 мин, p95/p99 latency и fallback rate")

    System_Boundary(reviewSystem, "Review Classifier System (Java 25 LTS Container)") {
        Container(apiService, "API Service", "JDK HttpServer + Virtual Threads", "Принимает HTTP POST, проверяет контракт (400/404/405/503), ограничивает конкурентность и отдаёт health/metrics")
        Container(useCaseEngine, "Classification & PII Engine", "Pure Java 25 (Domain + Application)", "Маскирует ПДн (PiiMasker), нормализует текст, вычисляет тональность (таймаут 500 мс + Fallback) и критичность")
        Container(modelAdapter, "Model Adapter & Cache", "JsonModelWeightsLoader (Jackson + SHA-256)", "Безопасно загружает словарь весов <= 10с и хранит immutable Map в памяти")
        Container(outboxWorker, "Durable Outbox & Async Retry Worker", "RetryableQaAdapter + FileOutboxStore", "Персистит критичные инциденты (fsync), восстанавливает после рестарта и проверяет 15-мин SLA")
        Container(auditMetrics, "Audit & Metrics Subsystem", "Logback AUDIT_ASYNC + PrometheusMetricsAdapter", "Формирует асинхронный JSON-аудит без ПДн и гистограммы/счётчики Prometheus")
    }

    ContainerDb(modelStorage, "Model Dictionary Volume", "JSON + .sha256 (ro)", "data/sentiment_v1.json и контрольная сумма SHA-256")
    ContainerDb(outboxFile, "Durable Outbox Journal", "Append/Atomic JSONL", "logs/qa_outbox.jsonl — сохранённые неподтверждённые карточки QA")

    System_Ext(qaService, "Quality Assurance Service", "Внешний HTTP-сервис разбора критических жалоб (QA_SERVICE_URL)")
    System_Ext(logStorage, "Centralized Log Collector", "Собирает структурированный JSON-аудит и алерты из stdout")
    System_Ext(monitoring, "Prometheus & Alertmanager", "Скрейпит метрики и оценивает правила из prometheus-alerts.yml")

    Rel(customerPortal, apiService, "Отправляет отзыв на классификацию", "HTTPS / JSON POST")
    Rel(apiService, useCaseEngine, "Передаёт валидированный ReviewInput", "In-process call")
    Rel(useCaseEngine, modelAdapter, "Читает веса словаря", "ModelWeightsPort")
    Rel(modelAdapter, modelStorage, "Читает и сверяет SHA-256 при старте", "Local File I/O")

    Rel(useCaseEngine, outboxWorker, "Передаёт критичный IncidentCard", "QualityAssurancePort")
    Rel(outboxWorker, outboxFile, "Персистит и восстанавливает очередь", "FileChannel force(true)")
    Rel(outboxWorker, qaService, "Асинхронная доставка с X-Idempotency-Key", "HTTP POST / JSON")

    Rel(useCaseEngine, auditMetrics, "Пишет аудит и бизнес-события", "AuditLogPort / MetricsPort")
    Rel(apiService, auditMetrics, "Фиксирует HTTP-latency", "MetricsExporter")
    Rel(auditMetrics, logStorage, "Выводит однострочный JSON", "stdout (ASYNC)")
    Rel(monitoring, apiService, "Собирает метрики и пробы", "HTTP GET text/plain & JSON")
    Rel(sreOps, monitoring, "Контролирует дашборды и алерты SLA", "HTTPS")
```
