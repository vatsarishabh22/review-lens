// The shared review state in .git/review-lens/ (spec/README.md, spec/state.schema.json).
// Readers keep only the fields the contract names, so a field this editor does not know fails the fixtures test.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export interface ReviewTarget {
  key: string;
  label: string;
  prNumber?: number | null;
  head: string;
  base: string;
}
export interface ActiveReview {
  target: ReviewTarget;
  head: string;
  reviewBranch: string;
  originalRef: string;
  stashMessage?: string | null;
}
export interface Draft {
  path: string;
  line: number;
  blob: string;
  body: string;
  posted?: boolean;
}
export interface ReviewMeta {
  target: ReviewTarget;
  range: { from: string; to: string };
  scopeLabel: string;
  reviewed: Record<string, string>;
  seenHead?: string | null;
  unsent?: Record<string, boolean> | null;
  drafts?: Draft[] | null;
  verdict?: "COMMENT" | "APPROVE" | "REQUEST_CHANGES" | null;
}

type Json = Record<string, any>;

/** Copies [keys] that are present, so absent stays absent and unknown fields are dropped. */
function pick<T>(o: Json, keys: string[]): T {
  return Object.fromEntries(
    keys.filter((k) => k in o).map((k) => [k, o[k]]),
  ) as T;
}

function record(o: unknown): asserts o is Json {
  if (!o || typeof o !== "object" || Array.isArray(o))
    throw new Error("Invalid review state: expected an object.");
}
function requireState(condition: unknown, field: string): asserts condition {
  if (!condition)
    throw new Error(
      `Invalid review state field: ${field}. Recovery files were not modified.`,
    );
}
const sha = (o: unknown) => typeof o === "string" && /^[0-9a-f]{40}$/.test(o);
const text = (o: unknown) => typeof o === "string";
const target = (o: Json) => {
  record(o);
  requireState(
    text(o.key) && /^(pr-\d+|branch-[A-Za-z0-9._-]+)$/.test(o.key),
    "target.key",
  );
  requireState(text(o.label) && text(o.head) && text(o.base), "target");
  requireState(
    o.prNumber == null || Number.isInteger(o.prNumber),
    "target.prNumber",
  );
  return pick<ReviewTarget>(o, ["key", "label", "prNumber", "head", "base"]);
};

export function parseActive(o: Json): ActiveReview {
  record(o);
  requireState(sha(o.head), "head");
  requireState(
    text(o.reviewBranch) && o.reviewBranch.startsWith("review/"),
    "reviewBranch",
  );
  requireState(
    text(o.originalRef) &&
      o.originalRef.length > 0 &&
      !o.originalRef.startsWith("-"),
    "originalRef",
  );
  requireState(o.stashMessage == null || text(o.stashMessage), "stashMessage");
  return {
    ...pick<ActiveReview>(o, [
      "head",
      "reviewBranch",
      "originalRef",
      "stashMessage",
    ]),
    target: target(o.target),
  };
}

export function parseMeta(o: Json): ReviewMeta {
  record(o);
  record(o.range);
  record(o.reviewed);
  requireState(
    text(o.range.from) &&
      /^[0-9a-f]{40}\^?$/.test(o.range.from) &&
      sha(o.range.to),
    "range",
  );
  requireState(text(o.scopeLabel), "scopeLabel");
  requireState(
    Object.values(o.reviewed).every((v) => sha(v) || v === "dismissed"),
    "reviewed",
  );
  requireState(o.seenHead == null || text(o.seenHead), "seenHead");
  if (o.unsent != null) {
    record(o.unsent);
    requireState(
      Object.values(o.unsent).every((v) => typeof v === "boolean"),
      "unsent",
    );
  }
  requireState(
    o.verdict == null ||
      ["COMMENT", "APPROVE", "REQUEST_CHANGES"].includes(o.verdict),
    "verdict",
  );
  if (o.drafts != null) {
    requireState(Array.isArray(o.drafts), "drafts");
    for (const draft of o.drafts) {
      record(draft);
      requireState(
        text(draft.path) &&
          Number.isInteger(draft.line) &&
          draft.line >= 1 &&
          sha(draft.blob) &&
          text(draft.body) &&
          (draft.posted === undefined || typeof draft.posted === "boolean"),
        "draft",
      );
    }
  }
  const meta = pick<ReviewMeta>(o, [
    "scopeLabel",
    "reviewed",
    "seenHead",
    "unsent",
    "verdict",
  ]);
  meta.target = target(o.target);
  meta.range = pick(o.range, ["from", "to"]);
  if ("drafts" in o)
    meta.drafts =
      o.drafts &&
      o.drafts.map((d: Json) =>
        pick<Draft>(d, ["path", "line", "blob", "body", "posted"]),
      );
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
export function reviewMeta(
  gitDir: string,
  key: string,
): ReviewMeta | undefined {
  const o = read(join(gitDir, "review-lens", `${key}.json`));
  return o && parseMeta(o);
}
