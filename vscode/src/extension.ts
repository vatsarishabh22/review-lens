import * as vscode from "vscode";
import { GitClient } from "./infrastructure/git";
import { ReviewService } from "./services/review";
import { ReviewEditor } from "./ui/editor";
import { ReviewSidebar } from "./ui/sidebar";
import { badge, resolveScope, sinceLastReview } from "./core/model";

export interface ExtensionSession {
  service: ReviewService;
  editor: ReviewEditor;
  sidebar: ReviewSidebar;
}
let session: ExtensionSession | undefined;
let provider: vscode.Disposable | undefined;
let status: vscode.StatusBarItem;
const disposables: vscode.Disposable[] = [];

export async function activate(context: vscode.ExtensionContext) {
  status = vscode.window.createStatusBarItem(
    vscode.StatusBarAlignment.Left,
    90,
  );
  status.command = "reviewLens.reviewPr";
  disposables.push(status);
  const update = async () => {
    const s = session?.service;
    await vscode.commands.executeCommand(
      "setContext",
      "reviewLens.active",
      !!s?.active,
    );
    if (s?.active) {
      status.text = `$(lock) Reviewing ${s.active.target.prNumber ? "#" + s.active.target.prNumber : s.active.target.label} · ${s.viewedCount()} of ${s.changes.length} viewed`;
      status.tooltip =
        "Review Lens · Read-only while reviewing. Open the Review sidebar for scope, finish and exit.";
      status.backgroundColor = new vscode.ThemeColor(
        "statusBarItem.warningBackground",
      );
      status.show();
    } else status.hide();
    await session?.editor.refresh();
  };
  const register = (name: string, action: (...args: any[]) => unknown) =>
    disposables.push(
      vscode.commands.registerCommand(
        `reviewLens.${name}`,
        async (...args: any[]) => {
          try {
            if (!vscode.workspace.isTrusted)
              throw new Error(
                "Trust this workspace before running Review Lens.",
              );
            return await action(...args);
          } catch (error) {
            void vscode.window.showErrorMessage(
              `Review Lens: ${error instanceof Error ? error.message : String(error)}`,
            );
          }
        },
      ),
    );
  const setup = async (folder?: vscode.WorkspaceFolder) => {
    if (session?.service.active)
      throw new Error(
        "Exit your current review before switching repositories.",
      );
    const folders =
      vscode.workspace.workspaceFolders?.filter(
        (f) => f.uri.scheme === "file",
      ) ?? [];
    if (!folder) {
      if (folders.length === 1) folder = folders[0];
      else if (folders.length > 1)
        folder = (
          await vscode.window.showQuickPick(
            folders.map((f) => ({
              label: f.name,
              description: f.uri.fsPath,
              folder: f,
            })),
            { title: "Choose the repository to review" },
          )
        )?.folder;
    }
    if (!folder)
      throw new Error("Open a local Git repository folder to use Review Lens.");
    const git = await GitClient.discover(folder.uri.fsPath);
    session?.sidebar.dispose();
    session?.editor.dispose();
    provider?.dispose();
    const service = new ReviewService(git),
      editor = new ReviewEditor(service, context.extensionUri);
    const sidebar = new ReviewSidebar(
      service,
      editor,
      context.extensionUri,
      update,
    );
    session = { service, editor, sidebar };
    provider = vscode.window.registerWebviewViewProvider(
      "reviewLens.review",
      sidebar,
      { webviewOptions: { retainContextWhenHidden: true } },
    );
    service.on("change", () => {
      void update().catch(() => {});
    });
    try {
      await service.initialize();
    } catch (error) {
      void vscode.window.showErrorMessage(String(error));
    }
    await update();
    if (service.active) {
      void vscode.window
        .showInformationMessage(
          `Still reviewing ${service.active.target.label}. Exit review restores ${service.active.originalRef}.`,
          "Open review",
        )
        .then((choice) => {
          if (choice) void sidebar.reveal();
        });
      void service.syncViewed();
    }
    return session;
  };
  const ensure = async () => session ?? setup();
  const current = (s: ExtensionSession) => {
    const input = vscode.window.tabGroups.activeTabGroup.activeTab?.input;
    return input instanceof vscode.TabInputTextDiff
      ? s.editor.relative(input.modified)
      : vscode.window.activeTextEditor
        ? s.editor.relative(vscode.window.activeTextEditor.document.uri)
        : undefined;
  };
  register("reviewPr", async () => (await ensure()).sidebar.reveal());
  register("selectRepository", async () => {
    const s = await setup();
    await s.sidebar.reveal();
  });
  register("refresh", async () => (await ensure()).sidebar.refresh());
  register("nextFile", async () => {
    const s = await ensure(),
      next = s.service.next(current(s));
    if (next) await s.editor.open(next.path);
    else if (s.service.active) {
      const choice = await vscode.window.showInformationMessage(
        "Every file is viewed.",
        ...(s.service.meta?.target.prNumber != null ? ["Finish review…"] : []),
      );
      if (choice) await s.sidebar.reveal(true);
    }
  });
  register("previousFile", async () => {
    const s = await ensure(),
      next = s.service.next(current(s), true);
    if (next) await s.editor.open(next.path);
  });
  register("markViewed", async () => {
    const s = await ensure(),
      file = current(s);
    if (!file || !s.service.meta) return;
    await s.service.toggle(file);
    const change = s.service.changes.find((c) => c.path === file);
    if (
      !change ||
      !s.service.meta ||
      badge(s.service.meta, change) !== "viewed"
    )
      return;
    const next = s.service.next(file);
    if (next) await s.editor.open(next.path);
    else if (
      s.service.changes.length &&
      s.service.viewedCount() === s.service.changes.length &&
      s.service.meta.target.prNumber != null
    ) {
      const choice = await vscode.window.showInformationMessage(
        "All files viewed. Finish your review when ready.",
        "Finish review…",
      );
      if (choice) await s.sidebar.reveal(true);
    }
  });
  register("diff", async () => {
    const s = await ensure(),
      file = current(s) ?? s.service.changes[0]?.path;
    if (!file) return;
    const tab = vscode.window.tabGroups.activeTabGroup.activeTab;
    await s.editor.open(file, !(tab?.input instanceof vscode.TabInputTextDiff));
  });
  register("scope", async () => {
    const s = await ensure(),
      meta = s.service.meta;
    if (!meta) return;
    const action = await vscode.window.showQuickPick(
      [
        { label: "Choose review scope", fetch: false },
        { label: "Check GitHub for new commits…", fetch: true },
      ],
      { title: meta.scopeLabel },
    );
    if (!action) return;
    const loaded = await vscode.window.withProgress(
      {
        location: vscode.ProgressLocation.Notification,
        title: "Loading review commits",
      },
      () => s.service.load(meta.target, action.fetch),
    );
    const all = resolveScope(loaded, []),
      since = sinceLastReview(loaded, meta.seenHead);
    const options = [
      { label: all.label, description: "Entire pull request", scope: all },
      ...(since
        ? [
            {
              label: since.label,
              description: "New commits since last review",
              scope: since,
            },
          ]
        : []),
      ...loaded.commits.map((c, i) => ({
        label: `${c.sha.slice(0, 7)} ${c.subject}`,
        description: c.author,
        scope: resolveScope(loaded, [i]),
      })),
    ];
    const picked = await vscode.window.showQuickPick(options, {
      title: "Review scope",
    });
    if (!picked) return;
    if (!(await s.editor.saveBuffers())) return;
    await s.service.begin(loaded, picked.scope);
    await update();
    await s.service.syncViewed();
    const file = s.service.next() ?? s.service.changes[0];
    if (file) await s.editor.open(file.path);
  });
  register("finish", async () => (await ensure()).sidebar.reveal(true));
  register("exit", async () => {
    const s = await ensure();
    const result = await s.service.exit();
    await s.editor.releaseGuards();
    await update();
    await s.sidebar.afterExit();
    s.sidebar.restored(result);
  });
  register("addComment", async (reply: vscode.CommentReply) =>
    (await ensure()).editor.addReply(reply),
  );
  register(
    "editComment",
    async (comment: vscode.Comment & { index?: number }) => {
      const s = await ensure(),
        index = comment.index;
      if (index === undefined) return;
      const draft = s.service.meta?.drafts?.[index];
      if (!draft || draft.posted) return;
      const body = await vscode.window.showInputBox({
        title: "Edit draft comment",
        value: draft.body,
        validateInput: (value) =>
          value.trim() ? undefined : "Comment cannot be blank.",
      });
      if (body !== undefined) await s.service.editDraft(index, body);
    },
  );
  register(
    "deleteComment",
    async (comment: vscode.Comment & { index?: number }) => {
      if (comment.index !== undefined)
        await (await ensure()).service.editDraft(comment.index);
    },
  );
  if (
    vscode.workspace.isTrusted &&
    vscode.workspace.workspaceFolders?.length === 1
  ) {
    try {
      await setup(vscode.workspace.workspaceFolders[0]);
    } catch {
      /* Commands report actionable setup errors on demand. */
    }
  }
  context.subscriptions.push({
    dispose: () => {
      session?.sidebar.dispose();
      session?.editor.dispose();
      provider?.dispose();
      disposables.splice(0).forEach((d) => d.dispose());
    },
  });
  return { getSession: () => session, setup };
}
export async function deactivate() {
  await session?.editor.releaseGuards();
}
