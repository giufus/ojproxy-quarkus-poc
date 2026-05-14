## 1. Repo skeleton

- [x] 1.1 Create top-level directories: `compose/`, `compose/postgres/`, `compose/ojp-libs/`, `user-service/`, `product-service/`
- [x] 1.2 Add a `.gitignore` covering `target/`, `*.class`, `.idea/`, `.vscode/`, `compose/ojp-libs/*.jar`, `compose/.env`
- [x] 1.3 Add an empty placeholder `compose/ojp-libs/.gitkeep` so the bind-mount target is tracked

## 2. Compose stack

- [x] 2.1 Write `compose/postgres/init.sql` that creates users `user_svc` / `product_svc`, schemas `user_svc` / `product_svc` (each owned by the matching user), and grants `CONNECT` on `appdb`
- [x] 2.2 Write `compose/compose.yaml` with a `postgres` service (`postgres:16-alpine`, env `POSTGRES_DB=appdb`, `POSTGRES_USER=appadmin`, `POSTGRES_PASSWORD=appadmin`, healthcheck `pg_isready -U appadmin -d appdb`, port `5432:5432`, named volume `pgdata`, init.sql mounted at `/docker-entrypoint-initdb.d/init.sql`)
- [x] 2.3 Add an `ojp` service to `compose.yaml` (`rrobetti/ojp:0.4.14-beta`, env `OJP_PORT=1059`, port `1059:1059`, `depends_on: postgres (condition: service_healthy)`, bind-mount `./ojp-libs` to `/opt/ojp/ojp-libs`)
- [x] 2.4 Verify the file has no `version:` key and uses only keys supported by both `podman compose` and `docker compose`

## 3. Justfile

- [x] 3.1 Create `justfile` at the repo root with a `default` recipe that runs `just --list`
- [x] 3.2 Add `init-ojp-libs` recipe: idempotent `curl` of `postgresql-42.7.4.jar` from Maven Central into `compose/ojp-libs/` (skip if already present)
- [x] 3.3 Add `up` recipe that depends on `init-ojp-libs` and runs `${COMPOSE} -f compose/compose.yaml up -d`
- [x] 3.4 Add `down` recipe that runs `${COMPOSE} -f compose/compose.yaml down -v`
- [x] 3.5 Add `logs` recipe accepting an optional service name (default `ojp`)
- [x] 3.6 Add `dev-user` and `dev-product` recipes that run `mvn -f <service>/pom.xml quarkus:dev`
- [x] 3.7 Add `test`, `build`, `clean` recipes that run the corresponding Maven goals on both services in sequence
- [x] 3.7a Add `it` recipe that first runs a precheck (`pg_isready -h localhost -U appadmin -d appdb` AND `nc -z localhost 1059`) and exits non-zero with the message "stack not up — run 'just up' first" if either fails; only then runs `mvn -f user-service/pom.xml verify` followed by `mvn -f product-service/pom.xml verify`. **MUST NOT** invoke `just up` or `just down` itself
- [x] 3.8 Implement the compose-engine auto-detection (`COMPOSE` variable: prefer `podman compose` if `podman` is on PATH, else `docker compose`)

## 4. user-service

- [x] 4.1 Bootstrap `user-service/pom.xml` against `io.quarkus.platform:quarkus-bom:3.33.<latest-patch>` (Quarkus 3.33 LTS). Required extensions: `quarkus-resteasy-reactive-jackson`, `quarkus-hibernate-orm-panache`, `quarkus-arc`. **Do NOT add `quarkus-jdbc-postgresql`.**
- [x] 4.2 Add a direct dependency on `org.openjproxy:ojp-jdbc-driver:0.4.14-beta`
- [x] 4.3 Configure `maven.compiler.release=25` in the pom. If `mvn package` or the first `quarkus:dev` boot fails on JDK 25 (OJP/gRPC/Netty incompatibility), fall back to `maven.compiler.release=21` keeping Quarkus 3.33 LTS unchanged, and note the fallback in the README
- [x] 4.4 Add `maven-failsafe-plugin` bound to `integration-test` + `verify` phases, including `*IT.java` and excluding it from surefire
- [x] 4.5 Write `src/main/resources/application.properties`:
  - `quarkus.http.port=8080`
  - `%test.quarkus.http.port=9091`
  - `quarkus.datasource.db-kind=other`
  - `quarkus.datasource.jdbc.driver=org.openjproxy.jdbc.Driver`
  - `quarkus.datasource.jdbc.url=jdbc:ojp[localhost:1059]_postgresql://postgres:5432/appdb`
  - `quarkus.datasource.username=user_svc`
  - `quarkus.datasource.password=user_svc`
  - `quarkus.hibernate-orm.dialect=org.hibernate.dialect.PostgreSQLDialect`
  - `quarkus.hibernate-orm.database.default-schema=user_svc`
  - `quarkus.hibernate-orm.database.generation=update`
