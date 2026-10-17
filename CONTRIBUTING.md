# Contributing to Review Lens

Thanks for helping. Review Lens is one product with **one folder per editor**. Every editor does the same thing to the
repository, so the shared part is written down once, in [`spec/`](spec), and each editor implements it in its own
language.

## Find your way

| Folder | What it is | Start here |
|---|---|---|
| [`spec/`](spec) | **The contract**: the review flow, the exact git and `gh` steps, the `.git/review-lens/` state files, their JSON schema and fixtures | [spec/README.md](spec/README.md) |
| [`jetbrains/`](jetbrains) | IntelliJ Platform plugin (Kotlin) | [jetbrains/CONTRIBUTING.md](jetbrains/CONTRIBUTING.md) |
| [`vscode/`](vscode) | VS Code extension (TypeScript), being built | [vscode/CONTRIBUTING.md](vscode/CONTRIBUTING.md) |
| [`prototype/`](prototype) | The approved UX as a clickable HTML page. Every editor's UI follows it. | Open `prototype/review-lens.html` in a browser |
| [`docs/images/`](docs/images) | Logo and screenshots | |

Each editor folder has two guides: `README.md` for its **users** (install and use) and `CONTRIBUTING.md` for its
**developers** (build, run, test, how the code is organised).

## The one rule: the contract

A review started in one editor must resume, or exit cleanly, in any other. That holds because every editor:

1. runs the same git and `gh` steps ([spec/README.md](spec/README.md)),
2. reads and writes the same files in `.git/review-lens/` ([spec/state.schema.json](spec/state.schema.json)), and
3. proves it in its test suite: every file in `spec/fixtures/` goes through the editor's **real** state reader, and the
   test fails if a field is lost.

**Changing the state?** Change `spec/` first (the README, the schema and a fixture), then every editor, in the
**same PR**. Both test suites read the fixtures, so CI catches an editor that falls behind.

## Bringing Review Lens to a new editor

1. Create a folder named after the editor, with its own `README.md` (users) and `CONTRIBUTING.md` (developers).
2. Implement [spec/README.md](spec/README.md), using the prototype for the UI.
3. Add the fixtures test, modelled on `jetbrains/…/SpecFixturesTest.kt` or `vscode/src/test/state.test.ts`.
4. Copy the CI pattern below for `.github/workflows/<editor>.yml` and `release-<editor>.yml`, and add its checks
   to `main`'s required checks.
5. Add a row to the editors table in the [README](README.md).

## CI: the same pattern for every editor

| File | Runs on | Does |
|---|---|---|
| `.github/workflows/<editor>.yml` | Every PR, and pushes to `main` | A `<Editor> · changes` job checks whether `<editor>/`, `spec/` or the editor's workflows changed. Every other job is skipped when they didn't, so a docs-only PR doesn't wait for builds. |
| `.github/workflows/release-<editor>.yml` | A `<editor>-v*` tag | Builds at the tag's version, creates a GitHub Release whose notes cover only what changed since this editor's previous release, then publishes to the editor's marketplaces |

Check names start with the editor (`JetBrains · build and test`, `VS Code · build and test`). A skipped job counts as
passing, so every check can be required on `main` without blocking unrelated PRs.

## Releasing

Each editor releases on its own, from a tag named after its folder. The version comes from the tag.

| Editor | Tag | What the workflow does | Secrets that turn on publishing |
|---|---|---|---|
| JetBrains | `jetbrains-v0.2.0` | Tests and builds, then attaches the zip to a GitHub Release, then publishes to JetBrains Marketplace | `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` |
| VS Code | `vscode-v0.2.0` | Tests and packages, then attaches the `.vsix` to a GitHub Release, then publishes to the VS Code Marketplace and Open VSX | `VSCE_PAT`, `OVSX_PAT` |

Each publishing step is skipped until its secret exists, so a tag always produces at least the GitHub Release.

```bash
git tag vscode-v0.1.0 && git push origin vscode-v0.1.0
```

## Ground rules

- **Never test against a working copy you care about.** Starting a review stashes and checks out. Use throwaway
  clones, or a local bare "origin" with `refs/pull/N/head` refs.
- Errors are notifications, never blocking dialogs. Work a review parks never includes the editor's own settings
  folder.
- Every UI change matches the prototype, and is checked with a screenshot before it's merged.
- `main` is protected: changes land through PRs with green checks.
- Commits are one-line [Conventional Commits](https://www.conventionalcommits.org/) scoped by folder:
  `feat(vscode): …`, `fix(jetbrains): …`, `docs(spec): …`.
