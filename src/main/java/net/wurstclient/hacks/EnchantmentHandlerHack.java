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
import com.mojang.blaze3d.platform.Window;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.HandledScreenAccessor;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.CheckboxSetting;
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
	private final Map<PotionCategory, List<PotionEntry>> potionGroupedEntries =
		new LinkedHashMap<>();
	// Separate grouped maps for player inventory and shulker contents
	private final Map<GearCategory, List<GearEntry>> gearGroupedEntriesPlayer =
		new LinkedHashMap<>();
	private final Map<BookCategory, List<BookEntry>> bookGroupedEntriesPlayer =
		new LinkedHashMap<>();
	private final Map<PotionCategory, List<PotionEntry>> potionGroupedEntriesPlayer =
		new LinkedHashMap<>();
	
	private final Map<GearCategory, List<GearEntry>> gearGroupedEntriesShulker =
		new LinkedHashMap<>();
	private final Map<BookCategory, List<BookEntry>> bookGroupedEntriesShulker =
		new LinkedHashMap<>();
	private final Map<PotionCategory, List<PotionEntry>> potionGroupedEntriesShulker =
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
	
	private final CheckboxSetting includePlayerInventory =
		new CheckboxSetting("Include player inventory", false);
	private final CheckboxSetting includeShulkerContents =
		new CheckboxSetting("Include shulker contents", false);
	
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
		addSetting(includePlayerInventory);
		addSetting(includeShulkerContents);
		
		for(GearCategory cat : GearCategory.ORDERED)
		{
			gearGroupedEntries.put(cat, new ArrayList<>());
			gearGroupedEntriesPlayer.put(cat, new ArrayList<>());
			gearGroupedEntriesShulker.put(cat, new ArrayList<>());
		}
		for(BookCategory cat : BookCategory.ORDERED)
		{
			bookGroupedEntries.put(cat, new ArrayList<>());
			bookGroupedEntriesPlayer.put(cat, new ArrayList<>());
			bookGroupedEntriesShulker.put(cat, new ArrayList<>());
		}
		for(PotionCategory cat : PotionCategory.ORDERED)
		{
			potionGroupedEntries.put(cat, new ArrayList<>());
			potionGroupedEntriesPlayer.put(cat, new ArrayList<>());
			potionGroupedEntriesShulker.put(cat, new ArrayList<>());
		}
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
		potionGroupedEntries.values().forEach(List::clear);
		// also clear player / shulker grouped maps
		gearGroupedEntriesPlayer.values().forEach(List::clear);
		bookGroupedEntriesPlayer.values().forEach(List::clear);
		potionGroupedEntriesPlayer.values().forEach(List::clear);
		gearGroupedEntriesShulker.values().forEach(List::clear);
		bookGroupedEntriesShulker.values().forEach(List::clear);
		potionGroupedEntriesShulker.values().forEach(List::clear);
		hitboxes.clear();
		hoveredSlotId = -1;
		hoverStartMs = 0L;
	}
	
	public void renderOnHandledScreen(AbstractContainerScreen<?> screen,
		GuiGraphics context, float partialTicks)
	{
		lastRenderActive = false;
		
		if(MC.player == null || MC.gameMode == null)
			return;
		
		if(needsRescan)
			rescan(screen);
		else
			refreshIfDirty(screen);
		
		if(allEntries.isEmpty())
		{
			hitboxes.clear();
			hoveredSlotId = -1;
			hoverStartMs = 0L;
			return;
		}
		
		HandledScreenAccessor accessor = (HandledScreenAccessor)screen;
		int windowWidth = context.guiWidth();
		int windowHeight = context.guiHeight();
		
		panelWidth = Mth.clamp(boxWidth.getValueI(), MIN_BOX_WIDTH,
			Math.min(MAX_BOX_WIDTH, windowWidth - 10));
		
		int configuredHeight = boxHeight.getValueI();
		if(configuredHeight <= 0)
			panelHeight =
				Math.min(accessor.getBackgroundHeight(), windowHeight - 10);
		else
			panelHeight = Mth.clamp(configuredHeight, 60,
				Math.max(60, windowHeight - 10));
		
		panelY = accessor.getY() + offsetY.getValueI();
		panelX =
			accessor.getX() - panelWidth - PANEL_PADDING + offsetX.getValueI();
		
		panelX =
			Mth.clamp(panelX, 2 - panelWidth, windowWidth - panelWidth - 2);
		panelY = Mth.clamp(panelY, 2, windowHeight - panelHeight - 2);
		
		renderOverlay(context);
		lastRenderActive = true;
	}
	
	public boolean handleMouseClick(AbstractContainerScreen<?> screen,
		double mouseX, double mouseY, int button)
	{
		if(!lastRenderActive)
			return false;
		
		if(button != 0 && button != 1)
			return isInsidePanel(mouseX, mouseY);
		
		AbstractContainerMenu handler =
			((AbstractContainerScreen<?>)screen).getMenu();
		
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
					else if(hitbox.entry instanceof PotionEntry potion)
						takeEntry(potion, handler);
					return true;
				}
				
				if(button == 1)
				{
					if(hitbox.entry instanceof GearEntry gear)
						takeGearCategory(gear.category, handler, hitbox.source);
					else if(hitbox.entry instanceof BookEntry book)
						takeBookCategory(book.category, handler, hitbox.source);
					else if(hitbox.entry instanceof PotionEntry potion)
						takePotionCategory(potion.category, handler,
							hitbox.source);
					return true;
				}
				continue;
			}
			
			if(hitbox.categoryKind == CategoryKind.GEAR
				&& hitbox.category instanceof GearCategory gearCat)
			{
				takeGearCategory(gearCat, handler, hitbox.source);
				return true;
			}
			
			if(hitbox.categoryKind == CategoryKind.BOOK
				&& hitbox.category instanceof BookCategory bookCat)
			{
				takeBookCategory(bookCat, handler, hitbox.source);
				return true;
			}
			
			if(hitbox.categoryKind == CategoryKind.POTION
				&& hitbox.category instanceof PotionCategory potCat)
			{
				takePotionCategory(potCat, handler, hitbox.source);
				return true;
			}
		}
		
		return isInsidePanel(mouseX, mouseY);
	}
	
	public boolean handleMouseScroll(AbstractContainerScreen<?> screen,
		double mouseX, double mouseY, double amount)
	{
		if(!lastRenderActive)
			return false;
		
		if(!isInsidePanel(mouseX, mouseY))
			return false;
		
		if(contentHeight <= panelInnerHeight())
			return true;
		
		float scale = Mth.clamp(textScale.getValueF(), 0.5F, 1.25F);
		int step = Math.max(8, Math.round(MC.font.lineHeight * scale));
		scrollOffset = Mth.clamp(scrollOffset - amount * step, 0, maxScroll);
		return true;
	}
	
	private boolean isInsidePanel(double mouseX, double mouseY)
	{
		return mouseX >= panelX && mouseX <= panelX + panelWidth
			&& mouseY >= panelY && mouseY <= panelY + panelHeight;
	}
	
	private void renderOverlay(GuiGraphics context)
	{
		hitboxes.clear();
		
		Font tr = MC.font;
		float scale = Mth.clamp(textScale.getValueF(), 0.5F, 1.25F);
		int lineHeight = Math.max(1, Math.round(tr.lineHeight * scale));
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
		
		// Render source sections: Container always, then optional Player and
		// Shulker sections (each contains gear, potions and books
		// subcategories)
		cursorY = renderSourceSection(context, tr, scale, titleX, cursorY,
			offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
			lineHeight, entryMargin, headerMargin, hoverFlag, "Container",
			gearGroupedEntries, potionGroupedEntries, bookGroupedEntries);
		
		if(includePlayerInventory.isChecked())
		{
			cursorY = renderSourceSection(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin, hoverFlag,
				"Player Inventory", gearGroupedEntriesPlayer,
				potionGroupedEntriesPlayer, bookGroupedEntriesPlayer);
		}
		
		if(includeShulkerContents.isChecked())
		{
			cursorY = renderSourceSection(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin, hoverFlag,
				"Shulker contents", gearGroupedEntriesShulker,
				potionGroupedEntriesShulker, bookGroupedEntriesShulker);
		}
		
		if(!hoverFlag[0])
		{
			hoveredSlotId = -1;
			hoverStartMs = 0L;
		}
		
		contentHeight = (int)Math.max(0, Math.round(cursorY - contentTop));
		maxScroll = Math.max(0, contentHeight - innerHeight);
		scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
		
		context.disableScissor();
	}
	
	private double renderGearSection(GuiGraphics context, Font tr, float scale,
		int titleX, double cursorY, double offset, double textAreaWidth,
		double hoverSpeed, double scaledMouseX, double scaledMouseY,
		int lineHeight, int entryMargin, int headerMargin, boolean[] hoverFlag)
	{
		boolean hasEntries = GearCategory.ORDERED.stream().anyMatch(
			cat -> !gearGroupedEntries.getOrDefault(cat, List.of()).isEmpty());
		if(!hasEntries)
			return cursorY;
		
		int sectionTitleY = (int)Math.round(cursorY - offset);
		drawSectionHeader(context, tr, titleX, sectionTitleY, "Enchanted Gear",
			scale);
		int underlineY = sectionTitleY
			+ Math.max(1, Math.round(MC.font.lineHeight * scale)) + 2;
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
				hoverFlag, "Container");
		}
		
		return cursorY;
	}
	
	private double renderSourceSection(GuiGraphics context, Font tr,
		float scale, int titleX, double cursorY, double offset,
		double textAreaWidth, double hoverSpeed, double scaledMouseX,
		double scaledMouseY, int lineHeight, int entryMargin, int headerMargin,
		boolean[] hoverFlag, String sourceTitle,
		Map<GearCategory, List<GearEntry>> gearMap,
		Map<PotionCategory, List<PotionEntry>> potionMap,
		Map<BookCategory, List<BookEntry>> bookMap)
	{
		boolean hasEntries = GearCategory.ORDERED.stream()
			.anyMatch(cat -> !gearMap.getOrDefault(cat, List.of()).isEmpty())
			|| BookCategory.ORDERED.stream().anyMatch(
				cat -> !bookMap.getOrDefault(cat, List.of()).isEmpty())
			|| PotionCategory.ORDERED.stream().anyMatch(
				cat -> !potionMap.getOrDefault(cat, List.of()).isEmpty());
		if(!hasEntries)
			return cursorY;
		
		int sectionTitleY = (int)Math.round(cursorY - offset);
		drawSectionHeader(context, tr, titleX, sectionTitleY, sourceTitle,
			scale);
		int underlineY = sectionTitleY
			+ Math.max(1, Math.round(MC.font.lineHeight * scale)) + 2;
		context.fill(panelX + 2, underlineY, panelX + panelWidth - 2,
			underlineY + 1, 0xFFD0D0D0);
		cursorY += lineHeight + headerMargin + 4;
		
		// Gear
		for(GearCategory category : GearCategory.ORDERED)
		{
			List<GearEntry> list = gearMap.getOrDefault(category, List.of());
			if(list.isEmpty())
				continue;
			
			cursorY = renderCategoryEntries(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin,
				category.getDisplayName(), CategoryKind.GEAR, category, list,
				hoverFlag, sourceTitle);
		}
		
		// Potions
		for(PotionCategory category : PotionCategory.ORDERED)
		{
			List<PotionEntry> list =
				potionMap.getOrDefault(category, List.of());
			if(list.isEmpty())
				continue;
			
			cursorY = renderCategoryEntries(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin,
				category.getDisplayName(), CategoryKind.POTION, category, list,
				hoverFlag, sourceTitle);
		}
		
		// Books
		for(BookCategory category : BookCategory.ORDERED)
		{
			List<BookEntry> list = bookMap.getOrDefault(category, List.of());
			if(list.isEmpty())
				continue;
			
			cursorY = renderCategoryEntries(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin,
				category.getDisplayName(), CategoryKind.BOOK, category, list,
				hoverFlag, sourceTitle);
		}
		
		return cursorY;
	}
	
	private double renderBookSection(GuiGraphics context, Font tr, float scale,
		int titleX, double cursorY, double offset, double textAreaWidth,
		double hoverSpeed, double scaledMouseX, double scaledMouseY,
		int lineHeight, int entryMargin, int headerMargin, boolean[] hoverFlag)
	{
		boolean hasEntries = BookCategory.ORDERED.stream().anyMatch(
			cat -> !bookGroupedEntries.getOrDefault(cat, List.of()).isEmpty());
		if(!hasEntries)
			return cursorY;
		
		int sectionTitleY = (int)Math.round(cursorY - offset);
		drawSectionHeader(context, tr, titleX, sectionTitleY, "Enchanted Books",
			scale);
		int underlineY = sectionTitleY
			+ Math.max(1, Math.round(MC.font.lineHeight * scale)) + 2;
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
				hoverFlag, "Container");
		}
		
		return cursorY;
	}
	
	private double renderPotionSection(GuiGraphics context, Font tr,
		float scale, int titleX, double cursorY, double offset,
		double textAreaWidth, double hoverSpeed, double scaledMouseX,
		double scaledMouseY, int lineHeight, int entryMargin, int headerMargin,
		boolean[] hoverFlag)
	{
		boolean hasEntries = PotionCategory.ORDERED.stream()
			.anyMatch(cat -> !potionGroupedEntries.getOrDefault(cat, List.of())
				.isEmpty());
		if(!hasEntries)
			return cursorY;
		
		int sectionTitleY = (int)Math.round(cursorY - offset);
		drawSectionHeader(context, tr, titleX, sectionTitleY, "Potions", scale);
		int underlineY = sectionTitleY
			+ Math.max(1, Math.round(MC.font.lineHeight * scale)) + 2;
		context.fill(panelX + 2, underlineY, panelX + panelWidth - 2,
			underlineY + 1, 0xFFD0D0D0);
		cursorY += lineHeight + headerMargin + 4;
		
		for(PotionCategory category : PotionCategory.ORDERED)
		{
			List<PotionEntry> list =
				potionGroupedEntries.getOrDefault(category, List.of());
			if(list.isEmpty())
				continue;
			
			cursorY = renderCategoryEntries(context, tr, scale, titleX, cursorY,
				offset, textAreaWidth, hoverSpeed, scaledMouseX, scaledMouseY,
				lineHeight, entryMargin, headerMargin,
				category.getDisplayName(), CategoryKind.POTION, category, list,
				hoverFlag, "Container");
		}
		
		return cursorY;
	}
	
	private double renderCategoryEntries(GuiGraphics context, Font tr,
		float scale, int titleX, double cursorY, double offset,
		double textAreaWidth, double hoverSpeed, double scaledMouseX,
		double scaledMouseY, int lineHeight, int entryMargin, int headerMargin,
		String displayName, CategoryKind kind, Object category,
		List<? extends AbstractEntry> entries, boolean[] hoverFlag,
		String source)
	{
		int headerY = (int)Math.round(cursorY - offset);
		String headerText = displayName + " (" + entries.size() + ")";
		drawScaledText(context, tr, headerText, titleX, headerY, HEADER_COLOR,
			scale);
		
		String takeAllText = "Take All";
		int takeAllWidth =
			Math.max(1, Math.round(tr.width(takeAllText) * scale));
		int takeAllX = panelX + panelWidth - PANEL_PADDING - takeAllWidth;
		int takeAllY = headerY;
		boolean actionHovered = scaledMouseX >= takeAllX - 2
			&& scaledMouseX <= takeAllX + takeAllWidth + 2
			&& scaledMouseY >= takeAllY - 2
			&& scaledMouseY <= takeAllY + lineHeight + 2;
		
		drawScaledText(context, tr, takeAllText, takeAllX, takeAllY,
			actionHovered ? ACTION_HOVER_COLOR : ACTION_COLOR, scale);
		
		// source will be supplied by the caller (renderSourceSection) via a
		// temporary field in the accessor - we pass a placeholder here; the
		// real value is set by renderSourceSection wrapper below when used.
		hitboxes.add(Hitbox.forCategory(takeAllX - 2, takeAllY - 2,
			takeAllWidth + 4, lineHeight + 4, kind, category, source));
		
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
			
			double textWidth = Math.max(1.0, tr.width(entry.line) * scale);
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
				scrollX = Mth.clamp(scrollX, 0.0, Math.max(0.0, travel));
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
	
	private void rescan(AbstractContainerScreen<?> screen)
	{
		refreshEntries(screen.getMenu());
		needsRescan = false;
	}
	
	private void refreshIfDirty(AbstractContainerScreen<?> screen)
	{
		refreshEntries(screen.getMenu());
	}
	
	private void refreshEntries(AbstractContainerMenu handler)
	{
		allEntries.clear();
		gearGroupedEntries.values().forEach(List::clear);
		bookGroupedEntries.values().forEach(List::clear);
		potionGroupedEntries.values().forEach(List::clear);
		// clear player / shulker grouped maps too
		gearGroupedEntriesPlayer.values().forEach(List::clear);
		bookGroupedEntriesPlayer.values().forEach(List::clear);
		potionGroupedEntriesPlayer.values().forEach(List::clear);
		gearGroupedEntriesShulker.values().forEach(List::clear);
		bookGroupedEntriesShulker.values().forEach(List::clear);
		potionGroupedEntriesShulker.values().forEach(List::clear);
		
		List<Slot> slots = handler.slots;
		int totalSlots = slots.size();
		int containerSlots;
		if(handler instanceof ChestMenu genericHandler)
		{
			containerSlots =
				Math.min(genericHandler.getRowCount() * 9, totalSlots);
		}else
		{
			// For other handlers (shulker, player inventory, etc.) assume the
			// player inventory occupies the last 36 slots. Compute container
			// slots as total - 36. If this yields 0, fall back to using all
			// slots so we still show items.
			int playerInvSlots = 36;
			if(MC.player != null && handler == MC.player.inventoryMenu)
				containerSlots = totalSlots; // player's own inventory: show all
			else
			{
				containerSlots = Math.max(0, totalSlots - playerInvSlots);
				if(containerSlots == 0)
					containerSlots = totalSlots;
			}
		}
		
		for(int i = 0; i < containerSlots; i++)
		{
			Slot slot = slots.get(i);
			if(slot == null || !slot.hasItem())
				continue;
			
			ItemStack stack = slot.getItem();
			if(stack.isEmpty())
				continue;
				
			// If configured, inspect shulker contents stored inside container
			// items
			if(includeShulkerContents.isChecked())
			{
				ItemContainerContents container =
					stack.get(DataComponents.CONTAINER);
				if(container != null)
				{
					int parentSlotNumber = slot.getContainerSlot() + 1;
					for(ItemStack inner : container.nonEmptyItems())
					{
						if(inner == null || inner.isEmpty())
							continue;
						if(inner.is(Items.ENCHANTED_BOOK))
						{
							var ench = EnchantmentHelper
								.getEnchantmentsForCrafting(inner).entrySet();
							BookEntry entry = buildBookEntryFromStack(
								parentSlotNumber, inner, ench);
							if(entry != null)
							{
								allEntries.add(entry);
								bookGroupedEntries
									.computeIfAbsent(entry.category,
										c -> new ArrayList<>())
									.add(entry);
							}
							continue;
						}
						PotionCategory pCatInner =
							PotionCategory.fromItem(inner.getItem());
						if(pCatInner != null)
						{
							PotionEntry entry = buildPotionEntryFromStack(
								parentSlotNumber, inner, pCatInner);
							if(entry != null)
							{
								allEntries.add(entry);
								potionGroupedEntries
									.computeIfAbsent(entry.category,
										c -> new ArrayList<>())
									.add(entry);
							}
							continue;
						}
						var enchInner = EnchantmentHelper
							.getEnchantmentsForCrafting(inner).entrySet();
						if(!enchInner.isEmpty())
						{
							GearCategory gCatInner =
								GearCategory.fromStack(inner);
							if(gCatInner != null)
							{
								GearEntry entry =
									buildGearEntryFromStack(parentSlotNumber,
										inner, gCatInner, enchInner);
								if(entry != null)
								{
									allEntries.add(entry);
									gearGroupedEntries
										.computeIfAbsent(entry.category,
											c -> new ArrayList<>())
										.add(entry);
								}
							}
						}
					}
				}
			}
			
			if(stack.is(Items.ENCHANTED_BOOK))
			{
				Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments =
					EnchantmentHelper.getEnchantmentsForCrafting(stack)
						.entrySet();
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
			
			// Potions: illustrate contents and put into delivery subcategories
			PotionCategory pCat = PotionCategory.fromItem(stack.getItem());
			if(pCat != null)
			{
				PotionEntry entry = buildPotionEntry(slot, stack, pCat);
				if(entry == null)
					continue;
				allEntries.add(entry);
				potionGroupedEntries
					.computeIfAbsent(entry.category, c -> new ArrayList<>())
					.add(entry);
				continue;
			}
			
			Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments =
				EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet();
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
		
		// Optionally include player inventory slots as well
		int scanSlots = containerSlots;
		if(includePlayerInventory.isChecked())
			scanSlots = slots.size();
		
		for(int i = containerSlots; i < scanSlots; i++)
		{
			Slot slot = slots.get(i);
			if(slot == null || !slot.hasItem())
				continue;
			ItemStack stack = slot.getItem();
			if(stack.isEmpty())
				continue;
				
			// First, handle top-level enchanted books, potions and gear in
			// the player inventory the same way we handle container slots.
			if(stack.is(Items.ENCHANTED_BOOK))
			{
				Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments =
					EnchantmentHelper.getEnchantmentsForCrafting(stack)
						.entrySet();
				if(!enchantments.isEmpty())
				{
					BookEntry entry = buildBookEntry(slot, enchantments);
					if(entry != null)
					{
						allEntries.add(entry);
						bookGroupedEntriesPlayer.computeIfAbsent(entry.category,
							c -> new ArrayList<>()).add(entry);
					}
				}
				continue;
			}
			
			PotionCategory pCat = PotionCategory.fromItem(stack.getItem());
			if(pCat != null)
			{
				PotionEntry entry = buildPotionEntry(slot, stack, pCat);
				if(entry != null)
				{
					allEntries.add(entry);
					potionGroupedEntriesPlayer
						.computeIfAbsent(entry.category, c -> new ArrayList<>())
						.add(entry);
				}
				continue;
			}
			
			Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments =
				EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet();
			if(!enchantments.isEmpty())
			{
				GearCategory category = GearCategory.fromStack(stack);
				if(category != null)
				{
					GearEntry entry =
						buildGearEntry(slot, stack, category, enchantments);
					if(entry != null)
					{
						allEntries.add(entry);
						gearGroupedEntriesPlayer
							.computeIfAbsent(category, c -> new ArrayList<>())
							.add(entry);
					}
				}
				continue;
			}
			
			// If configured, inspect shulker contents stored inside inventory
			// items
			if(includeShulkerContents.isChecked())
			{
				ItemContainerContents container =
					stack.get(DataComponents.CONTAINER);
				if(container != null)
				{
					int parentSlotNumber = slot.getContainerSlot() + 1;
					for(ItemStack inner : container.nonEmptyItems())
					{
						if(inner == null || inner.isEmpty())
							continue;
						// Enchanted book inside shulker
						if(inner.is(Items.ENCHANTED_BOOK))
						{
							var ench = EnchantmentHelper
								.getEnchantmentsForCrafting(inner).entrySet();
							BookEntry entry = buildBookEntryFromStack(
								parentSlotNumber, inner, ench);
							if(entry != null)
							{
								allEntries.add(entry);
								bookGroupedEntriesShulker
									.computeIfAbsent(entry.category,
										c -> new ArrayList<>())
									.add(entry);
							}
							continue;
						}
						// Potions inside shulker
						PotionCategory pCatInner =
							PotionCategory.fromItem(inner.getItem());
						if(pCatInner != null)
						{
							PotionEntry entry = buildPotionEntryFromStack(
								parentSlotNumber, inner, pCatInner);
							if(entry != null)
							{
								allEntries.add(entry);
								potionGroupedEntriesShulker
									.computeIfAbsent(entry.category,
										c -> new ArrayList<>())
									.add(entry);
							}
							continue;
						}
						var enchInner = EnchantmentHelper
							.getEnchantmentsForCrafting(inner).entrySet();
						if(!enchInner.isEmpty())
						{
							GearCategory gCatInner =
								GearCategory.fromStack(inner);
							if(gCatInner != null)
							{
								GearEntry entry =
									buildGearEntryFromStack(parentSlotNumber,
										inner, gCatInner, enchInner);
								if(entry != null)
								{
									allEntries.add(entry);
									gearGroupedEntriesShulker
										.computeIfAbsent(entry.category,
											c -> new ArrayList<>())
										.add(entry);
								}
							}
						}
					}
				}
			}
		}
		
		// Sort container, player and shulker grouped lists
		gearGroupedEntries.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
		gearGroupedEntriesPlayer.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
		gearGroupedEntriesShulker.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
		bookGroupedEntries.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
		bookGroupedEntriesPlayer.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
		bookGroupedEntriesShulker.values().forEach(list -> list
			.sort((a, b) -> Integer.compare(a.displaySlot, b.displaySlot)));
		potionGroupedEntries.values().forEach(list -> list.sort((a, b) -> {
			int n = a.primaryName.compareToIgnoreCase(b.primaryName);
			if(n != 0)
				return n;
			n = Integer.compare(b.primaryLevel, a.primaryLevel); // higher first
			if(n != 0)
				return n;
			n = Integer.compare(b.primaryDuration, a.primaryDuration); // longer
																		// first
			if(n != 0)
				return n;
			return Integer.compare(a.displaySlot, b.displaySlot);
		}));
		potionGroupedEntriesPlayer.values()
			.forEach(list -> list.sort((a, b) -> {
				int n = a.primaryName.compareToIgnoreCase(b.primaryName);
				if(n != 0)
					return n;
				n = Integer.compare(b.primaryLevel, a.primaryLevel); // higher
																		// first
				if(n != 0)
					return n;
				n = Integer.compare(b.primaryDuration, a.primaryDuration); // longer
				// first
				if(n != 0)
					return n;
				return Integer.compare(a.displaySlot, b.displaySlot);
			}));
		potionGroupedEntriesShulker.values()
			.forEach(list -> list.sort((a, b) -> {
				int n = a.primaryName.compareToIgnoreCase(b.primaryName);
				if(n != 0)
					return n;
				n = Integer.compare(b.primaryLevel, a.primaryLevel); // higher
																		// first
				if(n != 0)
					return n;
				n = Integer.compare(b.primaryDuration, a.primaryDuration); // longer
				// first
				if(n != 0)
					return n;
				return Integer.compare(a.displaySlot, b.displaySlot);
			}));
	}
	
	private GearEntry buildGearEntry(Slot slot, ItemStack stack,
		GearCategory category,
		Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments)
	{
		List<String> parts = new ArrayList<>();
		
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments)
		{
			Holder<Enchantment> enchantmentEntry = entry.getKey();
			if(enchantmentEntry == null)
				continue;
			
			int level = entry.getIntValue();
			ResourceLocation id = enchantmentEntry.unwrapKey()
				.map(registryKey -> registryKey.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(enchantmentEntry.getRegisteredName());
			String name = buildEnchantmentName(id, path);
			String levelText = Component
				.translatable("enchantment.level." + level).getString();
			parts.add(name + " " + levelText);
		}
		
		if(parts.isEmpty())
			return null;
		
		String enchantSummary = limitLength(String.join(", ", parts), 90);
		
		int slotNumber = slot.getContainerSlot() + 1;
		String itemName = limitLength(stack.getHoverName().getString(), 40);
		
		String line = slotNumber + " - " + itemName + " | " + enchantSummary;
		
		return new GearEntry(slot.index, slotNumber, category, line);
	}
	
	// Build a GearEntry for an item inside a shulker box (no real slot id)
	private GearEntry buildGearEntryFromStack(int parentSlotNumber,
		ItemStack stack, GearCategory category,
		Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments)
	{
		List<String> parts = new ArrayList<>();
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments)
		{
			Holder<Enchantment> enchantmentEntry = entry.getKey();
			if(enchantmentEntry == null)
				continue;
			int level = entry.getIntValue();
			ResourceLocation id = enchantmentEntry.unwrapKey()
				.map(registryKey -> registryKey.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(enchantmentEntry.getRegisteredName());
			String name = buildEnchantmentName(id, path);
			String levelText = Component
				.translatable("enchantment.level." + level).getString();
			parts.add(name + " " + levelText);
		}
		if(parts.isEmpty())
			return null;
		String enchantSummary = limitLength(String.join(", ", parts), 90);
		String itemName = limitLength(stack.getHoverName().getString(), 40);
		String line = parentSlotNumber + " - " + itemName + " | "
			+ enchantSummary + " (in shulker)";
		return new GearEntry(-1, parentSlotNumber, category, line);
	}
	
	private BookEntry buildBookEntry(Slot slot,
		Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments)
	{
		List<String> parts = new ArrayList<>();
		EnumSet<BookCategory> categories = EnumSet.noneOf(BookCategory.class);
		
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments)
		{
			Holder<Enchantment> enchantmentEntry = entry.getKey();
			if(enchantmentEntry == null)
				continue;
			
			int level = entry.getIntValue();
			ResourceLocation id = enchantmentEntry.unwrapKey()
				.map(registryKey -> registryKey.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(enchantmentEntry.getRegisteredName());
			BookCategory category = BookCategory.fromPath(path);
			categories.add(category);
			
			String name = buildEnchantmentName(id, path);
			String levelText = Component
				.translatable("enchantment.level." + level).getString();
			parts.add(name + " " + levelText);
		}
		
		if(parts.isEmpty())
			return null;
		
		BookCategory primary =
			categories.stream().filter(c -> c != BookCategory.MISC).findFirst()
				.orElseGet(() -> categories.stream().findFirst()
					.orElse(BookCategory.MISC));
		
		String enchantSummary = limitLength(String.join(", ", parts), 90);
		int slotNumber = slot.getContainerSlot() + 1;
		String line = slotNumber + " - " + enchantSummary;
		
		return new BookEntry(slot.index, slotNumber, primary, line);
	}
	
	// Build a BookEntry for an item inside a shulker box (no real slot id)
	private BookEntry buildBookEntryFromStack(int parentSlotNumber,
		ItemStack stack,
		Set<Object2IntMap.Entry<Holder<Enchantment>>> enchantments)
	{
		List<String> parts = new ArrayList<>();
		EnumSet<BookCategory> categories = EnumSet.noneOf(BookCategory.class);
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments)
		{
			Holder<Enchantment> enchantmentEntry = entry.getKey();
			if(enchantmentEntry == null)
				continue;
			int level = entry.getIntValue();
			ResourceLocation id = enchantmentEntry.unwrapKey()
				.map(registryKey -> registryKey.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(enchantmentEntry.getRegisteredName());
			BookCategory category = BookCategory.fromPath(path);
			categories.add(category);
			String name = buildEnchantmentName(id, path);
			String levelText = Component
				.translatable("enchantment.level." + level).getString();
			parts.add(name + " " + levelText);
		}
		if(parts.isEmpty())
			return null;
		BookCategory primary =
			categories.stream().filter(c -> c != BookCategory.MISC).findFirst()
				.orElseGet(() -> categories.stream().findFirst()
					.orElse(BookCategory.MISC));
		String enchantSummary = limitLength(String.join(", ", parts), 90);
		String line =
			parentSlotNumber + " - " + enchantSummary + " (in shulker)";
		return new BookEntry(-1, parentSlotNumber, primary, line);
	}
	
	private PotionEntry buildPotionEntry(Slot slot, ItemStack stack,
		PotionCategory category)
	{
		List<String> parts = new ArrayList<>();
		PotionContents potionContents = stack.getComponents()
			.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
		
		// First list all actual effects
		for(MobEffectInstance effectInstance : potionContents.getAllEffects())
		{
			Holder<MobEffect> effEntry = effectInstance.getEffect();
			ResourceLocation id =
				effEntry.unwrapKey().map(k -> k.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(effEntry.getRegisteredName());
			String name = buildEffectName(id, path);
			String ampText = effectInstance.getAmplifier() > 0
				? " " + (effectInstance.getAmplifier() + 1) : "";
			String durText = effectInstance.getDuration() > 0
				? " " + Math.max(0, effectInstance.getDuration() / 20) + "s"
				: "";
			parts.add(name + ampText + durText);
		}
		
		// If there are no effects, fall back to base potion name (water,
		// mundane, etc.)
		if(parts.isEmpty())
		{
			var basePotion = potionContents.potion();
			if(basePotion.isPresent())
			{
				Holder<Potion> p = basePotion.get();
				ResourceLocation id =
					p.unwrapKey().map(k -> k.location()).orElse(null);
				String path = id != null ? id.getPath()
					: sanitizePath(p.getRegisteredName());
				String name = buildPotionName(id, path);
				parts.add(name);
			}
		}
		
		String primaryName = null;
		int primaryLevel = 0;
		int primaryDuration = 0;
		
		Iterable<MobEffectInstance> effects = potionContents.getAllEffects();
		java.util.Iterator<MobEffectInstance> it = effects.iterator();
		if(it.hasNext())
		{
			MobEffectInstance first = it.next();
			ResourceLocation id = first.getEffect().unwrapKey()
				.map(k -> k.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(first.getEffect().getRegisteredName());
			primaryName = buildEffectName(id, path);
			primaryLevel = first.getAmplifier() + 1;
			primaryDuration = Math.max(0, first.getDuration());
		}else
		{
			var basePotion = potionContents.potion();
			if(basePotion.isPresent())
			{
				ResourceLocation id = basePotion.get().unwrapKey()
					.map(k -> k.location()).orElse(null);
				String path = id != null ? id.getPath()
					: sanitizePath(basePotion.get().getRegisteredName());
				primaryName = buildPotionName(id, path);
			}else
			{
				primaryName = "Unknown";
			}
		}
		
		if(parts.isEmpty())
			return null;
		
		String summary = limitLength(String.join(", ", parts), 90);
		int slotNumber = slot.getContainerSlot() + 1;
		String itemName = limitLength(stack.getHoverName().getString(), 40);
		String line = slotNumber + " - " + itemName + " | " + summary;
		
		return new PotionEntry(slot.index, slotNumber, category, line,
			primaryName, primaryLevel, primaryDuration);
	}
	
	private PotionEntry buildPotionEntryFromStack(int parentSlotNumber,
		ItemStack stack, PotionCategory category)
	{
		List<String> parts = new ArrayList<>();
		PotionContents potionContents = stack.getComponents()
			.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
		for(MobEffectInstance effectInstance : potionContents.getAllEffects())
		{
			Holder<MobEffect> effEntry = effectInstance.getEffect();
			ResourceLocation id =
				effEntry.unwrapKey().map(k -> k.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(effEntry.getRegisteredName());
			String name = buildEffectName(id, path);
			String ampText = effectInstance.getAmplifier() > 0
				? " " + (effectInstance.getAmplifier() + 1) : "";
			String durText = effectInstance.getDuration() > 0
				? " " + Math.max(0, effectInstance.getDuration() / 20) + "s"
				: "";
			parts.add(name + ampText + durText);
		}
		if(parts.isEmpty())
		{
			var basePotion = potionContents.potion();
			if(basePotion.isPresent())
			{
				Holder<Potion> p = basePotion.get();
				ResourceLocation id =
					p.unwrapKey().map(k -> k.location()).orElse(null);
				String path = id != null ? id.getPath()
					: sanitizePath(p.getRegisteredName());
				String name = buildPotionName(id, path);
				parts.add(name);
			}
		}
		if(parts.isEmpty())
			return null;
		String summary = limitLength(String.join(", ", parts), 90);
		String itemName = limitLength(stack.getHoverName().getString(), 40);
		String line = parentSlotNumber + " - " + itemName + " | " + summary
			+ " (in shulker)";
		String primaryName = null;
		int primaryLevel = 0;
		int primaryDuration = 0;
		Iterable<MobEffectInstance> effects = potionContents.getAllEffects();
		java.util.Iterator<MobEffectInstance> it = effects.iterator();
		if(it.hasNext())
		{
			MobEffectInstance first = it.next();
			ResourceLocation id = first.getEffect().unwrapKey()
				.map(k -> k.location()).orElse(null);
			String path = id != null ? id.getPath()
				: sanitizePath(first.getEffect().getRegisteredName());
			primaryName = buildEffectName(id, path);
			primaryLevel = first.getAmplifier() + 1;
			primaryDuration = Math.max(0, first.getDuration());
		}else
		{
			var basePotion = potionContents.potion();
			if(basePotion.isPresent())
			{
				ResourceLocation id = basePotion.get().unwrapKey()
					.map(k -> k.location()).orElse(null);
				String path = id != null ? id.getPath()
					: sanitizePath(basePotion.get().getRegisteredName());
				primaryName = buildPotionName(id, path);
			}else
			{
				primaryName = "Unknown";
			}
		}
		return new PotionEntry(-1, parentSlotNumber, category, line,
			primaryName, primaryLevel, primaryDuration);
	}
	
	private void takeEntry(GearEntry entry, AbstractContainerMenu handler)
	{
		if(MC.player == null || MC.gameMode == null || entry == null)
			return;
		
		// slotId < 0 indicates a display-only entry (e.g. shulker-inner)
		if(entry.slotId < 0)
			return;
		
		Slot slot = getSlotSafe(handler, entry.slotId);
		if(slot == null || !slot.hasItem())
			return;
		
		MC.gameMode.handleInventoryMouseClick(handler.containerId, entry.slotId,
			0, ClickType.QUICK_MOVE, MC.player);
		needsRescan = true;
	}
	
	private void takeEntry(BookEntry entry, AbstractContainerMenu handler)
	{
		if(MC.player == null || MC.gameMode == null || entry == null)
			return;
		
		if(entry.slotId < 0)
			return;
		
		Slot slot = getSlotSafe(handler, entry.slotId);
		if(slot == null || !slot.hasItem())
			return;
		
		MC.gameMode.handleInventoryMouseClick(handler.containerId, entry.slotId,
			0, ClickType.QUICK_MOVE, MC.player);
		needsRescan = true;
	}
	
	private void takeEntry(PotionEntry entry, AbstractContainerMenu handler)
	{
		if(MC.player == null || MC.gameMode == null || entry == null)
			return;
		
		if(entry.slotId < 0)
			return;
		
		Slot slot = getSlotSafe(handler, entry.slotId);
		if(slot == null || !slot.hasItem())
			return;
		
		MC.gameMode.handleInventoryMouseClick(handler.containerId, entry.slotId,
			0, ClickType.QUICK_MOVE, MC.player);
		needsRescan = true;
	}
	
	private void takeGearCategory(GearCategory category,
		AbstractContainerMenu handler, String source)
	{
		if(MC.player == null || MC.gameMode == null)
			return;
		
		List<GearEntry> list;
		switch(source)
		{
			case "Player Inventory":
			list = gearGroupedEntriesPlayer.get(category);
			break;
			case "Shulker contents":
			list = gearGroupedEntriesShulker.get(category);
			break;
			default:
			list = gearGroupedEntries.get(category);
		}
		
		if(list == null || list.isEmpty())
			return;
		
		for(GearEntry entry : new ArrayList<>(list))
		{
			if(entry.slotId < 0)
				continue; // display-only (in shulker)
			Slot slot = getSlotSafe(handler, entry.slotId);
			if(slot == null || !slot.hasItem())
				continue;
			
			MC.gameMode.handleInventoryMouseClick(handler.containerId,
				entry.slotId, 0, ClickType.QUICK_MOVE, MC.player);
		}
		
		needsRescan = true;
	}
	
	private void takeBookCategory(BookCategory category,
		AbstractContainerMenu handler, String source)
	{
		if(MC.player == null || MC.gameMode == null)
			return;
		
		List<BookEntry> list;
		switch(source)
		{
			case "Player Inventory":
			list = bookGroupedEntriesPlayer.get(category);
			break;
			case "Shulker contents":
			list = bookGroupedEntriesShulker.get(category);
			break;
			default:
			list = bookGroupedEntries.get(category);
		}
		
		if(list == null || list.isEmpty())
			return;
		
		for(BookEntry entry : new ArrayList<>(list))
		{
			if(entry.slotId < 0)
				continue;
			Slot slot = getSlotSafe(handler, entry.slotId);
			if(slot == null || !slot.hasItem())
				continue;
			MC.gameMode.handleInventoryMouseClick(handler.containerId,
				entry.slotId, 0, ClickType.QUICK_MOVE, MC.player);
		}
		
		needsRescan = true;
	}
	
	private void takePotionCategory(PotionCategory category,
		AbstractContainerMenu handler, String source)
	{
		if(MC.player == null || MC.gameMode == null)
			return;
		
		List<PotionEntry> list;
		switch(source)
		{
			case "Player Inventory":
			list = potionGroupedEntriesPlayer.get(category);
			break;
			case "Shulker contents":
			list = potionGroupedEntriesShulker.get(category);
			break;
			default:
			list = potionGroupedEntries.get(category);
		}
		
		if(list == null || list.isEmpty())
			return;
		
		for(PotionEntry entry : new ArrayList<>(list))
		{
			if(entry.slotId < 0)
				continue;
			Slot slot = getSlotSafe(handler, entry.slotId);
			if(slot == null || !slot.hasItem())
				continue;
			MC.gameMode.handleInventoryMouseClick(handler.containerId,
				entry.slotId, 0, ClickType.QUICK_MOVE, MC.player);
		}
		
		needsRescan = true;
	}
	
	private Slot getSlotSafe(AbstractContainerMenu handler, int slotId)
	{
		if(handler == null || slotId < 0)
			return null;
		
		List<Slot> slots = handler.slots;
		if(slots == null || slotId >= slots.size())
			return null;
		
		return handler.getSlot(slotId);
	}
	
	private int panelInnerHeight()
	{
		float scale = Mth.clamp(textScale.getValueF(), 0.5F, 1.25F);
		Font tr = MC.font;
		int titleHeight = Math.max(1, Math.round(tr.lineHeight * scale));
		int headerMargin = Math.max(1, Math.round(HEADER_MARGIN * scale));
		int contentTop =
			panelY + PANEL_PADDING + titleHeight + headerMargin + 1;
		int contentBottom = panelY + panelHeight - PANEL_PADDING;
		return Math.max(0, contentBottom - contentTop);
	}
	
	private static double getScaledMouseX(GuiGraphics context)
	{
		Window window = MC.getWindow();
		return MC.mouseHandler.xpos() * context.guiWidth()
			/ window.getScreenWidth();
	}
	
	private static double getScaledMouseY(GuiGraphics context)
	{
		Window window = MC.getWindow();
		return MC.mouseHandler.ypos() * context.guiHeight()
			/ window.getScreenHeight();
	}
	
	private static void drawScaledText(GuiGraphics context, Font tr,
		String text, float x, float y, int color, float scale)
	{
		RenderUtils.drawScaledText(context, tr, text, Math.round(x),
			Math.round(y), color, false, scale);
	}
	
	private void drawSectionHeader(GuiGraphics context, Font tr, int x, int y,
		String text, float scale)
	{
		int textWidth = Math.max(1, Math.round(tr.width(text) * scale));
		int textHeight = Math.max(1, Math.round(tr.lineHeight * scale));
		int left = Math.round(x - 4);
		int right = Math.round(x + textWidth + 4);
		int top = Math.round(y - 4);
		int bottom = Math.round(y + textHeight + 4);
		context.fill(left, top, right, bottom, 0xC0282828);
		drawScaledText(context, tr, text, x, y, TITLE_COLOR, scale);
	}
	
	private static String limitLength(String text, int max)
	{
		if(text.length() <= max)
			return text;
		return text.substring(0, Math.max(0, max - 3)) + "...";
	}
	
	private static String buildEnchantmentName(ResourceLocation id, String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown Enchant";
		
		String namespace = id != null ? id.getNamespace() : "minecraft";
		String key = "enchantment." + namespace + "." + path;
		String translated = Component.translatable(key).getString();
		if(translated.equals(key))
			return humanize(path);
		return translated;
	}
	
	private static String buildEffectName(ResourceLocation id, String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown Effect";
		
		String namespace = id != null ? id.getNamespace() : "minecraft";
		String key = "effect." + namespace + "." + path;
		String translated = Component.translatable(key).getString();
		if(translated.equals(key))
			return humanize(path);
		return translated;
	}
	
	private static String buildPotionName(ResourceLocation id, String path)
	{
		if(path == null || path.isEmpty())
			return "Unknown Potion";
		
		String namespace = id != null ? id.getNamespace() : "minecraft";
		String key = "potion." + namespace + "." + path;
		String translated = Component.translatable(key).getString();
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
	
	private static final class PotionEntry extends AbstractEntry
	{
		final PotionCategory category;
		final String primaryName;
		final int primaryLevel;
		final int primaryDuration; // ticks
		
		PotionEntry(int slotId, int displaySlot, PotionCategory category,
			String line, String primaryName, int primaryLevel,
			int primaryDuration)
		{
			super(slotId, displaySlot, line);
			this.category = Objects.requireNonNull(category);
			this.primaryName = primaryName != null ? primaryName : "";
			this.primaryLevel = primaryLevel;
			this.primaryDuration = primaryDuration;
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
		final String source; // "Container", "Player Inventory" or "Shulker
								// contents"
		
		private Hitbox(int x, int y, int width, int height, AbstractEntry entry,
			CategoryKind categoryKind, Object category, String source)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.entry = entry;
			this.categoryKind = categoryKind;
			this.category = category;
			this.source = source;
		}
		
		static Hitbox forEntry(int x, int y, int width, int height,
			AbstractEntry entry)
		{
			return new Hitbox(x, y, width, height, entry, null, null,
				"Container");
		}
		
		static Hitbox forCategory(int x, int y, int width, int height,
			CategoryKind kind, Object category, String source)
		{
			return new Hitbox(x, y, width, height, null, kind, category,
				source);
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
		BOOK,
		POTION;
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
						if(stack.is(Items.SHIELD))
							yield SHIELD;
						yield null;
					}
					default -> null;
				};
			
			if(stack.is(Items.SHIELD))
				return SHIELD;
			
			if(stack.is(Items.TRIDENT) || stack.is(Items.BOW)
				|| stack.is(Items.CROSSBOW))
				return WEAPON;
			
			Weapon weapon = stack.getComponents().get(DataComponents.WEAPON);
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
	
	private static enum PotionCategory
	{
		NORMAL("Potions (Normal)"),
		SPLASH("Potions (Splash)"),
		LINGERING("Potions (Lingering)");
		
		static final List<PotionCategory> ORDERED =
			List.of(PotionCategory.values());
		
		private final String displayName;
		
		PotionCategory(String displayName)
		{
			this.displayName = displayName;
		}
		
		String getDisplayName()
		{
			return displayName;
		}
		
		static PotionCategory fromItem(Item item)
		{
			if(item == Items.POTION)
				return NORMAL;
			if(item == Items.SPLASH_POTION)
				return SPLASH;
			if(item == Items.LINGERING_POTION)
				return LINGERING;
			return null;
		}
	}
}
