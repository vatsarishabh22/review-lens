# Developing Review Lens for JetBrains IDEs

The plugin is Kotlin on the IntelliJ Platform. It's built with the IntelliJ Platform Gradle Plugin and runs in every
JetBrains IDE 2026.1 or newer.

## Setup

Requires JDK 21 or newer. A JetBrains IDE's bundled runtime works too.

```bash
cd jetbrains
./gradlew test buildPlugin     # unit tests and real-git tests, then the plugin zip in build/distributions/
./gradlew runIde               # a sandbox IDE with the plugin installed
./gradlew verifyPlugin         # JetBrains' Plugin Verifier against the IDEs listed in verifyIdes
```

- **Which IDE it builds against:** `platformPath` in `gradle.properties` points at a locally installed IDE. When that
  path doesn't exist (as in CI), `platformVersion` is downloaded instead.
- **The verifier's IDEs:** `verifyIdes` in `gradle.properties` (`code:version`, comma-separated). CI runs one job per
  entry. `-PverifyIdes=IU:2026.2.3` checks a single IDE.
- **Screenshots and UI driving:** `./gradlew runIdeForUiTests --args=<repo>` starts the sandbox with JetBrains' Robot
  Server on port 8082.
- **Open it in an IDE:** open the `jetbrains/` folder in IntelliJ IDEA as a Gradle project.

Try reviews on a throwaway clone, never on a working copy you care about.

## Code

`src/main/kotlin/io/github/vatsarishabh22/reviewlens/`

| Layer | Files | Owns |
|---|---|---|
| **Core** (mirrors `spec/`) | `ReviewLifecycle` | Park → check out `review/<key>` → exit and restore. `active.json` |
| | `ReviewMeta` | One PR's state, `<key>.json`: range, scope label, viewed blobs, unsent ticks, drafts |
| | `ReviewScope` | Commit pick → `ScopeRange(from, to)` and its label |
| | `ReviewComments` | Draft, Verdict, what Finish review posts, and GitHub's one-call review payload |
| | `GitClient`, `GhClient`, `Cli`, `GitParsers` | Thin `git` / `gh` wrappers and their output parsers |
| **Services** | `ReviewSession` | Fetch a target (a PR via `pull/N/head`, or a branch), start or re-scope a review |
| | `ReviewService` | Project service: the active review, changed files, badges, viewed state (synced with GitHub) |
| | `ReviewBlame` | Project service: which in-scope commit last changed the caret's line, cached per scope range |
| | `ReviewDrafts` | Project service: draft comments and the summary, posted as one review |
| | `ReviewTargets` | What the panel lists: open PRs via `gh`, or remote branches without it |
| **UI** | `ReviewFlow` | Glue: background tasks, notifications, begin and exit, open file, diff |
| | `ReviewPanel`, `PrListView`, `PrDetailView`, `ChangedFilesView` | The Review tool window |
| | `ReviewBanner`, `FinishReviewPopup`, `CommentInlays`, `ReviewGutter`, `PrDescription` | The review bar, Finish popup, inline comments, gutter marks, PR description |
| | `TreeBadges`, `InlineBlame`, `ReviewStatusWidget`, `ReadOnlyGuard`, `ReviewActions`, `ReviewPrAction`, `ReviewStartup` | Other platform surfaces |
| | `ReviewUi` | `ReviewColors`, the **single colour source** (lazy `JBColor`s, so theme switches need no code), and shared widgets |

Principles the code follows:
- **One owner per concern.** A rule lives in one place, and everything else asks that owner.
- **No interface with only one implementation.**
- **Stable platform APIs only.** The JetBrains review toolkit (`collaboration-tools`) is experimental, so it isn't
  used.

## Tests

Plain JUnit 4. Git behaviour is tested against real temporary repositories, not mocks. `SpecFixturesTest` loads every
file in `../spec/fixtures` through the real readers.

## Releasing

Tag `jetbrains-v<version>`. See [Releasing](../CONTRIBUTING.md#releasing). The first JetBrains Marketplace upload is
done by hand. After that, a tag publishes on its own once the secrets are set.
