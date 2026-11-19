import { Draft, Verdict, submitBlocker } from "./model";
export interface PostedComment {
  id: string;
  author: string;
  body: string;
  url: string;
  commit?: string;
  mine: boolean;
  canEdit: boolean;
  canDelete: boolean;
}
export interface ReviewThread {
  id: string;
  path: string;
  line: number | null;
  startLine?: number | null;
  originalLine?: number | null;
  diffSide: string;
  isResolved: boolean;
  resolvedBy?: string;
  viewerCanResolve: boolean;
  viewerCanUnresolve: boolean;
  comments: PostedComment[];
  originalText?: string;
}
export interface ReviewSlot {
  id: string;
  head: string;
  own: boolean;
  pending?: { id: string; comments: number };
}
export interface ReviewSubmission {
  commit: string;
  summary: string;
  verdict: Verdict;
  comments: Draft[];
  replies: Draft[];
}
export function slotBlocker(
  slot: ReviewSlot,
  submission: ReviewSubmission,
): string | undefined {
  if (
    slot.pending &&
    submission.comments.length &&
    slot.head !== submission.commit
  )
    return "Your pending GitHub review targets the latest push. Review up to that push before adding comments, or submit that review on GitHub first.";
  return submitBlocker(
    submission.verdict,
    submission.summary,
    submission.comments.length +
      submission.replies.length +
      (slot.pending?.comments ?? 0),
    slot.own,
  );
}
export function anchored(thread: ReviewThread, commit: string): boolean {
  return (
    thread.line != null &&
    thread.diffSide === "RIGHT" &&
    thread.comments[0]?.commit === commit
  );
}
