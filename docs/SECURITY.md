# Security

This repository is designed to remain safe under public source visibility.

## Secret policy

Production secrets are prohibited from source, Git history and normal CI artifacts.

Never commit:

- production signing private keys;
- OpenBao production tokens or recovery material;
- KMS/HSM credentials;
- database credentials;
- payment/provider credentials;
- bearer/API tokens;
- private production entitlement snapshots.

CI fixtures use only explicit development/test identities and disposable credentials.

## Trust model

Security does not rely on this repository being private.

Important boundaries remain:

- signing private material stays behind the reviewed signing-service boundary;
- OpenBao runtime identity is least privilege;
- PostgreSQL owns replay/revocation authoritative state;
- transport origin is not License trust;
- signed evidence still requires frozen client verification and policy;
- License is not Authority and is not Execution permission.

## Public-repository workflow policy

Public pull-request workflows must:

- use read-only repository permissions unless a separately reviewed write is required;
- not use `pull_request_target` without a dedicated security review;
- not expose production secrets to untrusted fork code;
- not publish raw entitlement/private-subject/signature material as normal logs.

The Secret Guard performs both current-tree and reachable-history high-confidence secret scanning.

## Reporting

Do not publish real credentials or private exploit material in a public issue.

Use GitHub's private vulnerability-reporting/security-advisory channel when available, or contact the repository owner privately through the GitHub account before disclosing sensitive details publicly.
