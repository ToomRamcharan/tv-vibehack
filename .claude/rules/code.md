## Code — Agent Rules of Conduct

This file governs how agents write, modify, and ship code on this project. It applies to `engineering-frontend-developer` and any other coding-capable skill (check the best skill for the task in `.claude/skills/`).

### 1. Core Protocols

- **Branch Safety**: Never commit or push directly to `main`/`master`. Work only on a feature branch named `feature/<description>` (or `bugfix/<description>`). Do not create the branch yourself — if one doesn't exist, stop and warn the user to create it before proceeding.
- **Scope Discipline**: Only touch files relevant to the current task. If a fix outside scope is needed, flag it instead of silently expanding the diff.
- **Ask Before Destructive Actions**: Deleting files, dropping data, force-pushing, or overwriting/replacing a file's contents wholesale always requires explicit user confirmation first.
- **Communicate Plans First**: For any multi-step task, produce a short plan (goal, approach, files touched) before executing.

### 2. Implementation Standards

- **Design/Spec Fidelity**: Implementation must match the approved spec or design; any deviation requires a note explaining why.
- **Component/Module Reuse**: Prefer extending existing components or utilities over creating near-duplicates.
- **Match Existing Style**: Follow the naming, formatting, and error-handling conventions already used in the surrounding code, even over personal preference.
- **No Dead Code**: No commented-out code, unused imports/variables, or leftover debug prints before finishing a task.
- **No Hardcoded Secrets**: API keys, tokens, and credentials always come from environment variables or a secrets manager, never literals.
- **Comments Explain "Why," Not "What"**: Only comment on non-obvious decisions or tradeoffs.

### 3. Testing & Quality Assurance

- **Tests Required**: New logic ships with at least basic unit/interaction tests. No untested state logic.
- **Lint & Test Gate**: No task is marked complete until lint, type-check, and test suites pass.
- **Self-Review**: Before handing off, re-read your own diff for scope creep, leftover debug code, and TODOs.
- **Docs Stay in Sync**: If behavior changes, update the relevant README/spec in the same task.

### 4. Communication Rules

- Summarize what changed, why, and what's left — no walls of raw logs.
- Surface uncertainty explicitly rather than guessing silently on ambiguous requirements.
- When blocked, state the blocker and propose 1–2 concrete options rather than stalling.

### 5. Commits

- **Never commit on your own.** Leave all changes uncommitted, even when a task or spec asks for
  "small commits": the user tests first. Only commit when the user explicitly asks, and then
  split the work into the planned commits.
- Commit messages follow the convention `{gitmoji} {Verb} {message}` (no ticket IDs)
  - `{Verb}` is a capitalized action word: `Add`, `Update` or `Fix`
  - e.g.: `🎨 Update code readability`, `🐛 Fix Netflix opening a missing HDMI input`
- **No AI attribution**: never add `Co-Authored-By` (or any Claude/AI attribution) lines to commit
  messages or PR descriptions.

### 6. Priority Order

1. Safety (don't break main, don't lose data)
2. Correctness (meets the actual requirement)
3. Consistency with existing code
4. Code quality & maintainability
5. Speed
