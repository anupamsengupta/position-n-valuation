---
description: "Analyze staged git changes and create a commit message with appropriate type and emoji"
allowed-tools: Bash(git status:*), Bash(git diff --staged), Bash(git commit:*), Bash(git push:*)
---

## Context:

- Current git status: !`git status`
- Current git diff: !`git diff --staged`

Analyze above staged git changes and create a commit message. Use present tense and explain "why" something has changed, not just "what" has changed.

## Commit types with emojis:
Only use the following emojis:

- ✨ `feat:` - New feature
- 🐛 `fix:` - Bug fix
- 🔨 `refactor:` - Refactoring code
- 📝 `docs:` - Documentation
- 🎨 `style:` - Styling/formatting
- ✅ `test:` - Tests
- ⚡ `perf:` - Performance

## Scope hints for this codebase:

When the diff touches specific modules, include the module as scope:

- `pv-domain` changes → `feat(domain):` or `refactor(domain):`
- `pv-persistence` changes → `feat(persistence):` or `fix(persistence):`
- `pv-kafka` changes → `feat(kafka):` or `fix(kafka):`
- `pv-redis` changes → `feat(redis):` or `fix(redis):`
- `pv-guice` changes → `refactor(guice):` or `feat(guice):`
- `pv-app` changes → `feat(simulator):` or `fix(simulator):`
- `pv-integration-tests` changes → `test(integration):`
- `docs/functional-spec/` changes → `docs(functional-spec):`
- `docs/technical-spec/` changes → `docs(tech-spec):`
- `docs/adr/` changes → `docs(adr):`
- `.claude/agents/` or `.claude/commands/` changes → `docs(agents):`
- UI/frontend changes → `feat(ui):` or `fix(ui):`
- Multiple modules → use the most significant one, or omit scope if truly cross-cutting

## Format:
```
<emoji> <type>(<optional_scope>): <concise_description>

<optional_body_explaining_why>

<optional_footer: e.g. "Refs: FR-048, D-13" or "Closes: #123">
```

## Output:

1. Show summary of changes currently staged
2. Classify the change scope (library-scope / simulator-scope / ui-scope / docs / mixed)
3. Propose commit message with appropriate emoji and scope
4. If the diff touches a design constraint (D-1..D-14) or functional rule (FR-nnn), include it in the footer
5. Ask for confirmation before committing

DO NOT auto-commit — wait for user approval, and only commit if the user says so.
