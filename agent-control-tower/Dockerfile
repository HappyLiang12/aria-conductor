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
# git + curl power the git pack and shell tools; gh backs git_create_pr (`gh pr create`, #65).
# gh authenticates from GH_TOKEN in the process env, which GitPackHandler already injects.
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates && \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        -o /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list && \
    apt-get update && apt-get install -y --no-install-recommends gh && \
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
