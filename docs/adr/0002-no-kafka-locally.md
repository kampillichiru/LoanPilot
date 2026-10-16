# ADR-0002: DB-backed state machine instead of Kafka for local development

- **Status:** Accepted
- **Date:** 2026-10-16

## Context
The project runs on a Windows laptop without Docker. Kafka on native Windows is unreliable
and adds setup overhead that does not help learning AI concepts.

## Decision
Model the application lifecycle as a `status` column with explicit transitions, driven by
Spring application events (`@Async` listeners). Event names (`application.submitted`,
`docs.extracted`, `decision.ready`, …) match the future Kafka topics.

## Consequences
- ✅ Zero extra infrastructure locally.
- ✅ Swapping to Kafka later changes the transport, not the domain events.
- ⚠️ No durability or replay across restarts in the local version.
