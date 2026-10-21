import { test } from "node:test";
import * as assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import * as path from "node:path";
import { repository } from "./helpers";
import { StateStore } from "../infrastructure/store";
import { ReviewLifecycle } from "../services/lifecycle";
import { ReviewService } from "../services/review";
import { GhClient } from "../infrastructure/github";
const target = {
  key: "pr-7",
  label: "#7 Feature",
  prNumber: 7,
  head: "feature",
  base: "main",
};
test("park and exit restores staged edits, untracked work, and leaves editor settings alone", async (t) => {
  const r = await repository();
  t.after(r.clean);
  const store = new StateStore(r.client.gitDir),
    lifecycle = new ReviewLifecycle(r.client, store);
  await writeFile(path.join(r.root, "app.txt"), "staged\n");
  await r.git("add", "app.txt");
  await writeFile(path.join(r.root, "notes.txt"), "untracked\n");
  for (const dir of [".idea", ".vscode"]) {
    await mkdir(path.join(r.root, dir));
    await writeFile(path.join(r.root, dir, "settings.json"), "{}");
  }
  const steps: string[] = [];
  await lifecycle.start(target, r.head, (s) => steps.push(s));
  assert.equal(await r.client.currentRef(), "review/pr-7");
  assert.equal(
    await readFile(path.join(r.root, "app.txt"), "utf8"),
    "feature\n",
  );
  assert.equal(steps.length, 2);
  assert.equal(
    await readFile(path.join(r.root, ".vscode/settings.json"), "utf8"),
    "{}",
  );
  assert.equal(await lifecycle.exit(), "restored");
  assert.equal(await r.client.currentRef(), "main");
  assert.equal(
    await readFile(path.join(r.root, "app.txt"), "utf8"),
    "staged\n",
  );
  assert.equal(
    await readFile(path.join(r.root, "notes.txt"), "utf8"),
    "untracked\n",
  );
  assert.match(await r.git("status", "--porcelain"), /M  app.txt/);
  assert.equal(await store.active(), undefined);
  assert.equal((await r.git("stash", "list")).trim(), "");
});
test("a failed checkout rolls back and a clean review makes no stash", async (t) => {
  const r = await repository();
  t.after(r.clean);
  const store = new StateStore(r.client.gitDir),
    lifecycle = new ReviewLifecycle(r.client, store);
  await writeFile(path.join(r.root, "app.txt"), "mine\n");
  await assert.rejects(
    lifecycle.start(target, "0000000000000000000000000000000000000000"),
  );
  assert.equal(await r.client.currentRef(), "main");
  assert.equal(await store.active(), undefined);
  assert.equal(await readFile(path.join(r.root, "app.txt"), "utf8"), "mine\n");
  await r.git("checkout", "--", "app.txt");
  assert.equal((await lifecycle.start(target, r.head)).stashMessage, null);
  await assert.rejects(lifecycle.start(target, r.head), /already active/);
  assert.equal(await lifecycle.exit(), "nothing");
});
test("detached HEAD returns to the original commit and restart recovery loads active state", async (t) => {
  const r = await repository();
  t.after(r.clean);
  await r.git("checkout", "--detach", r.base);
  const store = new StateStore(r.client.gitDir);
  await new ReviewLifecycle(r.client, store).start(target, r.head);
  assert.equal((await store.active())?.originalRef, r.base);
  assert.equal(
    await new ReviewLifecycle(r.client, new StateStore(r.client.gitDir)).exit(),
    "nothing",
  );
  assert.equal(await r.client.currentRef(), r.base);
});
test("exit refuses new review edits and external branch changes without losing recovery state", async (t) => {
  const r = await repository();
  t.after(r.clean);
  const store = new StateStore(r.client.gitDir),
    lifecycle = new ReviewLifecycle(r.client, store);
  await lifecycle.start(target, r.head);
  await writeFile(path.join(r.root, "app.txt"), "external edit\n");
  await assert.rejects(lifecycle.exit(), /has edits/);
  assert.ok(await store.active());
  await r.git("checkout", "--", "app.txt");
  await r.git("checkout", "-qb", "outside");
  await assert.rejects(lifecycle.exit(), /checkout is now/);
  assert.ok(await store.active());
});
test("a conflicting restore keeps the named stash", async (t) => {
  const r = await repository();
  t.after(r.clean);
  const store = new StateStore(r.client.gitDir),
    lifecycle = new ReviewLifecycle(r.client, store);
  await writeFile(path.join(r.root, "app.txt"), "parked\n");
  await lifecycle.start(target, r.head);
  await r.git("checkout", "main");
  await writeFile(path.join(r.root, "app.txt"), "changed base\n");
  await r.git("commit", "-qam", "external update");
  assert.equal(await lifecycle.exit(), "conflict");
  assert.match(await r.git("stash", "list"), /review-lens: parked/);
});
class Offline extends GhClient {
  async viewed(): Promise<never> {
    throw new Error("offline");
  }
}
test("session re-scope preserves ticks and drafts, offline ticks persist, and fresh services resume", async (t) => {
  const r = await repository();
  t.after(r.clean);
  const s = new ReviewService(r.client, new Offline(r.root));
  await r.git("update-ref", "refs/review-lens/pr-7", r.head);
  const loaded = await s.load(target, false);
  await s.begin(loaded);
  await s.toggle("app.txt");
  assert.equal(s.meta?.unsent?.["app.txt"], true);
  assert.match(s.sync, /failed/);
  await s.addDraft("app.txt", 1, "check this");
  await s.begin(loaded, {
    range: { from: r.head + "^", to: r.head },
    label: "One commit",
  });
  assert.equal(s.meta?.drafts?.[0].body, "check this");
  assert.equal(s.meta?.unsent?.["app.txt"], true);
  const resumed = new ReviewService(r.client, new Offline(r.root));
  await resumed.initialize();
  assert.equal(resumed.meta?.scopeLabel, "One commit");
  assert.equal(resumed.viewedCount(), 1);
  await resumed.exit();
  assert.equal(await r.client.currentRef(), "main");
});

