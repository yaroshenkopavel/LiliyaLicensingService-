#!/usr/bin/env bash
set -euo pipefail

OPENBAO_IMAGE="${OPENBAO_IMAGE:-openbao/openbao:2.6.2}"
CONTAINER_NAME="${OPENBAO_CONTAINER_NAME:-liliya-openbao-s7-9b}"
DATA_VOLUME="${OPENBAO_DATA_VOLUME:-liliya-openbao-s7-9b-data}"
AUDIT_VOLUME="${OPENBAO_AUDIT_VOLUME:-liliya-openbao-s7-9b-audit}"
OPENBAO_PORT="${OPENBAO_PORT:-18600}"
OPENBAO_CLUSTER_PORT="${OPENBAO_CLUSTER_PORT:-18601}"
OPENBAO_ADDR="https://127.0.0.1:${OPENBAO_PORT}"
OPENBAO_KEY="${OPENBAO_KEY:-license-signing-s7-9b}"
POST_SNAPSHOT_MARKER_KEY="s7-9b-post-snapshot-marker"

for command in docker curl jq openssl base64 cmp; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required" >&2
    exit 2
  }
done

WORK_DIR="$(mktemp -d)"
chmod 700 "$WORK_DIR"
mkdir -p "$WORK_DIR/config" "$WORK_DIR/tls"

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  docker volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true
  docker volume rm "$AUDIT_VOLUME" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}

diagnose_failure() {
  echo "=== S7.9B OPENBAO SNAPSHOT RESTORE DIAGNOSTIC ===" >&2
  docker logs "$CONTAINER_NAME" 2>&1 | tail -160 >&2 || true
}

trap diagnose_failure ERR
trap cleanup EXIT

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
docker volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true
docker volume rm "$AUDIT_VOLUME" >/dev/null 2>&1 || true
docker volume create "$DATA_VOLUME" >/dev/null
docker volume create "$AUDIT_VOLUME" >/dev/null

docker run --rm --user 0 \
  -v "$DATA_VOLUME:/openbao/data" \
  -v "$AUDIT_VOLUME:/openbao/audit" \
  "$OPENBAO_IMAGE" \
  sh -c 'chown -R openbao:openbao /openbao/data /openbao/audit' >/dev/null

cat > "$WORK_DIR/tls/openssl.cnf" <<'EOF'
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
CN = Liliya S7.9B Recovery CA
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "$WORK_DIR/tls/ca.key" \
  -out "$WORK_DIR/tls/ca.crt" \
  -config "$WORK_DIR/tls/openssl.cnf" >/dev/null 2>&1

cat > "$WORK_DIR/tls/server.cnf" <<'EOF'
[req]
distinguished_name = dn
req_extensions = req_ext
prompt = no
[dn]
CN = localhost
[req_ext]
subjectAltName = @alt_names
[alt_names]
DNS.1 = localhost
IP.1 = 127.0.0.1
EOF

openssl req -newkey rsa:2048 -nodes \
  -keyout "$WORK_DIR/tls/server.key" \
  -out "$WORK_DIR/tls/server.csr" \
  -config "$WORK_DIR/tls/server.cnf" >/dev/null 2>&1

cat > "$WORK_DIR/tls/server-ext.cnf" <<'EOF'
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF

openssl x509 -req -days 1 \
  -in "$WORK_DIR/tls/server.csr" \
  -CA "$WORK_DIR/tls/ca.crt" \
  -CAkey "$WORK_DIR/tls/ca.key" \
  -CAcreateserial \
  -out "$WORK_DIR/tls/server.crt" \
  -extfile "$WORK_DIR/tls/server-ext.cnf" >/dev/null 2>&1

chmod 600 "$WORK_DIR/tls/ca.key"
chmod 644 "$WORK_DIR/tls/server.key" "$WORK_DIR/tls/server.crt" "$WORK_DIR/tls/ca.crt"

cat > "$WORK_DIR/config/openbao.hcl" <<'EOF'
ui = false
disable_mlock = true

storage "raft" {
  path    = "/openbao/data"
  node_id = "liliya-s7-9b-node-1"
}

