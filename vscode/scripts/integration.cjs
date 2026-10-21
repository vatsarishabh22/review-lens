const { downloadAndUnzipVSCode } = require("@vscode/test-electron");
const { spawn } = require("node:child_process");
const { mkdir, writeFile } = require("node:fs/promises");
const path = require("node:path");
const { repository } = require("../out/test/helpers");
const net = require("node:net");
async function main() {
  const extension = path.resolve(__dirname, ".."),
    artifacts = path.join(extension, ".artifacts");
  await mkdir(artifacts, { recursive: true });
  const temporary = path.join(extension, ".test-data", "editor-temp");
  await mkdir(temporary, { recursive: true });
  const repo = await repository();
  await repo.git("remote", "add", "origin", repo.root);
  await repo.git("update-ref", "refs/pull/7/head", repo.head);
  await repo.git("update-ref", "refs/remotes/origin/main", repo.base);
  await writeFile(path.join(repo.root, "app.txt"), "my staged work\n");
  await repo.git("add", "app.txt");
  await writeFile(path.join(repo.root, "notes.txt"), "my untracked notes\n");
  const executable =
    process.env.VSCODE_EXECUTABLE ||
    (await downloadAndUnzipVSCode({
      version: process.env.VSCODE_TEST_VERSION || "1.95.3",
      cachePath: path.join(extension, ".vscode-test"),
    }));
  const port = await new Promise((resolve) => {
    const server = net.createServer();
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
  const args = [
    repo.root,
    "--new-window",
    "--no-sandbox",
    "--disable-gpu",
    "--disable-updates",
    "--skip-welcome",
    "--skip-release-notes",
    "--disable-workspace-trust",
    "--disable-extensions",
    "--remote-debugging-port=" + port,
    "--user-data-dir=" +
      path.join(extension, ".vscode-test", path.basename(repo.root)),
    "--extensions-dir=" + path.join(extension, ".vscode-test", "extensions"),
    "--extensionDevelopmentPath=" + extension,
    "--extensionTestsPath=" + path.join(extension, "out/test/integration.js"),
  ];
  const env = {
    ...process.env,
    TEMP: temporary,
    TMP: temporary,
    TMPDIR: temporary,
    REVIEW_LENS_TEST_ROOT: repo.root,
    REVIEW_LENS_TEST_BASE: repo.base,
    REVIEW_LENS_TEST_HEAD: repo.head,
    REVIEW_LENS_TEST_ARTIFACTS: artifacts,
    REVIEW_LENS_CAPTURE_PORT: String(port),
  };
  delete env.ELECTRON_RUN_AS_NODE;
  const child = spawn(executable, args, {
    cwd: extension,
    env,
    shell: false,
    windowsHide: true,
    stdio: "pipe",
  });
  let logs = "";
  child.stdout.on("data", (d) => {
    logs += d;
    process.stdout.write(d);
  });
  child.stderr.on("data", (d) => {
    logs += d;
    process.stderr.write(d);
  });
  const timer = setTimeout(() => child.kill(), 150000);
  try {
    const code = await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("exit", resolve);
    });
    await writeFile(path.join(artifacts, "integration.log"), logs);
    if (code !== 0)
      throw new Error("VS Code integration tests failed: " + code);
  } finally {
    clearTimeout(timer);
    await repo.clean();
  }
}
main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
