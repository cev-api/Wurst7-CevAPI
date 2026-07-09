/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.oppstats;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.hacks.OppStatsHack;
import net.wurstclient.hacks.OppStatsHack.OppRecord;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import org.lwjgl.glfw.GLFW;

public final class OppStatsScreen extends Screen
{
	private static final ConcurrentMap<UUID, Identifier> mojangSkins =
		new ConcurrentHashMap<>();
	private static final ConcurrentHashMap.KeySetView<UUID, Boolean> loadingMojangSkins =
		ConcurrentHashMap.newKeySet();
	
	private final Screen previous;
	private final OppStatsHack hack;
	private OppList list;
	private EditBox searchBox;
	private Button onlineButton;
	private Button historicalButton;
	private Button copyButton;
	private Button copyEventsButton;
	private boolean showOnline = true;
	private String searchQuery = "";
	private long nextReloadAt;
	private int infoScroll;
	private int infoMaxScroll;
	private int infoPanelX;
	private int infoPanelY;
	private int infoPanelW;
	private int infoPanelH;
	
	public OppStatsScreen(Screen previous, OppStatsHack hack)
	{
		super(Component.empty());
		this.previous = previous;
		this.hack = hack;
	}
	
	@Override
	protected void init()
	{
		int top = 68;
		int bottomPad = 44;
		int listHeight = height - top - bottomPad;
		int leftPanelX = 16;
		int leftPanelW = Math.min(440, Math.max(220, width / 2 - 36));
		list = new OppList(Minecraft.getInstance(), width / 2 - 20, listHeight,
			top, 24, hack, showOnline, searchQuery);
		addWidget(list);
		
		addRenderableWidget(
			onlineButton = Button.builder(Component.literal("Online"), b -> {
				showOnline = true;
				list.reload(showOnline, searchQuery);
				updateModeButtonLabels();
			}).bounds(16, 12, 100, 20).build());
		addRenderableWidget(historicalButton =
			Button.builder(Component.literal("Historical"), b -> {
				showOnline = false;
				list.reload(showOnline, searchQuery);
				updateModeButtonLabels();
			}).bounds(122, 12, 110, 20).build());
		searchBox = new EditBox(font, leftPanelX, 36, leftPanelW, 18,
			Component.literal("Search"));
		searchBox.setHint(Component.literal("Search players..."));
		searchBox.setValue(searchQuery);
		searchBox.setResponder(value -> {
			searchQuery = value == null ? "" : value.trim();
			if(list != null)
				list.reload(showOnline, searchQuery);
		});
		addRenderableWidget(searchBox);
		copyButton = addRenderableWidget(Button
			.builder(Component.literal("Copy Profile"), b -> copySelected())
			.bounds(width - 360, height - 32, 110, 20).build());
		copyEventsButton = addRenderableWidget(
			Button.builder(Component.literal("Copy Events"), b -> copyEvents())
				.bounds(width - 242, height - 32, 110, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(width - 120, height - 32, 100, 20).build());
		updateModeButtonLabels();
	}
	
	@Override
	public void tick()
	{
		super.tick();
		if(System.currentTimeMillis() >= nextReloadAt)
		{
			list.reload(showOnline, searchQuery);
			nextReloadAt = System.currentTimeMillis() + 700L;
		}
		updateModeButtonLabels();
		boolean hasSelection = list.getSingleSelected() != null;
		copyButton.active = hasSelection;
		copyEventsButton.active = hasSelection;
	}
	
	private void updateModeButtonLabels()
	{
		if(onlineButton != null)
			onlineButton.setMessage(Component
				.literal("Online (" + hack.getOnlineRecords().size() + ")"));
		if(historicalButton != null)
			historicalButton.setMessage(Component.literal(
				"Historical (" + hack.getHistoricalRecords().size() + ")"));
	}
	
	private void copySelected()
	{
		OppRecord rec = list.getSingleSelected();
		if(rec == null)
			return;
		minecraft.keyboardHandler.setClipboard(hack.formatForClipboard(rec));
		ChatUtils.message("Copied OppStats profile for " + rec.name + ".");
	}
	
	private void copyEvents()
	{
		OppRecord rec = list.getSingleSelected();
		if(rec == null)
			return;
		minecraft.keyboardHandler.setClipboard(String.join("\n", rec.events));
		ChatUtils.message("Copied event log for " + rec.name + ".");
	}
	
	@Override
	public void onClose()
	{
		minecraft.gui.setScreen(previous);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		if(list != null && list.mouseClicked(event, doubleClick))
		{
			setFocused(list);
			return true;
		}
		
		return super.mouseClicked(event, doubleClick);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(searchBox == null || !searchBox.isFocused())
		{
			if(context.key() == GLFW.GLFW_KEY_UP)
				return list != null && list.moveSelection(-1);
			if(context.key() == GLFW.GLFW_KEY_DOWN)
				return list != null && list.moveSelection(1);
		}
		
		if(super.keyPressed(context))
			return true;
		
		if(list != null && list.keyPressed(context))
			return true;
		return false;
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		list.extractRenderState(context, mouseX, mouseY, partialTicks);
		context.centeredText(font, "OppStats", width / 2, 12, 0xFFFFFFFF);
		for(var r : renderables)
			r.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		OppRecord selected = list.getSingleSelected();
		if(selected == null)
			return;
		
		int panelX = width / 2 + 8;
		int panelW = width / 2 - 20;
		drawModelPanel(context, selected, panelX, 40);
		int equipmentY = 40;
		int equipmentH = drawEquipmentPanel(context, selected, panelX + 94,
			equipmentY, panelW - 96, mouseX, mouseY);
		int infoY = equipmentY + equipmentH + 8;
		drawInfoPanel(context, selected, panelX, infoY, panelW);
	}
	
	private void drawModelPanel(GuiGraphicsExtractor context,
		OppRecord selected, int x, int y)
	{
		Identifier skin = resolveSkin(selected.uuid);
		RenderUtils.fill2D(context, x, y, x + 86, y + 120, 0x55000000);
		RenderUtils.drawBorder2D(context, x, y, x + 86, y + 120, 0x88808080);
		
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 28, y + 7, 8, 8,
			30, 30, 8, 8, 64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 28, y + 7, 40, 8,
			30, 30, 8, 8, 64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 31, y + 37, 20, 20,
			24, 36, 8, 12, 64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 19, y + 38, 44, 20,
			12, 34, 4, 12, 64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 55, y + 38, 44, 20,
			12, 34, 4, 12, 64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 31, y + 73, 4, 20,
			12, 20, 4, 12, 64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 43, y + 73, 4, 20,
			12, 20, 4, 12, 64, 64, 0xFFFFFFFF);
		
	}
	
