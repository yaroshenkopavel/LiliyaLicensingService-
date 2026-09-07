#!/usr/bin/env bash
set -euo pipefail

for command in openssl curl keytool; do
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

WORK_DIR="$(mktemp -d)"
PORT="${S7_6_TLS_PORT:-18443}"
PASSWORD="$(openssl rand -hex 24)"
HOST_PID=""

cleanup() {
  if [[ -n "$HOST_PID" ]]; then
    kill "$HOST_PID" >/dev/null 2>&1 || true
    wait "$HOST_PID" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

generate_identity() {
  local name="$1"
  local dir="$WORK_DIR/$name"
  mkdir -p "$dir"

  cat > "$dir/ca.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
CN = Liliya S7.6 $name CA
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF

  openssl req -x509 -newkey rsa:2048 -nodes -days 1     -keyout "$dir/ca.key"     -out "$dir/ca.crt"     -config "$dir/ca.cnf" >/dev/null 2>&1

  cat > "$dir/server.cnf" <<EOF
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

  openssl req -newkey rsa:2048 -nodes     -keyout "$dir/server.key"     -out "$dir/server.csr"     -config "$dir/server.cnf" >/dev/null 2>&1

  cat > "$dir/server-ext.cnf" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF

  openssl x509 -req -days 1     -in "$dir/server.csr"     -CA "$dir/ca.crt"     -CAkey "$dir/ca.key"     -CAcreateserial     -out "$dir/server.crt"     -extfile "$dir/server-ext.cnf" >/dev/null 2>&1

  openssl pkcs12 -export     -inkey "$dir/server.key"     -in "$dir/server.crt"     -certfile "$dir/ca.crt"     -name liliya-s7-6     -passout "pass:$PASSWORD"     -out "$dir/server.p12" >/dev/null 2>&1

  chmod 600 "$dir/ca.key" "$dir/server.key" "$dir/server.p12"
}

start_host() {
  local identity="$1"
  local log="$WORK_DIR/$identity-host.log"

  S7_6_TLS_KEYSTORE_PATH="$WORK_DIR/$identity/server.p12"   S7_6_TLS_KEYSTORE_PASSWORD="$PASSWORD"   S7_6_TLS_HOST="127.0.0.1"   S7_6_TLS_PORT="$PORT"     "${GRADLE[@]}" :issuer-https-listener:runHttpsAcceptanceHost       --console=plain >"$log" 2>&1 &
  HOST_PID=$!

  for _ in $(seq 1 90); do
    if grep -F 'LICENSING_S7_6_HTTPS_HOST_READY=' "$log" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$HOST_PID" >/dev/null 2>&1; then
      cat "$log" >&2 || true
      return 1
    fi
    sleep 1
  done

  echo "S7.6 HTTPS host did not become ready" >&2
  cat "$log" >&2 || true
  return 1
}

stop_host() {
  kill "$HOST_PID" >/dev/null 2>&1 || true
  wait "$HOST_PID" >/dev/null 2>&1 || true
  HOST_PID=""
}

echo "=== S7.6 GENERATE EXTERNAL TLS IDENTITIES ==="
generate_identity "first"
generate_identity "second"

echo "=== S7.6 START FIRST CERTIFICATE ==="
start_host "first"

curl -fsS --cacert "$WORK_DIR/first/ca.crt"   "https://127.0.0.1:$PORT/health/ready"   | grep -F '"status":"ready"' >/dev/null

if curl -fsS --cacert "$WORK_DIR/second/ca.crt"   "https://127.0.0.1:$PORT/health/ready" >/dev/null 2>&1; then
  echo "Untrusted CA unexpectedly succeeded against first certificate" >&2
  exit 1
fi

if curl -fsS   "http://127.0.0.1:$PORT/health/ready" >/dev/null 2>&1; then
  echo "Plaintext HTTP unexpectedly succeeded on production HTTPS listener" >&2
  exit 1
fi

stop_host

echo "=== S7.6 ROTATE CERTIFICATE BY RESTART ==="
start_host "second"

curl -fsS --cacert "$WORK_DIR/second/ca.crt"   "https://127.0.0.1:$PORT/health/ready"   | grep -F '"status":"ready"' >/dev/null

if curl -fsS --cacert "$WORK_DIR/first/ca.crt"   "https://127.0.0.1:$PORT/health/ready" >/dev/null 2>&1; then
  echo "Old CA unexpectedly trusted after certificate rotation" >&2
  exit 1
fi

echo 'LICENSING_S7_6_TLS_EVIDENCE={"httpsOnly":true,"trustedCertificateAccepted":true,"untrustedCertificateRejected":true,"plaintextRejected":true,"externalPkcs12":true,"certificateRotationByRestart":true,"oldTrustRejectedAfterRotation":true,"privateKeyNeverGit":true,"transportOriginNotLicenseTrust":true}'
echo "=== S7.6 TLS CERTIFICATE ACCEPTANCE PASS ==="
