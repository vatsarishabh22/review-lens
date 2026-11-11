import { EventEmitter } from "node:events";
import {
  badge,
  branchTarget,
  FileChange,
  ListedTarget,
  LoadedTarget,
  ReviewMeta,
  ScopeRange,
  Verdict,
  mergeViewed,
  resolveScope,
  reviewPayload,
  submitBlocker,
  toPost,
  prKey,
  syncViewedState,
  repliesToPost,
} from "../core/model";
import { ReviewThread, ReviewSlot, slotBlocker } from "../core/threads";
import { GitClient } from "../infrastructure/git";
import { GhClient } from "../infrastructure/github";
import { StateStore } from "../infrastructure/store";
import { Restored, ReviewLifecycle } from "./lifecycle";
export class ReviewService extends EventEmitter {
  readonly store: StateStore;
  readonly lifecycle: ReviewLifecycle;
  active?: Awaited<ReturnType<StateStore["active"]>>;
  meta?: ReviewMeta;
  changes: FileChange[] = [];
  tracked: string[] = [];
  sync = "Viewed kept on this laptop";
  own = false;
  busy = false;
  step = "";
  recovery?: string;
  threads: ReviewThread[] = [];
  threadsKnown = false;
  threadError?: string;
  slot?: ReviewSlot;
  covered: Record<number, string> = {};
  impactAnalysisComplete = false;
  readonly impactKeys = new Set<string>();
  private viewedHost?: { key: string; id: string; files: Set<string> };
  private readonly viewedSends = new Map<string, Promise<void>>();
  private readonly viewedVersions = new Map<string, number>();
  private tail: Promise<unknown> = Promise.resolve();
  constructor(
    readonly git: GitClient,
    readonly gh = new GhClient(git.root),
  ) {
    super();
    this.store = new StateStore(git.gitDir);
    this.lifecycle = new ReviewLifecycle(git, this.store);
  }
  /** All writes, including GitHub confirmations, share one queue. Failed jobs do not poison it. */
  exclusive<T>(work: () => Promise<T>): Promise<T> {
    const job = this.tail.then(async () => {
      this.busy = true;
      this.emit("change");
      try {
        return await work();
      } finally {
        this.busy = false;
        this.step = "";
        this.emit("change");
      }
    });
    this.tail = job.catch(() => {});
    return job;
  }
  async initialize() {
    return this.exclusive(() => this.reload());
  }
  private async reload() {
    this.recovery = undefined;
    this.active = await this.store.active();
    this.impactKeys.clear();
    this.threads = [];
    this.threadsKnown = false;
    this.slot = undefined;
    this.meta = this.active
      ? await this.store.meta(this.active.target.key)
      : undefined;
    if (this.active && !this.meta)
      this.recovery =
        "Review recovery state exists without scope metadata. Use Exit review to restore your branch.";
    if (this.active)
      try {
        await this.lifecycle.assertCheckout(this.active);
      } catch (error) {
        this.recovery = error instanceof Error ? error.message : String(error);
      }
    if (this.recovery) {
      this.changes = [];
      this.tracked = [];
      this.emit("change");
      return;
    }
    this.changes = this.meta ? await this.git.changes(this.meta.range) : [];
    this.tracked = this.meta ? await this.git.trackedFiles() : [];
    this.emit("change");
  }
  async list(): Promise<{ targets: ListedTarget[]; branchesOnly: boolean }> {
    let prs;
    try {
      if (await this.gh.ready()) prs = await this.gh.list();
    } catch {
      /* Branches are the offline fallback. */
    }
    let targets: ListedTarget[];
    if (prs) {
      const me = await this.gh.viewer().catch(() => "");
      targets = prs.map((pr) => ({
        target: {
          key: prKey(pr.number),
          label: `#${pr.number} ${pr.title}`,
          prNumber: pr.number,
          head: pr.headRefName,
          base: `origin/${pr.baseRefName}`,
        },
        title: pr.title,
        pr,
        requested: pr.reviewRequests.some((r) => r.login === me && !!me),
      }));
    } else {
      const base = await this.git.defaultBase();
      targets = (await this.git.branches())
        .filter((b) => b !== base)
        .map((b) => ({
          target: branchTarget(b, base),
          title: b,
          requested: false,
        }));
    }
    for (const target of targets) {
      const saved = await this.store.meta(target.target.key);
      if (!saved) continue;
      target.newCommits =
        !!target.pr?.headRefOid &&
        target.pr.headRefOid !== (saved.seenHead ?? saved.range.to);
      const changes = await this.git
        .changes(saved.range)
        .catch(() => undefined);
      if (changes)
        target.progress = {
          viewed: changes.filter((c) => badge(saved, c) === "viewed").length,
          total: changes.length,
          verdict: saved.verdict,
        };
    }
    return { targets, branchesOnly: !prs };
  }
  async load(
    target: ListedTarget["target"],
    fetch = true,
  ): Promise<LoadedTarget> {
    let ref: string;
    if (fetch) {
      if (target.prNumber != null)
        ref = await this.git.fetchPr(target.prNumber, target.base);
      else {
        await this.git.git("fetch", "--no-tags", "origin");
        ref = target.head;
      }
    } else
      ref =
        this.meta?.seenHead ||
        (target.prNumber != null
          ? `refs/review-lens/pr-${target.prNumber}`
          : target.head);
    const head = await this.git.revParse(ref),
      mergeBase = (await this.git.git("merge-base", target.base, head)).trim();
    return {
      target,
      head,
      mergeBase,
      commits: await this.git.commits(mergeBase, head),
    };
  }
  async begin(loaded: LoadedTarget, scope = resolveScope(loaded, [])) {
    return this.exclusive(async () => {
      const existing = await this.store.active();
      if (existing && existing.target.key !== loaded.target.key)
        throw new Error("Exit the current review first.");
      const previous = await this.store.meta(loaded.target.key);
      const meta: ReviewMeta = {
        ...previous,
        target: loaded.target,
        range: scope.range,
        scopeLabel: scope.label,
        reviewed: previous?.reviewed ?? {},
        seenHead: loaded.head,
      };
      // Install scope before active.json can appear: a restart can always recover a valid review.
      if (!existing) {
        await this.store.saveMeta(meta);
        await this.lifecycle.start(loaded.target, scope.range.to, (step) => {
          this.step = step;
          this.emit("change");
        });
      } else {
        await this.lifecycle.assertCheckout(existing);
        if (existing.head !== scope.range.to)
          await this.lifecycle.moveTo(scope.range.to);
        try {
          await this.store.saveMeta(meta);
        } catch (error) {
          if (existing.head !== scope.range.to)
            await this.lifecycle.moveTo(existing.head);
          throw error;
        }
      }
      await this.reload();
    });
  }
  private async save(meta: ReviewMeta) {
    await this.store.saveMeta(meta);
    this.meta = meta;
    this.emit("change");
  }
  async syncViewed() {
    return this.exclusive(async () => {
      const meta = this.meta;
      this.own = false;
      if (this.recovery) return;
      if (!meta || meta.target.prNumber == null) {
        this.sync = "Viewed kept on this laptop";
        return;
      }
      try {
        const states = await this.gh.viewed(meta.target.prNumber);
        this.own = states.own;
        this.viewedHost = {
          key: meta.target.key,
          id: states.id,
          files: new Set(Object.keys(states.files)),
        };
        const queued = { ...meta.unsent },
          unsent = { ...queued };
        for (const [file, viewed] of Object.entries(queued)) {
          if (!Object.hasOwn(states.files, file)) {
            delete unsent[file];
            continue;
          }
          try {
            await this.gh.setViewed(states.id, file, viewed);
            delete unsent[file];
          } catch {
            /* Keep durable retry queue. */
          }
        }
        // The response was fetched before re-sends; queued local answers win over that stale response.
        await this.save(
          syncViewedState(
            meta,
            states,
            this.changes,
            (path) => !Object.hasOwn(unsent, path),
          ),
        );
        this.sync = Object.keys(unsent).length
          ? "GitHub sync failed · kept on this laptop"
          : "Synced with GitHub";
      } catch {
        this.sync = "GitHub sync failed · kept on this laptop";
      }
    });
  }
  async toggle(file: string) {
    const sending = await this.exclusive(async () => {
      const meta = this.meta,
        change = this.changes.find((c) => c.path === file);
      if (!meta || !change) throw new Error("File is not in this scope.");
      if (!this.active) throw new Error("No active review.");
      await this.lifecycle.assertCheckout(this.active);
      const viewed = badge(meta, change) !== "viewed";
      const reviewed = viewed
        ? { ...meta.reviewed, [file]: change.newBlob }
        : Object.fromEntries(
            Object.entries(meta.reviewed).filter(([path]) => path !== file),
          );
      await this.save({
        ...meta,
        reviewed,
        ...(meta.target.prNumber != null &&
        (!this.viewedHost || this.viewedHost.files.has(file))
          ? { unsent: { ...meta.unsent, [file]: viewed } }
          : {}),
      });
      const version = (this.viewedVersions.get(file) ?? 0) + 1;
      this.viewedVersions.set(file, version);
      return { meta, viewed, active: this.active, version };
    });
    const host = this.viewedHost;
    if (!host || host.key !== sending.meta.target.key || !host.files.has(file))
      return;
    const key = host.key + ":" + file;
    const job = (this.viewedSends.get(key) ?? Promise.resolve()).then(
      async () => {
        if (this.active !== sending.active) return;
        try {
          await this.gh.setViewed(host.id, file, sending.viewed);
          await this.exclusive(async () => {
            if (this.viewedVersions.get(file) !== sending.version) return;
            if (
              this.active !== sending.active ||
              this.meta?.unsent?.[file] !== sending.viewed
            )
              return;
            const unsent = { ...this.meta.unsent };
            delete unsent[file];
            await this.save({
              ...this.meta,
              unsent: Object.keys(unsent).length ? unsent : null,
            });
          });
        } catch {
          if (this.active === sending.active) {
            this.sync = "GitHub sync failed · kept on this laptop";
            this.emit("change");
          }
        }
      },
    );
    this.viewedSends.set(key, job);
    void job.finally(() => {
      if (this.viewedSends.get(key) === job) this.viewedSends.delete(key);
    });
  }
  async addDraft(file: string, line: number, body: string, startLine?: number) {
    return this.exclusive(async () => {
      const meta = this.meta,
        change = this.changes.find((c) => c.path === file);
      if (
        !meta ||
        meta.target.prNumber == null ||
        !change ||
        !body.trim() ||
        !Number.isInteger(line) ||
        line < 1
      )
        throw new Error("Invalid draft comment.");
      if (!this.active) throw new Error("No active review.");
      await this.lifecycle.assertCheckout(this.active);
      if (
        startLine !== undefined &&
        (!Number.isInteger(startLine) || startLine < 1 || startLine > line)
      )
        throw new Error("Invalid comment range.");
      const hunks = await this.git.commentHunks(meta.range, change);
      if (
        !hunks.some(
          (h) =>
            h.count > 0 &&
            (startLine ?? line) >= h.start &&
            line < h.start + h.count,
        )
      )
        throw new Error(
          "Comments must be on an added or modified line in this scope.",
        );
      await this.save({
        ...meta,
        drafts: [
          ...(meta.drafts ?? []),
          {
            path: file,
            line,
            blob: change.newBlob,
            body: body.trim(),
            posted: false,
            ...(startLine !== undefined && startLine !== line
              ? { startLine }
              : {}),
          },
        ],
      });
    });
  }
  async refreshThreads() {
    const active = this.active,
      meta = this.meta,
      number = meta?.target.prNumber;
    if (!active || !meta || number == null) return;
    try {
      const threads = await this.gh.reviewThreads(number);
      if (this.active !== active) return;
      const loaded = await Promise.all(
        threads.map(async (thread) => {
          if (
            thread.line == null ||
            thread.diffSide !== "RIGHT" ||
            thread.comments[0]?.commit !== meta.range.to
          )
            return thread;
          const text = await this.git
            .show(meta.range.to, thread.path)
            .catch(() => "");
          return {
            ...thread,
            originalText: text
              .split(/\r?\n/)
              .slice((thread.startLine ?? thread.line) - 1, thread.line)
              .join("\n"),
          };
        }),
      );
      if (this.active !== active) return;
      this.threads = loaded;
      this.threadsKnown = true;
      this.threadError = undefined;
    } catch (error) {
      if (this.active !== active) return;
      this.threadsKnown = false;
      this.threadError = error instanceof Error ? error.message : String(error);
    }
    this.emit("change");
  }
  private coveredMeta?: ReviewMeta;
  private covering?: Promise<void>;
  async refreshCovered() {
    if (this.coveredMeta === this.meta) return this.covering;
    this.coveredMeta = this.meta;
    this.covering = this.loadCovered();
    return this.covering;
  }
  private async loadCovered() {
    const meta = this.meta;
    if (!meta) return;
    const covered: Record<number, string> = {};
    await Promise.all(
      (meta.drafts ?? []).map(async (d, index) => {
        const change = this.changes.find(
          (c) => c.path === d.path && c.newBlob === d.blob,
        );
        if (!change || d.replyTo) return;
        const text = await this.git.show(meta.range.to, d.path).catch(() => "");
        covered[index] = text
          .split(/\r?\n/)
          .slice((d.startLine ?? d.line) - 1, d.line)
          .join("\n");
      }),
    );
    if (this.meta === meta) this.covered = covered;
  }
  async prepareFinish() {
    await this.refreshThreads();
    const active = this.active,
      number = this.meta?.target.prNumber;
    if (!active || number == null) return;
    const slot = await this.gh.reviewSlot(number);
    if (this.active === active) {
      this.slot = slot;
      this.own = slot.own;
      this.emit("change");
    }
  }
  outbox() {
    const drafts = this.meta?.drafts ?? [];
    return {
      comments: toPost(drafts, this.changes),
      replies: repliesToPost(
        drafts,
        this.threadsKnown
          ? this.threads.filter((t) => !t.isResolved).map((t) => t.id)
          : null,
      ),
    };
  }
  async reply(threadId: string, body: string) {
    return this.exclusive(async () => {
      const meta = this.meta,
        thread = this.threads.find((t) => t.id === threadId);
      if (!meta || !thread || thread.isResolved || !body.trim())
        throw new Error("Reply needs an open thread and nonempty text.");
      const change = this.changes.find((c) => c.path === thread.path);
      const blob =
        change?.newBlob ??
        (
          await this.git
            .git("rev-parse", `${meta.range.to}:${thread.path}`)
            .catch(() => "0".repeat(40))
        ).trim();
      await this.save({
        ...meta,
        drafts: [
          ...(meta.drafts ?? []),
          {
            path: thread.path,
            line: thread.line ?? thread.originalLine ?? 1,
            blob,
            body: body.trim(),
            replyTo: thread.id,
            posted: false,
          },
        ],
      });
    });
  }
  async resolveThread(id: string, resolved: boolean) {
    await this.exclusive(async () => {
      const thread = this.threads.find((t) => t.id === id);
      if (
        !thread ||
        !(resolved ? thread.viewerCanResolve : thread.viewerCanUnresolve)
      )
        throw new Error("GitHub does not allow this thread action.");
      await this.gh.resolveThread(id, resolved);
    });
    await this.refreshThreads();
  }
  async postedComment(id: string, body?: string) {
    await this.exclusive(async () => {
      const comment = this.threads
        .flatMap((t) => t.comments)
        .find((c) => c.id === id);
      if (
        !comment ||
        !(body === undefined ? comment.canDelete : comment.canEdit)
      )
        throw new Error("GitHub does not allow this comment action.");
      if (body !== undefined && !body.trim())
        throw new Error("Comment cannot be blank.");
      if (body === undefined) await this.gh.deleteComment(id);
      else await this.gh.editComment(id, body);
    });
    await this.refreshThreads();
  }
  async updateChecked(key: string, hash: string, place: string) {
    return this.exclusive(async () => {
      const meta = this.meta;
      if (!meta) return;
      const previous = meta.checked?.[key],
        places = new Set(previous?.hash === hash ? previous.places : []);
      if (places.has(place)) places.delete(place);
      else places.add(place);
      await this.save({
        ...meta,
        checked: { ...meta.checked, [key]: { hash, places: [...places] } },
      });
    });
  }
  async editDraft(index: number, body?: string) {
    return this.exclusive(async () => {
      const meta = this.meta,
        draft = meta?.drafts?.[index];
      if (!meta || !draft || draft.posted)
        throw new Error("This draft cannot be changed.");
      if (body !== undefined && !body.trim())
        throw new Error("Comment cannot be blank.");
      await this.save({
        ...meta,
        drafts: meta.drafts!.flatMap((d, i) =>
          i !== index
            ? [d]
            : body === undefined
              ? []
              : [{ ...d, body: body.trim() }],
        ),
      });
    });
  }
  private async end(): Promise<Restored> {
    const meta = this.meta;
    const result = await this.lifecycle.exit();
    if (meta?.checked && this.impactAnalysisComplete)
      await this.store.saveMeta({
        ...meta,
        checked: Object.fromEntries(
          Object.entries(meta.checked).filter(([key]) =>
            this.impactKeys.has(key),
          ),
        ),
      });
    await this.reload();
    return result;
  }
  async exit() {
    return this.exclusive(() => this.end());
  }
  async finish(summary: string, verdict?: Verdict): Promise<Restored> {
    return this.exclusive(async () => {
      const meta = this.meta;
      if (!meta || meta.target.prNumber == null)
        throw new Error("Only pull requests can be submitted.");
      if (verdict) {
        await this.lifecycle.assertCheckout(this.active!);
        await this.refreshThreads();
        const outbox = this.outbox(),
          sending = [...outbox.comments, ...outbox.replies];
        const submission = {
          commit: meta.range.to,
          summary,
          verdict,
          ...outbox,
        };
        this.slot = await this.gh.reviewSlot(meta.target.prNumber);
        const blocker = slotBlocker(this.slot, submission);
        if (blocker) throw new Error(blocker);
        await this.gh.submit(meta.target.prNumber, submission);
        await this.save({
          ...meta,
          verdict,
          drafts: meta.drafts?.map((d) =>
            sending.includes(d) ? { ...d, posted: true } : d,
          ),
        });
      }
      return this.end();
    });
  }
  viewedCount() {
    return this.meta
      ? this.changes.filter((c) => badge(this.meta!, c) === "viewed").length
      : 0;
  }
  next(after?: string, previous = false) {
    const i = this.changes.findIndex((c) => c.path === after),
      n = this.changes.length;
    if (!n) return undefined;
    if (previous) return this.changes[(i <= 0 ? n : i) - 1];
    for (let k = 1; k <= n; k++) {
      const c = this.changes[(i + k + n) % n];
      if (c.path !== after && this.meta && badge(this.meta, c) !== "viewed")
        return c;
    }
  }
}
