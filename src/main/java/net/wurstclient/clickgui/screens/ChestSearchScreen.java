/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.util.ItemNameUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.hacks.WaypointsHack;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;

public final class ChestSearchScreen extends Screen
{
	private final Screen prev;
	private ChestManager chestManager = new ChestManager();
	private String screenTitle = "Chest Search";
	
	private EditBox searchField;
	private java.util.List<ChestEntry> results = new ArrayList<>();
	private final java.util.List<Button> rowButtons =
		new java.util.ArrayList<>();
	private int totalChestsLogged = 0;
	private long totalItemsLogged = 0;
	private long totalMatchingItems = 0;
	private int totalMatches = 0;
	private boolean radiusFilterActive = false;
	private int radiusLimitBlocks = Integer.MAX_VALUE;
	private int radiusFilteredOut = 0;
	private boolean limitedResults = false;
	private double scrollOffset = 0.0;
	private Button scrollUpButton;
	private Button scrollDownButton;
	private boolean draggingScrollbar = false;
	private double scrollbarDragStartY = 0.0;
	private double scrollbarStartOffset = 0.0;
	private int scrollTrackTop = 0;
	private int scrollTrackBottom = 0;
	private int scrollTrackX = 0;
	private int scrollTrackWidth = 0;
	private int scrollThumbTop = 0;
	private int scrollThumbHeight = 0;
	private double scrollMaxOffset = 0.0;
	
	// persist temp waypoints across screen instances
	private static final class TempWp
	{
		final java.util.UUID id;
		final long expiresAtMs;
		
		TempWp(java.util.UUID id, long expiresAtMs)
		{
			this.id = id;
			this.expiresAtMs = expiresAtMs;
		}
	}
	
	private static final java.util.Map<String, TempWp> TEMP_WP_BY_POS =
		new java.util.HashMap<>();
	private final java.util.Map<ChestEntry, java.util.List<ChestEntry.ItemEntry>> matchCache =
		new java.util.HashMap<>();
	private String lastMatchQuery = "";
	private String currentQuery = "";
	
	private static String normalizeDimension(String dimension)
	{
		return dimension == null ? "" : dimension;
	}
	
	private static String canonicalDimension(String dimension)
	{
		String dim = normalizeDimension(dimension).trim();
		if(dim.isEmpty())
			return "";
		String lower = dim.toLowerCase(Locale.ROOT);
		int colon = lower.indexOf(':');
		if(colon >= 0 && colon < lower.length() - 1)
			return lower.substring(colon + 1);
		return lower;
	}
	
	private static String makePosKey(String dimension, BlockPos pos)
	{
		String dim = normalizeDimension(dimension);
		return pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":" + dim;
	}
	
	private static String formatBlockPos(BlockPos pos)
	{
		if(pos == null)
			return "null";
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}
	
	private static String makeEntryKey(ChestEntry entry)
	{
		if(entry == null)
			return "";
		String server = entry.serverIp == null ? "" : entry.serverIp;
		String dim = normalizeDimension(entry.dimension);
		BlockPos min = entry.getMinPos();
		BlockPos max = entry.getMaxPos();
		return server + "|" + dim + "|" + formatBlockPos(min) + ">"
			+ formatBlockPos(max);
	}
	
	public static boolean isWaypointActive(String dimension, BlockPos pos)
	{
		String key = makePosKey(dimension, pos);
		TempWp twp = TEMP_WP_BY_POS.get(key);
		if(twp == null)
			return false;
		long now = System.currentTimeMillis();
		if(twp.expiresAtMs > 0 && now >= twp.expiresAtMs)
		{
			TEMP_WP_BY_POS.remove(key);
			try
			{
				WaypointsHack wh = WurstClient.INSTANCE.getHax().waypointsHack;
				if(wh != null)
					wh.removeTemporaryWaypoint(twp.id);
			}catch(Throwable ignored)
			{}
			return false;
		}
		return true;
	}
	
	public static boolean isEspActive(String dimension, BlockPos pos)
	{
		return net.wurstclient.chestsearch.TargetHighlighter.INSTANCE
			.has(normalizeDimension(dimension), pos);
	}
	
	public static boolean isPinned(String dimension, BlockPos pos)
	{
		return isWaypointActive(dimension, pos) || isEspActive(dimension, pos);
	}
	
	public static boolean clearDecorations(String dimension, BlockPos pos)
	{
		boolean clearedEsp =
			net.wurstclient.chestsearch.TargetHighlighter.INSTANCE
				.clear(normalizeDimension(dimension), pos);
		boolean clearedWp = clearWaypoint(dimension, pos);
		boolean cleared = clearedEsp || clearedWp;
		if(cleared && WurstClient.MC != null)
		{
			try
			{
				WurstClient.MC.execute(() -> {
					if(WurstClient.MC.screen instanceof ChestSearchScreen screen)
						screen.refreshPins();
				});
			}catch(Throwable ignored)
			{}
		}
		return cleared;
	}
	
	private static boolean clearWaypoint(String dimension, BlockPos pos)
	{
		String key = makePosKey(dimension, pos);
		TempWp twp = TEMP_WP_BY_POS.remove(key);
		if(twp == null)
			return false;
		try
		{
			WaypointsHack hack = WurstClient.INSTANCE.getHax().waypointsHack;
			if(hack != null)
				hack.removeTemporaryWaypoint(twp.id);
		}catch(Throwable ignored)
		{}
		return true;
	}
	
	private final boolean openedByKeybind;
	private boolean ignoreNextSearchChange = false;
	
	public ChestSearchScreen(Screen prev, Object ignored)
	{
		super(Component.literal("Chest Search"));
		this.prev = prev;
		this.openedByKeybind = (ignored instanceof Boolean && (Boolean)ignored);
	}
	
	/**
	 * Construct using a custom ChestManager (for example, lootsearch data).
	 */
	public ChestSearchScreen(Screen prev,
		net.wurstclient.chestsearch.ChestManager manager,
		boolean openedByKeybind)
	{
		super(Component.literal("Chest Search"));
		this.prev = prev;
		this.chestManager = manager == null ? new ChestManager() : manager;
		this.openedByKeybind = openedByKeybind;
		try
		{
			if(this.chestManager != null
				&& this.chestManager.getClass().getName()
					.equals("net.wurstclient.lootsearch.LootChestManager"))
			{
				this.screenTitle = "Loot Search";
			}
		}catch(Throwable ignored)
		{}
	}
	
