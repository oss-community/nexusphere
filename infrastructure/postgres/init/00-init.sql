-- Runs once, when the PostgreSQL volume is first created (docker compose).
--
-- Module schemas are NOT created here. The application runs one Flyway instance per
-- module (see nexusphere.persistence.modules in core/bootstrap application.yaml); each
-- creates its own schema and history table from db/migration/<module>.
-- This script only prepares database-wide settings.

ALTER DATABASE nexusphere SET timezone TO 'UTC';
