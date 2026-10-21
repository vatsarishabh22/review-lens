import { ActiveReview, ReviewTarget } from "../core/model";
import { GitClient } from "../infrastructure/git";
import { StateStore } from "../infrastructure/store";
export type Restored = "nothing" | "restored" | "conflict" | "missing";
/** A failure must never discard the only record of where the reviewer's work lives. */
export class ReviewLifecycle {
  constructor(
    readonly git: GitClient,
    readonly store: StateStore,
  ) {}
  async start(
    target: ReviewTarget,
    head: string,
    progress: (step: string) => void = () => {},
  ): Promise<ActiveReview> {
    if (await this.store.active())
      throw new Error("A review is already active. Exit it first.");
    const originalRef = await this.git.currentRef();
    if (originalRef === `review/${target.key}`)
      throw new Error(
        "You are already on this review branch. Switch to your own branch before starting a new review.",
      );
    const dirty = await this.git.dirtyFiles();
    const stashMessage = dirty.length
      ? `review-lens: parked from ${originalRef} for ${target.label}`
      : null;
    const active: ActiveReview = {
      target,
      head,
      reviewBranch: `review/${target.key}`,
      originalRef,
      stashMessage,
    };
    await this.store.saveActive(active);
    try {
      if (stashMessage) {
        progress("Parking your changes");
        await this.git.stash(stashMessage);
      }
      progress("Checking out the review scope");
      await this.git.checkoutAt(active.reviewBranch, head);
      return active;
    } catch (error) {
      try {
        if ((await this.git.currentRef()) !== originalRef)
          await this.git.checkout(originalRef);
        const restored = stashMessage
          ? await this.git.restoreStash(stashMessage)
          : "restored";
        if (restored === "conflict")
          throw new Error(
            "Parked work needs conflict resolution; its stash and recovery state are kept.",
          );
        await this.store.clearActive();
      } catch (recoveryError) {
        throw new Error(
          `${String(error)} Recovery needs attention: ${String(recoveryError)}`,
        );
      }
      throw error;
    }
  }
  async assertCheckout(active: ActiveReview) {
    if (
      (await this.git.currentRef()) !== active.reviewBranch ||
      (await this.git.revParse("HEAD")) !== active.head
    )
      throw new Error(
        "The checkout changed outside Review Lens. Exit/recover this review before continuing.",
      );
  }
  async moveTo(head: string): Promise<ActiveReview> {
    const active = await this.store.active();
    if (!active) throw new Error("No active review.");
    await this.assertCheckout(active);
    if ((await this.git.dirtyFiles()).length)
      throw new Error(
        "The review checkout has edits. Keep them safe before changing scope.",
      );
    await this.git.checkoutAt(active.reviewBranch, head);
    try {
      const next = { ...active, head };
      await this.store.saveActive(next);
      return next;
    } catch (error) {
      await this.git.checkoutAt(active.reviewBranch, active.head);
      throw error;
    }
  }
  async exit(): Promise<Restored> {
    const active = await this.store.active();
    if (!active) return "nothing";
    const current = await this.git.currentRef();
    if (current !== active.reviewBranch && current !== active.originalRef)
      throw new Error(
        `The checkout is now ${current}. Return to ${active.reviewBranch} or ${active.originalRef} before restoring parked work.`,
      );
    if (current === active.reviewBranch && (await this.git.dirtyFiles()).length)
      throw new Error(
        "The review checkout has edits. Save them in a separate stash before exiting; your original parked work is untouched.",
      );
    await this.git.checkout(active.originalRef);
    const result = active.stashMessage
      ? await this.git.restoreStash(active.stashMessage)
      : "nothing";
    await this.store.clearActive();
    return result;
  }
}
