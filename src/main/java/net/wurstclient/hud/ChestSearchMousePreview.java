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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.chestsearch.ChestSearchItemStacks;
import net.wurstclient.hacks.ChestSearchHack;
import net.wurstclient.hacks.ChestSearchHack.PreviewAnchor;
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
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int SCROLLBAR_GAP = 3;
	
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
	private boolean lastLeftDown;
	private int totalRows;
	private int visibleRows;
	private int scrollRowOffset;
	
	public boolean handleMouseClick(double mouseX, double mouseY, int button)
	{
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !visible)
			return false;
		
		ChestSearchHack hack = WurstClient.INSTANCE.getHax().chestSearchHack;
		if(hack == null || !hack.shouldDisplayOnMouse()
			|| !hack.isFixedPreviewPosition() || hack.usesPinnedPreviewAnchor())
			return false;
		
		if(mouseX < previewX || mouseX > previewX + previewWidth
			|| mouseY < previewY || mouseY > previewY + previewHeight)
			return false;
		
		dragging = true;
		lastLeftDown = true;
		dragStartMouseX = mouseX;
		dragStartMouseY = mouseY;
		dragStartOffsetX = previewX;
		dragStartOffsetY = previewY;
		dragOffsetX = previewX;
		dragOffsetY = previewY;
		return true;
	}
	
	public boolean handleMouseRelease(int button)
	{
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !dragging)
			return false;
		
		ChestSearchHack hack = WurstClient.INSTANCE.getHax().chestSearchHack;
		if(hack != null)
			commitDraggedOffset(hack);
		dragging = false;
		lastLeftDown = false;
		return true;
	}
	
	public boolean handleMouseScroll(double mouseX, double mouseY,
		double amount)
	{
		if(!visible || totalRows <= visibleRows)
			return false;
		if(mouseX < previewX || mouseX > previewX + previewWidth
			|| mouseY < previewY || mouseY > previewY + previewHeight)
			return false;
		
		int maxOffset = Math.max(0, totalRows - visibleRows);
		scrollRowOffset =
			Mth.clamp(scrollRowOffset - (int)Math.signum(amount), 0, maxOffset);
		return true;
	}
	
	public void render(GuiGraphics context)
	{
		visible = false;
		ChestSearchHack hack = WurstClient.INSTANCE.getHax().chestSearchHack;
		if(hack == null || !hack.shouldDisplayOnMouse())
			return;
		Screen screen = WurstClient.MC.screen;
		if(WurstClient.MC.level == null || WurstClient.MC.player == null
			|| !canRenderOnScreen(screen))
			return;
		if(!(WurstClient.MC.hitResult instanceof BlockHitResult hit))
			return;
		
		ChestEntry entry = findEntry(hit.getBlockPos());
		
		if(entry == null || entry.items == null || entry.items.isEmpty())
			return;
		
		int slots = getSlotCount(entry);
		int rows = Math.max(1, (slots + COLUMNS - 1) / COLUMNS);
		totalRows = rows;
		int maxVisibleRows = getMaxVisibleRows(context);
		visibleRows = Math.min(rows, maxVisibleRows);
		scrollRowOffset =
			Mth.clamp(scrollRowOffset, 0, Math.max(0, rows - visibleRows));
		boolean scrollable = rows > visibleRows;
		int width = getPreviewSize(COLUMNS)
			+ (scrollable ? SCROLLBAR_WIDTH + SCROLLBAR_GAP : 0);
		int height = getPreviewSize(visibleRows);
		int anchorX = getAnchorX(context);
		int anchorY = getAnchorY(context);
		int x;
		int y;
		if(hack.isFixedPreviewPosition())
		{
			x = getCurrentFixedX(hack, context, anchorX, width);
			y = getCurrentFixedY(hack, context, anchorY, height);
			if(!hack.usesPinnedPreviewAnchor())
				handleDrag(context, hack, x, y, width, height);
			else
			{
				dragging = false;
				lastLeftDown = false;
			}
			x = dragging ? dragOffsetX : x;
			y = dragging ? dragOffsetY : y;
		}else
		{
			dragging = false;
			lastLeftDown = false;
			x = anchorX - width / 2;
			y = anchorY + 18;
		}
		x = Math.max(0, Math.min(context.guiWidth() - width, x));
		y = Math.max(0, Math.min(context.guiHeight() - height, y));
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
		
		int firstVisibleSlot = scrollRowOffset * COLUMNS;
		int lastVisibleSlot =
			Math.min(slots, firstVisibleSlot + visibleRows * COLUMNS);
		for(int slot = firstVisibleSlot; slot < lastVisibleSlot; slot++)
		{
			int sx = getSlotX(x, slot);
			int sy = getSlotY(y, slot, scrollRowOffset);
			context.fill(sx, sy, sx + SLOT_INNER_SIZE, sy + SLOT_INNER_SIZE,
				0x80303030);
			RenderUtils.drawBorder2D(context, sx, sy, sx + SLOT_INNER_SIZE,
				sy + SLOT_INNER_SIZE, 0xFF555555);
		}
		
		for(ChestEntry.ItemEntry item : entry.items)
		{
			if(item == null || item.slot < firstVisibleSlot
				|| item.slot >= lastVisibleSlot)
				continue;
			ItemStack stack = ChestSearchItemStacks.decode(item);
			if(stack.isEmpty())
				continue;
			
			int sx = getSlotX(x, item.slot);
			int sy = getSlotY(y, item.slot, scrollRowOffset);
			context.renderItem(stack, sx, sy);
			drawCount(context, item.count, sx, sy);
		}
		
		if(scrollable)
			drawScrollBar(context, x, y, width, height);
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
	
	private int getSlotY(int previewY, int slot, int firstVisibleRow)
	{
		return previewY + PADDING
			+ (slot / COLUMNS - firstVisibleRow) * SLOT_SIZE;
	}
	
	private void drawCount(GuiGraphics context, int count, int x, int y)
	{
		if(count <= 1)
			return;
		Font font = WurstClient.MC.font;
		String text = Integer.toString(count);
		int tx = x + 17 - font.width(text);
		int ty = y + 9;
		context.guiRenderState.up();
		context.drawString(font, text, tx, ty, 0xFFFFFFFF, true);
	}
	
	private int getAnchorX(GuiGraphics context)
	{
		if(WurstClient.MC.screen == null)
			return context.guiWidth() / 2;
		return (int)(WurstClient.MC.mouseHandler.xpos() * context.guiWidth()
			/ WurstClient.MC.getWindow().getScreenWidth());
	}
	
	private int getAnchorY(GuiGraphics context)
	{
		if(WurstClient.MC.screen == null)
			return context.guiHeight() / 2;
		return (int)(WurstClient.MC.mouseHandler.ypos() * context.guiHeight()
			/ WurstClient.MC.getWindow().getScreenHeight());
	}
	
	private int getCurrentFixedX(ChestSearchHack hack, GuiGraphics context,
		int anchorX, int width)
	{
		if(hack.usesPinnedPreviewAnchor())
			return getPinnedPreviewX(hack, context, width);
		int x = hack.getPreviewX();
		if(x == 0 && hack.getPreviewY() == 0)
			x = anchorX - width / 2;
		return x;
	}
	
	private int getCurrentFixedY(ChestSearchHack hack, GuiGraphics context,
		int anchorY, int height)
	{
		if(hack.usesPinnedPreviewAnchor())
			return getPinnedPreviewY(hack, context, height);
		int y = hack.getPreviewY();
		if(hack.getPreviewX() == 0 && y == 0)
			y = anchorY + 18;
		return y;
	}
	
	private void handleDrag(GuiGraphics context, ChestSearchHack hack, int x,
		int y, int width, int height)
	{
		com.mojang.blaze3d.platform.Window window = WurstClient.MC.getWindow();
		if(window == null)
		{
			dragging = false;
			lastLeftDown = false;
			return;
		}
		
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean canDrag = WurstClient.MC.screen instanceof ChatScreen
			|| (WurstClient.MC.screen instanceof AbstractContainerScreen<?>
				&& !isOwnInventoryScreen(WurstClient.MC.screen));
		if(!canDrag)
		{
			if(dragging)
				commitDraggedOffset(hack);
			dragging = false;
			lastLeftDown = leftDown;
			return;
		}
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		boolean overPreview = mouseX >= x && mouseX <= x + width && mouseY >= y
			&& mouseY <= y + height;
		
		if(!dragging && leftDown && !lastLeftDown && overPreview)
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
			if(leftDown)
			{
				dragOffsetX = clampPreviewX(context,
					dragStartOffsetX
						+ (int)Math.round(mouseX - dragStartMouseX),
					previewWidth);
				dragOffsetY = clampPreviewY(context,
					dragStartOffsetY
						+ (int)Math.round(mouseY - dragStartMouseY),
					previewHeight);
			}else
			{
				commitDraggedOffset(hack);
				dragging = false;
			}
		}
		
		lastLeftDown = leftDown;
	}
	
	private void commitDraggedOffset(ChestSearchHack hack)
	{
		hack.setPreviewPosition(dragOffsetX, dragOffsetY);
	}
	
	private int clampPreviewX(GuiGraphics context, int x, int width)
	{
		return clampPreviewX(context.guiWidth(), x, width);
	}
	
	private int clampPreviewY(GuiGraphics context, int y, int height)
	{
		return clampPreviewY(context.guiHeight(), y, height);
	}
	
	private int clampPreviewX(int screenWidth, int x, int width)
	{
		return Math.max(0, Math.min(screenWidth - width, x));
	}
	
	private int clampPreviewY(int screenHeight, int y, int height)
	{
		return Math.max(0, Math.min(screenHeight - height, y));
	}
	
	private boolean canRenderOnScreen(Screen screen)
	{
		if(isOwnInventoryScreen(screen))
			return false;
		return screen == null || screen instanceof ChatScreen
			|| screen instanceof AbstractContainerScreen<?>;
	}
	
	private boolean isOwnInventoryScreen(Screen screen)
	{
		if(screen instanceof InventoryScreen)
			return true;
		if(!(screen instanceof AbstractContainerScreen<?> handled)
			|| WurstClient.MC.player == null)
			return false;
		
		AbstractContainerMenu menu = handled.getMenu();
		return menu != null && menu == WurstClient.MC.player.inventoryMenu;
	}
	
	private int getMaxVisibleRows(GuiGraphics context)
	{
		int availableHeight = Math.max(18, context.guiHeight() - 8);
		int maxVisibleRows =
			(availableHeight - PADDING * 2 + SLOT_GAP) / SLOT_SIZE;
		return Math.max(1, maxVisibleRows);
	}
	
	private void drawScrollBar(GuiGraphics context, int x, int y, int width,
		int height)
	{
		int barX = x + width - SCROLLBAR_WIDTH - 2;
		int barTop = y + 2;
		int barBottom = y + height - 2;
		context.fill(barX, barTop, barX + SCROLLBAR_WIDTH, barBottom,
			0x60303030);
		int trackHeight = Math.max(1, barBottom - barTop);
		int thumbHeight = Math.max(12,
			Math.round(trackHeight * (visibleRows / (float)totalRows)));
		int maxOffset = Math.max(0, totalRows - visibleRows);
		int thumbY = barTop;
		if(maxOffset > 0)
		{
			int travel = Math.max(0, trackHeight - thumbHeight);
			thumbY += Math.round(travel * (scrollRowOffset / (float)maxOffset));
		}
		context.fill(barX, thumbY, barX + SCROLLBAR_WIDTH,
			Math.min(barBottom, thumbY + thumbHeight), 0xC0A0A0A0);
	}
	
	private int getPinnedPreviewX(ChestSearchHack hack, GuiGraphics context,
		int width)
	{
		int gap = hack.getPreviewAnchorGap();
		PreviewAnchor anchor = hack.getPreviewAnchor();
		return switch(anchor)
		{
			case HUD_LEFT -> context.guiWidth() / 2 - 91 - gap - width;
			case HUD_RIGHT -> context.guiWidth() / 2 + 91 + gap;
			case TOP_LEFT, BOTTOM_LEFT -> gap;
			case TOP_RIGHT, BOTTOM_RIGHT -> context.guiWidth() - width - gap;
			case DRAGGED -> hack.getPreviewX();
		};
	}
	
	private int getPinnedPreviewY(ChestSearchHack hack, GuiGraphics context,
		int height)
	{
		int gap = hack.getPreviewAnchorGap();
		PreviewAnchor anchor = hack.getPreviewAnchor();
		return switch(anchor)
		{
			case HUD_LEFT, HUD_RIGHT -> context.guiHeight() - height;
			case TOP_LEFT, TOP_RIGHT -> gap;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> context.guiHeight() - height
				- gap;
			case DRAGGED -> hack.getPreviewY();
		};
	}
	
	private static double getScaledMouseX(GuiGraphics context)
	{
		com.mojang.blaze3d.platform.Window window = WurstClient.MC.getWindow();
		if(window == null)
			return 0;
		return WurstClient.MC.mouseHandler.xpos() * context.guiWidth()
			/ window.getScreenWidth();
	}
	
	private static double getScaledMouseY(GuiGraphics context)
	{
		com.mojang.blaze3d.platform.Window window = WurstClient.MC.getWindow();
		if(window == null)
			return 0;
		return WurstClient.MC.mouseHandler.ypos() * context.guiHeight()
			/ window.getScreenHeight();
	}
}
