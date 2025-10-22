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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WeaponComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
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
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"enchantment handler", "gear enchantments", "book enchantments"})
public final class EnchantmentHandlerHack extends Hack
{
	private static final int MIN_BOX_WIDTH = 140;
	private static final int MAX_BOX_WIDTH = 360;
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
	
	private final List<AbstractEntry> allEntries = new ArrayList<>();
	private final Map<GearCategory, List<GearEntry>> gearGroupedEntries =
		new LinkedHashMap<>();
	private final Map<BookCategory, List<BookEntry>> bookGroupedEntries =
		new LinkedHashMap<>();
	private final List<Hitbox> hitboxes = new ArrayList<>();
	
	private final SliderSetting boxWidth = new SliderSetting("Box width", 200,
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
	
	public EnchantmentHandlerHack()
	{
		super("EnchantmentHandler");
		setCategory(Category.ITEMS);
		
		addSetting(boxWidth);
		addSetting(boxHeight);
		addSetting(offsetX);
		addSetting(offsetY);
		addSetting(textScale);
		addSetting(hoverScrollSpeed);
		
		for(GearCategory category : GearCategory.ORDERED)
			gearGroupedEntries.put(category, new ArrayList<>());
		for(BookCategory category : BookCategory.ORDERED)
			bookGroupedEntries.put(category, new ArrayList<>());
	}
	
	@Override
	protected void onDisable()
	{
		scrollOffset = 0;
		contentHeight = 0;
		maxScroll = 0;
		lastRenderActive = false;
		needsRescan = true;
		allEntries.clear();
		gearGroupedEntries.values().forEach(List::clear);
		bookGroupedEntries.values().forEach(List::clear);
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
		
		if(allEntries.isEmpty())
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
		
		GenericContainerScreenHandler handler =
			((GenericContainerScreen)screen).getScreenHandler();
		
		for(Hitbox hitbox : hitboxes)
		{
			if(!hitbox.contains(mouseX, mouseY))
				continue;
			
			if(hitbox.entry != null)
			{
				if(button == 0)
				{
					if(hitbox.entry instanceof GearEntry gear)
						takeEntry(gear, handler);
					else if(hitbox.entry instanceof BookEntry book)
						takeEntry(book, handler);
					return true;
				}
				
				if(button == 1)
				{
					if(hitbox.entry instanceof GearEntry gear)
						takeGearCategory(gear.category, handler);
					else if(hitbox.entry instanceof BookEntry book)
						takeBookCategory(book.category, handler);
					return true;
				}
				continue;
			}
			
			if(hitbox.categoryKind == CategoryKind.GEAR
				&& hitbox.category instanceof GearCategory gearCat)
			{
				takeGearCategory(gearCat, handler);
				return true;
			}
			
			if(hitbox.categoryKind == CategoryKind.BOOK
				&& hitbox.category instanceof BookCategory bookCat)
			{
				takeBookCategory(bookCat, handler);
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
		
		int contentTop = panelY + PANEL_PADDING;
		int titleX = panelX + PANEL_PADDING;
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
		boolean[] hoverFlag = new boolean[]{false};
		double textAreaWidth =
			Math.max(1.0, panelWidth - 2.0 * PANEL_PADDING - 4.0);
		double hoverSpeed = Math.max(1.0, hoverScrollSpeed.getValueI());
		
		cursorY = renderGearSection(context, tr, scale, titleX, cursorY, offset,
			textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY, lineHeight,
			entryMargin, headerMargin, hoverFlag);
		
		cursorY = renderBookSection(context, tr, scale, titleX, cursorY, offset,
			textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY, lineHeight,
			entryMargin, headerMargin, hoverFlag);
		
		if(!hoverFlag[0])
		{
			hoveredSlotId = -1;
			hoverStartMs = 0L;
		}
		
		contentHeight = (int)Math.max(0, Math.round(cursorY - contentTop));
		maxScroll = Math.max(0, contentHeight - innerHeight);
		scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);
		
		context.disableScissor();
	}
	
	private double renderGearSection(DrawContext context, TextRenderer tr,
		float scale, int titleX, double cursorY, double offset,
		double textAreaWidth, double hoverSpeed, double scaledMouseX,
		double scaledMouseY, int lineHeight, int entryMargin, int headerMargin,
		boolean[] hoverFlag)
	{
		boolean hasEntries = GearCategory.ORDERED.stream().anyMatch(
			cat -> !gearGroupedEntries.getOrDefault(cat, List.of()).isEmpty());
		if(!hasEntries)
			return cursorY;
		
		int sectionTitleY = (int)Math.round(cursorY - offset);
		drawScaledText(context, tr, "Enchanted Gear", titleX, sectionTitleY,
			TITLE_COLOR, scale);
		int underlineY = sectionTitleY
			+ Math.max(1, Math.round(MC.textRenderer.fontHeight * scale)) + 2;
		context.fill(panelX + 2, underlineY, panelX + panelWidth - 2,
			underlineY + 1, 0xFFD0D0D0);
		cursorY += lineHeight + headerMargin + 4;
		
		for(GearCategory category : GearCategory.ORDERED)
		{
			List<GearEntry> list =
				gearGroupedEntries.getOrDefault(category, List.of());
			if(list.isEmpty())
				continue;
			
			cursorY = renderCategoryEntries(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin,
				category.getDisplayName(), CategoryKind.GEAR, category, list,
				hoverFlag);
		}
		
		return cursorY;
	}
	
	private double renderBookSection(DrawContext context, TextRenderer tr,
		float scale, int titleX, double cursorY, double offset,
		double textAreaWidth, double hoverSpeed, double scaledMouseX,
		double scaledMouseY, int lineHeight, int entryMargin, int headerMargin,
		boolean[] hoverFlag)
	{
		boolean hasEntries = BookCategory.ORDERED.stream().anyMatch(
			cat -> !bookGroupedEntries.getOrDefault(cat, List.of()).isEmpty());
		if(!hasEntries)
			return cursorY;
		
		int sectionTitleY = (int)Math.round(cursorY - offset);
		drawScaledText(context, tr, "Enchanted Books", titleX, sectionTitleY,
			TITLE_COLOR, scale);
		int underlineY = sectionTitleY
			+ Math.max(1, Math.round(MC.textRenderer.fontHeight * scale)) + 2;
		context.fill(panelX + 2, underlineY, panelX + panelWidth - 2,
			underlineY + 1, 0xFFD0D0D0);
		cursorY += lineHeight + headerMargin + 4;
		
		for(BookCategory category : BookCategory.ORDERED)
		{
			List<BookEntry> list =
				bookGroupedEntries.getOrDefault(category, List.of());
			if(list.isEmpty())
				continue;
			
			cursorY = renderCategoryEntries(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin,
				category.getDisplayName(), CategoryKind.BOOK, category, list,
				hoverFlag);
		}
		
		return cursorY;
	}
	
	private double renderCategoryEntries(DrawContext context, TextRenderer tr,
		float scale, int titleX, double cursorY, double offset,
		double textAreaWidth, double hoverSpeed, double scaledMouseX,
		double scaledMouseY, int lineHeight, int entryMargin, int headerMargin,
		String displayName, CategoryKind kind, Object category,
		List<? extends AbstractEntry> entries, boolean[] hoverFlag)
	{
		int headerY = (int)Math.round(cursorY - offset);
		String headerText = displayName + " (" + entries.size() + ")";
		drawScaledText(context, tr, headerText, titleX, headerY, HEADER_COLOR,
			scale);
		
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
			takeAllWidth + 4, lineHeight + 4, kind, category));
		
		cursorY += lineHeight + entryMargin;
		
		for(AbstractEntry entry : entries)
		{
			int entryY = (int)Math.round(cursorY - offset);
			boolean hovered = scaledMouseX >= panelX + 2
				&& scaledMouseX <= panelX + panelWidth - 2
				&& scaledMouseY >= entryY - 2
				&& scaledMouseY <= entryY + lineHeight + 2;
			
			if(hovered)
				context.fill(panelX + 2, entryY - 2, panelX + panelWidth - 2,
					entryY + lineHeight + 2, 0x802A2A2A);
			
			double textWidth = Math.max(1.0, tr.getWidth(entry.line) * scale);
			double travel = textWidth - textAreaWidth;
			double scrollX = 0.0;
			
			if(hovered)
			{
				hoverFlag[0] = true;
				if(entry.slotId != hoveredSlotId)
				{
					hoveredSlotId = entry.slotId;
					hoverStartMs = System.currentTimeMillis();
				}
				
				if(travel > 1.0)
				{
					long elapsed = System.currentTimeMillis() - hoverStartMs;
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
				scrollX = MathHelper.clamp(scrollX, 0.0, Math.max(0.0, travel));
			}else if(hoveredSlotId == entry.slotId)
			{
				hoveredSlotId = -1;
				hoverStartMs = 0L;
			}
			
			float renderScroll = (float)(scrollX / scale);
			drawScaledText(context, tr, entry.line, titleX - renderScroll,
				entryY, hovered ? ENTRY_HOVER_COLOR : ENTRY_COLOR, scale);
			
			hitboxes.add(Hitbox.forEntry(panelX + 2, entryY - 2, panelWidth - 4,
				lineHeight + 4, entry));
			
			cursorY += lineHeight + entryMargin;
		}
		
		cursorY += headerMargin;
		return cursorY;
	}
	
	private void drawSectionHeader(DrawContext context, TextRenderer tr, int x,
		int y, String text, float scale)
	{
		int textWidth = Math.max(1, Math.round(tr.getWidth(text) * scale));
		int textHeight = Math.max(1, Math.round(tr.fontHeight * scale));
		int left = Math.round(x - 4);
		int right = Math.round(x + textWidth + 4);
		int top = Math.round(y - 4);
		int bottom = Math.round(y + textHeight + 4);
		context.fill(left, top, right, bottom, 0xC0282828);
		drawScaledText(context, tr, text, x, y, TITLE_COLOR, scale);
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
		allEntries.clear();
		gearGroupedEntries.values().forEach(List::clear);
		bookGroupedEntries.values().forEach(List::clear);
		
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
			if(stack.isEmpty())
				continue;
			
			if(stack.isOf(Items.ENCHANTED_BOOK))
			{
				Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> enchantments =
					EnchantmentHelper.getEnchantments(stack)
						.getEnchantmentEntries();
				if(enchantments.isEmpty())
					continue;
				
				BookEntry entry = buildBookEntry(slot, enchantments);
				if(entry == null)
					continue;
				
				allEntries.add(entry);
				bookGroupedEntries
					.computeIfAbsent(entry.category, c -> new ArrayList<>())
					.add(entry);
				continue;
			}
			
			Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> enchantments =
				EnchantmentHelper.getEnchantments(stack)
					.getEnchantmentEntries();
			if(enchantments.isEmpty())
				continue;
			
			GearCategory category = GearCategory.fromStack(stack);
			if(category == null)
				continue;
			
			GearEntry entry =
				buildGearEntry(slot, stack, category, enchantments);
			if(entry == null)
				continue;
			
			allEntries.add(entry);
			gearGroupedEntries.computeIfAbsent(category, c -> new ArrayList<>())
				.add(entry);
		}
		
		gearGroupedEntries.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
		bookGroupedEntries.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
	}
	
