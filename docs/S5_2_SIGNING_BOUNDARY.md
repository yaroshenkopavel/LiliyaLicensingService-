# S5.2 Signing Boundary

Status: implementation candidate.

Production interface:

`sign(canonicalPayload, exactKeyReference) -> Signed | typed failure`

Hard rules:

- the entitlement's signing-key identity selects one exact key reference;
- no fallback to another active/older key;
- retired or unavailable key fails closed;
- returned envelope must preserve the exact canonical bytes it signed;
- private key material is never part of the API/result;
- production KMS/HSM adapter is not implemented in S5.2.

Executable tests use an in-memory Ed25519 fixture only.
