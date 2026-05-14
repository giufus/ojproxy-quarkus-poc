## Context

This change introduces a brand-new POC repository — there is no prior code, no Maven parent, no existing CI. The motivation is documented in `proposal.md`: validate that Quarkus + Panache can run entirely on the OJP JDBC driver against a real Postgres backend, with one OJP instance fronting multiple services that live in distinct schemas.

Two pieces of external context constrain the design:

1. **OJP 0.4.x server requires backend drivers to be bind-mounted** into `/opt/ojp/ojp-libs` (per the OJP GitHub README for versions ≥ 0.4.0-beta). The OJP image does NOT ship with Postgres / MySQL / Oracle drivers. This means our compose file must mount a host directory containing `postgresql-42.7.x.jar`, and the build workflow must populate that directory.

2. **OJP JDBC URL syntax** is `jdbc:ojp[<ojp-host>:<ojp-port>]_<native-jdbc-url>` and the driver class is `org.openjproxy.jdbc.Driver`. The native URL inside the brackets is resolved on the OJP server side, so the host name there must be reachable from the OJP container (e.g. the compose service name `postgres`), NOT from the Quarkus process.

Stakeholder: the user (Giuseppe). Sole consumer for now. Decisions should optimize for "I can clone this repo, run `just up && just dev-user`, and see CRUD work."

## Goals / Non-Goals

**Goals:**
- Two independently-runnable Quarkus 3.x apps (`user-service`, `product-service`) on Java 25 with Panache + Hibernate ORM, talking to a shared Postgres via OJP.
- Schema-per-service isolation in a single Postgres database (`appdb`).
- A single `justfile` that drives the full dev loop (`up`, `down`, `dev-user`, `dev-product`, `test`, `it`, `build`, `init-ojp-libs`).
- A compose file that works with both `podman compose` and `docker compose` without modification.
- Unit tests (surefire) and integration tests (`@QuarkusIntegrationTest` + REST-assured, maven-failsafe) for each service, runnable via `just test` / `just it`.

**Non-Goals:**
- Quarkus DevServices, Testcontainers, ephemeral DBs — explicitly excluded per user direction. Integration tests rely on the developer having run `just up` beforehand.
- Authentication / authorization (services are open).
- TLS between client and OJP, or between OJP and Postgres.
- Schema migrations via Flyway/Liquibase — `hibernate-orm.database.generation=update` is sufficient for a POC.
- A Maven multi-module aggregator parent — the two services are independent siblings.
- Observability beyond Quarkus' default `/q/health` and console logging.
- Production hardening of the compose stack (resource limits, restart policies, secrets management).

## Decisions

### D1. Two independent Maven projects, no aggregator parent
Each service has its own `pom.xml` extending `io.quarkus.platform:quarkus-bom`. There is no `pom.xml` at the repo root.
- **Rationale**: simpler for a POC; each service is self-contained and can be packaged in isolation. The justfile sequences the two `mvn` invocations explicitly.
- **Alternative considered**: a parent pom with `<modules>`. Rejected because it adds a layer that this POC does not need and would force IDE imports to pull in both services together.

### D1b. Quarkus 3.33 LTS on Java 25 (Java 21 fallback)
Both services pin `io.quarkus.platform:quarkus-bom:3.33.x` (latest patch of the 3.33 LTS line) and `maven.compiler.release=25`. If at `/opsx:apply` time we discover OJP 0.4.14-beta is not compatible with JDK 25 (driver class fails to load, gRPC stack errors, or Quarkus 3.33 + `db-kind=other` refuses to boot), fall back to `maven.compiler.release=21` keeping the same Quarkus 3.33 LTS BOM.
- **Rationale**: LTS BOM gives us patch coverage; Java 25 matches the user's original ask. Java 21 is the safe net because Quarkus 3.33 LTS officially supports both 21 and 25.
- **Alternative considered**: latest non-LTS (e.g. Quarkus 3.34/3.35). Rejected — POC repos benefit from LTS so the pin doesn't go stale within weeks.

