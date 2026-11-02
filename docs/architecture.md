# Architecture Map

`docs/微服务划分.md` defines the eight business boundaries. The root Maven
project aggregates `common`, `gateway`, the service modules, and their public
contracts under `apis/`.

```text
clients -> gateway -> account / transaction / payment-adapter / clearing
                   -> notification / risk / access / device

transaction -> account-api, risk-api
access -> access-api, device-api
device-api -> internal command and event contracts
all services -> common
```

The transaction service orchestrates funds changes through service contracts;
only account service owns account balance writes. Access policy snapshots and
device delivery remain separate concerns. Monitoring is composed only from
`deploy/monitoring/`.

Service modules currently provide runnable Spring Boot and Actuator skeletons.
Domain endpoints, schemas, and external provider integrations are added when
their contracts are defined; this architecture scaffold does not invent them.
