/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ElytraInfoHack;
import net.wurstclient.util.RenderUtils;

public final class ElytraInfoHud
{
	private static final Minecraft MC = WurstClient.MC;
	private static final float BASE_Y_OFFSET = 16F;
	private static final int PADDING_X = 4;
	private static final int PADDING_Y = 3;
	
	private final ElytraInfoHack hack;
	
	private boolean dragging;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private int dragStartOffsetX;
	private int dragStartOffsetY;
	private int dragOffsetX;
	private int dragOffsetY;
	
	public ElytraInfoHud(ElytraInfoHack hack)
	{
		this.hack = hack;
	}
	
	public void render(GuiGraphics context)
	{
		if(MC == null || MC.player == null || hack == null || !hack.isEnabled())
			return;
		
		ItemStack chest = MC.player.getItemBySlot(EquipmentSlot.CHEST);
		if(chest.isEmpty() || chest.getItem() != Items.ELYTRA)
			return;
		
		Font font = MC.font;
		if(font == null)
			return;
		
		List<Segment> segments = buildSegments(chest);
		if(segments.isEmpty())
			return;
		double fontScale = hack.getFontScale();
		
		int textWidth = 0;
		for(Segment segment : segments)
			textWidth +=
				(int)Math.round(font.width(segment.text()) * fontScale);
		
		int textHeight = (int)Math.round(font.lineHeight * fontScale);
		int width = textWidth + PADDING_X * 2;
		int height = textHeight + PADDING_Y * 2;
		
		float centerX = context.guiWidth() / 2F;
		float centerY = context.guiHeight() / 2F;
		
		float x = centerX - width / 2F + getCurrentOffsetX();
		float y = centerY + BASE_Y_OFFSET + getCurrentOffsetY();
		
		handleDrag(context, x, y, width, height);
		
		x = centerX - width / 2F + getCurrentOffsetX();
		y = centerY + BASE_Y_OFFSET + getCurrentOffsetY();
		
		if(hack.hasBackground())
		{
			int bgColor = withAlpha(hack.getBackgroundColorI(),
				hack.getBackgroundOpacity());
			RenderUtils.fill2D(context, x, y, x + width, y + height, bgColor);
		}
		
		int drawX = Math.round(x) + PADDING_X;
		int drawY = Math.round(y) + PADDING_Y;
		for(Segment segment : segments)
		{
			RenderUtils.drawScaledText(context, font, segment.text(), drawX,
				drawY, withAlpha(segment.color(), hack.getTextOpacity()), false,
				fontScale);
			drawX += (int)Math.round(font.width(segment.text()) * fontScale);
		}
	}
	
	private List<Segment> buildSegments(ItemStack chest)
	{
		ArrayList<Segment> segments = new ArrayList<>(6);
		int textColor = hack.getTextColorI();
		boolean hidePrefixes = hack.hidePrefixes();
		
		if(hack.showYaw())
			segments.add(new Segment(formatValue("Yaw",
				String.format(Locale.ROOT, "%.1f°", normalizeYaw()),
				hidePrefixes), textColor));
		
		if(hack.showPitch())
			segments
				.add(
					new Segment(
						formatValue("Pitch",
							String.format(Locale.ROOT, "%.1f°",
								MC.player.getXRot()),
							hidePrefixes),
						textColor));
		
		if(hack.showAltitude())
			segments.add(new Segment(formatValue("Alt",
				String.format(Locale.ROOT, "%.1f", MC.player.getY()),
				hidePrefixes), textColor));
		
		if(hack.showSpeed())
		{
			long speed =
				Math.round(MC.player.getDeltaMovement().length() * 20.0);
			segments.add(new Segment(
				formatValue("Speed", speed + "b/s", hidePrefixes), textColor));
		}
		
		if(hack.showDirection())
			segments.add(new Segment(
				formatValue("Dir", directionFromYaw(), hidePrefixes),
				textColor));
		
		if(hack.showDurability())
		{
			int max = chest.getMaxDamage();
			int remaining = max - chest.getDamageValue();
			int percent =
				max > 0 ? (int)Math.round((remaining * 100.0) / max) : 0;
			int durabilityColor = textColor;
			if(hack.useDurabilityGradient())
			{
				double fraction = max > 0 ? remaining / (double)max : 0;
				durabilityColor = colorForFraction(fraction);
			}
			
			segments.add(new Segment(
				formatValue("Elytra",
					String.format(Locale.ROOT, "%d%%", percent), hidePrefixes),
				durabilityColor));
		}
		
		for(int i = segments.size() - 1; i >= 1; i--)
			segments.add(i, new Segment(" | ", textColor));
		
		return segments;
	}
	