test("failed submissions retain drafts; accepted reviews post once; mark-only keeps pending drafts and the last verdict", async (t) => {
  const r = await repository();
  t.after(r.clean);
  await r.git("update-ref", "refs/review-lens/pr-7", r.head);
  const gh = new Offline(r.root);
  let attempts = 0,
    accept = false;
  gh.submit = async (_number, payload: any) => {
    attempts++;
    assert.equal(payload.comments.length, 1);
    if (!accept) throw new Error("Line could not be resolved");
  };
  const s = new ReviewService(r.client, gh),
    loaded = await s.load(target, false);
  await s.begin(loaded);
  await s.addDraft("app.txt", 1, "why?");
  await assert.rejects(
    s.finish("Please check this", "COMMENT"),
    /Line could not be resolved/,
  );
  assert.ok(s.active);
  assert.equal(s.meta?.drafts?.[0].posted, false);
  assert.equal(s.meta?.verdict, undefined);
  accept = true;
  await s.finish("Thanks", "COMMENT");
  assert.equal(attempts, 2);
  assert.equal((await s.store.meta(target.key))?.drafts?.[0].posted, true);
  assert.equal(await r.client.currentRef(), "main");
  await s.begin(loaded);
  await s.addDraft("app.txt", 1, "keep for later");
  await s.finish("");
  const saved = await s.store.meta(target.key);
  assert.equal(attempts, 2);
  assert.equal(saved?.verdict, "COMMENT");
  assert.equal(saved?.drafts?.[1].posted, false);
});

test("concurrent local toggles are serialized and retain the final offline intent", async (t) => {
  const r = await repository();
  t.after(r.clean);
  await r.git("update-ref", "refs/review-lens/pr-7", r.head);
  const s = new ReviewService(r.client, new Offline(r.root));
  await s.begin(await s.load(target, false));
  await Promise.all([
    s.toggle("app.txt"),
    s.toggle("app.txt"),
    s.toggle("app.txt"),
  ]);
  assert.equal(s.meta?.unsent?.["app.txt"], true);
  assert.equal(s.viewedCount(), 1);
  await s.exit();
});
