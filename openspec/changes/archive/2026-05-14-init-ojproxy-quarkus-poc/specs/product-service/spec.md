## ADDED Requirements

### Requirement: Product Resource Exposes CRUD Endpoints

The `product-service` SHALL expose a JSON REST API at `/products` that supports creating, reading, updating, and deleting `Product` resources. Every endpoint MUST consume and produce `application/json`. The service MUST run on HTTP port `8082` in dev/prod profiles and on port `9092` in the test profile.

#### Scenario: Create a product

- **WHEN** a client sends `POST /products` with body `{"name":"Widget","sku":"WID-001","priceCents":1999}`
- **THEN** the service responds with HTTP `201 Created`, a `Location` header pointing to `/products/{generated-id}`, and a JSON body containing the assigned `id`, the submitted `name`, `sku`, `priceCents`, and a server-generated `createdAt` timestamp

#### Scenario: Unique SKU is enforced

- **WHEN** a client sends `POST /products` with a `sku` that already exists
- **THEN** the service responds with HTTP `409 Conflict` (preferred) or `400 Bad Request` and does NOT create a duplicate row

#### Scenario: List products

- **WHEN** a client sends `GET /products` after at least one product has been created
- **THEN** the service responds with HTTP `200 OK` and a JSON array of products

#### Scenario: Get a product by id

- **WHEN** a client sends `GET /products/{id}` for an existing id
- **THEN** the service responds with HTTP `200 OK` and the JSON representation of that product
- **WHEN** the id does not exist
- **THEN** the service responds with HTTP `404 Not Found`

#### Scenario: Update a product

- **WHEN** a client sends `PUT /products/{id}` with a JSON body containing updated fields for an existing id
- **THEN** the service responds with HTTP `200 OK` and the updated representation
- **WHEN** the id does not exist
- **THEN** the service responds with HTTP `404 Not Found`

#### Scenario: Delete a product

- **WHEN** a client sends `DELETE /products/{id}` for an existing id
- **THEN** the service responds with HTTP `204 No Content` and a subsequent `GET /products/{id}` returns `404`

### Requirement: Product Entity Is Persisted In The `product_svc` Schema

The `Product` entity SHALL be persisted via Hibernate ORM with Panache into the `product_svc` Postgres schema. The entity MUST have at minimum the columns `id` (auto-generated identity, primary key), `name` (non-null string), `sku` (non-null string, unique constraint), `price_cents` (non-null integer ≥ 0), and `created_at` (non-null timestamp). The service MUST authenticate to the database as DB user `product_svc` so that all schema lookups resolve to `product_svc` by default.

#### Scenario: Schema isolation

- **WHEN** the `product-service` boots against the running compose stack
- **THEN** Hibernate creates / updates the `products` table inside schema `product_svc` and does NOT touch schema `user_svc`

#### Scenario: Database authentication

- **WHEN** the service connects to the OJP JDBC URL
- **THEN** the connection is authenticated as Postgres role `product_svc` (not `appadmin` and not `user_svc`)

### Requirement: Product Service Has Unit And Integration Tests

The `product-service` Maven module SHALL provide at least one unit test under `src/test/java/**/*Test.java` (run by maven-surefire during `mvn test`) and at least one integration test under `src/test/java/**/*IT.java` annotated `@QuarkusIntegrationTest` (run by maven-failsafe during `mvn verify`). The integration test MUST exercise a full CRUD round-trip against the running OJP+Postgres stack on test port `9092`.

#### Scenario: Unit tests run without a database

- **WHEN** a developer runs `mvn test` (or `just test`) in `product-service`
- **THEN** unit tests execute and pass without requiring the compose stack to be up

#### Scenario: Integration tests run against the live stack

- **WHEN** the compose stack is up and the developer runs `mvn verify` (or `just it`) in `product-service`
- **THEN** maven-failsafe starts the packaged Quarkus app on port `9092`, performs a CRUD round-trip (POST → GET → PUT → DELETE) against `/products`, and all assertions pass
