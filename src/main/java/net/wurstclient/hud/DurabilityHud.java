/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.DurabilityHudHack;
import net.wurstclient.util.RenderUtils;

public final class DurabilityHud
{
	private static final Minecraft MC = WurstClient.MC;
	private static final EquipmentSlot[] ARMOR_SLOTS =
		new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
			EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final float ICON_SPACING = 6F;
	private static final float BAR_HEIGHT = 4F;
	private static final float BOSS_BAR_HEIGHT = 6F;
	private static final float TEXT_GAP = 2F;
	private static final float BASE_MARGIN = 6F;
	private static final float DRAG_PADDING = 4F;
	private static final float ACTIONBAR_AVOID_ZONE_HEIGHT = 120F;
	private static final float ACTIONBAR_AVOID_EXTRA_MARGIN = 10F;
	
	private static boolean overlayFieldsChecked;
	private static Field overlayMessageStringField;
	private static Field overlayMessageTimeField;
	
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
		boolean hasArmor = !armorStacks.isEmpty();
		float iconSize = (float)hack.getIconSize();
		float fontScale = (float)hack.getFontScale();
		DurabilityHudHack.DisplayMode displayMode = hack.getDisplayMode();
		boolean showBar =
			displayMode != DurabilityHudHack.DisplayMode.PERCENT_ONLY;
		boolean showPercent =
			displayMode != DurabilityHudHack.DisplayMode.BAR_ONLY;
		boolean bossBar = hack.isBossBarStyle();
		float barHeight =
			showBar ? (bossBar ? BOSS_BAR_HEIGHT : BAR_HEIGHT) : 0F;
		float textHeight = showPercent ? font.lineHeight * fontScale : 0F;
		
		float rowHeight = iconSize;
		if(showBar)
			rowHeight += TEXT_GAP + barHeight;
		if(showPercent)
			rowHeight += TEXT_GAP + textHeight;
		
		float anchorY = context.guiHeight() - (hasArmor ? 49 : 39);
		float baseIconY = anchorY - rowHeight - BASE_MARGIN;
		baseIconY = Math.max(baseIconY, BASE_MARGIN);
		float totalWidth = iconCount * iconSize;
		if(iconCount > 1)
			totalWidth += ICON_SPACING * (iconCount - 1);
		float baseStartX = context.guiWidth() / 2F - totalWidth / 2F;
		float startX = baseStartX + offsetX;
		float iconY = baseIconY + offsetY;
		
		handleDrag(context, startX, iconY, totalWidth, rowHeight);
		startX = baseStartX + offsetX;
		iconY = baseIconY + offsetY;
		
		if(hack.avoidActionbarText())
		{
			float avoid = getActionbarAvoidOffset(context, iconY);
			iconY = Math.max(iconY - avoid, BASE_MARGIN);
		}
		
		float barY = showBar ? iconY + iconSize + TEXT_GAP : 0F;
		float textY = 0F;
		if(showPercent)
		{
			if(showBar)
				textY = barY + barHeight + TEXT_GAP;
			else
				textY = iconY + iconSize + TEXT_GAP;
		}
		
		List<ItemStack> combined = new ArrayList<>(iconCount);
		combined.addAll(armorStacks);
		combined.addAll(handStacks);
		
		float x = startX;
		boolean gradientColor = hack.useGradientFontColor();
		int fontColor = hack.getFontColorI();
		for(int i = 0; i < combined.size(); i++)
		{
			ItemStack stack = combined.get(i);
			renderSlot(context, font, stack, x, iconY, iconSize, showBar,
				showPercent, barY, barHeight, textY, fontScale, bossBar,
				fontColor, gradientColor);
			if(i < combined.size() - 1)
				x += iconSize + ICON_SPACING;
		}
	}
	
	private void renderSlot(GuiGraphics context, Font font, ItemStack stack,
		float x, float iconY, float iconSize, boolean showBar,
		boolean showPercent, float barY, float barHeight, float textY,
		float fontScale, boolean bossBar, int baseTextColor,
		boolean useGradientText)
	{
		double fraction = getDurabilityFraction(stack);
		float fillWidth = (float)(fraction * iconSize);
		float clampedWidth = Mth.clamp(fillWidth, 0F, iconSize);
		int fillColor = getDurabilityColor(fraction);
		
		drawItem(context, stack, x, iconY, iconSize);
		
		if(showBar && barHeight > 0F)
		{
			int background = bossBar ? 0xFF101820 : 0xFF202020;
			RenderUtils.fill2D(context, x, barY, x + iconSize, barY + barHeight,
				background);
			if(clampedWidth > 0F)
				RenderUtils.fill2D(context, x, barY, x + clampedWidth,
					barY + barHeight, fillColor);
			if(bossBar)
			{
				RenderUtils.drawBorder2D(context, x, barY, x + iconSize,
					barY + barHeight, 0xFF909090);
				float highlightHeight = Math.min(2F, barHeight);
				RenderUtils.fill2D(context, x, barY, x + iconSize,
					barY + highlightHeight, 0x30FFFFFF);
			}
		}
		
		if(showPercent)
		{
			String label = Math.round(fraction * 100) + "%";
			double textWidth = font.width(label) * fontScale;
			float textX = x + (iconSize - (float)textWidth) / 2F;
			int actualColor = baseTextColor;
			if(useGradientText)
				actualColor = colorForFraction(fraction);
			RenderUtils.drawScaledText(context, font, label, Math.round(textX),
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
	
	private static float getActionbarAvoidOffset(GuiGraphics context,
		float hudY)
	{
		// Only shift if the HUD is actually near the bottom where the actionbar
		// message renders. This avoids surprising movement when the HUD is
		// dragged
		// elsewhere.
		if(hudY < context.guiHeight() - ACTIONBAR_AVOID_ZONE_HEIGHT)
			return 0F;
		
		if(!isOverlayMessageVisible())
			return 0F;
		
		if(MC == null || MC.font == null)
			return 0F;
		
		return MC.font.lineHeight + ACTIONBAR_AVOID_EXTRA_MARGIN;
	}
	
	private static boolean isOverlayMessageVisible()
	{
		if(MC == null)
			return false;
		
		Gui gui = MC.gui;
		if(gui == null)
			return false;
		
		initOverlayReflection();
		if(overlayMessageTimeField == null || overlayMessageStringField == null)
			return false;
		
		try
		{
			int time = overlayMessageTimeField.getInt(gui);
			if(time <= 0)
				return false;
			
			Object c = overlayMessageStringField.get(gui);
			if(!(c instanceof Component comp))
				return false;
			
			return !comp.getString().isEmpty();
			
		}catch(IllegalAccessException e)
		{
			return false;
		}
	}
	
	private static void initOverlayReflection()
	{
		if(overlayFieldsChecked)
			return;
		
		overlayFieldsChecked = true;
		
		// Mojang-mapped field names in modern versions. If they ever change,
		// the
		// feature will gracefully disable itself.
		overlayMessageStringField =
			getFieldOrNull(Gui.class, "overlayMessageString");
		overlayMessageTimeField =
			getFieldOrNull(Gui.class, "overlayMessageTime");
	}
	
	private static Field getFieldOrNull(Class<?> owner, String name)
	{
		try
		{
			Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			return f;
			
		}catch(ReflectiveOperationException e)
		{
			return null;
		}
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