### D2. Native Postgres JDBC driver is OFF the client classpath
Neither service depends on `quarkus-jdbc-postgresql` or `org.postgresql:postgresql`. The only JDBC driver visible to Quarkus is OJP.
- **Rationale**: this is the whole point of the POC. If the native driver is present, we cannot prove the path-through-OJP actually works.
- **Alternative considered**: keeping both drivers and letting Quarkus pick. Rejected — it would mask configuration bugs.

### D3. Datasource configured as `db-kind=other`
`application.properties` uses `quarkus.datasource.db-kind=other` plus `quarkus.datasource.jdbc.driver=org.openjproxy.jdbc.Driver`, and Hibernate is told the dialect explicitly via `quarkus.hibernate-orm.dialect=org.hibernate.dialect.PostgreSQLDialect`.
- **Rationale**: Quarkus' built-in `db-kind=postgresql` would try to pull in the Postgres driver and infer dialect from it. Since the native driver is intentionally absent (D2), we sidestep all of that with `other` and pin the dialect by hand.
- **Alternative considered**: `db-kind=postgresql` with `quarkus.datasource.jdbc.driver` overridden. Rejected — Quarkus may still try to validate / introspect using the native driver and fail at build time.

### D4. Single Postgres, two schemas, two DB users
Postgres exposes a database `appdb`. `init.sql` (mounted into `/docker-entrypoint-initdb.d/`) creates two users (`user_svc`, `product_svc`) and two schemas (one per user, owned by that user). Each Quarkus service authenticates as its own DB user and pins its default schema via `quarkus.hibernate-orm.database.default-schema`.
- **Rationale**: cheapest isolation that still lets us prove OJP can multiplex two distinct workloads; matches the user's explicit choice.
- **Alternative considered**: two Postgres containers, or one DB / one schema / two users with table-level grants. Rejected (former: heavier; latter: insufficient isolation).

### D5. OJP `ojp-libs` populated by an idempotent `just` task
A `just init-ojp-libs` recipe `curl`s `postgresql-42.7.4.jar` from Maven Central into `compose/ojp-libs/`. The `up` recipe depends on it. The directory is `.gitignore`d.
- **Rationale**: we don't want a JAR checked into git, and we don't want a custom Dockerfile when a bind-mount + 5-line shell recipe works.
- **Alternative considered**: build a derived `ojp-with-postgres` image. Rejected — overkill for a POC and obscures the official OJP image version.

### D6. Compose engine auto-detected in the justfile
The justfile defines `COMPOSE := if which("podman") =~ "" { "docker compose" } else { "podman compose" }` (or equivalent) so a single recipe works under either engine.
- **Rationale**: user uses both. The compose file uses only widely-supported keys (no `version:` field, no engine-specific `x-` extensions).
- **Alternative considered**: separate `just up-podman` / `just up-docker`. Rejected — needless duplication.

### D7. Test-profile ports 9091 / 9092; main ports 8080 / 8082
`application.properties` pins `quarkus.http.port=8080` for `user-service` and `8082` for `product-service`, plus `%test.quarkus.http.port=9091`/`9092` respectively.
- **Rationale**: per user direction. Quarkus' default test profile port is `8081`, which collides with developers who run something else on `8081`. Explicit non-default test ports also let a dev keep a `quarkus:dev` process up while `mvn verify` runs in another shell.

### D8. Integration tests use `@QuarkusIntegrationTest` against a packaged app
Each service has `src/test/java/.../<Entity>ResourceIT.java` annotated `@QuarkusIntegrationTest`, exercised by maven-failsafe via the `verify` phase. Tests assume a healthy compose stack and that the service-under-test starts its own packaged process on the test-profile port.
- **Rationale**: matches Quarkus convention; tests the actual packaged artifact, exercising the OJP path end-to-end.
- **Alternative considered**: `@QuarkusTest` on the dev-mode JVM. Rejected — it would let DevServices interfere even though we've disabled them, and would not exercise the packaged artifact.

