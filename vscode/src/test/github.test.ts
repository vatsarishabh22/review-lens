import { test } from "node:test";
import * as assert from "node:assert/strict";
import { GhClient } from "../infrastructure/github";
import { CliError } from "../infrastructure/cli";
test("GitHub calls paginate, send mutations as arguments, and pass review bodies on stdin", async () => {
  const calls: { program: string; args: string[]; input?: string }[] = [];
  const gh = new GhClient("repo", async (_root, program, args, input) => {
    calls.push({ program, args, input });
    return args.includes("--paginate")
      ? "#id\tPR_1\n#own\tfalse\na.txt\tVIEWED\n#id\tPR_1\nb.txt\tUNVIEWED\n"
      : "";
  });
  const states = await gh.viewed(1);
  assert.equal(states.files["b.txt"], "UNVIEWED");
  assert.ok(calls[0].args.includes("owner={owner}"));
  await gh.setViewed(states.id, "a file with 'quotes'.txt", false);
  assert.ok(calls[1].args.includes("path=a file with 'quotes'.txt"));
  assert.match(calls[1].args.at(-1)!, /unmarkFileAsViewed/);
  await gh.submit(1, { body: 'literal $() `command` "quotes"\nsecond line' });
  assert.deepEqual(calls[2].args.slice(-2), ["--input", "-"]);
  assert.equal(
    JSON.parse(calls[2].input!).body,
    'literal $() `command` "quotes"\nsecond line',
  );
});
test("GitHub review refusals expose the reason without marking a review accepted", async () => {
  const gh = new GhClient("repo", async () => {
    throw new CliError(
      "gh",
      '{"message":"Validation Failed","errors":[{"message":"Line could not be resolved"}]}',
      "HTTP 422",
      1,
    );
  });
  await assert.rejects(gh.submit(7, {}), /Line could not be resolved/);
});
