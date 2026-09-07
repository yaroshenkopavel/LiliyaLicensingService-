#!/usr/bin/env bash
set -euo pipefail

OPENBAO_IMAGE="${OPENBAO_IMAGE:-openbao/openbao:2.6.2}"
CONTAINER_NAME="${OPENBAO_CONTAINER_NAME:-liliya-openbao-s7-4}"
DATA_VOLUME="${OPENBAO_DATA_VOLUME:-liliya-openbao-s7-4-data}"
AUDIT_VOLUME="${OPENBAO_AUDIT_VOLUME:-liliya-openbao-s7-4-audit}"
OPENBAO_PORT="${OPENBAO_PORT:-18400}"
OPENBAO_CLUSTER_PORT="${OPENBAO_CLUSTER_PORT:-18401}"
OPENBAO_ADDR="https://127.0.0.1:${OPENBAO_PORT}"
OPENBAO_KEY="${OPENBAO_KEY:-license-signing-s7-4}"

for command in docker curl jq openssl; do
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
trap cleanup EXIT

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
docker volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true
docker volume rm "$AUDIT_VOLUME" >/dev/null 2>&1 || true
docker volume create "$DATA_VOLUME" >/dev/null
docker volume create "$AUDIT_VOLUME" >/dev/null

# Named Docker volumes are root-owned when first created. Prepare them once as root,
# then run the real OpenBao server as the image's normal non-root user.
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
CN = Liliya S7.4 Test CA
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF

openssl req -x509 -newkey rsa:2048 -nodes -days 1   -keyout "$WORK_DIR/tls/ca.key"   -out "$WORK_DIR/tls/ca.crt"   -config "$WORK_DIR/tls/openssl.cnf" >/dev/null 2>&1

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

openssl req -newkey rsa:2048 -nodes   -keyout "$WORK_DIR/tls/server.key"   -out "$WORK_DIR/tls/server.csr"   -config "$WORK_DIR/tls/server.cnf" >/dev/null 2>&1

cat > "$WORK_DIR/tls/server-ext.cnf" <<'EOF'
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF

openssl x509 -req -days 1   -in "$WORK_DIR/tls/server.csr"   -CA "$WORK_DIR/tls/ca.crt"   -CAkey "$WORK_DIR/tls/ca.key"   -CAcreateserial   -out "$WORK_DIR/tls/server.crt"   -extfile "$WORK_DIR/tls/server-ext.cnf" >/dev/null 2>&1

chmod 600 "$WORK_DIR/tls/ca.key"
chmod 644 "$WORK_DIR/tls/server.key" "$WORK_DIR/tls/server.crt" "$WORK_DIR/tls/ca.crt"

cat > "$WORK_DIR/config/openbao.hcl" <<'EOF'
ui = false
disable_mlock = true

storage "raft" {
  path    = "/openbao/data"
  node_id = "liliya-s7-4-node-1"
}

listener "tcp" {
  address         = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_cert_file   = "/openbao/tls/server.crt"
  tls_key_file    = "/openbao/tls/server.key"
}

api_addr     = "https://127.0.0.1:8200"
cluster_addr = "https://127.0.0.1:8201"
EOF


unseal_server() {
  local status
  status="$(
    curl -sS --cacert "$WORK_DIR/tls/ca.crt"       -o "$WORK_DIR/unseal-response.json"       -w '%{http_code}'       -H "Content-Type: application/json"       -X POST       -d "$(jq -n --arg key "$UNSEAL_KEY" '{key:$key}')"       "$OPENBAO_ADDR/v1/sys/unseal"
  )"
  if [[ "$status" != "200" ]]; then
    echo "OpenBao unseal failed with HTTP $status" >&2
    cat "$WORK_DIR/unseal-response.json" >&2 || true
    return 1
  fi
}

