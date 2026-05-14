-- Initialise the `appdb` database with one DB user and one schema per Quarkus service.
-- This script is mounted into /docker-entrypoint-initdb.d/ and runs only on first boot
-- (or after `just down -v` wipes the named volume).

-- Two service users (matching passwords for POC simplicity).
CREATE USER user_svc    WITH PASSWORD 'user_svc';
CREATE USER product_svc WITH PASSWORD 'product_svc';

-- One schema per service, owned by the matching user.
CREATE SCHEMA user_svc    AUTHORIZATION user_svc;
CREATE SCHEMA product_svc AUTHORIZATION product_svc;

-- Allow the service users to connect to appdb (current_database()).
GRANT CONNECT ON DATABASE appdb TO user_svc, product_svc;
