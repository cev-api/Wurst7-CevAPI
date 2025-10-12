/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.hacks.WaypointsHack;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;

public final class ChestSearchScreen extends Screen
{
	private final Screen prev;
	private ChestManager chestManager = new ChestManager();
	
	private TextFieldWidget searchField;
	private java.util.List<ChestEntry> results = new ArrayList<>();
	private final java.util.List<ButtonWidget> rowButtons =
		new java.util.ArrayList<>();
	private int totalChestsLogged = 0;
	private long totalItemsLogged = 0;
	private int totalMatches = 0;
	private boolean limitedResults = false;
	private double scrollOffset = 0.0;
	private ButtonWidget scrollUpButton;
	private ButtonWidget scrollDownButton;
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
	
	private static String normalizeDimension(String dimension)
	{
		return dimension == null ? "" : dimension;
	}
	
	private static String makePosKey(String dimension, BlockPos pos)
	{
		String dim = normalizeDimension(dimension);
		return pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":" + dim;
	}
	
	private static String makeEntryKey(ChestEntry entry)
	{
		String server = entry.serverIp == null ? "" : entry.serverIp;
		// Use the clicked position when building the entry key so that
		// separate recordings of the same canonical chest (different
		// clicked halves) are not deduplicated away. This ensures the
		// UI shows the exact recorded block the player clicked.
		BlockPos pos = entry.getClickedPos();
		return server + "|" + makePosKey(entry.dimension, pos);
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
					if(WurstClient.MC.currentScreen instanceof ChestSearchScreen screen)
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
		super(Text.literal("Chest Search"));
		this.prev = prev;
		this.openedByKeybind = (ignored instanceof Boolean && (Boolean)ignored);
	}
	
	@Override
	protected void init()
	{
		int mid = this.width / 2;
		int controlsY = 18;
		searchField = new TextFieldWidget(this.textRenderer, mid - 150,
			controlsY, 220, 20, Text.literal("Search"));
		searchField.setVisible(true);
		searchField.setEditable(true);
		addDrawableChild(searchField);
		searchField.setChangedListener(this::onSearchChanged);
		searchField.setTextPredicate(s -> true);
		searchField.setMaxLength(100);
		searchField.setMessage(
			Text.literal("Type item name or id, e.g. minecraft:stone"));
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
			searchField.setText("");
			ignoreNextSearchChange = true;
		}
		addDrawableChild(ButtonWidget.builder(Text.literal("Search"), b -> {
			onSearchChanged(searchField.getText());
			rebuildRowButtons();
		}).dimensions(mid + 80, controlsY, 70, 20).build());
		// Removed Refresh button (it recreated the ChestManager and re-run the
		// query which is redundant since Search already does the same).
		// removed Scan Open button per user request
		
		addDrawableChild(ButtonWidget
			.builder(Text.literal("Back"), b -> client.setScreen(prev))
			.dimensions(mid - 150, this.height - 28, 300, 20).build());
		