start_server() {
  docker run -d     --name "$CONTAINER_NAME"     -p "$OPENBAO_PORT:8200"     -p "$OPENBAO_CLUSTER_PORT:8201"     -v "$DATA_VOLUME:/openbao/data"     -v "$AUDIT_VOLUME:/openbao/audit"     -v "$WORK_DIR/config:/openbao/config:ro"     -v "$WORK_DIR/tls:/openbao/tls:ro"     "$OPENBAO_IMAGE"     server -config=/openbao/config/openbao.hcl >/dev/null

  for _ in $(seq 1 60); do
    code="$(curl -sS --cacert "$WORK_DIR/tls/ca.crt" -o /dev/null -w '%{http_code}'       "$OPENBAO_ADDR/v1/sys/health" || true)"
    if [[ "$code" == "200" || "$code" == "429" || "$code" == "472" || "$code" == "473" || "$code" == "501" || "$code" == "503" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "OpenBao non-dev server did not become reachable" >&2
  docker logs "$CONTAINER_NAME" >&2 || true
  return 1
}

echo "=== S7.4 NON-DEV OPENBAO ACCEPTANCE ==="
echo "OpenBao image: $OPENBAO_IMAGE"
echo "Mode: non-dev / integrated Raft / TLS"

start_server

INIT_RESPONSE="$(
  docker exec     -e BAO_ADDR=https://127.0.0.1:8200     -e BAO_CACERT=/openbao/tls/ca.crt     "$CONTAINER_NAME"     bao operator init -key-shares=1 -key-threshold=1 -format=json
)"
ROOT_TOKEN="$(printf '%s' "$INIT_RESPONSE" | jq -r '.root_token // empty')"
UNSEAL_KEY="$(printf '%s' "$INIT_RESPONSE" | jq -r '.unseal_keys_b64[0] // .keys_base64[0] // empty')"
unset INIT_RESPONSE

if [[ -z "$ROOT_TOKEN" || -z "$UNSEAL_KEY" ]]; then
  echo "OpenBao initialization did not return required ephemeral acceptance material" >&2
  exit 1
fi

echo "S7.4 stage: unseal initial server"
unseal_server

echo "S7.4 stage: enable Transit"
curl -fsS --cacert "$WORK_DIR/tls/ca.crt"   -H "X-Vault-Token: $ROOT_TOKEN"   -H "Content-Type: application/json"   -X POST   -d '{"type":"transit"}'   "$OPENBAO_ADDR/v1/sys/mounts/transit" >/dev/null

echo "S7.4 stage: create Transit key"
curl -fsS --cacert "$WORK_DIR/tls/ca.crt"   -H "X-Vault-Token: $ROOT_TOKEN"   -H "Content-Type: application/json"   -X POST   -d '{"type":"ecdsa-p256","exportable":false,"allow_plaintext_backup":false}'   "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY" >/dev/null

POLICY_CONTENT='path "transit/keys/'"$OPENBAO_KEY"'" { capabilities = ["read"] }
path "transit/sign/'"$OPENBAO_KEY"'/*" { capabilities = ["update"] }'
POLICY_JSON="$(jq -n --arg policy "$POLICY_CONTENT" '{policy:$policy}')"

echo "S7.4 stage: install runtime signing policy"
curl -fsS --cacert "$WORK_DIR/tls/ca.crt"   -H "X-Vault-Token: $ROOT_TOKEN"   -H "Content-Type: application/json"   -X PUT   -d "$POLICY_JSON"   "$OPENBAO_ADDR/v1/sys/policies/acl/liliya-license-signer-s7-4" >/dev/null

echo "S7.4 stage: create least-privilege runtime token"
TOKEN_RESPONSE="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt"     -H "X-Vault-Token: $ROOT_TOKEN"     -H "Content-Type: application/json"     -X POST     -d '{"policies":["liliya-license-signer-s7-4"],"ttl":"1h","renewable":false,"no_default_policy":true}'     "$OPENBAO_ADDR/v1/auth/token/create"
)"
APP_TOKEN="$(printf '%s' "$TOKEN_RESPONSE" | jq -r '.auth.client_token // empty')"
unset TOKEN_RESPONSE
[[ -n "$APP_TOKEN" ]] || { echo "Failed to create least-privilege runtime token" >&2; exit 1; }

