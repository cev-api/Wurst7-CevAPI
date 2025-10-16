# Wurst Client v7.50.2 (MC1.21.8) - Modified by CevAPI 

![CevAPI Logo](https://i.imgur.com/Uju0ZZJ.png)

- Original Repo: https://github.com/Wurst-Imperium/Wurst7  
- Downloads: [https://www.wurstclient.net/download/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2Fdownload%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Installation guide: [https://www.wurstclient.net/tutorials/how-to-install/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2Ftutorials%2Fhow-to-install%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Feature list: [https://www.wurstclient.net/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Wiki: [https://wurst.wiki/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwurst.wiki%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  

## Relationship to upstream

This project is a friendly, independent fork of Wurst 7. I originally proposed these features upstream and the maintainers kindly declined, so I’m keeping them in a separate fork. I’ll continue to maintain these additions and periodically re-base/sync with the upstream project.

- Upstream repository: https://github.com/Wurst-Imperium/Wurst7  
- This fork: https://github.com/cev-api/Wurst7-CevAPI  
- Status: actively maintained and re-based as upstream evolves  

All credit for the original client goes to Wurst-Imperium and its contributors. This fork is not affiliated with or endorsed by Wurst-Imperium. This fork maintains the original GPLv3 licensing.

---

## What’s new in this fork

### MobSearch
- Search mobs by fuzzy name/ID or exact type (e.g., `minecraft:zombie` or `zombie`).  
- Added list mode with visual list of mobs.  
- Multi-term queries: comma-separated (e.g., `skeleton, zombie`).  
- Rendering: Boxes, Octahedrons, Lines, or Both. Rainbow or fixed color, filled or unfilled and configurable box size.

![MobSearch](https://i.imgur.com/PeklZSq.png)

### BedESP
- Finds all bed types.  
- Chunk-based scanning with configurable color and Box/Line style.  
- Rendering: Boxes, Lines, or Both in a fixed color.

![BedESP](https://i.imgur.com/kPHKPDz.png)

### SignESP
- Finds all sign types/materials.  
- Option: include frames (item frames + glow item frames) with a separate color.  
- Rendering: Boxes, Lines, or Both in a fixed color.

![SignESP](https://i.imgur.com/oD0tV1l.png)

### WorkstationESP
- Highlights crafting/workstation blocks: Crafting Table, Smithing Table, Anvil, Grindstone, Enchanting Table, Cartography Table, Stonecutter, Loom, Furnace, Blast Furnace, Smoker, Campfire, Soul Campfire, Brewing Stand, Cauldron, Barrel, Composter, Lectern, Fletching Table, Beacon.  
- Per-block toggles and colors. Defaults: all enabled.  
- Rendering: Boxes, Lines, or Both in a fixed color.

![WorkStation](https://i.imgur.com/OloVtTi.png)

### RedstoneESP
- Highlights redstone components: Torch/Block, Lever, Tripwire Hook, Target Block, Dust, Repeater, Comparator, Observer, Daylight Detector, Skulk Sensor (+ Calibrated), Pistons, Dispenser, Dropper, Hopper, Trapped Chest, Note Block, Jukebox, Bell, Lectern (redstone), Powered/Detector/Activator Rails.  
- Buttons and pressure plates are grouped with individual toggles + colors.  
- Rendering: Boxes, Lines, or Both in a fixed color.

![RedStone](https://i.imgur.com/Vf9dI8W.png)

### TridentESP
- Highlights thrown tridents, plus held tridents by players/mobs.  
- Color distinctions: your tridents, other players, mobs.  
- Rendering: Boxes, Lines, or Both.  
- Box size: Accurate or Fancy.  
- Colors: fixed, rainbow, or by owner type.  
- Toggles for held-trident highlights.

![Trident](https://i.imgur.com/8v7uVbU.png)

### LavaWaterESP
- Highlights lava and water blocks (source and flowing) with boxes and or traces.
- Per type toggles, color and transparency sliders.
- Render cap and adjustable chunk radius to keep FPS down and the view unobstructive.

![Lava](https://i.imgur.com/34Kss6S.png)

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

![WayPoints](https://i.imgur.com/Tmp71qs.png) ![Waypoint Manager](https://i.imgur.com/41CKEiO.png) ![Waypoint Editor](https://i.imgur.com/QNCS66B.png)

### Chest Search
- Automatically or manually scan each chest you open and store its contents in a JSON file per server
 - Able to detect chest changes that you make, so adding or removing items instantly updates the JSON
 - Unable to detect chest changes that other players make
 - Able to delete entries
- Visually search all scanned chests for content based on keywords
- Create an ESP highlight of the chest that has your desired item or create a waypoint to track the chest down
- Chests are auto-removed if it has been detected as broken/missing
- Adjustable Waypoint and ESP timeout
- Adjustable ESP and Waypoint colors

![ChestSearch](https://i.imgur.com/o5DYBqR.png)

### Breadcrumbs
- Leaves a line trail behind you (toggle-able/pause-able).  
- Trail can be infinitely long
- Trail can be applied to other players (toggleable unique colors for each player (shared with PlayerESP))
- Settings: color, max sections, section length, thickness, targets, keep trails toggle, unique colors toggle.

![BreadCrumbs](https://i.imgur.com/OXzqDOz.png)

### LogoutSpots
- Records where players log out.  
- Removes spots when they rejoin.  
- Rendering: solid box + outline, optional tracers, name labels with adjustable scale.  
- Settings: side color, line color, name scale, tracers toggle, spot lifetime, waypoint toggle.  

![Logout](https://i.imgur.com/dPpTS5J.png)

### AutoDisenchant
- Feeds items from your inventory (and or hotbar) that can be disenchanted into the grindstone automatically.

![Auto](https://i.imgur.com/bpUaRf1.png) ![Dis](https://i.imgur.com/7Bu31YZ.png)

### SignFramePassThrough
- You can now open chests that have item frames or signs in the way!
- Right-clicking item frames (with items in them) or signs interacts with the block behind them. Hold sneak to interact with the frame or sign normally.
- Toggle between signs, frames or both

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

Examples:  
- Highlight skulls → Item ID: `minecraft:player_head`, special color: magenta, outline-only ON.  
- Highlight talismans (non-standard item) → Query: `talisman`, special color: rainbow, highlight frames ON, lines-only-for-special ON.

![Item](https://i.imgur.com/nsWLDdJ.png)

### List UI (Search, MobSearch, ItemESP, Xray etc) Improvements
- Able to specifically enter and save keywords into the list (Great for custom items on modded servers).
- Able to clear the entire list with a single button

![ListUI](https://i.imgur.com/rjfk4em.png)

### ClickGUI Improvements
- Accidentally typing in ClickGUI just continues what you typed in the Navigator.
- Favorites category, middle click a hack for it to be added to Favorites. Middle click when within Favorites to remove it.
- Hacklist has font scaler, transparency, X & Y position adjustments, adjustable shadow box.
- Hacklist has the ability to highlight entries pursuant to the selected ESP color.
- Hacklist has the ability show the count of detected ESP items/blocks/entities (BedESP, SignESP, WorkstationESP, RedstoneESP, ChestESP, MobsearchESP, MobESP, ItemESP). They're toggled in each hack.

![HackList](https://i.imgur.com/FsOxPD2.png)

### Search Improvements
- Keyword queries supported; falls back to picker when empty.  
- List mode with visual item list.  
- Multi-term queries: comma-separated (e.g., `ore, ancient`).  
- Rendering: Boxes, Lines, or Both. Tracers cancel view-bobbing.  
- Fixed/rainbow line colors.  
- New minimum search to 100 results.
- Replaced full-sort approach to bounded max-heap (PriorityQueue) that keeps the closest N matches. This avoids sorting entire result and keeps search as fast as X-Ray.
- Safer rescans and better crash handling.

![Search](https://i.imgur.com/zpITuWb.png)

### X-Ray Improvements
- Added ESP (Highlight Corners and or Fill Blocks)
    - Uses cached positions for speed
    - Optional transparency slider for ESP
- Multi-term queries: comma-separated (e.g., `diamond, ancient`).  
- Opacity, block type changes and 'only show exposed' apply live without toggling.  
- New minimum search to 100 blocks.

![X](https://i.imgur.com/CXm2HzS.png)

### PlayerESP Improvements
- Added toggle for unique colors for each player (shared with Breadcrumbs)
- Added box fill with transparency slider
- Added static color option
- Added Line of Sight Detection (LOS)
    - When you're spotted ESP and tracer will turn a bold red regardless of distance or color settings
    - Adjustable FOV and range
- Improved coloring for default distance based rendering, close (red) to far (green) (when all above off)
- Able to ignore NPCs

![ESP](https://i.imgur.com/1F7zU31.png)

### Nuker Improvements
- Auto toggle AutoTool option (If it wasn't on already, it will be enabled when using Nuker then turned off with Nuker)

### AutoSteal Improvements
- Toggle 'Steal/Store Same' to move items that match the same ones in the players inventory or chest. Bind-able to a key.

### BaseFinder Improvements
- Updated natural blocks list to latest versions.  

### MobESP Improvements
- Added rainbow/fixed color options for boxes/lines.  
- Added octahedron shapes and set it as the new default.
- Added box color fill option

![Mob](https://i.imgur.com/VXHW4qe.png)

### Portal ESP Improvement
- Radius changes reset scan instantly.  

### Larger Scan Radius
- Extended up to 65×65 chunks for all chunk-based features.  

### Above Ground (New ESP Option)
- ItemESP, MobSearch, Search, ChestESP, BedESP, SignESP, PortalESP, RedstoneESP and WorkstationESP now have an adjustable Y limit (default 62 which is approximately sea level)
- There is a global toggle and setting that can apply to all, or the user can set toggle and set them individually in the hack's settings (```.aboveground on/off```, ```.aboveground toggle```, ```.aboveground y #```)
- This will help prevent you from detecting inaccessible mobs/items and help you focus on scanning the surface of the world (if that's your goal) 

### Sticky Area (New ESP Option)
- Added to chunk-based ESPs (Search, Portal ESP, BedESP, SignESP, WorkstationESP, RedstoneESP, ChestESP).  
- Keeps results anchored as you move. Default OFF.  

### Unsafe Chat Toast
- Optional; toggle via NoChatReports or Wurst Options.  

### Removed Wurst Logo
- Removed from UI and Options.  

### Replace Taco with Neco
- Replaced Taco icon with dancing Neco-Arc.  

### Location on Disconnect
- Copyable XYZ co-ordinates on disconnect/kick (Works with AutoLeave)

![Disc](https://i.imgur.com/b327XLx.png)

### Anti-Fingerprint
- Detects and stops resource-pack fingerprinting. 
- Policies: Observe (vanilla prompt + log), BlockLocal (decline private/LAN), BlockAll (decline all), SandboxAll (server sees fail; client saves copy). 
- Detects burst requests (N in M ms) with toasts; supports host whitelist. 
- Cache defenses: clear before download and per-session cache isolation. 
- Sandbox: optional auto-extract of resource-packs for inspection. 
- Telemetry: toast verbosity + audit log (host/IP/URL/UUID/required, cache/sandbox paths). 
- Access via Other → Anti-Fingerprint, Wurst Options, and Multiplayer/Server Finder buttons. 

![AntiFingerprint](https://i.imgur.com/4xycaQG.png)

### Cheat Detect
- Watches other players each tick, tracking motion/swing stats to detect suspicious patterns
- Detects speed, flight, boat flight and high CPS sword swings
- Able to adjust the sensitivity for each detection
- Alerts throttle themselves to prevent spam
- Able to detect if other players are as cool as you!

![Cheater](https://i.imgur.com/beZoL1U.png)

### Usability
- Right-click Area setting resets it to default.  

### Stability
- Fixed crashes on empty/zero-size shapes.  
- Search tracers now use block centers as fallback.  
- SignESP skips zero-size entries safely.  

### Notes
- Scanning only includes server-loaded chunks. Larger radii work best in single-player or on high view distance servers.  

---

## License

This code is licensed under the GNU General Public License v3. 






