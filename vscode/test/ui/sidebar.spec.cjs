const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");
const { sidebarHtml } = require("../../out/ui/html");
const root = path.resolve(__dirname, "../..");
const target = {
  target: {
    key: "pr-7",
    label: "#7 Retry invoice exports when the bucket drops the connection",
    prNumber: 7,
    head: "feat/export-retries",
    base: "origin/main",
  },
  title: "Retry invoice exports when the bucket drops the connection",
  requested: true,
  pr: {
    number: 7,
    title: "Retry invoice exports",
    author: { login: "asha", name: "Asha Rao" },
    body: "Exports fail when the connection drops mid-upload.\n\n## What changes\n- **Retry connection errors**\n- Add regression tests\n\n<script>alert(1)</script>",
    additions: 42,
    deletions: 2,
    changedFiles: 4,
  },
};
const loaded = {
  target: target.target,
  head: "b".repeat(40),
  mergeBase: "a".repeat(40),
  commits: [
    {
      sha: "b".repeat(40),
      subject: "Test the retry helper and refuse empty uploads",
      author: "Asha Rao",
      time: 1,
    },
    {
      sha: "c".repeat(40),
      subject: "Retry invoice exports when the bucket drops the connection",
      author: "Asha Rao",
      time: 1,
    },
  ],
};
async function state(page, value) {
  await page.evaluate(
    (state) => window.postMessage({ type: "state", state }, "*"),
    value,
  );
}
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.messages = [];
    window.acquireVsCodeApi = () => ({
      postMessage: (m) => window.messages.push(m),
      getState: () => ({}),
      setState: () => {},
    });
  });
  await page.route("http://review-lens.test/**", async (route) => {
    const name = new URL(route.request().url()).pathname;
    if (name === "/")
      return route.fulfill({
        contentType: "text/html",
        body: sidebarHtml(
          "http://review-lens.test/review.css",
          "http://review-lens.test/review.js",
          "http://review-lens.test",
          "testnonce",
        ),
      });
    return route.fulfill({
      contentType: name.endsWith(".css")
        ? "text/css"
        : "application/javascript",
      body: fs.readFileSync(path.join(root, "media", path.basename(name))),
    });
  });
  await page.goto("http://review-lens.test/");
});
test("list, detail, contiguous scope, parking and untrusted PR text", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await state(page, { targets: [target], branchesOnly: false });
  await expect(page.getByText("Waiting on you", { exact: true })).toBeVisible();
  await page.screenshot({
    path: ".artifacts/ui/list-light.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: /Retry invoice exports/ }).click();
  expect(await page.evaluate(() => window.messages.at(-1).action)).toBe(
    "select",
  );
  await state(page, {
    targets: [target],
    selected: target,
    loaded,
    checks: "Checks passed",
  });
  await expect(
    page.getByRole("button", { name: "Start review", exact: true }),
  ).toBeEnabled();
  await expect(page.locator(".description script")).toHaveCount(0);
  await page.screenshot({
    path: ".artifacts/ui/detail-light.png",
    fullPage: true,
  });
  await page.locator('[data-pick="1"]').check();
  await page.getByRole("button", { name: "Start review", exact: true }).click();
  expect(await page.evaluate(() => window.messages.at(-1).picked)).toEqual([1]);
  await state(page, {
    targets: [target],
    selected: target,
    loaded,
    park: { files: [" M app.txt", "?? NOTES.md"], branch: "feat/my-work" },
  });
  await expect(
    page.getByRole("button", { name: "Park and start review" }),
  ).toBeVisible();
  await page.screenshot({
    path: ".artifacts/ui/park-light.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
test("review, finish blockers, pending drafts, mark without posting, dark theme and narrow widths", async ({
  page,
}) => {
  const changes = [
    "invoices/export.py",
    "invoices/retry.py",
    "invoices/storage.py",
    "tests/test_retry.py",
  ].map((path, i) => ({
    path,
    status: i === 1 ? "A" : "M",
    newBlob: "blob",
    badge: i === 1 ? "viewed" : i === 2 ? "changed" : "todo",
    added: 12,
    deleted: 2,
  }));
  const draft = {
    path: changes[0].path,
    line: 22,
    blob: "blob",
    body: "Could we log the attempt number?",
    posted: false,
  };
  const review = {
    active: { target: target.target },
    meta: {
      target: target.target,
      scopeLabel: "All commits (2)",
      range: { from: loaded.mergeBase, to: loaded.head },
      drafts: [draft],
    },
    changes,
    tracked: changes.map((c) => c.path).concat("README.md"),
    viewed: 1,
    pending: 1,
    waiting: 0,
    own: false,
    sync: "Synced with GitHub",
    current: changes[0].path,
  };
  await state(page, review);
  await expect(page.getByText("1 of 4 files viewed")).toBeVisible();
  await page.screenshot({
    path: ".artifacts/ui/review-light.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: /Finish review/ }).click();
  await expect(
    page.getByRole("button", { name: "Submit to GitHub" }),
  ).toBeEnabled();
  await page.getByRole("radio", { name: /Mark as reviewed/ }).check();
  await expect(
    page.getByRole("textbox", { name: "Review summary" }),
  ).toBeDisabled();
  await expect(page.getByText(/draft comments won’t be posted/)).toBeVisible();
  await page.screenshot({
    path: ".artifacts/ui/finish-light.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Finish review", exact: true })
    .click();
  expect(await page.evaluate(() => window.messages.at(-1).verdict)).toBe(
    "MARK",
  );
  await state(page, { ...review, own: true, pending: 0 });
  await page.getByRole("radio", { name: /^Comment/ }).check();
  await expect(
    page.getByRole("button", { name: "Submit to GitHub" }),
  ).toBeDisabled();
  await expect(page.getByRole("radio", { name: /^Approve/ })).toBeDisabled();
  await page
    .getByRole("textbox", { name: "Review summary" })
    .fill("Thanks for the fix");
  await expect(
    page.getByRole("button", { name: "Submit to GitHub" }),
  ).toBeEnabled();
  await page.evaluate(() => {
    const colors = {
      "sideBar-background": "#18191b",
      foreground: "#dfe1e5",
      descriptionForeground: "#989da6",
      "widget-border": "#393b40",
      "input-background": "#232427",
      "input-foreground": "#dfe1e5",
      "button-background": "#3574f0",
      "button-secondaryBackground": "#34363b",
      "button-secondaryForeground": "#eee",
      "badge-background": "#393b40",
      "badge-foreground": "#ddd",
      "list-hoverBackground": "#303237",
      "editorWidget-background": "#232427",
      "list-inactiveSelectionBackground": "#2e436e",
      "list-activeSelectionBackground": "#2e436e",
      "list-activeSelectionForeground": "#fff",
    };
    for (const [key, value] of Object.entries(colors))
      document.documentElement.style.setProperty("--vscode-" + key, value);
    document.documentElement.style.colorScheme = "dark";
  });
  await page.screenshot({
    path: ".artifacts/ui/finish-dark.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 280, height: 900 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
