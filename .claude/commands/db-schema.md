---
description: "Design database schema, produce Flyway migrations, and generate deployment artifacts from a tech spec"
argument-hint: "[path to tech spec, or 'review' for existing schema audit, or 'optimize <query or table>']"
allowed-tools: Read, Write, Grep, Glob, Bash, Agent
---

Route the following database task to the data architect: $ARGUMENTS

## Mode detection

Determine the mode from $ARGUMENTS:

### Mode A — Schema design from tech spec (default)
If $ARGUMENTS is a path to a tech spec or a feature name:

1. Delegate to @db-data-architect-ctrm-eu-power to:
   - Read the tech spec (especially §7 Data Model Impact)
   - Produce schema feedback if §7 has gaps
   - **STOP.** Present the feedback. Wait for user approval.
2. Produce DDL: tables, indexes, triggers, RLS policies, pg_partman config
   - Save Flyway versioned migrations to `src/main/resources/db/migration/`
   - Save repeatable migrations (functions, triggers, RLS) to same directory
   - **STOP.** Present the migration scripts. Wait for user approval.
3. Produce deployment artifact at `docs/deployment/migration-<version>.md`
   - Pre-flight checks, execution steps, post-flight verification, rollback procedure
   - **STOP.** Present the deployment doc.
4. Summary: list all files created, tables affected, partition setup, index count.

### Mode B — Schema review
If $ARGUMENTS contains "review":

1. Delegate to @db-data-architect-ctrm-eu-power to:
   - Scan existing Flyway scripts in `src/main/resources/db/migration/`
   - Scan JPA entities in `pv-persistence/` for mapping consistency
   - Check every table for: tenant_id presence, RLS policy, bitemporal triggers (where applicable), index coverage, naming convention compliance
2. Produce a CRITICAL / WARNING / SUGGESTION report.

### Mode C — Query optimization
If $ARGUMENTS contains "optimize":

1. Delegate to @db-data-architect-ctrm-eu-power to:
   - Locate the query (from the named table, repository method, or pasted SQL)
   - Analyze the execution plan (or predict it from schema + indexes)
   - Propose index changes, query rewrites, or partition alignment
2. Produce the fix as a Flyway migration script if DDL is needed.
