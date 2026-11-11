import { spawn } from "node:child_process";
export class CliError extends Error {
  constructor(
    public readonly program: string,
    public readonly stdout: string,
    public readonly stderr: string,
    public readonly code: number | null,
  ) {
    super(
      stderr
        .trim()
        .split(/\r?\n/)[0]
        ?.replace(/^(fatal|error): /, "") || `${program} failed (${code})`,
    );
  }
}
/** Never invoke a shell: refs, filenames and review text remain arguments/data. */
export function run(
  root: string,
  program: string,
  args: string[],
  input?: string,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, {
      cwd: root,
      shell: false,
      windowsHide: true,
      stdio: ["pipe", "pipe", "pipe"],
      env: {
        ...process.env,
        GIT_TERMINAL_PROMPT: "0",
        GH_PROMPT_DISABLED: "1",
        GIT_PAGER: "cat",
      },
    });
    const stdout: Buffer[] = [],
      stderr: Buffer[] = [];
    let bytes = 0,
      exceeded = false;
    // Local Git transactions must finish: interrupting stash/checkout can leave child Git processes mutating the tree.
    const canInterrupt =
      program !== "git" || !["stash", "checkout"].includes(args[0]);
    const timer = canInterrupt
      ? setTimeout(() => {
          child.kill();
        }, 120_000)
      : undefined;
    const collect = (parts: Buffer[]) => (data: Buffer) => {
      bytes += data.length;
      if (bytes > 64 * 1024 * 1024) {
        exceeded = true;
        if (canInterrupt) child.kill();
      } else parts.push(data);
    };
    child.stdout.on("data", collect(stdout));
    child.stderr.on("data", collect(stderr));
    child.once("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
    child.once("close", (code) => {
      clearTimeout(timer);
      const out = Buffer.concat(stdout).toString("utf8"),
        err = Buffer.concat(stderr).toString("utf8");
      if (exceeded)
        reject(
          new Error(
            `${program} output exceeded 64 MB. Narrow the review scope.`,
          ),
        );
      else if (code !== 0) reject(new CliError(program, out, err, code));
      else resolve(out);
    });
    child.stdin.on("error", () => {
      /* A rejected command may close stdin before consuming its body. */
    });
    child.stdin.end(input);
  });
}
