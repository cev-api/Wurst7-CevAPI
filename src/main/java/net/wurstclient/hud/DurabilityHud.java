/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.DurabilityHudHack;
import net.wurstclient.mixin.GuiAccessor;
import net.wurstclient.util.RenderUtils;

public final class DurabilityHud
{
	private static final Minecraft MC = WurstClient.MC;
	private static final EquipmentSlot[] ARMOR_SLOTS =
		new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
			EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final float BAR_HEIGHT = 4F;
	private static final float BOSS_BAR_HEIGHT = 6F;
	private static final float TEXT_GAP = 2F;
	private static final float BASE_MARGIN = 6F;
	private static final float DRAG_PADDING = 4F;
	private static final float ACTIONBAR_AVOID_ZONE_HEIGHT = 120F;
	private static final float ACTIONBAR_AVOID_EXTRA_MARGIN = 4F;
	private static final float SELECTED_ITEM_Y_OFFSET = 59F;
	private static final float SELECTED_ITEM_CREATIVE_Y_BONUS = 14F;
	private static final float OVERLAY_MESSAGE_Y_OFFSET = 72F;
	private static final float TEXT_BLOCK_CLEARANCE = 4F;
	private static final float TEXT_LINE_SPACING = 1F;
	private static final float TEXT_BACKDROP_PADDING = 2F;
	
	private final DurabilityHudHack hack;
	
	private float offsetX;
	private float offsetY;
	private boolean dragging;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private float dragStartOffsetX;
	private float dragStartOffsetY;
	
	public DurabilityHud(DurabilityHudHack hack)
	{
		this.hack = hack;
	}
	
	public void render(GuiGraphics context)
	{
		if(MC == null || hack == null || !hack.isEnabled())
			return;
		
		Player player = MC.player;
		if(player == null)
			return;
		
		List<ItemStack> armorStacks = new ArrayList<>();
		for(EquipmentSlot slot : ARMOR_SLOTS)
		{
			ItemStack stack = player.getItemBySlot(slot);
			if(hasDurability(stack))
				armorStacks.add(stack);
		}
		
		List<ItemStack> handStacks = new ArrayList<>();
		ItemStack mainHand = player.getItemBySlot(EquipmentSlot.MAINHAND);
		if(hasDurability(mainHand))
			handStacks.add(mainHand);
		if(hack.isShowOffhand())
		{
			ItemStack offHand = player.getItemBySlot(EquipmentSlot.OFFHAND);
			if(hasDurability(offHand))
				handStacks.add(offHand);
		}
		
		int iconCount = armorStacks.size() + handStacks.size();
		if(iconCount == 0)
			return;
		
		Font font = MC.font;
		if(font == null)
			return;
		
		boolean hasArmor = !armorStacks.isEmpty();
		float iconSize = (float)hack.getIconSize();
		float iconSpacing = (float)hack.getIconSpacing();
		float fontScale = (float)hack.getFontScale();
		boolean verticalLayout =
			hack.getIconLayout() == DurabilityHudHack.IconLayout.VERTICAL;
		boolean sideInfo = hack
			.getVerticalInfoPosition() == DurabilityHudHack.VerticalInfoPosition.SIDE;
		DurabilityHudHack.DisplayMode displayMode = hack.getDisplayMode();
		boolean showBar =
			displayMode != DurabilityHudHack.DisplayMode.PERCENT_ONLY;
		boolean showPercent =
			displayMode != DurabilityHudHack.DisplayMode.BAR_ONLY;
		boolean bossBar = hack.isBossBarStyle();
		float barHeight =
			showBar ? (bossBar ? BOSS_BAR_HEIGHT : BAR_HEIGHT) : 0F;
		float scaledTextHeight = showPercent ? font.lineHeight * fontScale : 0F;
		
		float infoHeight = 0F;
		if(showBar)
			infoHeight += barHeight;
		if(showPercent)
			infoHeight += (infoHeight > 0 ? TEXT_GAP : 0F) + scaledTextHeight;
		
		float slotHeight = iconSize;
		float slotWidth = iconSize;
		float infoWidth = iconSize;
		if(sideInfo)
		{
			infoWidth = iconSize;
			slotWidth += TEXT_GAP + infoWidth;
			slotHeight = Math.max(slotHeight, infoHeight);
		}else
		{
			if(showBar)
				slotHeight += TEXT_GAP + barHeight;
			if(showPercent)
				slotHeight += TEXT_GAP + scaledTextHeight;
		}
		
		float anchorY = context.guiHeight() - (hasArmor ? 49 : 39);
		float totalHeight =
			verticalLayout ? iconCount * slotHeight : slotHeight;
		if(iconCount > 1 && verticalLayout)
			totalHeight += iconSpacing * (iconCount - 1);
		float baseIconY = anchorY - totalHeight - BASE_MARGIN;
		baseIconY = Math.max(baseIconY, BASE_MARGIN);
		float totalWidth = verticalLayout ? slotWidth : iconCount * slotWidth;
		if(iconCount > 1 && !verticalLayout)
			totalWidth += iconSpacing * (iconCount - 1);
		float baseStartX = context.guiWidth() / 2F - totalWidth / 2F;
		float startX = baseStartX + offsetX;
		float iconY = baseIconY + offsetY;
		
		handleDrag(context, startX, iconY, totalWidth, totalHeight);
		startX = baseStartX + offsetX;
		iconY = baseIconY + offsetY;
		
		if(hack.avoidActionbarText())
		{
			float avoid = getBottomTextAvoidOffset(context, iconY, totalHeight);
			iconY = Math.max(iconY - avoid, BASE_MARGIN);
		}
		
		List<ItemStack> combined = new ArrayList<>(iconCount);
		combined.addAll(armorStacks);
		combined.addAll(handStacks);
		
		boolean gradientColor = hack.useGradientFontColor();
		int fontColor = hack.getFontColorI();
		for(int i = 0; i < combined.size(); i++)
		{
			ItemStack stack = combined.get(i);
			float x = verticalLayout ? startX
				: startX + i * (slotWidth + iconSpacing);
			float y =
				verticalLayout ? iconY + i * (slotHeight + iconSpacing) : iconY;
			renderSlot(context, font, stack, x, y, iconSize, showBar,
				showPercent, barHeight, fontScale, bossBar, fontColor,
				gradientColor, sideInfo, infoWidth);
		}
	}
	
