<p align="center">
  <img src="docs/images/logo.png" width="380" alt="Review Lens: a detective robot inspecting a pull request through a magnifying lens">
</p>

<h1 align="center">Review Lens</h1>

<p align="center"><b>Review pull requests inside your editor, with the whole codebase around them.</b></p>

<p align="center">
  <a href="jetbrains/README.md"><img src="https://img.shields.io/badge/IntelliJ_IDEA-available-2ea44f?logo=intellijidea&logoColor=white" alt="IntelliJ IDEA: available"></a>
  <a href="jetbrains/README.md"><img src="https://img.shields.io/badge/PyCharm-available-2ea44f?logo=pycharm&logoColor=white" alt="PyCharm: available"></a>
  <a href="jetbrains/README.md"><img src="https://img.shields.io/badge/WebStorm-available-2ea44f?logo=webstorm&logoColor=white" alt="WebStorm: available"></a>
  <a href="jetbrains/README.md"><img src="https://img.shields.io/badge/GoLand-available-2ea44f?logo=goland&logoColor=white" alt="GoLand: available"></a>
  <a href="vscode/README.md"><img src="https://img.shields.io/badge/VS_Code-coming_soon-lightgrey" alt="VS Code: coming soon"></a>
  <br>
  <a href="https://github.com/vatsarishabh22/review-lens/actions/workflows/jetbrains.yml"><img src="https://img.shields.io/github/actions/workflow/status/vatsarishabh22/review-lens/jetbrains.yml?branch=main&label=build" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-blue" alt="License: Apache 2.0"></a>
  <!-- TODO after the Marketplace release: add the JetBrains Marketplace version badge; when VS Code ships, turn its badge green -->
</p>

## Why

A diff page shows the lines that changed. To judge them, you need the code around them: the caller, the test, the
config. Review Lens checks the PR out **in your editor**, so you review full files with every tool you already use.
Then it puts you back exactly where you were.

![A review in progress: changed files with badges, the review bar, and a draft comment](docs/images/review.png)

## How it works

```mermaid
flowchart LR
    A["Your branch<br/>with uncommitted work"] -->|Start review:<br/>work parked| B["The PR, read-only<br/>full files, changes marked"]
    B -->|Finish review:<br/>post or mark reviewed| C(["GitHub: one review,<br/>or nothing posted"])
    C -->|work restored| D["Your branch as you left it,<br/>the PR list ready for the next"]
    B -->|Exit review:<br/>work restored| D
```

- **Your work stays safe.** Uncommitted changes are parked and come back exactly on Exit.
- **Read the change in context.** Full files, changes marked in the gutter, a diff one click away.
- **Track progress.** ⭐ not viewed · ✅ viewed · 🔄 changed since viewed, synced with GitHub.
- **Comment, then post once.** Drafts, summary and verdict go to GitHub as a single review, or mark it reviewed without posting.
- **See what's done.** Reviewed PRs turn green in the list with their verdict: ✓ Reviewed · approved.
- **Review only what's new.** Pick all commits, some commits, or only those since your last review.

## Get started

| Editor | Guide |
|---|---|
| **IntelliJ IDEA, PyCharm, WebStorm, GoLand** and the other JetBrains IDEs (2026.1+) | **[Install and use →](jetbrains/README.md)** |
| **VS Code**, Cursor, Windsurf | [Coming soon](vscode/README.md) |

You'll need **git**, and the **[GitHub CLI](https://cli.github.com/)** signed in (`gh auth login`).

## Your data

Review Lens keeps its state in your repository's `.git/review-lens/` folder, and your parked work in a named git
stash. It talks only to GitHub, through `gh`.

## Contributing

One product, one folder per editor, one shared contract. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache 2.0](LICENSE)
