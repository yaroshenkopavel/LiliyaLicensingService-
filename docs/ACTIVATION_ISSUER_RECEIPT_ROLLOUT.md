# Activation issuer receipt rollout

The first successful activation ISSUE writes `licensing_issue_receipt` in the same PostgreSQL
transaction as `licensing_decision_state`. If activation completion fails later, the same claimed
code, request ID and install binding can retry and receive the original signed envelope. The
receipt is authoritative data: include it in database backups and restore it with decision state.

## Existing database

Before deploying the runtime binary, an administrator must run the following migration in the
same database that holds `licensing_decision_state`. Production runtime users should not have
schema creation privileges. `PostgreSqlDecisionTransactionPort.initializeSchema()` creates the
same table for administrative bootstrap and integration tests.

```sql
CREATE TABLE IF NOT EXISTS licensing_issue_receipt (
    request_id TEXT PRIMARY KEY,
    request_subject TEXT NOT NULL,
    request_product_id TEXT NOT NULL,
    replay_sequence BIGINT NOT NULL,
    revocation_epoch BIGINT NOT NULL,
    envelope_schema_version BIGINT NOT NULL,
    algorithm TEXT NOT NULL,
    signing_key_reference TEXT NOT NULL,
    canonical_payload BYTEA NOT NULL,
    signature BYTEA NOT NULL
);
```

Grant the runtime role `SELECT, INSERT` on this table. It needs no `UPDATE` or `DELETE` privilege.
Verify that the production readiness check can select from the table before routing traffic.
Back up both tables together. Do not delete receipts during rollback: an issued activation can
still be awaiting completion and its original signature must survive a deployment rollback.

## Acceptance before merge

- PostgreSQL integration: first ISSUE, later unrelated ISSUE for the same scope, then replay of
  the original request returns exactly the original payload and signature without signing again.
- A forced receipt insert failure rolls back the replay state update.
- Force activation `complete()` to fail once after issuer commit; retry the same code/request/install
  binding and verify the original signed envelope and one replay-sequence increment.
- Repeat after code expiry, provided its original claim happened before expiry; new claims after
  expiry remain rejected.
- Run the authenticated production entrypoint, backup/restore, privacy and secret-guard acceptance
  suites on the exact new HEAD. Do not merge while these checks are unavailable or red.
