import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { INSTALL_SCRIPT, installScript } from "../src/install.ts";

test("the served script is hooks/install-user-hooks.sh (run node scripts/sync-install.mjs after changing it)", () => {
  assert.equal(INSTALL_SCRIPT, readFileSync(new URL("../../hooks/install-user-hooks.sh", import.meta.url), "utf8"));
});

test("the served script points the hooks at the Worker it came from", () => {
  const script = installScript("other.example.workers.dev");
  assert.ok(script.includes("https://other.example.workers.dev/hook"));
  assert.ok(!script.includes("clauder.wim-d57.workers.dev"));
  assert.equal(installScript("clauder.wim-d57.workers.dev"), INSTALL_SCRIPT);
});
