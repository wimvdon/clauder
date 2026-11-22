import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { HOOKS_VERSION, INSTALL_SCRIPT, installScript } from "../src/install.ts";

test("the served script is hooks/install-user-hooks.sh (run node scripts/sync-install.mjs after changing it)", () => {
  assert.equal(INSTALL_SCRIPT, readFileSync(new URL("../../hooks/install-user-hooks.sh", import.meta.url), "utf8"));
});

test("the served script points the hooks at the Worker it came from", () => {
  const script = installScript("other.example.workers.dev");
  assert.ok(script.includes("https://other.example.workers.dev/hook"));
  assert.ok(!script.includes("clauder.wim-d57.workers.dev"));
  assert.equal(installScript("clauder.wim-d57.workers.dev"), INSTALL_SCRIPT.replaceAll("__HOOKS_VERSION__", HOOKS_VERSION));
});

test("the served script carries the installer's version, so old hooks can update themselves", () => {
  assert.match(HOOKS_VERSION, /^[0-9a-f]{12}$/);
  assert.ok(INSTALL_SCRIPT.includes('VERSION = "__HOOKS_VERSION__"'));
  assert.ok(installScript("x.workers.dev").includes(`VERSION = "${HOOKS_VERSION}"`));
});