### D9. JDBC URL hard-codes `localhost:1059` and `postgres:5432`
`jdbc:ojp[localhost:1059]_postgresql://postgres:5432/appdb` is the URL in every `application.properties`.
- **Rationale**: Quarkus runs on the host; OJP is exposed on host port 1059 (compose port-maps `1059:1059`). The native URL inside the brackets is resolved by OJP, which lives in the compose network, so `postgres:5432` refers to the compose service.
- **Trade-off**: not configurable per environment yet. For this POC that is fine; promoting to non-localhost setups would require an env-var override (Quarkus supports `${OJP_HOST:localhost}` interpolation in `application.properties` — easy to add later).

## Risks / Trade-offs

- **[Risk]** OJP 0.4.14-beta is a beta release — APIs and config keys may drift. → **Mitigation**: pin the image and driver versions explicitly; document in README. Roll forward only intentionally.
- **[Risk]** Quarkus 3.33 LTS on Java 25: some libraries (notably anything using `sun.misc.Unsafe` or older Byte Buddy versions) may not yet be JDK-25-compatible. The OJP driver itself uses gRPC + Netty and may surface a JDK-25 incompatibility. → **Mitigation**: per D1b, if `mvn package` or service boot fails on Java 25, fall back to Java 21 keeping Quarkus 3.33 LTS.
- **[Risk]** `db-kind=other` may trip Quarkus' Hibernate auto-configuration (e.g. boot-time validation that expects a known kind). → **Mitigation**: pin `quarkus.hibernate-orm.dialect` explicitly and explicitly declare `quarkus-agroal` if Quarkus complains about a missing connection pool.
- **[Risk]** Integration tests are flaky if the developer forgot to run `just up`. → **Mitigation**: `just it` could shell out to a precheck (`pg_isready` + `nc -z localhost 1059`) before invoking `mvn verify`. Keep it lightweight; do not auto-start the stack from `mvn`.
- **[Trade-off]** Bind-mounted `ojp-libs` means the host directory must exist and contain the JAR before `up`. → **Mitigation**: `up` recipe declares `init-ojp-libs` as a prerequisite; recipe is idempotent.
- **[Trade-off]** No TLS / no auth. → **Acceptable for POC**; documented as non-goal.

## Migration Plan

N/A — this is greenfield. There is no existing system to migrate from or roll back to. If the POC is abandoned, deleting the repo (or the change) is sufficient cleanup. Containers stop with `just down` and remove their volumes (`-v`).

## Resolved Questions

The three open questions raised in the original draft have been answered by the user:

- **Quarkus + Java pin** → **Quarkus 3.33 LTS on Java 25**. If OJP 0.4.14-beta turns out to be incompatible with that combination during `/opsx:apply` (e.g. driver fails to load under JDK 25, or Quarkus 3.33 + OJP `db-kind=other` doesn't boot), fall back to **Java 21** with Quarkus 3.33 LTS (Quarkus 3.33 LTS supports both JDKs). Document the chosen JDK explicitly in the README and in the `maven.compiler.release` setting of each pom.
- **`just it` behavior** → **manual with a precheck**. The recipe MUST run `pg_isready -h localhost -U appadmin -d appdb` and `nc -z localhost 1059` (or equivalent portable checks) before invoking `mvn verify` on either service. If either check fails, the recipe MUST exit non-zero with a clear message instructing the developer to run `just up` first. It MUST NOT bring the stack up or down on its own.
- **Postgres host port** → **expose `5432:5432`**. The README MUST call out that this collides with any locally-running Postgres on `5432` and suggest the fix (stop the local instance or change the host mapping in `compose.yaml`).

## Open Questions

None at this time. Update this section if new ambiguity surfaces during `/opsx:apply`.
