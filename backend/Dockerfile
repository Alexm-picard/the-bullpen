# syntax=docker/dockerfile:1.7

# ---------- builder ----------
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

# Cache Gradle wrapper + deps first
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --version

COPY src src
COPY config config
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*.jar /workspace/app.jar

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-alpine AS runtime
RUN apk add --no-cache curl tini \
    && addgroup -S -g 1001 appgroup \
    && adduser  -S -G appgroup -u 1001 appuser

WORKDIR /app
COPY --from=builder /workspace/app.jar /app/app.jar
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

USER appuser

ENV BULLPEN_REGISTRY_DB=/app/data/registry.sqlite \
    LOG_FORMAT=json \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080 8081

HEALTHCHECK --interval=15s --timeout=3s --retries=4 --start-period=20s \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "/app/app.jar"]
CMD ["--spring.profiles.active=api"]
