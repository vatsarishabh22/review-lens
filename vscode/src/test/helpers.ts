import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import * as path from "node:path";
import { run } from "../infrastructure/cli";
import { GitClient } from "../infrastructure/git";
export const testRoot = path.resolve(__dirname, "../../.test-data");
export async function repository() {
  await mkdir(testRoot, { recursive: true });
  const root = await mkdtemp(path.join(testRoot, "repo-"));
  const git = (...args: string[]) =>
    run(root, "git", [
      "-c",
      "user.name=Test",
      "-c",
      "user.email=test@example.test",
      "-c",
      "core.autocrlf=false",
      ...args,
    ]);
  await git("init", "-q", "-b", "main");
  await git("config", "core.autocrlf", "false");
  await writeFile(path.join(root, "app.txt"), "base\n");
  await git("add", ".");
  await git("commit", "-qm", "base");
  const base = (await git("rev-parse", "HEAD")).trim();
  await git("checkout", "-qb", "feature");
  await writeFile(path.join(root, "app.txt"), "feature\n");
  await writeFile(path.join(root, "added.txt"), "new\n");
  await git("add", ".");
  await git("commit", "-qm", "feature");
  const head = (await git("rev-parse", "HEAD")).trim();
  await git("checkout", "-q", "main");
  return {
    root,
    git,
    base,
    head,
    client: await GitClient.discover(root),
    clean: () => rm(root, { recursive: true, force: true }),
  };
}
