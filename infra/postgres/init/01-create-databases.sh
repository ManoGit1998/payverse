#!/usr/bin/env bash
# Creates one database + one owner role per service (database-per-service pattern).
# Runs once, on first start of an empty Postgres volume.
set -euo pipefail

: "${SERVICE_DB_PASSWORD:?SERVICE_DB_PASSWORD must be set}"

for svc in auth user wallet txn payment bill rewards notification fraud recon admin; do
  echo "Creating database ${svc}_db owned by ${svc}_svc"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${svc}_svc') THEN
        CREATE ROLE ${svc}_svc LOGIN PASSWORD '${SERVICE_DB_PASSWORD}';
      END IF;
    END
    \$\$;
    SELECT 'CREATE DATABASE ${svc}_db OWNER ${svc}_svc'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${svc}_db')\gexec
    REVOKE ALL ON DATABASE ${svc}_db FROM PUBLIC;
SQL
done
