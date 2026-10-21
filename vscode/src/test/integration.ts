import * as vscode from "vscode";
import * as assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import * as path from "node:path";
import { ExtensionSession } from "../extension";
export async function run() {
  const extension = vscode.extensions.getExtension(
    "vatsarishabh22.review-lens",
  );
  assert.ok(extension);
  const api = (await extension.activate()) as {
    getSession(): ExtensionSession | undefined;
  };
  const session = api.getSession();
  assert.ok(session);
  const { service, editor } = session;
  // Keep browser-only DOM types out of the extension host's TypeScript compilation.
  const browser = await require("@playwright/test").chromium.connectOverCDP(
    `http://127.0.0.1:${process.env.REVIEW_LENS_CAPTURE_PORT}`,
  );
  const page =
    browser
      .contexts()[0]
      .pages()
      .find((p: { url(): string }) => p.url().includes("workbench")) ??
    browser.contexts()[0].pages()[0];
  const target = {
    key: "pr-7",
    label: "#7 Retry invoice exports",
    prNumber: 7,
    head: "feature",
    base: "origin/main",
  };
  service.gh.ready = async () => true;
  service.gh.viewer = async () => "reviewer";
  service.gh.checks = async () => "Checks passed";
  service.gh.list = async () => [
    {
      number: 7,
      title: "Retry invoice exports",
      headRefName: "feature",
      baseRefName: "main",
      author: { login: "asha", name: "Asha Rao" },
      body: "Retry interrupted exports.\n\n## What changes\n- Handle connection resets",
      additions: 2,
      deletions: 1,
      changedFiles: 2,
      updatedAt: new Date().toISOString(),
      reviewRequests: [{ login: "reviewer" }],
    },
  ];
  service.gh.viewed = async () => ({ id: "PR_7", own: false, files: {} });
  service.gh.setViewed = async () => {};
  let payload: any;
  service.gh.submit = async (_number, body) => {
    payload = body;
  };
  await vscode.commands.executeCommand("reviewLens.reviewPr");
  await session.sidebar.refresh();
  let sidebar: any;
  for (let i = 0; i < 100 && !sidebar; i++) {
    for (const frame of page.frames())
      if (await frame.locator("#app").count()) sidebar = frame;
    if (!sidebar) await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.ok(sidebar, "The real webview sidebar must render");
  await sidebar.getByRole("button", { name: /Retry invoice exports/ }).click();
  await sidebar
    .getByRole("button", { name: "Start review", exact: true })
    .click();
  await sidebar
    .getByRole("button", { name: "Park and start review", exact: true })
    .click();
  await sidebar
    .getByRole("button", { name: "Next unviewed", exact: true })
    .waitFor();
  // The start button runs the real fetch, park and checkout flow through webview message handling.
  const loaded = await service.load(target, false);
  assert.equal(await service.git.currentRef(), "review/pr-7");
  await editor.open("app.txt");
  await editor.refresh();
  const textEditor = vscode.window.activeTextEditor;
  assert.ok(textEditor);
  const original = textEditor.document.getText();
  assert.equal(original.replace(/\r/g, ""), "feature\n");
  // Exercise the actual typing command: the native editor must refuse the write.
  await vscode.commands.executeCommand("type", { text: "SHOULD_NOT_APPEAR" });
  assert.equal(
    textEditor.document.getText(),
    original,
    "Review editor must remain read-only",
  );
  await service.addDraft("app.txt", 1, "Could we cover a connection reset?");
  await service.toggle("app.txt");
  assert.equal(
    service.meta?.reviewed["app.txt"],
    service.changes.find((c) => c.path === "app.txt")?.newBlob,
  );
  assert.equal(service.meta?.unsent, null);
  await editor.refresh();
  await page.screenshot({
    path: path.join(
      process.env.REVIEW_LENS_TEST_ARTIFACTS!,
      "editor-review.png",
    ),
  });
  await editor.open("app.txt", true);
  assert.ok(
    vscode.window.tabGroups.all.some((g) =>
      g.tabs.some((t) => t.input instanceof vscode.TabInputTextDiff),
    ),
    "Native diff tab should open",
  );
  await page.screenshot({
    path: path.join(process.env.REVIEW_LENS_TEST_ARTIFACTS!, "editor-diff.png"),
  });
  const before = editor.uri(loaded.mergeBase, "app.txt");
  assert.equal(
    (await vscode.workspace.openTextDocument(before))
      .getText()
      .replace(/\r/g, ""),
    "base\n",
  );
  await session.sidebar.reveal(true);
  await sidebar
    .getByRole("textbox", { name: "Review summary" })
    .fill("Looks good after the retry test");
  await sidebar
    .getByRole("button", { name: "Submit to GitHub", exact: true })
    .click();
  await sidebar
    .getByRole("searchbox", { name: "Search pull requests" })
    .waitFor();
  assert.equal(payload.commit_id, loaded.head);
  assert.equal(payload.comments.length, 1);
  assert.equal(payload.event, "COMMENT");
  assert.equal(await service.git.currentRef(), "main");
  const root = process.env.REVIEW_LENS_TEST_ROOT!;
  assert.equal(
    (await readFile(path.join(root, "app.txt"), "utf8")).replace(/\r/g, ""),
    "my staged work\n",
  );
  assert.equal(
    (await readFile(path.join(root, "notes.txt"), "utf8")).replace(/\r/g, ""),
    "my untracked notes\n",
  );
  assert.match(await service.git.git("status", "--porcelain"), /M  app.txt/);
  assert.equal(await service.store.active(), undefined);
  assert.equal((await service.store.meta("pr-7"))?.drafts?.[0].posted, true);
  await editor.open("app.txt"); // no active review: safely a no-op
  const restored = await vscode.window.showTextDocument(
    vscode.Uri.file(path.join(root, "app.txt")),
  );
  await vscode.commands.executeCommand("type", { text: "editable again " });
  assert.ok(
    restored.document.getText().includes("editable again"),
    "Exit must release the session read-only guard",
  );
  await vscode.commands.executeCommand("workbench.action.files.revert");
  await writeFile(
    path.join(
      process.env.REVIEW_LENS_TEST_ARTIFACTS!,
      "integration-result.json",
    ),
    JSON.stringify(
      {
        passed: true,
        vscode: vscode.version,
        readOnly: true,
        diff: true,
        drafts: true,
        restoredStaging: true,
      },
      null,
      2,
    ),
  );
  console.log(
    "Review Lens integration passed: checkout, read-only typing, viewed sync, comments, native diff, one review payload, staged/untracked restoration, writable after exit.",
  );
}
