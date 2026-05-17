# ojproxy-quarkus-poc

POC che dimostra due servizi Quarkus 3 non-reattivi (Panache + Hibernate ORM) connessi a un singolo backend PostgreSQL **tramite [OpenJ Proxy](https://openjproxy.com/) (OJP) 0.4.14-beta**, con confronto A/B sotto carico rispetto a una connessione diretta.

Il POC è strutturato in tre fasi:

| Fase | Obiettivo | Stato |
|------|-----------|-------|
| **A — Observability** | Stack LGTM self-hosted (Loki, Grafana, Tempo, Prometheus) | ✅ Done |
| **B — Pool tuning** | Pool Agroal + OJP/HikariCP calibrati e simmetrici | ✅ Done |
| **C — A/B comparison** | Dimostrare i vantaggi di OJP sotto carico JMeter | ✅ Done |

## Architettura

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
                    │ container)  │  metrics su :9159/metrics
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

### Stack observability (Phase A)

```
Quarkus/OJP ──OTLP──► otel-collector ──► Tempo   (trace)
                                    └──► Loki    (log)
                    ◄── scrape /q/metrics ── Prometheus ─► Grafana :3000
                    ◄── scrape :9159/metrics (OJP HikariCP)
                    ◄── scrape :9187/metrics (postgres-exporter)
```

## Layout

```
compose/
  compose.yaml                postgres + ojp + LGTM stack
  ojp.env                     configurazione OJP (pool, timeout)
  postgres/init.sql            schema + seed 100 utenti + 100 prodotti
  ojp-libs/                   jar Postgres montato nel container OJP
  observability/              config OTel-collector, Tempo, Loki, Prometheus, Grafana
user-service/                 Quarkus app, schema user_svc, porta 8080 (test 9091)
product-service/              Quarkus app, schema product_svc, porta 8082 (test 9092)
justfile                      workflow developer
openspec/                     change proposal + design + tasks
```

## Prerequisiti

