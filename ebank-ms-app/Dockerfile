# syntax=docker/dockerfile:1
# Multi-stage image shared by every module:
#   docker build --build-arg MODULE=<service> --build-arg PORT=<port> .
# (docker-compose.yml wires the build args per service)
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre

# ---------------------------------------------------------------- build stage
FROM ${MAVEN_IMAGE} AS build
ARG MODULE
WORKDIR /build

# 1) pom layer (re-run only when the pom changes); deps live in a shared
#    BuildKit cache mount, so all 5 modules reuse one local repository
COPY ${MODULE}/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests dependency:go-offline || true

# 2) sources + package
COPY ${MODULE}/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package && \
    cp target/*.jar /build/app.jar

# -------------------------------------------------------------- runtime stage
FROM ${JRE_IMAGE} AS runtime
ARG MODULE
ARG PORT=8080

# curl is needed by the compose healthchecks (/actuator/health)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 1001 --create-home --shell /usr/sbin/nologin spring

# The app resolves its config relatively, exactly like in the IDE:
#   WORKDIR /app/<module>  ->  ../config-repo  and  ../.env
# compose mounts ./config-repo at /app/config-repo and injects secrets as
# real OS env vars (so ../.env is intentionally absent -> optional import no-ops).
WORKDIR /app/${MODULE}

COPY --from=build /build/app.jar ./app.jar

EXPOSE ${PORT}

USER spring

# MaxRAMPercentage: size the heap from the container cgroup limit (prod overlay
# sets deploy.resources.limits); ExitOnOutOfMemoryError: fail fast so the
# restart policy can recover the container.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
