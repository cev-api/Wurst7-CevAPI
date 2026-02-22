/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.itemhandler;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;

import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.wurstclient.WurstClient;
import net.wurstclient.util.RenderUtils;

public class ItemHandlerHud
{
	private static final Minecraft MC = WurstClient.MC;
	private boolean dragging;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private int dragStartOffsetX;
	private int dragStartOffsetY;
	private int dragOffsetX;
	private int dragOffsetY;
	private boolean lastLeftDown = false;
	
	public void render(GuiGraphics context, float partialTicks)
	{
		// no per-tick short-circuit; draw every GUI render call
		
		ItemHandlerHack hack = WurstClient.INSTANCE.getHax().itemHandlerHack;
		if(hack == null || !hack.isHudEnabled())
			return;
		
		List<ItemHandlerHack.GroundItem> rawItems =
			hack.getTrackedItems().stream()
				.filter(g -> g.distance() <= hack.getPopupRange()).toList();
		
		List<ItemHandlerHack.NearbyLabel> rawLabels = hack.getTrackedLabels();
		
		if(rawItems.isEmpty() && rawLabels.isEmpty())
			return;
		
		class MergeEntry
		{
			ItemStack rep;
			String displayName;
			int total;
			double closest;
			boolean isLabel;
			boolean isSpecial;
			
			MergeEntry(ItemStack rep, String displayName, int total,
				double closest, boolean isLabel, boolean isSpecial)
			{
				this.rep = rep;
				this.displayName = displayName;
				this.total = total;
				this.closest = closest;
				this.isLabel = isLabel;
				this.isSpecial = isSpecial;
			}
		}
		
		Map<String, MergeEntry> map = new HashMap<>();
		for(ItemHandlerHack.GroundItem gi : rawItems)
		{
			ItemStack stack = gi.stack();
			String key = buildHudKey(gi);
			MergeEntry me = map.get(key);
			if(me == null)
			{
				map.put(key,
					new MergeEntry(stack.copy(), gi.displayName(),
						stack.getCount(), gi.distance(), false,
						hack.isSpecialByItemEsp(stack)));
			}else
			{
				me.total += stack.getCount();
				if(gi.distance() < me.closest)
					me.closest = gi.distance();
				me.isSpecial = me.isSpecial || hack.isSpecialByItemEsp(stack);
			}
		}
		
		List<MergeEntry> items = new ArrayList<>(map.values());
		
		for(ItemHandlerHack.NearbyLabel label : rawLabels)
		{
			if(label == null || label.icon() == null || label.text() == null)
				continue;
			items.add(new MergeEntry(label.icon().copy(), label.text(), 1,
				label.distance(), true, false));
		}
		
		if(hack.isPinSpecialItemsTop())
		{
			items.sort(
				Comparator.comparing((MergeEntry m) -> m.isSpecial ? 0 : 1)
					.thenComparingDouble(m -> m.closest));
		}else
		{
			items.sort(Comparator.comparingDouble(m -> m.closest));
		}
		
		int guiW = context.guiWidth();
		
		// Render only one popup (expanded list). The previous header
		// duplicate is removed to avoid showing two linked popups.
		int headerHeight = 18;
		
		// compute dynamic box width based on text widths
		double uiScale =
			WurstClient.INSTANCE.getHax().itemHandlerHack.getPopupScale();
		double scale = uiScale; // text scale
		Font font = MC.font;
		boolean largeIcon = uiScale >= 1.25;
		int iconSpace = largeIcon ? 28 : 20; // space for icon
		int padding = 8;
		int configuredMax =
			WurstClient.INSTANCE.getHax().itemHandlerHack.getPopupMaxItems();
		int maxDisplay = Math.min(items.size(), configuredMax);
		int maxNameW = 0;
		int maxDistW = 0;
		for(int ii = 0; ii < maxDisplay; ii++)
		{
			MergeEntry me = items.get(ii);
			String name = me.displayName;
			int wName = (int)Math.round(font.width(name) * scale);
			String subtitle = "";
			if(hack != null)
			{
				if(hack.isShowEnchantmentsInNames())
					subtitle = hack.getEnchantmentSummary(me.rep);
				if(subtitle.isBlank() && hack.isShowRegistryName())
					subtitle =
						net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(me.rep.getItem()).toString();
			}
			if(!subtitle.isBlank())
				wName = Math.max(wName,
					(int)Math.round(font.width(subtitle) * (scale * 0.6)));
			String dist = ((int)Math.round(me.closest)) + " blocks";
			int wDist = (int)Math.round(font.width(dist) * scale);
			maxNameW = Math.max(maxNameW, wName);
			maxDistW = Math.max(maxDistW, wDist);
		}
		int gap = 10;
		int boxWidth =
			iconSpace + padding + maxNameW + gap + maxDistW + padding;
		if(boxWidth < 120)
			boxWidth = 120;
		
		int hudOffsetX = dragging ? dragOffsetX : hack.getHudOffsetX();
		int hudOffsetY = dragging ? dragOffsetY : hack.getHudOffsetY();
		int x = guiW + hudOffsetX;
		int y = hudOffsetY;
		int ex = x - boxWidth;
		int ey = y + headerHeight;
		int rowHeight = 16;
		int footer = items.size() > maxDisplay ? 1 : 0;
		int height = maxDisplay * rowHeight + footer * rowHeight + 6;
		
		handleDrag(context, hack, ex, ey, boxWidth, height);
		
		hudOffsetX = dragging ? dragOffsetX : hack.getHudOffsetX();
		hudOffsetY = dragging ? dragOffsetY : hack.getHudOffsetY();
		x = guiW + hudOffsetX;
		y = hudOffsetY;
		ex = x - boxWidth;
		ey = y + headerHeight;
		
		context.fill(ex, ey, ex + boxWidth, ey + height, 0xCC101010);
		context.guiRenderState.up();
		
		Font tr = MC.font;
		int i = 0;
		int displayCount = Math.min(items.size(), configuredMax);
		for(MergeEntry me : items)
		{
			if(i >= displayCount)
				break;
			int iy = ey + 4 + i * rowHeight;
			RenderUtils.drawItem(context, me.rep, ex + 4, iy, largeIcon);
			context.guiRenderState.up();
			String name = me.displayName;
			
			// draw main name
			int nameX = ex + iconSpace + 2;
			RenderUtils.drawScaledText(context, tr, name, nameX, iy + 2,
				0xFFFFFFFF, false, scale);
			
			// integer distance right-aligned
			String dist = ((int)Math.round(me.closest)) + " blocks";
			double distScale = scale * 0.9;
			int distW = (int)Math.round(tr.width(dist) * distScale);
			int edgePad = 4; // closer to right edge
			int distX = ex + boxWidth - edgePad - distW;
			RenderUtils.drawScaledText(context, tr, dist, distX, iy + 2,
				0xFFBBBBBB, false, distScale);
			
			if(!me.isLabel)
			{
				// draw count overlay in icon bottom-right (16x16 icon)
				String cnt = String.valueOf(me.total);
				double countScale = 0.75 * uiScale;
				int cW = (int)Math.round(tr.width(cnt) * countScale);
				int iconBX = ex + 4; // icon draw origin
				int iconBY = iy; // icon draw origin
				int iconSize = largeIcon ? 24 : 16;
				int countX = iconBX + iconSize - 2 - cW;
				int countY = iconBY + iconSize - (largeIcon ? 10 : 8);
				RenderUtils.drawScaledText(context, tr, cnt, countX, countY,
					0xFFFFFFFF, false, countScale);
			}
			
			// Optional subtitle line: enchantments or registry id.
			if(hack != null)
			{
				String subtitle = "";
				if(hack.isShowEnchantmentsInNames())
					subtitle = hack.getEnchantmentSummary(me.rep);
				if(subtitle.isBlank() && hack.isShowRegistryName())
					subtitle =
						net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(me.rep.getItem()).toString();
				if(!subtitle.isBlank())
					RenderUtils.drawScaledText(context, tr, subtitle, ex + 22,
						iy + 9, 0xFF909090, false, 0.6);
			}
			i++;
		}
		
		if(items.size() > maxDisplay)
		{
			context.guiRenderState.up();
			String more = (items.size() - maxDisplay) + " more";
			// left aligned with a bit more padding
			RenderUtils.drawScaledText(context, tr, more, ex + 8,
				ey + 8 + maxDisplay * rowHeight, 0xFFB0B0B0, false,
				scale * 0.95);
		}
		
		// Do not open the ItemHandler GUI from the popup via mouse clicks.
		// Opening the ItemHandler GUI remains available via keybind/command
		// and the settings button.
	}
	
