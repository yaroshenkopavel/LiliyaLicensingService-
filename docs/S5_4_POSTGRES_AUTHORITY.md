# S5.4 PostgreSQL Replay / Revocation Authority

Status: implementation candidate.

This slice replaces test-only in-memory decision ownership with a real PostgreSQL transactional
authority for replay sequence, revocation epoch and the exact signed result lineage.

Hard properties:

- exact subject/product scope is serialized inside PostgreSQL;
- replay sequence must advance strictly;
- revocation epoch never moves backwards;
- signed envelope bytes commit atomically with the authoritative state;
- stale/non-advancing candidates fail closed;
- a fresh process/port instance reopens committed state;
- concurrent same-scope writers produce no lost update;
- no client request field becomes server authority merely by being supplied.

The implementation uses transaction-scoped PostgreSQL advisory locking plus exact-row update checks.
Production deployment credentials are not stored in the repository.
