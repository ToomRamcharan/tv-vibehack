---
description: Prepare, plan and implement a new feature following project rules
argument-hint: <short description>
---

Feature request: $ARGUMENTS

Work through these steps in order. Stop after step 5 and wait for approval before writing code.

1. **Load context.** Read `CLAUDE.md` and every file in `.claude/rules/`, even if you believe they are
   already loaded. Pick the most relevant skill from `.claude/skills/` (usually
   `engineering-frontend-developer`) and invoke it.
2. **Check the branch.** Run `git branch --show-current`. If it is `development`, `main` or `master`,
   stop and ask the user to create a feature branch — do not create it yourself.
3. **Clarify the requirement.** Restate the feature in 2–3 sentences. List open questions and ask them
   before continuing if anything is ambiguous.
4. **Map the codebase.** Start with `graphify query "<feature>"`. Then read the closest existing feature
   end to end (page → service → repository → store) and reuse its patterns. Check:
   - DB changes → migration + `ormMigrations` registration; never hand-edit `base-data*.json`
   - i18n keys → both `src/assets/i18n/en.json` and `de.json`
   - Which variants (gss, saxony, …) and platforms (Android/iOS/web) are affected
5. **Plan.** Present: goal, approach, files to create/modify, tests to add, risks. Wait for approval.
6. **Implement** following the README guidelines, with unit tests for new logic.
7. **Verify.** Run `npm run lint` and `npm test`; fix failures. Run `graphify update .`.
   Re-read the full diff for scope creep, dead code and leftover debug output.
8. **Report** what changed, why, and what's left. Propose a commit message in the project format;
   commit only if the user asks.
