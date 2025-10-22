/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.Window;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.HandledScreenAccessor;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;

@SearchTags({"better book handling", "book overlay", "enchanted books"})
public final class BetterBookHandlingHack extends Hack
{
	private static final int MIN_BOX_WIDTH = 120;
	private static final int MAX_BOX_WIDTH = 320;
	private static final int PANEL_PADDING = 6;
	private static final int HEADER_MARGIN = 4;
	private static final int ENTRY_MARGIN = 3;
	private static final int TITLE_COLOR = 0xFFF7F7F7;
	private static final int HEADER_COLOR = 0xFFC8D8FF;
	private static final int ENTRY_COLOR = 0xFFEDEDED;
	private static final int ENTRY_HOVER_COLOR = 0xFFFFE7A9;
	private static final int ACTION_COLOR = 0xFF86C5FF;
	private static final int ACTION_HOVER_COLOR = 0xFFB6E1FF;
	private static final long HOVER_SCROLL_DELAY_MS = 400;
	private static final double HOVER_SCROLL_PAUSE = 32.0;
	
	private final List<BookEntry> entries = new ArrayList<>();
	private final Map<BookCategory, List<BookEntry>> groupedEntries =
		new LinkedHashMap<>();
	private final List<Hitbox> hitboxes = new ArrayList<>();
	
	private final SliderSetting boxWidth = new SliderSetting("Box width", 180,
		MIN_BOX_WIDTH, MAX_BOX_WIDTH, 2, ValueDisplay.INTEGER);
	private final SliderSetting boxHeight = new SliderSetting("Box height",
		"Panel height in pixels.\n0 = match the container height.", 0, 0, 320,
		2, ValueDisplay.INTEGER);
	private final SliderSetting offsetX = new SliderSetting("Horizontal offset",
		"Moves the panel left/right relative to the container.", 0, -300, 300,
		2, ValueDisplay.INTEGER);
	private final SliderSetting offsetY = new SliderSetting("Vertical offset",
		"Moves the panel up/down relative to the container.", 0, -200, 200, 2,
		ValueDisplay.INTEGER);
	private final SliderSetting textScale = new SliderSetting("Text scale", 0.7,
		0.5, 1.25, 0.05, ValueDisplay.DECIMAL);
	private final SliderSetting hoverScrollSpeed = new SliderSetting(
		"Hover scroll speed", "Pixels per second when hovering long entries.",
		25, 5, 80, 1, ValueDisplay.INTEGER);
	
	private double scrollOffset;
	private double maxScroll;
	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;
	private boolean lastRenderActive;
	private boolean needsRescan = true;
	private int contentHeight;
	private int hoveredSlotId = -1;
	private long hoverStartMs;
	
	public BetterBookHandlingHack()
	{
		super("BetterBookHandling");
		
		setCategory(Category.ITEMS);
		addSetting(boxWidth);
		addSetting(boxHeight);
		addSetting(offsetX);
		addSetting(offsetY);
		addSetting(textScale);
		addSetting(hoverScrollSpeed);
		
		for(BookCategory category : BookCategory.ORDERED)
			groupedEntries.put(category, new ArrayList<>());
	}
	
	@Override
	protected void onDisable()
	{
		scrollOffset = 0;
		contentHeight = 0;
		maxScroll = 0;
		lastRenderActive = false;
		needsRescan = true;
		entries.clear();
		groupedEntries.values().forEach(List::clear);
		hitboxes.clear();
		hoveredSlotId = -1;
		hoverStartMs = 0L;
	}
	
