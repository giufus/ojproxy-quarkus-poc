# ojproxy-quarkus-poc

POC showing two non-reactive Quarkus 3 services (Panache + Hibernate ORM) talking to a single PostgreSQL backend **exclusively through [OpenJ Proxy](https://openjproxy.com/) (OJP) 0.4.14-beta**. Each service owns its own Postgres schema in a shared `appdb` database.

The point of the POC is to verify that:

- A Quarkus app can be configured with `db-kind=other` and the OJP JDBC driver instead of the native Postgres driver.
- One OJP server can transparently serve multiple services that live in distinct Postgres schemas.

## Layout

```
compose/                    podman/docker-compose stack
  compose.yaml              postgres + ojp services
  postgres/init.sql         creates schemas + users on first boot
  ojp-libs/                 bind-mounted into the OJP container; populated by `just init-ojp-libs`
user-service/               Quarkus app, schema user_svc, port 8080 (test 9091)
product-service/            Quarkus app, schema product_svc, port 8082 (test 9092)
justfile                    developer workflow
openspec/                   change proposal + specs for this POC
```

## Prerequisites

- **Java 25** (fall back to **Java 21** if you hit a JDK-25 incompatibility — see *Troubleshooting*)
- **Maven 3.9+**
- **[just](https://github.com/casey/just)** ≥ 1.x
- **podman** or **docker** with the `compose` subcommand
- Optional: `psql` client for poking the DB

The `justfile` auto-detects the compose engine (prefers podman if installed).

## Quickstart

```bash
just init-ojp-libs     # downloads postgresql-42.7.4.jar into compose/ojp-libs/
just up                # starts postgres + ojp in the background
just dev-user          # in one shell: runs user-service on :8080
just dev-product       # in another shell: runs product-service on :8082
```

Then exercise the APIs:

```bash
# user-service
curl -X POST http://localhost:8080/users \
  -H 'content-type: application/json' \
  -d '{"name":"alice","email":"alice@example.com"}'

curl http://localhost:8080/users

# product-service
curl -X POST http://localhost:8082/products \
  -H 'content-type: application/json' \
  -d '{"name":"Widget","sku":"WID-001","priceCents":1999}'

curl http://localhost:8082/products
```

Tear down with `just down` (removes the named `pgdata` volume too).

## `just` recipes

| Recipe            | What it does                                                                 |
|-------------------|------------------------------------------------------------------------------|
| `just`            | List recipes                                                                 |
| `just init-ojp-libs` | Idempotent download of `postgresql-42.7.4.jar` into `compose/ojp-libs/`     |
| `just up`         | `init-ojp-libs` then bring the compose stack up                              |
| `just down`       | Bring stack down, remove volumes (`-v`)                                      |
| `just logs [svc]` | Tail logs for a service (default `ojp`)                                      |
| `just dev-user`   | `quarkus:dev` for user-service (binds :8080)                                 |
| `just dev-product`| `quarkus:dev` for product-service (binds :8082)                              |
| `just test`       | `mvn test` for both services (surefire, no DB required)                      |
| `just it`         | `mvn verify` for both services (failsafe + REST-assured, requires stack up)  |
| `just build`      | `mvn package` for both services                                              |
| `just clean`      | `mvn clean` for both services                                                |

`just it` performs a precheck (`pg_isready` on :5432 + raw TCP on :1059) and exits with a clear error if the stack is not up. It will **never** bring the stack up or down on its own — that's intentional.

## Ports

| Component       | Host port | Notes                                                   |
|-----------------|-----------|---------------------------------------------------------|
| Postgres        | 5432      | exposed for `psql` debugging; see warning below         |
| OJP server      | 1059      | client services connect here                            |
| user-service    | 8080      | dev/prod                                                |
| user-service    | 9091      | test profile (failsafe ITs)                             |
| product-service | 8082      | dev/prod                                                |
| product-service | 9092      | test profile (failsafe ITs)                             |

**⚠ Postgres :5432 collision**: if you already have a local Postgres listening on host port 5432, `just up` will fail. Either stop the local server or change the host-side mapping in [compose/compose.yaml](compose/compose.yaml) (e.g. `"55432:5432"`).

## Architecture in one diagram

```
+---------------------+        +---------------------+
| user-service (host) |        | product-service     |
|  :8080 / :9091      |        | (host) :8082 / :9092|
|  org.openjproxy.    |        |  org.openjproxy.    |
|  jdbc.Driver        |        |  jdbc.Driver        |
+----------+----------+        +----------+----------+
           |                              |
           |   jdbc:ojp[localhost:1059]_postgresql://postgres:5432/appdb
           |                              |
           +--------------+---------------+
                          | TCP :1059
                          v
                   +-------------+
                   | ojp (compose|
                   |  container) |
                   +------+------+
                          | postgres:5432 (compose network)
                          v
                   +-------------+
                   |  postgres   |
                   |  appdb      |
                   |  schema:    |
                   |   user_svc  |
                   |   product_  |
                   |   svc       |
                   +-------------+
```

- Native Postgres JDBC driver is **NOT** on the Quarkus classpath — only the OJP driver.
- OJP's container loads `postgresql-42.7.4.jar` from `/opt/ojp/ojp-libs` (mandatory ≥ 0.4.0-beta).
- Each service authenticates as its own Postgres user (`user_svc` / `product_svc`) and pins its default schema via Hibernate config.

## Troubleshooting

**`just up` says "ojp-libs: no such file"**
Run `just init-ojp-libs` first (or just rerun `just up` — it depends on `init-ojp-libs`). If `curl` fails, check your network and retry.

**Port :1059 already in use**
Another process is bound to 1059. Find it (`ss -tnlp | grep 1059` or `lsof -i :1059`) and stop it, or change the host-side mapping in `compose/compose.yaml`.

**Port :5432 already in use**
See the warning above — most likely a local Postgres. Stop it (`sudo systemctl stop postgresql`) or remap host-side in `compose/compose.yaml`.

**Quarkus boot error: schema permissions / "permission denied for schema"**
The Postgres init script (`compose/postgres/init.sql`) only runs on a fresh data volume. If you changed it after a first `up`, you need `just down` (which passes `-v` to remove the named volume), then `just up` again to re-init.

**`mvn package` or `quarkus:dev` fails on JDK 25**
OJP uses gRPC + Netty under the hood; on rare occasions these libraries lag a JDK release. **Fallback**: edit both `pom.xml` files and set `<maven.compiler.release>21</maven.compiler.release>`, switch your local JDK to 21 (`sdk use java 21-tem` or similar), and retry. Quarkus 3.33 LTS supports both 21 and 25.

**`just it` fails immediately with "stack not up"**
Run `just up` first. The recipe deliberately does not auto-manage the stack.

**`UserResourceIT` / `ProductResourceIT` hang or fail with connection refused**
Check that `nc -z localhost 1059` succeeds and that `psql -h localhost -U appadmin -d appdb -c '\dn'` lists `user_svc` and `product_svc`. If schemas are missing, the init script didn't run — `just down` then `just up`.

## OpenSpec change

This POC was scaffolded via OpenSpec change [`init-ojproxy-quarkus-poc`](openspec/changes/init-ojproxy-quarkus-poc/). See its [proposal.md](openspec/changes/init-ojproxy-quarkus-poc/proposal.md), [design.md](openspec/changes/init-ojproxy-quarkus-poc/design.md), and [tasks.md](openspec/changes/init-ojproxy-quarkus-poc/tasks.md) for the rationale behind every choice.