	private void renderSlot(GuiGraphics context, Font font, ItemStack stack,
		float x, float y, float iconSize, boolean showBar, boolean showPercent,
		float barHeight, float fontScale, boolean bossBar, int baseTextColor,
		boolean useGradientText, boolean infoToSide, float infoWidth)
	{
		double fraction = getDurabilityFraction(stack);
		float fillWidth = (float)(fraction * iconSize);
		float clampedWidth = Mth.clamp(fillWidth, 0F, iconSize);
		int fillColor = getDurabilityColor(fraction);
		
		drawItem(context, stack, x, y, iconSize);
		float barY = 0F;
		float textY = 0F;
		float barX = x;
		float textX = x;
		if(infoToSide)
		{
			float infoX = x + iconSize + TEXT_GAP;
			float scaledTextHeight =
				showPercent ? font.lineHeight * fontScale : 0F;
			float infoBlockHeight = 0F;
			if(showBar)
				infoBlockHeight += barHeight;
			if(showPercent)
				infoBlockHeight +=
					(infoBlockHeight > 0 ? TEXT_GAP : 0F) + scaledTextHeight;
			float cursorY = y + (iconSize - infoBlockHeight) / 2F;
			barX = infoX + (infoWidth - iconSize) / 2F;
			textX = infoX;
			if(showBar)
			{
				barY = cursorY;
				cursorY += barHeight + TEXT_GAP;
			}
			if(showPercent)
				textY = cursorY;
		}else
		{
			if(showBar)
				barY = y + iconSize + TEXT_GAP;
			if(showPercent)
				textY = showBar ? barY + barHeight + TEXT_GAP
					: y + iconSize + TEXT_GAP;
		}
		
		if(showBar && barHeight > 0F)
		{
			int background = bossBar ? 0xFF101820 : 0xFF202020;
			RenderUtils.fill2D(context, barX, barY, barX + iconSize,
				barY + barHeight, background);
			if(clampedWidth > 0F)
				RenderUtils.fill2D(context, barX, barY, barX + clampedWidth,
					barY + barHeight, fillColor);
			if(bossBar)
			{
				RenderUtils.drawBorder2D(context, barX, barY, barX + iconSize,
					barY + barHeight, 0xFF909090);
				float highlightHeight = Math.min(2F, barHeight);
				RenderUtils.fill2D(context, barX, barY, barX + iconSize,
					barY + highlightHeight, 0x30FFFFFF);
			}
		}
		
		if(showPercent)
		{
			String label = Math.round(fraction * 100) + "%";
			double textWidth = font.width(label) * fontScale;
			float drawX;
			if(infoToSide)
				drawX = textX + (infoWidth - (float)textWidth) / 2F;
			else
				drawX = x + (iconSize - (float)textWidth) / 2F;
			int actualColor = baseTextColor;
			if(useGradientText)
				actualColor = colorForFraction(fraction);
			RenderUtils.drawScaledText(context, font, label, Math.round(drawX),
				Math.round(textY), actualColor, true, fontScale);
		}
	}
	
