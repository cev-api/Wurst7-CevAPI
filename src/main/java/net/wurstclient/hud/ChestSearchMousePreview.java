/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.chestsearch.ChestSearchItemStacks;
import net.wurstclient.hacks.ChestSearchHack;
import net.wurstclient.util.RenderUtils;
import org.lwjgl.glfw.GLFW;

public final class ChestSearchMousePreview
{
	private static final int COLUMNS = 9;
	private static final int SLOT_SIZE = 18;
	private static final int SLOT_INNER_SIZE = 16;
	private static final int SLOT_GAP = 2;
	private static final int PADDING = 4;
	private static final int MAX_SLOTS = 54;
	
	private final ChestManager chestManager = new ChestManager();
	private boolean visible;
	private boolean dragging;
	private int previewX;
	private int previewY;
	private int previewWidth;
	private int previewHeight;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private int dragStartOffsetX;
	private int dragStartOffsetY;
	private int dragOffsetX;
	private int dragOffsetY;
	
	public void render(GuiGraphicsExtractor context)
	{
		visible = false;
		ChestSearchHack hack = WurstClient.INSTANCE.getHax().chestSearchHack;
		if(hack == null || !hack.shouldDisplayOnMouse())
			return;
		if(WurstClient.MC.level == null || WurstClient.MC.player == null
			|| WurstClient.MC.screen != null
				&& !(WurstClient.MC.screen instanceof ChatScreen))
			return;
		if(!(WurstClient.MC.hitResult instanceof BlockHitResult hit))
			return;
		
		ChestEntry entry = findEntry(hit.getBlockPos());
		if(entry == null || entry.items == null || entry.items.isEmpty())
			return;
		
		int slots = getSlotCount(entry);
		int rows = Math.max(1, (slots + COLUMNS - 1) / COLUMNS);
		int width = getPreviewSize(COLUMNS);
		int height = getPreviewSize(rows);
		int anchorX = getAnchorX(context);
		int anchorY = getAnchorY(context);
		int x;
		int y;
		if(hack.isFixedPreviewPosition())
		{
			x = getCurrentFixedX(hack, anchorX, width);
			y = getCurrentFixedY(hack, anchorY);
			handleDrag(context, hack, x, y, width, height);
			x = dragging ? dragOffsetX : x;
			y = dragging ? dragOffsetY : y;
		}else
		{
			dragging = false;
			x = anchorX - width / 2;
			y = anchorY + 18;
		}
		x = Math.max(2, Math.min(context.guiWidth() - width - 2, x));
		y = Math.max(2, Math.min(context.guiHeight() - height - 2, y));
		previewX = x;
		previewY = y;
		previewWidth = width;
		previewHeight = height;
		visible = true;
		
		context.guiRenderState.up();
		context.fill(x, y, x + width, y + height,
			hack.getMousePreviewBackgroundARGB());
		RenderUtils.drawBorder2D(context, x, y, x + width, y + height,
			0xFF808080);
		
		for(int slot = 0; slot < slots; slot++)
		{
			int sx = getSlotX(x, slot);
			int sy = getSlotY(y, slot);
			context.fill(sx, sy, sx + SLOT_INNER_SIZE, sy + SLOT_INNER_SIZE,
				0x80303030);
			RenderUtils.drawBorder2D(context, sx, sy, sx + SLOT_INNER_SIZE,
				sy + SLOT_INNER_SIZE, 0xFF555555);
		}
		
		for(ChestEntry.ItemEntry item : entry.items)
		{
			if(item == null || item.slot < 0 || item.slot >= slots)
				continue;
			ItemStack stack = ChestSearchItemStacks.decode(item);
			if(stack.isEmpty())
				continue;
			
			int sx = getSlotX(x, item.slot);
			int sy = getSlotY(y, item.slot);
			context.item(stack, sx, sy);
			drawCount(context, item.count, sx, sy);
		}
	}
	
	private ChestEntry findEntry(BlockPos pos)
	{
		String dimension =
			WurstClient.MC.level.dimension().identifier().toString();
		List<ChestEntry> entries = chestManager.all();
		for(ChestEntry entry : entries)
		{
			if(entry == null || entry.dimension == null
				|| !entry.dimension.equals(dimension))
				continue;
			BlockPos min = entry.getMinPos();
			BlockPos max = entry.getMaxPos();
			if(pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ())
				return entry;
		}
		return null;
	}
	
	private int getSlotCount(ChestEntry entry)
	{
		int maxSlot = entry.items.stream().filter(i -> i != null)
			.max(Comparator.comparingInt(i -> i.slot)).map(i -> i.slot)
			.orElse(26);
		int slots = Math.max(27, maxSlot + 1);
		slots = ((slots + COLUMNS - 1) / COLUMNS) * COLUMNS;
		return Math.min(MAX_SLOTS, slots);
	}
	
