/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointsManager;
import net.wurstclient.waypoints.WaypointDimension;

public final class WaypointsScreen extends Screen
{
	private final Screen prev;
	private final WaypointsManager manager;
	private java.util.List<Waypoint> cachedList;
	private int listStartY;
	
	// Scrolling support
	private static final int ROW_HEIGHT = 24;
	private int scroll; // 0 at top, grows when scrolling down
	private int viewportTop;
	private int viewportBottom;
	
	// Dimension filter (null = show all)
	private net.wurstclient.waypoints.WaypointDimension filterDim = null;
	
	// One row = 4 buttons. We keep references so we can reposition & toggle
	// visibility.
	private static final class RowWidgets
	{
		Waypoint w;
		ButtonWidget nameBtn;
		ButtonWidget visBtn;
		ButtonWidget delBtn;
		ButtonWidget copyBtn;
	}
	
	private final ArrayList<RowWidgets> rows = new ArrayList<>();
	
	// Persisted scroll per-world+dimension so returning keeps the same
	// position even if a new screen instance was created.
	private static final Map<String, Integer> savedScrolls = new HashMap<>();
	
	private void saveScrollState()
	{
		try
		{
			String key = resolveWorldId() + ":"
				+ (filterDim == null ? "ALL" : filterDim.name());
			savedScrolls.put(key, scroll);
		}catch(Exception ignored)
		{}
	}
	
	public WaypointsScreen(Screen prev, WaypointsManager manager)
	{
		super(Text.literal("Waypoints"));
		this.prev = prev;
		this.manager = manager;
	}
	
	@Override
	protected void init()
	{
		int y = 32;
		int x = this.width / 2 - 150;
		
		rows.clear();
		// preserve scroll so returning to this screen or refreshing doesn't
		// jump the list back to the top
		// scroll = 0;
		
		// Initialize default filter to current world if not set
		if(filterDim == null)
			filterDim = currentDim();
		
		// Filter buttons centered above the create button
		int filterBtnWidth = 64;
		int spacing = 8;
		int totalWidth = filterBtnWidth * 3 + spacing * 2;
		int fx = this.width / 2 - totalWidth / 2;
		int fy = y; // top row for filters
		
		addDrawableChild(ButtonWidget.builder(Text.literal(
			(filterDim == net.wurstclient.waypoints.WaypointDimension.OVERWORLD)
				? "[OW]" : "OW"),
			b -> {
				filterDim =
					net.wurstclient.waypoints.WaypointDimension.OVERWORLD;
				client.setScreen(this);
			}).dimensions(fx, fy, filterBtnWidth, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal(
			(filterDim == net.wurstclient.waypoints.WaypointDimension.NETHER)
				? "[Nether]" : "Nether"),
			b -> {
				filterDim = net.wurstclient.waypoints.WaypointDimension.NETHER;
				client.setScreen(this);
			}).dimensions(fx + filterBtnWidth + spacing, fy, filterBtnWidth, 20)
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal(
			(filterDim == net.wurstclient.waypoints.WaypointDimension.END)
				? "[End]" : "End"),
			b -> {
				filterDim = net.wurstclient.waypoints.WaypointDimension.END;
				client.setScreen(this);
			}).dimensions(fx + (filterBtnWidth + spacing) * 2, fy,
				filterBtnWidth, 20)
			.build());
		
		// Move create button below the filters
		int createY = y + 24; // 20 height + 4px gap
		addDrawableChild(
			ButtonWidget.builder(Text.literal("Create waypoint"), b -> {
				Waypoint w = new Waypoint(java.util.UUID.randomUUID(),
					System.currentTimeMillis());
				w.setName("New Waypoint");
				if(client.player != null)
					w.setPos(BlockPos.ofFloored(client.player.getPos()));
				else
					w.setPos(BlockPos.ORIGIN);
				w.setDimension(currentDim());
				w.setMaxVisible(5000);
				w.setLines(false); // default new waypoints without lines
				client
					.setScreen(new WaypointEditScreen(this, manager, w, true));
			}).dimensions(x, createY, 300, 20).build());
		