	@Override
	protected void init()
	{
		int mid = this.width / 2;
		int controlsY = 18;
		searchField = new EditBox(this.font, mid - 150, controlsY, 220, 20,
			Component.literal("Search"));
		searchField.setVisible(true);
		searchField.setEditable(true);
		addRenderableWidget(searchField);
		searchField.setResponder(this::onSearchChanged);
		searchField.setFilter(s -> true);
		searchField.setMaxLength(100);
		searchField.setMessage(
			Component.literal("Type item name or id, e.g. minecraft:stone"));
		if(!openedByKeybind)
		{
			this.setInitialFocus(searchField);
			searchField.setFocused(true);
		}
		// If opened by keybind, clear any key that may have been typed to
		// avoid the key appearing in the search field. We also ignore the
		// first change (single stray key) that may be delivered just after
		// opening.
		if(openedByKeybind)
		{
			searchField.setValue("");
			ignoreNextSearchChange = true;
		}
		addRenderableWidget(Button.builder(Component.literal("Search"), b -> {
			onSearchChanged(searchField.getValue());
			rebuildRowButtons();
		}).bounds(mid + 80, controlsY, 70, 20).build());
		// Removed Refresh button (it recreated the ChestManager and re-run the
		// query which is redundant since Search already does the same).
		// removed Scan Open button per user request
		
		addRenderableWidget(Button
			.builder(Component.literal("Back"), b -> minecraft.setScreen(prev))
			.bounds(mid - 150, this.height - 28, 300, 20).build());
		
		scrollOffset = 0;
		draggingScrollbar = false;
		onSearchChanged("");
		rebuildRowButtons();
		scrollUpButton =
			addRenderableWidget(Button.builder(Component.literal("▲▲"), b -> {
				scrollOffset = 0;
				clampScroll();
				rebuildRowButtons();
			}).bounds(0, 0, 20, 16).build());
		scrollDownButton =
			addRenderableWidget(Button.builder(Component.literal("▼▼"), b -> {
				scrollOffset = scrollMaxOffset;
				clampScroll();
				rebuildRowButtons();
			}).bounds(0, 0, 20, 16).build());
		scrollUpButton.visible = false;
		scrollDownButton.visible = false;
	}
	
	private void onSearchChanged(String q)
	{
		if(ignoreNextSearchChange)
		{
			// ignore a single-character stray keypress; reset text and
			// continue with empty query
			ignoreNextSearchChange = false;
			if(q != null && q.length() <= 1)
			{
				searchField.setValue("");
				q = "";
			}
		}
		String qq = (q == null ? "" : q).trim();
		currentQuery = qq;
		lastMatchQuery = qq.toLowerCase(Locale.ROOT);
		matchCache.clear();
		if(this.chestManager == null)
			this.chestManager = new ChestManager();
		java.util.List<ChestEntry> raw =
			qq.isEmpty() ? new java.util.ArrayList<>(chestManager.all())
				: new java.util.ArrayList<>(chestManager.search(qq));
		
		// Only include pinned chests additionally if not searching
		if(qq.isEmpty())
		{
			for(ChestEntry e : chestManager.all())
			{
				BlockPos pos = e.getClickedPos();
				if(isPinned(e.dimension, pos))
					raw.add(e);
			}
		}
		// Remove stale entries on-the-fly when chunk is loaded and container
		// missing (handled by cleaner in background)
		
		java.util.LinkedHashMap<String, ChestEntry> dedup =
			new java.util.LinkedHashMap<>();
		for(ChestEntry e : raw)
		{
			String key = makeEntryKey(e);
			ChestEntry existing = dedup.get(key);
			if(existing == null
				|| extractLastSeenOrder(e) > extractLastSeenOrder(existing))
				dedup.put(key, e);
		}
		
		java.util.List<ChestEntry> allEntries = chestManager.all();
		java.util.Map<String, ChestEntry> canonicalAll =
			new java.util.LinkedHashMap<>();
		for(ChestEntry entry : allEntries)
		{
			if(entry == null)
				continue;
			String key = makeEntryKey(entry);
			ChestEntry existing = canonicalAll.get(key);
			if(existing == null
				|| extractLastSeenOrder(entry) > extractLastSeenOrder(existing))
				canonicalAll.put(key, entry);
		}
		totalChestsLogged = canonicalAll.size();
		long totalItems = 0L;
		for(ChestEntry entry : canonicalAll.values())
		{
			if(entry.items == null)
				continue;
			for(ChestEntry.ItemEntry item : entry.items)
				totalItems += item != null ? item.count : 0;
		}
		totalItemsLogged = totalItems;
		
		java.util.List<ChestEntry> working =
			new java.util.ArrayList<>(dedup.values());
		boolean isLootManager = false;
		try
		{
			isLootManager = this.chestManager != null
				&& this.chestManager.getClass().getName()
					.equals("net.wurstclient.lootsearch.LootChestManager");
		}catch(Throwable ignored)
		{}
		
		if(isLootManager)
		{
			// Sort by distance to player (closest first). If dimension differs,
			// prefer entries in the player's current dimension.
			net.minecraft.client.Minecraft mc = WurstClient.MC;
			Vec3 playerPos = null;
			String playerDim = "";
			if(mc != null && mc.player != null)
			{
				playerPos = new Vec3(mc.player.getX(), mc.player.getY(),
					mc.player.getZ());
				try
				{
					if(mc.level != null)
						playerDim = mc.level.dimension().location().toString();
				}catch(Throwable ignored)
				{}
			}
			final Vec3 pPos = playerPos;
			final String pDimKey = canonicalDimension(playerDim);
			java.util.Comparator<ChestEntry> distComp = (a, b) -> {
				if(a == null && b == null)
					return 0;
				if(a == null)
					return 1;
				if(b == null)
					return -1;
				String aDim = canonicalDimension(a.dimension);
				String bDim = canonicalDimension(b.dimension);
				if(!aDim.equals(bDim))
				{
					if(aDim.equals(pDimKey))
						return -1;
					if(bDim.equals(pDimKey))
						return 1;
					return aDim.compareTo(bDim);
				}
				if(pPos == null)
					return 0;
				double da =
					Vec3.atCenterOf(a.getClickedPos()).distanceToSqr(pPos);
				double db =
					Vec3.atCenterOf(b.getClickedPos()).distanceToSqr(pPos);
				return Double.compare(da, db);
			};
			working.sort(distComp);
		}else
		{
			java.util.Comparator<ChestEntry> recencyComparator =
				java.util.Comparator
					.comparingLong(ChestSearchScreen::extractLastSeenOrder)
					.reversed();
			working.sort(recencyComparator);
		}
		java.util.List<ChestEntry> pinned = new java.util.ArrayList<>();
		java.util.List<ChestEntry> others = new java.util.ArrayList<>();
		for(ChestEntry e : working)
		{
			BlockPos pos = e.getClickedPos();
			if(isPinned(e.dimension, pos))
				pinned.add(e);
			else
				others.add(e);
		}
		java.util.List<ChestEntry> ordered = new java.util.ArrayList<>();
		ordered.addAll(pinned);
		ordered.addAll(others);
		results = applyRadiusFilter(ordered);
		totalMatches = results.size();
		boolean shouldLimit = qq.isEmpty();
		if(shouldLimit)
		{
			int maxResults = 50;
			try
			{
				maxResults = WurstClient.INSTANCE.getHax().chestSearchHack
					.getMaxSearchResults();
			}catch(Throwable ignored)
			{}
			limitedResults = results.size() > maxResults;
			if(limitedResults)
				results =
					new java.util.ArrayList<>(results.subList(0, maxResults));
		}else
		{
			limitedResults = false;
		}
		totalMatchingItems = 0;
		for(ChestEntry entry : results)
		{
			for(ChestEntry.ItemEntry item : collectMatches(entry, qq))
			{
				if(item != null)
					totalMatchingItems += item.count;
			}
		}
		clampScroll();
		rebuildRowButtons();
	}
	
