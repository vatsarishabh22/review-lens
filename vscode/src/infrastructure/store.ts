import { promises as fs } from "node:fs";
import * as path from "node:path";
import { randomUUID } from "node:crypto";
import { ActiveReview, ReviewMeta, parseActive, parseMeta } from "../state";
export class StateStore {
  readonly directory: string;
  constructor(gitDir: string) {
    this.directory = path.join(gitDir, "review-lens");
  }
  private file(key: string) {
    if (key !== "active" && !/^(pr-\d+|branch-[A-Za-z0-9._-]+)$/.test(key))
      throw new Error("Invalid review state key.");
    return path.join(this.directory, key + ".json");
  }
  private async read(key: string): Promise<any | undefined> {
    try {
      return JSON.parse(await fs.readFile(this.file(key), "utf8"));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
      throw new Error(
        `Cannot read ${key}.json. Recovery state has been kept: ${String(error)}`,
      );
    }
  }
  private async write(key: string, value: unknown) {
    await fs.mkdir(this.directory, { recursive: true });
    const destination = this.file(key),
      temp = destination + "." + randomUUID() + ".tmp";
    try {
      await fs.writeFile(temp, JSON.stringify(value, null, 2) + "\n", {
        flag: "wx",
      });
      await fs.rename(temp, destination);
    } finally {
      await fs.rm(temp, { force: true });
    }
  }
  async active(): Promise<ActiveReview | undefined> {
    const o = await this.read("active");
    return o && parseActive(o);
  }
  async meta(key: string): Promise<ReviewMeta | undefined> {
    const o = await this.read(key);
    return o && parseMeta(o);
  }
  async saveActive(active: ActiveReview) {
    await this.write("active", active);
  }
  async saveMeta(meta: ReviewMeta) {
    await this.write(meta.target.key, meta);
  }
  async clearActive() {
    await fs.rm(this.file("active"), { force: true });
  }
}
