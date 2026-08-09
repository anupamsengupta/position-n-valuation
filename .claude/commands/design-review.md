---
description: "Review an existing tech spec or ADR for design completeness and constraint compliance"
argument-hint: "[path to spec file, e.g. docs/technical-spec/volume-import-v1.0.md]"
allowed-tools: Read, Grep, Glob, Bash, Agent
---

Review the following specification for design completeness: $ARGUMENTS

## Step 1 — Read the spec
Read the file at the path provided. If the path doesn't exist, search docs/technical-spec/, docs/technical-spec/claude-gen/, docs/adr/, docs/ui-spec/, and docs/ui-spec/claude-gen for a matching filename.

## Step 2 — Delegate to the right reviewer
- If this is a backend tech spec or ADR: delegate to @sv-solutions-architect-ctrm-eu-power in review mode. It will produce a CRITICAL / SHOULD-FIX / NICE-TO-HAVE gap report with D-1..D-14 compliance, pattern catalog citations, and subsystem cross-references.
- If this is a UI spec: delegate to @ui-solution-architect in review mode.
- If this is a functional spec: delegate to @fc-functional-expert-ctrm-eu-power in review mode. It will score against the 8-point checklist.

## Step 3 — Present findings
Present the gap report and compliance summary. Do not auto-fix anything.