	private static long extractLastSeenOrder(ChestEntry entry)
	{
		if(entry == null || entry.lastSeen == null)
			return Long.MIN_VALUE;
		try
		{
			return Instant.parse(entry.lastSeen).toEpochMilli();
		}catch(DateTimeParseException e)
		{
			return Long.MIN_VALUE;
		}catch(Throwable ignored)
		{
			return Long.MIN_VALUE;
		}
	}
	
	private java.util.List<ChestEntry> applyRadiusFilter(
		java.util.List<ChestEntry> entries)
	{
		radiusFilterActive = false;
		radiusFilteredOut = 0;
		radiusLimitBlocks = Integer.MAX_VALUE;
		if(entries == null || entries.isEmpty())
			return entries;
		
		net.minecraft.client.Minecraft mc = WurstClient.MC;
		if(mc == null || mc.player == null)
			return entries;
			
		// If displaying LootChestManager data (seedmapper exports), skip
		// the ChestSearch display radius filter so all loot entries are shown.
		try
		{
			if(this.chestManager != null
				&& this.chestManager.getClass().getName()
					.equals("net.wurstclient.lootsearch.LootChestManager"))
			{
				return entries;
			}
		}catch(Throwable ignored)
		{}
		net.wurstclient.hacks.ChestSearchHack hack;
		try
		{
			hack = WurstClient.INSTANCE.getHax().chestSearchHack;
		}catch(Throwable ignored)
		{
			hack = null;
		}
		if(hack == null || hack.isDisplayRadiusUnlimited())
			return entries;
		
		int radiusBlocks = hack.getDisplayRadius();
		if(radiusBlocks <= 0 || radiusBlocks >= Integer.MAX_VALUE)
			return entries;
		
		radiusFilterActive = true;
		radiusLimitBlocks = radiusBlocks;
		double radiusSq = (double)radiusBlocks * (double)radiusBlocks;
		Vec3 playerPos =
			new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
		String playerDim = "";
		try
		{
			if(mc.level != null)
				playerDim = mc.level.dimension().location().toString();
		}catch(Throwable ignored)
		{}
		String playerDimKey = canonicalDimension(playerDim);
		
		java.util.ArrayList<ChestEntry> filtered =
			new java.util.ArrayList<>(entries.size());
		int filteredOut = 0;
		for(ChestEntry entry : entries)
		{
			if(entry == null)
				continue;
			boolean include = true;
			String entryDimKey = canonicalDimension(entry.dimension);
			if(!entryDimKey.isEmpty() && !playerDimKey.isEmpty()
				&& !entryDimKey.equals(playerDimKey))
				include = false;
			
			if(include)
			{
				BlockPos pos = entry.getClickedPos();
				Vec3 chestPos = Vec3.atCenterOf(pos);
				if(chestPos.distanceToSqr(playerPos) > radiusSq)
					include = false;
			}
			
			if(include)
				filtered.add(entry);
			else
				filteredOut++;
		}
		radiusFilteredOut = filteredOut;
		return filtered;
	}
	
