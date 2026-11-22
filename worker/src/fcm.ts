// Firebase Cloud Messaging HTTP v1 sender. The service account JSON lives in the Worker secret
// FCM_SERVICE_ACCOUNT; an OAuth access token is minted from it with a signed JWT (RS256).

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

export function parseServiceAccount(json: string | undefined): ServiceAccount | null {
  if (!json) return null;
  try {
    const sa = JSON.parse(json) as Partial<ServiceAccount>;
    if (sa.project_id && sa.client_email && sa.private_key) return sa as ServiceAccount;
  } catch {
    // fall through
  }
  return null;
}

export function base64url(bytes: Uint8Array | string): string {
  const data = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  let binary = "";
  for (const b of data) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function jwtClaims(sa: ServiceAccount, nowSeconds: number): Record<string, unknown> {
  return {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  };
}

function pemToDer(pem: string): ArrayBuffer {
  // A key pasted with its JSON escapes still in place has literal "\n" sequences; drop those too.
  const body = pem.replace(/\\n/g, "\n").replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const binary = atob(body);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out.buffer;
}

export async function signedJwt(sa: ServiceAccount, nowSeconds: number): Promise<string> {
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify(jwtClaims(sa, nowSeconds)));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  return `${header}.${claims}.${base64url(new Uint8Array(signature))}`;
}

export type SendResult = "sent" | "unregistered" | "failed";

/** Mints and caches an access token, then sends data messages. */
export class FcmClient {
  private token: { value: string; expiresAt: number } | null = null;
  private readonly sa: ServiceAccount;
  private readonly fetcher: typeof fetch;

  constructor(sa: ServiceAccount, fetcher: typeof fetch = fetch) {
    this.sa = sa;
    this.fetcher = fetcher;
  }

  private async accessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (this.token && this.token.expiresAt - 60 > now) return this.token.value;
    const assertion = await signedJwt(this.sa, now);
    const response = await this.fetcher("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Google sign-in for the service account failed: ${response.status} ${text.slice(0, 200)}`);
    }
    const json = (await response.json()) as { access_token: string; expires_in: number };
    this.token = { value: json.access_token, expiresAt: now + json.expires_in };
    return json.access_token;
  }

  async send(deviceToken: string, data: Record<string, string>): Promise<SendResult> {
    const response = await this.fetcher(
      `https://fcm.googleapis.com/v1/projects/${this.sa.project_id}/messages:send`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${await this.accessToken()}`, "content-type": "application/json" },
        body: JSON.stringify({
          message: { token: deviceToken, data, android: { priority: "high", ttl: "600s" } },
        }),
      },
    );
    if (response.ok) return "sent";
    const text = await response.text();
    if (response.status === 404 || text.includes("UNREGISTERED")) return "unregistered";
    console.log(`FCM send failed: ${response.status} ${text.slice(0, 300)}`);
    return "failed";
  }
}
