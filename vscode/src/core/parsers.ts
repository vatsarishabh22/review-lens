import { FileChange } from "./model";
export function parseChanges(raw: string, numstat: string): FileChange[] {
  const counts = new Map<string, [number | null, number | null]>();
  const tokens = numstat.split("\0");
  for (let i = 0; i < tokens.length;) {
    const match = /^(\d+|-)\t(\d+|-)\t([\s\S]*)$/.exec(tokens[i++]);
    if (!match) continue;
    let path = match[3];
    if (!path) {
      i++;
      path = tokens[i++];
    }
    counts.set(path, [
      match[1] === "-" ? null : Number(match[1]),
      match[2] === "-" ? null : Number(match[2]),
    ]);
  }
  const entries = raw.split("\0"),
    changes: FileChange[] = [];
  for (let i = 0; i < entries.length;) {
    const header = entries[i++];
    if (!header) continue;
    const fields = header.split(" ");
    if (fields.length !== 5) throw new Error("Invalid git diff output.");
    const status = fields[4][0],
      renamed = status === "R" || status === "C";
    const oldPath = renamed ? entries[i++] : undefined,
      path = entries[i++];
    if (path === undefined) throw new Error("Missing git diff path.");
    const count = counts.get(path) ?? [null, null];
    changes.push({
      path,
      ...(oldPath === undefined ? {} : { oldPath }),
      status,
      newBlob: fields[3],
      added: count[0],
      deleted: count[1],
    });
  }
  return changes.sort((a, b) => a.path.localeCompare(b.path));
}
export interface Hunk {
  start: number;
  count: number;
  oldStart: number;
  oldCount: number;
  kind: "added" | "modified" | "deleted";
}
export function parseHunks(diff: string): Hunk[] {
  return [
    ...diff.matchAll(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/gm),
  ].map((m) => {
    const oldCount = m[2] === undefined ? 1 : Number(m[2]),
      count = m[4] === undefined ? 1 : Number(m[4]);
    return {
      oldStart: Number(m[1]),
      oldCount,
      start: Number(m[3]),
      count,
      kind: count === 0 ? "deleted" : oldCount === 0 ? "added" : "modified",
    };
  });
}
export interface BlameCommit {
  sha: string;
  author: string;
  time: number;
  summary: string;
}
export function parseBlame(text: string): Map<number, BlameCommit> {
  const result = new Map<number, BlameCommit>();
  let commit: BlameCommit | undefined,
    line = 0;
  for (const row of text.split("\n")) {
    const match = /^([0-9a-f]{40}) \d+ (\d+)(?: \d+)?$/.exec(row);
    if (match) {
      line = Number(match[2]);
      commit = { sha: match[1], author: "", time: 0, summary: "" };
    } else if (commit && row.startsWith("author "))
      commit.author = row.slice(7);
    else if (commit && row.startsWith("author-time "))
      commit.time = Number(row.slice(12));
    else if (commit && row.startsWith("summary "))
      commit.summary = row.slice(8);
    else if (commit && row.startsWith("\t")) result.set(line, commit);
  }
  return result;
}
