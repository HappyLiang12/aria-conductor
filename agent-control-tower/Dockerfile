# Aria Conductor Backend - Multi-stage Docker Build
# Build: Maven + JDK 21 -> Runtime: JRE 21

# Stage 1: Cache dependencies
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
# Copy only POM files first for layer caching
COPY pom.xml .
COPY act-common/pom.xml act-common/
COPY act-agent/pom.xml act-agent/
COPY act-execution/pom.xml act-execution/
COPY act-knowledge/pom.xml act-knowledge/
COPY act-aria/pom.xml act-aria/
COPY act-dashboard-api/pom.xml act-dashboard-api/
COPY act-app/pom.xml act-app/
COPY act-test-support/pom.xml act-test-support/
RUN mvn dependency:go-offline -B -pl act-app -am || true

# Stage 2: Build
FROM deps AS build
COPY . .
RUN mvn package -DskipTests -pl act-app -am -B

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends git curl && \
    rm -rf /var/lib/apt/lists/*
RUN groupadd -r aria && useradd -r -g aria -s /bin/false aria
RUN mkdir -p /workspaces && chown aria:aria /workspaces
USER aria
WORKDIR /app
COPY --from=build /build/act-app/target/act-app-*.jar /app/aria-conductor.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=60s \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
  "--enable-preview", "-jar", "/app/aria-conductor.jar"]
