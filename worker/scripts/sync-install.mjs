// Copies hooks/install-user-hooks.sh into src/install.ts, so the Worker can serve it at
// GET /install.sh. Run after changing the script: node scripts/sync-install.mjs
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

const script = readFileSync(new URL("../../hooks/install-user-hooks.sh", import.meta.url), "utf8");
const version = createHash("sha256").update(script).digest("hex").slice(0, 12);
writeFileSync(
  new URL("../src/install.ts", import.meta.url),
  `// Generated from hooks/install-user-hooks.sh by scripts/sync-install.mjs. Don't edit by hand.\n` +
    `export const INSTALL_SCRIPT = ${JSON.stringify(script)};\n\n` +
    `/** Changes with every change to the installer, so the Worker can tell a session its hooks are old. */\n` +
    `export const HOOKS_VERSION = ${JSON.stringify(version)};\n\n` +
    `/** The hook installer, pointed at the Worker that serves it. It holds no secret: the token\n` +
    ` * comes from the environment's CLAUDER_HOOK_TOKEN variable when the hooks run. */\n` +
    `export function installScript(host: string): string {\n` +
    `  return INSTALL_SCRIPT.replaceAll("clauder.wim-d57.workers.dev", host).replaceAll("__HOOKS_VERSION__", HOOKS_VERSION);\n` +
    `}\n`,
);
