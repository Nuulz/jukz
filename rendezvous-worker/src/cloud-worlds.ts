// A cloud world's backup can vanish from R2 while the account still lists it: the daily cron deletes
// expired backups (limits.ts), and an operator may clean the bucket by hand. Such a world can never be
// brought again, so lists drop it — and, when we know whose list it is, forget it for good.

import type { Env } from "./hub";

type Listed = { worldId: string };

/** [worlds] whose backup is still in R2, in order. [forget] is called for each one that's gone. */
export async function liveWorlds<T extends Listed>(env: Env, worlds: T[], forget?: (worldId: string) => Promise<unknown>): Promise<T[]> {
  const present = await Promise.all(worlds.map((w) => env.SNAPSHOTS.head(`${w.worldId}/pack`).then((o) => o !== null, () => true)));
  const gone = worlds.filter((_, i) => !present[i]);
  if (forget && gone.length > 0) {
    console.log(`account worlds pruned (backup gone): ${gone.map((w) => w.worldId).join(",")}`);
    await Promise.all(gone.map((w) => forget(w.worldId)));
  }
  return worlds.filter((_, i) => present[i]);
}
