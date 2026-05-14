set shell := ["bash", "-uc"]

# Prefer `podman compose` if podman is on PATH, else fall back to `docker compose`.
COMPOSE := `(command -v podman >/dev/null 2>&1 && echo "podman compose") || echo "docker compose"`

PG_JDBC_VERSION := "42.7.4"
PG_JDBC_URL := "https://repo1.maven.org/maven2/org/postgresql/postgresql/" + PG_JDBC_VERSION + "/postgresql-" + PG_JDBC_VERSION + ".jar"
PG_JDBC_JAR := "compose/ojp-libs/postgresql-" + PG_JDBC_VERSION + ".jar"

# Default recipe: list everything.
default:
    @just --list

# Download the Postgres JDBC driver jar into compose/ojp-libs/ (idempotent).
init-ojp-libs:
    @mkdir -p compose/ojp-libs
    @if [ -f "{{PG_JDBC_JAR}}" ]; then \
        echo "✔ {{PG_JDBC_JAR}} already present"; \
    else \
        echo "→ downloading postgresql-{{PG_JDBC_VERSION}}.jar ..."; \
        curl -fsSL -o "{{PG_JDBC_JAR}}" "{{PG_JDBC_URL}}"; \
        echo "✔ downloaded {{PG_JDBC_JAR}}"; \
    fi

# Bring the compose stack up (auto-runs init-ojp-libs first).
up: init-ojp-libs
    {{COMPOSE}} -f compose/compose.yaml up -d
    @echo "stack up — postgres on :5432, ojp on :1059"

# Bring the compose stack down and remove the named pgdata volume.
down:
    {{COMPOSE}} -f compose/compose.yaml down -v

# Tail compose logs for a service (default: ojp).
logs svc='ojp':
    {{COMPOSE}} -f compose/compose.yaml logs -f {{svc}}

# Run user-service in dev mode (binds :8080).
dev-user:
    mvn -f user-service/pom.xml quarkus:dev

# Run product-service in dev mode (binds :8082).
dev-product:
    mvn -f product-service/pom.xml quarkus:dev

# Run both services in dev mode concurrently (Ctrl-C stops both).
dev-all:
    #!/usr/bin/env bash
    set -e
    trap 'kill $(jobs -p) 2>/dev/null; exit' INT TERM EXIT
    mvn -f user-service/pom.xml quarkus:dev &
    mvn -f product-service/pom.xml quarkus:dev &
    wait

# Run unit tests for both services (no stack required).
test:
    mvn -f user-service/pom.xml test
    mvn -f product-service/pom.xml test

# Run integration tests for both services (precheck + mvn verify; requires `just up`).
it:
    @echo "→ precheck: postgres on :5432 and ojp on :1059"
    @bash -c 'exec 3<>/dev/tcp/localhost/5432' >/dev/null 2>&1 \
        || { echo "✘ stack not up — run 'just up' first (postgres unreachable on :5432)"; exit 1; }
    @bash -c 'exec 3<>/dev/tcp/localhost/1059' >/dev/null 2>&1 \
        || { echo "✘ stack not up — run 'just up' first (ojp unreachable on :1059)"; exit 1; }
    @echo "✔ stack reachable"
    mvn -f user-service/pom.xml verify
    mvn -f product-service/pom.xml verify

# Package both services.
build:
    mvn -f user-service/pom.xml package
    mvn -f product-service/pom.xml package

# Clean both services.
clean:
    mvn -f user-service/pom.xml clean
    mvn -f product-service/pom.xml clean
