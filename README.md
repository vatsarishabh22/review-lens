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
  <a href="https://plugins.jetbrains.com/plugin/35225-review-lens"><img src="https://img.shields.io/jetbrains/plugin/v/35225?label=JetBrains%20Marketplace" alt="JetBrains Marketplace version"></a>
  <a href="https://plugins.jetbrains.com/plugin/35225-review-lens"><img src="https://img.shields.io/jetbrains/plugin/d/35225" alt="JetBrains Marketplace downloads"></a>
  <!-- when VS Code ships, turn its badge green -->
</p>

<p align="center"><img src="docs/images/demo.gif" alt="Review Lens in 40 seconds: pick a PR, your work is parked, see who else uses the changed function, suggest a fix on two lines, resolve a thread, tick each file, approve, and you're back on your branch with your work restored"></p>

## Why

A diff page shows the lines that changed. To judge them, you need the code around them: the caller, the test, the
config. Review Lens checks the PR out **in your editor**, so you review full files with every tool you already use.
Then it puts you back exactly where you were.

![A review in progress: changed files with badges, the review bar, and GitHub threads under their lines](docs/images/review.png)

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
- **See who else uses what changed.** Each changed function shows the places outside the PR that use it, as code you can read and tick off. Python, Java and Kotlin.
- **See the whole conversation.** Everyone's GitHub comments sit under their lines, and the Comments tab lists them all. Reply, resolve, or edit your own comments in place.
- **Suggest the fix.** Comment on several lines, or propose replacement lines the author applies from GitHub.
- **Comment, then post once.** Comments, replies, summary and verdict go to GitHub as a single review, or mark it reviewed without posting.
- **See what's done.** Reviewed PRs turn green in the list with their verdict: ✓ Reviewed · approved.
- **Review only what's new.** Someone pushed after your review? The PR shows 🔄 New commits, and one click reviews only those.

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
