import { test } from "node:test";
import * as assert from "node:assert/strict";
import { meaningful } from "../core/impact";
test("impact skips comments and formatting without hiding changed string values", () => {
  assert.equal(
    meaningful(" return value + 1; // why\n"),
    meaningful("\treturn value+1; // another reason\n"),
  );
  assert.equal(
    meaningful("/* comment\n changed */\nreturn value"),
    meaningful("return value"),
  );
  assert.notEqual(meaningful('return "a b"'), meaningful('return "ab"'));
  assert.notEqual(
    meaningful('return "https://a"'),
    meaningful('return "https://b"'),
  );
  assert.equal(
    meaningful("# a python comment\nreturn value"),
    meaningful("# changed\nreturn value"),
  );
});
