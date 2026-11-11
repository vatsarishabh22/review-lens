import { run, CliError } from "./cli";
import { PullRequest, ViewedState, ViewedStates } from "../core/model";
import {
  ReviewThread,
  PostedComment,
  ReviewSlot,
  ReviewSubmission,
  slotBlocker,
} from "../core/threads";
const viewedQuery = `query($owner:String!,$name:String!,$number:Int!,$endCursor:String){viewer{login} repository(owner:$owner,name:$name){pullRequest(number:$number){id author{login} files(first:100,after:$endCursor){pageInfo{hasNextPage endCursor} nodes{path viewerViewedState}}}}}`;
export function parseViewedStates(text: string): ViewedStates {
  const files = new Map<string, ViewedState>();
  let id = "",
    own = false;
  for (const row of text.split(/\r?\n/)) {
    const tab = row.indexOf("\t");
    if (tab < 0) continue;
    const key = row.slice(0, tab),
      value = row.slice(tab + 1);
    if (key === "#id") id = value;
    else if (key === "#own") own = value === "true";
    else if (["VIEWED", "UNVIEWED", "DISMISSED"].includes(value))
      files.set(key, value as ViewedState);
  }
  if (!id) throw new Error("GitHub did not return this pull request.");
  return { id, own, files: Object.fromEntries(files) };
}
export function githubRefusal(body: string): string | undefined {
  try {
    const o = JSON.parse(body);
    return (
      o.errors
        ?.map((e: string | { message?: string }) =>
          typeof e === "string" ? e : e.message,
        )
        .filter(Boolean)
        .join("; ") || o.message
    );
  } catch {
    return undefined;
  }
}
export function summarizeChecks(json: string): string {
  const outcomes = (JSON.parse(json).statusCheckRollup ?? []).map((c: any) =>
    c.status && c.status !== "COMPLETED" ? "PENDING" : c.conclusion || c.state,
  );
  const failed = outcomes.filter((s: string) =>
    [
      "FAILURE",
      "ERROR",
      "TIMED_OUT",
      "CANCELLED",
      "ACTION_REQUIRED",
      "STARTUP_FAILURE",
    ].includes(s),
  ).length;
  return failed
    ? `${failed} check${failed === 1 ? "" : "s"} failing`
    : outcomes.some((s: string) =>
          [
            "PENDING",
            "EXPECTED",
            "QUEUED",
            "IN_PROGRESS",
            "WAITING",
            "REQUESTED",
          ].includes(s),
        )
      ? "Checks running"
      : outcomes.length
        ? "Checks passed"
        : "";
}
export class GhClient {
  constructor(
    readonly root: string,
    private readonly execute = run,
  ) {}
  private async gh(...args: string[]) {
    return this.execute(this.root, "gh", args);
  }
  async ready() {
    try {
      await this.gh("auth", "status");
      return true;
    } catch {
      return false;
    }
  }
  async viewer() {
    return (await this.gh("api", "user", "--jq", ".login")).trim();
  }
  async list(): Promise<PullRequest[]> {
    return JSON.parse(
      await this.gh(
        "pr",
        "list",
        "--state",
        "open",
        "--limit",
        "100",
        "--json",
        "number,title,headRefName,headRefOid,baseRefName,author,body,additions,deletions,changedFiles,updatedAt,reviewRequests",
      ),
    );
  }
  async checks(number: number) {
    return summarizeChecks(
      await this.gh(
        "pr",
        "view",
        String(number),
        "--json",
        "statusCheckRollup",
      ),
    );
  }
  async viewed(number: number): Promise<ViewedStates> {
    const jq =
      '.data as $d | $d.repository.pullRequest as $p | ("#id\\t" + $p.id), ("#own\\t" + (($p.author.login // "") == $d.viewer.login | tostring)), ($p.files.nodes[] | "\\(.path)\\t\\(.viewerViewedState)")';
    return parseViewedStates(
      await this.gh(
        "api",
        "graphql",
        "--paginate",
        "-F",
        "owner={owner}",
        "-F",
        "name={repo}",
        "-F",
        `number=${number}`,
        "-f",
        `query=${viewedQuery}`,
        "--jq",
        jq,
      ),
    );
  }
  async setViewed(id: string, file: string, viewed: boolean) {
    const mutation = viewed ? "markFileAsViewed" : "unmarkFileAsViewed";
    await this.gh(
      "api",
      "graphql",
      "-f",
      `id=${id}`,
      "-f",
      `path=${file}`,
      "-f",
      `query=mutation($id:ID!,$path:String!){${mutation}(input:{pullRequestId:$id,path:$path}){clientMutationId}}`,
    );
  }
  private async graphql(
    query: string,
    variables: Record<string, unknown>,
  ): Promise<any> {
    try {
      const response = JSON.parse(
        await this.execute(
          this.root,
          "gh",
          ["api", "graphql", "--input", "-"],
          JSON.stringify({ query, variables }),
        ),
      );
      if (response.errors?.length)
        throw new Error(response.errors.map((e: any) => e.message).join("; "));
      if (!response.data)
        throw new Error("GitHub did not return a GraphQL result.");
      return response.data;
    } catch (error) {
      if (error instanceof CliError)
        throw new Error(githubRefusal(error.stdout) || error.message);
      throw error;
    }
  }
  private async prQuery(
    number: number,
    fields: string,
    cursor?: string,
  ): Promise<any> {
    const query = `query($owner:String!,$name:String!,$number:Int!,$cursor:String){viewer{login} repository(owner:$owner,name:$name){pullRequest(number:$number){${fields}}}}`;
    const response = JSON.parse(
      await this.gh(
        "api",
        "graphql",
        "-F",
        "owner={owner}",
        "-F",
        "name={repo}",
        "-F",
        `number=${number}`,
        "-f",
        `query=${query}`,
        ...(cursor ? ["-f", `cursor=${cursor}`] : []),
      ),
    );
    if (response.errors?.length)
      throw new Error(response.errors.map((e: any) => e.message).join("; "));
    if (!response.data?.repository?.pullRequest)
      throw new Error("GitHub did not return this pull request.");
    return response.data;
  }
  async reviewSlot(number: number): Promise<ReviewSlot> {
    let cursor: string | undefined;
    do {
      const data = await this.prQuery(
        number,
        "id headRefOid viewerDidAuthor reviews(states:PENDING,first:100,after:$cursor){pageInfo{hasNextPage endCursor} nodes{id viewerDidAuthor comments{totalCount}}}",
        cursor,
      );
      const pr = data.repository.pullRequest;
      const pending = pr.reviews.nodes.find((r: any) => r.viewerDidAuthor);
      if (pending || !pr.reviews.pageInfo?.hasNextPage)
        return {
          id: pr.id,
          head: pr.headRefOid,
          own: pr.viewerDidAuthor,
          ...(pending
            ? {
                pending: {
                  id: pending.id,
                  comments: pending.comments.totalCount,
                },
              }
            : {}),
        };
      cursor = pr.reviews.pageInfo.endCursor;
    } while (cursor);
    throw new Error("GitHub returned an invalid pending-review cursor.");
  }
  async reviewThreads(number: number): Promise<ReviewThread[]> {
    const threads: ReviewThread[] = [];
    let cursor: string | undefined;
    const commentFields =
      "id body url author{login} commit{oid} viewerCanUpdate viewerCanDelete";
    do {
      const data = await this.prQuery(
        number,
        `reviewThreads(first:100,after:$cursor){pageInfo{hasNextPage endCursor} nodes{id path line startLine originalLine diffSide isResolved resolvedBy{login} viewerCanResolve viewerCanUnresolve comments(first:100){pageInfo{hasNextPage endCursor} nodes{${commentFields}}}}}`,
        cursor,
      );
      const connection = data.repository.pullRequest.reviewThreads;
      for (const raw of connection.nodes) {
        const nodes = [...raw.comments.nodes];
        let commentsCursor = raw.comments.pageInfo.hasNextPage
          ? raw.comments.pageInfo.endCursor
          : undefined;
        while (commentsCursor) {
          const result = await this.graphql(
            `query($id:ID!,$cursor:String){node(id:$id){... on PullRequestReviewThread{comments(first:100,after:$cursor){pageInfo{hasNextPage endCursor} nodes{${commentFields}}}}}}`,
            { id: raw.id, cursor: commentsCursor },
          );
          const page = result.node.comments;
          nodes.push(...page.nodes);
          commentsCursor = page.pageInfo.hasNextPage
            ? page.pageInfo.endCursor
            : undefined;
        }
        const comments: PostedComment[] = nodes.map((c) => ({
          id: c.id,
          author: c.author?.login ?? "ghost",
          body: c.body,
          url: c.url,
          commit: c.commit?.oid,
          mine: c.author?.login === data.viewer.login,
          canEdit: c.viewerCanUpdate,
          canDelete: c.viewerCanDelete,
        }));
        threads.push({ ...raw, resolvedBy: raw.resolvedBy?.login, comments });
      }
      cursor = connection.pageInfo.hasNextPage
        ? connection.pageInfo.endCursor
        : undefined;
    } while (cursor);
    return threads;
  }
  async resolveThread(id: string, resolved: boolean) {
    const field = resolved ? "resolveReviewThread" : "unresolveReviewThread";
    await this.graphql(
      `mutation($id:ID!){${field}(input:{threadId:$id}){thread{id}}}`,
      { id },
    );
  }
  async editComment(id: string, body: string) {
    await this.graphql(
      "mutation($id:ID!,$body:String!){updatePullRequestReviewComment(input:{pullRequestReviewCommentId:$id,body:$body}){pullRequestReviewComment{id}}}",
      { id, body },
    );
  }
  async deleteComment(id: string) {
    await this.graphql(
      "mutation($id:ID!){deletePullRequestReviewComment(input:{id:$id}){pullRequestReview{id}}}",
      { id },
    );
  }
  async submit(number: number, submission: ReviewSubmission) {
    const slot = await this.reviewSlot(number),
      blocker = slotBlocker(slot, submission);
    if (blocker) throw new Error(blocker);
    const thread = (d: ReviewSubmission["comments"][number]) => ({
      path: d.path,
      line: d.line,
      side: "RIGHT",
      body: d.body,
      ...(d.startLine != null
        ? { startLine: d.startLine, startSide: "RIGHT" }
        : {}),
    });
    let review = slot.pending?.id;
    if (!review)
      review = (
        await this.graphql(
          "mutation($pr:ID!,$commit:GitObjectID!,$threads:[DraftPullRequestReviewThread]){addPullRequestReview(input:{pullRequestId:$pr,commitOID:$commit,threads:$threads}){pullRequestReview{id}}}",
          {
            pr: slot.id,
            commit: submission.commit,
            threads: submission.comments.map(thread),
          },
        )
      ).addPullRequestReview.pullRequestReview.id;
    if (!review)
      throw new Error("GitHub did not return the pending review id.");
    const added: string[] = [];
    try {
      if (slot.pending)
        for (const comment of submission.comments) {
          const result = await this.graphql(
            "mutation($input:AddPullRequestReviewThreadInput!){addPullRequestReviewThread(input:$input){thread{comments(first:1){nodes{id}}}}}",
            { input: { pullRequestReviewId: review, ...thread(comment) } },
          );
          added.push(
            result.addPullRequestReviewThread.thread.comments.nodes[0].id,
          );
        }
      for (const reply of submission.replies) {
        const result = await this.graphql(
          "mutation($review:ID!,$thread:ID!,$body:String!){addPullRequestReviewThreadReply(input:{pullRequestReviewId:$review,pullRequestReviewThreadId:$thread,body:$body}){comment{id}}}",
          { review, thread: reply.replyTo, body: reply.body },
        );
        added.push(result.addPullRequestReviewThreadReply.comment.id);
      }
      await this.graphql(
        "mutation($review:ID!,$event:PullRequestReviewEvent!,$body:String){submitPullRequestReview(input:{pullRequestReviewId:$review,event:$event,body:$body}){pullRequestReview{id}}}",
        {
          review,
          event: submission.verdict,
          body: submission.summary.trim() || null,
        },
      );
    } catch (error) {
      const failures: string[] = [];
      if (slot.pending)
        for (const id of added.reverse()) {
          try {
            await this.deleteComment(id);
          } catch {
            failures.push(id);
          }
        }
      else
        try {
          await this.graphql(
            "mutation($review:ID!){deletePullRequestReview(input:{pullRequestReviewId:$review}){pullRequestReview{id}}}",
            { review },
          );
        } catch {
          failures.push(review);
        }
      throw new Error(
        `${error instanceof Error ? error.message : String(error)}${failures.length ? ` Cleanup failed for ${failures.join(", ")}; inspect your pending review on GitHub before retrying.` : ""}`,
      );
    }
  }
}
