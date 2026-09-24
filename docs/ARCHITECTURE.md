# Architecture

## Current separation

Canonical direction:

`authenticated service request → structural validation → entitlement source/service policy → canonical entitlement → service signing → signed envelope → frozen client verifier/policy`

For authenticated service-state evidence:

`backend authoritative state → authenticated service-state envelope → frozen client verification → accepted state snapshot → LicensePolicy context`

Hard separation:

`Backend issuance != LicenseDecision != Authority != Execution`

The backend may issue signed entitlement/state evidence, but it does not grant runtime Authority and it does not execute privileged client actions.

## Current backend components

The current repository contains separate modules for:

- issuer core contracts and canonical entitlement handling;
- transport contract and HTTP/HTTPS endpoint/listener;
- request authentication;
- entitlement-source SPI;
- PostgreSQL persistence and PostgreSQL deployment entitlement provider;
- OpenBao Transit signing;
- deployment/runtime composition;
- observability;
- acceptance/testkit support.

PostgreSQL is the authoritative server-side source for durable licensing state used by the production entitlement source. Request subject/product fields are lookup keys only; a request cannot mint entitlement data by itself.

OpenBao Transit remains the signing boundary. Private signing material must not leave that boundary or enter source control.

## Client boundary

Frozen LiliyaCore verification and policy semantics remain client-owned. Service-state acceptance feeds LicensePolicy context and must stop before runtime Authority/Execution.

## Historical note

Early S5.1/S5.2 documents describe the initial server-neutral and signing-boundary slices. They are retained as design history and should not be interpreted as the current repository-wide implementation status.