		// Xaero integration buttons sit right below the create button
		int toolsY = createY + 24;
		int toolGap = 10;
		int toolWidth = (300 - toolGap) / 2;
		addDrawableChild(
			ButtonWidget.builder(Text.literal("Import Xaero"), b -> {
				importFromXaero();
			}).dimensions(x, toolsY, toolWidth, 20).build());
		addDrawableChild(
			ButtonWidget.builder(Text.literal("Export Xaero"), b -> {
				exportToXaero();
			}).dimensions(x + toolWidth + toolGap, toolsY, toolWidth, 20)
				.build());
		
		// Advance y to start the list below the Xaero buttons (keep previous
		// gap)
		y = toolsY + 28;
		// Cache list for consistent rendering and color boxes
		// Apply dimension filter when building the cached list
		cachedList = new ArrayList<>();
		for(Waypoint w : manager.all())
		{
			if(filterDim == null || w.getDimension() == filterDim)
				cachedList.add(w);
		}
		listStartY = y;
		
		// Determine viewport for the list (space until the Back button line)
		viewportTop = listStartY;
		viewportBottom = this.height - 36; // a bit above back button
		
		// Try to restore a previously saved scroll position for this world+dim
		try
		{
			String key = resolveWorldId() + ":"
				+ (filterDim == null ? "ALL" : filterDim.name());
			Integer s = savedScrolls.get(key);
			if(s != null)
				scroll = s;
		}catch(Exception ignored)
		{}
		
		for(int i = 0; i < cachedList.size(); i++)
		{
			Waypoint w = cachedList.get(i);
			int rowY = y + i * ROW_HEIGHT;
			
			ButtonWidget nameBtn = addDrawableChild(
				ButtonWidget.builder(Text.literal(w.getName()), b -> {
					client.setScreen(
						new WaypointEditScreen(this, manager, w, false));
				}).dimensions(x, rowY, 140, 20).build());
			
			ButtonWidget visBtn = addDrawableChild(ButtonWidget
				.builder(Text.literal(w.isVisible() ? "Hide" : "Show"), b -> {
					w.setVisible(!w.isVisible());
					manager.addOrUpdate(w);
					saveNow();
					// Refresh in-place without stacking a new screen
					client.setScreen(this);
				}).dimensions(x + 145, rowY, 55, 20).build());
			
			ButtonWidget delBtn = addDrawableChild(
				ButtonWidget.builder(Text.literal("Delete"), b -> {
					manager.remove(w);
					saveNow();
					// Refresh in-place without stacking a new screen
					client.setScreen(this);
				}).dimensions(x + 205, rowY, 55, 20).build());
			
			ButtonWidget copyBtn = addDrawableChild(
				ButtonWidget.builder(Text.literal("Copy"), b -> {
					String s = w.getPos().getX() + ", " + w.getPos().getY()
						+ ", " + w.getPos().getZ();
					client.keyboard.setClipboard(s);
				}).dimensions(x + 265, rowY, 35, 20).build());
			
			RowWidgets rw = new RowWidgets();
			rw.w = w;
			rw.nameBtn = nameBtn;
			rw.visBtn = visBtn;
			rw.delBtn = delBtn;
			rw.copyBtn = copyBtn;
			rows.add(rw);
		}
		
