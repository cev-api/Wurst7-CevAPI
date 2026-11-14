/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.GlobalToggleHack;
import net.wurstclient.util.SdlUtils;

public final class TracerLegendHud
{
	private static final Minecraft MC = WurstClient.MC;
	private static final int BASE_X = 8;
	private static final int BASE_Y = 8;
	private static final int PADDING = 5;
	private static final int ROW_HEIGHT = 12;
	private static final int SWATCH_WIDTH = 10;
	private static final int SWATCH_GAP = 5;
	
	private final GlobalToggleHack hack;
	private boolean dragging;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private int dragStartOffsetX;
	private int dragStartOffsetY;
	private int dragOffsetX;
	private int dragOffsetY;
	private List<GlobalToggleHack.TracerLegendEntry> lastEntries = List.of();
	private long lastEntriesSeenAt;
	
	public TracerLegendHud(GlobalToggleHack hack)
	{
		this.hack = hack;
	}
	
	public void render(GuiGraphicsExtractor context)
	{
		if(hack == null)
			return;
		List<GlobalToggleHack.TracerLegendEntry> entries =
			hack.consumeTracerLegendEntries();
		if(!hack.isTracerLegendEnabled() || hack.shouldHideWurstHud()
			|| MC == null || MC.font == null)
		{
			lastEntries = List.of();
			if(dragging)
				commitDrag();
			dragging = false;
			return;
		}
		float opacity = 1F;
		if(!entries.isEmpty())
		{
			lastEntries = entries;
			lastEntriesSeenAt = System.nanoTime();
		}else if(hack.isTracerLegendFadeEnabled()
			&& !hack.isTracerLegendAlwaysShowAll() && !lastEntries.isEmpty())
		{
			long elapsed = System.nanoTime() - lastEntriesSeenAt;
			if(elapsed >= 1_000_000_000L)
			{
				lastEntries = List.of();
				return;
			}
			opacity = 1F - elapsed / 1_000_000_000F;
			entries = lastEntries;
		}else
		{
			lastEntries = List.of();
			return;
		}
		
		Font font = MC.font;
		int textWidth =
			hack.isTracerLegendTitleEnabled() ? font.width("Tracer legend") : 0;
		for(GlobalToggleHack.TracerLegendEntry entry : entries)
			textWidth = Math.max(textWidth, font.width(entry.label()));
		int width = PADDING * 2 + SWATCH_WIDTH + SWATCH_GAP + textWidth;
		int headerHeight =
			hack.isTracerLegendTitleEnabled() ? ROW_HEIGHT + 3 : 0;
		int height = PADDING * 2 + headerHeight + entries.size() * ROW_HEIGHT;
		int x = BASE_X + getCurrentOffsetX();
		int y = BASE_Y + getCurrentOffsetY();
		handleDrag(context, x, y, width, height);
		x = BASE_X + getCurrentOffsetX();
		y = BASE_Y + getCurrentOffsetY();
		
		context.fill(x, y, x + width, y + height,
			withOpacity(0xA0000000, opacity));
		if(hack.isTracerLegendTitleEnabled())
			context.text(font, Component.literal("Tracer legend"), x + PADDING,
				y + PADDING, withOpacity(0xFFFFFFFF, opacity), false);
		int rowY = y + PADDING + headerHeight;
		for(GlobalToggleHack.TracerLegendEntry entry : entries)
		{
			int color = withOpacity(entry.color() | 0xFF000000, opacity);
			int textY = rowY + (ROW_HEIGHT - font.lineHeight) / 2;
			int swatchY = rowY + (ROW_HEIGHT - 6) / 2;
			context.fill(x + PADDING, swatchY, x + PADDING + SWATCH_WIDTH,
				swatchY + 6, color);
			context.text(font, Component.literal(entry.label()),
				x + PADDING + SWATCH_WIDTH + SWATCH_GAP, textY, color, false);
			rowY += ROW_HEIGHT;
		}
	}
	
	private static int withOpacity(int color, float opacity)
	{
		int alpha = color >>> 24;
		return (Math.round(alpha * opacity) << 24) | (color & 0x00FFFFFF);
	}
	
	private void handleDrag(GuiGraphicsExtractor context, int x, int y,
		int width, int height)
	{
		boolean canEdit = MC.gui.screen() instanceof ChatScreen
			|| MC.gui.screen() instanceof AbstractContainerScreen<?>;
		if(!canEdit)
		{
			if(dragging)
				commitDrag();
			dragging = false;
			return;
		}
		Window window = MC.getWindow();
		if(window == null)
		{
			if(dragging)
				commitDrag();
			dragging = false;
			return;
		}
		
		double mouseX = MC.mouseHandler.xpos() * context.guiWidth()
			/ window.getScreenWidth();
		double mouseY = MC.mouseHandler.ypos() * context.guiHeight()
			/ window.getScreenHeight();
		boolean leftDown = SdlUtils.getMouseButtonState(
			InputConstants.MOUSE_BUTTON_LEFT) == InputConstants.PRESS;
		boolean overHud = mouseX >= x && mouseX <= x + width && mouseY >= y
			&& mouseY <= y + height;
		if(leftDown && overHud)
		{
			if(!dragging)
			{
				dragging = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				dragStartOffsetX = hack.getTracerLegendOffsetX();
				dragStartOffsetY = hack.getTracerLegendOffsetY();
				dragOffsetX = dragStartOffsetX;
				dragOffsetY = dragStartOffsetY;
			}
			dragOffsetX = clampX(
				dragStartOffsetX + (int)Math.round(mouseX - dragStartMouseX),
				width, context.guiWidth());
			dragOffsetY = clampY(
				dragStartOffsetY + (int)Math.round(mouseY - dragStartMouseY),
				height, context.guiHeight());
		}else if(!leftDown)
		{
			if(dragging)
				commitDrag();
			dragging = false;
		}
	}
	
	private int clampX(int offset, int width, int screenWidth)
	{
		return Math.max(-BASE_X,
			Math.min(screenWidth - width - BASE_X, offset));
	}
	
	private int clampY(int offset, int height, int screenHeight)
	{
		return Math.max(-BASE_Y,
			Math.min(screenHeight - height - BASE_Y, offset));
	}
	
	private int getCurrentOffsetX()
	{
		return dragging ? dragOffsetX : hack.getTracerLegendOffsetX();
	}
	
	private int getCurrentOffsetY()
	{
		return dragging ? dragOffsetY : hack.getTracerLegendOffsetY();
	}
	
	private void commitDrag()
	{
		hack.setTracerLegendOffsets(dragOffsetX, dragOffsetY);
	}
}
