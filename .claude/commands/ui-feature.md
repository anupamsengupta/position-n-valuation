---
description: "Drive a UI feature from spec through implementation and accessibility review"
argument-hint: "[screen or component description]"
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, Agent
---

Drive the following UI feature through the frontend pipeline: $ARGUMENTS

Execute these stages in order. Do NOT skip STOP points.

## Stage 1 — UI Tech Spec
Delegate to @ui-solution-architect to produce a UI tech spec (Phase 1 of its workflow).
Save to docs/ui-spec/<feature-slug>-v1.0.md.
**STOP.** Present the UI spec. Wait for user approval before continuing.

## Stage 2 — Backend Contract Check
Before implementation, verify that every backend endpoint referenced in the UI spec exists in a backend tech spec under docs/technical-spec/.
If any endpoint is missing, **STOP** and tell the user to run @sv-solutions-architect-ctrm-eu-power to design the API first.

## Stage 3 — Implementation
Delegate to @ui-solution-architect (Phase 2 of its workflow) to implement the approved spec.
Run `pnpm test` and `pnpm typecheck` before claiming done.
**STOP.** Present the implementation summary.

## Stage 4 — Accessibility Review
Delegate to @ui-a11y-reviewer-ctrm-eu-power to review the implementation diff.
Present the findings by severity.
**STOP.** If any Critical or Serious findings exist, route back to Stage 3 for fixes.

## Stage 5 — Code Quality Review
Delegate to @ui-code-quality-reviewer-ctrm-eu-power to review the implementation diff.
Present the findings by severity.
**STOP.** If any Critical or High findings exist, route back to Stage 3 for fixes.

## Stage 6 — Summary
Summarize:
- UI spec path
- Components created
- Tests added (unit, Storybook, E2E)
- Accessibility findings resolved
- Code quality findings resolved
- Any open items
