# syntax=docker/dockerfile:1
# Build from the REPO ROOT:  docker build -f shipment-webhook/Dockerfile -t ghcr.io/sdhayanand/tb-shipment-webhook .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY order-intake-api/pom.xml order-intake-api/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY shipment-webhook/pom.xml shipment-webhook/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl shipment-webhook -am dependency:go-offline || true
COPY common common
COPY shipment-webhook shipment-webhook
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl shipment-webhook -am -DskipTests package \
 && cp shipment-webhook/target/shipment-webhook-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
