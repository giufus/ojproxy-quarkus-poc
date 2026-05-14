## Why

We need a reference proof-of-concept that validates **OpenJ Proxy (OJP) 0.4.14-beta** as a JDBC intermediary for Quarkus 3.x services using Panache with PostgreSQL. Before adopting OJP for any real workload we want to confirm two things in code: (1) a non-reactive Quarkus + Hibernate ORM Panache stack can talk to Postgres *entirely* through the OJP driver (no native Postgres driver in the client), and (2) one OJP server can transparently serve multiple independent services that live in **different Postgres schemas** of the same database.

## What Changes

- Add a `user-service` Quarkus Maven application (Java 25) with a single `User` entity backed by Panache + Hibernate ORM, exposing CRUD REST endpoints under `/users`.
- Add a `product-service` Quarkus Maven application (Java 25) with a single `Product` entity backed by Panache + Hibernate ORM, exposing CRUD REST endpoints under `/products`.
- Route both services through OJP using the `org.openjproxy:ojp-jdbc-driver:0.4.14-beta` driver and the JDBC URL pattern `jdbc:ojp[localhost:1059]_postgresql://postgres:5432/appdb`. **The native Postgres JDBC driver is intentionally NOT on the client classpath.**
- Provide a `compose/compose.yaml` stack (compatible with both `podman compose` and `docker compose`) containing:
  - a single Postgres 16 container (`appdb`, two schemas: `user_svc`, `product_svc`, one DB user per schema);
  - one OJP server container (`rrobetti/ojp:0.4.14-beta`) with the Postgres JDBC driver JAR bind-mounted into `/opt/ojp/ojp-libs`.
- Provide a top-level `justfile` with tasks for: `up`, `down`, `logs`, `init-ojp-libs`, `dev-user`, `dev-product`, `test`, `it`, `build`, `clean`.
- Provide unit tests (surefire, `*Test.java`) and integration tests (maven-failsafe, `*IT.java` using `@QuarkusIntegrationTest` + REST-assured) for both services. Integration tests assume the compose stack is already up — **no Quarkus DevServices and no Testcontainers** are used at this stage.
- Configure ports: `user-service` on **8080** (dev/prod) and **9091** (test profile); `product-service` on **8082** (dev/prod) and **9092** (test profile). Quarkus's default test port 8081 is deliberately avoided to keep test runs from clashing with any other dev process.
- Add a top-level `README.md` covering prerequisites, quickstart, and OJP-specific troubleshooting (libs bind-mount, schema permissions).

## Capabilities

### New Capabilities
- `user-service`: REST CRUD over a `User` entity (Panache + Postgres-via-OJP), running on schema `user_svc`.
- `product-service`: REST CRUD over a `Product` entity (Panache + Postgres-via-OJP), running on schema `product_svc`.
- `ojp-data-access`: shared client-side and infrastructure contract for reaching Postgres through OJP — JDBC URL convention, driver class, server image/port, libs bind-mount.
- `local-dev-stack`: compose stack (`postgres` + `ojp`) and `justfile` workflow that lets a developer bring up the backend and run either service in dev mode with a single command.

### Modified Capabilities
<!-- None: this is a greenfield POC; no existing specs to amend. -->

## Impact

- **Code**: introduces two brand-new Quarkus Maven modules (`user-service/`, `product-service/`) and a `compose/` directory at the repo root. No existing code is touched.
- **Dependencies**: each service adds `org.openjproxy:ojp-jdbc-driver:0.4.14-beta`; **does not** add `quarkus-jdbc-postgresql`. Hibernate dialect is pinned to `org.hibernate.dialect.PostgreSQLDialect`. Postgres JDBC driver JAR (42.7.x) is required by the OJP server side only and is fetched at workflow time by `just init-ojp-libs`.
- **Infrastructure**: two new container images pulled locally — `postgres:16-alpine` and `rrobetti/ojp:0.4.14-beta`. Two host ports exposed: `1059` (OJP), `5432` (Postgres, for `psql` probing — removable later).
- **Tooling**: developers must have Java 25, Maven, `just`, and either `podman` or `docker` available locally. The justfile auto-selects the compose engine.
- **Out of scope (non-goals)**: authentication/authorization, TLS to OJP, observability beyond Quarkus defaults, performance/load testing, multi-region, devservices, testcontainers, and any production hardening of the compose stack.
