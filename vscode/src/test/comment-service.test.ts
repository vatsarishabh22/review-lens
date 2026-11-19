import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ReviewService } from "../services/review";
import { GitClient } from "../infrastructure/git";
import { GhClient } from "../infrastructure/github";
import { ReviewThread } from "../core/threads";
function setup() {
  const git = new GitClient("unused", "unused"),
    gh = new GhClient("unused");
  const service = new ReviewService(git, gh);
  const target = {
    key: "pr-7",
    label: "#7",
    prNumber: 7,
    head: "feature",
    base: "main",
  };
  service.active = {
    target,
    head: "b".repeat(40),
    reviewBranch: "review/pr-7",
    originalRef: "main",
  };
  service.meta = {
    target,
    scopeLabel: "All",
    range: { from: "a".repeat(40), to: "b".repeat(40) },
    reviewed: {},
  };
  service.changes = [
    {
      path: "calc.ts",
      status: "M",
      newBlob: "c".repeat(40),
      added: 2,
      deleted: 1,
    },
  ];
  service.store.saveMeta = async () => {};
  service.lifecycle.assertCheckout = async () => {};
  git.commentHunks = async () => [
    { start: 2, count: 4, oldStart: 2, oldCount: 3, kind: "modified" },
    { start: 20, count: 2, oldStart: 19, oldCount: 1, kind: "modified" },
  ];
  const thread: ReviewThread = {
    id: "T1",
    path: "calc.ts",
    line: 3,
    diffSide: "RIGHT",
    isResolved: false,
    viewerCanResolve: true,
    viewerCanUnresolve: true,
    comments: [
      {
        id: "C1",
        author: "me",
        body: "public",
        url: "https://github.com/example",
        mine: true,
        canEdit: true,
        canDelete: true,
      },
    ],
  };
  service.threads = [thread];
  service.threadsKnown = true;
  gh.reviewThreads = async () => [thread];
  return { service, gh, git, thread };
}
test("multiline comments stay in one diff block; rejected ranges never save a draft", async () => {
  const { service } = setup();
  await service.addDraft("calc.ts", 5, "```suggestion\nreplacement\n```", 2);
  assert.equal(service.meta?.drafts?.[0].startLine, 2);
  await assert.rejects(
    service.addDraft("calc.ts", 20, "crosses blocks", 3),
    /Comments must/,
  );
  await assert.rejects(
    service.addDraft("calc.ts", 3, "reversed", 4),
    /Invalid comment range/,
  );
  assert.equal(service.meta?.drafts?.length, 1);
});
test("resolved or disappeared replies stay pending and host failure never removes local text", async () => {
  const { service, gh, thread } = setup();
  await service.reply("T1", "keep this reply");
  assert.equal(service.outbox().replies.length, 1);
  thread.isResolved = true;
  assert.equal(service.outbox().replies.length, 0);
  await assert.rejects(service.reply("T1", "no"), /open thread/);
  service.threads = [];
  assert.equal(service.outbox().replies.length, 0);
  gh.reviewThreads = async () => {
    throw new Error("offline");
  };
  await service.refreshThreads();
  assert.equal(service.meta?.drafts?.[0].body, "keep this reply");
  assert.match(service.threadError!, /offline/);
});
test("permission and GitHub refusals do not mutate posted comments or local drafts", async () => {
  const { service, gh, thread } = setup();
  await service.reply("T1", "local reply");
  thread.comments[0].canEdit = false;
  await assert.rejects(service.postedComment("C1", "edited"), /does not allow/);
  thread.comments[0].canEdit = true;
  gh.editComment = async () => {
    throw new Error("permission changed");
  };
  await assert.rejects(
    service.postedComment("C1", "edited"),
    /permission changed/,
  );
  gh.deleteComment = async () => {
    throw new Error("cannot delete");
  };
  await assert.rejects(service.postedComment("C1"), /cannot delete/);
  assert.equal(thread.comments[0].body, "public");
  assert.equal(service.meta?.drafts?.[0].body, "local reply");
});
