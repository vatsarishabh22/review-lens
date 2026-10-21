<p align="center">
  <img src="images/icon.png" width="96" alt="Review Lens logo">
</p>

# Review Lens for VS Code

Review a GitHub pull request in VS Code, in the window you already have open, with the whole codebase around it.
Your uncommitted work is parked, the PR is checked out read-only with its changes marked in full files, and **Exit
review** brings you back to your branch with your work restored.

## Features

| | |
|---|---|
| **Same window, work kept safe** | Uncommitted changes are parked in a named stash and restored on exit |
| **Viewed checklist** | ⭐ not viewed · ✅ viewed · 🔄 changed since viewed, synced with GitHub |
| **Inline comments and Finish review** | Drafts, summary and verdict posted to GitHub as one review |
| **Works with the JetBrains plugin** | Same review state: start a review in one editor, resume it in the other |
| **Commit scopes** | All commits, a consecutive selection, or commits since your last review |
| **Full-file context** | Native editors, scope gutter marks, caret blame, native diffs, and Explorer badges |
| **Recovery** | An active review survives a window reload; failed restores keep the named stash |

## Requirements

- **git** on your `PATH`.
- **[GitHub CLI](https://cli.github.com/) (`gh`)**, signed in with `gh auth login`.
- VS Code **1.95+**, a trusted local Git checkout, and an `origin` remote.

Without a working GitHub CLI connection, Review Lens lists remote branches and keeps progress locally.

## Install

Build a `.vsix` using the contributor guide, then run **Extensions: Install from VSIX…** in VS Code and select it.
This implementation has not been published to a marketplace. Compatible desktop VS Code forks can install the VSIX;
each fork's editor integration should be checked before relying on it for reviews.

## Use

1. Open your repository and select the **Review Lens** activity-bar icon, or run **Review Lens: Review PR…**.
   In a workspace with multiple folders, choose the repository first.
2. Choose a PR. Requests waiting on you appear first. Read its description, check results, and choose what to review.
   Checking commits fills gaps so each selection is one consecutive Git range.
3. Click **Start review**. Save any unsaved buffers, then review the park card and select **Park and start review**
   if your working tree has changes. The checkout moves to the scope's final commit on `review/<key>`.
4. Read full files. The sidebar and Explorer show **★ not viewed**, **✓ viewed**, and **↻ changed since viewed**.
   Switch between changed files and the whole project. Gutter marks compare against the scope's base, and the caret
   line shows blame only for commits in that scope.
5. Hover an added or modified line and use the comment gutter to write a **draft**. Use **Add draft comment** to save
   it locally. Drafts can be edited or deleted inline or from the sidebar, and survive Exit.
6. Use **Finish review** to send a summary, verdict and matching pending comments as one GitHub review. On your own
   PR only Comment is permitted. Empty Comment and Request changes reviews are held back with a reason.
   **Mark as reviewed** posts nothing and keeps drafts. Every finish restores your original branch and opens the list.
7. **Exit** also restores your branch and work, retaining progress and drafts for a later review.

| Action | Shortcut |
|---|---|
| Mark viewed and advance | `Alt+R` |
| Next unviewed file | `Alt+N` |
| File / Diff | `Alt+D` |

The **Scope** action changes scope or fetches new commits. **Review Lens: Select Repository** switches repositories
after the current review ends.

## Your work and data

Review Lens stores state in the checkout's own Git directory, under `review-lens/`, using the shared JSON contract.
Viewed state syncs through `gh`; offline changes are queued and retried on refresh or the next review.
Drafts are pinned to file blobs: a draft written on another version stays pending until that version is reviewed again.

Staged edits and untracked files are parked in a named Git stash. `.idea/` and `.vscode/` settings stay put.
Ignored files are not included in the stash. Do not run two editors' review lifecycles concurrently on the same checkout.

The extension uses VS Code's in-session read-only editor command, preserving real file URIs and language navigation.
On hosts without that command it opens immutable Git snapshot documents; language extensions may offer fewer features
for those virtual documents. Native read-only protection covers editing in VS Code, not external tools, terminals, or
a user explicitly overriding the editor's read-only state. Exit refuses to overwrite new review-checkout edits.

## Troubleshooting

- **GitHub unavailable:** run `gh auth status`. Remote branch review and local viewed tracking still work.
- **Parked work conflicts:** resolve the working-tree conflicts. The named stash is kept; inspect it before applying
  it again. Review Lens reports the outcome without silently dropping work.
- **Checkout changed outside Review Lens:** return to the recorded review branch or original branch, then Exit.
- **GitHub rejects a review:** drafts remain pending and the review stays open. Edit the rejected comment and retry.
- **State file cannot be read:** preserve `.git/review-lens/` and the named stash; fix the reported JSON error before
  resuming. Do not delete recovery information while work is parked.

See the [project README](https://github.com/vatsarishabh22/review-lens) for how Review Lens works. To help build this
extension, see [CONTRIBUTING.md](CONTRIBUTING.md).
