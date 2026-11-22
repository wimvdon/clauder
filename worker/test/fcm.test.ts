import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, createVerify } from "node:crypto";
import { base64url, FcmClient, jwtClaims, parseServiceAccount, signedJwt } from "../src/fcm.ts";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const sa = {
  project_id: "clauder-test",
  client_email: "push@clauder-test.iam.gserviceaccount.com",
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
};

test("parseServiceAccount", () => {
  assert.deepEqual(parseServiceAccount(JSON.stringify(sa)), sa);
  assert.equal(parseServiceAccount(undefined), null);
  assert.equal(parseServiceAccount("{}"), null);
  assert.equal(parseServiceAccount("not json"), null);
});

test("base64url has no padding or unsafe characters", () => {
  assert.equal(base64url(new Uint8Array([251, 255, 191])), "-_-_");
  assert.equal(base64url("a"), "YQ");
});

test("jwtClaims ask for the messaging scope for one hour", () => {
  const claims = jwtClaims(sa, 1000);
  assert.equal(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
  assert.equal(claims.exp, 4600);
});

test("signedJwt is a valid RS256 signature", async () => {
  const jwt = await signedJwt(sa, 1000);
  const [header, claims, signature] = jwt.split(".");
  const verify = createVerify("RSA-SHA256");
  verify.update(`${header}.${claims}`);
  assert.equal(verify.verify(publicKey, Buffer.from(signature, "base64url")), true);
  assert.equal(JSON.parse(Buffer.from(claims, "base64url").toString()).iss, sa.client_email);
});

test("FcmClient sends a high-priority data message and caches the token", async () => {
  const calls: { url: string; body: string }[] = [];
  const fake = (async (url: string, init: RequestInit) => {
    calls.push({ url, body: String(init.body) });
    if (url.includes("oauth2")) return Response.json({ access_token: "at", expires_in: 3600 });
    if (url.includes("gone")) return new Response('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 });
    return Response.json({ name: "projects/x/messages/1" });
  }) as unknown as typeof fetch;
  const client = new FcmClient(sa, fake);
  assert.equal(await client.send("device-token", { a: "1" }), "sent");
  assert.equal(await client.send("device-token", { a: "2" }), "sent");
  assert.equal(calls.filter((c) => c.url.includes("oauth2")).length, 1);
  const message = JSON.parse(calls[1].body).message;
  assert.equal(message.token, "device-token");
  assert.equal(message.android.priority, "high");
  assert.deepEqual(message.data, { a: "1" });
  assert.ok(calls[1].url.endsWith("/projects/clauder-test/messages:send"));
});

test("FcmClient reports unregistered tokens", async () => {
  const fake = (async (url: string) => {
    if (url.includes("oauth2")) return Response.json({ access_token: "at", expires_in: 3600 });
    return new Response('{"error":{"details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 });
  }) as unknown as typeof fetch;
  assert.equal(await new FcmClient(sa, fake).send("t", {}), "unregistered");
});

test("signedJwt accepts a key whose line breaks are still written as \\n", async () => {
  const escaped = { ...sa, private_key: sa.private_key.replace(/\n/g, "\\n") };
  const jwt = await signedJwt(escaped, 1000);
  const [header, claims, signature] = jwt.split(".");
  const verify = createVerify("RSA-SHA256");
  verify.update(`${header}.${claims}`);
  assert.equal(verify.verify(publicKey, Buffer.from(signature, "base64url")), true);
});

test("a failed Google sign-in says why", async () => {
  const fake = (async () =>
    new Response('{"error":"invalid_grant","error_description":"Invalid JWT Signature."}', { status: 400 })) as unknown as typeof fetch;
  await assert.rejects(new FcmClient(sa, fake).send("device-token", {}), /400 .*invalid_grant/);
});