	private int drawEquipmentPanel(GuiGraphicsExtractor context,
		OppRecord selected, int x, int y, int w, int mouseX, int mouseY)
	{
		int panelH = 254;
		RenderUtils.fill2D(context, x, y, x + w, y + panelH, 0x55000000);
		RenderUtils.drawBorder2D(context, x, y, x + w, y + panelH, 0x88808080);
		
		SlotView[] slots = new SlotView[]{new SlotView("Head", selected.helmet),
			new SlotView("Chest", selected.chest),
			new SlotView("Legs", selected.legs),
			new SlotView("Feet", selected.boots),
			new SlotView("Main", selected.mainHand),
			new SlotView("Off", selected.offHand)};
		
		int rowH = 40;
		for(int i = 0; i < slots.length; i++)
		{
			int rowY = y + 8 + i * rowH;
			int iconX = x + 40;
			int labelX = x + 8;
			context.text(font, slots[i].label, labelX, rowY + 11, 0xFFD0E0FF,
				false);
			ItemStack stack = parseStackIdToDisplay(slots[i].raw);
			if(!stack.isEmpty())
				RenderUtils.drawItem(context, stack, iconX, rowY + 6, true);
			String name = extractItemName(slots[i].raw);
			String durability = durabilityOnly(slots[i].raw);
			String nameLine = name.equals("N/A") ? "N/A"
				: name + (durability.isBlank() ? "" : "  |  " + durability);
			String enchantText = enchantsOnly(slots[i].raw);
			boolean hasEnchants = enchantText != null && !enchantText.isBlank()
				&& !enchantText.equals("N/A") && !enchantText.equals("none");
			int contentX = iconX + 32;
			int contentW = w - (contentX - x) - 8;
			int nameY = hasEnchants ? rowY : rowY + 11;
			drawTrimmed(context, nameLine, contentX, nameY, contentW,
				0xFFE0E0E0);
			drawEnchantLines(context, enchantText, contentX, rowY + 11,
				contentW, 2);
			if(mouseX >= iconX && mouseX <= iconX + 18 && mouseY >= rowY + 6
				&& mouseY <= rowY + 24)
				showSlotTooltip(context, slots[i], mouseX, mouseY);
			else if(mouseX >= contentX && mouseX <= contentX + contentW
				&& mouseY >= rowY && mouseY <= rowY + rowH)
				showSlotTooltip(context, slots[i], mouseX, mouseY);
		}
		return panelH;
	}
	
