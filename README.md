# Wurst Client v7.51.4 (MC1.21.11) - Modified by CevAPI 

![CevAPI Logo](https://i.imgur.com/kBIn9Ab.png)

- Original Repo: https://github.com/Wurst-Imperium/Wurst7  
- Downloads: [https://www.wurstclient.net/download/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2Fdownload%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Installation guide: [https://www.wurstclient.net/tutorials/how-to-install/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2Ftutorials%2Fhow-to-install%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Feature list: [https://www.wurstclient.net/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Wiki: [https://wurst.wiki/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwurst.wiki%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  

## Supported Versions
- [1.21.1 - New (Based on 1.21.8)](https://github.com/cev-api/Wurst7-CevAPI/tree/1.21.1)
- [1.21.8](https://github.com/cev-api/Wurst7-CevAPI/tree/1.21.8)
- [1.21.10 - Recommended](https://github.com/cev-api/Wurst7-CevAPI/tree/1.21.10)
- [1.21.11 - Latest (Based on 1.21.10)](https://github.com/cev-api/Wurst7-CevAPI/tree/master)

### Download
Pre-compiled copies are available on the [Release Page](https://github.com/cev-api/Wurst7-CevAPI/releases). I make changes very often so if you want the latest patches/bug fixes and upgrades you **must** compile it yourself.

## Relationship to upstream

This project is a friendly, independent fork of Wurst 7. I originally proposed these features upstream and the maintainers kindly declined, so I’m keeping them in a separate fork. I’ll continue to maintain these additions and periodically re-base/sync with the upstream project.

- Upstream repository: https://github.com/Wurst-Imperium/Wurst7  
- This fork: https://github.com/cev-api/Wurst7-CevAPI  
- Status: actively maintained and re-based as upstream evolves  
- Will try and support at least the latest two Minecraft versions

All credit for the original client goes to Wurst-Imperium and its contributors. This fork is not affiliated with or endorsed by Wurst-Imperium. This fork maintains the original GPLv3 licensing.

## NiceWurst Variant (Cheat-Free Build)

This fork now ships with [NiceWurst](https://github.com/cev-api/NiceWurst), a non-cheating configuration you can build from the same source by passing ```-Pnicewurst=1``` at build (or setting NICEWURST=1 as an env var). 

### The NiceWurst profile:

- Keeps only the utility/survival hacks listed in ```src/main/java/net/wurstclient/nicewurst/NiceWurstModule.java``` (edit the allowlist there if you want to add or remove features);
- Removes combat/duplication/exploit hacks entirely and forces all ESP overlays to respect walls, so you can’t see entities through blocks;
hides the Alt Manager, Anti-Fingerprint UI, X-Ray block editor, and other “grey-area” tools;
- Rebrands the mod (jar name, mod id, HUD, links) to “NiceWurst 7” and stores data in .minecraft/nicewurst/ so it never touches your regular Wurst profile.

Build without the flag to get the full CevAPI experience; build with the flag for a genuinely cheat-free alternative.

### NiceWurst In-Game Screenshot
![NiceWurst](https://i.imgur.com/86vmxQi.png)

### Wurst7-CevAPI In-Game Screenshot
![Wurst7Cevapi](https://i.imgur.com/HjOIhzM.png)

## Novelty

I’m pleased to note that many of the features and improvements below are completely unique to this Wurst fork and aren’t found in any other clients or mods. Some are even original enough to stand on their own as full concepts. These include:

- EnchantmentHandler
- ItemHandler
- ChestSearch
- AutoDisenchant
- AntiSocial
- CheatDetect
- SafeTP / TPAHere
- TrialSpawnerESP
- QuickShulker
- AutoTrader
- AntiBlast
- [NiceWurst](https://github.com/cev-api/NiceWurst)
- SeedMapperHelper
- SpearAssist
- LootSearch
- OfflineSettings
- TargetPlace
- SurfaceXray
- Anti-Fingerprint
- TridentESP
- MobSearch
- Outreach
- LivestreamDetector
- Redstone, Bed, Sign & Workstation ESP
- SignFramePassThrough (I didn't know something like this existed as a mod already)
- Custom Waypoint System (Not a new idea, but unique in design and functionality)
- The overall changes to existing hacks, many of which greatly improve original functionality!

Because so many of these mods are entirely original, I plan to release some of them as standalone projects in the future.

## Unoriginal

I have taken the following ideas from other clients/mods:

- XCarryHack
- WindChargeKey
- BeaconExploit
- AutoMace
- CoordLogger
- AntiDrop
- LogoutSpots
- Breadcrumbs

I did not, nor could I copy their code directly as most are Meteor based mods. So these are considered my interpretation of the functionality of those hacks.


---

## What’s new in this fork?

### MobSearch
- Search mobs by fuzzy name/ID or exact type (e.g., `minecraft:zombie` or `zombie`).  
- Added list mode with visual list of mobs.  
- Multi-term queries: comma-separated (e.g., `skeleton, zombie`).  
- Rendering: Glow Outlines, Boxes, Octahedrons, Lines, or Both. Rainbow or fixed color, filled or unfilled and configurable box size.

![MobSearch](https://i.imgur.com/BXtUd9Q.png)

### BedESP
- Finds all bed types.  
- Chunk-based scanning with configurable color and Box/Line style.  
- Rendering: Boxes, Lines, or Both in a fixed color.
- Filter for hiding Trial Chamber and or Villager beds.

![BedESP](https://i.imgur.com/8ZF7n6a.png)

### SignESP
- Finds all sign types/materials.  
- Option: include frames (item frames + glow item frames) with a separate color.  
- Rendering: Boxes, Lines, or Both in a fixed color.

![SignESP](https://i.imgur.com/qgb9lOr.png)

### WorkstationESP
- Highlights crafting/workstation blocks: Crafting Table, Smithing Table, Anvil, Grindstone, Enchanting Table, Cartography Table, Stonecutter, Loom, Furnace, Blast Furnace, Smoker, Campfire, Soul Campfire, Brewing Stand, Cauldron, Barrel, Composter, Lectern, Fletching Table, Beacon.  
- Per-block toggles and colors. Defaults: all enabled.  
- Rendering: Boxes, Lines, or Both in a fixed color.
- Color override settings option to force all blocks to be a single color.
- Colors here will superseed ChestESP.
- Added HackList count.

![WorkStation](https://i.imgur.com/prsll1D.png)

### RedstoneESP
- Highlights redstone components: Torch/Block, Lever, Tripwire Hook, Target Block, Dust, Repeater, Comparator, Observer, Daylight Detector, Skulk Sensor (+ Calibrated), Pistons, Dispenser, Dropper, Hopper, Trapped Chest, Note Block, Jukebox, Bell, Lectern (redstone), Powered/Detector/Activator Rails.  
- Buttons and pressure plates are grouped with individual toggles + colors.  
- Rendering: Boxes, Lines, or Both in a fixed color.
- Color override settings option to force all blocks to be a single color.
- Colors here will superseed ChestESP.
- Added HackList count.

![RedStone](https://i.imgur.com/VGGq4eU.png)

### TridentESP
- Highlights thrown tridents, plus held tridents by players/mobs.  
- Color distinctions: your tridents, other players, mobs.  
- Rendering: Boxes, Lines, or Both.  
- Box size: Accurate or Fancy.  
- Colors: fixed, rainbow, or by owner type.  
- Toggles for held-trident highlights.

![Trident](https://i.imgur.com/h3m9NcD.png)

### LavaWaterESP
- Highlights lava and water blocks (source and flowing) with boxes and or traces.
- Per type toggles, color and transparency sliders.
- Render cap and adjustable chunk radius to keep FPS down and the view unobstructive.

![Lava](https://i.imgur.com/UU8vTIh.png)

### Waypoints
- Create and save waypoints, with optional automatic death waypoints for all players.  
- Manager UI (`.waypoints` or apostrophe key).  
- Compass overlay: Show a bar at the top of the screen that shows the waypoint icons in your field of view
    - Shows names of waypoints when you're directly facing them
    - Adjustable position and transparency
    - Moves out of the way of boss/town bars
    - Optionally shows death position
    - Optionally shows logout locations
- Coordinates overlay: Show your direction and current XYZ position above the compass overlay
- Preset icons; skull, arrow, heart etc.
- Features: name, co-ordinates, dimension, icon, visibility, lines, color, copy button, opposite co-ordinates (nether), death pruning.  
- Constant-size labels and optional tracers.  
- Stored per world/server under `wurst/waypoints/<worldId>.json`.  
- Xaero's Minimap integration, allows exporting and importing of waypoint data (disconnect & reconnect to update).
- Adjustable Tri-state Beacon Mode on waypoints (On/Off/ESP) that matches the waypoint's color.
- Toggleable automatic portal logging, logs numerically each portal you enter and matches it in the opposite dimension with the same number.

![WayPoints](https://i.imgur.com/dxFc17N.png) ![Waypoint Manager](https://i.imgur.com/K2xGVqc.png) ![Waypoint Editor](https://i.imgur.com/23i14s1.png)

### Chest Search
- Automatically or manually scan each chest you open and store its contents in a JSON file per server (You can also disable entirely)
 - Able to detect chest changes that you make, so adding or removing items instantly updates the JSON
 - Unable to detect chest changes that other players make
 - Able to delete entries
 - Able to determine and search for weapon/armor, potion and book enchantments
- Visually search all scanned chests for content based on keywords
- Create an ESP highlight of the chest that has your desired item or create a waypoint to track the chest down
- Chests are auto-removed if it has been detected as broken/missing
- Adjustable Waypoint and ESP timeout
- Adjustable ESP and Waypoint colors
- Adjustable search radius
- Adjustable font size
- Highlight open chests with X or an alternative color
- Option to remove tracers for opened chests

![ChestSearch](https://i.imgur.com/OXuVeF5.png) 

### Breadcrumbs
- Leaves a line trail behind you (toggle-able/pause-able).  
- Trail can be infinitely long
- Trail can be applied to other players (toggleable unique colors for each player (shared with PlayerESP))
- Settings: color, max sections, section length, thickness, targets, keep trails toggle, unique colors toggle.

![BreadCrumbs](https://i.imgur.com/XYWLWXY.png)

### LogoutSpots
- Records where players log out.  
- Removes spots when they rejoin.  
- Rendering: solid box + outline, optional tracers, name labels with adjustable scale.  
- Settings: side color, line color, name scale, tracers toggle, spot lifetime, waypoint toggle.  

![Logout](https://i.imgur.com/4JKPkNL.png)

### AutoDisenchant
- Feeds items from your inventory (and or hotbar) that can be disenchanted into the grindstone automatically.

### SignFramePassThrough
- You can now open chests that have item frames or signs in the way!
- Right-clicking item frames (with items in them) or signs interacts with the block behind them. Hold sneak to interact with the frame or sign normally.
- Toggle between signs, frames or both

### Location on Disconnect
- Copyable XYZ co-ordinates on disconnect/kick (Works with AutoLeave)

![Disc](https://i.imgur.com/b327XLx.png)

### Antisocial
- Hooks into the PlayerESP enter/leave detector (even if ESP itself is off) and logs out the instant someone walks into range.
- Reuses AutoLeave's Quit/Chars/SelfHurt modes so you can pick the safest disconnect for your server.
- Toggles AutoReconnect off so you stay gone.
- Perfect for hiding or protecting yourself while AFK farming

### Anti-Fingerprint
- Detects and stops resource-pack fingerprinting. 
  - Basic protections are already enabled by default.
- Policies: Observe (vanilla prompt + log), BlockLocal (decline private/LAN), BlockAll (decline all), SandboxAll (server sees fail; client saves copy). 
- Detects burst requests (N in M ms) with toasts; supports host whitelist. 
- Cache defenses: clear before download and per-session cache isolation. 
- Sandbox: optional auto-extract of resource-packs for inspection. 
- Telemetry: toast verbosity + audit log (host/IP/URL/UUID/required, cache/sandbox paths). 
- Access via Other → Anti-Fingerprint, Wurst Options, and Multiplayer/Server Finder buttons. 

![AntiFingerprint](https://i.imgur.com/b6VgF2K.png)

### Cheat Detect
- Watches other players each tick, tracking motion/swing stats to detect suspicious patterns
- Detects speed, flight, boat flight and high CPS sword swings
- Able to adjust the sensitivity for each detection
- Alerts throttle themselves to prevent spam
- Able to detect if other players are as cool as you!

![Cheater](https://i.imgur.com/X3l0ASq.png)

### SurfaceXRay
- Makes the exposed surface of tracked blocks or fluids like lava or water partially or fully transparent while clearing the matching volume underneath.
- Works together with XRay
- Can basically be used as a floor/block remover (see 2nd screenshot)
- Add items with the list UI, meaning you can add keywords or just select multiple blocks easily. 

![Surface](https://i.imgur.com/GLou1Ey.png) 
![NoNetherrack](https://i.imgur.com/AyVac4K.png)

### AntiDrop
- Prevents you from dropping the selected items by accident. Defaults to all weapons, tools, and shulker boxes.
- When enabled you cannot press the throw button on the chosen items and you cannot drag the chosen items out of inventory.

### EnchantmentHandler
- Displays an inventory overlay for chests containing enchanted items, listing each item’s slot number, enchantments, and providing quick-take options (single or by category).
- Renders on top of the vanilla screen darkening, stays aligned beside the container and has an adjustable size and font scaling
- Works with books, potions and gear and will separate them in the list by category and type

![Chest](https://i.imgur.com/0TdpYkM.png)

### SafeTP
- Combines Blink, your desired teleport command and a timer into a single function
- Allows you to teleport without worrying about the movement restrictions during the countdown
- Safe TPA Here option: Allows you to auto activate blink when a player accepts your TPA Here request and then auto deactivates when they arrive.

### AutoMace
- Auto changes to mace and attacks when falling on a target
- Has various settings such as minimum fall distance, switch delay, attack delay and filters
- Novel design where it implements a toggleable modified AimAssist setting whilst falling
- Adds toggleable MaceDMG hack on impact

### WindChargeKey
- Bind switching then throwing a wind charge to a key
- Delay, silent and auto jump settings

### XCarryHack
- Store items in your crafting table
- Added alerts if the game auto-drops or returns the items back into your main inventory

### BeaconExploit
- Force specific effects even on lower tiers
- Get resistance, regeneration, jump boost and strength with 4 beacons on a single level for example

![Sus](https://i.imgur.com/1eJGZpv.png)

### TrialSpawnerESP
- Finds every Trial Spawner entity and renders colors based on the state (idle, charging, active, completed)
- Draws activation ring which brightens when you're within the detection range
- Reads data on the on the spawner to show wave progress, active mob count, next-wave timer, cooldown countdown, trial type, distance, mob type and more
- Uses block-decoration heuristics to determine mob type even if data is missing
- Draws tethers to nearby Trial Vaults
- Highlights and counts Trial/Ominous Vaults
- Able to record opened Ominous Vaults to avoid re-trying the same ones in the future
- Congifurable with toggles for all of the above

![Trial](https://i.imgur.com/xOL9U3G.png)

### QuickShulker
- Automates sorting and storage by moving items into a temporary shulker box. Works on both containers (like chests, barrels, hoppers) and your own inventory.
- When used on an open container, it detects which items were taken (based on inventory slot increases) and transfers only those into the shulker, keeping the rest of your items untouched.
- When used from your own inventory, it simply transfers eligible items according to your filters and mode.
- Supports transfer modes (All / Stackable / Non-stackable) and blacklist / whitelist item lists.
- Protects key hotbar slots (placed shulker and best pickaxe) from being moved or overwritten.
- Automatically places a shulker safely nearby, faces it, opens it, deposits items, then breaks and retrieves it.
- Uses safe placement logic and short, latency-aware waits to sync with server timing.
- Ideal for rapid looting, cleanup, or personal inventory compression without manual sorting.
- Optionally can continue to a new shulker box if the first is full mid-transfer.

### AutoTrader
- Automatically sells selected items to villagers / merchants.
- Adds an "AutoTrader" button to the trade screen and a configurable Item List setting where you pick which items to quickly sell.
- When a merchant GUI is open the hack will detect matching trades (first and optional second buy-slot) and repeatedly perform purchases while:
  - the villager still has the trade available
  - the selected item is present in the player's inventory
- Robust sold-out detection and safe-stop logic to avoid spamming when offers are exhausted.
- Ideal for quickly offloading farmed goods (seeds, string, paper, etc.) into emeralds without manual clicking.

### AntiBlast
- Prevents push back from TNT, creeper explosions, respawn anchors, crystals, wither skulls, wither spawn explosions, ghast fireballs, breeze gusts (and wind charges) and other custom plugins/entities that use explosion physics.

### ItemHandler
- Jade-style Popup HUD and full GUI that lists nearby dropped items grouped by item id with aggregated counts (With optional registry IDs).
- Able to select or reject which item/s to pick up when moving over them:
  - Pick: reject (drop) everything except the selected types (only selected types will be picked up).
  - Reject: reject the selected types and drop them.
- Trace Selected Items: toggles tracing for selected types (uses ItemESP world render pass to draw rainbow tracer lines and ESP boxes).
  - Works independently of ItemESP
- Adjustable distance for item detection.
- Adjustable font scale setting (scales text and popup icon size).
- Adjustable Popup HUD display offset X/Y
- Respects ItemESP ignored list (optional) and provides link to edit the list.
- Detects XP Orbs and shows the exact XP each orb will give

![Popup](https://i.imgur.com/W97borj.png)
![GUI](https://i.imgur.com/eunLgVr.png)

### TargetPlace
- Select a block by pressing P, when that block is broken or destroyed (turned to air) it is automatically replaced with the block you're holding and it will be orientated down. This works from a distance as well.
- Deselect by holding SHIFT + P
- Useful for aiding in the breaking of bedrock with TNT and pistons. New piston can be spawned in the same tick as explosion, breaking bedrock all while just standing near it.

### OfflineSettings
- Auto Reconnect without 5 second timer, perfect for when you're logged out from another location
- Rejoin the current server with a random alt, with an alt with a specific name or with the same name as another player
- Logout selected players in the current server with an option for auto logouts
- Auto detect cracked/offline servers on connect (even when hack is disabled)
- Reconnect to server and immediately send a command, such as /op player
- Adds mixin buttons to disconnect/kick/ban screens for all cracked/offline servers that have the above functions

### CoordLogger
- Detect world and local events and attempt to position them with ESP and chat messages
- Events that are too far away the direction will be given
- Toggles for various types of events
- Toggles for debugging and ESP

### SeedMapperHelper
- UI for [my fork](https://github.com/cev-api/SeedMapper-CevAPI) of [SeedMapper](https://github.com/xpple/SeedMapper/) which covers all settings except for debug/dev mode.
- Supports ESP color settings so long as you're using [my SeedMapper fork](https://github.com/cev-api/SeedMapper-CevAPI)
- Supports keybinds for all settings

![UI](https://i.imgur.com/4b5IA0u.png)

### LootSearch
- Using [my fork](https://github.com/cev-api/SeedMapper-CevAPI) of [SeedMapper](https://github.com/xpple/SeedMapper/) you can export loot tables for selected/all structures and then parse that information using the same UI as ChestSearch.
- Sorted by closest chest.
- You can search for specific loot and set a waypoint towards it.
- Will not function without a valid SeedMapper loot export JSON for the server you're in.

![Loot](https://i.imgur.com/7pkTPxW.png)

### SpearAssist
- Boost Modes:
  - Dash - While charging with your right click you can boost yourself forward a specific distance with your left click creating a velocity based attack.
  - Hold - While charging you are constantly boosting forward whenever you press the left click.
- Your player can optionally stay grounded when boosting as to avoid flying off into the distance on each attack.
- Allow reverse toggle lets you hold S/back while attacking to flip boosts so they launch you backward instead.
- Highlighting: You can highlight near and far entities within your attack range. Near meaning the distance you can jab at them and far the distance you can charge at them. Default set to 7 for near and 8.5 blocks for far.
- Your charge is automatically and constantly resumed (no cooldown) so you can forever hold right click. It will be noisy but even if the spear is pointed down you will still be able to attack. 
- You can optionally allow aim assist to work whilst holding right click.
- Auto Attack, your jab attack will continue to auto hit once the cooldown has expired so long as you're hitting an entity.
- Designed for elytra-free ground PvP/PvE, though may be even more interesting with one.

### Custom Command Prefixes
- In the Wurst Options menu you can now change your command prefix
- Cycle through commonly used command prefixes by pressing the Command Prefix button

![CMD](https://i.imgur.com/A79Het4.png)
![CMD2](https://i.imgur.com/qBhwEUf.png)
![CMD3](https://i.imgur.com/veagIsN.png)

### Outreach
- Works the same as reach, gives you approximately 9 blocks of distance in attacking, but this time its through walls! 
- Has the same filters and FOV settings as KillAura.
- Your attacks will spam when going through walls so an auto-cooldown is applied.

### PearlESP
- Highlight held pearls by other players
- Shows where other players (including yourself) will be teleported
- Predict where your pearl will go via a trajectory line
- Adjustable color, default is purple

### Preset Manager
- Save full setups (enabled hacks, favourites, settings, keybinds, UI layout, navigator preferences etc) as named presets
- Load a preset to restore which will then reset the client state immediately
- Lets you delete presets
- Presets are stored under ```.minecraft/wurst/presets/<name>``` for easy backup or sharing
- Accessible through UI Settings in the ClickUI/Navigator or through the Wurst Options menu mixin

### Anticheat Detector
- Watches server behavior and packets to estimate if anti-cheats are present and active
- Flags the likely protection so you know which hacks might be risky to use
- Setback Detection, tracks sudden position corrections/teleports that indicate that the server rejected your hack and automatically disables it.
  - Works independantly of Anticheat Detector

### Livestream Detector
- Scans the player list for Youtube, Twitch, Tiktok and Kick for live streams and announces them in chat
- Supports API keys for large bulk scans without potential rate limiting
- Option to skip automatic scans if player list exceeds a predefined limit
- Option to cancel mid scan
- Available debug command ```.livestream <username>``` to manually check a username

## What’s changed or improved in this fork?

### ChestESP
- Added sticky area mode which makes the scan anchored instead of re-centering when you move
- Added ESP Y limit
- Added HackList count
- Added Buried chest highlighting including a filter to only find buried chests
- Added filters for chests to not highlight them if near spawners, trial chambers and villages (Excluding double chests and shulkers)

### ItemESP (Expanded)
Highlights dropped, equipped, and framed items with powerful filters and customization.

- Base highlighting: all dropped items with configurable base color.  
- Special filters: List, Item ID, or Query (multi-term keywords).  
- Special colors & styles: fixed or rainbow, outline-only, lines-only-for-special.  
- Equipped specials: highlight items held or worn by players/mobs (not yourself).  
- Item frames: highlight frames if the contained item matches special criteria.  
- Keyword matching: supports base ID, translation key, and display name (works with renamed/NBT/plugin-modified items). 
- Multi-term queries: comma-separated (e.g., `head, axe`).   
- Rendering: boxes with fill + outline; tracers use special color (or base color).  
- Robust parsing: lists accept unknown entries as keywords (safe parsing via `Identifier.tryParse`).  
- Ignore list: using keywords or specific item names you can exclude items from being highlighted entirely.
- Entity ignore: Can ignore other players, armor stands etc
- Detects XP Orbs and allows for different coloring or disabling entirely.

Examples:  
- Highlight skulls → Item ID: `minecraft:player_head`, special color: magenta, outline-only ON.  
- Highlight talismans (non-standard item) → Query: `talisman`, special color: rainbow, highlight frames ON, lines-only-for-special ON.

![Item](https://i.imgur.com/d0z2gTj.png)

### List UI (Search, MobSearch, ItemESP, Xray etc) Improvements
- Able to specifically enter and save keywords into the list (Great for custom items on modded servers).
- Able to clear the entire list with a single button
- Can now multi-select items by holding CTRL or SHIFT
- Deleting entries does not push you to the top of the list anymore

![ListUI](https://i.imgur.com/elZoUaz.png)

### ClickGUI Improvements
- Added Favorites category, middle click a hack for it to be added to Favorites. Middle click when within Favorites to remove it.
- Accidentally typing in ClickGUI just continues what you typed in the Navigator.
- Hacklist has font scaler, transparency, X & Y position adjustments, adjustable shadow box.
- Hacklist has the ability to highlight entries pursuant to the selected ESP color.
- Hacklist has the ability show the count of detected ESP items/blocks/entities (BedESP, SignESP, WorkstationESP, RedstoneESP, ChestESP, MobsearchESP, MobESP, ItemESP). They're toggled in each hack.
- UI now has more color options which are also applied to the Navigator UI
- New Isolate Windows toggle which allows the active window (or sub category/setting) to not display any other windows behind it
- Hacks can now support multiple pop up windows, or sub pop up windows. In Navigator these pop ups are drop downs instead.
- Right-click Area setting resets it to default.  
- Can now scroll all drop down/pop ups with your mouse.

![HackList](https://i.imgur.com/fzcQdjy.png)

### Search Improvements
- Keyword queries supported; falls back to picker when empty.  
- List mode with visual item list.  
- Multi-term queries: comma-separated (e.g., `ore, ancient`).  
- Rendering: Boxes, Lines, or Both. Tracers cancel view-bobbing.  
- Fixed/rainbow line colors.  
- New minimum search to 100 results.
- Replaced full-sort approach to bounded max-heap (PriorityQueue) that keeps the closest N matches. This avoids sorting entire result and keeps search as fast as X-Ray.
- Safer rescans and better crash handling.
- Improved search speed.

![Search](https://i.imgur.com/jxcn89u.png)

### X-Ray Improvements
- Prevent zero-alpha and treat near-opaque as opaque to prevent Sodium translucent BSP crashes
- Added ESP (Highlight Corners and or Fill Blocks)
    - Uses cached positions for speed
    - Optional transparency slider for ESP
- Multi-term queries: comma-separated (e.g., `diamond, ancient`).  
- Opacity, block type changes and 'only show exposed' apply live without toggling.  
- New minimum search to 100 blocks.

![X](https://i.imgur.com/21DE1Gt.png)
![X2](https://i.imgur.com/K2MjX6w.png)

### PlayerESP Improvements
- Added toggle for unique colors for each player (shared with Breadcrumbs)
- Added box fill with transparency slider
- Added static color option
- Added glow outlines as an option 
- Added Line of Sight Detection (LOS)
    - When you're spotted ESP and tracer will turn a bold red regardless of distance or color settings
    - Adjustable FOV and range
- Improved coloring for default distance based rendering, close (red) to far (green) (when all above off)
- Able to ignore NPCs
- Enter/Leave area notification option in chat with name, co-ordinates and block distance

![ESP](https://i.imgur.com/ydpYuOm.png)

### TriggerBot Improvements
- Can now select the mob and the desired weapon/tool to use against them and it will quickly auto-switch when detected
  - Limited to three different mobs for now to prevent clutter

### Nuker Improvements
- Auto toggle AutoTool option (If it wasn't on already, it will be enabled when using Nuker then turned off with Nuker)

### AutoSteal Improvements
- Toggle 'Steal/Store Same' to move items that match the same ones in the players inventory or chest. Bind-able to a key.

### BaseFinder Improvements
- ESP Y range adjustability
- Live updates for selected blocks, meaning you can adjust them on the fly without resetting the hack
- Added tracer option
- Added sticky area mode which makes the scan anchored instead of re-centering when you move
- Updated natural blocks list to latest versions (including trial chambers)
- Added HackList count

### MobESP Improvements
- Added rainbow/fixed color options for boxes/lines.  
- Added octahedron shapes.
- Added glow outlines as an option and set it as the new default.
- Added box color fill option.
- Added Shulker projectile ESP

![Mob](https://i.imgur.com/1LFR8ll.png)

### Portal ESP Improvement
- Single centered line for Nether Portals, End Portal Frames, and active End Portals.
- Radius changes reset scan instantly.  

### Larger Scan Radius
- Extended up to 65×65 chunks for all chunk-based features.  

### Above Ground/ESP Y Limit (New ESP Option)
- ItemESP, MobESP, MobSearch, Search, ChestESP, BedESP, SignESP, PortalESP, RedstoneESP and WorkstationESP now have an adjustable Y limit (default 62 which is approximately sea level)
- There is a global toggle and setting that can apply to all, or the user can set toggle and set them individually in the hack's settings (```.aboveground on/off```, ```.aboveground toggle```, ```.aboveground y #```) - also accessible via 'GlobalToggle' setting in the Other category within the ClickUI.
- This will help prevent you from detecting inaccessible mobs/items and help you focus on scanning the surface of the world (if that's your goal) 

### Sticky Area (New ESP Option)
- Added to chunk-based ESPs (Search, Portal ESP, BedESP, SignESP, WorkstationESP, RedstoneESP, ChestESP).  
- Keeps results anchored as you move instead of re-centering as you move. Default OFF.  
- Greatly helps with FPS when using things like Search or X-Ray and moving quickly
- Global toggle accessible via 'GlobalToggle' setting in the Other category within the ClickUI. 

### TooManyHax Improved
- Submenu of TooManyHax in the ClickUI/Navigator shows a list of toggleable hacks instead of needing to manually enter the name of each one via cmd.
- Hacks disabled by TooManyHax will be removed from the ClickUI to declutter

### Panic Improved
- It now saves your current enabled hacks and allows you to restore them via ClickUI/Navigator or keybind.

### Scaffold Walk Improved
- Ignores shulker boxes

### AutoLibrarian Improved
- Can now discover enchantments provided by data packs.
- Able to search for enchantments by keywords.

![Library](https://i.imgur.com/pWqgNz8.png)

### NoSlowDown Improved
- Added no slowdown for lava
- Added no slowdown for water

### TrueSight Improved
- Added customisable glow ESP for invisible entities

![UHHHUHOHUHUHHUHOHOHOHHOHH](https://i.imgur.com/TA65cm8.png)

### AutoSign Improved
- Can input sign text directly into ClickUI/Nagivator
- Can now save and manage a list of presets

### HandNoClip Improved
- Now shows a red X over your crosshair to remind you that you cannot place or interact with blocks (in front of you) while the hack is enabled
- X is removed when using any combat weapon as they still function normally

### Keybind Manager Improved
- Can now clear the entire keybinds instead of just resetting.

### Alt Manager Improved
- Can now multi-select and delete alt accounts

### Unsafe Chat Toast
- Optional; toggle via NoChatReports or Wurst Options.  

### Removed Wurst Logo
- Removed from UI and Options.  

### Replace Taco with Neco
- Replaced Taco icon with dancing Neco-Arc.  

### Stability
- Fixed crashes on empty/zero-size shapes.  
- Search tracers now use block centers as fallback.  
- SignESP skips zero-size entries safely.  
- ChunkSearcher now snapshots each chunk’s block-state palettes on the client thread and lets the async scan read from that immutable copy, preventing the off-thread palette races that causes rare crashes.
- Fixed rare empty outline shape crash (safe bounding box for empty shapes fix)

### Notes
- Scanning only includes server-loaded chunks. Larger radii work best in single-player or on high view distance servers.  

---

## License

This code is licensed under the GNU General Public License v3. 






