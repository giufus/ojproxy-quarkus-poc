# local-dev-stack Specification

## Purpose
TBD - created by archiving change init-ojproxy-quarkus-poc. Update Purpose after archive.
## Requirements
### Requirement: Compose Stack Provides Postgres And OJP

The repository SHALL contain a compose definition at `compose/compose.yaml` that, when started, brings up exactly two services: a Postgres 16 backend and an OJP 0.4.14-beta server. The compose file MUST be compatible with both `podman compose` and `docker compose` and MUST NOT use engine-specific extensions.

#### Scenario: Compose starts both services

- **WHEN** a developer runs `just up` from a clean checkout (after `just init-ojp-libs`)
- **THEN** `compose/compose.yaml` brings up containers named `postgres` (image `postgres:16-alpine`) and `ojp` (image `rrobetti/ojp:0.4.14-beta`), both report a healthy / running status, and the Postgres container has finished executing `init.sql`

#### Scenario: Compose runs under either engine

- **WHEN** the developer has only `podman` installed
- **THEN** `just up` invokes `podman compose -f compose/compose.yaml up -d` and succeeds
- **WHEN** the developer has only `docker` installed
- **THEN** `just up` invokes `docker compose -f compose/compose.yaml up -d` and succeeds

### Requirement: Postgres Is Initialised With Two Schemas And Two Users

On first start, the Postgres container SHALL execute `compose/postgres/init.sql` (mounted into `/docker-entrypoint-initdb.d/`) which creates database `appdb`, users `user_svc` and `product_svc`, and schemas `user_svc` (owned by user `user_svc`) and `product_svc` (owned by user `product_svc`). The Postgres superuser is `appadmin`.

#### Scenario: Schemas exist after first boot

- **WHEN** the stack is brought up for the first time
- **THEN** `psql -h localhost -U appadmin -d appdb -c '\dn'` lists schemas `user_svc` and `product_svc` owned by their respective users

#### Scenario: Each service user can only manage its own schema

- **WHEN** a connection authenticated as `user_svc` attempts to create a table inside schema `product_svc`
- **THEN** Postgres rejects the operation with a permission-denied error

### Requirement: Justfile Provides The Standard Developer Workflow

The repository SHALL contain a top-level `justfile` that defines, at minimum, the following recipes: `default` (list recipes), `init-ojp-libs`, `up`, `down`, `logs`, `dev-user`, `dev-product`, `test`, `it`, `build`, `clean`. The `up` recipe MUST depend on `init-ojp-libs` so a fresh checkout works in one command.

#### Scenario: Listing recipes

- **WHEN** a developer runs `just` with no arguments
- **THEN** `just --list` output is printed and includes all recipes named above

#### Scenario: Running a service in dev mode

- **WHEN** the developer runs `just dev-user`
- **THEN** Maven launches `quarkus:dev` for `user-service` and the service binds to port `8080`
- **WHEN** the developer runs `just dev-product` in another shell
- **THEN** Maven launches `quarkus:dev` for `product-service` and the service binds to port `8082` without colliding with `user-service`

#### Scenario: Running tests

- **WHEN** the developer runs `just test`
- **THEN** maven-surefire executes unit tests for both services and exits with success
- **WHEN** the compose stack is up and the developer runs `just it`
- **THEN** maven-failsafe executes the `*IT.java` integration tests for both services against the live OJP + Postgres backend and exits with success

#### Scenario: `just it` precheck when stack is down

- **WHEN** the compose stack is NOT running and the developer runs `just it`
- **THEN** the recipe exits non-zero before invoking Maven, prints a message instructing the developer to run `just up` first, and does NOT attempt to start the stack itself

#### Scenario: Bringing the stack down

- **WHEN** the developer runs `just down`
- **THEN** the compose stack is stopped, containers are removed, and named volumes are removed (`-v` flag); a subsequent `just up` triggers a fresh Postgres init

### Requirement: README Documents Prerequisites And Quickstart

The repository SHALL contain a top-level `README.md` documenting required local tooling (Java 25, Maven 3.9+, `just`, `podman` or `docker`), a quickstart sequence (`just init-ojp-libs && just up && just dev-user`), and troubleshooting notes for OJP-specific failure modes (missing libs mount, Postgres schema permissions, port 1059 already in use).

#### Scenario: First-time developer follows quickstart

- **WHEN** a developer with the prerequisites installed follows the README quickstart from a clean checkout
- **THEN** they reach a running `user-service` responding `200 OK` on `GET http://localhost:8080/users` without consulting any other documentation