	private int getPreviewSize(int slotsOrRows)
	{
		return PADDING * 2 + slotsOrRows * SLOT_INNER_SIZE
			+ Math.max(0, slotsOrRows - 1) * SLOT_GAP;
	}
	
	private int getSlotX(int previewX, int slot)
	{
		return previewX + PADDING + slot % COLUMNS * SLOT_SIZE;
	}
	
	private int getSlotY(int previewY, int slot)
	{
		return previewY + PADDING + slot / COLUMNS * SLOT_SIZE;
	}
	
	private void drawCount(GuiGraphicsExtractor context, int count, int x,
		int y)
	{
		if(count <= 1)
			return;
		Font font = WurstClient.MC.font;
		String text = Integer.toString(count);
		int tx = x + 17 - font.width(text);
		int ty = y + 9;
		context.guiRenderState.up();
		context.text(font, text, tx, ty, 0xFFFFFFFF, true);
	}
	
	private int getAnchorX(GuiGraphicsExtractor context)
	{
		if(WurstClient.MC.screen == null)
			return context.guiWidth() / 2;
		return (int)(WurstClient.MC.mouseHandler.xpos() * context.guiWidth()
			/ WurstClient.MC.getWindow().getScreenWidth());
	}
	
	private int getAnchorY(GuiGraphicsExtractor context)
	{
		if(WurstClient.MC.screen == null)
			return context.guiHeight() / 2;
		return (int)(WurstClient.MC.mouseHandler.ypos() * context.guiHeight()
			/ WurstClient.MC.getWindow().getScreenHeight());
	}
	
	private int getCurrentFixedX(ChestSearchHack hack, int anchorX, int width)
	{
		int x = hack.getPreviewX();
		if(x == 0 && hack.getPreviewY() == 0)
			x = anchorX - width / 2;
		return x;
	}
	
	private int getCurrentFixedY(ChestSearchHack hack, int anchorY)
	{
		int y = hack.getPreviewY();
		if(hack.getPreviewX() == 0 && y == 0)
			y = anchorY + 18;
		return y;
	}
	
	private void handleDrag(GuiGraphicsExtractor context, ChestSearchHack hack,
		int x, int y, int width, int height)
	{
		boolean canDrag = WurstClient.MC.screen instanceof ChatScreen;
		if(!canDrag)
		{
			if(dragging)
				commitDraggedOffset(hack);
			dragging = false;
			return;
		}
		
		com.mojang.blaze3d.platform.Window window = WurstClient.MC.getWindow();
		if(window == null)
		{
			if(dragging)
				commitDraggedOffset(hack);
			dragging = false;
			return;
		}
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean overHud = mouseX >= x && mouseX <= x + width && mouseY >= y
			&& mouseY <= y + height;
		
		if(leftDown)
		{
			if(!dragging && overHud)
			{
				dragging = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				dragStartOffsetX = x;
				dragStartOffsetY = y;
				dragOffsetX = x;
				dragOffsetY = y;
			}
			
			if(dragging)
			{
				dragOffsetX = clampPreviewX(context, dragStartOffsetX
					+ (int)Math.round(mouseX - dragStartMouseX), width);
				dragOffsetY =
					clampPreviewY(context,
						dragStartOffsetY
							+ (int)Math.round(mouseY - dragStartMouseY),
						height);
			}
			return;
		}
		
		if(!leftDown && dragging)
		{
			commitDraggedOffset(hack);
			dragging = false;
		}
	}
	
	private void commitDraggedOffset(ChestSearchHack hack)
	{
		hack.setPreviewPosition(dragOffsetX, dragOffsetY);
	}
	
	private int clampPreviewX(GuiGraphicsExtractor context, int x, int width)
	{
		return Math.max(2, Math.min(context.guiWidth() - width - 2, x));
	}
	
	private int clampPreviewY(GuiGraphicsExtractor context, int y, int height)
	{
		return Math.max(2, Math.min(context.guiHeight() - height - 2, y));
	}
	
	private static double getScaledMouseX(GuiGraphicsExtractor context)
	{
		com.mojang.blaze3d.platform.Window window = WurstClient.MC.getWindow();
		if(window == null)
			return 0;
		return WurstClient.MC.mouseHandler.xpos() * context.guiWidth()
			/ window.getScreenWidth();
	}
	
	private static double getScaledMouseY(GuiGraphicsExtractor context)
	{
		com.mojang.blaze3d.platform.Window window = WurstClient.MC.getWindow();
		if(window == null)
			return 0;
		return WurstClient.MC.mouseHandler.ypos() * context.guiHeight()
			/ window.getScreenHeight();
	}
}
