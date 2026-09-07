# S5.3 Entitlement Source + Decision Transaction

Status: implementation candidate.

Direction:

`validated request → explicit entitlement source → authoritative source record → serialized decision transaction → canonical entitlement → exact signing boundary → committed decision lineage`.

Rules:

- request fields are lookup/evidence, not entitlement authority;
- ineligible source result never reaches signer;
- source/provider failure never mints entitlement;
- same decision scope is serialized by the transaction port;
- S5.3 in-memory transaction adapter is test evidence only;
- durable replay/revocation authority remains S5.4.
