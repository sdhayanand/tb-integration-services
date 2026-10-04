# syntax=docker/dockerfile:1
# Build from the REPO ROOT so the common module is available:
#   docker build -f inventory-service/Dockerfile -t ghcr.io/sdhayanand/tb-inventory-service .
# (Jib is the primary image build in CI; this Dockerfile serves docker-compose users.)

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# poms first for dependency caching
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY order-intake-api/pom.xml order-intake-api/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY shipment-webhook/pom.xml shipment-webhook/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl inventory-service -am dependency:go-offline || true
COPY common common
COPY inventory-service inventory-service
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl inventory-service -am -DskipTests package \
 && cp inventory-service/target/inventory-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8081/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