listener "tcp" {
  address         = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_cert_file   = "/openbao/tls/server.crt"
  tls_key_file    = "/openbao/tls/server.key"
}

api_addr     = "https://127.0.0.1:8200"
cluster_addr = "https://127.0.0.1:8201"

audit "file" "liliya-recovery" {
  description = "Liliya S7.9B recovery audit acceptance"
  options {
    file_path = "/openbao/audit/openbao-audit.log"
    mode      = "0600"
  }
}
EOF

start_server() {
  docker run -d \
    --name "$CONTAINER_NAME" \
    -p "$OPENBAO_PORT:8200" \
    -p "$OPENBAO_CLUSTER_PORT:8201" \
    -v "$DATA_VOLUME:/openbao/data" \
    -v "$AUDIT_VOLUME:/openbao/audit" \
    -v "$WORK_DIR/config:/openbao/config:ro" \
    -v "$WORK_DIR/tls:/openbao/tls:ro" \
    "$OPENBAO_IMAGE" \
    server -config=/openbao/config/openbao.hcl >/dev/null

  local code
  for _ in $(seq 1 60); do
    code="$(curl -sS --cacert "$WORK_DIR/tls/ca.crt" -o /dev/null -w '%{http_code}' \
      "$OPENBAO_ADDR/v1/sys/health" || true)"
    if [[ "$code" == "200" || "$code" == "429" || "$code" == "472" || "$code" == "473" || "$code" == "501" || "$code" == "503" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "OpenBao S7.9B server did not become reachable" >&2
  return 1
}

unseal_server() {
  local status
  status="$(
    curl -sS --cacert "$WORK_DIR/tls/ca.crt" \
      -o "$WORK_DIR/unseal-response.json" \
      -w '%{http_code}' \
      -H "Content-Type: application/json" \
      -X POST \
      -d "$(jq -n --arg key "$UNSEAL_KEY" '{key:$key}')" \
      "$OPENBAO_ADDR/v1/sys/unseal"
  )"
  [[ "$status" == "200" ]] || {
    echo "OpenBao unseal failed with HTTP $status" >&2
    return 1
  }
}

wait_for_active_server() {
  local code
  for _ in $(seq 1 60); do
    code="$(curl -sS --cacert "$WORK_DIR/tls/ca.crt" -o /dev/null -w '%{http_code}' \
      "$OPENBAO_ADDR/v1/sys/health" || true)"
    if [[ "$code" == "200" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "OpenBao S7.9B did not become active" >&2
  return 1
}

ensure_active_after_restore() {
  local health code sealed
  for _ in $(seq 1 90); do
    health="$(curl -sS --cacert "$WORK_DIR/tls/ca.crt" "$OPENBAO_ADDR/v1/sys/health" || true)"
    code="$(curl -sS --cacert "$WORK_DIR/tls/ca.crt" -o /dev/null -w '%{http_code}' \
      "$OPENBAO_ADDR/v1/sys/health" || true)"
    if [[ "$code" == "200" ]]; then
      return 0
    fi
    sealed="$(printf '%s' "$health" | jq -r '.sealed // empty' 2>/dev/null || true)"
    if [[ "$sealed" == "true" ]]; then
      unseal_server || true
    fi
    sleep 1
  done
  echo "OpenBao S7.9B did not become active after snapshot restore" >&2
  return 1
}

echo "=== S7.9B OPENBAO RAFT SNAPSHOT RESTORE ACCEPTANCE ==="
echo "Mode: non-dev / integrated Raft / TLS / normal snapshot restore"

start_server

INIT_RESPONSE="$(
  docker exec \
    -e BAO_ADDR=https://127.0.0.1:8200 \
    -e BAO_CACERT=/openbao/tls/ca.crt \
    "$CONTAINER_NAME" \
    bao operator init -key-shares=1 -key-threshold=1 -format=json
)"
ROOT_TOKEN="$(printf '%s' "$INIT_RESPONSE" | jq -r '.root_token // empty')"
UNSEAL_KEY="$(printf '%s' "$INIT_RESPONSE" | jq -r '.unseal_keys_b64[0] // .keys_base64[0] // empty')"
unset INIT_RESPONSE
[[ -n "$ROOT_TOKEN" && -n "$UNSEAL_KEY" ]]

unseal_server
wait_for_active_server

curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
  -H "X-Vault-Token: $ROOT_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST \
  -d '{"type":"transit"}' \
  "$OPENBAO_ADDR/v1/sys/mounts/transit" >/dev/null

curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
  -H "X-Vault-Token: $ROOT_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST \
  -d '{"type":"ecdsa-p256","exportable":false,"allow_plaintext_backup":false}' \
  "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY" >/dev/null

POLICY='path "transit/keys/'"$OPENBAO_KEY"'" { capabilities = ["read"] }
path "transit/sign/'"$OPENBAO_KEY"'/sha2-256" { capabilities = ["update"] }'

curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
  -H "X-Vault-Token: $ROOT_TOKEN" \
  -H "Content-Type: application/json" \
  -X PUT \
  -d "$(jq -n --arg policy "$POLICY" '{policy:$policy}')" \
  "$OPENBAO_ADDR/v1/sys/policies/acl/liliya-s7-9b-signer" >/dev/null

APP_TOKEN="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
    -H "X-Vault-Token: $ROOT_TOKEN" \
    -H "Content-Type: application/json" \
    -X POST \
    -d '{"policies":["liliya-s7-9b-signer"],"ttl":"1h","renewable":false,"no_default_policy":true}' \
    "$OPENBAO_ADDR/v1/auth/token/create" |
    jq -r '.auth.client_token // empty'
)"
[[ -n "$APP_TOKEN" ]]

RUNTIME_SNAPSHOT_STATUS="$(
  curl -sS --cacert "$WORK_DIR/tls/ca.crt" \
    -o "$WORK_DIR/runtime-snapshot-denied.out" \
    -w '%{http_code}' \
    -H "X-Vault-Token: $APP_TOKEN" \
    "$OPENBAO_ADDR/v1/sys/storage/raft/snapshot"
)"
[[ "$RUNTIME_SNAPSHOT_STATUS" == "403" ]] || {
  echo "Runtime signer unexpectedly acquired snapshot capability: HTTP $RUNTIME_SNAPSHOT_STATUS" >&2
  exit 1
}

