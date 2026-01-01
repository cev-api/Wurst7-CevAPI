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
import net.wurstclient.WurstClient;
import net.wurstclient.util.RenderUtils;

public class ItemHandlerHud
{
	private static final Minecraft MC = WurstClient.MC;
	// rendering per-frame guard removed: was causing flicker
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
		if(rawItems.isEmpty())
			return;
		
		class MergeEntry
		{
			ItemStack rep;
			int total;
			double closest;
			
			MergeEntry(ItemStack rep, int total, double closest)
			{
				this.rep = rep;
				this.total = total;
				this.closest = closest;
			}
		}
		
		Map<String, MergeEntry> map = new HashMap<>();
		for(ItemHandlerHack.GroundItem gi : rawItems)
		{
			ItemStack stack = gi.stack();
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(stack.getItem()).toString();
			String key = id;
			MergeEntry me = map.get(key);
			if(me == null)
			{
				map.put(key, new MergeEntry(stack.copy(), stack.getCount(),
					gi.distance()));
			}else
			{
				me.total += stack.getCount();
				if(gi.distance() < me.closest)
					me.closest = gi.distance();
			}
		}
		
		List<MergeEntry> items = new ArrayList<>(map.values());
		items.sort(Comparator.comparingDouble(m -> m.closest));
		
		int guiW = context.guiWidth();
		int x = guiW + hack.getHudOffsetX();
		int y = hack.getHudOffsetY();
		
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
			String name = resolveName(me.rep);
			int wName = (int)Math.round(font.width(name) * scale);
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
		int ex = x - boxWidth;
		int ey = y + headerHeight;
		int rowHeight = 16;
		int footer = items.size() > maxDisplay ? 1 : 0;
		int height = maxDisplay * rowHeight + footer * rowHeight + 6;
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
			String name = resolveName(me.rep);
			
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
			
			// optionally show registry id as subtitle
			if(hack != null && hack.isShowRegistryName())
			{
				String reg =
					net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(me.rep.getItem()).toString();
				RenderUtils.drawScaledText(context, tr, reg, ex + 22, iy + 9,
					0xFF909090, false, 0.6);
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
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		// hitbox covers the single list popup
		boolean overList = mouseX >= ex && mouseX <= ex + boxWidth
			&& mouseY >= ey && mouseY <= ey + height;
		Window window = MC.getWindow();
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		// Do not open the ItemHandler GUI from the popup via mouse clicks.
		// This avoids the popup behind other full-screen GUIs triggering
		// the ItemHandler screen when clicking in overlapping areas.
		// (Opening the ItemHandler GUI is available via the dedicated
		// settings button or a keybind/command.)
		lastLeftDown = leftDown;
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
	
	private static String resolveName(ItemStack stack)
	{
		try
		{
			String n = stack.getHoverName().getString();
			if(n == null || n.isBlank())
				return net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getKey(stack.getItem()).getPath();
			return n;
		}catch(Throwable t)
		{
			return net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(stack.getItem()).getPath();
		}
	}
}
