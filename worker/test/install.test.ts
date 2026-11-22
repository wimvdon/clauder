import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { HOOKS_LEVEL, HOOKS_VERSION, INSTALL_SCRIPT, installScript } from "../src/install.ts";
import { loadedIsCurrent } from "../src/hooks.ts";

test("the served script is hooks/install-user-hooks.sh (run node scripts/sync-install.mjs after changing it)", () => {
  assert.equal(INSTALL_SCRIPT, readFileSync(new URL("../../hooks/install-user-hooks.sh", import.meta.url), "utf8"));
});

test("the served script points the hooks at the Worker it came from", () => {
  const script = installScript("other.example.workers.dev");
  assert.ok(script.includes("https://other.example.workers.dev/hook"));
  assert.ok(!script.includes("clauder.wim-d57.workers.dev"));
  assert.equal(
    installScript("clauder.wim-d57.workers.dev"),
    INSTALL_SCRIPT.replaceAll("__HOOKS_VERSION__", HOOKS_VERSION).replaceAll("__HOOKS_LEVEL__", HOOKS_LEVEL),
  );
});

test("the served script carries the installer's version, so old hooks can update themselves", () => {
  assert.match(HOOKS_VERSION, /^[0-9a-f]{12}$/);
  assert.ok(INSTALL_SCRIPT.includes('VERSION = "__HOOKS_VERSION__"'));
  assert.ok(installScript("x.workers.dev").includes(`VERSION = "${HOOKS_VERSION}"`));
});

test("the loaded hook settings say their level, in the command and the HTTP hook headers", () => {
  const script = installScript("x.workers.dev");
  assert.match(HOOKS_LEVEL, /^L\d+$/);
  assert.ok(script.includes(`clauder-prompt.py ${HOOKS_LEVEL}"`));
  assert.ok(script.includes(`"X-Clauder-Hooks-Loaded": "${HOOKS_LEVEL}"`));
  assert.ok(script.includes('"X-Clauder-Hooks-Loaded": sys.argv[1]'));
});

test("a session's hook settings count as current from level 1, whatever else the installer changed", () => {
  assert.equal(loadedIsCurrent(null), undefined);
  assert.equal(loadedIsCurrent(HOOKS_LEVEL), true);
  assert.equal(loadedIsCurrent("L1"), true);
  assert.equal(loadedIsCurrent("5ba5b56f378b"), true);
  assert.equal(loadedIsCurrent("987c2ff00000"), false);
  assert.equal(loadedIsCurrent("L0"), false);
});

test("only git push commands are sent from the shell", () => {
  assert.ok(INSTALL_SCRIPT.includes('{"matcher": "Bash", "hooks": [dict(h, **{"if": "Bash(git push*)"})]}'));
});
