# Store listing (Modrinth and CurseForge)

Text and files ready to paste when creating the project. Icon: `icon.png` (128x128, same file as the mod's own).

## Basics

| Field | Value |
|---|---|
| Name | jukz |
| Summary (under 256 chars) | Play with friends in the same world with no server. The world opens on whoever is playing, passes to the next player when they leave, and waits in the cloud when everyone is gone. |
| Loader | Fabric |
| Game version | 1.21.1 |
| Environment | Client: required. Server: not needed (it works through the integrated server). |
| Categories | Modrinth: Multiplayer, Social, Cosmetic, Utility. CurseForge: Multiplayer, Cosmetic, Utility & QoL. |
| License | MIT |
| Source | https://github.com/Nuulz/jukz |
| Issues | https://github.com/Nuulz/jukz/issues |
| Website | https://nuulm.com/jukz |
| Donation | https://ko-fi.com/nobmz |
| Required dependencies | Fabric API, Fabric Language Kotlin, owo-lib |

## Gallery (in this order, first one is the cover)

1. `playing.png`: A guest playing live in the host's world, joined through the relay
2. `world-list.png`: The green dot in the world list means a friend is hosting it right now
3. `host-left.png`: The host left with you inside: take over and keep playing
4. `cloud-upload.png`: Nobody around? The world is saved to the cloud for the next player
5. `cosmetics-front.png`: Cosmetics worn on your character, seen by everyone running jukz
6. `cosmetics-screen.png`: Pick yours with a 3D preview
7. `account-screen.png`: Your account in game: plan, cloud worlds and worlds only on this PC
8. `world-info.png`: Share code and Access: Open / Closed
9. `two-networks-relay.png`: Tested across two real networks, one behind CGNAT

## Description

```markdown
# jukz

**Your Minecraft world, wherever you are. Your friends, whenever they want.**

Open a world and your friends can jump in: no server, no port forwarding, no "is the host online?".
When the host leaves, someone else takes over. When everyone leaves, the world waits for you in the cloud.

## What it does

- **Play together without hosting anything.** Open a world and jukz announces it. Friends paste your share
  code (Multiplayer → *Play together*), or open their own copy of the world and join you automatically. It
  works through routers and mobile data because jukz brings its own relay.
- **A green dot in your world list** means a friend is playing that world right now. Click it to join.
- **The world never goes offline just because the host left.** Quit with friends inside and they can click
  *Host now* and carry on with the exact world you had. Quit alone and the world is backed up to the cloud;
  whoever opens it next continues from there.
- **Your worlds on every PC.** Sign in with a Microsoft account and your cloud worlds follow you to another PC
  or a new launcher instance. Manage it from the account screen in the game (person icon on the title screen).
- **Cosmetics, free.** Hats, glasses, backpacks, wings and a pixel badge next to your name in the tab list.
  Everyone running jukz sees them. Design your own in Blockbench and send it at nuulm.com/jukz/crear.
- **You decide who gets in.** Close access from the pause menu to make the world private. Guests are checked by
  Mojang like on a normal server, and every world has an owner key, so someone with only the code can't take it over.

## Install

Minecraft 1.21.1 with Fabric Loader 0.16.5 or newer, plus Fabric API, Fabric Language Kotlin and owo-lib.
Every friend needs the mod too.

## Accounts are optional

| | No account | Microsoft account |
|---|---|---|
| Playing with friends, handoff, relay | Yes | Yes |
| Cloud backup size | up to 40 MB | up to 95 MB |
| Kept in the cloud | 30 days | 180 days |
| Backups per day | 10 | 60 |
| Your worlds on every PC | No | up to 50 |
| Cosmetics | No | Yes, free |

The limits only apply to the cloud copy. Worlds on your PC never expire.

## Privacy and network use

jukz talks to its own server, `jukz.nuulm.com` (a Cloudflare Worker), to find who is hosting a world, relay
the game connection when a direct one isn't possible, and store cloud backups. It sends the world's code and
key-signed requests, your cosmetics, and, if you sign in, a signature made with your Minecraft chat
certificate (never your password or access token). The server and the mod are open source, and you can point
`rendezvous.url` in `config/jukz.properties` at your own server, or at `none` for LAN only.

## Free

jukz is free and its servers are paid for out of pocket. If it saved your world you can chip in on Ko-fi;
nothing is locked behind it.

Source, docs and issues: github.com/Nuulz/jukz · Website: nuulm.com/jukz
```

## Steps only the owner can do

1. **Modrinth:** create the project at modrinth.com/dashboard/projects (sign in with GitHub). Paste the fields
   above, upload the icon and gallery, and upload `jukz-0.2.1.jar` from the GitHub release as version 0.2.1
   (Fabric, 1.21.1, with the three dependencies). Submit for review (usually a day or two).
2. **CurseForge:** create the project at console.curseforge.com (Minecraft Mods), same fields, upload the same
   jar with Fabric + 1.21.1 + Java 21 and the dependencies. Moderation takes longer.
3. Once approved, add the project IDs and API tokens as GitHub secrets so the release workflow can upload
   new jars on its own.
