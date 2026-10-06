# Changelog

Every version gets a `## X.Y.Z` section with `- ` bullets. The mod shows the sections newer than the
version you last played in its "jukz was updated" screen, and a GitHub release whose notes are empty
gets its section as notes. Write them for players, not developers.

## 0.4.4

- Faster connections over IPv6: if you and the host both have IPv6, you now connect directly instead of going through the relay, with less lag. Nothing to set up.
- jukz 0.3.0 and older can no longer play online: update to keep playing.

## 0.4.3

- Your character stays yours: when a friend took over the world, they could load in with the previous host's inventory and position (Minecraft 26.2 stores the owner differently). Fixed.
- Players with and without a Microsoft account in the same world: whoever hosts, players with an account always join as themselves, with their own inventory and skin. Friends without one still get in when the host allows it, and can't take the name of a player with an account.
- New Settings in the hub (also Mod Menu's Config button): let friends without an account in, play over the internet or only on your network, and the relay testing switch. No more editing jukz.properties by hand.
- If a friend without an account is turned away, the host now gets a notice, and World info has a switch to let them in right away, no restart.
- My cloud no longer lists worlds whose backup has expired.

## 0.4.2

- Cosmetics without a Mojang account: you can now wear every free hat, face piece, back piece and badge. They stay on your PC and go to the friends you play with through jukz, over the game connection, like your skin. Paid and special items still need a Microsoft account.
- Move your cosmetics: in the hub, ▲▼ raise or lower the piece you're wearing and ◀▶ bring it closer to or farther from you, half a pixel per click. Your friends see it where you put it.
- Fixed: joining a friend from Minecraft's own LAN list (instead of through jukz) didn't count you as a guest, so when the host left, the world went to the cloud instead of passing to you.

## 0.4.1

Patch for 0.4.0.

- Fixed: on a PC whose clock is off (for example, a few hours behind), the world never went online, nobody could join by code, and the cloud backup failed when closing the world. jukz already corrected the clock for part of its requests; now it does for all of them.

## 0.4.0

- New jukz hub: your profile, cosmetics, skin and cloud worlds in one window that fills the screen, with a side menu and you in 3D on every section. A new pixel look across all jukz screens.
- Profile as a dashboard: your name, storage, today's uploads with a bar, retention, cosmetics and creations. Cards take you where they're about.
- Cosmetics: point at a card to try it on before wearing it.
- Change your skin in game (Hub → Skin): choose a PNG or drop one on the window, pick classic or slim arms and preview it. With a Mojang account it changes your real skin; without one, it stays on your PC and goes only to the friends you play with through jukz, over the game connection. No jukz server stores it.
- On a small window or a big GUI scale the hub switches to a compact layout.

## 0.3.1

- jukz now runs on Minecraft 1.21.11 and 26.2 too (one jar per Minecraft version; 26.2 needs Java 25).
- Worlds know which Minecraft version they're on. A friend hosting on another version is explained instead of a failed connection; a world saved on a newer version is never opened on an older one; and opening an older world asks first, since updating it leaves friends on the old version behind.
- Your account only brings over worlds of your Minecraft version; the others show their version in the account screen.
- The mod declares exactly the Minecraft version it was built for.
- Protection against bots, with no sign-in: each PC can open or join a few worlds per minute. If you go over, the world stays playable on your PC and goes online by itself a few minutes later; World info tells you when.
- Two players who end up with different copies of the same world can no longer overwrite each other's cloud backup.

## 0.2.2

- The mod is listed under its full name, Joining Every Known Zone (JUKZ).

## 0.2.1

- Mod Menu support: jukz has its icon, links (website, Ko-fi, source) and a Config button that opens your account screen. Mod Menu is optional.
- jukz now has an icon.

## 0.2.0

- Cosmetics: hats, glasses, backpacks and wings worn on your character, plus a badge next to your name in the tab list. All free.
- Pick them from the cube button on the title screen, in the pause menu or in Multiplayer → Cosmetics, with a 3D preview of yourself.
- Make your own: upload a Blockbench model at nuulm.com/jukz/crear. If it gets in, it's yours to keep.
- Guests are now verified by Mojang like on a normal server, and every world has an owner key: someone with only the code can't take it over.
- Your worlds follow your account (Microsoft accounts): worlds you back up to the cloud come over on their own when you start the game on another PC. The new account screen (person icon on the title screen) lists them.
- Worlds travel about half the size: cloud backups and handoffs are compressed far better, with nothing left out (a new world: ~9.5 MB → ~4.6 MB).
- Accounts are optional. Without one: cloud backups up to 40 MB, kept 30 days, 10 a day. Playing signed in with a Microsoft account: up to 95 MB, kept 180 days, 60 a day, plus your worlds on every PC and cosmetics. If a backup is over the limit, jukz says so instead of retrying.
- Your account, in game: the person icon on the title screen (or Singleplayer / Cosmetics → My account) shows your plan and today's backups, the worlds in your cloud (bring one here, or forget it) and the worlds only on this PC, with an Upload button so your other PCs get them.
- The website account page (nuulm.com/jukz/cuenta) keeps the password and "delete my data".
- Fixed: a world's saved generation stayed one behind after hosting, so opening it re-downloaded your own cloud copy, and uploading it from the menu was refused.
- This screen: after an update, jukz tells you what changed.
- Ko-fi button on the title screen, for anyone who wants to help pay for the servers. Nothing is locked behind it.

## 0.1.0

- First version: worlds that live on whoever is playing, with live handoff, cloud backups and joining by code.
