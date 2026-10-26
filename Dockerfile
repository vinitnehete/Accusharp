# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Wrapper and POM first so the dependency layer caches independently of source.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw -B dependency:go-offline

COPY src/ src/
# Tests run in CI, not here - a build that silently skips them is fine only
# because the pipeline gates on them separately.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run as root. An RCE in the app should not also be host root.
RUN addgroup -S hrms && adduser -S -G hrms hrms
USER hrms

COPY --from=build --chown=hrms:hrms /app/target/hrms-*.jar app.jar

# Documentation for local runs. Render supplies PORT (10000 by default), and
# application.properties maps it to server.port at runtime.
EXPOSE 8080

# Container memory, not a fixed heap - the JVM sizes itself to the cgroup limit
# so the batch paths (attendance generation, whole-company payroll) get what the
# orchestrator actually granted. JAVA_TOOL_OPTIONS is read directly by the JVM,
# which lets the entrypoint stay in exec form without a command shell.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Every one of these is mandatory. The application refuses to start without
# JWT_SECRET, APP_ENCRYPTION_KEY, or (on an empty database)
# HRMS_SEED_PLATFORM_OWNER_PASSWORD - see the fail-closed guards in JwtService,
# EncryptedStringConverter and DataSeeder.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
