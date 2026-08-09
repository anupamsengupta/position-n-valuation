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

## Stage 3 — Implementation
Delegate to @sv-implementation-engineer-ctrm-eu-power with the approved tech spec.
The engineer must classify scope (library / simulator / production-host) before coding.
**STOP.** Present the implementation summary. Wait for user approval before continuing.

## Stage 4 — Code Review
Delegate to @sv-code-reviewer-ctrm-eu-power to review the implementation against the tech spec.
Present the CRITICAL / WARNING / SUGGESTION report.
**STOP.** If any CRITICAL findings exist, route back to Stage 3 for fixes.

## Stage 5 — Summary
Summarize:
- Functional spec path
- Tech spec path
- Files changed
- Review findings resolved
- Any open items remaining
