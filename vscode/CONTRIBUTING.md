# Developing Review Lens for VS Code

**Status:** skeleton. The extension installs and registers **Review Lens: Review PR…**, which says "coming soon". The
shared state reader is done and tested. The review flow is the next thing to build.

## Setup

Requires Node 22+.

```bash
cd vscode
npm ci
npm test          # compile, then the spec/fixtures conformance tests
```

To run it, open the `vscode/` folder in VS Code and press **F5**. That starts an Extension Development Host with the
extension loaded. Try it on a throwaway clone, never on a working copy you care about.

## Code

| File | Owns |
|---|---|
| `src/extension.ts` | Activation and command registration (the VS Code surface) |
| `src/state.ts` | Reading `.git/review-lens/active.json` and `<key>.json`. It keeps only the fields the contract names. |
| `src/test/state.test.ts` | Loads every `spec/fixtures` file through `state.ts`, and fails if a field is lost |

## What to build next

Follow [`../spec/README.md`](../spec/README.md) step by step, and [`../prototype/review-lens.html`](../prototype/review-lens.html)
for every screen:

1. **List:** open PRs via `gh pr list`, the ones waiting on you first. Fall back to remote branches when `gh` isn't
   available.
2. **Start:** write `active.json` first, park the work, then check out `review/<key>`.
3. **Read:** changed files with badges, and changes marked against the scope's `from`. PR files are read-only.
4. **Viewed, comments, Finish review:** the same `<key>.json` fields and the same GitHub calls as the spec.
5. **Exit:** check out the original ref, pop the stash by its message, delete `active.json`.

The JetBrains plugin is the reference implementation. Its `ReviewLifecycle`, `GitClient`, `GhClient` and
`ReviewComments` map one to one onto these steps.

## Releasing

Tag `vscode-v<version>`. See [Releasing](../CONTRIBUTING.md#releasing).
