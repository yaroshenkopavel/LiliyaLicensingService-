# Architecture

## Slice 5 separation

Canonical direction:

`authenticated service request → structural validation → entitlement source/service policy → canonical entitlement → service signing → signed envelope → client frozen verifier/policy`

Hard separation:

`Backend issuance != LicenseDecision != Authority != Execution`

S5.1 contains no transport, production signer, KMS/HSM, database, billing provider, Authority or runtime execution code.
