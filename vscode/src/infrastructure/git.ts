import * as path from "node:path";
import { run } from "./cli";
import { Commit, FileChange, ScopeRange } from "../core/model";
import { parseBlame, parseChanges, parseHunks } from "../core/parsers";
const exclusions = [":(exclude,glob).ide[a]/**", ":(exclude,glob).vscod[e]/**"];
export class GitClient {
  constructor(
    readonly root: string,
    readonly gitDir: string,
  ) {}
  static async discover(folder: string): Promise<GitClient> {
    const root = path.resolve(
      folder,
      (await run(folder, "git", ["rev-parse", "--show-cdup"])).trim(),
    );
    return new GitClient(
      root,
      path.resolve(
        root,
        (await run(root, "git", ["rev-parse", "--git-dir"])).trim(),
      ),
    );
  }
  async git(...args: string[]) {
    return run(this.root, "git", args);
  }
  async revParse(ref: string) {
    return (await this.git("rev-parse", "--verify", ref)).trim();
  }
  async currentRef() {
    try {
      return (await this.git("symbolic-ref", "--short", "-q", "HEAD")).trim();
    } catch {
      return this.revParse("HEAD");
    }
  }
  async dirtyFiles() {
    return (await this.git("status", "--porcelain", "--", ".", ...exclusions))
      .split(/\r?\n/)
      .filter(Boolean);
  }
  async stash(message: string) {
    await this.git(
      "stash",
      "push",
      "--include-untracked",
      "-m",
      message,
      "--",
      ".",
      ...exclusions,
    );
  }
  async restoreStash(
    message: string,
  ): Promise<"restored" | "conflict" | "missing"> {
    const stash = (await this.git("stash", "list", "--format=%gd%x09%gs"))
      .split("\n")
      .find((s) => s.trimEnd().endsWith(": " + message))
      ?.split("\t")[0];
    if (!stash) return "missing";
    try {
      await this.git("stash", "pop", "--index", stash);
      return "restored";
    } catch {
      // Never pop again onto a partially applied conflict from the first attempt.
      if ((await this.git("ls-files", "-u")).trim()) return "conflict";
      try {
        await this.git("stash", "pop", stash);
        return "restored";
      } catch {
        return "conflict";
      }
    }
  }
  async checkout(ref: string) {
    await this.git("checkout", ref);
  }
  async checkoutAt(branch: string, sha: string) {
    await this.git("checkout", "-B", branch, sha);
  }
  async defaultBase() {
    try {
      return (
        await this.git("symbolic-ref", "--short", "refs/remotes/origin/HEAD")
      ).trim();
    } catch {
      return "origin/main";
    }
  }
  async branches() {
    return (await this.git("branch", "-r", "--format=%(refname:lstrip=2)"))
      .split(/\r?\n/)
      .filter((b) => b && !b.endsWith("/HEAD"));
  }
  async fetchPr(number: number, base: string) {
    const ref = `refs/review-lens/pr-${number}`;
    await this.git(
      "fetch",
      "--no-tags",
      "origin",
      `+pull/${number}/head:${ref}`,
      `+refs/heads/${base.replace(/^origin\//, "")}:refs/remotes/origin/${base.replace(/^origin\//, "")}`,
    );
    return ref;
  }
  async commits(from: string, to: string): Promise<Commit[]> {
    return (
      await this.git("log", "--format=%H%x09%an%x09%ct%x09%s", `${from}..${to}`)
    )
      .split(/\r?\n/)
      .filter(Boolean)
      .map((s) => {
        const [sha, author, time, ...subject] = s.split("\t");
        return { sha, author, time: Number(time), subject: subject.join("\t") };
      });
  }
  async changes(range: ScopeRange): Promise<FileChange[]> {
    const [raw, stat] = await Promise.all([
      this.git(
        "diff",
        "--raw",
        "-z",
        "--no-abbrev",
        "-M",
        range.from,
        range.to,
      ),
      this.git("diff", "--numstat", "-z", "-M", range.from, range.to),
    ]);
    return parseChanges(raw, stat);
  }
  async show(rev: string, file: string) {
    return this.git("show", `${rev}:${file}`);
  }
  async trackedFiles() {
    return (await this.git("ls-files", "-z")).split("\0").filter(Boolean);
  }
  async hunks(range: ScopeRange, change: FileChange) {
    return parseHunks(
      await this.git(
        "diff",
        "--no-ext-diff",
        "--no-color",
        "--unified=0",
        "-M",
        range.from,
        range.to,
        "--",
        ...(change.oldPath ? [change.oldPath] : []),
        change.path,
      ),
    );
  }
  async blame(range: ScopeRange, file: string) {
    return parseBlame(
      await this.git("blame", "--line-porcelain", range.to, "--", file),
    );
  }
  relative(file: string): string | undefined {
    const relative = path.relative(this.root, file);
    return relative &&
      relative !== ".." &&
      !relative.startsWith(".." + path.sep) &&
      !path.isAbsolute(relative)
      ? relative.split(path.sep).join("/")
      : undefined;
  }
  file(relative: string): string {
    const resolved = path.resolve(this.root, relative);
    if (!this.relative(resolved) || relative.includes("\0"))
      throw new Error("File is outside the repository.");
    return resolved;
  }
}
