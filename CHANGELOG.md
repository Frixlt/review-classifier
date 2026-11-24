# Changelog

## [1.0.0] - 2026-11-24

### Added
- Микросервис на OpenJDK 25 LTS с Clean / Hexagonal архитектурой (`domain`, `application`, `adapters`, `infrastructure`).
- Встроенный многопоточный HTTP-сервер на базе JDK `HttpServer` и Virtual Threads (`/api/v1/reviews/classify`, `/health/live`, `/health/ready`, `/metrics`).
- Безопасная загрузка словаря весов в JSON с проверкой контрольной суммы SHA-256 (`data/sentiment_v1.json`).
- Маскирование ПДн (`PiiMasker`: `[PHONE]`, `[EMAIL]`, `[NAME]`) до скоринга, аудита и отправки в QA.
- Поддержка Runtime Fallback (`DEC-05`), Outbox + Retry для критичных инцидентов и отслеживание 15-минутного SLA.
- Полный конвейер качества: Spotless, Checkstyle, PMD, SpotBugs, JUnit 5, JaCoCo (>= 80%), Makefile, Docker, GitHub Actions CI и ПР-3 документация.