	private void handleDrag(GuiGraphics context, float x, float y, int width,
		int height)
	{
		boolean canDrag = MC.screen instanceof ChatScreen
			|| MC.screen instanceof AbstractContainerScreen<?>;
		if(!canDrag)
		{
			if(dragging)
				commitDraggedOffset();
			dragging = false;
			return;
		}
		
		Window window = MC.getWindow();
		if(window == null)
		{
			if(dragging)
				commitDraggedOffset();
			dragging = false;
			return;
		}
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		
		boolean overHud = mouseX >= x && mouseX <= x + width && mouseY >= y
			&& mouseY <= y + height;
		
		if(leftDown && overHud)
		{
			if(!dragging)
			{
				dragging = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				dragStartOffsetX = hack.getHudOffsetX();
				dragStartOffsetY = hack.getHudOffsetY();
				dragOffsetX = dragStartOffsetX;
				dragOffsetY = dragStartOffsetY;
			}
			
			dragOffsetX = clampHudOffsetX(
				dragStartOffsetX + (int)Math.round(mouseX - dragStartMouseX));
			dragOffsetY = clampHudOffsetY(
				dragStartOffsetY + (int)Math.round(mouseY - dragStartMouseY));
			return;
		}
		
		if(!leftDown)
		{
			if(dragging)
				commitDraggedOffset();
			dragging = false;
		}
	}
	
	private int getCurrentOffsetX()
	{
		return dragging ? dragOffsetX : hack.getHudOffsetX();
	}
	
	private int getCurrentOffsetY()
	{
		return dragging ? dragOffsetY : hack.getHudOffsetY();
	}
	
	private int clampHudOffsetX(int x)
	{
		return Math.max(hack.getHudOffsetMinX(),
			Math.min(hack.getHudOffsetMaxX(), x));
	}
	
	private int clampHudOffsetY(int y)
	{
		return Math.max(hack.getHudOffsetMinY(),
			Math.min(hack.getHudOffsetMaxY(), y));
	}
	
	private void commitDraggedOffset()
	{
		hack.setHudOffsets(dragOffsetX, dragOffsetY);
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
	
	private float normalizeYaw()
	{
		float yaw = MC.player.getYRot() % 360F;
		if(yaw < 0F)
			yaw += 360F;
		return yaw;
	}
	
	private String directionFromYaw()
	{
		float yaw = normalizeYaw();
		String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		int index = Mth.floor((yaw + 22.5F) / 45F) & 7;
		return dirs[index];
	}
	
	private static String formatValue(String prefix, String value,
		boolean hidePrefixes)
	{
		return hidePrefixes ? value : prefix + ": " + value;
	}
	
	private static int withAlpha(int rgb, int alpha)
	{
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
	}
	
	private static int colorForFraction(double fraction)
	{
		float f = (float)Mth.clamp(fraction, 0.0, 1.0);
		if(f >= 0.5F)
		{
			float t = (f - 0.5F) / 0.5F;
			return blend(0xFFFFFF00, 0xFF00FF00, t);
		}
		
		float t = f / 0.5F;
		return blend(0xFFFF0000, 0xFFFFFF00, t);
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
	
	private record Segment(String text, int color)
	{}
}
