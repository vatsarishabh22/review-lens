import { run, CliError } from "./cli";
import { PullRequest, ViewedState, ViewedStates } from "../core/model";
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
        : "No checks";
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
        "number,title,headRefName,baseRefName,author,body,additions,deletions,changedFiles,updatedAt,reviewRequests",
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
  async submit(number: number, payload: unknown) {
    try {
      await this.execute(
        this.root,
        "gh",
        [
          "api",
          `repos/{owner}/{repo}/pulls/${number}/reviews`,
          "--method",
          "POST",
          "--input",
          "-",
        ],
        JSON.stringify(payload),
      );
    } catch (error) {
      if (error instanceof CliError)
        throw new Error(githubRefusal(error.stdout) || error.message);
      throw error;
    }
  }
}