	private GearEntry buildGearEntry(Slot slot, ItemStack stack,
		GearCategory category,
		Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> enchantments)
	{
		List<String> parts = new ArrayList<>();
		
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
			String name = buildEnchantmentName(id, path);
			String levelText =
				Text.translatable("enchantment.level." + level).getString();
			parts.add(name + " " + levelText);
		}
		
		if(parts.isEmpty())
			return null;
		
		String enchantSummary = limitLength(String.join(", ", parts), 90);
		
		int slotNumber = slot.getIndex() + 1;
		String itemName = limitLength(stack.getName().getString(), 40);
		
		String line = slotNumber + " - " + itemName + " | " + enchantSummary;
		
		return new GearEntry(slot.id, slotNumber, category, line);
	}
	
	private BookEntry buildBookEntry(Slot slot,
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
	
	private void takeEntry(GearEntry entry,
		GenericContainerScreenHandler handler)
	{
		if(MC.player == null || MC.interactionManager == null || entry == null)
			return;
		
		Slot slot = handler.getSlot(entry.slotId);
		if(slot == null || !slot.hasStack())
			return;
		
		MC.interactionManager.clickSlot(handler.syncId, entry.slotId, 0,
			SlotActionType.QUICK_MOVE, MC.player);
		needsRescan = true;
	}
	
	private void takeEntry(BookEntry entry,
		GenericContainerScreenHandler handler)
	{
		if(MC.player == null || MC.interactionManager == null || entry == null)
			return;
		
		Slot slot = handler.getSlot(entry.slotId);
		if(slot == null || !slot.hasStack())
			return;
		
		MC.interactionManager.clickSlot(handler.syncId, entry.slotId, 0,
			SlotActionType.QUICK_MOVE, MC.player);
		needsRescan = true;
	}
	
	private void takeGearCategory(GearCategory category,
		GenericContainerScreenHandler handler)
	{
		if(MC.player == null || MC.interactionManager == null)
			return;
		
		List<GearEntry> list = gearGroupedEntries.get(category);
		if(list == null || list.isEmpty())
			return;
		
		for(GearEntry entry : new ArrayList<>(list))
		{
			Slot slot = handler.getSlot(entry.slotId);
			if(slot == null || !slot.hasStack())
				continue;
			
			MC.interactionManager.clickSlot(handler.syncId, entry.slotId, 0,
				SlotActionType.QUICK_MOVE, MC.player);
		}
		
		needsRescan = true;
	}
	
	private void takeBookCategory(BookCategory category,
		GenericContainerScreenHandler handler)
	{
		if(MC.player == null || MC.interactionManager == null)
			return;
		
		List<BookEntry> list = bookGroupedEntries.get(category);
		if(list == null || list.isEmpty())
			return;
		
		for(BookEntry entry : new ArrayList<>(list))
		{
			Slot slot = handler.getSlot(entry.slotId);
			if(slot == null || !slot.hasStack())
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
	
	private static abstract class AbstractEntry
	{
		final int slotId;
		final int displaySlot;
		final String line;
		
		AbstractEntry(int slotId, int displaySlot, String line)
		{
			this.slotId = slotId;
			this.displaySlot = displaySlot;
			this.line = line;
		}
	}
	
	private static final class GearEntry extends AbstractEntry
	{
		final GearCategory category;
		
		GearEntry(int slotId, int displaySlot, GearCategory category,
			String line)
		{
			super(slotId, displaySlot, line);
			this.category = Objects.requireNonNull(category);
		}
	}
	
	private static final class BookEntry extends AbstractEntry
	{
		final BookCategory category;
		
		BookEntry(int slotId, int displaySlot, BookCategory category,
			String line)
		{
			super(slotId, displaySlot, line);
			this.category = Objects.requireNonNull(category);
		}
	}
	
	private static final class Hitbox
	{
		final int x;
		final int y;
		final int width;
		final int height;
		final AbstractEntry entry;
		final CategoryKind categoryKind;
		final Object category;
		
		private Hitbox(int x, int y, int width, int height, AbstractEntry entry,
			CategoryKind categoryKind, Object category)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.entry = entry;
			this.categoryKind = categoryKind;
			this.category = category;
		}
		
		static Hitbox forEntry(int x, int y, int width, int height,
			AbstractEntry entry)
		{
			return new Hitbox(x, y, width, height, entry, null, null);
		}
		
		static Hitbox forCategory(int x, int y, int width, int height,
			CategoryKind kind, Object category)
		{
			return new Hitbox(x, y, width, height, null, kind, category);
		}
		
		boolean contains(double mouseX, double mouseY)
		{
			return mouseX >= x && mouseX <= x + width && mouseY >= y
				&& mouseY <= y + height;
		}
	}
	
	private static enum CategoryKind
	{
		GEAR,
		BOOK;
	}
	
	private static enum GearCategory
	{
		HELMET("Armor (Helmet)"),
		CHESTPLATE("Armor (Chest)"),
		LEGGINGS("Armor (Leggings)"),
		BOOTS("Armor (Boots)"),
		SHIELD("Shield"),
		WEAPON("Weapons");
		
		static final List<GearCategory> ORDERED =
			List.of(GearCategory.values());
		
		private final String displayName;
		
		GearCategory(String displayName)
		{
			this.displayName = displayName;
		}
		
		String getDisplayName()
		{
			return displayName;
		}
		
		static GearCategory fromStack(ItemStack stack)
		{
			Item item = stack.getItem();
			EquipmentSlot slot = ItemUtils.getArmorSlot(item);
			if(slot != null)
				return switch(slot)
				{
					case HEAD -> HELMET;
					case CHEST -> CHESTPLATE;
					case LEGS -> LEGGINGS;
					case FEET -> BOOTS;
					case OFFHAND ->
					{
						if(stack.isOf(Items.SHIELD))
							yield SHIELD;
						yield null;
					}
					default -> null;
				};
			
			if(stack.isOf(Items.SHIELD))
				return SHIELD;
			
			if(stack.isOf(Items.TRIDENT) || stack.isOf(Items.BOW)
				|| stack.isOf(Items.CROSSBOW))
				return WEAPON;
			
			WeaponComponent weapon =
				stack.getComponents().get(DataComponentTypes.WEAPON);
			if(weapon != null)
				return WEAPON;
			
			return null;
		}
	}
	
	private static enum BookCategory
	{
		HELMET("Armor (Helmet)", "respiration", "aqua_affinity"),
		CHEST("Armor (Chest)", "protection", "fire_protection",
			"blast_protection", "projectile_protection", "thorns"),
		LEGGINGS("Armor (Leggings)", "swift_sneak"),
		BOOTS("Armor (Boots)", "feather_falling", "depth_strider",
			"frost_walker", "soul_speed"),
		WEAPON("Weapons", "sharpness", "smite", "bane_of_arthropods",
			"knockback", "looting", "fire_aspect", "sweeping", "sweeping_edge",
			"breach", "density", "wind_burst"),
		TOOLS("Tools", "efficiency", "silk_touch", "fortune"),
		BOW("Bow", "power", "punch", "flame", "infinity"),
		CROSSBOW("Crossbow", "piercing", "quick_charge", "multishot"),
		TRIDENT("Trident", "impaling", "riptide", "channeling", "loyalty"),
		SHIELD("Shield", "bulwark"),
		ELYTRA("Elytra", "wind_burst"),
		MISC("Misc", "mending", "unbreaking", "binding_curse",
			"vanishing_curse", "lure", "luck_of_the_sea");
		
		static final List<BookCategory> ORDERED =
			List.of(BookCategory.values());
		
		private final String displayName;
		private final Set<String> enchantIds;
		
		BookCategory(String displayName, String... enchantIds)
		{
			this.displayName = displayName;
			this.enchantIds = Set.of(enchantIds);
		}
		
		String getDisplayName()
		{
			return displayName;
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
