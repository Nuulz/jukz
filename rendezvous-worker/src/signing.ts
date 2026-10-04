// Short-lived HMAC-signed URLs for snapshot blobs, standing in for R2 presigned URLs: the Worker signs
// `{op}:{worldId}/{part}:{exp}` with SNAPSHOT_SIGNING_KEY and verifies it when the URL comes back.

export const URL_TTL_SECS = 300;

const encoder = new TextEncoder();

async function hmacHex(key: string, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey("raw", encoder.encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(message));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function payload(op: string, worldId: string, part: string, exp: number, tier: string): string {
  // Upload URLs also carry the uploader's tier (its size cap and how long the backup is kept).
  return tier ? `${op}:${worldId}/${part}:${exp}:${tier}` : `${op}:${worldId}/${part}:${exp}`;
}

export async function signBlobUrl(
  origin: string,
  key: string,
  op: "put" | "get",
  worldId: string,
  part: "pack" | "head",
  nowMs: number = Date.now(),
  tier = "",
): Promise<string> {
  const exp = Math.floor(nowMs / 1000) + URL_TTL_SECS;
  const sig = await hmacHex(key, payload(op, worldId, part, exp, tier));
  return `${origin}/v1/snapshot/blob/${worldId}/${part}?op=${op}&exp=${exp}${tier ? `&t=${tier}` : ""}&sig=${sig}`;
}

/** Constant-time check of the signature, the operation and the expiry. */
export async function verifyBlobUrl(
  url: URL,
  key: string,
  op: "put" | "get",
  worldId: string,
  part: "pack" | "head",
  nowMs: number = Date.now(),
): Promise<boolean> {
  const exp = Number(url.searchParams.get("exp"));
  const sig = url.searchParams.get("sig") ?? "";
  if (url.searchParams.get("op") !== op || !Number.isSafeInteger(exp) || exp < Math.floor(nowMs / 1000)) return false;
  const expected = await hmacHex(key, payload(op, worldId, part, exp, url.searchParams.get("t") ?? ""));
  if (expected.length !== sig.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ sig.charCodeAt(i);
  return diff === 0;
}
