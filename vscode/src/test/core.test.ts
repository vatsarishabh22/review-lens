import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
  badge,
  mergeViewed,
  resolveScope,
  sinceLastReview,
  submitBlocker,
  toPost,
  reviewPayload,
  branchTarget,
} from "../core/model";
import { parseBlame, parseChanges, parseHunks } from "../core/parsers";
import { parseViewedStates, summarizeChecks } from "../infrastructure/github";
const loaded = {
  target: branchTarget("origin/feature", "origin/main"),
  head: "c3",
  mergeBase: "base",
  commits: ["c3", "c2", "c1"].map((sha) => ({
    sha,
    subject: sha,
    author: "A",
    time: 1,
  })),
};
const change = {
  path: "a.txt",
  status: "M",
  newBlob: "new",
  added: 1,
  deleted: 1,
};
test("valid Git filenames cannot collide with JavaScript object properties", () => {
  const paths = ["__proto__", "constructor", "toString"];
  const meta = {
    target: loaded.target,
    range: { from: "f", to: "t" },
    scopeLabel: "All",
    reviewed: {},
  };
  const changes = paths.map((path) => ({ ...change, path }));
  const states = parseViewedStates(
    "#id\tPR_1\n" + paths.map((path) => `${path}\tVIEWED`).join("\n"),
  );
  assert.equal(badge(meta, changes[0]), "todo");
  assert.equal(badge(meta, changes[1]), "todo");
  const reviewed = mergeViewed(meta, states, changes);
  assert.equal(Object.keys(reviewed).length, 3);
  for (const path of paths) {
    assert.equal(reviewed[path], "new");
    assert.equal(badge({ ...meta, reviewed }, { ...change, path }), "viewed");
  }
});
test("scope is contiguous, all and since-last-review resolve to their exact checkout", () => {
  assert.deepEqual(resolveScope(loaded, []).range, { from: "base", to: "c3" });
  assert.deepEqual(resolveScope(loaded, [1]).range, { from: "c2^", to: "c2" });
  assert.throws(() => resolveScope(loaded, [0, 2]), /consecutive/);
  assert.throws(() => resolveScope(loaded, [-1]));
  assert.deepEqual(sinceLastReview(loaded, "c1")?.range, {
    from: "c1",
    to: "c3",
  });
  assert.equal(sinceLastReview(loaded, "rewritten"), undefined);
  assert.equal(branchTarget("origin/a/b c", "main").key, "branch-a_b_c");
});
test("NUL parsers preserve whitespace, renamed paths and binary counts", () => {
  const raw =
    ":100644 100644 old new R100\0 old name\0new\tname\0:000000 100644 000 binary A\0image.png\0";
  const stat = "0\t0\t\0 old name\0new\tname\0-\t-\timage.png\0";
  const changes = parseChanges(raw, stat);
  assert.equal(changes.find((c) => c.oldPath)?.path, "new\tname");
  assert.equal(changes.find((c) => c.oldPath)?.oldPath, " old name");
  assert.equal(changes.find((c) => c.path === "image.png")?.added, null);
  assert.deepEqual(
    parseHunks("@@ -1,0 +2,3 @@\n@@ -6,2 +8,0 @@").map((h) => h.kind),
    ["added", "deleted"],
  );
});
test("viewed merge preserves queued local answers and dismissed blob history", () => {
  const meta = {
    target: loaded.target,
    range: { from: "f", to: "t" },
    scopeLabel: "All",
    reviewed: { "a.txt": "old" },
    unsent: { "a.txt": true },
  };
  const states = {
    id: "PR_1",
    own: false,
    files: { "a.txt": "UNVIEWED" as const },
  };
  assert.deepEqual(mergeViewed(meta, states, [change]), meta.reviewed);
  assert.deepEqual(mergeViewed(meta, states, [change], {}), {});
  assert.deepEqual(
    mergeViewed(
      { ...meta, unsent: null },
      { ...states, files: { "a.txt": "DISMISSED" } },
      [change],
    ),
    meta.reviewed,
  );
  assert.equal(
    parseViewedStates(
      "#id\tPR_1\n#own\ttrue\na.txt\tVIEWED\n#id\tPR_1\nb.txt\tDISMISSED",
    ).own,
    true,
  );
});
test("finish posts only matching pending blobs and enforces GitHub verdict rules", () => {
  const draft = { path: "a.txt", line: 1, blob: "new", body: "why?" };
  assert.deepEqual(
    toPost(
      [draft, { ...draft, posted: true }, { ...draft, blob: "old" }],
      [change],
    ),
    [draft],
  );
  assert.ok(submitBlocker("APPROVE", "LGTM", 0, true));
  assert.ok(submitBlocker("COMMENT", "", 0, false));
  assert.equal(submitBlocker("APPROVE", "", 0, false), undefined);
  assert.deepEqual(reviewPayload("head", "", "COMMENT", [draft]), {
    commit_id: "head",
    event: "COMMENT",
    comments: [{ path: "a.txt", line: 1, side: "RIGHT", body: "why?" }],
  });
});
test("blame and check results retain commit context and failure precedence", () => {
  const sha = "a".repeat(40);
  assert.equal(
    parseBlame(
      `${sha} 1 2 1\nauthor Alice\nauthor-time 12\nsummary Change\n\tcode`,
    ).get(2)?.author,
    "Alice",
  );
  assert.equal(
    summarizeChecks(
      JSON.stringify({
        statusCheckRollup: [{ status: "IN_PROGRESS" }, { state: "ERROR" }],
      }),
    ),
    "1 check failing",
  );
});