	private void drawItem(GuiGraphics context, ItemStack stack, float x,
		float y, float size)
	{
		context.pose().pushMatrix();
		context.pose().translate(x, y);
		float scale = size / 16F;
		context.pose().scale(scale, scale);
		context.renderItem(stack, 0, 0);
		context.pose().popMatrix();
	}
	
	private void handleDrag(GuiGraphics context, float startX, float iconY,
		float totalWidth, float rowHeight)
	{
		if(MC == null)
			return;
		
		boolean containerOpen = MC.screen instanceof AbstractContainerScreen<?>;
		if(!containerOpen)
		{
			dragging = false;
			return;
		}
		
		Window window = MC.getWindow();
		if(window == null)
		{
			dragging = false;
			return;
		}
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean overHud = isMouseOverHud(mouseX, mouseY, startX, iconY,
			totalWidth, rowHeight);
		
		if(leftDown && overHud)
		{
			if(!dragging)
			{
				dragging = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				dragStartOffsetX = offsetX;
				dragStartOffsetY = offsetY;
			}
			
			float dx = (float)(mouseX - dragStartMouseX);
			float dy = (float)(mouseY - dragStartMouseY);
			offsetX = dragStartOffsetX + dx;
			offsetY = dragStartOffsetY + dy;
			return;
		}
		
		if(!leftDown)
			dragging = false;
	}
	
	private static float getBottomTextAvoidOffset(GuiGraphics context,
		float hudY, float hudHeight)
	{
		if(MC == null || MC.font == null || MC.gui == null
			|| !(MC.gui instanceof GuiAccessor accessor))
			return 0F;
		
		if(hudY < context.guiHeight() - ACTIONBAR_AVOID_ZONE_HEIGHT)
			return 0F;
		
		float hudBottom = hudY + hudHeight;
		float requiredShift = 0F;
		
		HotbarHighlightState hotbar = getHotbarHighlightState(accessor);
		if(hotbar.isVisible())
		{
			DurabilityHudHack hack = WurstClient.INSTANCE != null
				? WurstClient.INSTANCE.getHax().durabilityHudHack : null;
			boolean includeEnchantments = hack != null && hack.isEnabled()
				&& hack.isShowHotbarEnchantments();
			float top =
				getHotbarTextTop(context, MC.font, hotbar, includeEnchantments);
			requiredShift = Math.max(requiredShift,
				hudBottom + ACTIONBAR_AVOID_EXTRA_MARGIN - top);
		}
		
		if(isOverlayMessageVisible())
		{
			float overlayTop = context.guiHeight() - OVERLAY_MESSAGE_Y_OFFSET;
			requiredShift = Math.max(requiredShift,
				hudBottom + ACTIONBAR_AVOID_EXTRA_MARGIN - overlayTop);
		}
		
		return Math.max(0F, requiredShift);
	}
	
