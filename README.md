# ojproxy-quarkus-poc

POC showing two non-reactive Quarkus 3 services (Panache + Hibernate ORM) connected to a single PostgreSQL backend **through [OpenJ Proxy](https://openjproxy.com/) (OJP) 0.4.14-beta**, with an A/B comparison under load against a direct connection.

The POC is structured in three phases:

| Phase | Goal | Status |
|-------|------|--------|
| **A — Observability** | Self-hosted LGTM stack (Loki, Grafana, Tempo, Prometheus) | ✅ Done |
| **B — Pool tuning** | Agroal + OJP/HikariCP pools calibrated and symmetric | ✅ Done |
| **C — A/B comparison** | Demonstrate OJP advantages under JMeter load | ✅ Done |

## Architecture

```
┌────────────────────────┐     ┌────────────────────────┐
│  user-service (host)   │     │  product-service (host) │
│  :8080  /  test :9091  │     │  :8082  /  test :9092   │
│  driver: OJP (default) │     │  driver: OJP (default)  │
│  driver: PG  (direct)  │     │  driver: PG  (direct)   │
└──────────┬─────────────┘     └────────────┬────────────┘
           │                                │
           │  jdbc:ojp[localhost:1059]_postgresql://postgres:5432/appdb
           │                                │
           └───────────────┬────────────────┘
                           │ TCP :1059
                           ▼
                    ┌─────────────┐
                    │ ojp (compose│  HikariCP pool → Postgres
                    │ container)  │  metrics on :9159/metrics
                    └──────┬──────┘
                           │ postgres:5432 (compose network)
                           ▼
                    ┌─────────────┐
                    │  postgres   │  appdb
                    │  schema:    │  max_connections=25 (A/B)
                    │   user_svc  │
                    │   product_  │
                    │   svc       │
                    └─────────────┘
                           │
                    ┌─────────────┐
                    │  postgres-  │  → Prometheus :9187
                    │  exporter   │  pg_stat_activity_count
                    └─────────────┘
```

### Observability stack (Phase A)

```
Quarkus/OJP ──OTLP──► otel-collector ──► Tempo      (traces)
                                    └──► Loki       (logs)
                    ◄── scrape /q/metrics ── Prometheus ─► Grafana :3000
                    ◄── scrape :9159/metrics (OJP HikariCP)
                    ◄── scrape :9187/metrics (postgres-exporter)
```

## Layout

```
compose/
  compose.yaml                postgres + ojp + LGTM stack
  ojp.env                     OJP configuration (pool, timeouts)
  postgres/init.sql            schema + seed 100 users + 100 products
  ojp-libs/                   Postgres jar bind-mounted into the OJP container
  observability/              OTel-collector, Tempo, Loki, Prometheus, Grafana configs
user-service/                 Quarkus app, schema user_svc, port 8080 (test 9091)
product-service/              Quarkus app, schema product_svc, port 8082 (test 9092)
justfile                      developer workflow
openspec/                     change proposal + design + tasks
```

## Prerequisites

- **Java 25** (or **Java 21** as fallback — see *Troubleshooting*)
- **Maven 3.9+**
- **[just](https://github.com/casey/just)** ≥ 1.x
- **podman** or **docker** with the `compose` subcommand
- Optional: `psql` client for poking the DB

The `justfile` auto-detects the compose engine (prefers podman if installed).

## Quickstart

```bash
just up             # downloads the Postgres jar, starts the full compose stack
just dev-user       # shell 1: user-service on :8080 (OJP mode)
just dev-product    # shell 2: product-service on :8082 (OJP mode)
```

To start both services at once:

```bash
just dev-all        # forks both in the background; Ctrl-C stops both
```

Grafana is available at [http://localhost:3000](http://localhost:3000) (anonymous access, Editor role).

Tear down with `just down` (also removes the `pgdata` volume).

## Exercising the APIs

The database is pre-populated by `compose/postgres/init.sql` (100 users + 100 products, IDs 1-100). Quarkus uses `schema-management.strategy=none` — Hibernate does not touch the schema.

```bash
# user-service
curl http://localhost:8080/users/1
curl -X POST http://localhost:8080/users \
  -H 'content-type: application/json' \
  -d '{"name":"alice","email":"alice@example.com"}'

# product-service
curl http://localhost:8082/products/1
curl -X POST http://localhost:8082/products \
  -H 'content-type: application/json' \
  -d '{"name":"Widget","sku":"WID-001","priceCents":1999}'
```

## A/B Experiment: OJP vs direct (Phase C)

### Goal

Demonstrate that OJP — through **centralised connection multiplexing** — keeps the number of real Postgres connections under control, while in direct mode the applications saturate the DB connection limit.

### Sizing math

| Layer | Setting | OJP mode | Direct mode |
|-------|---------|:---:|:---:|
| Postgres | `max_connections` | **25** | **25** |
| Agroal (per service) | `jdbc.max-size` | 10 gRPC stubs | 10 direct conns |
| OJP/HikariCP (per pool) | `OJP_MAX_CONNECTIONS` | 10 real conns | — |
| **Total real connections to Postgres** | | **20 ≤ 25 ✓** | **20 ≤ 25\*** |

> \* The limit of 25 is intentionally tight. To make saturation visible with 2 services, increase `jdbc.max-size` above 12 or reduce `max_connections` further. Alternatively, scale up JMeter thread count: connections are opened on demand up to `max-size`.

### Changing `max_connections`

The `PG_MAX_CONNECTIONS` env var is set in the `environment:` block of the `postgres` service in [compose/compose.yaml](compose/compose.yaml):

```yaml
PG_MAX_CONNECTIONS: "25"   # comment out this line to revert to the Postgres default (100)
```

After any change: `just down && just up` (the `-c max_connections=…` flag is re-read at boot).

### Procedure

**Run A — OJP mode (baseline)**

```bash
just dev-all        # start both services with the OJP driver
# launch JMeter
```

In Grafana → Explore → Prometheus:
```promql
pg_stat_activity_count{usename=~"user_svc|product_svc"}
```
Expected: sum ≤ 20, stable, 0 HTTP errors.

**Run B — direct mode**

```bash
just dev-all-direct     # start both services with the native Postgres driver
# same JMeter plan
```

Expected: `pg_stat_activity_count` climbs toward the limit, Quarkus logs show `FATAL: sorry, too many clients already`, HTTP 500 errors in JMeter.

### The `direct` profile

Both services support a Quarkus `direct` profile that bypasses OJP and uses the native Postgres driver (`quarkus-jdbc-postgresql`):

- [user-service/src/main/resources/application-direct.properties](user-service/src/main/resources/application-direct.properties)
- [product-service/src/main/resources/application-direct.properties](product-service/src/main/resources/application-direct.properties)

Same code, same ports, same REST endpoints — JMeter always points to the same URL.

## `just` recipes

| Recipe | What it does |
|--------|-------------|
| `just` | List recipes |
| `just init-ojp-libs` | Idempotent download of `postgresql-42.7.4.jar` into `compose/ojp-libs/` |
| `just up` | `init-ojp-libs` then bring the full compose stack up |
| `just down` | Bring stack down, remove volumes (`-v`) |
| `just logs [svc]` | Tail logs for a service (default `ojp`) |
| `just dev-user` | `quarkus:dev` user-service on :8080 (OJP) |
| `just dev-product` | `quarkus:dev` product-service on :8082 (OJP) |
| `just dev-all` | Both services in OJP mode (fork; Ctrl-C stops both) |
| `just dev-user-direct` | `quarkus:dev` user-service on :8080 (direct Postgres) |
| `just dev-product-direct` | `quarkus:dev` product-service on :8082 (direct Postgres) |
| `just dev-all-direct` | Both services in direct mode (fork; Ctrl-C stops both) |
| `just obs` | Open Grafana in the browser (`http://localhost:3000`) |
| `just obs-logs` | Tail observability stack logs |
| `just test` | `mvn test` for both services (surefire, no DB required) |
| `just it` | `mvn verify` for both services (failsafe + REST-assured, requires stack up) |
| `just build` | `mvn package` for both services |
| `just clean` | `mvn clean` for both services |

`just it` performs a precheck on :5432 and :1059 before running tests. It never manages the stack on its own — that is intentional.

## Ports

| Component | Host port | Notes |
|-----------|-----------|-------|
| Postgres | 5432 | exposed for `psql` debugging |
| OJP server | 1059 | services connect here |
| OJP metrics | 9159 | HikariCP metrics (Prometheus) |
| postgres-exporter | 9187 | `pg_stat_activity` metrics |
| user-service | 8080 | dev/run |
| user-service | 9091 | test profile (failsafe ITs) |
| product-service | 8082 | dev/run |
| product-service | 9092 | test profile (failsafe ITs) |
| OTel Collector | 4317 | OTLP gRPC |
| OTel Collector | 4318 | OTLP HTTP |
| Prometheus | 9090 | Prometheus UI |
| Grafana | 3000 | Grafana UI (anonymous access) |

**⚠ Port :5432 collision**: if a local Postgres is already listening on 5432, `just up` will fail. Either stop the local server (`sudo systemctl stop postgresql`) or remap the host-side port in [compose/compose.yaml](compose/compose.yaml).

## Pool tuning (Phase B)

### Invariants

```
Σ Agroal max-size  ≤  Σ OJP max-connections  ≤  Postgres max_connections
     20 (10+10)    ≤        20 (10+10)        ≤        25
```

OJP connection timeout (1500 ms) < Agroal acquisition-timeout (2000 ms): Agroal never waits longer than OJP can respond.

### OJP configuration

[compose/ojp.env](compose/ojp.env):

```
OJP_MAX_CONNECTIONS=10
OJP_CONNECTION_TIMEOUT=1500
OJP_IDLE_TIMEOUT=300000
```

### Agroal configuration (default profile, both services)

```properties
quarkus.datasource.jdbc.min-size=2
quarkus.datasource.jdbc.initial-size=2
quarkus.datasource.jdbc.max-size=10
quarkus.datasource.jdbc.acquisition-timeout=2S
quarkus.datasource.jdbc.max-lifetime=25M
```

## Troubleshooting

**`just up` says "ojp-libs: no such file"**
Re-run `just up` — it depends on `init-ojp-libs`. If `curl` fails, check your network.

**Port :1059 already in use**
`ss -tnlp | grep 1059` or `lsof -i :1059` to find the process. Stop it or remap in `compose/compose.yaml`.

**Port :5432 already in use**
Most likely a local Postgres. `sudo systemctl stop postgresql` or remap.

**Quarkus boot error: "permission denied for schema"**
The Postgres init script only runs on a fresh data volume. `just down && just up` to re-initialise.

**Seed data missing (empty tables)**
`just down && just up` — the `pgdata` volume must be recreated for `init.sql` to run again.

**`mvn package` or `quarkus:dev` fails on JDK 25**
gRPC/Netty may lag behind a JDK release. Fallback: set `<maven.compiler.release>21</maven.compiler.release>` in both `pom.xml` files and switch to JDK 21 (`sdk use java 21-tem`).

**`just it` fails immediately with "stack not up"**
Run `just up` first. The recipe never manages the stack on its own.

**`UserResourceIT` / `ProductResourceIT` hang or fail with connection refused**
Check that `nc -z localhost 1059` succeeds and that `psql -h localhost -U appadmin -d appdb -c '\dn'` lists the `user_svc` and `product_svc` schemas. If missing: `just down && just up`.

## OpenSpec change

This POC was scaffolded via the OpenSpec change [`init-ojproxy-quarkus-poc`](openspec/changes/init-ojproxy-quarkus-poc/). See its [proposal.md](openspec/changes/init-ojproxy-quarkus-poc/proposal.md), [design.md](openspec/changes/init-ojproxy-quarkus-poc/design.md), and [tasks.md](openspec/changes/init-ojproxy-quarkus-poc/tasks.md) for the rationale behind every choice.