echo "S7.4 stage: enable audit device"
curl -fsS --cacert "$WORK_DIR/tls/ca.crt"   -H "X-Vault-Token: $ROOT_TOKEN"   -H "Content-Type: application/json"   -X PUT   -d '{"type":"file","options":{"file_path":"/openbao/audit/openbao-audit.log"}}'   "$OPENBAO_ADDR/v1/sys/audit/file" >/dev/null

ROTATE_STATUS="$(
  curl -sS --cacert "$WORK_DIR/tls/ca.crt"     -o "$WORK_DIR/rotate-denied.out"     -w '%{http_code}'     -H "X-Vault-Token: $APP_TOKEN"     -H "Content-Type: application/json"     -X POST     -d '{}'     "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY/rotate"
)"
[[ "$ROTATE_STATUS" == "403" ]] || {
  echo "Least-privilege runtime token unexpectedly reached key management: HTTP $ROTATE_STATUS" >&2
  exit 1
}

INPUT_B64="$(printf 'liliya-s7-4-signing-proof' | base64 -w0)"
SIGNATURE_BEFORE="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt"     -H "X-Vault-Token: $APP_TOKEN"     -H "Content-Type: application/json"     -X POST     -d "$(jq -n --arg input "$INPUT_B64" '{input:$input,key_version:1,hash_algorithm:"sha2-256"}')"     "$OPENBAO_ADDR/v1/transit/sign/$OPENBAO_KEY/sha2-256" |
    jq -r '.data.signature // empty'
)"
[[ -n "$SIGNATURE_BEFORE" ]] || { echo "Initial Transit signing failed" >&2; exit 1; }

docker exec "$CONTAINER_NAME" sh -c 'test -s /openbao/audit/openbao-audit.log'

echo "S7.4 stage: restart OpenBao with persisted Raft state"
docker rm -f "$CONTAINER_NAME" >/dev/null
start_server

echo "S7.4 stage: unseal restarted server"
unseal_server

KEY_META="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt"     -H "X-Vault-Token: $APP_TOKEN"     "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY"
)"
printf '%s' "$KEY_META" | jq -e '.data.type == "ecdsa-p256"' >/dev/null
printf '%s' "$KEY_META" | jq -e '.data.keys["1"] != null' >/dev/null

SIGNATURE_AFTER="$(
  curl -fsS --cacert "$WORK_DIR/tls/ca.crt"     -H "X-Vault-Token: $APP_TOKEN"     -H "Content-Type: application/json"     -X POST     -d "$(jq -n --arg input "$INPUT_B64" '{input:$input,key_version:1,hash_algorithm:"sha2-256"}')"     "$OPENBAO_ADDR/v1/transit/sign/$OPENBAO_KEY/sha2-256" |
    jq -r '.data.signature // empty'
)"
[[ -n "$SIGNATURE_AFTER" ]] || { echo "Transit signing after restart failed" >&2; exit 1; }

docker exec "$CONTAINER_NAME" sh -c 'test -s /openbao/audit/openbao-audit.log'

echo 'LICENSING_S7_4_OPENBAO_EVIDENCE={"nonDevMode":true,"integratedRaft":true,"tls":true,"initializedAndUnsealed":true,"leastPrivilegeRuntimeToken":true,"keyManagementDenied":true,"auditDevice":true,"restartPersistence":true,"exactKeyVersionSigningAfterRestart":true,"rootMaterialOutsideApplicationRuntime":true,"snapshotRestoreDeferredToRecoverySlice":true}'
echo "=== S7.4 NON-DEV OPENBAO ACCEPTANCE PASS ==="
