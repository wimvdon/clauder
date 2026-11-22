import { test } from "node:test";
import assert from "node:assert/strict";
import { isAuthorized, parseLimit, safeEqual, sessionUrl } from "../src/auth.ts";

test("safeEqual", () => {
  assert.equal(safeEqual("abc", "abc"), true);
  assert.equal(safeEqual("abc", "abd"), false);
  assert.equal(safeEqual("abc", "abcd"), false);
});

test("isAuthorized accepts only the exact bearer token", () => {
  assert.equal(isAuthorized("Bearer s3cret", "s3cret"), true);
  assert.equal(isAuthorized("  Bearer   s3cret  ", "s3cret"), true);
  assert.equal(isAuthorized("Bearer wrong", "s3cret"), false);
  assert.equal(isAuthorized("s3cret", "s3cret"), false);
  assert.equal(isAuthorized(null, "s3cret"), false);
  assert.equal(isAuthorized("Bearer ", "s3cret"), false);
});

test("isAuthorized refuses everything when no token is configured", () => {
  assert.equal(isAuthorized("Bearer ", undefined), false);
  assert.equal(isAuthorized("Bearer anything", ""), false);
});

test("sessionUrl maps a cloud session id to its claude.ai URL", () => {
  assert.equal(
    sessionUrl("cse_016RkgrdsxPDkWqQHTVbuDvm"),
    "https://claude.ai/code/session_016RkgrdsxPDkWqQHTVbuDvm",
  );
});

test("sessionUrl ignores missing or unexpanded values", () => {
  assert.equal(sessionUrl(null), null);
  assert.equal(sessionUrl(""), null);
  assert.equal(sessionUrl("$CLAUDE_CODE_REMOTE_SESSION_ID"), null);
  assert.equal(sessionUrl("cse_../evil"), null);
});

test("parseLimit", () => {
  assert.equal(parseLimit(null, 500), 500);
  assert.equal(parseLimit("20", 500), 20);
  assert.equal(parseLimit("9999", 500), 500);
  assert.equal(parseLimit("-3", 500), 500);
  assert.equal(parseLimit("abc", 500), 500);
});
