## ADDED Requirements

### Requirement: User Resource Exposes CRUD Endpoints

The `user-service` SHALL expose a JSON REST API at `/users` that supports creating, reading, updating, and deleting `User` resources. Every endpoint MUST consume and produce `application/json`. The service MUST run on HTTP port `8080` in dev/prod profiles and on port `9091` in the test profile.

#### Scenario: Create a user

- **WHEN** a client sends `POST /users` with body `{"name":"alice","email":"alice@example.com"}`
- **THEN** the service responds with HTTP `201 Created`, a `Location` header pointing to `/users/{generated-id}`, and a JSON body containing the assigned `id`, the submitted `name` and `email`, and a server-generated `createdAt` timestamp

#### Scenario: List users

- **WHEN** a client sends `GET /users` after at least one user has been created
- **THEN** the service responds with HTTP `200 OK` and a JSON array containing each persisted user with fields `id`, `name`, `email`, `createdAt`

#### Scenario: Get a user by id

- **WHEN** a client sends `GET /users/{id}` for an existing id
- **THEN** the service responds with HTTP `200 OK` and the JSON representation of that user
- **WHEN** the id does not exist
- **THEN** the service responds with HTTP `404 Not Found`

#### Scenario: Update a user

- **WHEN** a client sends `PUT /users/{id}` with a JSON body containing updated `name` and/or `email` for an existing id
- **THEN** the service responds with HTTP `200 OK` and the updated representation
- **WHEN** the id does not exist
- **THEN** the service responds with HTTP `404 Not Found`

#### Scenario: Delete a user

- **WHEN** a client sends `DELETE /users/{id}` for an existing id
- **THEN** the service responds with HTTP `204 No Content` and a subsequent `GET /users/{id}` returns `404`

### Requirement: User Entity Is Persisted In The `user_svc` Schema

The `User` entity SHALL be persisted via Hibernate ORM with Panache into the `user_svc` Postgres schema. The entity MUST have at minimum the columns `id` (auto-generated identity, primary key), `name` (non-null string), `email` (non-null string), and `created_at` (non-null timestamp). The service MUST authenticate to the database as the DB user `user_svc` so that all schema lookups resolve to `user_svc` by default.

#### Scenario: Schema isolation

- **WHEN** the `user-service` boots against the running compose stack
- **THEN** Hibernate creates / updates the `users` table inside schema `user_svc` and does NOT touch schema `product_svc`

#### Scenario: Database authentication

- **WHEN** the service connects to the OJP JDBC URL
- **THEN** the connection is authenticated as Postgres role `user_svc` (not `appadmin` and not `product_svc`)

### Requirement: User Service Has Unit And Integration Tests

The `user-service` Maven module SHALL provide at least one unit test under `src/test/java/**/*Test.java` (run by maven-surefire during `mvn test`) and at least one integration test under `src/test/java/**/*IT.java` annotated `@QuarkusIntegrationTest` (run by maven-failsafe during `mvn verify`). The integration test MUST exercise a full CRUD round-trip against the running OJP+Postgres stack on test port `9091`.

#### Scenario: Unit tests run without a database

- **WHEN** a developer runs `mvn test` (or `just test`) in `user-service`
- **THEN** unit tests execute and pass without requiring the compose stack to be up

#### Scenario: Integration tests run against the live stack

- **WHEN** the compose stack is up and the developer runs `mvn verify` (or `just it`) in `user-service`
- **THEN** maven-failsafe starts the packaged Quarkus app on port `9091`, performs a CRUD round-trip (POST → GET → PUT → DELETE) against `/users`, and all assertions pass
