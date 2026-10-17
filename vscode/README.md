<p align="center">
  <img src="../docs/images/logo.png" width="280" alt="Review Lens logo">
</p>

# Review Lens for VS Code

> **Coming soon.** This extension is being built. It's available today for
> [JetBrains IDEs](https://github.com/vatsarishabh22/review-lens/blob/main/jetbrains/README.md).

Review a GitHub pull request in VS Code, in the window you already have open, with the whole codebase around it.
Your uncommitted work is parked, the PR is checked out read-only with its changes marked in full files, and **Exit
review** brings you back to your branch with your work restored.

It will also work in VS Code forks such as Cursor and Windsurf, through Open VSX.

## Planned

| | |
|---|---|
| **Same window, work kept safe** | Uncommitted changes are parked in a named stash and restored on exit |
| **Viewed checklist** | ⭐ not viewed · ✅ viewed · 🔄 changed since viewed, synced with GitHub |
| **Inline comments and Finish review** | Drafts, summary and verdict posted to GitHub as one review |
| **Works with the JetBrains plugin** | Same review state: start a review in one editor, resume it in the other |

## Requirements

- **git** on your `PATH`.
- **[GitHub CLI](https://cli.github.com/) (`gh`)**, signed in with `gh auth login`.

See the [project README](https://github.com/vatsarishabh22/review-lens) for how Review Lens works. To help build this
extension, see [CONTRIBUTING.md](https://github.com/vatsarishabh22/review-lens/blob/main/vscode/CONTRIBUTING.md).