	private void rebuildRowButtons()
	{
		for(Button btn : rowButtons)
		{
			this.removeWidget(btn);
		}
		rowButtons.clear();
		
		int x = this.width / 2 - 150;
		int resultsTop = getResultsTop();
		int y = resultsTop - (int)Math.round(scrollOffset);
		int visibleTop = resultsTop;
		int visibleBottom = getVisibleBottom();
		// scrolling buttons/row setup only
		
		// scrolling buttons/row setup only
		for(ChestEntry e : results)
		{
			String dim = normalizeDimension(e.dimension);
			BlockPos minPos = e.getClickedPos();
			// Use the actual recorded block position for this entry so that
			// toggling ESP/waypoint targets the chest half the user clicked
			// on. Canonical entries are still used elsewhere to dedupe, but
			// decorations should apply to the specific block position.
			boolean waypointActive = isWaypointActive(dim, minPos);
			boolean espActive = isEspActive(dim, minPos);
			boolean pinnedEntry = waypointActive || espActive;
			String query =
				searchField.getValue() == null ? "" : searchField.getValue();
			java.util.List<ChestEntry.ItemEntry> matches =
				collectMatches(e, query);
			boolean isLootManager = false;
			try
			{
				isLootManager = this.chestManager != null
					&& this.chestManager.getClass().getName()
						.equals("net.wurstclient.lootsearch.LootChestManager");
			}catch(Throwable ignored)
			{}
			int matchLines = matches.isEmpty() ? 1 : matches.size();
			int boxHeight = computeBoxHeight(pinnedEntry, matchLines);
			if(y + boxHeight < visibleTop)
			{
				y += boxHeight + 6;
				continue;
			}
			if(y > visibleBottom)
				break;
			int btnY = y + 6;
			Component espLabel =
				espActive
					? Component.literal("ESP*")
						.withStyle(style -> style
							.withColor(net.minecraft.ChatFormatting.GOLD))
					: Component.literal("ESP");
			Button espBtn = null;
			if(!isLootManager)
			{
				espBtn = Button.builder(espLabel, b -> {
					try
					{
						String dimLocal = normalizeDimension(e.dimension);
						// Use this entry's clicked position (the block the
						// player actually clicked when recording) so ESP draws
						// on the expected chest half.
						BlockPos useMin = e.getClickedPos();
						boolean exists = false;
						if(WurstClient.MC != null
							&& WurstClient.MC.level != null)
						{
							boolean isLootManagerInner = false;
							try
							{
								isLootManagerInner = this.chestManager != null
									&& this.chestManager.getClass().getName()
										.equals(
											"net.wurstclient.lootsearch.LootChestManager");
							}catch(Throwable ignored)
							{}
							if(!isLootManagerInner)
							{
								var world = WurstClient.MC.level;
								var state = world.getBlockState(useMin);
								boolean container = state != null && (state
									.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
									|| state
										.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
									|| state
										.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock
									|| state
										.getBlock() instanceof net.minecraft.world.level.block.DecoratedPotBlock
									|| state
										.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock);
								boolean hasBe =
									world.getBlockEntity(useMin) != null;
								exists = container && hasBe;
							}else
							{
								// For loot export entries, they may not exist
								// as placed blocks in
								// the world; allow ESP drawing without removing
								// the entry.
								exists = true;
							}
						}
						if(!exists)
						{
							try
							{
								new ChestManager().removeChest(e.serverIp,
									e.dimension, useMin.getX(), useMin.getY(),
									useMin.getZ());
							}catch(Throwable ignored)
							{}
							this.chestManager = new ChestManager();
							onSearchChanged(searchField.getValue());
							minecraft.execute(this::refreshPins);
							return;
						}
						net.wurstclient.hacks.ChestSearchHack hack =
							WurstClient.INSTANCE.getHax().chestSearchHack;
						net.wurstclient.chestsearch.TargetHighlighter.INSTANCE
							.setColors(hack.getEspFillARGB(),
								hack.getEspLineARGB());
						// render a single-block ESP at the canonical primary
						// position
						net.wurstclient.chestsearch.TargetHighlighter.INSTANCE
							.toggle(dimLocal, useMin, useMin,
								hack.getEspTimeMs());
						minecraft.execute(this::refreshPins);
					}catch(Throwable ignored)
					{}
				}).bounds(0, btnY, 40, 16).build();
				if(espActive)
					espBtn
						.setTooltip(net.minecraft.client.gui.components.Tooltip
							.create(Component.literal("ESP active")));
			}
			// position esp and wp buttons to the right side of the result box
			int boxRight = x + 340;
			int wpWidth = 56;
			int espWidth = isLootManager ? 0 : 40;
			int deleteWidth = 56;
			// Stack buttons vertically at the right edge (ESP, Waypoint,
			// Delete)
			int stackWidth = Math.max(Math.max(wpWidth, espWidth), deleteWidth);
			int stackRight = boxRight - 6; // 6px padding from box edge
			int stackX = stackRight - stackWidth;
			// place esp at top, wp below, delete below that
			if(!isLootManager && espBtn != null)
			{
				espBtn.setPosition(stackX + (stackWidth - espWidth) / 2, btnY);
				addRenderableWidget(espBtn);
				rowButtons.add(espBtn);
			}
			boolean hasWp = waypointActive;
			Component wpLabel =
				hasWp
					? Component.literal("Remove*")
						.withStyle(style -> style
							.withColor(net.minecraft.ChatFormatting.GOLD))
					: Component.literal("Waypoint");
			Button wpBtn = Button.builder(wpLabel, b -> {
				WaypointsHack wh = WurstClient.INSTANCE.getHax().waypointsHack;
				if(wh != null)
				{
					// Add/remove waypoint at the clicked block position.
					BlockPos wpPos = e.getClickedPos();
					String posKey = makePosKey(dim, wpPos);
					if(TEMP_WP_BY_POS.containsKey(posKey))
					{
						TempWp tmp = TEMP_WP_BY_POS.remove(posKey);
						java.util.UUID id = tmp == null ? null : tmp.id;
						try
						{
							wh.removeTemporaryWaypoint(id);
						}catch(Throwable ignored)
						{}
						minecraft.execute(this::refreshPins);
						return;
					}
					net.wurstclient.hacks.ChestSearchHack hack =
						WurstClient.INSTANCE.getHax().chestSearchHack;
					Waypoint w = new Waypoint(java.util.UUID.randomUUID(),
						System.currentTimeMillis());
					w.setName("Chest: " + minPos.getX() + "," + minPos.getY()
						+ "," + minPos.getZ());
					w.setPos(wpPos);
					w.setMaxVisible(10000);
					w.setLines(true);
					w.setColor(hack.getWaypointColorARGB());
					WaypointDimension wdim = WaypointDimension.OVERWORLD;
					if(!dim.isEmpty())
					{
						String p = dim.toLowerCase();
						if(p.contains("nether"))
							wdim = WaypointDimension.NETHER;
						else if(p.contains("end"))
							wdim = WaypointDimension.END;
					}
					w.setDimension(wdim);
					java.util.UUID id = wh.addTemporaryWaypoint(w);
					int sleep = hack.getWaypointTimeMs();
					TEMP_WP_BY_POS.put(posKey,
						new TempWp(id, System.currentTimeMillis() + sleep));
					minecraft.execute(this::refreshPins);
					Thread.ofPlatform().start(() -> {
						try
						{
							Thread.sleep(sleep);
						}catch(Exception ignored)
						{}
						try
						{
							wh.removeTemporaryWaypoint(id);
						}catch(Throwable ignored)
						{}
						TEMP_WP_BY_POS.remove(posKey);
						minecraft.execute(this::refreshPins);
					});
				}
			}).bounds(0, btnY, 56, 16).build();
			if(hasWp)
				wpBtn.setTooltip(net.minecraft.client.gui.components.Tooltip
					.create(Component.literal("Waypoint active")));
			wpBtn.setPosition(stackX + (stackWidth - wpWidth) / 2, btnY + 18);
			addRenderableWidget(wpBtn);
			
			// Delete (X) button to remove the recorded chest entry from disk
			Button delBtn = Button.builder(Component.literal("Delete"), b -> {
				try
				{
					BlockPos delPos = e.getClickedPos();
					new ChestManager().removeChest(e.serverIp, e.dimension,
						delPos.getX(), delPos.getY(), delPos.getZ());
				}catch(Throwable ignored)
				{}
				this.chestManager = new ChestManager();
				onSearchChanged(searchField.getValue());
				minecraft.execute(this::refreshPins);
			}).bounds(0, btnY, deleteWidth, 16).build();
			delBtn.setPosition(stackX + (stackWidth - deleteWidth) / 2,
				btnY + 36);
			// hide per-row buttons when their row is outside the visible
			// scrolling region so they don't overlap header/search UI
			boolean rowVisible = btnY >= visibleTop && btnY <= visibleBottom;
			if(!isLootManager && espBtn != null)
				espBtn.visible = rowVisible;
			wpBtn.visible = rowVisible;
			delBtn.visible = rowVisible;
			if(!isLootManager && espBtn != null)
				espBtn.active = rowVisible;
			wpBtn.active = rowVisible;
			delBtn.active = rowVisible;
			delBtn.setTooltip(net.minecraft.client.gui.components.Tooltip
				.create(Component.literal("Delete entry")));
			addRenderableWidget(delBtn);
			rowButtons.add(delBtn);
			rowButtons.add(wpBtn);
			y += boxHeight + 6;
		}
	}
	
