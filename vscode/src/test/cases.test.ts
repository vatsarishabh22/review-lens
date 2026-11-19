import { test } from "node:test";
import * as assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import * as path from "node:path";
import {
  badge,
  branchTarget,
  prKey,
  resolveScope,
  sinceLastReview,
  mergeViewed,
  syncViewedState,
  toPost,
  repliesToPost,
  suggestionParts,
} from "../core/model";
import { parseBlame, parseChanges } from "../core/parsers";
import {
  githubRefusal,
  parseViewedStates,
  summarizeChecks,
} from "../infrastructure/github";
const meta = (given: any): any => ({
  target: {},
  range: {},
  reviewed: given.reviewed ?? given.local ?? {},
  unsent: given.unsent,
});
const states = (given: any): any => ({ id: "", own: false, files: given.host });
const loaded = (given: any): any => ({
  head: given.head ?? given.commits[0],
  mergeBase: given.mergeBase,
  commits: given.commits.map((sha: string) => ({ sha })),
});
const adapters: Record<string, (given: any) => unknown> = {
  scope: (g) => {
    const s = resolveScope(
      loaded(g),
      g.picked.map((sha: string) => g.commits.indexOf(sha)),
    );
    return { ...s.range, label: s.label };
  },
  "since-last-review": (g) => {
    const s = sinceLastReview(loaded(g), g.seenHead);
    return s ? { ...s.range, label: s.label } : null;
  },
  badge: (g) =>
    ({ todo: "⭐", viewed: "✅", changed: "🔄" })[badge(meta(g), g.change)],
  "viewed-merge": (g) => mergeViewed(meta(g), states(g), g.changes),
  "viewed-sync": (g) => {
    const tried: string[] = [];
    const m = syncViewedState(meta(g), states(g), g.changes, (p) => {
      tried.push(p);
      return g.accepts.includes(p);
    });
    return { reviewed: m.reviewed, unsent: m.unsent, tried };
  },
  "finish-selection": (g) => ({
    comments: toPost(g.drafts, g.changes).map((d) => d.body),
    replies: repliesToPost(g.drafts, g.threads).map((d) => d.body),
  }),
  "state-key": (g) =>
    g.pr !== undefined ? prKey(g.pr) : branchTarget(g.branch, "").key,
  suggestion: (g) => suggestionParts(g.body),
  "git-changes": (g) =>
    parseChanges(g.raw, g.numstat).map((c) => ({
      ...c,
      oldPath: c.oldPath ?? null,
    })),
  "git-blame": (g) => {
    const result = parseBlame(g.porcelain),
      lines: Record<string, string> = {},
      commits: Record<string, unknown> = {};
    for (const [line, c] of result) {
      lines[line] = c.sha;
      commits[c.sha] = { author: c.author, time: c.time, summary: c.summary };
    }
    return { lines, commits };
  },
  "github/checks": (g) => summarizeChecks(JSON.stringify(g.json)),
  "github/refusal": (g) => githubRefusal(g.body) ?? null,
  "github/viewed-answer": (g) => {
    const s = parseViewedStates(g.text);
    return { pullRequestId: s.id, files: s.files };
  },
};
const directory = path.resolve(__dirname, "../../../spec/cases");
function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? files(path.join(dir, entry.name))
      : entry.name.endsWith(".json")
        ? [path.join(dir, entry.name)]
        : [],
  );
}
const seen = new Set<string>();
for (const file of files(directory)) {
  const rule = JSON.parse(readFileSync(file, "utf8"));
  assert.ok(adapters[rule.rule], `No adapter for ${file}`);
  seen.add(rule.rule);
  for (const example of rule.cases)
    test(`${rule.rule}: ${example.name}`, () => {
      let result: unknown;
      try {
        result = adapters[rule.rule](example.given);
      } catch {
        result = { error: true };
      }
      assert.deepEqual(result, example.expect);
    });
}
assert.deepEqual(
  [...seen].sort(),
  Object.keys(adapters).sort(),
  "Every adapter must have a shared case file",
);

test("packaged language catalog matches the shared impact contract", () => {
  assert.deepEqual(
    JSON.parse(
      readFileSync(
        path.resolve(__dirname, "../../media/code-files.json"),
        "utf8",
      ),
    ),
    JSON.parse(
      readFileSync(
        path.resolve(__dirname, "../../../spec/code-files.json"),
        "utf8",
      ),
    ),
  );
});
