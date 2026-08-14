---
description: "Drive a backend feature from functional spec through code review with STOP gates between stages"
argument-hint: "[feature description or user story]"
allowed-tools: Read, Write, Grep, Glob, Bash, Agent
---

Drive the following feature through our full backend pipeline: $ARGUMENTS

Execute these stages in order. Do NOT skip STOP points. Do NOT proceed without explicit user approval at each gate.

## Stage 1 — Functional Specification
Delegate to @fc-functional-expert-ctrm-eu-power to produce a functional spec.
Save to docs/functional-spec/claude-gen/<feature-slug>.functional.md.
**STOP.** Present the functional spec. Wait for user approval before continuing.

## Stage 2 — Technical Design
Delegate to @sv-solutions-architect-ctrm-eu-power with the approved functional spec.
Produce a tech spec at docs/technical-spec/claude-gen/<feature-slug>-v1.0.md and any required ADRs.
**STOP.** Present the tech spec. Wait for user approval before continuing.

## Stage 3 — Database Schema & Migrations
If the tech spec includes §7 (Data Model Impact) with new or modified tables:
Delegate to @db-data-architect-ctrm-eu-power with the approved tech spec.
The data architect will:
- Review §7 for completeness and produce feedback if needed
- Produce Flyway migration scripts (versioned + repeatable)
- Produce a deployment artifact at docs/deployment/migration-<version>.md
**STOP.** Present the migration scripts and deployment doc. Wait for user approval.
If §7 has no schema changes, skip this stage and note "No schema changes required."

## Stage 4 — Implementation
Delegate to @sv-implementation-engineer-ctrm-eu-power with the approved tech spec.
If Stage 3 produced Flyway migrations, the engineer must map JPA entities to the new schema
and verify with `hbm2ddl.auto=validate`.
The engineer must classify scope (library / simulator / production-host) before coding.
**STOP.** Present the implementation summary. Wait for user approval before continuing.

## Stage 5 — Code Review
Delegate to @sv-code-reviewer-ctrm-eu-power to review the implementation against the tech spec.
Present the CRITICAL / WARNING / SUGGESTION report.
**STOP.** If any CRITICAL findings exist, route back to Stage 4 for fixes.

## Stage 6 — Summary
Summarize:
- Functional spec path
- Tech spec path
- Migration scripts created (if any)
- Deployment artifact path (if any)
- Files changed
- Review findings resolved
- Any open items remaining
