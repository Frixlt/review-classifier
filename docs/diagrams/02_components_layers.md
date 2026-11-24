# Clean / Hexagonal Architecture — Layer & Dependency Rule

Стрелки отражают правило зависимостей (Dependency Rule): внешние слои зависят от `application` и `domain`, тогда как `domain` и `application` не имеют зависимостей от фреймворков, HTTP и Jackson.

```mermaid
flowchart TD
    subgraph Infrastructure ["Infrastructure Layer"]
        Main["Main & AppConfig"]
        Wiring["ApplicationWiring"]
    end

    subgraph Inbound ["Adapters Inbound (HTTP)"]
        Server["ReviewHttpServer"]
        ClassifyHandler["ClassifyReviewHandler"]
        HealthHandler["HealthHandler"]
        MetricsHandler["MetricsHandler"]
    end

    subgraph Outbound ["Adapters Outbound"]
        WeightsLoader["JsonModelWeightsLoader"]
        QaAdapter["RetryableQaAdapter\n+ FileOutboxStore + HttpIncidentSender"]
        AuditLogger["JsonStdoutAuditLogger"]
        MetricsAdapter["PrometheusMetricsAdapter\n(MetricsPort + MetricsExporter)"]
        ClockAdapter["SystemClockAdapter"]
    end

    subgraph Application ["Application Layer"]
        UseCase["ClassifyReviewUseCase"]
    end

    subgraph Domain ["Domain Layer (Pure Java 25)"]
        Ports["Domain Ports\n(ModelWeights, QA, AuditLog, Metrics, Clock)"]
        Services["Domain Services\n(Validator, PiiMasker, Preprocessor,\nSentimentScoring, IssueDetector, CriticalityEvaluator)"]
        Models["Immutable Records & Exceptions"]
    end

    Main --> Wiring
    Wiring --> Server
    Wiring --> UseCase
    Wiring --> WeightsLoader
    Wiring --> QaAdapter
    Wiring --> AuditLogger
    Wiring --> MetricsAdapter
    Wiring --> ClockAdapter

    Server --> ClassifyHandler
    Server --> HealthHandler
    Server --> MetricsHandler
    ClassifyHandler --> UseCase
    HealthHandler --> Ports
    MetricsHandler --> MetricsAdapter

    UseCase --> Services
    UseCase --> Ports
    WeightsLoader -.->|"implements"| Ports
    QaAdapter -.->|"implements"| Ports
    AuditLogger -.->|"implements"| Ports
    MetricsAdapter -.->|"implements"| Ports
    ClockAdapter -.->|"implements"| Ports
    Services --> Models
```