- [x] 4.6 Implement `User` Panache entity (fields: `id`, `name`, `email`, `createdAt`) in package `com.example.user`
- [x] 4.7 Implement `UserResource` with `GET/POST/PUT/DELETE /users[/{id}]`, returning the status codes specified in `specs/user-service/spec.md`
- [x] 4.8 Write at least one unit test `UserMappingTest.java` (no Quarkus context, just JUnit) under `src/test/java/...`
- [x] 4.9 Write `UserResourceIT.java` annotated `@QuarkusIntegrationTest` that performs a CRUD round-trip with REST-assured against port `9091`

## 5. product-service

- [x] 5.1 Bootstrap `product-service/pom.xml` mirroring `user-service` (Quarkus 3.33 LTS BOM, same `maven.compiler.release` value chosen in task 4.3, same extensions, same OJP driver dep, same maven-failsafe config)
- [x] 5.2 Write `src/main/resources/application.properties` with `quarkus.http.port=8082`, `%test.quarkus.http.port=9092`, datasource username/password/default-schema set to `product_svc`, all other datasource keys identical to `user-service`
- [x] 5.3 Implement `Product` Panache entity (fields: `id`, `name`, `sku` (unique), `priceCents`, `createdAt`) in package `com.example.product`
- [x] 5.4 Implement `ProductResource` with `GET/POST/PUT/DELETE /products[/{id}]`, including the `409 Conflict` on duplicate SKU
- [x] 5.5 Write at least one unit test `ProductMappingTest.java` under `src/test/java/...`
- [x] 5.6 Write `ProductResourceIT.java` annotated `@QuarkusIntegrationTest` that performs a CRUD round-trip against port `9092` (including the duplicate-SKU 409 scenario)

## 6. README

- [x] 6.1 Write top-level `README.md` covering: project goal (one paragraph), prerequisites (Java 25 — or 21 per fallback, Maven 3.9+, `just`, podman or docker), quickstart (`just init-ojp-libs && just up && just dev-user`), per-service ports (dev 8080/8082, test 9091/9092), common `just` recipes, the **Postgres-5432 port-collision warning** (advise stopping any local Postgres or remapping in compose.yaml), and troubleshooting (libs mount missing, port 1059 busy, schema permissions, JDK-25 → JDK-21 fallback procedure)

## 7. End-to-end verification

- [x] 7.1 From a clean state, run `just init-ojp-libs` and confirm `compose/ojp-libs/postgresql-42.7.4.jar` exists
- [x] 7.2 Run `just up`; confirm `docker ps` / `podman ps` shows `postgres` and `ojp` healthy; confirm `nc -z localhost 1059` succeeds
- [x] 7.3 Run `just dev-user` in one shell, hit `POST/GET/PUT/DELETE http://localhost:8080/users` with curl, confirm 201/200/200/204 responses
- [x] 7.4 Run `just dev-product` in another shell, hit `POST/GET/PUT/DELETE http://localhost:8082/products` with curl, including a duplicate-SKU POST to confirm the 409 path
- [x] 7.5 Connect with `psql -h localhost -U appadmin -d appdb` and run `\dt user_svc.*; \dt product_svc.*` to confirm tables landed in the correct schemas
- [x] 7.6 Run `just test` — surefire unit tests pass for both services
- [x] 7.7 Run `just it` — failsafe integration tests pass for both services against the live stack
- [x] 7.8 Run `just down` — containers stopped, volumes removed; a subsequent `just up` performs a fresh Postgres init
