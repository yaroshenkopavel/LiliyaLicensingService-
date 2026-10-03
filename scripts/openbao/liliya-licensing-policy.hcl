path "auth/token/renew-self" {
  capabilities = ["update"]
}

path "transit/keys/license-signing" {
  capabilities = ["read"]
}

path "transit/sign/license-signing/sha2-256" {
  capabilities = ["update"]
}

path "transit/keys/license-service-state" {
  capabilities = ["read"]
}

path "transit/sign/license-service-state/sha2-256" {
  capabilities = ["update"]
}

path "transit/keys/activation-signing" {
  capabilities = ["read"]
}

path "transit/sign/activation-signing/sha2-256" {
  capabilities = ["update"]
}