	private void showSlotTooltip(GuiGraphicsExtractor context, SlotView slot,
		int mouseX, int mouseY)
	{
		ArrayList<Component> lines = new ArrayList<>();
		String name = extractItemName(slot.raw);
		lines.add(Component.literal(slot.label + ": " + name));
		String dur = durabilityOnly(slot.raw);
		if(!dur.isBlank())
			lines.add(Component.literal(dur));
		String ench = enchantsOnly(slot.raw);
		if(ench.equals("N/A") || ench.equals("none") || ench.isBlank())
			lines.add(Component.literal("No enchantments"));
		else
			for(String part : ench.split("\\s*,\\s*"))
				if(!part.isBlank())
					lines.add(Component.literal(part.trim()));
		context.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
	}
	
	private void drawInfoPanel(GuiGraphicsExtractor context, OppRecord selected,
		int x, int y, int w)
	{
		infoPanelX = x;
		infoPanelY = y;
		infoPanelW = w;
		infoPanelH = height - 44 - y;
		RenderUtils.fill2D(context, x, y, x + w, y + infoPanelH, 0x44000000);
		RenderUtils.drawBorder2D(context, x, y, x + w, y + infoPanelH,
			0x66808080);
		
		ArrayList<InfoLine> lines = new ArrayList<>();
		lines.add(new InfoLine("Identity", true));
		lines.add(new InfoLine("Name: " + selected.name, false));
		lines.add(new InfoLine("UUID: " + selected.uuid, false));
		lines.add(
			new InfoLine("Last seen: " + hack.formatLastSeen(selected), false));
		lines.add(new InfoLine(
			"Last join: " + hack.formatEpoch(selected.lastJoinAt), false));
		lines.add(new InfoLine(
			"Last leave: " + hack.formatEpoch(selected.lastLeaveAt), false));
		lines.add(new InfoLine("", false));
		lines.add(new InfoLine("Location", true));
		lines.add(new InfoLine("Pos: " + formatPos(selected) + " Dist: "
			+ formatDistance(selected), false));
		lines.add(new InfoLine("", false));
		lines.add(new InfoLine("Status", true));
		lines.add(new InfoLine("HP: " + fmt(selected.health) + "  Abs: "
			+ fmt(selected.absorption) + "  Armor: " + na(selected.armorValue)
			+ "  Ping: " + na(getLivePing(selected)), false));
		lines.add(new InfoLine("Gamemode: " + na(selected.gamemode), false));
		lines.add(new InfoLine("", false));
		lines.add(new InfoLine("Stats", true));
		lines.add(new InfoLine("Joins: " + selected.joinCount, false));
		lines.add(new InfoLine("", false));
		lines.add(new InfoLine("Recent observed items", true));
		if(selected.recentItems.isEmpty())
			lines.add(new InfoLine("N/A", false));
		else
			for(String item : selected.recentItems)
				lines.add(new InfoLine(item, false));
		lines.add(new InfoLine("", false));
		lines.add(new InfoLine("Recent events", true));
		for(String event : selected.events)
			lines.add(new InfoLine(event, false));
		
		int lineHeight = 11;
		int totalHeight = lines.size() * lineHeight + 6;
		infoMaxScroll = Math.max(0, totalHeight - (infoPanelH - 8));
		if(infoScroll > infoMaxScroll)
			infoScroll = infoMaxScroll;
		
		context.enableScissor(x + 2, y + 2, x + w - 2, y + infoPanelH - 2);
		int lineY = y + 6 - infoScroll;
		for(InfoLine line : lines)
		{
			if(lineY > y - lineHeight && lineY < y + infoPanelH - 2)
			{
				if(line.title)
					drawSectionTitle(context, line.text, x + 6, lineY);
				else if(!line.text.isBlank())
					drawLine(context, line.text, x + 6, lineY, w - 12);
			}
			lineY += lineHeight;
		}
		context.disableScissor();
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
		double scrollY)
	{
		if(mouseX >= infoPanelX && mouseX <= infoPanelX + infoPanelW
			&& mouseY >= infoPanelY && mouseY <= infoPanelY + infoPanelH
			&& infoMaxScroll > 0)
		{
			infoScroll -= (int)Math.round(scrollY * 18.0);
			if(infoScroll < 0)
				infoScroll = 0;
			if(infoScroll > infoMaxScroll)
				infoScroll = infoMaxScroll;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
	
	private void drawLine(GuiGraphicsExtractor context, String text, int x,
		int y, int maxWidth)
	{
		if(font.width(text) <= maxWidth)
		{
			context.text(font, text, x, y, 0xFFE0E0E0, false);
			return;
		}
		RenderUtils.drawScaledText(context, font, text, x, y, 0xFFE0E0E0, false,
			0.82);
	}
	
	private void drawTrimmed(GuiGraphicsExtractor context, String text, int x,
		int y, int maxW, int color)
	{
		if(text == null || text.isBlank() || text.equals("N/A")
			|| text.equals("none"))
			return;
		String t = text;
		while(font.width(t) > maxW && t.length() > 3)
			t = t.substring(0, t.length() - 1);
		if(!t.equals(text))
			t = t.substring(0, Math.max(1, t.length() - 1)) + "…";
		context.text(font, t, x, y, color, false);
	}
	
	private void drawEnchantLines(GuiGraphicsExtractor context, String enchants,
		int x, int y, int maxW, int maxLines)
	{
		enchants = normalizeEnchantText(enchants);
		if(enchants == null || enchants.isBlank() || enchants.equals("N/A")
			|| enchants.equals("none"))
			return;
		
		String[] parts = enchants.split("\\s*,\\s*");
		int line = 0;
		for(String part : parts)
		{
			if(part == null || part.isBlank())
				continue;
			if(line >= maxLines)
			{
				drawTrimmed(context, "+" + (parts.length - line) + " more", x,
					y + line * 9, maxW, 0xFFB8FFB8);
				return;
			}
			drawTrimmed(context, part.trim(), x, y + line * 9, maxW,
				0xFF96FF96);
			line++;
		}
	}
	
	private void drawSectionTitle(GuiGraphicsExtractor context, String title,
		int x, int y)
	{
		context.text(font, title, x, y, 0xFFD0E0FF, false);
	}
	
	private ItemStack parseStackIdToDisplay(String text)
	{
		if(text == null || text.equals("N/A"))
			return ItemStack.EMPTY;
		int idStart = text.indexOf("id=");
		if(idStart < 0)
			return ItemStack.EMPTY;
		int idEnd = text.indexOf(',', idStart);
		String id = idEnd < 0 ? text.substring(idStart + 3)
			: text.substring(idStart + 3, idEnd);
		try
		{
			var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getValue(Identifier.parse(id.trim()));
			return item == null ? ItemStack.EMPTY : new ItemStack(item);
		}catch(Exception e)
		{
			return ItemStack.EMPTY;
		}
	}
	
	private String extractItemName(String stackText)
	{
		if(stackText == null || stackText.equals("N/A"))
			return "N/A";
		int cut = stackText.indexOf(" x");
		return cut > 0 ? stackText.substring(0, cut).trim() : stackText;
	}
	
	private String enchantsOnly(String stackText)
	{
		if(stackText == null || stackText.equals("N/A"))
			return "N/A";
		int i = stackText.indexOf("enchants=");
		if(i < 0)
			return "N/A";
		return normalizeEnchantText(
			stackText.substring(i + "enchants=".length()).trim());
	}
	
	private String normalizeEnchantText(String text)
	{
		if(text == null)
			return null;
		
		String normalized = text.trim();
		while(normalized.endsWith("]"))
			normalized =
				normalized.substring(0, normalized.length() - 1).trim();
		return normalized;
	}
	
	private String durabilityOnly(String stackText)
	{
		if(stackText == null || stackText.equals("N/A"))
			return "";
		int i = stackText.indexOf("durability=");
		if(i < 0)
			return "";
		int end = stackText.indexOf(',', i);
		String v = end < 0 ? stackText.substring(i + "durability=".length())
			: stackText.substring(i + "durability=".length(), end);
		if(v.equalsIgnoreCase("n/a"))
			return "";
		return "Durability: " + v.trim();
	}
	
	private String formatPos(OppRecord r)
	{
		if(r.lastPos == null)
			return "N/A";
		return String.format("(%.2f, %.2f, %.2f)", r.lastPos.x, r.lastPos.y,
			r.lastPos.z);
	}
	
	private String formatDistance(OppRecord r)
	{
		if(Double.isNaN(r.distance))
			return "N/A";
		return String.format("%.2f", r.distance);
	}
	
	private String fmt(float v)
	{
		if(Float.isNaN(v))
			return "N/A";
		return String.format("%.1f", v);
	}
	
	private static String na(int value)
	{
		return value < 0 ? "N/A" : String.valueOf(value);
	}
	
	private String na(String value)
	{
		return value == null || value.isBlank() ? "N/A" : value;
	}
	
	private static int getLivePing(OppRecord rec)
	{
		Minecraft mc = Minecraft.getInstance();
		if(rec == null || rec.uuid == null || mc.getConnection() == null)
			return rec == null ? -1 : rec.ping;
		
		PlayerInfo info = mc.getConnection().getPlayerInfo(rec.uuid);
		if(info != null)
			return info.getLatency();
		
		return rec.ping;
	}
	
	private Identifier resolveSkin(UUID uuid)
	{
		requestMojangSkin(uuid);
		Identifier mojangSkin = mojangSkins.get(uuid);
		if(mojangSkin != null)
			return mojangSkin;
		
		PlayerInfo info = minecraft.getConnection() == null ? null
			: minecraft.getConnection().getPlayerInfo(uuid);
		if(info != null)
			return info.getSkin().body().texturePath();
		
		return DefaultPlayerSkin.get(uuid).body().texturePath();
	}
	
	private static void requestMojangSkin(UUID uuid)
	{
		if(uuid == null || !loadingMojangSkins.add(uuid))
			return;
		
		CompletableFuture.supplyAsync(() -> {
			Minecraft mc = Minecraft.getInstance();
			try
			{
				var result =
					mc.services().sessionService().fetchProfile(uuid, false);
				return result == null ? null : result.profile();
				
			}catch(Exception e)
			{
				return null;
			}
		}).thenCompose(profile -> {
			if(profile == null)
				return CompletableFuture.completedFuture(Optional.empty());
			
			Minecraft mc = Minecraft.getInstance();
			return mc.getSkinManager().get((GameProfile)profile);
		}).thenAccept(optSkin -> {
			optSkin.ifPresent(
				skin -> mojangSkins.put(uuid, skin.body().texturePath()));
			loadingMojangSkins.remove(uuid);
		}).exceptionally(error -> {
			loadingMojangSkins.remove(uuid);
			return null;
		});
	}
	
	private record SlotView(String label, String raw)
	{}
	
	private record InfoLine(String text, boolean title)
	{}
	
	private static final class OppList
		extends MultiSelectEntryListWidget<OppList.Entry>
	{
		private final OppStatsHack hack;
		private boolean showOnline;
		
		public OppList(Minecraft mc, int width, int height, int top,
			int itemHeight, OppStatsHack hack, boolean showOnline,
			String searchQuery)
		{
			super(mc, width, height, top, itemHeight);
			this.hack = hack;
			this.showOnline = showOnline;
			reload(showOnline, searchQuery);
			ensureSelection();
		}
		
		void reload(boolean showOnline, String searchQuery)
		{
			this.showOnline = showOnline;
			var prev = captureState();
			clearEntries();
			List<OppRecord> src = showOnline ? hack.getOnlineRecords()
				: hack.getHistoricalRecords();
			String query = searchQuery == null ? ""
				: searchQuery.trim().toLowerCase(Locale.ROOT);
			for(OppRecord rec : src)
			{
				if(!query.isBlank() && !matchesSearch(rec, query))
					continue;
				addEntry(new Entry(this, rec));
			}
			if(!children().isEmpty())
				restoreState(prev);
		}
		
		OppRecord getSingleSelected()
		{
			var selected = getSelectedEntries();
			if(selected.isEmpty())
				return null;
			return selected.get(0).record;
		}
		
		boolean moveSelection(int delta)
		{
			if(delta == 0)
				return false;
			
			List<Entry> entries = children();
			if(entries.isEmpty())
				return false;
			
			Entry selected = getSelected();
			int index = entries.indexOf(selected);
			if(index < 0)
				index = delta > 0 ? -1 : entries.size();
			
			int next = Math.max(0, Math.min(entries.size() - 1, index + delta));
			if(next == index)
				return false;
			
			Entry entry = entries.get(next);
			onEntryClicked(entry, false, false);
			double targetScroll =
				Math.max(0, Math.min(maxScrollAmount(), next * 24.0));
			setScrollAmount(targetScroll);
			return true;
		}
		
		@Override
		protected String getSelectionKey(Entry entry)
		{
			return entry.record.uuid.toString();
		}
		
		@Override
		public int getRowWidth()
		{
			return width - 20;
		}
		
		private boolean matchesSearch(OppRecord rec, String query)
		{
			if(rec == null || query == null || query.isBlank())
				return true;
			
			String name =
				rec.name == null ? "" : rec.name.toLowerCase(Locale.ROOT);
			if(name.contains(query))
				return true;
			
			String uuid = rec.uuid == null ? ""
				: rec.uuid.toString().toLowerCase(Locale.ROOT);
			return uuid.contains(query);
		}
		
		private static final class Entry
			extends MultiSelectEntryListWidget.Entry<Entry>
		{
			private final OppRecord record;
			
			public Entry(OppList parent, OppRecord record)
			{
				super(parent);
				this.record = record;
			}
			
			@Override
			public String selectionKey()
			{
				return record.uuid.toString();
			}
			
			@Override
			public Component getNarration()
			{
				return Component.literal(record.name);
			}
			
			@Override
			public void extractContent(GuiGraphicsExtractor context, int mouseX,
				int mouseY, boolean hovered, float tickDelta)
			{
				int x = getContentX();
				int y = getContentY();
				Identifier skin = resolveSkin(record.uuid);
				context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 2, y + 2,
					8, 8, 16, 16, 8, 8, 64, 64, 0xFFFFFFFF);
				context.blit(RenderPipelines.GUI_TEXTURED, skin, x + 2, y + 2,
					40, 8, 16, 16, 8, 8, 64, 64, 0xFFFFFFFF);
				context.text(Minecraft.getInstance().font, record.name, x + 24,
					y + 2, 0xFFFFFFFF, false);
				String pingText = record.online
					? "ping: " + na(getLivePing(record)) + " ms" : "offline";
				context.text(Minecraft.getInstance().font, pingText, x + 24,
					y + 12, record.online ? 0xFF55FF55 : 0xFFFF7777, false);
			}
			
			private Identifier resolveSkin(UUID uuid)
			{
				requestMojangSkin(uuid);
				Identifier mojangSkin = mojangSkins.get(uuid);
				if(mojangSkin != null)
					return mojangSkin;
				
				Minecraft mc = Minecraft.getInstance();
				PlayerInfo info = mc.getConnection() == null ? null
					: mc.getConnection().getPlayerInfo(uuid);
				if(info != null)
					return info.getSkin().body().texturePath();
				
				return DefaultPlayerSkin.get(uuid).body().texturePath();
			}
		}
	}
}
