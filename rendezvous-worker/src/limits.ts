// What each kind of player may store, as pure rules (unit-tested). Signing in is optional: jukz works
// without it, with smaller limits. "Account" means the upload came from a game signed in with a Microsoft
// account (a cosmetics session) — the only identity the Worker can check at upload time.
//
// Cloud backups go through the Worker, whose request body limit is 100 MB, hence the account cap.

export type Tier = "guest" | "account";

export interface Limits {
  /** Largest cloud backup of one world (the compressed snapshot). */
  maxSnapshotBytes: number;
  /** A backup nobody updated for this long is deleted (the world itself lives on in players' saves). */
  keepDays: number;
  /** Cloud backups per day: per IP without an account, per player with one. */
  uploadsPerDay: number;
  /** Worlds remembered on the account for other PCs (accounts only). */
  cloudWorlds: number;
}

const MB = 1024 * 1024;

export const LIMITS: Record<Tier, Limits> = {
  guest: { maxSnapshotBytes: 40 * MB, keepDays: 30, uploadsPerDay: 10, cloudWorlds: 0 },
  account: { maxSnapshotBytes: 95 * MB, keepDays: 180, uploadsPerDay: 60, cloudWorlds: 50 },
};

export function tierOf(player: string | null): Tier {
  return player ? "account" : "guest";
}

/** Who a daily upload counter belongs to. */
export function usageSubject(player: string | null, ip: string): string {
  return player ? `player:${player}` : `ip:${ip}`;
}

export function dayKey(nowMs: number): string {
  return new Date(nowMs).toISOString().slice(0, 10);
}

export type UploadCheck = { ok: true } | { ok: false; status: 413 | 429; message: string };

const mb = (bytes: number) => `${Math.round(bytes / MB)} MB`;

/** May this backup go up? [size] is what the mod declares (older mods don't; the PUT still checks it). */
export function checkUpload(tier: Tier, size: number | undefined, usedToday: number): UploadCheck {
  const limits = LIMITS[tier];
  if (size !== undefined && size > limits.maxSnapshotBytes) {
    const more = tier === "guest" ? ` (${mb(LIMITS.account.maxSnapshotBytes)} when signed in with a Microsoft account)` : "";
    return { ok: false, status: 413, message: `this world is ${mb(size)}; cloud backups are up to ${mb(limits.maxSnapshotBytes)}${more}` };
  }
  if (usedToday >= limits.uploadsPerDay) {
    return { ok: false, status: 429, message: `${limits.uploadsPerDay} cloud backups a day reached; try again tomorrow` };
  }
  return { ok: true };
}

/** Is a backup last written at [uploadedMs] by [tier] past keeping? Unknown tiers (older backups) keep the longer time. */
export function expired(tier: string | undefined, uploadedMs: number, nowMs: number): boolean {
  const limits = tier === "guest" ? LIMITS.guest : LIMITS.account;
  return nowMs - uploadedMs > limits.keepDays * 24 * 60 * 60_000;
}

export function parseTier(raw: string | null | undefined): Tier {
  return raw === "account" ? "account" : "guest";
}
