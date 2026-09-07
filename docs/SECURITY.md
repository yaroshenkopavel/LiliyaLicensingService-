# Security

Production secrets are prohibited from source and normal CI artifacts.

Never commit:

- production signing private keys;
- KMS/HSM credentials;
- database credentials;
- payment/provider credentials;
- bearer/API tokens;
- private production entitlement snapshots.

S5.1 uses only deterministic fake/test signing material with explicit `test-` identity.
