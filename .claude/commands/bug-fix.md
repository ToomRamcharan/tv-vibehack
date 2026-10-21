---
description: Diagnose and fix a bug with a minimal, tested change following project rules
argument-hint: <bug description / steps to reproduce>
---

Bug report: $ARGUMENTS

Work through these steps in order. Stop after step 5 and wait for approval before changing code.

1. **Load context.** Read `CLAUDE.md` and every file in `.claude/rules/`, even if you believe they are
   already loaded.
2. **Check the branch.** Run `git branch --show-current`. If it is `development`, `main` or `master`,
   stop and ask the user to create a branch — do not create it yourself.
3. **Pin down the bug.** Restate expected vs actual behavior, and which variants (gss, saxony, …) and
   platforms (Android/iOS/web) are affected. If reproduction steps, logs or the Rollbar error are
   missing, ask for them before guessing.
4. **Find the root cause.** Start with `graphify query "<affected area>"`, then trace the code path
   (page → service → repository → store). Check `git log -p` on the suspect files for recent changes
   that may have introduced it. Distinguish the root cause from the symptom — do not stop at the first
   place the error surfaces.
5. **Report the diagnosis.** Present: root cause (with `file:line`), why it happens, the proposed
   minimal fix, other places that share the same flaw, and how you'll prove it's fixed. Wait for
   approval.
6. **Write a failing test first** that reproduces the bug, and confirm it fails for the right reason.
   If a unit test isn't feasible (native plugin, device-only), say so and describe the manual check.
7. **Fix minimally.** Change only what the root cause requires — no refactors or unrelated cleanups;
   flag those separately. Confirm the new test now passes.
8. **Verify.** Run `npm run lint` and `npm test`; fix failures. Run `graphify update .`.
   Re-read the full diff for scope creep, dead code and leftover debug output.
9. **Report** root cause, fix, test added, and any remaining risk or manual verification needed on
   device/variant. Propose a commit message in the project format (🐛 gitmoji); commit only if the
   user asks.