	private void handleDrag(GuiGraphics context, ItemHandlerHack hack, int ex,
		int ey, int boxWidth, int height)
	{
		Window window = MC.getWindow();
		if(window == null)
		{
			dragging = false;
			lastLeftDown = false;
			return;
		}
		
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean containerOpen = MC.screen instanceof AbstractContainerScreen<?>;
		if(!containerOpen)
		{
			if(dragging)
			{
				hack.setHudOffsets(dragOffsetX, dragOffsetY);
				dragging = false;
			}
			lastLeftDown = leftDown;
			return;
		}
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		boolean overList = mouseX >= ex && mouseX <= ex + boxWidth
			&& mouseY >= ey && mouseY <= ey + height;
		
		if(!dragging && leftDown && !lastLeftDown && overList)
		{
			dragging = true;
			dragStartMouseX = mouseX;
			dragStartMouseY = mouseY;
			dragStartOffsetX = hack.getHudOffsetX();
			dragStartOffsetY = hack.getHudOffsetY();
			dragOffsetX = dragStartOffsetX;
			dragOffsetY = dragStartOffsetY;
		}
		
		if(dragging)
		{
			if(leftDown)
			{
				int dx = (int)Math.round(mouseX - dragStartMouseX);
				int dy = (int)Math.round(mouseY - dragStartMouseY);
				int minX = hack.getHudOffsetMinX();
				int maxX = hack.getHudOffsetMaxX();
				int minY = hack.getHudOffsetMinY();
				int maxY = hack.getHudOffsetMaxY();
				dragOffsetX = clamp(dragStartOffsetX + dx, minX, maxX);
				dragOffsetY = clamp(dragStartOffsetY + dy, minY, maxY);
			}else
			{
				hack.setHudOffsets(dragOffsetX, dragOffsetY);
				dragging = false;
			}
		}
		
		lastLeftDown = leftDown;
	}
	
	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
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
	
	private static String buildHudKey(ItemHandlerHack.GroundItem gi)
	{
		String id = gi.baseId();
		String label = gi.sourceLabel();
		if(label == null || label.isBlank())
			return id;
		return id + "|" + label;
	}
}
