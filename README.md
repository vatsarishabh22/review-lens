# Review Lens

[![CI](https://github.com/vatsarishabh22/review-lens/actions/workflows/ci.yml/badge.svg)](https://github.com/vatsarishabh22/review-lens/actions/workflows/ci.yml)

<!-- TODO after the Marketplace release: replace with the plugin's Marketplace badge and link -->
**[Marketplace link coming soon]**

Review a GitHub pull request inside your JetBrains IDE, in the window you already have open, with the whole codebase
around it.

GitHub's diff page shows the lines that changed. To judge them you need the rest: the function that calls them, the
test next to them, the config they read. Review Lens checks the PR out **in place**. You read full files with the PR's
changes marked, jump to definitions and search as usual. When you're done, **Exit review** puts you back on your branch
with your unfinished work exactly as you left it.

![A review in progress: the changed files with their badges, the review bar, and a draft comment under a changed line](docs/images/review.png)

## What you get

| | |
|---|---|
| **Same window, work kept safe** | Your uncommitted changes are parked in a named stash; Exit review restores them. |
| **The PR on a local branch** | Checked out as `review/pr-<N>`; nothing is committed or pushed. |
| **Read-only while reviewing** | Typing in a PR file is refused, so you never edit the PR by accident. |
| **Changes in the gutter** | Changed lines are marked in the full file; **File / Diff** flips to a side-by-side diff. |
| **Viewed checklist** | ⭐ not viewed · ✅ viewed · 🔄 changed since viewed, synced with GitHub's checkboxes. |
| **Pick what to review** | All commits, some commits, or only those pushed since your last review. |
| **Inline comments** | Hover a changed line, click **+**. Drafts stay editable until you finish. |
| **Finish review** | Drafts, summary and verdict go to GitHub as **one review**. |
| **Blame on the caret line** | Which commit in the PR last touched the line you're on, and who wrote it. |
| **Works offline** | Without GitHub it lists branches instead and says so; viewed ticks wait and sync later. |

## Install

Works in every JetBrains IDE **2026.1 or newer**: IntelliJ IDEA, PyCharm, WebStorm, GoLand and the rest.

### Option 1 · Marketplace (recommended)

<!-- TODO after the Marketplace release: link the listing and confirm its name -->
**Settings → Plugins → Marketplace**, search **Review Lens**, click **Install**.

### Option 2 · One command

Quit the IDE, then run the line for your system.

<details open>
<summary><b>macOS</b></summary>

```bash
# IntelliJ IDEA
"/Applications/IntelliJ IDEA.app/Contents/MacOS/idea" installPlugins io.github.vatsarishabh22.reviewlens

# PyCharm
/Applications/PyCharm.app/Contents/MacOS/pycharm installPlugins io.github.vatsarishabh22.reviewlens
```

</details>

<details>
<summary><b>Windows</b> (PowerShell)</summary>

```powershell
# IntelliJ IDEA
& (Resolve-Path "C:\Program Files\JetBrains\IntelliJ IDEA*\bin\idea64.exe")[-1] installPlugins io.github.vatsarishabh22.reviewlens

# PyCharm
& (Resolve-Path "C:\Program Files\JetBrains\PyCharm*\bin\pycharm64.exe")[-1] installPlugins io.github.vatsarishabh22.reviewlens
```

</details>

<details>
<summary><b>Linux</b></summary>

```bash
# from the folder you unpacked the IDE into
./bin/idea.sh installPlugins io.github.vatsarishabh22.reviewlens      # IntelliJ IDEA
./bin/pycharm.sh installPlugins io.github.vatsarishabh22.reviewlens   # PyCharm
```

</details>

<details>
<summary><b>JetBrains Toolbox</b> (any system)</summary>

Turn on **Toolbox → Settings → Tools → Generate shell scripts**, then:

```bash
idea installPlugins io.github.vatsarishabh22.reviewlens      # IntelliJ IDEA
pycharm installPlugins io.github.vatsarishabh22.reviewlens   # PyCharm
```

On macOS the script returns at once and the install finishes in the background a second or two later.

</details>

Another IDE? Swap the launcher name:

| IDE | macOS | Windows | Linux / Toolbox |
|---|---|---|---|
| WebStorm | `WebStorm.app/Contents/MacOS/webstorm` | `webstorm64.exe` | `webstorm.sh` / `webstorm` |
| GoLand | `GoLand.app/Contents/MacOS/goland` | `goland64.exe` | `goland.sh` / `goland` |

### Option 3 · Download

<!-- TODO after the first release: check the asset name -->
Get the zip from the [latest release](https://github.com/vatsarishabh22/review-lens/releases/latest), then
**Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick it and restart.

### You'll also need

- **git** on your `PATH`.
- **[GitHub CLI](https://cli.github.com/) (`gh`)**, signed in with `gh auth login`. It's needed for pull requests,
  viewed sync and comments. Without it you can still review remote branches.

## How to use it

**1. Open the Review tool window** from the left toolbar, or click **Review PR…** in the main toolbar. PRs waiting on
your review come first. Click ↻ to refresh.

![The PR list: one PR waiting on you, one other open](docs/images/pr-list.png)

**2. Pick a PR.** You see its description (click **Show more** for all of it), size, CI checks and commits.
Under **What to review**, keep *All commits*, tick the commits you want, or pick *Since your last review*.

![PR detail: description, checks, Start review and the commit picker](docs/images/pr-detail.png)

**3. Start review.** If you have uncommitted work, Review Lens lists it and parks it before switching. The PR is
checked out and its first changed file opens.

![The park card: one uncommitted file will be parked and restored on exit](docs/images/park.png)

**4. Read and tick.** The review bar sits above every file:

| Control | Shortcut | What it does |
|---|---|---|
| Scope ▾ | | Switch to other commits, or check GitHub for new ones |
| Prev / Next unviewed | `⌥N` | Move through the changed files |
| File / Diff | `⌥D` | Full file with changes marked, or side-by-side diff |
| Viewed | `⌥R` | Tick the file; the shortcut also opens the next unviewed one |
| Finish review | | Summary and verdict, posted with your comments |
| Exit | | Back to your branch, your work restored |

**5. Comment.** Hover a changed line, click the **+** in the gutter, write your comment and click **Add comment**.
It stays a draft, which you can edit or delete, until you finish. Drafts survive Exit and restarts.

**6. Finish review.** Add a summary if you like, pick **Comment**, **Approve** or **Request changes**, and click
**Submit to GitHub**. Submit stays greyed out until there's something to post; hover it to see why. On your own PR
only *Comment* is possible, which is GitHub's rule. If GitHub refuses a comment (for example, its line no longer
exists), that comment stays a draft, so nothing you wrote is lost.

![Finish review: a summary, the verdict, and the pending comment that goes with it](docs/images/finish.png)

**7. Exit review** when you're done. Come back later and the PR shows **Resume review**, with your ticks and drafts
kept.

## Your data

Everything stays in your repository's `.git` folder, outside your working files:

| What | Where |
|---|---|
| The review in progress (your branch, the parked stash) | `.git/review-lens/active.json` |
| Each PR's scope, viewed ticks and drafts | `.git/review-lens/<pr>.json` |
| Your parked work | A git stash named after the review |

Nothing is sent anywhere except GitHub: viewed ticks when you tick, and your review when you submit it. The IDE's own
`.idea/` folder is never parked. Problems show up as notifications, never as blocking dialogs.
