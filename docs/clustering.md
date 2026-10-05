# Clustering: horizontal scaling on NATS JetStream

One published image, selected at runtime: `eddi.messaging.type=in-memory`
(the default) runs a single node with no dependencies; `eddi.messaging.type=nats`
lets any number of EDDI replicas share one database and one NATS JetStream
cluster behind a plain round-robin load balancer.

The full operator guide — topology, the ordering contract, the degraded-mode
contract, failure modes, sizing and the runbook — is completed in a later
section of this change. Every property is listed in the
[Configuration Reference](configuration-reference.md#clustering-only-when-eddimessagingtypenats).
