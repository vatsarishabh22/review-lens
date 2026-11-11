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
  const posted = [
    {
      id: "T1",
      path: "calc.ts",
      line: 2,
      startLine: 1,
      diffSide: "RIGHT",
      isResolved: false,
      viewerCanResolve: true,
      viewerCanUnresolve: true,
      comments: [
        {
          id: "C1",
          author: "reviewer",
          body: "Please cover negative input",
          url: "https://github.com/example",
          commit: process.env.REVIEW_LENS_TEST_HEAD!,
          mine: true,
          canEdit: true,
          canDelete: true,
        },
      ],
    },
  ];
  service.gh.reviewThreads = async () => posted;
  service.gh.resolveThread = async (_id, resolved) => {
    posted[0].isResolved = resolved;
  };
  service.gh.editComment = async (_id, body) => {
    posted[0].comments[0].body = body;
  };
  service.gh.deleteComment = async () => {
    posted[0].comments = [];
  };
  service.gh.reviewSlot = async () => ({
    id: "PR_7",
    head: process.env.REVIEW_LENS_TEST_HEAD!,
    own: false,
  });
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
  await editor.open("calc.ts");
  await vscode.extensions
    .getExtension("vscode.typescript-language-features")
    ?.activate();
  const calcUri = vscode.Uri.file(service.git.file("calc.ts"));
  let references: vscode.Location[] = [];
  for (let attempt = 0; attempt < 60; attempt++) {
    references =
      (await vscode.commands.executeCommand<vscode.Location[]>(
        "vscode.executeReferenceProvider",
        calcUri,
        new vscode.Position(0, 17),
      )) ?? [];
    if (references.some((r) => r.uri.fsPath.endsWith("caller.ts"))) break;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  assert.ok(
    references.some((r) => r.uri.fsPath.endsWith("caller.ts")),
    "Real TypeScript provider resolves caller",
  );
  await session.impact.refresh();
  const calcDocument = await vscode.workspace.openTextDocument(calcUri);
  const lenses = session.impact.provideCodeLenses(calcDocument);
  assert.ok(
    lenses.some((l) => l.command?.title.includes("2 places use this")),
    JSON.stringify(lenses),
  );
  await session.impact.peek("calc.ts#calculate");
  await session.impact.toggle("calc.ts#calculate", "caller.ts#caller");
  assert.deepEqual(service.meta?.checked?.["calc.ts#calculate"].places, [
    "caller.ts#caller",
  ]);
  assert.ok((await service.store.meta("pr-7"))?.checked?.["calc.ts#calculate"]);
  await page.screenshot({
    path: path.join(
      process.env.REVIEW_LENS_TEST_ARTIFACTS!,
      "editor-impact.png",
    ),
  });
  const selected = vscode.window.activeTextEditor!;
  selected.selection = new vscode.Selection(
    0,
    0,
    1,
    selected.document.lineAt(1).text.length,
  );
  await editor.suggest();
  const suggestionComment = (editor as any).threads
    .flatMap((t: any) => t.comments)
    .find((c: any) => c.index === 1);
  assert.equal(suggestionComment.mode, vscode.CommentMode.Editing);
  assert.match(
    suggestionComment.body,
    /^```suggestion\n/,
    "Editing must retain the original suggestion syntax",
  );
  assert.equal(service.meta?.drafts?.[1].startLine, 1);
  assert.equal(service.meta?.drafts?.[1].line, 2);
  await editor.cancelEdit();
  await service.refreshThreads();
  await editor.refresh();
  let native = (editor as any).threads.find((t: any) => t.hostId === "T1");
  assert.ok(native, "GitHub thread appears in native comments");
  await editor.editPosted("C1");
  native = (editor as any).threads.find((t: any) => t.hostId === "T1");
  const comment = native.comments.find((c: any) => c.id === "C1");
  assert.equal(comment.mode, vscode.CommentMode.Editing);
  comment.body = "Updated public comment";
  await editor.savePosted(comment);
  assert.equal(posted[0].comments[0].body, "Updated public comment");
  await service.reply("T1", "I will add the test");
  await service.resolveThread("T1", true);
  assert.equal(
    service.outbox().replies.length,
    0,
    "Resolved replies stay local",
  );
  await service.resolveThread("T1", false);
  assert.equal(service.outbox().replies.length, 1);
  await sidebar.getByRole("button", { name: "Comments", exact: true }).click();
  await sidebar.getByText("Updated public comment", { exact: true }).waitFor();
  await page.screenshot({
    path: path.join(
      process.env.REVIEW_LENS_TEST_ARTIFACTS!,
      "editor-comments.png",
    ),
  });
  await sidebar
    .getByRole("button", { name: "Changed files", exact: true })
    .click();
  await service.toggle("app.txt");
  assert.equal(
    service.meta?.reviewed["app.txt"],
    service.changes.find((c) => c.path === "app.txt")?.newBlob,
  );
  for (let i = 0; i < 100 && service.meta?.unsent; i++)
    await new Promise((resolve) => setTimeout(resolve, 20));
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
  await editor.open("caller.ts");
  const closedTabs = vscode.window.tabGroups.all
    .flatMap((g) => g.tabs)
    .filter(
      (t) =>
        t.input instanceof vscode.TabInputText &&
        ["app.txt", "added.txt", "caller.ts"].includes(
          path.basename(t.input.uri.fsPath),
        ),
    );
  await vscode.window.tabGroups.close(closedTabs);
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
  assert.equal(payload.commit, loaded.head);
  assert.equal(payload.comments.length, 2);
  assert.equal(payload.replies.length, 1);
  assert.equal(payload.comments[1].startLine, 1);
  assert.equal(payload.verdict, "COMMENT");
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
  const remainingTabs = vscode.window.tabGroups.all
    .flatMap((g) => g.tabs)
    .filter((t) => t.input instanceof vscode.TabInputText);
  assert.equal(
    remainingTabs.length,
    1,
    "Exit must not reopen closed review files",
  );
  assert.ok(
    remainingTabs[0].input instanceof vscode.TabInputText &&
      remainingTabs[0].input.uri.fsPath.endsWith("calc.ts"),
  );
  assert.equal((await service.store.meta("pr-7"))?.drafts?.[0].posted, true);
  await editor.open("app.txt"); // no active review: safely a no-op
  const restored = await vscode.window.showTextDocument(
    vscode.Uri.file(path.join(root, "app.txt")),
  );
  await editor.refresh();
  await vscode.commands.executeCommand("type", { text: "editable again " });
  assert.ok(
    restored.document.getText().includes("editable again"),
    "Exit must release the session read-only guard",
  );
  await vscode.commands.executeCommand("workbench.action.files.revert");
  const added = await vscode.window.showTextDocument(
    vscode.Uri.file(path.join(root, "caller.ts")),
  );
  await editor.refresh();
  await vscode.commands.executeCommand("type", {
    text: "editable after reopening",
  });
  assert.ok(
    added.document.getText().includes("editable after reopening"),
    "Closed review files must be writable when reopened",
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
