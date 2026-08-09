---
description: "Review current code changes (git diff) against the tech spec and platform constraints"
argument-hint: "[optional: path to tech spec, or branch name]"
allowed-tools: Read, Grep, Glob, Bash, Agent
---

Review the current code changes against the platform's design constraints.

## Step 1 — Determine the diff
If a branch name was provided in $ARGUMENTS, use `git diff origin/main...<branch>`.
Otherwise, use `git diff` for unstaged changes or `git diff --cached` for staged changes.
If neither has content, use `git diff HEAD~1` for the last commit.

## Step 2 — Classify the diff
Run `git diff --stat` (or equivalent) to identify which modules were touched.
Classify as library-scope, simulator-scope, ui-scope, or mixed.

## Step 3 — Route to the right reviewer(s)
- If the diff touches `pv-domain`, `pv-persistence`, `pv-kafka`, `pv-redis`, `pv-guice`, or `pv-app`: delegate to @sv-code-reviewer-ctrm-eu-power.
- If the diff touches frontend code (tsx, ts, css, html under a UI directory): delegate to @ui-a11y-reviewer-ctrm-eu-power for accessibility, then @ui-code-quality-reviewer-ctrm-eu-power for code quality. Present findings from each separately.
- If mixed: run all applicable reviewers, present findings grouped by reviewer.

## Step 4 — Tech spec linkage
If a tech spec path was provided in $ARGUMENTS, pass it to the reviewer.
If not, ask the user which tech spec this change implements. If they say "none," that's the reviewer's first CRITICAL finding.