KEY_META_BEFORE="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
    -H "X-Vault-Token: $APP_TOKEN" \
    "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY"
)"
printf '%s' "$KEY_META_BEFORE" | jq -e '.data.latest_version == 1' >/dev/null
printf '%s' "$KEY_META_BEFORE" | jq -r '.data.keys["1"].public_key' > "$WORK_DIR/public-before.pem"
grep -F 'BEGIN PUBLIC KEY' "$WORK_DIR/public-before.pem" >/dev/null

printf 'liliya-s7-9b-snapshot-proof' > "$WORK_DIR/message.bin"
INPUT_B64="$(base64 -w0 "$WORK_DIR/message.bin")"
SIGNATURE_BEFORE="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
    -H "X-Vault-Token: $APP_TOKEN" \
    -H "Content-Type: application/json" \
    -X POST \
    -d "$(jq -n --arg input "$INPUT_B64" '{input:$input,key_version:1,hash_algorithm:"sha2-256"}')" \
    "$OPENBAO_ADDR/v1/transit/sign/$OPENBAO_KEY/sha2-256" |
    jq -r '.data.signature // empty'
)"
[[ "$SIGNATURE_BEFORE" == vault:v1:* ]]
printf '%s' "$SIGNATURE_BEFORE" | cut -d: -f3 | base64 -d > "$WORK_DIR/signature-before.der"
openssl dgst -sha256 \
  -verify "$WORK_DIR/public-before.pem" \
  -signature "$WORK_DIR/signature-before.der" \
  "$WORK_DIR/message.bin" >/dev/null

echo "S7.9B stage: save integrated-Raft snapshot"
curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
  -H "X-Vault-Token: $ROOT_TOKEN" \
  "$OPENBAO_ADDR/v1/sys/storage/raft/snapshot" \
  -o "$WORK_DIR/raft.snap"
test -s "$WORK_DIR/raft.snap"

echo "S7.9B stage: mutate authoritative OpenBao state after snapshot"
curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
  -H "X-Vault-Token: $ROOT_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST -d '{}' \
  "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY/rotate" >/dev/null

curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
  -H "X-Vault-Token: $ROOT_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST \
  -d '{"type":"ecdsa-p256","exportable":false,"allow_plaintext_backup":false}' \
  "$OPENBAO_ADDR/v1/transit/keys/$POST_SNAPSHOT_MARKER_KEY" >/dev/null

MUTATED_META="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
    -H "X-Vault-Token: $ROOT_TOKEN" \
    "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY"
)"
printf '%s' "$MUTATED_META" | jq -e '.data.latest_version == 2' >/dev/null

echo "S7.9B stage: restore normal Raft snapshot"
RESTORE_STATUS="$(
  curl -sS --cacert "$WORK_DIR/tls/ca.crt" \
    -o "$WORK_DIR/restore-response.out" \
    -w '%{http_code}' \
    -H "X-Vault-Token: $ROOT_TOKEN" \
    -H "Content-Type: application/octet-stream" \
    -X POST \
    --data-binary @"$WORK_DIR/raft.snap" \
    "$OPENBAO_ADDR/v1/sys/storage/raft/snapshot"
)"
[[ "$RESTORE_STATUS" == "200" || "$RESTORE_STATUS" == "204" ]] || {
  echo "Normal Raft snapshot restore failed with HTTP $RESTORE_STATUS" >&2
  cat "$WORK_DIR/restore-response.out" >&2 || true
  exit 1
}

ensure_active_after_restore

RESTORED_META="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
    -H "X-Vault-Token: $APP_TOKEN" \
    "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY"
)"
printf '%s' "$RESTORED_META" | jq -e '.data.latest_version == 1' >/dev/null
printf '%s' "$RESTORED_META" | jq -e '.data.keys["1"] != null' >/dev/null
printf '%s' "$RESTORED_META" | jq -e '.data.keys["2"] == null' >/dev/null
printf '%s' "$RESTORED_META" | jq -r '.data.keys["1"].public_key' > "$WORK_DIR/public-after.pem"
cmp "$WORK_DIR/public-before.pem" "$WORK_DIR/public-after.pem"

MARKER_STATUS="$(
  curl -sS --cacert "$WORK_DIR/tls/ca.crt" \
    -o "$WORK_DIR/marker-after-restore.out" \
    -w '%{http_code}' \
    -H "X-Vault-Token: $ROOT_TOKEN" \
    "$OPENBAO_ADDR/v1/transit/keys/$POST_SNAPSHOT_MARKER_KEY"
)"
[[ "$MARKER_STATUS" == "404" ]] || {
  echo "Post-snapshot marker survived restore: HTTP $MARKER_STATUS" >&2
  exit 1
}

openssl dgst -sha256 \
  -verify "$WORK_DIR/public-after.pem" \
  -signature "$WORK_DIR/signature-before.der" \
  "$WORK_DIR/message.bin" >/dev/null

SIGNATURE_AFTER="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt" \
    -H "X-Vault-Token: $APP_TOKEN" \
    -H "Content-Type: application/json" \
    -X POST \
    -d "$(jq -n --arg input "$INPUT_B64" '{input:$input,key_version:1,hash_algorithm:"sha2-256"}')" \
    "$OPENBAO_ADDR/v1/transit/sign/$OPENBAO_KEY/sha2-256" |
    jq -r '.data.signature // empty'
)"
[[ "$SIGNATURE_AFTER" == vault:v1:* ]]

docker exec "$CONTAINER_NAME" sh -c 'test -s /openbao/audit/openbao-audit.log'

echo 'LICENSING_S7_9B_EVIDENCE={"nonDevMode":true,"integratedRaft":true,"tls":true,"normalSnapshotSave":true,"normalSnapshotRestore":true,"forceRestore":false,"postSnapshotMutationRemoved":true,"exactKeyVersionRestored":true,"publicKeyContinuity":true,"preSnapshotSignatureStillVerifies":true,"signingAfterRestore":true,"runtimeSnapshotCapabilityDenied":true,"auditDevice":true}'
echo "=== S7.9B OPENBAO RAFT SNAPSHOT RESTORE PASS ==="
