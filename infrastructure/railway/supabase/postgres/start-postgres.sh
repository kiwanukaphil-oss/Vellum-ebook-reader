#!/usr/bin/env bash
set -Eeuo pipefail

database_server_pid=""

# Relays Railway's shutdown signal to PostgreSQL so the database can checkpoint
# and stop cleanly before the container exits.
forward_database_signal() {
  local signal_name="$1"
  if [[ -n "$database_server_pid" ]] && kill -0 "$database_server_pid" 2>/dev/null; then
    kill -s "$signal_name" "$database_server_pid"
  fi
}

trap 'forward_database_signal TERM' TERM
trap 'forward_database_signal INT' INT

docker-entrypoint.sh postgres \
  -c config_file=/etc/postgresql/postgresql.conf \
  -c "data_directory=${PGDATA:-/var/lib/postgresql/data/pgdata}" \
  -c log_min_messages=fatal &
database_server_pid=$!

# The first start may include initdb and Supabase's bootstrap scripts. Wait for
# that complete server before applying the current Railway password to every
# role used by Vellum.
database_exit_code=0
until pg_isready \
  --host /var/run/postgresql \
  --port "${PGPORT:-5432}" \
  --username supabase_admin >/dev/null 2>&1; do
  if ! kill -0 "$database_server_pid" 2>/dev/null; then
    wait "$database_server_pid" || database_exit_code=$?
    exit "$database_exit_code"
  fi
  sleep 1
done

psql \
  --host /var/run/postgresql \
  --port "${PGPORT:-5432}" \
  --username supabase_admin \
  --dbname postgres \
  --set ON_ERROR_STOP=1 \
  --file /docker-entrypoint-initdb.d/init-scripts/99-roles.sql

wait "$database_server_pid"
