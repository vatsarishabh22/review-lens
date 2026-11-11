import { Draft, ReviewMeta, ReviewTarget } from "../state";
export type { ActiveReview, Draft, ReviewMeta, ReviewTarget } from "../state";
export type Verdict = NonNullable<ReviewMeta["verdict"]>;
export interface ScopeRange {
  from: string;
  to: string;
}
export interface Commit {
  sha: string;
  subject: string;
  author: string;
  time: number;
}
export interface FileChange {
  path: string;
  oldPath?: string;
  status: string;
  newBlob: string;
  added: number | null;
  deleted: number | null;
}
export interface LoadedTarget {
  target: ReviewTarget;
  head: string;
  mergeBase: string;
  commits: Commit[];
}
export interface PullRequest {
  number: number;
  title: string;
  headRefName: string;
  headRefOid?: string;
  baseRefName: string;
  author: { login: string; name?: string };
  body: string;
  additions: number;
  deletions: number;
  changedFiles: number;
  updatedAt: string;
  reviewRequests: { login?: string }[];
}
export interface ListedTarget {
  target: ReviewTarget;
  title: string;
  pr?: PullRequest;
  requested: boolean;
  newCommits?: boolean;
  progress?: { viewed: number; total: number; verdict?: Verdict | null };
}
export type ViewedState = "VIEWED" | "UNVIEWED" | "DISMISSED";
export interface ViewedStates {
  id: string;
  own: boolean;
  files: Record<string, ViewedState>;
}
export type Badge = "todo" | "viewed" | "changed";
export const prKey = (number: number) => `pr-${number}`;
export const badge = (meta: ReviewMeta, change: FileChange): Badge =>
  !Object.hasOwn(meta.reviewed, change.path)
    ? "todo"
    : meta.reviewed[change.path] === change.newBlob
      ? "viewed"
      : "changed";
export function branchTarget(branch: string, base: string): ReviewTarget {
  return {
    key:
      "branch-" +
      branch.replace(/^origin\//, "").replace(/[^A-Za-z0-9._-]/g, "_"),
    label: branch,
    head: branch,
    base,
  };
}
export function resolveScope(
  loaded: LoadedTarget,
  indexes: number[],
): { range: ScopeRange; label: string } {
  const commits = loaded.commits;
  if (!commits.length)
    throw new Error("Nothing to review: no commits beyond the base.");
  const picked = [...new Set(indexes)].sort((a, b) => a - b);
  if (picked.some((i) => !Number.isInteger(i) || i < 0 || i >= commits.length))
    throw new Error("Invalid commit selection.");
  if (!picked.length || picked.length === commits.length)
    return {
      range: { from: loaded.mergeBase, to: loaded.head },
      label: `All commits (${commits.length})`,
    };
  if (picked[picked.length - 1] - picked[0] + 1 !== picked.length)
    throw new Error("Select consecutive commits.");
  return {
    range: {
      from: commits[picked[picked.length - 1]].sha + "^",
      to: commits[picked[0]].sha,
    },
    label:
      picked.length === 1
        ? `Commit ${commits[picked[0]].sha.slice(0, 7)}`
        : `${picked.length} of ${commits.length} commits`,
  };
}
export function sinceLastReview(
  loaded: LoadedTarget,
  seenHead?: string | null,
) {
  const count = loaded.commits.findIndex((c) => c.sha === seenHead);
  return count > 0
    ? {
        range: { from: seenHead!, to: loaded.head },
        label: `Since your last review (${count})`,
      }
    : undefined;
}
export function mergeViewed(
  meta: ReviewMeta,
  states: ViewedStates,
  changes: FileChange[],
  queued = meta.unsent ?? {},
): Record<string, string> {
  const reviewed = new Map(Object.entries(meta.reviewed));
  for (const change of changes) {
    if (Object.hasOwn(queued, change.path)) continue;
    switch (states.files[change.path]) {
      case "VIEWED":
        reviewed.set(change.path, change.newBlob);
        break;
      case "UNVIEWED":
        reviewed.delete(change.path);
        break;
      case "DISMISSED":
        if (
          !reviewed.has(change.path) ||
          reviewed.get(change.path) === change.newBlob
        )
          reviewed.set(change.path, "dismissed");
        break;
    }
  }
  return Object.fromEntries(reviewed);
}
export function toPost(drafts: Draft[], changes: FileChange[]): Draft[] {
  return drafts.filter(
    (d) =>
      !d.posted &&
      !d.replyTo &&
      changes.some((c) => c.path === d.path && c.newBlob === d.blob),
  );
}
export function repliesToPost(
  drafts: Draft[],
  threads: string[] | null,
): Draft[] {
  return drafts.filter(
    (d) =>
      !d.posted &&
      !!d.replyTo &&
      (threads === null || threads.includes(d.replyTo)),
  );
}
export function syncViewedState(
  meta: ReviewMeta,
  states: ViewedStates,
  changes: FileChange[],
  send: (path: string, viewed: boolean) => boolean,
): ReviewMeta {
  const queued = meta.unsent ?? {},
    unsent: Record<string, boolean> = {};
  for (const [path, viewed] of Object.entries(queued)) {
    if (Object.hasOwn(states.files, path) && !send(path, viewed))
      Object.defineProperty(unsent, path, { value: viewed, enumerable: true });
  }
  return {
    ...meta,
    reviewed: mergeViewed(meta, states, changes, queued),
    unsent: Object.keys(unsent).length ? unsent : null,
  };
}
export function suggestionParts(
  body: string,
): ({ text: string } | { suggestion: string[] })[] {
  const parts: ({ text: string } | { suggestion: string[] })[] = [];
  const pattern = /^```suggestion\r?\n([\s\S]*?)^```[ \t]*$/gm;
  let end = 0;
  for (const match of body.matchAll(pattern)) {
    const text = body.slice(end, match.index).trim();
    if (text) parts.push({ text });
    const lines = match[1].replace(/\r/g, "").replace(/\n$/, "");
    parts.push({ suggestion: lines ? lines.split("\n") : [] });
    end = match.index! + match[0].length;
  }
  const text = body.slice(end).trim();
  if (text) parts.push({ text });
  return parts;
}
export function submitBlocker(
  verdict: Verdict,
  summary: string,
  comments: number,
  own: boolean,
): string | undefined {
  if (own && verdict !== "COMMENT")
    return "GitHub only permits a Comment review on your own pull request.";
  if (verdict !== "APPROVE" && !summary.trim() && !comments)
    return "Add a summary or an inline comment before submitting.";
}
export function reviewPayload(
  commit: string,
  summary: string,
  verdict: Verdict,
  drafts: Draft[],
) {
  return {
    commit_id: commit,
    event: verdict,
    ...(summary.trim() ? { body: summary.trim() } : {}),
    comments: drafts.map((d) => ({
      path: d.path,
      line: d.line,
      side: "RIGHT",
      body: d.body,
    })),
  };
}