		// Clamp scroll so it stays within valid bounds after rebuilding the
		// list
		int contentHeight = rows.size() * ROW_HEIGHT;
		int maxScroll =
			Math.max(0, contentHeight - (viewportBottom - viewportTop));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		// Persist the (possibly adjusted) scroll position
		saveScrollState();
		// Scroll buttons (▲ / ▼) positioned just to the right of the 300px list
		// area
		int arrowX = x + 305; // a little to the right of the list
		// "Top" button above the up-arrow that jumps directly to the top
		addDrawableChild(ButtonWidget.builder(Text.literal("▲▲"), b -> {
			scrollToTop();
		}).dimensions(arrowX + 20, Math.max(0, viewportTop - 24), 20, 20)
			.build());
		// Up arrow (move up by a few rows)
		addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> {
			scrollBy(-ROW_HEIGHT * 3);
		}).dimensions(arrowX + 20, viewportTop, 20, 20).build());
		// Down arrow (move down by a few rows)
		addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> {
			scrollBy(ROW_HEIGHT * 3);
		}).dimensions(arrowX + 20, viewportBottom - 20, 20, 20).build());
		// "Bottom" button below the down-arrow that jumps directly to bottom
		addDrawableChild(ButtonWidget.builder(Text.literal("▼▼"), b -> {
			scrollToBottom();
		}).dimensions(arrowX + 20, viewportBottom, 20, 20).build());
		
		addDrawableChild(ButtonWidget
			.builder(Text.literal("Back"), b -> client.setScreen(prev))
			.dimensions(x, this.height - 28, 300, 20).build());
	}
	
	private void scrollBy(int dy)
	{
		int contentHeight = rows.size() * ROW_HEIGHT;
		int maxScroll =
			Math.max(0, contentHeight - (viewportBottom - viewportTop));
		scroll = Math.max(0, Math.min(scroll + dy, maxScroll));
		saveScrollState();
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		int step = (int)Math.signum(verticalAmount) * ROW_HEIGHT * 2;
		if(step != 0)
		{
			scrollBy(-step); // invert so wheel up moves content up
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
			verticalAmount);
	}
	
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta)
	{
		// No blur - just a translucent background
		context.fill(0, 0, this.width, this.height, 0x88000000);
		
		// Update row positions & visibility before widgets are rendered
		int x = this.width / 2 - 150;
		viewportTop = listStartY;
		viewportBottom = this.height - 36;
		// viewport height used for clipping below
		
		for(int i = 0; i < rows.size(); i++)
		{
			RowWidgets rw = rows.get(i);
			int rowY = listStartY + i * ROW_HEIGHT - scroll;
			
			boolean visible =
				rowY >= viewportTop && rowY <= viewportBottom - 20;
			// Reposition
			rw.nameBtn.setY(rowY);
			rw.visBtn.setY(rowY);
			rw.delBtn.setY(rowY);
			rw.copyBtn.setY(rowY);
			// Toggle visibility so buttons outside viewport don't render or
			// receive input
			rw.nameBtn.visible = visible;
			rw.visBtn.visible = visible;
			rw.delBtn.visible = visible;
			rw.copyBtn.visible = visible;
		}
		
		super.render(context, mouseX, mouseY, delta);
		
		// Title
		context.drawCenteredTextWithShadow(client.textRenderer, "Waypoints",
			this.width / 2, 12, 0xFFFFFFFF);
		
		// Draw small color boxes for each saved waypoint next to the name
		// Use the same filtered list as the rows to render color boxes and clip
		// to viewport
		int boxLeft = x - 20;
		var liveList = new ArrayList<>(manager.all());
		// If a filter is active, filter the live list too
		if(filterDim != null)
		{
			var tmp = new ArrayList<Waypoint>();
			for(Waypoint w : liveList)
				if(w.getDimension() == filterDim)
					tmp.add(w);
			liveList = tmp;
		}
		
		// Enable scissor so stray boxes outside the list area are not drawn
		int scissorLeft = boxLeft - 2;
		int scissorTop = viewportTop;
		int scissorRight = x + 300 + 20;
		int scissorBottom = viewportBottom;
		context.enableScissor(scissorLeft, scissorTop, scissorRight,
			scissorBottom);
		
		// Draw color boxes based on the rows we've created; only draw visible
		// rows
		for(RowWidgets rw : rows)
		{
			if(rw == null || rw.nameBtn == null || !rw.nameBtn.visible)
				continue;
			int rowY = rw.nameBtn.getY();
			// ensure within viewport
			if(rowY + 16 < viewportTop || rowY > viewportBottom)
				continue;
			int boxY = rowY + 2;
			int color = rw.w.getColor();
			// border
			context.fill(boxLeft - 1, boxY - 1, boxLeft + 17, boxY + 17,
				0xFF333333);
			// fill
			context.fill(boxLeft, boxY, boxLeft + 16, boxY + 16, color);
		}
		
		context.disableScissor();
	}
	
	private WaypointDimension currentDim()
	{
		if(client.world == null)
			return WaypointDimension.OVERWORLD;
		String key = client.world.getRegistryKey().getValue().getPath();
		switch(key)
		{
			case "the_nether":
			return WaypointDimension.NETHER;
			case "the_end":
			return WaypointDimension.END;
			default:
			return WaypointDimension.OVERWORLD;
		}
	}
	
	private String resolveWorldId()
	{
		net.minecraft.client.network.ServerInfo s =
			client.getCurrentServerEntry();
		if(s != null && s.address != null && !s.address.isEmpty())
			return s.address.replace(':', '_');
		return "singleplayer";
	}
	
	private void importFromXaero()
	{
		String worldId = resolveWorldId();
		WaypointsManager.XaeroSyncStats stats =
			manager.importFromXaero(worldId);
		if(stats.imported() > 0 || stats.updated() > 0)
		{
			manager.save(worldId);
			refreshAfterDataChange();
		}
		sendXaeroMessage(importSummary(stats));
	}
	
	private void exportToXaero()
	{
		String worldId = resolveWorldId();
		WaypointsManager.XaeroSyncStats stats = manager.exportToXaero(worldId);
		sendXaeroMessage(exportSummary(stats));
	}
	
	private void refreshAfterDataChange()
	{
		init(client, this.width, this.height);
	}
	
	private void sendXaeroMessage(String message)
	{
		if(message == null || message.isBlank() || client == null)
			return;
		Text text = Text.literal(message);
		if(client.player != null)
			client.player.sendMessage(text, false);
		else if(client.inGameHud != null)
			client.inGameHud.getChatHud().addMessage(text);
	}
	
	private String importSummary(WaypointsManager.XaeroSyncStats stats)
	{
		if(stats.filesTouched().isEmpty() && stats.imported() == 0
			&& stats.updated() == 0 && stats.skipped() == 0)
			return "No Xaero waypoint files found for this world.";
		StringBuilder sb = new StringBuilder("Imported from Xaero: ");
		sb.append(stats.imported()).append(" added");
		sb.append(", ").append(stats.updated()).append(" updated");
		if(stats.skipped() > 0)
			sb.append(" (").append(stats.skipped()).append(" skipped)");
		return sb.toString();
	}
	
	private String exportSummary(WaypointsManager.XaeroSyncStats stats)
	{
		StringBuilder sb = new StringBuilder("Exported to Xaero: ");
		if(stats.exported() > 0)
			sb.append(stats.exported()).append(" waypoints");
		else
			sb.append("no waypoints written");
		if(!stats.filesTouched().isEmpty())
			sb.append(" -> ").append(stats.filesTouched().get(0));
		if(stats.skipped() > 0)
			sb.append(" (").append(stats.skipped()).append(" errors)");
		return sb.toString();
	}
	
	void saveNow()
	{
		manager.save(resolveWorldId());
	}
	
	private void scrollToTop()
	{
		scroll = 0;
		saveScrollState();
	}
	
	private void scrollToBottom()
	{
		int contentHeight = rows.size() * ROW_HEIGHT;
		int maxScroll =
			Math.max(0, contentHeight - (viewportBottom - viewportTop));
		scroll = maxScroll;
		saveScrollState();
	}
}