	public static boolean renderSelectedItemNameWithEnchantments(
		GuiGraphics context)
	{
		if(MC == null || MC.gui == null
			|| !(MC.gui instanceof GuiAccessor accessor))
			return false;
		
		DurabilityHudHack hack = WurstClient.INSTANCE != null
			? WurstClient.INSTANCE.getHax().durabilityHudHack : null;
		if(hack == null || !hack.isEnabled()
			|| !hack.isShowHotbarEnchantments())
			return false;
		
		HotbarHighlightState state = getHotbarHighlightState(accessor);
		if(!state.isVisible() || state.stack().isEmpty() || MC == null
			|| MC.gameMode == null
			|| MC.gameMode.getPlayerMode() == GameType.SPECTATOR)
			return false;
		
		List<Component> lines = getEnchantmentLines(state.stack());
		
		int alpha = getHotbarHighlightAlpha(state.timer());
		if(alpha <= 0)
			return true;
		
		Font font = MC.font;
		if(font == null)
			return true;
		
		MutableComponent nameComponent =
			Component.empty().append(state.stack().getHoverName())
				.withStyle(state.stack().getRarity().color());
		if(state.stack().has(DataComponents.CUSTOM_NAME))
			nameComponent.withStyle(ChatFormatting.ITALIC);
		
		int y = Math.round(getHotbarTextTop(context, font, state, true));
		
		int alphaColor = ARGB.white(alpha);
		int nameWidth = font.width(nameComponent);
		int nameX = (context.guiWidth() - nameWidth) / 2;
		context.drawStringWithBackdrop(font, nameComponent, nameX, y, nameWidth,
			alphaColor);
		
		if(lines.isEmpty())
			return true;
		
		y += font.lineHeight + 1;
		for(Component line : lines)
		{
			int width = font.width(line);
			int x = (context.guiWidth() - width) / 2;
			context.drawStringWithBackdrop(font, line, x, y, width, alphaColor);
			y += font.lineHeight;
		}
		
		return true;
	}
	
