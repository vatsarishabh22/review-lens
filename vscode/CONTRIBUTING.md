# Developing Review Lens for VS Code

The extension implements the shared review lifecycle in TypeScript, with a webview sidebar and native editor,
diff, comment, decoration, CodeLens and status-bar integrations. All implementation and tooling live in this folder.

## Setup

Requires Node 22+.

```bash
cd vscode
npm ci --cache .npm-cache
npm test                       # compile, pure rules, real-Git lifecycle, shared fixtures, GitHub payload tests
npm run test:ui                # headless Edge, screenshots and sidebar interaction tests
npm run test:integration       # downloads VS Code 1.95.3, runs an isolated Extension Development Host
npm run package -- --out review-lens-0.0.1.vsix
```

To run it, open the `vscode/` folder in VS Code and press **F5**. That starts an Extension Development Host with the
extension loaded. The launch/task configuration is included. Try reviews on a throwaway clone, never on a working
copy you care about. Starting a review changes branches and parks work.

`test:ui` uses installed Edge by default; set `PLAYWRIGHT_CHANNEL=chrome` for Chrome. Screenshots are in `.artifacts/ui/`.
`test:integration` accepts `VSCODE_EXECUTABLE` to use an installed IDE, or `VSCODE_TEST_VERSION` to test another version.
It creates a temporary Git repo with a local origin and pull-request ref. GitHub is simulated: it never posts a real
review. It checks real typing protection, native diffs, draft submission, staged/untracked restoration and editing after
exit, and captures `.artifacts/editor-review.png` and `editor-diff.png`.

Tests, browser temporary directories, editor profiles and downloads stay in `.test-data/`, `.vscode-test/`,
`.npm-cache/` and `.artifacts/`. These are ignored and excluded from the VSIX.

## Code

| File | Owns |
|---|---|
| `src/extension.ts` | Activation, per-repository service wiring, commands, status bar and cleanup |
| `src/core/model.ts` | Shared types, scopes, badges, viewed merge and review payload rules |
| `src/core/parsers.ts` | NUL-separated Git diff, hunk and blame parsers |
| `src/infrastructure/cli.ts` | Asynchronous argument-based child processes; no shell interpolation |
| `src/infrastructure/git.ts` | Git discovery, fetch, stash, checkout, file content and diff operations |
| `src/infrastructure/github.ts` | PR listing/checks, paginated viewed state, mutations and one review POST |
| `src/infrastructure/store.ts` | Validated keys and atomic JSON replacement in the checkout's Git directory |
| `src/state.ts` | Validated contract readers, preserving absent/null fields and rejecting malformed recovery state |
| `src/services/lifecycle.ts` | Recovery record, park, checkout, rollback, re-scope and restore |
| `src/services/review.ts` | Serialized session operations, progress, viewed retry queue, drafts and finish |
| `src/ui/editor.ts` | Read-only sessions, immutable snapshots, native diff/comments, gutter, blame and badges |
| `src/ui/sidebar.ts` | Host-side sidebar state and validated message handling |
| `src/ui/html.ts`, `media/` | CSP-protected sidebar shell, theme-aware styles and renderer |
| `src/test/`, `test/ui/`, `scripts/` | Unit, real-Git, GitHub boundary, browser and actual editor tests |

## Compatibility and safety

[`../spec/README.md`](../spec/README.md) and `spec/fixtures/` are read-only inputs to this implementation. The fixtures
must load through the actual state readers without losing fields, and each fixture is checked against the shared schema.
Keep the schema unchanged unless a separately scoped task explicitly authorizes changes outside `vscode/`.

The lifecycle writes scope metadata and then `active.json` before parking or checkout. It refuses overlapping reviews,
unexpected branch changes, and new working-tree edits at re-scope/exit. A failed start attempts rollback; failed rollback
retains recovery information. Stash conflicts retain the stash. Local Git transactions are not killed by a generic timeout.

One queue serializes repository/state mutations and GitHub confirmations. UI code owns no Git command construction.
Changes are saved locally before network sync. Read responses fetched before replaying queued ticks cannot erase them.
State shapes and review payload behavior match JetBrains; VS Code additionally excludes its own `.vscode/` settings
from parking, alongside `.idea/`. Nothing is stored in project settings or user settings.

The sidebar follows `prototype/review-lens.html`, adapted to VS Code surfaces. Native comments replace IntelliJ inlays,
and CodeLens/editor actions replace its custom review banner. Test with both light/dark themes and a narrow sidebar.
The webview has a restrictive CSP, no Node access, no external scripts/fonts/images, escaped PR text and checked host
messages. Only the extension host executes CLIs. No experimental VS Code API is enabled.

The JetBrains plugin is the reference implementation. Its `ReviewLifecycle`, `GitClient`, `GhClient` and
`ReviewComments` map one to one onto these steps.

## Releasing

Tag `vscode-v<version>`. See [Releasing](../CONTRIBUTING.md#releasing).
