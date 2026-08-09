---
description: "Design a technical specification from a functional spec or feature description"
argument-hint: "[path to functional spec, or feature description]"
allowed-tools: Read, Write, Grep, Glob, Bash, Agent
---

Delegate to @sv-solutions-architect-ctrm-eu-power to produce a technical specification for: $ARGUMENTS

If $ARGUMENTS is a file path, pass it as the functional spec input.
If $ARGUMENTS is a description without a functional spec, the architect will ask for one or tell the user to run /functional-spec first.

Save the output to docs/technical-spec/claude-gen/<feature-slug>-v1.0.md when ready.
