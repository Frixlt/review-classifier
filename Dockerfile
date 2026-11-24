# ============================================================
# Stage 1: Builder — resolve dependencies & build fat-JAR
# ============================================================
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY config/ config/
COPY src/ src/
RUN ./mvnw -B package -DskipTests

# ============================================================
# Stage 2: Runner — minimal non-root Java 25 JRE image
# ============================================================
FROM eclipse-temurin:25-jre-alpine AS runner

WORKDIR /app

RUN addgroup -S app && adduser -S -G app -h /app -s /sbin/nologin app

COPY --from=builder /build/target/review-classifier-1.0.0.jar /app/app.jar
COPY data/ /opt/models/

RUN mkdir -p /app/logs && chown -R app:app /app /opt/models

USER app

ENV APP_HOST=0.0.0.0 \
    APP_PORT=8080 \
    MODEL_PATH=/opt/models/sentiment_v1.json \
    MODEL_SHA256_PATH=/opt/models/sentiment_v1.json.sha256 \
    OUTBOX_LOG_PATH=/app/logs/qa_outbox.jsonl \
    SLA_LIMIT_SECONDS=900 \
    LOG_LEVEL=INFO

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://localhost:8080/health/live >/dev/null 2>&1 || exit 1

ENTRYPOINT ["java", "-Dsun.net.httpserver.maxReqTime=10", "-Dsun.net.httpserver.maxRspTime=10", "-XX:+UseZGC", "-Xmx256m", "-jar", "/app/app.jar"]
