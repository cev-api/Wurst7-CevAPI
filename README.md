# Wurst Client v7.50.1 (MC1.21.8) - Modified by CevAPI 

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
- Rendering: Boxes, Lines, or Both. Rainbow or fixed color. Box size configurable.  

### BedESP
- Finds all bed types.  
- Chunk-based scanning with configurable color and Box/Line style.  
- Rendering: Boxes, Lines, or Both in a fixed color.  

### SignESP
- Finds all sign types/materials.  
- Option: include frames (item frames + glow item frames) with a separate color.  
- Rendering: Boxes, Lines, or Both in a fixed color.  

### WorkstationESP
- Highlights crafting/workstation blocks: Crafting Table, Smithing Table, Anvil, Grindstone, Enchanting Table, Cartography Table, Stonecutter, Loom, Furnace, Blast Furnace, Smoker, Campfire, Soul Campfire, Brewing Stand, Cauldron, Barrel, Composter, Lectern, Fletching Table, Beacon.  
- Per-block toggles and colors. Defaults: all enabled.  
- Rendering: Boxes, Lines, or Both in a fixed color.  

### RedstoneESP
- Highlights redstone components: Torch/Block, Lever, Tripwire Hook, Target Block, Dust, Repeater, Comparator, Observer, Daylight Detector, Skulk Sensor (+ Calibrated), Pistons, Dispenser, Dropper, Hopper, Trapped Chest, Note Block, Jukebox, Bell, Lectern (redstone), Powered/Detector/Activator Rails.  
- Buttons and pressure plates are grouped with individual toggles + colors.  
- Rendering: Boxes, Lines, or Both in a fixed color.  

### TridentESP
- Highlights thrown tridents, plus held tridents by players/mobs.  
- Color distinctions: your tridents, other players, mobs.  
- Rendering: Boxes, Lines, or Both.  
- Box size: Accurate or Fancy.  
- Colors: fixed, rainbow, or by owner type.  
- Toggles for held-trident highlights.  

### LavaWaterESP
- Highlights lava and water blocks (source and flowing) with boxes and or traces.
- Per type toggles, color and transparency sliders.
- Render cap and adjustable chunk radius to keep FPS down and the view unobstructive.

### Waypoints
- Create and save waypoints, with optional automatic death waypoints for all players.  
- Manager UI (`.waypoints` or apostrophe key).  
- Compass overlay: Show a bar at the top of the screen that shows the waypoint icons, when you're looking at it then it will display its name (Adjustable Position/Transparency)
- Coordinates overlay: Show your direction and current XYZ position above the compass overlay
- Preset icons
- Features: name, co-ordinates, dimension, icon, visibility, lines, color, copy button, opposite co-ordinates (nether), death pruning.  
- Constant-size labels and optional tracers.  
- Stored per world/server under `wurst/waypoints/<worldId>.json`.  

### Breadcrumbs
- Leaves a line trail behind you (toggle-able/pause-able).  
- Settings: color, max sections, section length, thickness.  

### LogoutSpots
- Records where players log out.  
- Removes spots when they rejoin.  
- Rendering: solid box + outline, optional tracers, name labels with adjustable scale.  
- Settings: side color, line color, name scale, tracers toggle.  

### ItemESP (expanded)
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

### ClickGUI improvements
- Accidentally typing in ClickGUI just continues what you typed in the Navigator typing in ClickGUI just continues what you typed in the Navigator
- Favorites category, middle click a hack for it to be added to Favorites. Middle click when within Favorites to remove it.

### Search improvements
- Keyword queries supported; falls back to picker when empty.  
- List mode with visual item list.  
- Multi-term queries: comma-separated (e.g., `ore, ancient`).  
- Rendering: Boxes, Lines, or Both. Tracers cancel view-bobbing.  
- Fixed/rainbow line colors.  
- New minimum search to 100 results.
- Replaced full-sort approach to bounded max-heap (PriorityQueue) that keeps the closest N matches. This avoids sorting entire result and keeps search as fast as X-Ray.
- Safer rescans and better crash handling.  

### X-Ray improvements
- Added ESP (Highlight Corners and or Fill Blocks)
    - Uses cached positions for speed
    - Optional transparency slider for ESP
- Multi-term queries: comma-separated (e.g., `diamond, ancient`).  
- Opacity, block type changes and 'only show exposed' apply live without toggling.  
- New minimum search to 100 blocks.

### Nuker improvements
- Auto toggle AutoTool option (If it wasn't on already, it will be enabled when using Nuker then turned off with Nuker)

### AutoSteal improvements
- Toggle 'Steal/Store Same' to move items that match the same ones in the players inventory or chest. Bind-able to a key.

### BaseFinder improvements
- Updated natural blocks list to latest versions.  

### MobESP improvements
- Added rainbow/fixed color options for boxes/lines.  

### Portal ESP improvement
- Radius changes reset scan instantly.  

### Larger scan radius
- Extended up to 65×65 chunks for all chunk-based features.  

### Sticky area (new option)
- Added to chunk-based ESPs (Search, Portal ESP, BedESP, SignESP, WorkstationESP, RedstoneESP, ChestESP).  
- Keeps results anchored as you move. Default OFF.  

### Unsafe Chat Toast
- Optional; toggle via NoChatReports or Wurst Options.  

### Removed Wurst Logo
- Removed from UI and Options.  

### Replace Taco with Neco
- Replaced Taco icon with dancing Neco-Arc.  

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


