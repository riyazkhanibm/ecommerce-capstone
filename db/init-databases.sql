-- Runs once when the postgres container is first created.
-- Each microservice owns its own database (database-per-service pattern).
CREATE DATABASE productdb;
CREATE DATABASE orderdb;
CREATE DATABASE userdb;