	public void renderOnHandledScreen(HandledScreen<?> screen,
		DrawContext context, float partialTicks)
	{
		lastRenderActive = false;
		
		if(!(screen instanceof GenericContainerScreen generic))
			return;
		
		if(MC.player == null || MC.interactionManager == null)
			return;
		
		if(needsRescan)
			rescan(generic);
		else
			refreshIfDirty(generic);
		
		if(entries.isEmpty())
		{
			hitboxes.clear();
			hoveredSlotId = -1;
			hoverStartMs = 0L;
			return;
		}
		
		HandledScreenAccessor accessor = (HandledScreenAccessor)screen;
		int windowWidth = context.getScaledWindowWidth();
		int windowHeight = context.getScaledWindowHeight();
		
		panelWidth = MathHelper.clamp(boxWidth.getValueI(), MIN_BOX_WIDTH,
			Math.min(MAX_BOX_WIDTH, windowWidth - 10));
		
		int configuredHeight = boxHeight.getValueI();
		if(configuredHeight <= 0)
			panelHeight =
				Math.min(accessor.getBackgroundHeight(), windowHeight - 10);
		else
			panelHeight = MathHelper.clamp(configuredHeight, 60,
				Math.max(60, windowHeight - 10));
		
		panelY = accessor.getY() + offsetY.getValueI();
		panelX =
			accessor.getX() - panelWidth - PANEL_PADDING + offsetX.getValueI();
		
		panelX = MathHelper.clamp(panelX, 2 - panelWidth,
			windowWidth - panelWidth - 2);
		panelY = MathHelper.clamp(panelY, 2, windowHeight - panelHeight - 2);
		
		renderOverlay(context);
		lastRenderActive = true;
	}
	
	public boolean handleMouseClick(HandledScreen<?> screen, double mouseX,
		double mouseY, int button)
	{
		if(!lastRenderActive || !(screen instanceof GenericContainerScreen))
			return false;
		
		if(button != 0 && button != 1)
			return isInsidePanel(mouseX, mouseY);
		
		for(Hitbox hitbox : hitboxes)
		{
			if(!hitbox.contains(mouseX, mouseY))
				continue;
			
			if(hitbox.category != null)
			{
				if(button == 0 || button == 1)
				{
					takeCategory(hitbox.category,
						((GenericContainerScreen)screen).getScreenHandler());
					return true;
				}
				continue;
			}
			
			if(hitbox.entry == null)
				continue;
			
			if(button == 0)
			{
				takeEntry(hitbox.entry,
					((GenericContainerScreen)screen).getScreenHandler());
				return true;
			}
			
			if(button == 1)
			{
				takeCategory(hitbox.entry.category,
					((GenericContainerScreen)screen).getScreenHandler());
				return true;
			}
		}
		
		return isInsidePanel(mouseX, mouseY);
	}
	
	public boolean handleMouseScroll(HandledScreen<?> screen, double mouseX,
		double mouseY, double amount)
	{
		if(!lastRenderActive || !(screen instanceof GenericContainerScreen))
			return false;
		
		if(!isInsidePanel(mouseX, mouseY))
			return false;
		
		if(contentHeight <= panelInnerHeight())
			return true;
		
		float scale = MathHelper.clamp(textScale.getValueF(), 0.5F, 1.25F);
		int step = Math.max(8, Math.round(MC.textRenderer.fontHeight * scale));
		scrollOffset =
			MathHelper.clamp(scrollOffset - amount * step, 0, maxScroll);
		return true;
	}
	
	private boolean isInsidePanel(double mouseX, double mouseY)
	{
		return mouseX >= panelX && mouseX <= panelX + panelWidth
			&& mouseY >= panelY && mouseY <= panelY + panelHeight;
	}
	