- **Java 25** (o **Java 21** come fallback — vedi *Troubleshooting*)
- **Maven 3.9+**
- **[just](https://github.com/casey/just)** ≥ 1.x
- **podman** o **docker** con il subcommand `compose`
- Opzionale: client `psql` per ispezionare il DB

Il `justfile` rileva automaticamente l'engine compose (preferisce podman se installato).

## Quickstart

```bash
just up             # scarica il jar Postgres, avvia l'intero stack compose
just dev-user       # shell 1: user-service su :8080 (modalità OJP)
just dev-product    # shell 2: product-service su :8082 (modalità OJP)
```

Per avviare entrambi i servizi in un colpo solo:

```bash
just dev-all        # fork in background, Ctrl-C ferma entrambi
```

Grafana è disponibile su [http://localhost:3000](http://localhost:3000) (accesso anonimo, ruolo Editor).

Spegni tutto con `just down` (rimuove anche il volume `pgdata`).

## Esercitare le API

Il database viene pre-popolato da `compose/postgres/init.sql` (100 utenti + 100 prodotti, IDs 1-100). Quarkus usa `schema-management.strategy=none` — Hibernate non tocca lo schema.

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

## Esperimento A/B OJP vs direct (Phase C)

### Obiettivo

Dimostrare che OJP — grazie al **connection multiplexing centralizzato** — mantiene un numero controllato di connessioni reali a Postgres, mentre in modalità diretta le app saturano il limite del DB.

### Sizing matematico

| Layer | Setting | Modalità OJP | Modalità direct |
|-------|---------|:---:|:---:|
| Postgres | `max_connections` | **25** | **25** |
| Agroal (per servizio) | `jdbc.max-size` | 10 stub gRPC | 10 conn dirette |
| OJP/HikariCP (per pool) | `OJP_MAX_CONNECTIONS` | 10 conn reali | — |
| **Totale connessioni reali a Postgres** | | **20 ≤ 25 ✓** | **20 ≤ 25\*** |

> \* Il limite di 25 è artificioso. Per rendere visibile la saturazione con 2 servizi, abbassa `jdbc.max-size` a valori più alti o riduci `max_connections`. Oppure scala JMeter con più thread: le connessioni vengono aperte su domanda fino al max-size del pool.

### Come cambiare `max_connections`

L'env var `PG_MAX_CONNECTIONS` è definita nel `environment:` block del servizio `postgres` in [compose/compose.yaml](compose/compose.yaml):

```yaml
PG_MAX_CONNECTIONS: "25"   # commenta questa riga per tornare al default Postgres (100)
```

Dopo ogni modifica: `just down && just up` (il comando `-c max_connections=…` viene riletto al boot).

### Procedura

**Run A — modalità OJP (baseline)**

```bash
just dev-all        # avvia entrambi i servizi con driver OJP
# lancia JMeter
```

In Grafana → Explore → Prometheus:
```promql
pg_stat_activity_count{usename=~"user_svc|product_svc"}
```
Atteso: somma ≤ 20, stabile, 0 errori HTTP.

**Run B — modalità direct**

```bash
just dev-all-direct     # avvia entrambi i servizi con driver Postgres nativo
# stesso piano JMeter
```

Atteso: `pg_stat_activity_count` sale verso il limite, log Quarkus con `FATAL: sorry, too many clients already`, errori HTTP 500 in JMeter.

### Profilo `direct`

I servizi supportano un profilo Quarkus `direct` che bypassa OJP e usa il driver Postgres nativo (`quarkus-jdbc-postgresql`):

- [user-service/src/main/resources/application-direct.properties](user-service/src/main/resources/application-direct.properties)
- [product-service/src/main/resources/application-direct.properties](product-service/src/main/resources/application-direct.properties)

Stesso codice, stesse porte, stessi endpoint REST — JMeter punta sempre allo stesso URL.

## Ricette `just`

| Ricetta | Cosa fa |
|---------|---------|
| `just` | Elenca le ricette |
| `just init-ojp-libs` | Download idempotente di `postgresql-42.7.4.jar` in `compose/ojp-libs/` |
| `just up` | `init-ojp-libs` poi avvia l'intero compose stack |
| `just down` | Abbatte lo stack, rimuove i volumi (`-v`) |
| `just logs [svc]` | Tail log di un servizio (default `ojp`) |
| `just dev-user` | `quarkus:dev` user-service su :8080 (OJP) |
| `just dev-product` | `quarkus:dev` product-service su :8082 (OJP) |
| `just dev-all` | Entrambi i servizi in OJP mode (fork, Ctrl-C ferma entrambi) |
| `just dev-user-direct` | `quarkus:dev` user-service su :8080 (direct Postgres) |
| `just dev-product-direct` | `quarkus:dev` product-service su :8082 (direct Postgres) |
| `just dev-all-direct` | Entrambi i servizi in direct mode (fork, Ctrl-C ferma entrambi) |
| `just obs` | Apre Grafana nel browser (`http://localhost:3000`) |
| `just obs-logs` | Tail log dello stack observability |
| `just test` | `mvn test` per entrambi i servizi (surefire, no DB) |
| `just it` | `mvn verify` per entrambi i servizi (failsafe + REST-assured, richiede stack up) |
| `just build` | `mvn package` per entrambi i servizi |
| `just clean` | `mvn clean` per entrambi i servizi |

`just it` fa un precheck su :5432 e :1059 prima di avviare i test. Non gestisce lo stack autonomamente — è intenzionale.

## Porte

| Componente | Porta host | Note |
|------------|-----------|------|
| Postgres | 5432 | esposta per debug con `psql` |
| OJP server | 1059 | i servizi si connettono qui |
| OJP metrics | 9159 | HikariCP metrics (Prometheus) |
| postgres-exporter | 9187 | `pg_stat_activity` metrics |
| user-service | 8080 | dev/run |
| user-service | 9091 | test profile (failsafe ITs) |
| product-service | 8082 | dev/run |
| product-service | 9092 | test profile (failsafe ITs) |
| OTel Collector | 4317 | OTLP gRPC |
| OTel Collector | 4318 | OTLP HTTP |
| Prometheus | 9090 | UI Prometheus |
| Grafana | 3000 | UI Grafana (accesso anonimo) |

**⚠ Collisione :5432**: se hai un Postgres locale in ascolto su 5432, `just up` fallisce. Ferma il servizio locale (`sudo systemctl stop postgresql`) o rimappa la porta host in [compose/compose.yaml](compose/compose.yaml).

## Pool tuning (Phase B)

### Invarianti rispettati

```
Σ Agroal max-size  ≤  Σ OJP max-connections  ≤  Postgres max_connections
     20 (10+10)    ≤        20 (10+10)        ≤        25
```

OJP connection timeout (1500 ms) < Agroal acquisition-timeout (2000 ms): Agroal non aspetta più di quanto OJP possa rispondere.

### Configurazione OJP

[compose/ojp.env](compose/ojp.env):

```
OJP_MAX_CONNECTIONS=10
OJP_CONNECTION_TIMEOUT=1500
OJP_IDLE_TIMEOUT=300000
```

### Configurazione Agroal (default, entrambi i servizi)

```properties
quarkus.datasource.jdbc.min-size=2
quarkus.datasource.jdbc.initial-size=2
quarkus.datasource.jdbc.max-size=10
quarkus.datasource.jdbc.acquisition-timeout=2S
quarkus.datasource.jdbc.max-lifetime=25M
```

## Troubleshooting

**`just up` dice "ojp-libs: no such file"**
Ri-esegui `just up` — dipende da `init-ojp-libs`. Se `curl` fallisce controlla la rete.

**Porta :1059 già in uso**
`ss -tnlp | grep 1059` o `lsof -i :1059` per trovare il processo. Fermalo o rimappa in `compose/compose.yaml`.

**Porta :5432 già in uso**
Probabilmente Postgres locale. `sudo systemctl stop postgresql` o rimappa.

**Quarkus boot error: "permission denied for schema"**
Il init script di Postgres gira solo su un volume fresco. `just down && just up` per re-inizializzare.

**I dati seed non ci sono (tabelle vuote)**
`just down && just up` — il volume `pgdata` deve essere ricreato perché `init.sql` sia rieseguito.

**`mvn package` o `quarkus:dev` fallisce su JDK 25**
gRPC/Netty può essere in ritardo su un JDK release. Fallback: imposta `<maven.compiler.release>21</maven.compiler.release>` in entrambi i `pom.xml` e usa JDK 21 (`sdk use java 21-tem`).

**`just it` fallisce subito con "stack not up"**
Esegui prima `just up`. La ricetta non gestisce lo stack autonomamente.

**`UserResourceIT` / `ProductResourceIT` in hang o connection refused**
Controlla che `nc -z localhost 1059` risponda e che `psql -h localhost -U appadmin -d appdb -c '\dn'` mostri i schema `user_svc` e `product_svc`. Se mancano: `just down && just up`.

## OpenSpec change

Questo POC è stato scaffolded tramite la change [`init-ojproxy-quarkus-poc`](openspec/changes/init-ojproxy-quarkus-poc/). Vedi [proposal.md](openspec/changes/init-ojproxy-quarkus-poc/proposal.md), [design.md](openspec/changes/init-ojproxy-quarkus-poc/design.md) e [tasks.md](openspec/changes/init-ojproxy-quarkus-poc/tasks.md).
