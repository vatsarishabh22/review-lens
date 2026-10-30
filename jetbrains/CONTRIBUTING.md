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

- **Which IDE it builds against:** IntelliJ IDEA Ultimate `platformVersion` (Java and Kotlin bundled) plus the
  `PythonCore` plugin `pythonCoreVersion`, both downloaded once and cached, so the impact map compiles for all three
  languages. Each language is an optional dependency (`impact-python.xml`, `impact-java.xml`, `impact-kotlin.xml`):
  an IDE without one simply skips it. `supportsKotlinPluginMode` stays in the main `plugin.xml`, the only place the
  platform reads it.
- **The verifier's IDEs:** `verifyIdes` in `gradle.properties` (`code:version`, comma-separated). CI runs one job per
  entry. `-PverifyIdes=IU:2026.2.3` checks a single IDE.
- **Screenshots and UI driving:** `./gradlew runIdeForUiTests --args=<repo>` starts the sandbox with JetBrains' Robot
  Server on port 8082. It runs the IDE at `platformPath` when installed, else downloads `pycharmVersion`.
- **Open it in an IDE:** open the `jetbrains/` folder in IntelliJ IDEA as a Gradle project.

Try reviews on a throwaway clone, never on a working copy you care about.

## Code

`src/main/kotlin/io/github/vatsarishabh22/reviewlens/`

| Layer | Files | Owns |
|---|---|---|
| **Core** (mirrors `spec/`) | `ReviewLifecycle` | Park → check out `review/<key>` → exit and restore. `active.json` |
| | `ReviewMeta` | One PR's state, `<key>.json`: range, scope label, viewed blobs, unsent ticks, drafts, impact ticks; the viewed merge |
| | `ReviewScope` | Commit pick → `ScopeRange(from, to)` and its label; state keys |
| | `ReviewComments`, `Remarks` | Drafts, the outbox Finish posts, suggestion blocks; the Comments tab's rows and filters |
| | `GitClient`, `Cli`, `GitParsers` | Thin `git` wrapper and its output parsers |
| **Host** | `ReviewHost` | The code host contract (list, head ref, threads, slot, one-call Finish, checks, optional Viewed sync) and `ReviewHosts`, which picks `origin`'s host ([spec/gitlab.md](../spec/gitlab.md)) |
| | `github/GhClient`, `github/GitHubWire` | GitHub's host: the `gh` calls, its Finish rules (`GitHubSlot`), and its GraphQL, jq and parsers. Only `ReviewHost` imports this package |
| **Services** | `ReviewSession` | Fetch a target (a PR via the host's head ref, or a branch), start or re-scope a review |
| | `ReviewService` | Project service: the review in progress, its changed files, ticks and badges |
| | `HostViewed` | The host's Viewed checkboxes: folded in at load, each tick sent, failures queued |
| | `HostThreads` | The host's threads and slot, and the writes that can't wait for Finish: edit, delete, resolve |
| | `ReviewDrafts` | Draft comments and the summary, posted as one review |
| | `ReviewBlame` | Which in-scope commit last changed the caret's line, cached per scope range |
| | `ReviewTargets` | What the panel lists: open PRs from the host, or remote branches without one |
| **Impact map** ([spec/impact.md](../spec/impact.md)) | `ImpactLanguage`, `PythonImpact`, `JavaImpact`, `KotlinImpact` | One extension per language: what a function is, its header, its name |
| | `FunctionScanner`, `ChangedFunctions`, `ImpactSearch`, `CodeFiles` | Changed functions in the scope, the places that use them, which files are code |
| | `ImpactService` | Project service: the map for the review in progress, and its ticks |
| **UI** | `ReviewFlow` | Glue: background tasks, notifications, begin and exit, open file, diff |
| | `ReviewPanel`, `PrListView`, `PrDetailView`, `ChangedFilesView` | The Review tool window |
| | `ReviewBanner`, `FinishReviewPopup`, `CommentInlays`, `ImpactChip`, `ImpactInlays`, `ReviewGutter`, `PrDescription` | The review bar, Finish popup, inline comments, impact chips and peeks, gutter marks, PR description |
| | `TreeBadges`, `InlineBlame`, `ReviewStatusWidget`, `ReadOnlyGuard`, `ReviewActions`, `ReviewPrAction`, `ReviewStartup` | Other platform surfaces |
| | `ReviewUi` | `ReviewColors`, the **single colour source** (lazy `JBColor`s, so theme switches need no code), and shared widgets |

Principles the code follows:
- **One owner per concern.** A rule lives in one place, and everything else asks that owner.
- **An interface only where a second implementation is planned:** `ReviewHost` (GitLab, 0.2.0) and `ImpactLanguage`
  (one per language). Shared code never imports a host's package; `HostBoundaryTest` fails if it does.
- **Views only draw.** What a list shows and what a popup says are plain functions with tests (`remarks`, `finishWords`).
- **Stable platform APIs only.** The JetBrains review toolkit (`collaboration-tools`) is experimental, so it isn't
  used.

## Tests

Plain JUnit 4, plus the IntelliJ Platform test framework (`BasePlatformTestCase`, and the Java fixture via
`testFramework(Plugin.Java)`) where a test needs real Python, Java or Kotlin code. Git behaviour is tested against real
temporary repositories, not mocks; hosts against fakes of `ReviewHost`. `SpecFixturesTest` loads every file in
`../spec/fixtures` through the real readers, and `SpecCasesTest` runs every case in `../spec/cases` through the real
functions.

## Releasing

Tag `jetbrains-v<version>`. See [Releasing](../CONTRIBUTING.md#releasing). The first JetBrains Marketplace upload is
done by hand. After that, a tag publishes on its own once the secrets are set.
