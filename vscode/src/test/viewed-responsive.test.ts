import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ReviewService } from "../services/review";
import { GitClient } from "../infrastructure/git";
import { GhClient } from "../infrastructure/github";

test("a stalled viewed send does not block local ticks or Exit and an old-session confirmation cannot change a new review", async () => {
  const git = new GitClient("unused", "unused"),
    gh = new GhClient("unused");
  const s = new ReviewService(git, gh);
  const target = {
    key: "pr-7",
    label: "#7",
    prNumber: 7,
    head: "feat",
    base: "main",
  };
  s.active = {
    target,
    head: "head",
    reviewBranch: "review/pr-7",
    originalRef: "main",
  };
  s.meta = {
    target,
    range: { from: "base", to: "head" },
    scopeLabel: "All",
    reviewed: {},
  };
  s.changes = [
    { path: "a", status: "M", newBlob: "blob", added: 1, deleted: 1 },
  ];
  s.store.saveMeta = async () => {};
  s.lifecycle.assertCheckout = async () => {};
  gh.viewed = async () => ({
    id: "PR_7",
    own: false,
    files: { a: "UNVIEWED" },
  });
  await s.syncViewed();
  let complete!: () => void,
    sent = 0;
  gh.setViewed = async () => {
    sent++;
    await new Promise<void>((resolve) => {
      complete = resolve;
    });
  };
  await s.toggle("a");
  assert.equal(sent, 1);
  assert.equal(s.busy, false);
  assert.equal(s.meta.unsent?.a, true);
  await s.toggle("a");
  assert.equal(s.meta.unsent?.a, false);
  const old = s.meta;
  s.active = undefined;
  complete();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(s.meta, old);
  assert.equal(s.meta.unsent?.a, false);
  assert.equal(sent, 1);
});
