---
description: Run a feature from functional spec through code review
argument-hint: [feature-description]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent
---

Drive the following feature through our full pipeline: $ARGUMENTS

1. Delegate to @fc-functional-expert-ctrm-eu-power to produce docs/specs/<slug>.functional.md
2. STOP. Ask me to review the functional spec before continuing.
3. Delegate to @ssv-solutions-architect-ctrm with the approved functional spec. Produce
   docs/specs/<slug>.tech.md and any ADRs.
4. STOP. Ask me to review the design.
5. Delegate to @sv-implementation-engineer-ctrm with the approved tech spec.
6. Delegate to @sv-code-reviewer to review against the tech spec.
7. Summarize the review findings and stop.

Do not skip STOP points. Do not proceed to the next stage without my explicit go-ahead.