import { test } from "node:test";
import * as assert from "node:assert/strict";
import { GhClient } from "../infrastructure/github";
import { anchored, ReviewSubmission, slotBlocker } from "../core/threads";
const submission: ReviewSubmission = {
  commit: "head",
  verdict: "COMMENT",
  summary: "Review",
  comments: [
    {
      path: "a.ts",
      startLine: 2,
      line: 4,
      blob: "blob",
      body: "```suggestion\nchanged\n```",
    },
  ],
  replies: [
    { path: "old.ts", line: 1, blob: "old", body: "reply", replyTo: "T1" },
  ],
};
function host(adopted = false, fail = "", cleanupFail = false) {
  const calls: { query: string; variables: any }[] = [];
  const gh = new GhClient("repo", async (_root, _program, args, input) => {
    const request = input
      ? JSON.parse(input)
      : {
          query: args.find((a) => a.startsWith("query="))?.slice(6),
          variables: {},
        };
    calls.push(request);
    const q: string = request.query;
    if (q.includes("reviews(states:PENDING"))
      return JSON.stringify({
        data: {
          viewer: { login: "me" },
          repository: {
            pullRequest: {
              id: "PR1",
              headRefOid: "head",
              viewerDidAuthor: false,
              reviews: {
                nodes: adopted
                  ? [
                      {
                        id: "existing",
                        viewerDidAuthor: true,
                        comments: { totalCount: 3 },
                      },
                    ]
                  : [],
              },
            },
          },
        },
      });
    if (
      (fail && q.includes(fail)) ||
      (cleanupFail && q.includes("deletePullRequest"))
    )
      return JSON.stringify({ errors: [{ message: "rejected" }] });
    const data = q.includes("addPullRequestReviewThreadReply")
      ? { addPullRequestReviewThreadReply: { comment: { id: "reply" } } }
      : q.includes("addPullRequestReviewThread(")
        ? {
            addPullRequestReviewThread: {
              thread: { comments: { nodes: [{ id: "added" }] } },
            },
          }
        : q.includes("addPullRequestReview(")
          ? { addPullRequestReview: { pullRequestReview: { id: "created" } } }
          : q.includes("submitPullRequestReview")
            ? { submitPullRequestReview: { pullRequestReview: { id: "done" } } }
            : { deleted: { id: "ok" } };
    return JSON.stringify({ data });
  });
  return { gh, calls };
}
test("GraphQL creates a commit-pinned review with ranges, suggestions and replies then submits once", async () => {
  const { gh, calls } = host();
  await gh.submit(7, submission);
  const start = calls.find((c) => c.query.includes("addPullRequestReview("))!;
  assert.equal(start.variables.commit, "head");
  assert.deepEqual(start.variables.threads[0], {
    path: "a.ts",
    line: 4,
    side: "RIGHT",
    startLine: 2,
    startSide: "RIGHT",
    body: submission.comments[0].body,
  });
  assert.equal(
    calls.filter((c) => c.query.includes("submitPullRequestReview")).length,
    1,
  );
  assert.equal(
    calls.find((c) => c.query.includes("addPullRequestReviewThreadReply"))
      ?.variables.thread,
    "T1",
  );
});
test("adopted review keeps existing comments and rolls back only this invocation additions", async () => {
  const { gh, calls } = host(true, "submitPullRequestReview");
  await assert.rejects(gh.submit(7, submission), /rejected/);
  assert.equal(
    calls.some((c) => c.query.includes("addPullRequestReview(")),
    false,
  );
  assert.equal(
    calls.some((c) => c.query.includes("deletePullRequestReview(input")),
    false,
  );
  assert.deepEqual(
    calls
      .filter((c) => c.query.includes("deletePullRequestReviewComment"))
      .map((c) => c.variables.id),
    ["reply", "added"],
  );
});
test("a newly-created review is deleted on failure and rollback failures are reported", async () => {
  const { gh, calls } = host(false, "addPullRequestReviewThreadReply");
  await assert.rejects(gh.submit(7, submission), /rejected/);
  assert.equal(
    calls.filter((c) => c.query.includes("deletePullRequestReview(input"))
      .length,
    1,
  );
  const bad = host(false, "submitPullRequestReview", true);
  await assert.rejects(bad.gh.submit(7, submission), /Cleanup failed/);
});
test("pending older-scope comments are held, replies alone can be adopted, and existing pending comments count for empty review validation", () => {
  const slot = {
    id: "PR1",
    head: "new",
    own: false,
    pending: { id: "pending", comments: 1 },
  };
  assert.match(slotBlocker(slot, submission)!, /latest push/);
  assert.equal(
    slotBlocker(slot, { ...submission, comments: [], summary: "" }),
    undefined,
  );
  assert.equal(
    slotBlocker(
      { ...slot, own: true },
      { ...submission, comments: [], verdict: "APPROVE" },
    ),
    "GitHub only permits a Comment review on your own pull request.",
  );
});
test("threads attach only when the host anchors them to the scope commit on the right side", () => {
  const thread: any = {
    line: 4,
    diffSide: "RIGHT",
    comments: [{ commit: "head" }],
  };
  assert.equal(anchored(thread, "head"), true);
  assert.equal(anchored({ ...thread, line: null }, "head"), false);
  assert.equal(anchored({ ...thread, diffSide: "LEFT" }, "head"), false);
  assert.equal(anchored(thread, "old"), false);
});
test("threads and all comment pages are read without truncating long discussions", async () => {
  let count = 0;
  const gh = new GhClient("repo", async (_root, _program, args, input) => {
    count++;
    const comment = (id: string) => ({
      id,
      body: id,
      url: "https://github.com/x",
      author: { login: "me" },
      commit: { oid: "head" },
      viewerCanUpdate: true,
      viewerCanDelete: true,
    });
    if (input)
      return JSON.stringify({
        data: {
          node: {
            comments: {
              nodes: [comment("C2")],
              pageInfo: { hasNextPage: false },
            },
          },
        },
      });
    const second = args.includes("cursor=next");
    return JSON.stringify({
      data: {
        viewer: { login: "me" },
        repository: {
          pullRequest: {
            reviewThreads: {
              nodes: second
                ? []
                : [
                    {
                      id: "T1",
                      path: "a",
                      line: 1,
                      diffSide: "RIGHT",
                      isResolved: false,
                      viewerCanResolve: true,
                      comments: {
                        nodes: [comment("C1")],
                        pageInfo: { hasNextPage: true, endCursor: "more" },
                      },
                    },
                  ],
              pageInfo: { hasNextPage: !second, endCursor: "next" },
            },
          },
        },
      },
    });
  });
  const threads = await gh.reviewThreads(7);
  assert.equal(count, 3);
  assert.equal(threads[0].comments.length, 2);
  assert.equal(threads[0].comments[1].mine, true);
});
