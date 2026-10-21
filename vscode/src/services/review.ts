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
} from "../core/model";
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
          key: `pr-${pr.number}`,
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
        const queued = { ...meta.unsent },
          unsent = { ...queued };
        for (const [file, viewed] of Object.entries(queued)) {
          try {
            await this.gh.setViewed(states.id, file, viewed);
            delete unsent[file];
          } catch {
            /* Keep durable retry queue. */
          }
        }
        // The response was fetched before re-sends; queued local answers win over that stale response.
        await this.save({
          ...meta,
          reviewed: mergeViewed(meta, states, this.changes, queued),
          unsent: Object.keys(unsent).length ? unsent : null,
        });
        this.sync = Object.keys(unsent).length
          ? "GitHub sync failed · kept on this laptop"
          : "Synced with GitHub";
      } catch {
        this.sync = "GitHub sync failed · kept on this laptop";
      }
    });
  }
  async toggle(file: string) {
    await this.exclusive(async () => {
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
        ...(meta.target.prNumber != null
          ? { unsent: { ...meta.unsent, [file]: viewed } }
          : {}),
      });
    });
    // Local update completes before the network operation is queued.
    return this.syncViewed();
  }
  async addDraft(file: string, line: number, body: string) {
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
      const hunks = await this.git.hunks(meta.range, change);
      if (
        !hunks.some(
          (h) => h.count > 0 && line >= h.start && line < h.start + h.count,
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
          },
        ],
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
    const result = await this.lifecycle.exit();
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
        const sending = toPost(meta.drafts ?? [], this.changes);
        const blocker = submitBlocker(
          verdict,
          summary,
          sending.length,
          this.own,
        );
        if (blocker) throw new Error(blocker);
        await this.gh.submit(
          meta.target.prNumber,
          reviewPayload(meta.range.to, summary, verdict, sending),
        );
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