	private int getResultsTop()
	{
		int sfY = 18;
		int summaryY = sfY + 24;
		return summaryY + 22;
	}
	
	private int getVisibleBottom()
	{
		return this.height - 40;
	}
	
	private void clampScroll()
	{
		String query = searchField == null ? "" : searchField.getValue();
		int contentHeight = calculateContentHeight(query);
		int visibleHeight = Math.max(0, getVisibleBottom() - getResultsTop());
		double maxScroll = Math.max(0, contentHeight - visibleHeight);
		scrollMaxOffset = maxScroll;
		if(scrollOffset < 0)
			scrollOffset = 0;
		else if(scrollOffset > maxScroll)
			scrollOffset = maxScroll;
		if(maxScroll <= 0)
		{
			scrollOffset = 0;
			draggingScrollbar = false;
		}
	}
	
	private int calculateContentHeight(String query)
	{
		if(results == null || results.isEmpty())
			return 0;
		int total = 0;
		for(ChestEntry e : results)
		{
			String dim = normalizeDimension(e.dimension);
			BlockPos chestPos = e.getClickedPos();
			boolean waypointActive = isWaypointActive(dim, chestPos);
			boolean espActive = isEspActive(dim, chestPos);
			boolean pinnedEntry = waypointActive || espActive;
			java.util.List<ChestEntry.ItemEntry> matches =
				collectMatches(e, query);
			int matchLines = matches.isEmpty() ? 1 : matches.size();
			int boxHeight = computeBoxHeight(pinnedEntry, matchLines);
			total += boxHeight + 6;
		}
		if(total > 0)
			total -= 6;
		return total;
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		if(verticalAmount != 0 && results != null && !results.isEmpty())
		{
			scrollOffset -= verticalAmount * 18;
			clampScroll();
			rebuildRowButtons();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
			verticalAmount);
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if(button == 0 && scrollMaxOffset > 0)
		{
			if(isOverScrollbarThumb(mouseX, mouseY))
			{
				draggingScrollbar = true;
				scrollbarDragStartY = mouseY;
				scrollbarStartOffset = scrollOffset;
				return true;
			}
			if(isOverScrollbarTrack(mouseX, mouseY))
			{
				double trackRange =
					(scrollTrackBottom - scrollTrackTop) - scrollThumbHeight;
				if(trackRange > 0)
				{
					double ratio =
						(mouseY - scrollTrackTop - scrollThumbHeight / 2.0)
							/ trackRange;
					scrollOffset = Math.max(0.0,
						Math.min(scrollMaxOffset, ratio * scrollMaxOffset));
					clampScroll();
					rebuildRowButtons();
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}
	
	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button,
		double deltaX, double deltaY)
	{
		if(draggingScrollbar && button == 0 && scrollMaxOffset > 0)
		{
			double trackRange =
				(scrollTrackBottom - scrollTrackTop) - scrollThumbHeight;
			if(trackRange > 0)
			{
				double delta = mouseY - scrollbarDragStartY;
				double ratio = delta / trackRange;
				scrollOffset = Math.max(0.0, Math.min(scrollMaxOffset,
					scrollbarStartOffset + ratio * scrollMaxOffset));
				clampScroll();
				rebuildRowButtons();
			}
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}
	
	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button)
	{
		if(button == 0 && draggingScrollbar)
		{
			draggingScrollbar = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}
	
	private boolean isOverScrollbarThumb(double mouseX, double mouseY)
	{
		return scrollMaxOffset > 0 && mouseX >= scrollTrackX
			&& mouseX <= scrollTrackX + scrollTrackWidth
			&& mouseY >= scrollThumbTop
			&& mouseY <= scrollThumbTop + scrollThumbHeight;
	}
	
	private boolean isOverScrollbarTrack(double mouseX, double mouseY)
	{
		return scrollMaxOffset > 0 && mouseX >= scrollTrackX
			&& mouseX <= scrollTrackX + scrollTrackWidth
			&& mouseY >= scrollTrackTop && mouseY <= scrollTrackBottom;
	}
	
	private void refreshPins()
	{
		if(searchField == null)
			return;
		onSearchChanged(searchField.getValue());
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta)
	{
		context.fill(0, 0, this.width, this.height, 0x88000000);
		context.drawCenteredString(this.font,
			Component.literal(this.screenTitle), this.width / 2, 4, 0xFFFFFFFF);
		int mid = this.width / 2;
		int sfX = mid - 150;
		int sfY = 18;
		context.fill(sfX - 2, sfY - 2, sfX + 222, sfY + 22, 0xFF333333);
		int summaryY = sfY + 24;
		// draw result panels BEFORE super.render so buttons draw on top
		int x = this.width / 2 - 150;
		int visibleTop = getResultsTop();
		int visibleBottom = getVisibleBottom();
		int visibleHeight = Math.max(0, visibleBottom - visibleTop);
		String q = searchField.getValue() == null ? ""
			: searchField.getValue().toLowerCase();
		int contentHeight = calculateContentHeight(q);
		float scale = 1.0f;
		try
		{
			net.wurstclient.hacks.ChestSearchHack hack =
				WurstClient.INSTANCE.getHax().chestSearchHack;
			if(hack != null)
				scale = hack.getTextScaleF();
		}catch(Throwable ignored)
		{}
		double maxScroll = Math.max(0, contentHeight - visibleHeight);
		scrollMaxOffset = maxScroll;
		if(scrollOffset < 0)
			scrollOffset = 0;
		else if(scrollOffset > maxScroll)
			scrollOffset = maxScroll;
		if(maxScroll <= 0)
			draggingScrollbar = false;
		int y = visibleTop - (int)Math.round(scrollOffset);
		
		int trackWidth = 8;
		int trackX = x + 340 + 6;
		int buttonWidth =
			scrollUpButton != null ? scrollUpButton.getWidth() : 44;
		int buttonHeight =
			scrollUpButton != null ? scrollUpButton.getHeight() : 16;
		if(scrollDownButton != null)
		{
			buttonWidth = Math.max(buttonWidth, scrollDownButton.getWidth());
			buttonHeight = Math.max(buttonHeight, scrollDownButton.getHeight());
		}
		int upButtonY = visibleTop;
		int downButtonY = visibleBottom - buttonHeight;
		if(downButtonY < upButtonY + buttonHeight)
			downButtonY = upButtonY + buttonHeight;
		
		if(scrollUpButton != null)
		{
			scrollUpButton.visible = maxScroll > 0;
			scrollUpButton.active = maxScroll > 0;
			scrollUpButton.setPosition(trackX - (buttonWidth - trackWidth) / 2,
				upButtonY);
		}
		if(scrollDownButton != null)
		{
			scrollDownButton.visible = maxScroll > 0;
			scrollDownButton.active = maxScroll > 0;
			scrollDownButton.setPosition(
				trackX - (buttonWidth - trackWidth) / 2, downButtonY);
		}
		
		int trackTop = upButtonY + buttonHeight + 4;
		int trackBottom = downButtonY - 4;
		scrollTrackX = trackX;
		scrollTrackWidth = trackWidth;
		if(maxScroll > 0 && trackBottom > trackTop + 1)
		{
			scrollTrackTop = trackTop;
			scrollTrackBottom = trackBottom;
			int trackHeight = trackBottom - trackTop;
			int thumbHeight = (int)Math
				.round((visibleHeight / Math.max(1.0, (double)contentHeight))
					* trackHeight);
			scrollThumbHeight =
				Math.max(12, Math.min(trackHeight, thumbHeight));
			int thumbTravel = trackHeight - scrollThumbHeight;
			int thumbOffset = thumbTravel > 0
				? (int)Math.round((scrollOffset / maxScroll) * thumbTravel) : 0;
			scrollThumbTop = trackTop + thumbOffset;
			context.fill(trackX, trackTop, trackX + trackWidth, trackBottom,
				0x55222222);
			context.fill(trackX, scrollThumbTop, trackX + trackWidth,
				scrollThumbTop + scrollThumbHeight, 0xFFAAAAAA);
		}else
		{
			scrollTrackTop = trackTop;
			scrollTrackBottom = trackTop;
			scrollThumbTop = trackTop;
			scrollThumbHeight = 0;
			if(scrollUpButton != null)
			{
				scrollUpButton.visible = false;
				scrollDownButton.visible = false;
			}
		}
		for(ChestEntry e : results)
		{
			String dim = normalizeDimension(e.dimension);
			final BlockPos minPos = e.getClickedPos();
			boolean waypointActive = isWaypointActive(dim, minPos);
			boolean espActive = isEspActive(dim, minPos);
			boolean pinnedEntry = waypointActive || espActive;
			
			java.util.List<ChestEntry.ItemEntry> matches = collectMatches(e, q);
			int totalCount = 0;
			for(ChestEntry.ItemEntry it : matches)
				totalCount += it.count;
			int matchLines = matches.isEmpty() ? 1 : matches.size();
			int boxHeight = computeBoxHeight(pinnedEntry, matchLines);
			if(y + boxHeight < visibleTop)
			{
				y += boxHeight + 6;
				continue;
			}
			if(y > visibleBottom)
				break;
			int bgColor = pinnedEntry ? 0x80423210 : 0x80202020;
			context.fill(x - 6, y, x + 340, y + boxHeight, bgColor);
			int headerY = y + 6;
			String locationLabel = formatLocationLabel(e, dim);
			int boxRight = x + 340;
			// compute available width for location text (leave space for
			// buttons)
			int wpWidth = 56;
			int espWidth = 40;
			int availWidth = (boxRight - x) - (espWidth + 4 + wpWidth + 12);
			if(availWidth < 50) // fallback
				availWidth = 50;
			if(pinnedEntry)
			{
				String header = "[PINNED] " + locationLabel;
				if(totalCount > 0)
					header += "  x" + totalCount;
				// wrap header into up to two lines so it doesn't go under
				// buttons
				java.util.List<String> lines =
					wrapText(header, availWidth, scale);
				for(int i = 0; i < lines.size(); i++)
					RenderUtils.drawScaledText(context, this.font, lines.get(i),
						x, headerY + i * 12, 0xFFF8D866, false, scale);
				// Omit the small "Active: ESP/Waypoint" status line to
				// reduce clutter and avoid overlap with the location text.
			}else
			{
				String header = locationLabel
					+ (totalCount > 0 ? ("  x" + totalCount) : "");
				java.util.List<String> lines =
					wrapText(header, availWidth, scale);
				for(int i = 0; i < lines.size(); i++)
					RenderUtils.drawScaledText(context, this.font, lines.get(i),
						x, headerY + i * 12, 0xFFFFFFFF, false, scale);
			}
			int headerLines = headerLineCount(pinnedEntry);
			int lineY = headerY + headerLines * 14;
			// add an extra spacer line between header and first item so item
			// icons/text don't overlap the header/facing text
			lineY += 12;
			if(matches.isEmpty())
			{
				String msg = q.isEmpty() ? "No items recorded."
					: "No items match this search.";
				RenderUtils.drawScaledText(context, this.font, msg, x,
					lineY + 2, 0xFFBBBBBB, false, scale);
				lineY += Math.round(18 * scale);
			}else
			{
				for(ChestEntry.ItemEntry it : matches)
				{
					try
					{
						net.minecraft.resources.ResourceLocation id =
							net.minecraft.resources.ResourceLocation
								.tryParse(it.itemId);
						if(id != null)
						{
							net.minecraft.world.item.Item item =
								net.minecraft.core.registries.BuiltInRegistries.ITEM
									.get(id);
							net.minecraft.world.item.ItemStack stack =
								new net.minecraft.world.item.ItemStack(item, 1);
							context.renderItem(stack, x + 2, lineY - 2);
						}
					}catch(Throwable ignored)
					{}
					String name =
						(it.displayName != null ? it.displayName : it.itemId);
					// If we have structured enchantment/potion info, append a
					// short readable summary so users can see e.g. "Sharpness"
					String extra = "";
					try
					{
						if(it.enchantments != null
							&& !it.enchantments.isEmpty())
						{
							java.util.List<String> human =
								new java.util.ArrayList<>();
							for(int ei = 0; ei < it.enchantments.size(); ei++)
							{
								String ench = it.enchantments.get(ei);
								net.minecraft.resources.ResourceLocation eid =
									null;
								try
								{
									eid =
										net.minecraft.resources.ResourceLocation
											.tryParse(ench);
								}catch(Throwable ignored)
								{}
								String path = eid != null ? eid.getPath()
									: ItemNameUtils.sanitizePath(ench);
								String baseName = ItemNameUtils
									.buildEnchantmentName(eid, path);
								String levelText = "";
								try
								{
									if(it.enchantmentLevels != null
										&& ei < it.enchantmentLevels.size())
									{
										int lvl = it.enchantmentLevels.get(ei)
											.intValue();
										if(lvl > 0)
											levelText = " " + Component
												.translatable(
													"enchantment.level." + lvl)
												.getString();
									}
								}catch(Throwable ignored)
								{}
								human.add(baseName + levelText);
							}
							extra =
								" [" + String.join(", ", human) + "\u00A7r]";
						}else if(it.primaryPotion != null
							&& !it.primaryPotion.isBlank())
						{
							net.minecraft.resources.ResourceLocation pid = null;
							try
							{
								pid = net.minecraft.resources.ResourceLocation
									.tryParse(it.primaryPotion);
							}catch(Throwable ignored)
							{}
							String ppath = pid != null ? pid.getPath()
								: ItemNameUtils.sanitizePath(it.primaryPotion);
							extra =
								" [" + ItemNameUtils.buildPotionName(pid, ppath)
									+ "\u00A7r]";
						}
						
						// Fallback: if recorder didn't populate structured
						// fields,
						// try to extract probable enchantment/effect ids from
						// NBT
						// text (covers older entries or cases where structured
						// extraction failed).
						if(extra.isEmpty() && it.nbt != null)
						{
							String s =
								it.nbt.toString().toLowerCase(Locale.ROOT);
							java.util.regex.Matcher m = java.util.regex.Pattern
								.compile("([a-z0-9_]+):([a-z0-9_]+)")
								.matcher(s);
							java.util.List<String> found =
								new java.util.ArrayList<>();
							while(m.find() && found.size() < 3)
							{
								String part = m.group(2);
								if(part == null)
									continue;
								if(found.contains(part))
									continue;
								// skip obvious tokens
								if(part.equals("nbt") || part.equals("ench")
									|| part.equals("id") || part.equals("item"))
									continue;
								found.add(part);
							}
							if(!found.isEmpty())
								extra = " ["
									+ String.join(", ",
										found.stream()
											.map(ChestSearchScreen::humanize)
											.toArray(String[]::new))
									+ "\u00A7r]";
						}
					}catch(Throwable ignored)
					{}
					
					// If extra is identical to the base name (e.g. "Paper" and
					// "[Paper]"), omit the extra to avoid duplication.
					if(extra != null && !extra.isEmpty())
					{
						String extraContent = extra.length() > 3
							? extra.substring(2, extra.length() - 1) : "";
						// remove Minecraft formatting sequences (section sign +
						// code)
						try
						{
							extraContent =
								extraContent.replaceAll("\u00A7.", "");
						}catch(Throwable ignored)
						{}
						String normExtra = extraContent.toLowerCase(Locale.ROOT)
							.replaceAll("[^a-z0-9 ]", "").trim();
						String normName =
							(name == null ? "" : name).toLowerCase(Locale.ROOT)
								.replaceAll("[^a-z0-9 ]", "").trim();
						if(normExtra.equals(normName))
							extra = "";
					}
					
					String line = name + extra + " x" + it.count + " (slot "
						+ it.slot + ")";
					RenderUtils.drawScaledText(context, this.font, line, x + 20,
						lineY + 2, 0xFFEFEFEF, false, scale);
					lineY += Math.max(18, Math.round(18 * scale));
				}
			}
			y += boxHeight + 6;
		}
		// disable clipping before drawing GUI children so they are not
		// clipped by the results region
		try
		{
			context.disableScissor();
		}catch(Throwable ignored)
		{
			// ignore scissor underflow if scissor wasn't enabled
		}
		// now draw children (buttons etc.) on top
		super.render(context, mouseX, mouseY, delta);
		int shown = results == null ? 0 : results.size();
		String limiter = limitedResults
			? " (showing first " + WurstClient.INSTANCE.getHax().chestSearchHack
				.getMaxSearchResults() + ")"
			: "";
		java.util.ArrayList<String> summaryExtras = new java.util.ArrayList<>();
		if(radiusFilterActive && radiusLimitBlocks < Integer.MAX_VALUE)
			summaryExtras.add("radius <= " + radiusLimitBlocks + " blocks");
		if(radiusFilterActive && radiusFilteredOut > 0)
			summaryExtras.add(radiusFilteredOut + " outside radius");
		String extra = summaryExtras.isEmpty() ? ""
			: " (" + String.join(", ", summaryExtras) + ")";
		String matchLabel =
			currentQuery.isEmpty() ? "Listed items" : "Matching items";
		String summary =
			"Showing " + shown + "/" + totalMatches + limiter + extra + " - "
				+ matchLabel + ": " + totalMatchingItems + " - Tracking "
				+ totalChestsLogged + " chests, " + totalItemsLogged + " items";
		int summaryPadding = 8;
		int summaryWidth = this.font.width(summary) + summaryPadding * 2;
		if(summaryWidth > this.width - 4)
			summaryWidth = this.width - 4;
		int summaryHalf = summaryWidth / 2;
		int summaryCenter = this.width / 2;
		int summaryLeft = Math.max(0, summaryCenter - summaryHalf);
		int summaryRight = Math.min(this.width, summaryCenter + summaryHalf);
		context.fill(summaryLeft, summaryY - 2, summaryRight, summaryY + 18,
			0xFF222222);
		context.drawCenteredString(this.font, Component.literal(summary),
			this.width / 2, summaryY + 2, 0xFFCCCCCC);
		
		if(shown == 0)
		{
			String msg =
				totalChestsLogged > 0
					? "No chests match this search. Tracking "
						+ totalChestsLogged + " chests with " + totalItemsLogged
						+ " items." + " Matching items: 0."
					: "No chests recorded yet.";
			context.drawCenteredString(this.font, Component.literal(msg),
				this.width / 2, this.height / 2, 0xFFAAAAAA);
		}
	}
	
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY,
		float delta)
	{
		// Skip Minecraft's default menu blur; render() already draws our
		// overlay.
	}
	
	private String formatLocationLabel(ChestEntry entry, String dimension)
	{
		BlockPos min = entry.getMinPos();
		BlockPos max = entry.getMaxPos();
		StringBuilder sb = new StringBuilder();
		if(dimension != null && !dimension.isEmpty())
		{
			String dimLabel = dimension;
			int colon = dimLabel.indexOf(':');
			if(colon >= 0 && colon < dimLabel.length() - 1)
				dimLabel = dimLabel.substring(colon + 1);
			sb.append(dimLabel).append(" @ ");
		}
		sb.append(min.getX()).append(",").append(min.getY()).append(",")
			.append(min.getZ());
		if(!min.equals(max))
		{
			sb.append(" - ").append(max.getX()).append(",").append(max.getY())
				.append(",").append(max.getZ());
			int sizeX = Math.abs(max.getX() - min.getX()) + 1;
			int sizeY = Math.abs(max.getY() - min.getY()) + 1;
			int sizeZ = Math.abs(max.getZ() - min.getZ()) + 1;
			sb.append(" size ").append(sizeX).append("x").append(sizeY)
				.append("x").append(sizeZ);
		}
		if(entry.facing != null && !entry.facing.isBlank())
			sb.append(" facing ").append(entry.facing);
		return sb.toString();
	}
	
	private java.util.List<ChestEntry.ItemEntry> collectMatches(
		ChestEntry entry, String query)
	{
		String q = (query == null ? "" : query.trim()).toLowerCase(Locale.ROOT);
		if(q.equals(lastMatchQuery))
		{
			java.util.List<ChestEntry.ItemEntry> cached = matchCache.get(entry);
			if(cached != null)
				return cached;
		}
		if(entry.items == null)
		{
			java.util.List<ChestEntry.ItemEntry> empty =
				java.util.Collections.emptyList();
			if(q.equals(lastMatchQuery))
				matchCache.put(entry, empty);
			return empty;
		}
		java.util.List<ChestEntry.ItemEntry> matches =
			new java.util.ArrayList<>();
		for(ChestEntry.ItemEntry it : entry.items)
		{
			if(it == null)
				continue;
			boolean matched = false;
			if(q.isEmpty())
				matched = true;
			if(!matched && it.itemId != null
				&& it.itemId.toLowerCase(Locale.ROOT).contains(q))
				matched = true;
			if(!matched && it.displayName != null
				&& it.displayName.toLowerCase(Locale.ROOT).contains(q))
				matched = true;
			// Also search NBT (full ItemStack data) so enchantments on
			// books/gear
			// are searchable if full NBT was recorded.
			if(!matched && it.nbt != null)
			{
				String n = it.nbt.toString().toLowerCase(Locale.ROOT);
				if(n.contains(q))
					matched = true;
			}
			// Also match extracted enchantment/potion ids collected by the
			// recorder so queries like "sharpness" or "speed" match items.
			if(!matched && it.enchantments != null)
			{
				for(String en : it.enchantments)
				{
					if(en != null && en.toLowerCase(Locale.ROOT).contains(q))
					{
						matched = true;
						break;
					}
				}
			}
			if(!matched && it.potionEffects != null)
			{
				for(String pe : it.potionEffects)
				{
					if(pe != null && pe.toLowerCase(Locale.ROOT).contains(q))
					{
						matched = true;
						break;
					}
				}
			}
			if(!matched && it.primaryPotion != null
				&& it.primaryPotion.toLowerCase(Locale.ROOT).contains(q))
				matched = true;
			if(matched)
				matches.add(it);
		}
		java.util.List<ChestEntry.ItemEntry> immutable = java.util.Collections
			.unmodifiableList(new java.util.ArrayList<>(matches));
		if(q.equals(lastMatchQuery))
			matchCache.put(entry, immutable);
		return immutable;
	}
	
	/*
	 * Find a canonical chest entry for given entry by matching contents.
	 *
	 * Currently unused; kept for reference. If needed later it can be
	 * re-enabled to map an entry to a canonical one by comparing item lists.
	 */
	// private ChestEntry findCanonicalEntry(ChestEntry entry)
	// {
	// if(entry == null || entry.items == null)
	// return entry;
	// for(ChestEntry e : chestManager.all())
	// {
	// if(e.items == null)
	// continue;
	// if(e.items.size() != entry.items.size())
	// continue;
	// boolean same = true;
	// for(int i = 0; i < e.items.size(); i++)
	// {
	// ChestEntry.ItemEntry a = e.items.get(i);
	// ChestEntry.ItemEntry b = entry.items.get(i);
	// if(a == null && b == null)
	// continue;
	// if(a == null || b == null)
	// {
	// same = false;
	// break;
	// }
	// if(a.count != b.count)
	// {
	// same = false;
	// break;
	// }
	// String ida = a.itemId == null ? "" : a.itemId;
	// String idb = b.itemId == null ? "" : b.itemId;
	// if(!ida.equals(idb))
	// {
	// same = false;
	// break;
	// }
	// }
	// if(same)
	// return e;
	// }
	// return entry;
	// }
	
	private static int headerLineCount(boolean pinnedEntry)
	{
		// Single-line header for both pinned and non-pinned entries now
		return 1;
	}
	
	private static int computeBoxHeight(boolean pinnedEntry, int matchLines)
	{
		int headerLines = headerLineCount(pinnedEntry);
		int effectiveLines = Math.max(1, matchLines);
		int topPadding = 6;
		int bottomPadding = 6;
		int headerHeight = headerLines * 14;
		int lineHeight = 18;
		int base = topPadding + headerHeight + effectiveLines * lineHeight
			+ bottomPadding;
		// reserve vertical space for stacked buttons (ESP, Waypoint, Delete)
		int minButtonSpace = 3 * lineHeight;
		return Math.max(base,
			topPadding + headerHeight + minButtonSpace + bottomPadding);
	}
	
	private java.util.List<String> wrapText(String text, int maxWidth,
		float scale)
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		if(text == null || text.isEmpty())
		{
			return lines;
		}
		
		String remaining = text;
		for(int line = 0; line < 2 && !remaining.isEmpty(); line++)
		{
			if(this.font.width(remaining) * scale <= maxWidth)
			{
				lines.add(remaining);
				break;
			}
			// find split point at last space that fits
			int cut = remaining.length();
			while(cut > 0 && this.font.width(remaining.substring(0, cut))
				* scale > maxWidth)
				cut = remaining.lastIndexOf(' ', Math.max(0, cut - 1));
			if(cut <= 0)
			{
				// can't find space - hard cut
				int pos = 1;
				while(pos < remaining.length()
					&& this.font.width(remaining.substring(0, pos))
						* scale <= maxWidth)
					pos++;
				lines.add(remaining.substring(0, pos - 1));
				remaining = remaining.substring(pos - 1).trim();
			}else
			{
				lines.add(remaining.substring(0, cut).trim());
				remaining = remaining.substring(cut).trim();
			}
		}
		return lines.isEmpty() ? java.util.List.of("") : lines;
	}
	
	private static String humanize(String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown";
		String humanized = java.util.Arrays.stream(path.split("_"))
			.filter(part -> !part.isEmpty())
			.map(part -> Character.toUpperCase(part.charAt(0))
				+ (part.length() > 1 ? part.substring(1) : ""))
			.collect(java.util.stream.Collectors.joining(" "));
		return humanized.isEmpty() ? "Unknown" : humanized;
	}
	
	@Override
	public void resize(net.minecraft.client.Minecraft client, int width,
		int height)
	{
		super.resize(client, width, height);
		clampScroll();
		rebuildRowButtons();
	}
}
