# Docs Index

This folder is deep-dive reference material for the Wallet Ledger design. The root `README.md` (added in Phase 1.5 of [03-implementation-plan.md](03-implementation-plan.md)) is the entry point for actually running and evaluating the project — it covers the essentials and links into these files for detail. Come here when you want the full reasoning behind a specific choice, not as a substitute for the root README.

## Files, in suggested reading order

1. **[04-design-decisions.md](04-design-decisions.md)** — read this first if you want the "why." The reasoning behind the schema/API/architecture choices, written as decision / alternative considered / trade-off, in the spirit of an ADR (architecture decision record). Not meant to be read start-to-finish so much as consulted per topic (double-entry vs. single-entry, holds + system accounts, locking, idempotency, stored vs. derived balance, ...).
2. **[02-tech-stack.md](02-tech-stack.md)** — the concrete tools (Spring Boot, JPA, Flyway, MapStruct, ...) and the package-by-domain folder layout, with a short rationale for each pick.
3. **[01-api-list.md](01-api-list.md)** — the actual HTTP surface: every endpoint, its auth requirement, and request/response shape. Reference material — look things up here rather than read top to bottom.
4. **[03-implementation-plan.md](03-implementation-plan.md)** — the delivery roadmap: 7 phases, in the order they're meant to be built, each with its migration, entities, endpoints, and tests. Most useful for understanding *when* something lands and *why in that order*; it forward-references `04-design-decisions.md` extensively for the reasoning behind each phase's choices.
5. **[05-test-plan.md](05-test-plan.md)** — the detailed test-case checklist per phase (Given/When/Then, unit vs. integration, traced back to the requirement legend). Read right alongside `03-implementation-plan.md` — it's the granular expansion of that document's short "Tests" bullets, not a standalone narrative.

## Why this order, not the filename order

The `01`–`04` filenames reflect the order these documents were first written, not necessarily the best order to read them in — hence this index. `04-design-decisions.md` is listed first here because it's the most evaluative content (why the design is safe and correct), even though it's numbered last. `03-implementation-plan.md` and `05-test-plan.md` are listed last because they're the most sequencing-oriented documents, and both assume the reasoning in `04` as background — most of their sections link directly into a specific section there.

The numeric filenames stay as-is (not renumbered to match this order) since `03`/`04`/`05` already cross-reference each other by filename in several places; renumbering would just move which direction has more forward-references, not remove them. `05` was numbered after `04` simply because it was written later, continuing the "filename = write order" convention this index exists to work around.
