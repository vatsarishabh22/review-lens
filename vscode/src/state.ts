// The shared review state in .git/review-lens/ (spec/README.md, spec/state.schema.json).
// Readers keep only the fields the contract names, so a field this editor does not know fails the fixtures test.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export interface ReviewTarget { key: string; label: string; prNumber?: number | null; head: string; base: string }
export interface ActiveReview { target: ReviewTarget; head: string; reviewBranch: string; originalRef: string; stashMessage?: string | null }
/** A pending comment on `line`, or on `startLine`..`line` when lines were selected (spec/README.md, Comment). */
export interface Draft { path: string; startLine?: number | null; line: number; blob: string; body: string; posted?: boolean; replyTo?: string | null }
export interface ReviewMeta {
  target: ReviewTarget;
  range: { from: string; to: string };
  scopeLabel: string;
  reviewed: Record<string, string>;
  seenHead?: string | null;
  unsent?: Record<string, boolean> | null;
  drafts?: Draft[] | null;
  verdict?: "COMMENT" | "APPROVE" | "REQUEST_CHANGES" | null;
  /** The impact map's ticks: function key -> the places checked while its text hashed to [hash] (spec/impact.md). */
  checked?: Record<string, { hash: string; places: string[] }> | null;
}

type Json = Record<string, any>;

/** Copies [keys] that are present, so absent stays absent and unknown fields are dropped. */
function pick<T>(o: Json, keys: string[]): T {
  return Object.fromEntries(keys.filter((k) => k in o).map((k) => [k, o[k]])) as T;
}

const target = (o: Json) => pick<ReviewTarget>(o, ["key", "label", "prNumber", "head", "base"]);

export function parseActive(o: Json): ActiveReview {
  return { ...pick<ActiveReview>(o, ["head", "reviewBranch", "originalRef", "stashMessage"]), target: target(o.target) };
}

export function parseMeta(o: Json): ReviewMeta {
  const meta = pick<ReviewMeta>(o, ["scopeLabel", "reviewed", "seenHead", "unsent", "verdict"]);
  meta.target = target(o.target);
  meta.range = pick(o.range, ["from", "to"]);
  if ("checked" in o) meta.checked = o.checked && Object.fromEntries(Object.entries(o.checked as Json).map(([k, v]: [string, any]) => [k, pick(v, ["hash", "places"])]));
  if ("drafts" in o) meta.drafts = o.drafts && o.drafts.map((d: Json) => pick<Draft>(d, ["path", "startLine", "line", "blob", "body", "posted", "replyTo"]));
  return meta;
}

function read(file: string): Json | undefined {
  return existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : undefined;
}

/** The review in progress for the repository whose git dir is [gitDir], if any. */
export function activeReview(gitDir: string): ActiveReview | undefined {
  const o = read(join(gitDir, "review-lens", "active.json"));
  return o && parseActive(o);
}

/** The saved state of review [key] ("pr-7", or a branch key), if this repository has one. */
export function reviewMeta(gitDir: string, key: string): ReviewMeta | undefined {
  const o = read(join(gitDir, "review-lens", `${key}.json`));
  return o && parseMeta(o);
}
