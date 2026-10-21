import * as vscode from "vscode";
import * as path from "node:path";
import { ReviewService } from "../services/review";
import { badge, FileChange } from "../core/model";
import { Hunk } from "../core/parsers";
/** Git snapshots are immutable and also serve as the left side of native diffs. */
export class ReviewEditor
  implements
    vscode.Disposable,
    vscode.TextDocumentContentProvider,
    vscode.FileDecorationProvider,
    vscode.CodeLensProvider
{
  private readonly disposables: vscode.Disposable[] = [];
  private readonly decorationChanges = new vscode.EventEmitter<
    vscode.Uri | vscode.Uri[] | undefined
  >();
  private readonly lensChanges = new vscode.EventEmitter<void>();
  readonly onDidChangeFileDecorations = this.decorationChanges.event;
  readonly onDidChangeCodeLenses = this.lensChanges.event;
  private readonly hunks = new Map<string, Promise<Hunk[]>>();
  private readonly blames = new Map<
    string,
    ReturnType<ReviewService["git"]["blame"]>
  >();
  private scopeCommits?: Promise<Set<string>>;
  private readOnlyAvailable?: Promise<boolean>;
  private readonly guarded = new Map<string, vscode.Uri>();
  private readonly markers: vscode.TextEditorDecorationType[];
  private readonly blameDecoration =
    vscode.window.createTextEditorDecorationType({
      after: {
        color: new vscode.ThemeColor("descriptionForeground"),
        fontStyle: "italic",
        margin: "0 0 0 3em",
      },
    });
  private readonly controller = vscode.comments.createCommentController(
    "review-lens",
    "Review Lens",
  );
  private threads: vscode.CommentThread[] = [];
  private range = "";
  constructor(
    readonly service: ReviewService,
    readonly extensionUri: vscode.Uri,
  ) {
    this.markers = (["added", "modified", "deleted"] as const).map((kind) =>
      vscode.window.createTextEditorDecorationType({
        isWholeLine: true,
        gutterIconPath: vscode.Uri.joinPath(
          extensionUri,
          "media",
          kind + ".svg",
        ),
        overviewRulerColor: new vscode.ThemeColor(
          kind === "added"
            ? "editorGutter.addedBackground"
            : kind === "modified"
              ? "editorGutter.modifiedBackground"
              : "editorGutter.deletedBackground",
        ),
        overviewRulerLane: vscode.OverviewRulerLane.Left,
      }),
    );
    this.disposables.push(
      ...this.markers,
      this.blameDecoration,
      this.controller,
      this.decorationChanges,
      this.lensChanges,
      vscode.workspace.registerTextDocumentContentProvider("review-lens", this),
      vscode.window.registerFileDecorationProvider(this),
      vscode.languages.registerCodeLensProvider(
        [{ scheme: "file" }, { scheme: "review-lens" }],
        this,
      ),
      vscode.window.onDidChangeActiveTextEditor((editor) => {
        if (editor) void this.paint(editor).catch((e) => this.report(e));
      }),
      vscode.window.onDidChangeVisibleTextEditors((editors) => {
        for (const editor of editors)
          void this.paint(editor).catch((e) => this.report(e));
      }),
      vscode.window.onDidChangeTextEditorSelection((e) => {
        void this.blame(e.textEditor).catch(() => {});
      }),
    );
    this.controller.options = {
      prompt: "Write a draft comment",
      placeHolder: "Posted when you finish the review",
    };
    this.controller.commentingRangeProvider = {
      provideCommentingRanges: async (document) => {
        const file = this.relative(document.uri),
          change = this.service.changes.find((c) => c.path === file);
        if (
          !change ||
          this.service.meta?.target.prNumber == null ||
          !this.isCurrent(document.uri)
        )
          return [];
        return (await this.changedLines(change))
          .filter((h) => h.count > 0)
          .map(
            (h) => new vscode.Range(h.start - 1, 0, h.start + h.count - 2, 0),
          );
      },
    };
    const onChange = () => {
      void this.refresh().catch((e) => this.report(e));
    };
    service.on("change", onChange);
    this.disposables.push({ dispose: () => service.off("change", onChange) });
  }
  private report(error: unknown) {
    void vscode.window.showErrorMessage(
      `Review Lens: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
  relative(uri: vscode.Uri): string | undefined {
    if (uri.scheme === "file") return this.service.git.relative(uri.fsPath);
    if (uri.scheme === "review-lens") {
      try {
        const q = JSON.parse(uri.query);
        return typeof q.file === "string" ? q.file : undefined;
      } catch {
        return undefined;
      }
    }
  }
  private isCurrent(uri: vscode.Uri) {
    return (
      uri.scheme === "file" ||
      (uri.scheme === "review-lens" &&
        JSON.parse(uri.query).rev === this.service.meta?.range.to)
    );
  }
  uri(rev: string, file: string, empty = false): vscode.Uri {
    return vscode.Uri.from({
      scheme: "review-lens",
      path: "/" + file,
      query: JSON.stringify({ rev, file, empty }),
    });
  }
  async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
    const q = JSON.parse(uri.query);
    this.service.git.file(q.file);
    if (q.empty) return "";
    if (!/^[0-9a-f]{40}\^?$/.test(q.rev))
      throw new Error("Invalid review revision.");
    const text = await this.service.git.show(q.rev, q.file);
    return text.includes("\0")
      ? "Binary file — use the file's native viewer.\n"
      : text;
  }
  async ensureReadOnlyAvailable() {
    // The built-in session command keeps real file URIs, language services, and user settings intact.
    return (this.readOnlyAvailable ??= Promise.resolve(
      vscode.commands.getCommands(true),
    ).then((commands) =>
      commands.includes(
        "workbench.action.files.setActiveEditorReadonlyInSession",
      ),
    ));
  }
  async open(file: string, diff = false) {
    if (this.service.recovery) throw new Error(this.service.recovery);
    const meta = this.service.meta;
    if (!meta) return;
    const change = this.service.changes.find((c) => c.path === file);
    if (diff || change?.status === "D") {
      if (!change) return;
      const before = this.uri(
        meta.range.from,
        change.oldPath ?? file,
        change.status === "A",
      );
      const after = this.uri(meta.range.to, file, change.status === "D");
      await vscode.commands.executeCommand(
        "vscode.diff",
        before,
        after,
        `${path.basename(file)} · ${meta.scopeLabel}`,
        { preview: false },
      );
      return;
    }
    const uri = (await this.ensureReadOnlyAvailable())
      ? vscode.Uri.file(this.service.git.file(file))
      : this.uri(meta.range.to, file);
    await vscode.window.showTextDocument(uri, { preview: false });
    if (vscode.window.activeTextEditor)
      await this.paint(vscode.window.activeTextEditor);
  }
  async saveBuffers() {
    const dirty = vscode.workspace.textDocuments.filter(
      (d) =>
        d.uri.scheme === "file" &&
        this.service.git.relative(d.uri.fsPath) &&
        d.isDirty,
    );
    if (dirty.length) {
      const choice = await vscode.window.showWarningMessage(
        `Save ${dirty.length} unsaved file${dirty.length === 1 ? "" : "s"} before parking your work?`,
        "Save and continue",
        "Cancel",
      );
      if (choice !== "Save and continue") return false;
      for (const document of dirty)
        if (!(await document.save()))
          throw new Error(
            "An unsaved file could not be saved. Review was not started.",
          );
    }
    return true;
  }
  private async guard(editor: vscode.TextEditor) {
    if (
      !this.service.meta ||
      editor.document.uri.scheme !== "file" ||
      !this.relative(editor.document.uri)
    )
      return;
    if (vscode.window.activeTextEditor !== editor) return;
    if (await this.ensureReadOnlyAvailable()) {
      if (vscode.window.activeTextEditor !== editor || !this.service.meta)
        return;
      await vscode.commands.executeCommand(
        "workbench.action.files.setActiveEditorReadonlyInSession",
      );
      this.guarded.set(editor.document.uri.toString(), editor.document.uri);
    } else {
      // Old hosts/forks without the command use a truly immutable content-provider editor.
      const file = this.relative(editor.document.uri)!;
      if (!editor.document.isDirty)
        await vscode.window.showTextDocument(
          this.uri(this.service.meta.range.to, file),
          { preview: false },
        );
    }
  }
  async releaseGuards() {
    const selected = vscode.window.activeTextEditor?.document.uri;
    for (const uri of this.guarded.values()) {
      try {
        await vscode.window.showTextDocument(uri, {
          preview: false,
          preserveFocus: false,
        });
        await vscode.commands.executeCommand(
          "workbench.action.files.resetActiveEditorReadonlyInSession",
        );
      } catch {
        /* A file deleted by checkout no longer has an editor. */
      }
    }
    this.guarded.clear();
    const tabs = vscode.window.tabGroups.all
      .flatMap((g) => g.tabs)
      .filter(
        (t) =>
          (t.input instanceof vscode.TabInputTextDiff &&
            (t.input.original.scheme === "review-lens" ||
              t.input.modified.scheme === "review-lens")) ||
          (t.input instanceof vscode.TabInputText &&
            t.input.uri.scheme === "review-lens"),
      );
    if (tabs.length) await vscode.window.tabGroups.close(tabs);
    if (selected?.scheme === "file")
      await vscode.window.showTextDocument(selected, { preview: false }).then(
        () => {},
        () => {},
      );
  }
  async refresh() {
    const scope = this.service.meta
      ? `${this.service.meta.range.from}:${this.service.meta.range.to}`
      : "";
    if (scope !== this.range) {
      this.range = scope;
      this.hunks.clear();
      this.blames.clear();
      this.scopeCommits = undefined;
    }
    this.decorationChanges.fire(undefined);
    this.lensChanges.fire();
    await this.renderComments();
    for (const editor of vscode.window.visibleTextEditors)
      await this.paint(editor);
  }
  async changedLines(change: FileChange) {
    if (!this.service.meta) return [];
    const key = this.range + change.path;
    if (!this.hunks.has(key))
      this.hunks.set(
        key,
        this.service.git.hunks(this.service.meta.range, change),
      );
    return this.hunks.get(key)!;
  }
  private async paint(editor: vscode.TextEditor) {
    if (editor.document.isClosed) return;
    await this.guard(editor);
    const scope = this.range,
      file = this.relative(editor.document.uri),
      change = this.service.changes.find((c) => c.path === file);
    const hunks =
      change && this.isCurrent(editor.document.uri)
        ? await this.changedLines(change)
        : [];
    if (scope !== this.range || editor.document.isClosed) return;
    ["added", "modified", "deleted"].forEach((kind, i) =>
      editor.setDecorations(
        this.markers[i],
        hunks
          .filter((h) => h.kind === kind)
          .map((h) => ({
            range: new vscode.Range(
              Math.max(
                0,
                Math.min(
                  editor.document.lineCount - 1,
                  h.start - (h.count ? 1 : 0),
                ),
              ),
              0,
              Math.max(
                0,
                Math.min(
                  editor.document.lineCount - 1,
                  h.start + Math.max(h.count, 1) - 2,
                ),
              ),
              0,
            ),
            hoverMessage: `${kind === "added" ? "Added" : kind === "deleted" ? "Deleted" : "Modified"} in this review scope. Open File / Diff to see the change.`,
          })),
      ),
    );
    await this.blame(editor);
  }
  private async blame(editor: vscode.TextEditor) {
    editor.setDecorations(this.blameDecoration, []);
    const meta = this.service.meta,
      file = this.relative(editor.document.uri);
    if (
      !meta ||
      !file ||
      !this.service.changes.some((c) => c.path === file) ||
      !this.isCurrent(editor.document.uri)
    )
      return;
    const range = this.range,
      line = editor.selection.active.line;
    const key = range + file;
    if (!this.blames.has(key))
      this.blames.set(
        key,
        this.service.git.blame(meta.range, file).catch(() => new Map()),
      );
    const blame = (await this.blames.get(key)!).get(line + 1);
    if (
      !blame ||
      range !== this.range ||
      line !== editor.selection.active.line ||
      editor.document.isClosed
    )
      return;
    // Only commits actually included in the scope get an annotation.
    this.scopeCommits ??= this.service.git
      .git("rev-list", `${meta.range.from}..${meta.range.to}`)
      .then((text) => new Set(text.split(/\r?\n/)));
    const shas = await this.scopeCommits;
    if (
      range === this.range &&
      shas.has(blame.sha) &&
      !editor.document.isClosed &&
      line === editor.selection.active.line
    )
      editor.setDecorations(this.blameDecoration, [
        {
          range: editor.document.lineAt(line).range,
          renderOptions: {
            after: {
              contentText: `${blame.sha.slice(0, 7)} · ${blame.author} · ${blame.summary}`,
            },
          },
        },
      ]);
  }
  provideFileDecoration(uri: vscode.Uri): vscode.FileDecoration | undefined {
    const meta = this.service.meta,
      file = this.relative(uri);
    if (!meta || !file) return;
    const change = this.service.changes.find((c) => c.path === file);
    if (change) {
      const state = badge(meta, change);
      return {
        badge: state === "viewed" ? "✓" : state === "changed" ? "↻" : "★",
        tooltip:
          state === "viewed"
            ? "Viewed"
            : state === "changed"
              ? "Changed since viewed"
              : "Not viewed",
        color: new vscode.ThemeColor(
          state === "viewed" ? "testing.iconPassed" : "list.warningForeground",
        ),
        propagate: true,
      };
    }
  }
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    const meta = this.service.meta,
      file = this.relative(document.uri);
    if (!meta || !file || !this.isCurrent(document.uri)) return [];
    const change = this.service.changes.find((c) => c.path === file),
      range = new vscode.Range(0, 0, 0, 0);
    const commands = [
      {
        title: `🔒 Reviewing ${meta.target.prNumber ? "#" + meta.target.prNumber : meta.target.label} · ${meta.scopeLabel}`,
        command: "reviewLens.scope",
      },
      { title: "Next unviewed", command: "reviewLens.nextFile" },
    ];
    if (change)
      commands.push(
        { title: "File / Diff", command: "reviewLens.diff" },
        {
          title: badge(meta, change) === "viewed" ? "✓ Viewed" : "Mark viewed",
          command: "reviewLens.markViewed",
        },
      );
    if (meta.target.prNumber != null)
      commands.push({ title: "Finish review…", command: "reviewLens.finish" });
    commands.push({ title: "Exit review", command: "reviewLens.exit" });
    return commands.map((command) => new vscode.CodeLens(range, command));
  }
  private async renderComments() {
    // Do not replace native reply editors on a progress event: preserve unsaved typing.
    const signature = JSON.stringify([this.range, this.service.meta?.drafts]);
    if (signature === this.commentSignature) return;
    this.commentSignature = signature;
    this.threads.forEach((t) => t.dispose());
    this.threads = [];
    const meta = this.service.meta;
    if (!meta || meta.target.prNumber == null || this.service.recovery) return;
    const native = await this.ensureReadOnlyAvailable();
    if (signature !== this.commentSignature) return;
    const groups = new Map<
      string,
      { index: number; draft: NonNullable<typeof meta.drafts>[number] }[]
    >();
    meta.drafts?.forEach((draft, index) => {
      if (
        this.service.changes.some(
          (c) => c.path === draft.path && c.newBlob === draft.blob,
        )
      ) {
        const key = `${draft.path}\0${draft.line}`;
        groups.set(key, [...(groups.get(key) ?? []), { draft, index }]);
      }
    });
    for (const group of groups.values()) {
      const { draft } = group[0];
      const uri = native
        ? vscode.Uri.file(this.service.git.file(draft.path))
        : this.uri(meta.range.to, draft.path);
      const thread = this.controller.createCommentThread(
        uri,
        new vscode.Range(draft.line - 1, 0, draft.line - 1, 0),
        group.map(
          ({ draft: d, index }) =>
            ({
              body: d.body,
              mode: vscode.CommentMode.Preview,
              author: { name: "You" },
              label: d.posted ? "On GitHub" : "Pending",
              contextValue: d.posted ? "posted" : "draft",
              index,
            }) as vscode.Comment,
        ),
      );
      thread.label = "Review Lens · posted when you finish";
      thread.canReply = true;
      this.threads.push(thread);
      thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    }
  }
  private commentSignature = "";
  async addReply(reply: vscode.CommentReply) {
    const file = this.relative(reply.thread.uri);
    if (!file || !reply.thread.range)
      throw new Error("No changed line selected.");
    await this.service.addDraft(
      file,
      reply.thread.range.start.line + 1,
      reply.text,
    );
    // A newly created empty thread belongs to VS Code; remove it after persisted drafts render.
    if (!reply.thread.comments.length) reply.thread.dispose();
  }
  dispose() {
    this.threads.forEach((t) => t.dispose());
    this.disposables.forEach((d) => d.dispose());
  }
}