	private void renderOverlay(DrawContext context)
	{
		hitboxes.clear();
		
		TextRenderer tr = MC.textRenderer;
		float scale = MathHelper.clamp(textScale.getValueF(), 0.5F, 1.25F);
		int lineHeight = Math.max(1, Math.round(tr.fontHeight * scale));
		int headerMargin = Math.max(1, Math.round(HEADER_MARGIN * scale));
		int entryMargin = Math.max(1, Math.round(ENTRY_MARGIN * scale));
		
		int titleY = panelY + PANEL_PADDING;
		int titleX = panelX + PANEL_PADDING;
		int titleHeight = Math.max(1, Math.round(tr.fontHeight * scale));
		
		context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight,
			0xC0101010);
		context.fill(panelX, panelY, panelX + panelWidth, panelY + 1,
			0xFF202020);
		context.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth,
			panelY + panelHeight, 0xFF202020);
		context.fill(panelX, panelY, panelX + 1, panelY + panelHeight,
			0xFF202020);
		context.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth,
			panelY + panelHeight, 0xFF202020);
		
		drawScaledText(context, tr, "Enchanted Books", titleX, titleY,
			TITLE_COLOR, scale);
		
		int contentTop = titleY + titleHeight + headerMargin + 1;
		int contentBottom = panelY + panelHeight - PANEL_PADDING;
		int innerHeight = contentBottom - contentTop;
		if(innerHeight <= 0)
		{
			contentHeight = 0;
			maxScroll = 0;
			scrollOffset = 0;
			return;
		}
		
		double scaledMouseX = getScaledMouseX(context);
		double scaledMouseY = getScaledMouseY(context);
		
		context.enableScissor(panelX + 1, contentTop, panelX + panelWidth - 1,
			panelY + panelHeight - 1);
		
		double cursorY = contentTop;
		double offset = scrollOffset;
		boolean anyEntryHovered = false;
		double textAreaWidth =
			Math.max(1.0, panelWidth - 2.0 * PANEL_PADDING - 4.0);
		double hoverSpeed = Math.max(1.0, hoverScrollSpeed.getValueI());
		
		for(BookCategory category : BookCategory.ORDERED)
		{
			List<BookEntry> list =
				groupedEntries.getOrDefault(category, Collections.emptyList());
			if(list.isEmpty())
				continue;
			
			int headerY = (int)Math.round(cursorY - offset);
			String headerText = category.displayName + " (" + list.size() + ")";
			drawScaledText(context, tr, headerText, titleX, headerY,
				HEADER_COLOR, scale);
			
			String takeAllText = "Take All";
			int takeAllWidth =
				Math.max(1, Math.round(tr.getWidth(takeAllText) * scale));
			int takeAllX = panelX + panelWidth - PANEL_PADDING - takeAllWidth;
			int takeAllY = headerY;
			boolean actionHovered = scaledMouseX >= takeAllX - 2
				&& scaledMouseX <= takeAllX + takeAllWidth + 2
				&& scaledMouseY >= takeAllY - 2
				&& scaledMouseY <= takeAllY + lineHeight + 2;
			
			drawScaledText(context, tr, takeAllText, takeAllX, takeAllY,
				actionHovered ? ACTION_HOVER_COLOR : ACTION_COLOR, scale);
			
			hitboxes.add(Hitbox.forCategory(takeAllX - 2, takeAllY - 2,
				takeAllWidth + 4, lineHeight + 4, category));
			
			cursorY += lineHeight + entryMargin;
			for(BookEntry entry : list)
			{
				int entryY = (int)Math.round(cursorY - offset);
				boolean hovered = scaledMouseX >= panelX + 2
					&& scaledMouseX <= panelX + panelWidth - 2
					&& scaledMouseY >= entryY - 2
					&& scaledMouseY <= entryY + lineHeight + 2;
				
				if(hovered)
					context.fill(panelX + 2, entryY - 2,
						panelX + panelWidth - 2, entryY + lineHeight + 2,
						0x802A2A2A);
				
				double textWidth =
					Math.max(1.0, (double)tr.getWidth(entry.line) * scale);
				double travel = textWidth - textAreaWidth;
				double scrollX = 0.0;
				if(hovered)
				{
					anyEntryHovered = true;
					if(entry.slotId != hoveredSlotId)
					{
						hoveredSlotId = entry.slotId;
						hoverStartMs = System.currentTimeMillis();
					}
					
					if(travel > 1.0)
					{
						long elapsed =
							System.currentTimeMillis() - hoverStartMs;
						if(elapsed > HOVER_SCROLL_DELAY_MS)
						{
							double progress = (elapsed - HOVER_SCROLL_DELAY_MS)
								/ 1000.0 * hoverSpeed;
							double cycle = travel * 2.0 + HOVER_SCROLL_PAUSE;
							double cyclePos = progress % cycle;
							if(cyclePos <= travel)
								scrollX = cyclePos;
							else if(cyclePos <= travel + HOVER_SCROLL_PAUSE)
								scrollX = travel;
							else
							{
								double back =
									cyclePos - travel - HOVER_SCROLL_PAUSE;
								scrollX = Math.max(0.0, travel - back);
							}
						}
					}
					scrollX =
						MathHelper.clamp(scrollX, 0.0, Math.max(0.0, travel));
				}else if(hoveredSlotId == entry.slotId)
				{
					hoveredSlotId = -1;
					hoverStartMs = 0L;
				}
				
				float renderScroll = (float)(scrollX / scale);
				drawScaledText(context, tr, entry.line, titleX - renderScroll,
					entryY, hovered ? ENTRY_HOVER_COLOR : ENTRY_COLOR, scale);
				
				hitboxes.add(Hitbox.forEntry(panelX + 2, entryY - 2,
					panelWidth - 4, lineHeight + 4, entry));
				
				cursorY += lineHeight + entryMargin;
			}
			
			cursorY += headerMargin;
		}
		
		if(!anyEntryHovered)
		{
			hoveredSlotId = -1;
			hoverStartMs = 0L;
		}
		
		contentHeight = (int)Math.max(0, Math.round(cursorY - contentTop));
		maxScroll = Math.max(0, contentHeight - innerHeight);
		scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);
		
		context.disableScissor();
	}
	
	private void rescan(GenericContainerScreen screen)
	{
		refreshEntries(screen.getScreenHandler());
		needsRescan = false;
	}
	
	private void refreshIfDirty(GenericContainerScreen screen)
	{
		refreshEntries(screen.getScreenHandler());
	}
	
	private void refreshEntries(ScreenHandler handler)
	{
		entries.clear();
		groupedEntries.values().forEach(List::clear);
		
		if(!(handler instanceof GenericContainerScreenHandler genericHandler))
			return;
		
		int containerSlots = genericHandler.getRows() * 9;
		List<Slot> slots = genericHandler.slots;
		containerSlots = Math.min(containerSlots, slots.size());
		
		for(int i = 0; i < containerSlots; i++)
		{
			Slot slot = slots.get(i);
			if(slot == null || !slot.hasStack())
				continue;
			
			ItemStack stack = slot.getStack();
			if(!stack.isOf(Items.ENCHANTED_BOOK))
				continue;
			
			Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> enchantments =
				EnchantmentHelper.getEnchantments(stack)
					.getEnchantmentEntries();
			if(enchantments.isEmpty())
				continue;
			
			BookEntry entry = buildEntry(slot, enchantments);
			if(entry == null)
				continue;
			
			entries.add(entry);
			groupedEntries
				.computeIfAbsent(entry.category, c -> new ArrayList<>())
				.add(entry);
		}
		
		for(BookCategory category : BookCategory.ORDERED)
		{
			List<BookEntry> list = groupedEntries.get(category);
			if(list != null)
				list.sort(
					(a, b) -> Integer.compare(a.displaySlot, b.displaySlot));
		}
	}
	
	private BookEntry buildEntry(Slot slot,
		Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> enchantments)
	{
		List<String> parts = new ArrayList<>();
		EnumSet<BookCategory> categories = EnumSet.noneOf(BookCategory.class);
		
		for(Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : enchantments)
		{
			RegistryEntry<Enchantment> enchantmentEntry = entry.getKey();
			if(enchantmentEntry == null)
				continue;
			
			int level = entry.getIntValue();
			Identifier id = enchantmentEntry.getKey()
				.map(registryKey -> registryKey.getValue()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(enchantmentEntry.getIdAsString());
			BookCategory category = BookCategory.fromPath(path);
			categories.add(category);
			
			String name = buildEnchantmentName(id, path);
			String levelText =
				Text.translatable("enchantment.level." + level).getString();
			parts.add(name + " " + levelText);
		}
		
		if(parts.isEmpty())
			return null;
		
		BookCategory primary =
			categories.stream().filter(c -> c != BookCategory.MISC).findFirst()
				.orElseGet(() -> categories.stream().findFirst()
					.orElse(BookCategory.MISC));
		
		String enchantSummary = limitLength(String.join(", ", parts), 90);
		
		int slotNumber = slot.getIndex() + 1;
		String line = slotNumber + " - " + enchantSummary;
		
		return new BookEntry(slot.id, slotNumber, primary, line);
	}
	
	private void takeEntry(BookEntry entry,
		GenericContainerScreenHandler handler)
	{
		if(MC.player == null || MC.interactionManager == null)
			return;
		
		if(entry == null)
			return;
		
		Slot slot = handler.getSlot(entry.slotId);
		if(slot == null || !slot.hasStack())
			return;
		
		ItemStack stack = slot.getStack();
		if(!stack.isOf(Items.ENCHANTED_BOOK))
			return;
		
		MC.interactionManager.clickSlot(handler.syncId, entry.slotId, 0,
			SlotActionType.QUICK_MOVE, MC.player);
		needsRescan = true;
	}
	
	private void takeCategory(BookCategory category,
		GenericContainerScreenHandler handler)
	{
		if(MC.player == null || MC.interactionManager == null)
			return;
		
		List<BookEntry> list = groupedEntries.get(category);
		if(list == null || list.isEmpty())
			return;
		
		for(BookEntry entry : new ArrayList<>(list))
		{
			Slot slot = handler.getSlot(entry.slotId);
			if(slot == null || !slot.hasStack())
				continue;
			
			if(!slot.getStack().isOf(Items.ENCHANTED_BOOK))
				continue;
			
			MC.interactionManager.clickSlot(handler.syncId, entry.slotId, 0,
				SlotActionType.QUICK_MOVE, MC.player);
		}
		
		needsRescan = true;
	}
	
	private int panelInnerHeight()
	{
		float scale = MathHelper.clamp(textScale.getValueF(), 0.5F, 1.25F);
		TextRenderer tr = MC.textRenderer;
		int titleHeight = Math.max(1, Math.round(tr.fontHeight * scale));
		int headerMargin = Math.max(1, Math.round(HEADER_MARGIN * scale));
		int contentTop =
			panelY + PANEL_PADDING + titleHeight + headerMargin + 1;
		int contentBottom = panelY + panelHeight - PANEL_PADDING;
		return Math.max(0, contentBottom - contentTop);
	}
	
	private static double getScaledMouseX(DrawContext context)
	{
		Window window = MC.getWindow();
		return MC.mouse.getX() * context.getScaledWindowWidth()
			/ window.getWidth();
	}
	
	private static double getScaledMouseY(DrawContext context)
	{
		Window window = MC.getWindow();
		return MC.mouse.getY() * context.getScaledWindowHeight()
			/ window.getHeight();
	}
	
	private static void drawScaledText(DrawContext context, TextRenderer tr,
		String text, float x, float y, int color, float scale)
	{
		RenderUtils.drawScaledText(context, tr, text, Math.round(x),
			Math.round(y), color, false, scale);
	}
	
	private static String limitLength(String text, int max)
	{
		if(text.length() <= max)
			return text;
		return text.substring(0, Math.max(0, max - 3)) + "...";
	}
	
	private static String buildEnchantmentName(Identifier id, String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown Enchant";
		
		String namespace = id != null ? id.getNamespace() : "minecraft";
		String key = "enchantment." + namespace + "." + path;
		String translated = Text.translatable(key).getString();
		if(translated.equals(key))
			return humanize(path);
		return translated;
	}
	
	private static String humanize(String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown";
		
		String humanized =
			Arrays.stream(path.split("_")).filter(part -> !part.isEmpty())
				.map(part -> Character.toUpperCase(part.charAt(0))
					+ (part.length() > 1 ? part.substring(1) : ""))
				.collect(Collectors.joining(" "));
		return humanized.isEmpty() ? "Unknown" : humanized;
	}
	
	private static String sanitizePath(String raw)
	{
		if(raw == null || raw.isEmpty())
			return "";
		int colon = raw.indexOf(':');
		return colon >= 0 && colon + 1 < raw.length() ? raw.substring(colon + 1)
			: raw;
	}
	
	private static final class BookEntry
	{
		final int slotId;
		final int displaySlot;
		final BookCategory category;
		final String line;
		
		BookEntry(int slotId, int displaySlot, BookCategory category,
			String line)
		{
			this.slotId = slotId;
			this.displaySlot = displaySlot;
			this.category = Objects.requireNonNull(category);
			this.line = line;
		}
	}
	
	private static final class Hitbox
	{
		final int x;
		final int y;
		final int width;
		final int height;
		final BookEntry entry;
		final BookCategory category;
		
		private Hitbox(int x, int y, int width, int height, BookEntry entry,
			BookCategory category)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.entry = entry;
			this.category = category;
		}
		
		static Hitbox forEntry(int x, int y, int width, int height,
			BookEntry entry)
		{
			return new Hitbox(x, y, width, height, entry, null);
		}
		
		static Hitbox forCategory(int x, int y, int width, int height,
			BookCategory category)
		{
			return new Hitbox(x, y, width, height, null,
				Objects.requireNonNull(category));
		}
		
		boolean contains(double mouseX, double mouseY)
		{
			return mouseX >= x && mouseX <= x + width && mouseY >= y
				&& mouseY <= y + height;
		}
	}
	
	private enum BookCategory
	{
		HELMET("Armor (Helmet)", "respiration", "aqua_affinity"),
		CHEST("Armor (Chest)", "protection", "fire_protection",
			"blast_protection", "projectile_protection", "thorns"),
		LEGGINGS("Armor (Leggings)", "swift_sneak"),
		BOOTS("Armor (Boots)", "feather_falling", "depth_strider",
			"frost_walker", "soul_speed"),
		WEAPON("Weapons", "sharpness", "smite", "bane_of_arthropods",
			"knockback", "looting", "fire_aspect", "sweeping", "sweeping_edge",
			"breach", "density"),
		TOOLS("Tools", "efficiency", "silk_touch", "fortune"),
		BOW("Bow", "power", "punch", "flame", "infinity"),
		CROSSBOW("Crossbow", "piercing", "quick_charge", "multishot"),
		TRIDENT("Trident", "impaling", "riptide", "channeling", "loyalty"),
		SHIELD("Shield", "bulwark"),
		ELYTRA("Elytra", "wind_burst"),
		MISC("Misc", "mending", "unbreaking", "binding_curse",
			"vanishing_curse", "lure", "luck_of_the_sea", "looting",
			"frost_walker", "aqua_affinity");
		
		static final List<BookCategory> ORDERED =
			List.of(HELMET, CHEST, LEGGINGS, BOOTS, WEAPON, TOOLS, BOW,
				CROSSBOW, TRIDENT, SHIELD, ELYTRA, MISC);
		
		private final String displayName;
		private final Set<String> enchantIds;
		
		BookCategory(String displayName, String... enchantIds)
		{
			this.displayName = displayName;
			this.enchantIds = Set.of(enchantIds);
		}
		
		static BookCategory fromPath(String path)
		{
			if(path == null || path.isEmpty())
				return MISC;
			
			for(BookCategory category : ORDERED)
				if(category.enchantIds.contains(path))
					return category;
				
			return MISC;
		}
	}
}
