#!/usr/bin/env bash
set -euo pipefail

POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:17}"
CONTAINER_NAME="${S7_5_POSTGRES_CONTAINER:-liliya-postgres-s7-5}"
HOST_PORT="${S7_5_POSTGRES_PORT:-18543}"
DATABASE="liliya_licensing_s7_5"
ADMIN_USER="postgres"
RUNTIME_USER="liliya_runtime_s7_5"
ADMIN_PASSWORD="$(openssl rand -hex 24)"
RUNTIME_PASSWORD="$(openssl rand -hex 24)"
WORK_DIR="$(mktemp -d)"
BACKUP_PATH="$WORK_DIR/licensing-s7-5.dump"

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

for command in docker openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required" >&2
    exit 2
  }
done

if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "Gradle is required" >&2
  exit 2
fi

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

docker run -d   --name "$CONTAINER_NAME"   -p "$HOST_PORT:5432"   -e POSTGRES_PASSWORD="$ADMIN_PASSWORD"   -e POSTGRES_DB="$DATABASE"   "$POSTGRES_IMAGE" >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$CONTAINER_NAME" pg_isready -U "$ADMIN_USER" -d "$DATABASE" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec "$CONTAINER_NAME" pg_isready -U "$ADMIN_USER" -d "$DATABASE" >/dev/null

export S7_5_POSTGRES_URL="jdbc:postgresql://127.0.0.1:$HOST_PORT/$DATABASE"
export S7_5_POSTGRES_ADMIN_USER="$ADMIN_USER"
export S7_5_POSTGRES_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export S7_5_POSTGRES_RUNTIME_USER="$RUNTIME_USER"
export S7_5_POSTGRES_RUNTIME_PASSWORD="$RUNTIME_PASSWORD"

echo "=== S7.5 SEED AUTHORITATIVE STATE ==="
"${GRADLE[@]}" :issuer-postgres:test   --tests "pro.liliya.licensing.postgres.PostgreSqlBackupRestoreAcceptanceTest.seed_authoritative_state_for_backup"   --rerun-tasks   --console=plain

echo "=== S7.5 CREATE LEAST-PRIVILEGE RUNTIME ROLE ==="
docker exec   -e PGPASSWORD="$ADMIN_PASSWORD"   "$CONTAINER_NAME"   psql -v ON_ERROR_STOP=1     -U "$ADMIN_USER"     -d "$DATABASE"     -v runtime_user="$RUNTIME_USER"     -v runtime_password="$RUNTIME_PASSWORD"     -c "CREATE ROLE $RUNTIME_USER LOGIN PASSWORD '$RUNTIME_PASSWORD' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"     -c "GRANT CONNECT ON DATABASE $DATABASE TO $RUNTIME_USER"     -c "GRANT USAGE ON SCHEMA public TO $RUNTIME_USER"     -c "GRANT SELECT, INSERT, UPDATE ON TABLE licensing_decision_state TO $RUNTIME_USER"     >/dev/null

ROLE_FLAGS="$(
  docker exec     -e PGPASSWORD="$ADMIN_PASSWORD"     "$CONTAINER_NAME"     psql -At -U "$ADMIN_USER" -d "$DATABASE"     -c "SELECT rolsuper || ':' || rolcreatedb || ':' || rolcreaterole || ':' || rolreplication || ':' || rolbypassrls FROM pg_roles WHERE rolname = '$RUNTIME_USER'"
)"
[[ "$ROLE_FLAGS" == "false:false:false:false:false" ]] || {
  echo "S7.5 runtime role unexpectedly has administrative privilege" >&2
  exit 1
}

echo "=== S7.5 CUSTOM-FORMAT BACKUP ==="
docker exec   -e PGPASSWORD="$ADMIN_PASSWORD"   "$CONTAINER_NAME"   pg_dump     -U "$ADMIN_USER"     -d "$DATABASE"     --format=custom     --no-owner     --no-acl   > "$BACKUP_PATH"

[[ -s "$BACKUP_PATH" ]] || {
  echo "S7.5 backup archive is empty" >&2
  exit 1
}

docker exec -i "$CONTAINER_NAME" pg_restore --list < "$BACKUP_PATH"   | grep -F "TABLE DATA public licensing_decision_state" >/dev/null

echo "=== S7.5 DESTROY AND RECREATE DATABASE ==="
docker exec   -e PGPASSWORD="$ADMIN_PASSWORD"   "$CONTAINER_NAME"   psql -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d postgres   -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DATABASE' AND pid <> pg_backend_pid()"   -c "DROP DATABASE $DATABASE"   -c "CREATE DATABASE $DATABASE TEMPLATE template0"   >/dev/null

echo "=== S7.5 RESTORE DATABASE ==="
docker exec -i   -e PGPASSWORD="$ADMIN_PASSWORD"   "$CONTAINER_NAME"   pg_restore     --exit-on-error     --no-owner     --no-acl     -U "$ADMIN_USER"     -d "$DATABASE"   < "$BACKUP_PATH"

docker exec   -e PGPASSWORD="$ADMIN_PASSWORD"   "$CONTAINER_NAME"   psql -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$DATABASE"   -c "GRANT USAGE ON SCHEMA public TO $RUNTIME_USER"   -c "GRANT SELECT, INSERT, UPDATE ON TABLE licensing_decision_state TO $RUNTIME_USER"   >/dev/null

echo "=== S7.5 VERIFY RESTORE AND ADVANCE ==="
"${GRADLE[@]}" :issuer-postgres:test   --tests "pro.liliya.licensing.postgres.PostgreSqlBackupRestoreAcceptanceTest.restored_authoritative_state_is_exact_and_runtime_can_advance_monotonically"   --tests "pro.liliya.licensing.postgres.PostgreSqlBackupRestoreAcceptanceTest.runtime_role_has_no_schema_creation_privilege"   --rerun-tasks   --console=plain

echo 'LICENSING_S7_5_POSTGRES_EVIDENCE={"postgres17":true,"customFormatBackup":true,"authoritativeStateSeededByApplicationPort":true,"databaseDestroyedBeforeRestore":true,"restoreSucceeded":true,"exactReplayRevocationRestored":true,"exactEnvelopeLineageRestored":true,"runtimeRoleLeastPrivilege":true,"runtimeSchemaDdlDenied":true,"postRestoreMonotonicAdvance":true,"hiddenRetry":false}'
echo "=== S7.5 POSTGRES BACKUP RESTORE ACCEPTANCE PASS ==="
