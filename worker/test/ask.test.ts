import { test } from "node:test";
import assert from "node:assert/strict";
import { findAsk, waitsOnUser } from "../src/ask.ts";

test("finds a question put to the user", () => {
  assert.equal(findAsk("Here is the mockup. Which do you like? I'd go with A."), "Which do you like?");
  assert.equal(findAsk("All green.\n\nWant me to merge it?"), "Want me to merge it?");
  assert.equal(findAsk("Klaar. Zal ik de release starten?"), "Zal ik de release starten?");
  assert.equal(findAsk("- **Option B:** cheaper. Ok to go with B?"), "Ok to go with B?");
});

test("ignores questions that aren't for the user", () => {
  assert.equal(findAsk("Why did it fail? The token had expired, so I renewed it."), null);
  assert.equal(findAsk("## Why does it crash?\n\nR8 removed a class. Fixed in PR #20."), null);
  assert.equal(findAsk('The issue asks "can you add dark mode?" and I added it.'), null);
  assert.equal(findAsk("Run `curl -s host?x=1` to check. It works."), null);
  assert.equal(findAsk("```\nwhat do you see?\n```\nAll done."), null);
  assert.equal(findAsk("Done. Let me know if anything breaks."), null);
  assert.equal(findAsk(""), null);
});

test("finds a request for approval without a question mark", () => {
  assert.equal(findAsk("The mockup is up. Let me know which one you want."), "Let me know which one you want.");
  assert.equal(findAsk("I'll open the PR once you approve the mockup."), "I'll open the PR once you approve the mockup.");
  assert.equal(findAsk("De mockup staat klaar. Laat maar weten welke je kiest."), "Laat maar weten welke je kiest.");
});

test("a question wins over an earlier request for approval", () => {
  assert.equal(findAsk("Let me know which one you want. Or should I pick?"), "Or should I pick?");
});

test("steps that wait on the user", () => {
  assert.equal(waitsOnUser("After approval: build it in the app and open a pull request"), true);
  assert.equal(waitsOnUser("Once you pick a font, apply it"), true);
  assert.equal(waitsOnUser("Na goedkeuring: bouwen en PR openen"), true);
  assert.equal(waitsOnUser("Build it in the app"), false);
  assert.equal(waitsOnUser("After the build, open a pull request"), false);
});

test("the turn summary says Claude waits on the user", () => {
  const summary = "I've posted round 4 of the icon mockup to Wim. I'm waiting for their answer before drawing the rest.";
  assert.equal(findAsk(summary), "I'm waiting for their answer before drawing the rest.");
  assert.equal(findAsk("I've posted the mockup in the thread for Wim to approve."), "I've posted the mockup in the thread for Wim to approve.");
  assert.equal(findAsk("I'm waiting for CI to finish."), null);
});
