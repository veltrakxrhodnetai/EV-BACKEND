# Backend DB Clone Setup

This project uses Flyway migrations in:

- backend/src/main/resources/db/migration

## Why startup failed on another machine

The error indicates two independent issues:

1. Checksum mismatch for version 12
- Someone changed the contents of V12 after it had already been applied in that database.
- Flyway blocks startup to protect schema consistency.

2. Resolved migration 4.1 not applied
- The machine has a migration file with version 4.1, but that version is not in flyway history for that DB.
- This usually happens when switching branches/repos with different migration sets.

## Golden rules

1. Never edit already-applied migration files (V1..V23).
2. Add only new migration files for schema changes.
3. Keep same migration folder in all repos/environments.

## Clean clone flow (recommended)

Use a fresh database/volume so Flyway starts from scratch.

1. Stop and remove old DB volume:
- docker compose -f infra/docker-compose.yml down -v

2. Start postgres fresh:
- docker compose -f infra/docker-compose.yml up -d postgres

3. Run backend:
- java -jar target/ev-csms-backend-0.0.1-SNAPSHOT.jar

## Existing DB flow (keep data)

1. Back up database first.
2. Inspect flyway history:
- SELECT installed_rank, version, description, script, checksum, success
  FROM backend.flyway_schema_history
  ORDER BY installed_rank;

3. Resolve checksum mismatch for V12:
- Preferred: restore original V12 file content used when DB first migrated.
- Practical alternative: run Flyway repair once for that DB.

4. Resolve version 4.1 mismatch:
- Ensure all machines use same migration files.
- If 4.1 is valid and must run, enable out-of-order only for that execution.

## No mock data requirement

Historical migrations include seed inserts (V1, V3, V8, V10, V9).
To keep migration history immutable, do not edit those files.

After first startup, run optional cleanup script:

- psql -h localhost -U evuser -d evcsms -f scripts/cleanup_seed_data.sql

This removes known demo/test rows while keeping schema intact.
