# Review Lens

A PyCharm / IntelliJ plugin for **context-first PR review in the same window**: pick a PR, your uncommitted work is
parked (named stash), the PR is checked out on a local `review/<key>` branch, you read full files with the PR's changes
marked in the gutter, and **Exit review** puts you back on your branch with your work restored.

The approved UX is the clickable prototype in `prototype/review-lens.html` (the source of truth; a private published copy
mirrors it). Every UI change matches it. Badges: ⭐ not viewed yet · ✅ viewed · 🔄 changed since you viewed it.

Edit this repo in **IntelliJ IDEA** (Kotlin support). The plugin itself targets PyCharm: it is built against, and run in,
the locally installed PyCharm.

## Build and run

The machine has no system JDK; PyCharm's bundled JBR is used. Java networking needs IPv4 here.

```bash
export JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
./gradlew test buildPlugin                 # unit + real-git tests, plugin zip
./gradlew runIde --args=<repo path>        # sandbox IDE with the plugin
./gradlew runIdeForUiTests --args=<repo>   # same, plus JetBrains Robot Server on :8082 for screenshots/driving
```

- Builds against the locally installed PyCharm (`platformPath` in `gradle.properties`), not a downloaded IDE.
- Compiles on JBR 25, emits Java 21 bytecode (2026.1 minimum).
- `SimpleLocalLineStatusTracker` lives in the product module `intellij.platform.vcs.impl`: declared via
  `bundledModule(...)` in Gradle and `<depends>com.intellij.modules.vcs</depends>` in `plugin.xml`.

## Architecture (`src/main/kotlin/io/github/vatsarishabh22/reviewlens/`)

| File | Owns |
|---|---|
| `ReviewLifecycle` | Park work → checkout `review/<key>` at scope end → exit restores. State in `.git/review-lens/active.json` |
| `ReviewSession` | Fetch a target (PR via `pull/N/head`, or branch), start / re-scope a review |
| `ReviewMeta` | Per-PR state (`.git/review-lens/<key>.json`): range, scope label, reviewed blobs |
| `ReviewService` | Project service: active review, changed files, badges, reviewed state, caret blame |
| `ReviewGutter` | Gutter marks vs the scope base (`SimpleLocalLineStatusTracker`), only while reviewing |
| `ReviewFlow` | UI glue: background tasks, notifications, begin/exit, scope popup, open file, diff |
| `GitClient` / `GhClient` / `Cli` | Thin CLI wrappers (`git`, `gh`) |
| `GitParsers` | `diff --raw/--numstat -z` and `blame --porcelain` parsers |
| `ReviewScope` | Commit pick → `ScopeRange(from, to)` and its label |
| `ReviewPanel`, `ReviewBanner`, `TreeBadges`, `InlineBlame`, `ReviewActions`, `ReviewPrAction`, `ReviewStartup`, `ReadOnlyGuard` | UI surfaces (steps 3–4 rebuild the panel and banner to the prototype) |

## Rules

- **Never ship UI unseen.** Every UI step is screenshot-verified in the Robot Server sandbox against the matching
  prototype screen before the user sees it. Popups close when the sandbox window is unfocused, so drive flows through the
  plugin's own classes rather than popups.
- **Never test against a real working copy.** Starting a review stashes and checks out. Use throwaway clones or a
  local bare "origin" with `refs/pull/N/head` refs.
- **Stable platform APIs only.** The JetBrains review toolkit (`collaboration-tools`) is experimental; use it only where
  nothing stable exists.
- Work parked by a review excludes `.idea/` (the open IDE keeps writing it). Errors surface as balloons, never dialogs.
- Kotlin, SOLID OOP, no speculative abstractions. Tests: plain JUnit 4; git behaviour is tested against real temp repos.
- Commits: one-line Conventional Commits with scope (`feat(review-lens): …`), the why in the subject, no co-author trailer.
  The maintainer runs `git commit` / `git push` himself — hand him the commands.
