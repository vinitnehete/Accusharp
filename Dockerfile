# ---- build ----------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Wrapper and POM first so the dependency layer caches independently of source.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
# Tests run in CI, not here - a build that silently skips them is fine only
# because the pipeline gates on them separately.
RUN ./mvnw -B -DskipTests package

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run as root. An RCE in the app should not also be host root.
RUN addgroup -S hrms && adduser -S -G hrms hrms
USER hrms

COPY --from=build --chown=hrms:hrms /app/target/hrms-*.jar app.jar

EXPOSE 8080

# Container memory, not a fixed heap - the JVM sizes itself to the cgroup limit
# so the batch paths (attendance generation, whole-company payroll) get what the
# orchestrator actually granted.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Every one of these is mandatory. The application refuses to start without
# JWT_SECRET, APP_ENCRYPTION_KEY, or (on an empty database)
# HRMS_SEED_PLATFORM_OWNER_PASSWORD - see the fail-closed guards in JwtService,
# EncryptedStringConverter and DataSeeder.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