		scrollOffset = 0;
		draggingScrollbar = false;
		onSearchChanged("");
		rebuildRowButtons();
		scrollUpButton =
			addDrawableChild(ButtonWidget.builder(Text.literal("▲▲"), b -> {
				scrollOffset = 0;
				clampScroll();
				rebuildRowButtons();
			}).dimensions(0, 0, 20, 16).build());
		scrollDownButton =
			addDrawableChild(ButtonWidget.builder(Text.literal("▼▼"), b -> {
				scrollOffset = scrollMaxOffset;
				clampScroll();
				rebuildRowButtons();
			}).dimensions(0, 0, 20, 16).build());
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
				searchField.setText("");
				q = "";
			}
		}
		String qq = (q == null ? "" : q).trim();
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
			if(!dedup.containsKey(key))
				dedup.put(key, e);
		}
		
		java.util.List<ChestEntry> allEntries = chestManager.all();
		totalChestsLogged = allEntries.size();
		long totalItems = 0L;
		for(ChestEntry entry : allEntries)
		{
			if(entry.items == null)
				continue;
			for(ChestEntry.ItemEntry item : entry.items)
				totalItems += item != null ? item.count : 0;
		}
		totalItemsLogged = totalItems;
		
		java.util.List<ChestEntry> working =
			new java.util.ArrayList<>(dedup.values());
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
		totalMatches = ordered.size();
		results = ordered;
		int maxResults = 50;
		try
		{
			maxResults = WurstClient.INSTANCE.getHax().chestSearchHack
				.getMaxSearchResults();
		}catch(Throwable ignored)
		{}
		limitedResults = results.size() > maxResults;
		if(limitedResults)
			results = new java.util.ArrayList<>(results.subList(0, maxResults));
		clampScroll();
		rebuildRowButtons();
	}
	
	private void rebuildRowButtons()
	{
		for(ButtonWidget btn : rowButtons)
		{
			this.remove(btn);
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
				searchField.getText() == null ? "" : searchField.getText();
			java.util.List<ChestEntry.ItemEntry> matches =
				collectMatches(e, query);
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
			Text espLabel = espActive
				? Text.literal("ESP*")
					.styled(style -> style
						.withColor(net.minecraft.util.Formatting.GOLD))
				: Text.literal("ESP");
			ButtonWidget espBtn = ButtonWidget.builder(espLabel, b -> {
				try
				{
					String dimLocal = normalizeDimension(e.dimension);
					// Use this entry's clicked position (the block the
					// player actually clicked when recording) so ESP draws
					// on the expected chest half.
					BlockPos useMin = e.getClickedPos();
					boolean exists = false;
					if(WurstClient.MC != null && WurstClient.MC.world != null)
					{
						var world = WurstClient.MC.world;
						var state = world.getBlockState(useMin);
						boolean container = state != null && (state
							.getBlock() instanceof net.minecraft.block.ChestBlock
							|| state
								.getBlock() instanceof net.minecraft.block.BarrelBlock
							|| state
								.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock
							|| state
								.getBlock() instanceof net.minecraft.block.DecoratedPotBlock
							|| state
								.getBlock() instanceof net.minecraft.block.EnderChestBlock);
						boolean hasBe = world.getBlockEntity(useMin) != null;
						exists = container && hasBe;
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
						onSearchChanged(searchField.getText());
						client.execute(this::refreshPins);
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
						.toggle(dimLocal, useMin, useMin, hack.getEspTimeMs());
					client.execute(this::refreshPins);
				}catch(Throwable ignored)
				{}
			}).dimensions(0, btnY, 40, 16).build();
			if(espActive)
				espBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip
					.of(Text.literal("ESP active")));
			// position esp and wp buttons to the right side of the result box
			int boxRight = x + 340;
			int wpWidth = 56;
			int espWidth = 40;
			int deleteWidth = 56;
			// Stack buttons vertically at the right edge (ESP, Waypoint,
			// Delete)
			int stackWidth = Math.max(Math.max(wpWidth, espWidth), deleteWidth);
			int stackRight = boxRight - 6; // 6px padding from box edge
			int stackX = stackRight - stackWidth;
			// place esp at top, wp below, delete below that
			espBtn.setPosition(stackX + (stackWidth - espWidth) / 2, btnY);
			addDrawableChild(espBtn);
			rowButtons.add(espBtn);
			boolean hasWp = waypointActive;
			Text wpLabel = hasWp
				? Text.literal("Remove*")
					.styled(style -> style
						.withColor(net.minecraft.util.Formatting.GOLD))
				: Text.literal("Waypoint");
			ButtonWidget wpBtn = ButtonWidget.builder(wpLabel, b -> {
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
						client.execute(this::refreshPins);
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
					client.execute(this::refreshPins);
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
						client.execute(this::refreshPins);
					});
				}
			}).dimensions(0, btnY, 56, 16).build();
			if(hasWp)
				wpBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip
					.of(Text.literal("Waypoint active")));
			wpBtn.setPosition(stackX + (stackWidth - wpWidth) / 2, btnY + 18);
			addDrawableChild(wpBtn);
			
			// Delete (X) button to remove the recorded chest entry from disk
			ButtonWidget delBtn =
				ButtonWidget.builder(Text.literal("Delete"), b -> {
					try
					{
						BlockPos delPos = e.getClickedPos();
						new ChestManager().removeChest(e.serverIp, e.dimension,
							delPos.getX(), delPos.getY(), delPos.getZ());
					}catch(Throwable ignored)
					{}
					this.chestManager = new ChestManager();
					onSearchChanged(searchField.getText());
					client.execute(this::refreshPins);
				}).dimensions(0, btnY, deleteWidth, 16).build();
			delBtn.setPosition(stackX + (stackWidth - deleteWidth) / 2,
				btnY + 36);
			// hide per-row buttons when their row is outside the visible
			// scrolling region so they don't overlap header/search UI
			boolean rowVisible = btnY >= visibleTop && btnY <= visibleBottom;
			espBtn.visible = rowVisible;
			wpBtn.visible = rowVisible;
			delBtn.visible = rowVisible;
			espBtn.active = rowVisible;
			wpBtn.active = rowVisible;
			delBtn.active = rowVisible;
			delBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip
				.of(Text.literal("Delete entry")));
			addDrawableChild(delBtn);
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
		String query = searchField == null ? "" : searchField.getText();
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
	public boolean mouseClicked(Click context, boolean doubleClick)
	{
		double mouseX = context.x();
		double mouseY = context.y();
		int button = context.button();
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
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public boolean mouseDragged(Click context, double deltaX, double deltaY)
	{
		double mouseY = context.y();
		int button = context.button();
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
		return super.mouseDragged(context, deltaX, deltaY);
	}
	
	@Override
	public boolean mouseReleased(Click context)
	{
		if(context.button() == 0 && draggingScrollbar)
		{
			draggingScrollbar = false;
			return true;
		}
		return super.mouseReleased(context);
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
		onSearchChanged(searchField.getText());
	}
	
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta)
	{
		context.fill(0, 0, this.width, this.height, 0x88000000);
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Chest Search"), this.width / 2, 4, 0xFFFFFFFF);
		int mid = this.width / 2;
		int sfX = mid - 150;
		int sfY = 18;
		context.fill(sfX - 2, sfY - 2, sfX + 222, sfY + 22, 0xFF333333);
		int summaryY = sfY + 24;
		context.fill(sfX - 2, summaryY - 2, sfX + 360, summaryY + 18,
			0xFF222222);
		// draw result panels BEFORE super.render so buttons draw on top
		int x = this.width / 2 - 150;
		int visibleTop = getResultsTop();
		int visibleBottom = getVisibleBottom();
		int visibleHeight = Math.max(0, visibleBottom - visibleTop);
		String q = searchField.getText() == null ? ""
			: searchField.getText().toLowerCase();
		int contentHeight = calculateContentHeight(q);
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
				java.util.List<String> lines = wrapText(header, availWidth);
				for(int i = 0; i < lines.size(); i++)
					context.drawText(this.textRenderer,
						Text.literal(lines.get(i)), x, headerY + i * 12,
						0xFFF8D866, false);
				// Omit the small "Active: ESP/Waypoint" status line to
				// reduce clutter and avoid overlap with the location text.
			}else
			{
				String header = locationLabel
					+ (totalCount > 0 ? ("  x" + totalCount) : "");
				java.util.List<String> lines = wrapText(header, availWidth);
				for(int i = 0; i < lines.size(); i++)
					context.drawText(this.textRenderer,
						Text.literal(lines.get(i)), x, headerY + i * 12,
						0xFFFFFFFF, false);
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
				context.drawText(this.textRenderer, Text.literal(msg), x,
					lineY + 2, 0xFFBBBBBB, false);
				lineY += 18;
			}else
			{
				for(ChestEntry.ItemEntry it : matches)
				{
					try
					{
						net.minecraft.util.Identifier id =
							net.minecraft.util.Identifier.tryParse(it.itemId);
						if(id != null)
						{
							net.minecraft.item.Item item =
								net.minecraft.registry.Registries.ITEM.get(id);
							net.minecraft.item.ItemStack stack =
								new net.minecraft.item.ItemStack(item, 1);
							context.drawItem(stack, x + 2, lineY - 2);
						}
					}catch(Throwable ignored)
					{}
					String name =
						(it.displayName != null ? it.displayName : it.itemId);
					String line =
						name + " x" + it.count + " (slot " + it.slot + ")";
					context.drawText(this.textRenderer, Text.literal(line),
						x + 20, lineY + 2, 0xFFEFEFEF, false);
					lineY += 18;
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
		String summary =
			"Showing " + shown + "/" + totalMatches + limiter + " - Tracking "
				+ totalChestsLogged + " chests, " + totalItemsLogged + " items";
		context.drawText(this.textRenderer, Text.literal(summary), sfX,
			summaryY + 2, 0xFFCCCCCC, false);
		
		if(shown == 0)
		{
			String msg = totalChestsLogged > 0
				? "No chests match this search. Tracking " + totalChestsLogged
					+ " chests with " + totalItemsLogged + " items."
				: "No chests recorded yet.";
			context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal(msg), this.width / 2, this.height / 2, 0xFFAAAAAA);
		}
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
		String q = query == null ? "" : query.toLowerCase();
		java.util.List<ChestEntry.ItemEntry> matches =
			new java.util.ArrayList<>();
		if(entry.items == null)
			return matches;
		for(ChestEntry.ItemEntry it : entry.items)
		{
			if(it == null)
				continue;
			boolean matched = false;
			if(q.isEmpty())
				matched = true;
			if(!matched && it.itemId != null
				&& it.itemId.toLowerCase().contains(q))
				matched = true;
			if(!matched && it.displayName != null
				&& it.displayName.toLowerCase().contains(q))
				matched = true;
			// Also search NBT (full ItemStack data) so enchantments on
			// books/gear
			// are searchable if full NBT was recorded.
			if(!matched && it.nbt != null)
			{
				String n = it.nbt.toString().toLowerCase();
				if(n.contains(q))
					matched = true;
			}
			if(matched)
				matches.add(it);
		}
		return matches;
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
	
	private java.util.List<String> wrapText(String text, int maxWidth)
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		if(text == null || text.isEmpty())
		{
			return lines;
		}
		String remaining = text;
		for(int line = 0; line < 2 && !remaining.isEmpty(); line++)
		{
			if(this.textRenderer.getWidth(remaining) <= maxWidth)
			{
				lines.add(remaining);
				break;
			}
			// find split point at last space that fits
			int cut = remaining.length();
			while(cut > 0 && this.textRenderer
				.getWidth(remaining.substring(0, cut)) > maxWidth)
				cut = remaining.lastIndexOf(' ', Math.max(0, cut - 1));
			if(cut <= 0)
			{
				// can't find space - hard cut
				int pos = 1;
				while(pos < remaining.length() && this.textRenderer
					.getWidth(remaining.substring(0, pos)) <= maxWidth)
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
	
	@Override
	public void resize(net.minecraft.client.MinecraftClient client, int width,
		int height)
	{
		super.resize(client, width, height);
		clampScroll();
		rebuildRowButtons();
	}
}
