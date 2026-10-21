import * as vscode from "vscode";
import { randomBytes } from "node:crypto";
import { ReviewService } from "../services/review";
import {
  ListedTarget,
  LoadedTarget,
  resolveScope,
  sinceLastReview,
  badge,
  toPost,
  Verdict,
} from "../core/model";
import { ReviewEditor } from "./editor";
import { sidebarHtml } from "./html";
export class ReviewSidebar
  implements vscode.WebviewViewProvider, vscode.Disposable
{
  private view?: vscode.WebviewView;
  private targets: ListedTarget[] = [];
  private branchesOnly = false;
  private selected?: ListedTarget;
  private loaded?: LoadedTarget;
  private savedHead?: string | null;
  private checks = "";
  private error = "";
  private loading = false;
  private park?: { files: string[]; branch: string };
  private generation = 0;
  private operation = false;
  private readonly change = () => {
    this.post();
  };
  private readonly subscriptions: vscode.Disposable[] = [];
  constructor(
    readonly service: ReviewService,
    readonly editor: ReviewEditor,
    readonly extensionUri: vscode.Uri,
    readonly changed: () => Promise<void>,
  ) {
    service.on("change", this.change);
  }
  resolveWebviewView(view: vscode.WebviewView) {
    this.view = view;
    view.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(this.extensionUri, "media")],
    };
    const asset = (name: string) =>
      view.webview
        .asWebviewUri(vscode.Uri.joinPath(this.extensionUri, "media", name))
        .toString();
    view.webview.html = sidebarHtml(
      asset("review.css"),
      asset("review.js"),
      view.webview.cspSource,
      randomBytes(18).toString("hex"),
    );
    this.subscriptions.push(
      view.webview.onDidReceiveMessage((message) => {
        void this.dispatch(message).catch((e) => this.fail(e));
      }),
    );
    this.subscriptions.push(
      view.onDidChangeVisibility(() => {
        if (view.visible) this.post();
      }),
    );
    this.subscriptions.push(
      vscode.window.onDidChangeActiveTextEditor(() => this.post()),
    );
  }
  private fail(error: unknown) {
    this.error = error instanceof Error ? error.message : String(error);
    this.post();
    void vscode.window.showErrorMessage(`Review Lens: ${this.error}`);
  }
  async refresh() {
    this.loading = true;
    this.error = "";
    this.post();
    try {
      if (this.service.active) await this.service.syncViewed();
      else if (this.selected) await this.fetchSelected();
      else {
        const list = await this.service.list();
        this.targets = list.targets;
        this.branchesOnly = list.branchesOnly;
      }
    } finally {
      this.loading = false;
      this.post();
    }
  }
  private async fetchSelected() {
    const selected = this.selected;
    if (!selected) return;
    const generation = ++this.generation;
    const [loaded, saved, checks] = await Promise.all([
      this.service.load(selected.target),
      this.service.store.meta(selected.target.key),
      selected.pr
        ? this.service.gh
            .checks(selected.pr.number)
            .catch(() => "Checks unavailable")
        : Promise.resolve(""),
    ]);
    if (generation !== this.generation) return;
    this.loaded = loaded;
    this.savedHead = saved?.seenHead;
    this.checks = checks;
  }
  async reveal(finish = false) {
    await vscode.commands.executeCommand("workbench.view.extension.reviewLens");
    this.view?.show?.(true);
    this.post(finish);
  }
  private post(finish = false) {
    if (!this.view) return;
    const s = this.service,
      meta = s.meta;
    this.view.title = s.active
      ? "Review"
      : this.selected
        ? "Pull request"
        : "Pull requests";
    void this.view.webview.postMessage({
      type: "state",
      state: {
        active: s.active,
        meta,
        recovery: s.recovery,
        changes: s.changes.map((c) => ({
          ...c,
          badge: meta ? badge(meta, c) : "todo",
        })),
        tracked: s.tracked,
        viewed: s.viewedCount(),
        sync: s.sync,
        own: s.own,
        busy: s.busy || this.operation,
        step: s.step,
        targets: this.targets,
        branchesOnly: this.branchesOnly,
        selected: this.selected,
        loaded: this.loaded,
        since: this.loaded
          ? sinceLastReview(this.loaded, this.savedHead)
          : undefined,
        checks: this.checks,
        loading: this.loading,
        error: this.error,
        park: this.park,
        pending: meta ? toPost(meta.drafts ?? [], s.changes).length : 0,
        waiting: meta
          ? (meta.drafts ?? []).filter((d) => !d.posted).length -
            toPost(meta.drafts ?? [], s.changes).length
          : 0,
        current: vscode.window.activeTextEditor
          ? this.editor.relative(vscode.window.activeTextEditor.document.uri)
          : undefined,
        finish,
      },
    });
  }
  private scope(message: any) {
    if (!this.loaded) throw new Error("Wait for the commits to load.");
    if (message.since === true) {
      const scope = sinceLastReview(this.loaded, this.savedHead);
      if (!scope) throw new Error("No commits since your last review.");
      return scope;
    }
    if (
      !Array.isArray(message.picked) ||
      message.picked.length > this.loaded.commits.length
    )
      throw new Error("Invalid scope.");
    return resolveScope(this.loaded, message.picked);
  }
  private async dispatch(message: any) {
    if (!message || typeof message.action !== "string") return;
    if (message.action === "ready") {
      this.post();
      if (!this.targets.length && !this.service.active) await this.refresh();
      return;
    }
    if (!vscode.workspace.isTrusted)
      throw new Error("Trust this workspace before running Review Lens.");
    // Untrusted webview messages carry identifiers only; real targets and blobs stay in the extension host.
    if (this.operation || this.service.busy) return;
    this.operation = true;
    this.error = "";
    this.post();
    try {
      switch (message.action) {
        case "refresh":
          await this.refresh();
          break;
        case "back":
          this.generation++;
          this.selected = undefined;
          this.loaded = undefined;
          this.park = undefined;
          break;
        case "select": {
          const selected = this.targets.find(
            (t) => t.target.key === message.key,
          );
          if (!selected) throw new Error("Unknown review target.");
          this.selected = selected;
          this.loaded = undefined;
          this.park = undefined;
          this.checks = "Loading checks…";
          this.post();
          await this.fetchSelected();
          break;
        }
        case "start":
        case "parkStart": {
          const scope = this.scope(message);
          if (!(await this.editor.saveBuffers())) break;
          const files = await this.service.git.dirtyFiles();
          if (files.length && message.action !== "parkStart") {
            this.park = { files, branch: await this.service.git.currentRef() };
            break;
          }
          await this.service.begin(this.loaded!, scope);
          this.park = undefined;
          await this.changed();
          const first =
            this.service.next()?.path ??
            this.service.changes[0]?.path ??
            this.service.tracked[0];
          if (first) await this.editor.open(first);
          await this.service.syncViewed();
          break;
        }
        case "cancelPark":
          this.park = undefined;
          break;
        case "open":
        case "diff":
          if (
            typeof message.path === "string" &&
            this.service.tracked
              .concat(this.service.changes.map((c) => c.path))
              .includes(message.path)
          )
            await this.editor.open(message.path, message.action === "diff");
          break;
        case "toggle":
          if (typeof message.path === "string")
            await this.service.toggle(message.path);
          break;
        case "next": {
          const file = this.service.next(
            vscode.window.activeTextEditor
              ? this.editor.relative(
                  vscode.window.activeTextEditor.document.uri,
                )
              : undefined,
          );
          if (file) await this.editor.open(file.path);
          break;
        }
        case "scope":
          await vscode.commands.executeCommand("reviewLens.scope");
          break;
        case "exit":
          await vscode.commands.executeCommand("reviewLens.exit");
          await this.afterExit();
          break;
        case "finish": {
          if (
            typeof message.summary !== "string" ||
            !["COMMENT", "APPROVE", "REQUEST_CHANGES", "MARK"].includes(
              message.verdict,
            )
          )
            throw new Error("Invalid review submission.");
          const restored = await this.service.finish(
            message.summary,
            message.verdict === "MARK"
              ? undefined
              : (message.verdict as Verdict),
          );
          await this.editor.releaseGuards();
          await this.changed();
          await this.afterExit();
          this.restored(restored, "Review finished");
          break;
        }
        case "editDraft": {
          const index = message.index;
          if (!Number.isInteger(index)) throw new Error("Invalid draft.");
          const draft = this.service.meta?.drafts?.[index];
          if (!draft || draft.posted) break;
          const body = await vscode.window.showInputBox({
            title: "Edit draft comment",
            value: draft.body,
            validateInput: (value) =>
              value.trim() ? undefined : "Comment cannot be blank.",
          });
          if (body !== undefined) await this.service.editDraft(index, body);
          break;
        }
        case "deleteDraft":
          if (Number.isInteger(message.index))
            await this.service.editDraft(message.index);
          break;
      }
    } finally {
      this.operation = false;
      this.post();
    }
  }
  async afterExit() {
    this.selected = undefined;
    this.loaded = undefined;
    this.park = undefined;
    await this.refresh();
  }
  restored(result: string, title = "Back on your branch") {
    const text =
      result === "conflict"
        ? `${title}. Your parked changes need conflict resolution; the named stash is kept.`
        : result === "missing"
          ? `${title}. The parked stash was not found; it may have been restored by hand.`
          : result === "restored"
            ? `${title}. Your parked changes are restored.`
            : `${title}. Nothing was parked.`;
    if (result === "conflict" || result === "missing")
      void vscode.window.showWarningMessage(text);
    else void vscode.window.showInformationMessage(text);
  }
  dispose() {
    this.service.off("change", this.change);
    this.subscriptions.forEach((d) => d.dispose());
  }
}
