# Wurst Client v7.50.1 (MC1.21.8) - Modified by CevAPI 

![Wurst Client logo](https://img.wimods.net/github.com/Wurst-Imperium/Wurst7?to=https://wurst.wiki/_media/logo/wurst_758x192.webp)

- **Original Repo:** https://github.com/Wurst-Imperium/Wurst7

- **Downloads:** [https://www.wurstclient.net/download/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2Fdownload%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)

- **Installation guide:** [https://www.wurstclient.net/tutorials/how-to-install/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2Ftutorials%2Fhow-to-install%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)

- **Feature list:** [https://www.wurstclient.net/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)

- **Wiki:** [https://wurst.wiki/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwurst.wiki%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)

- **Forum:** [https://wurstforum.net/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwurstforum.net%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)	

- **Twitter/X:** [https://x.com/Wurst_Imperium](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https://x.com/Wurst_Imperium)

- **YouTube:** [https://www.youtube.com/@Alexander01998](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https://www.youtube.com/@Alexander01998)

- **Donations/Perks:** [https://ko-fi.com/wurst](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https://ko-fi.com/wurst)

## What’s new in this fork

### New hacks
- MobSearch
  - Search mobs by fuzzy name/ID or exact type (e.g., `minecraft:zombie` or `zombie`).
  - Rendering: Boxes, Lines, or Both. Rainbow or fixed color (default red). Box size configurable.

- BedESP
  - Chunk-based scanning with configurable color and Box/Line style.

- SignESP
  - Finds all sign types/materials.
  - Option: Include Frames (item frames + glow item frames) with a separate color.
  - Style + color + area; empty/zero-size shapes are ignored for stability.

- WorkstationESP
  - Highlights: Crafting Table, Smithing Table, Anvil (all states), Grindstone, Enchanting Table, Cartography Table, Stonecutter, Loom, Furnace, Blast Furnace, Smoker, Campfire, Soul Campfire, Brewing Stand, Cauldron, Barrel, Composter, Lectern, Fletching Table, Beacon.
  - Style + color + area.
  - Per-block toggles and per-block colors. Defaults: all enabled; each uses the main/default color unless overridden.

- RedstoneESP
  - Highlights: Redstone Torch/Block, Lever, Buttons/Plates (all types), Tripwire Hook, Target Block, Dust, Repeater, Comparator, Observer, Daylight Detector, Sculk Sensor (+ Calibrated), Pistons (regular/sticky), Dispenser, Dropper, Hopper, Trapped Chest, Note Block, Jukebox, Bell, Lectern (redstone), Powered/Detector/Activator Rails.
  - Style + color + area.
  - Per-component toggles and per-component colors. Defaults: all enabled; each uses the main/default color unless overridden.

### Search improvements
- New keyword field (plain text): type terms like “diamond”. Falls back to the block picker when empty.
- Rendering style: Boxes, Lines, or Both (tracers). View-bobbing is canceled when lines are enabled.
- Color mode: “Use fixed color” toggle (default off → rainbow). When enabled, you can pick a fixed color.
- Safer update cycle: query/radius changes trigger proper rescans; shared normalization and predicate helpers.
- Tracer safety: falls back to block center when the outline shape is empty (prevents crashes on empty VoxelShapes).

### Portal ESP improvement
- Area/radius changes reset its scan coordinator so expanding the radius takes effect immediately.

### Larger scan radius
- ChunkAreaSetting extended up to 65×65 chunks for all chunk-based features (Search, Portal ESP, BedESP, SignESP, WorkstationESP, RedstoneESP).

### Sticky area (new option)
- Added to: Search, Portal ESP, BedESP, SignESP, RedstoneESP, WorkstationESP, ChestESP.
- Default: Off (re-centers every time you enter a new chunk to match ESP drop-off distances).
- Tip: Turn Sticky area On to keep results anchored as you move. Useful for pathing back to things you found at the edge of your range.

### Stability
- Fixed “UnsupportedOperationException: No bounds for empty shape” by:
  - Using the block center when an outline is empty (Search tracers).
  - Skipping entries with empty/zero-size shapes (SignESP).

### Notes
- Scanning only includes chunks the server has sent. Larger radii work best in singleplayer or on servers with higher view distance.

## License

This code is licensed under the GNU General Public License v3. 
