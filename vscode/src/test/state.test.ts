import { deepStrictEqual, ok, throws } from "node:assert";
import {
  mkdtempSync,
  mkdirSync,
  copyFileSync,
  readFileSync,
  readdirSync,
} from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import Ajv2020 from "ajv/dist/2020";
import { activeReview, reviewMeta, parseActive, parseMeta } from "../state";

// spec/fixtures is the state every editor shares: the real readers must load each file and lose no field.
const spec = join(__dirname, "..", "..", "..", "spec");
const fixtures = join(spec, "fixtures");
const temporary = join(__dirname, "..", "..", ".test-data");
mkdirSync(temporary, { recursive: true });

function install(name: string): { gitDir: string; json: unknown } {
  const gitDir = mkdtempSync(join(temporary, "review-lens-spec-"));
  mkdirSync(join(gitDir, "review-lens"));
  copyFileSync(join(fixtures, name), join(gitDir, "review-lens", name));
  return {
    gitDir,
    json: JSON.parse(readFileSync(join(fixtures, name), "utf8")),
  };
}

test("active review loads whole", () => {
  const { gitDir, json } = install("active.json");
  deepStrictEqual(activeReview(gitDir), json);
});

test("every PR and branch state file loads whole", () => {
  const names = readdirSync(fixtures).filter((n) => n !== "active.json");
  ok(names.length > 0, `no fixtures found in ${fixtures}`);
  for (const name of names) {
    const { gitDir, json } = install(name);
    deepStrictEqual(
      reviewMeta(gitDir, name.replace(/\.json$/, "")),
      json,
      name,
    );
  }
});

test("no review in progress reads as undefined", () => {
  deepStrictEqual(
    activeReview(mkdtempSync(join(temporary, "review-lens-spec-"))),
    undefined,
  );
});

// The schema is the contract's written form: every fixture must satisfy it, so the two can't drift apart.
test("every fixture matches spec/state.schema.json", () => {
  const validate = new Ajv2020({ allErrors: true }).compile(
    JSON.parse(readFileSync(join(spec, "state.schema.json"), "utf8")),
  );
  for (const name of readdirSync(fixtures)) {
    const ok = validate(JSON.parse(readFileSync(join(fixtures, name), "utf8")));
    deepStrictEqual(ok ? [] : validate.errors, [], name);
  }
});

test("malformed recovery state is refused instead of guessed", () => {
  throws(() => parseActive({ head: "missing" }), /Invalid review state/);
  const meta = JSON.parse(readFileSync(join(fixtures, "pr-7.json"), "utf8"));
  throws(
    () => parseMeta({ ...meta, reviewed: { "a.txt": false } }),
    /reviewed/,
  );
  throws(
    () => parseMeta({ ...meta, drafts: [{ path: "a.txt", line: 0 }] }),
    /draft/,
  );
});