	private static List<Component> getEnchantmentLines(ItemStack stack)
	{
		List<Component> lines = new ArrayList<>();
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		appendEnchantmentLines(lines, seen, stack
			.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		appendEnchantmentLines(lines, seen, stack.getOrDefault(
			DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
		return lines;
	}
	
	private static void appendEnchantmentLines(List<Component> out,
		Set<String> seen, ItemEnchantments enchantments)
	{
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments
			.entrySet())
		{
			Holder<Enchantment> holder = entry.getKey();
			int level = entry.getIntValue();
			String key = holder.getRegisteredName() + "#" + level;
			if(!seen.add(key))
				continue;
			out.add(Enchantment.getFullname(holder, level));
		}
	}
	
	private static int getHotbarHighlightAlpha(int timer)
	{
		int alpha = (int)(timer * 256.0F / 10.0F);
		return Math.min(alpha, 255);
	}
	
	private static float getHotbarTextTop(GuiGraphics context, Font font,
		HotbarHighlightState state, boolean includeEnchantments)
	{
		float y = context.guiHeight() - SELECTED_ITEM_Y_OFFSET;
		if(MC.gameMode != null && !MC.gameMode.canHurtPlayer())
			y += SELECTED_ITEM_CREATIVE_Y_BONUS;
		
		int lines = 1;
		if(includeEnchantments)
			lines += getEnchantmentLines(state.stack()).size();
		
		float textHeight = lines * font.lineHeight;
		if(lines > 1)
			textHeight += (lines - 1) * TEXT_LINE_SPACING;
		// drawStringWithBackdrop renders a small padded backdrop around text.
		textHeight += TEXT_BACKDROP_PADDING * 2F;
		float textBottom = y + textHeight;
		float barsTop = getStatusBarsTopY(context);
		float overlap = textBottom + TEXT_BLOCK_CLEARANCE - barsTop;
		if(overlap > 0F)
			y -= overlap;
		
		return Math.max(BASE_MARGIN, y);
	}
	
	private static float getStatusBarsTopY(GuiGraphics context)
	{
		if(MC == null || MC.player == null)
			return context.guiHeight() - SELECTED_ITEM_Y_OFFSET;
		
		boolean hasArmor = MC.player.getArmorValue() > 0;
		return context.guiHeight() - (hasArmor ? 49F : 39F);
	}
	
	private static boolean isOverlayMessageVisible()
	{
		if(MC == null || MC.gui == null
			|| !(MC.gui instanceof GuiAccessor accessor))
			return false;
		
		int time = accessor.getOverlayMessageTime();
		if(time <= 0)
			return false;
		
		Component msg = accessor.getOverlayMessageString();
		return msg != null && !msg.getString().isEmpty();
	}
	
	private static HotbarHighlightState getHotbarHighlightState(
		GuiAccessor accessor)
	{
		int timer = accessor.getToolHighlightTimer();
		ItemStack stack = accessor.getLastToolHighlight();
		if(stack == null)
			stack = ItemStack.EMPTY;
		return new HotbarHighlightState(timer, stack);
	}
	
	private static boolean isMouseOverHud(double mouseX, double mouseY,
		float startX, float iconY, float totalWidth, float rowHeight)
	{
		float left = startX - DRAG_PADDING;
		float right = startX + totalWidth + DRAG_PADDING;
		float top = iconY - DRAG_PADDING;
		float bottom = iconY + rowHeight + DRAG_PADDING;
		
		return mouseX >= left && mouseX <= right && mouseY >= top
			&& mouseY <= bottom;
	}
	
	private static double getScaledMouseX(GuiGraphics context)
	{
		Window window = MC.getWindow();
		if(window == null)
			return 0;
		return MC.mouseHandler.xpos() * context.guiWidth()
			/ window.getScreenWidth();
	}
	
	private static double getScaledMouseY(GuiGraphics context)
	{
		Window window = MC.getWindow();
		if(window == null)
			return 0;
		return MC.mouseHandler.ypos() * context.guiHeight()
			/ window.getScreenHeight();
	}
	
	private static boolean hasDurability(ItemStack stack)
	{
		return stack != null && !stack.isEmpty() && stack.isDamageableItem()
			&& stack.getMaxDamage() > 0;
	}
	
	private record HotbarHighlightState(int timer, ItemStack stack)
	{
		private boolean isVisible()
		{
			return timer > 0 && stack != null && !stack.isEmpty();
		}
	}
	
	private static double getDurabilityFraction(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return 0.0;
		if(!stack.isDamageableItem())
			return 1.0;
		int maxDamage = stack.getMaxDamage();
		if(maxDamage <= 0)
			return 1.0;
		int damage = stack.getDamageValue();
		return Mth.clamp((maxDamage - damage) / (double)maxDamage, 0.0, 1.0);
	}
	
	private static int getDurabilityColor(double fraction)
	{
		float clamped = (float)Mth.clamp(fraction, 0.0, 1.0);
		int red = (int)Math.round(255 * (1F - clamped));
		int green = (int)Math.round(255 * clamped);
		return 0xFF000000 | (red << 16) | (green << 8);
	}
	
	private static int colorForFraction(double fraction)
	{
		float f = (float)Mth.clamp(fraction, 0.0, 1.0);
		if(f >= 0.5F)
		{
			float t = (f - 0.5F) / 0.5F;
			int g = 0xFF00FF00;
			int y = 0xFFFFFF00;
			return blend(y, g, t);
		}
		
		float t = f / 0.5F;
		int r = 0xFFFF0000;
		int y = 0xFFFFFF00;
		return blend(r, y, t);
	}
	
	private static int blend(int from, int to, float t)
	{
		t = Mth.clamp(t, 0F, 1F);
		int fa = (from >> 24) & 0xFF;
		int fr = (from >> 16) & 0xFF;
		int fg = (from >> 8) & 0xFF;
		int fb = from & 0xFF;
		int ta = (to >> 24) & 0xFF;
		int tr = (to >> 16) & 0xFF;
		int tg = (to >> 8) & 0xFF;
		int tb = to & 0xFF;
		int a = (int)Math.round(fa + (ta - fa) * t);
		int r = (int)Math.round(fr + (tr - fr) * t);
		int g = (int)Math.round(fg + (tg - fg) * t);
		int b = (int)Math.round(fb + (tb - fb) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
