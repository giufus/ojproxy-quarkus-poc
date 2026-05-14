## ADDED Requirements

### Requirement: Client Services Use Only The OJP JDBC Driver

Both Quarkus services SHALL declare exactly one JDBC driver dependency: `org.openjproxy:ojp-jdbc-driver:0.4.14-beta`. Neither service SHALL include `quarkus-jdbc-postgresql`, `org.postgresql:postgresql`, nor any other native JDBC driver as a runtime dependency. The Quarkus datasource MUST be configured with `quarkus.datasource.db-kind=other` and `quarkus.datasource.jdbc.driver=org.openjproxy.jdbc.Driver`.

#### Scenario: Native Postgres driver is absent

- **WHEN** a developer inspects the runtime classpath of either packaged service (e.g. `mvn dependency:tree`)
- **THEN** no `org.postgresql:postgresql` artifact appears

#### Scenario: Quarkus boots with `db-kind=other`

- **WHEN** the service starts
- **THEN** Quarkus does NOT fail at boot complaining about an unknown `db-kind` and the Agroal connection pool is initialised against the OJP driver

### Requirement: JDBC URL Follows OJP Convention

Every service's `quarkus.datasource.jdbc.url` SHALL match the pattern `jdbc:ojp[<ojp-host>:<ojp-port>]_postgresql://<pg-host>:<pg-port>/<db>` and SHALL use `localhost:1059` as the OJP endpoint and `postgres:5432/appdb` as the native Postgres endpoint. The Hibernate dialect MUST be pinned with `quarkus.hibernate-orm.dialect=org.hibernate.dialect.PostgreSQLDialect`.

#### Scenario: URL is well-formed

- **WHEN** the configured URL is parsed by the OJP driver
- **THEN** the driver extracts OJP host `localhost`, OJP port `1059`, native protocol `postgresql`, native host `postgres`, native port `5432`, and database `appdb` without errors

#### Scenario: Dialect is pinned explicitly

- **WHEN** Hibernate boots
- **THEN** it uses `org.hibernate.dialect.PostgreSQLDialect` regardless of any auto-detection that would otherwise be triggered by `db-kind=other`

### Requirement: OJP Server Has Postgres JDBC Driver Available

The OJP server container SHALL have `postgresql-42.7.x.jar` available inside `/opt/ojp/ojp-libs`. This is supplied by bind-mounting the host directory `compose/ojp-libs/` (populated by the `just init-ojp-libs` recipe) into that path. The OJP server SHALL listen on TCP port `1059` and that port MUST be reachable from the host (compose port mapping `1059:1059`).

#### Scenario: Driver is mounted

- **WHEN** the OJP container starts
- **THEN** `postgresql-42.7.x.jar` is present in `/opt/ojp/ojp-libs` and OJP logs do not report a missing-driver error when a client connects with a `postgresql` native URL

#### Scenario: Libs directory is populated by `just init-ojp-libs`

- **WHEN** a developer runs `just init-ojp-libs` from a fresh checkout
- **THEN** `compose/ojp-libs/postgresql-42.7.x.jar` exists on disk after the recipe completes
- **WHEN** the recipe is run a second time
- **THEN** it is a no-op (does not re-download) and exits with success

#### Scenario: OJP port is reachable from the host

- **WHEN** the compose stack is up
- **THEN** `nc -z localhost 1059` succeeds within 30 seconds of `just up` returning
