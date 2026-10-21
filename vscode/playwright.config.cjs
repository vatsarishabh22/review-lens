const { defineConfig } = require("@playwright/test");
const path = require("node:path");
const fs = require("node:fs");
const temporary = path.join(__dirname, ".test-data", "browser-temp");
fs.mkdirSync(temporary, { recursive: true });
process.env.TEMP = process.env.TMP = process.env.TMPDIR = temporary;
module.exports = defineConfig({
  testDir: "./test/ui",
  timeout: 30000,
  use: {
    browserName: "chromium",
    channel: process.env.PLAYWRIGHT_CHANNEL || "msedge",
    headless: true,
    viewport: { width: 420, height: 920 },
  },
  reporter: "list",
  outputDir: ".artifacts/playwright",
  workers: 1,
});
