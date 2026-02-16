/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public final class DualPacketListWidget
{
	private static final int HEADER_HEIGHT = 48;
	private static final int PADDING = 4;
	private static final int GAP = 8;
	private static final int ITEM_HEIGHT = 14;
	private static final int SEARCH_TO_LIST_GAP = 6;
	
	private final Font font = Minecraft.getInstance().font;
	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final String title;
	
	private final EditBox searchBox;
	private final Consumer<Set<String>> selectionChanged;
	
	private final ArrayList<String> allPackets = new ArrayList<>();
	private final LinkedHashSet<String> selectedPackets = new LinkedHashSet<>();
	private final ArrayList<String> filteredAvailable = new ArrayList<>();
	private final ArrayList<String> filteredSelected = new ArrayList<>();
	
	private int leftScroll;
	private int rightScroll;
	
	private boolean draggingLeftScrollbar;
	private boolean draggingRightScrollbar;
	private int dragThumbOffsetY;
	
	public DualPacketListWidget(int x, int y, int width, int height,
		String title, List<String> packets, Set<String> initialSelection,
		Consumer<Set<String>> selectionChanged)
	{
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.title = title;
		this.selectionChanged = selectionChanged;
		
		this.searchBox =
			new EditBox(font, x + PADDING, y + 18, width - PADDING * 2, 20,
				net.minecraft.network.chat.Component.literal(""));
		searchBox.setHint(
			net.minecraft.network.chat.Component.literal("Search packets..."));
		searchBox.setResponder(value -> updateFilteredLists());
		searchBox.setMaxLength(256);
		
		setPackets(packets);
		setSelection(initialSelection);
	}
	
	public void setPackets(List<String> packets)
	{
		allPackets.clear();
		allPackets.addAll(packets);
		updateFilteredLists();
	}
	
	public void setSelection(Set<String> selected)
	{
		selectedPackets.clear();
		selectedPackets.addAll(selected);
		updateFilteredLists();
	}
	
	public Set<String> getSelection()
	{
		return new LinkedHashSet<>(selectedPackets);
	}
	
	public EditBox getSearchBox()
	{
		return searchBox;
	}
	
	public void selectAll()
	{
		selectedPackets.clear();
		selectedPackets.addAll(allPackets);
		updateFilteredLists();
		selectionChanged.accept(getSelection());
	}
	
	public void clearAll()
	{
		selectedPackets.clear();
		updateFilteredLists();
		selectionChanged.accept(getSelection());
	}
	
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		int listWidth = (width - GAP) / 2;
		int listY = y + HEADER_HEIGHT;
		int listHeight = height - HEADER_HEIGHT - PADDING;
		int leftX = x + PADDING;
		int rightX = x + listWidth + GAP / 2;
		int panelListWidth = listWidth - PADDING;
		
		context.fill(x, y, x + width, y + height, 0xC0101010);
		context.fill(x, y, x + width, y + 1, 0xFF3A3A3A);
		context.fill(x, y + height - 1, x + width, y + height, 0xFF3A3A3A);
		context.fill(x, y, x + 1, y + height, 0xFF3A3A3A);
		context.fill(x + width - 1, y, x + width, y + height, 0xFF3A3A3A);
		
		context.drawString(font, title, x + PADDING, y + 3, 0xFFFFFFFF, false);
		
		renderList(context, leftX, listY, panelListWidth, listHeight,
			"Available", filteredAvailable, leftScroll, mouseX, mouseY, true);
		renderList(context, rightX, listY, panelListWidth, listHeight,
			"Selected (" + selectedPackets.size() + ")", filteredSelected,
			rightScroll, mouseX, mouseY, false);
	}
	
	private void renderList(GuiGraphics context, int px, int py, int pw, int ph,
		String label, List<String> entries, int scroll, int mouseX, int mouseY,
		boolean isAvailable)
	{
		context.fill(px, py, px + pw, py + ph, 0xFF1A1A1A);
		context.fill(px, py, px + pw, py + 1, 0xFF4A4A4A);
		context.fill(px, py + ph - 1, px + pw, py + ph, 0xFF4A4A4A);
		context.fill(px, py, px + 1, py + ph, 0xFF4A4A4A);
		context.fill(px + pw - 1, py, px + pw, py + ph, 0xFF4A4A4A);
		
		context.drawString(font, label, px + 2, py - 10, 0xFFAAAAAA, false);
		
		int contentHeight = Math.max(0, ph - SEARCH_TO_LIST_GAP);
		int visible = Math.max(1, contentHeight / ITEM_HEIGHT);
		int itemBaseY = py + 2 + SEARCH_TO_LIST_GAP;
		
		for(int i = 0; i < visible && i + scroll < entries.size(); i++)
		{
			int idx = i + scroll;
			String packet = entries.get(idx);
			int iy = itemBaseY + i * ITEM_HEIGHT;
			boolean hover = mouseX >= px && mouseX < px + pw && mouseY >= iy
				&& mouseY < iy + ITEM_HEIGHT;
			
			if(hover)
				context.fill(px + 1, iy, px + pw - 1, iy + ITEM_HEIGHT,
					isAvailable ? 0xFF2A4A2A : 0xFF4A2A2A);
			
			int iconColor = isAvailable ? 0xFF55FF55 : 0xFFFF5555;
			context.drawString(font, isAvailable ? "+" : "-", px + 4,
				iy + (ITEM_HEIGHT - 9) / 2, iconColor, false);
			
			String clipped = trimToWidth(packet, pw - 18);
			context.drawString(font, clipped, px + 14,
				iy + (ITEM_HEIGHT - 9) / 2, 0xFFDDDDDD, false);
		}
		
		if(entries.size() > visible && visible > 0)
		{
			int trackX1 = px + pw - 4;
			int trackX2 = px + pw - 2;
			int trackY1 = py + 2;
			int trackY2 = py + ph - 2;
			context.fill(trackX1, trackY1, trackX2, trackY2, 0xFF0D0D0D);
			
			ScrollbarGeometry g = getScrollbarGeometry(entries.size(), visible,
				scroll, trackY1, trackY2);
			context.fill(trackX1, g.thumbY(), trackX2,
				g.thumbY() + g.thumbHeight(), 0xFF5A5A5A);
		}
	}
	
	private String trimToWidth(String value, int width)
	{
		if(font.width(value) <= width)
			return value;
		
		return font.plainSubstrByWidth(value, Math.max(0, width - 6)) + "...";
	}
	
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		double mouseX = event.x();
		double mouseY = event.y();
		
		int listWidth = (width - GAP) / 2;
		int listY = y + HEADER_HEIGHT;
		int listHeight = height - HEADER_HEIGHT - PADDING;
		int visible = Math.max(1,
			Math.max(0, listHeight - SEARCH_TO_LIST_GAP) / ITEM_HEIGHT);
		int leftX = x + PADDING;
		int rightX = x + listWidth + GAP / 2;
		int panelListWidth = listWidth - PADDING;
		
		if(event.button() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			if(handleScrollbarClick(mouseX, mouseY, leftX, listY,
				panelListWidth, listHeight, true))
				return true;
			
			if(handleScrollbarClick(mouseX, mouseY, rightX, listY,
				panelListWidth, listHeight, false))
				return true;
		}
		
		if(mouseY < listY || mouseY >= listY + listHeight)
			return isMouseInside(mouseX, mouseY);
		
		int row =
			(int)((mouseY - listY - 2 - SEARCH_TO_LIST_GAP) / ITEM_HEIGHT);
		if(row < 0 || row >= visible)
			return isMouseInside(mouseX, mouseY);
		
		if(mouseX >= leftX && mouseX < leftX + panelListWidth)
		{
			int idx = row + leftScroll;
			if(idx >= 0 && idx < filteredAvailable.size())
			{
				selectedPackets.add(filteredAvailable.get(idx));
				updateFilteredLists();
				selectionChanged.accept(getSelection());
				return true;
			}
		}
		
		if(mouseX >= rightX && mouseX < rightX + panelListWidth)
		{
			int idx = row + rightScroll;
			if(idx >= 0 && idx < filteredSelected.size())
			{
				selectedPackets.remove(filteredSelected.get(idx));
				updateFilteredLists();
				selectionChanged.accept(getSelection());
				return true;
			}
		}
		
		return isMouseInside(mouseX, mouseY);
	}
	
	private boolean handleScrollbarClick(double mouseX, double mouseY, int px,
		int py, int pw, int ph, boolean left)
	{
		List<String> entries = left ? filteredAvailable : filteredSelected;
		int visible =
			Math.max(1, Math.max(0, ph - SEARCH_TO_LIST_GAP) / ITEM_HEIGHT);
		if(entries.size() <= visible)
			return false;
		
		int trackX1 = px + pw - 4;
		int trackX2 = px + pw - 2;
		int trackY1 = py + 2;
		int trackY2 = py + ph - 2;
		
		if(mouseX < trackX1 || mouseX >= trackX2 || mouseY < trackY1
			|| mouseY >= trackY2)
			return false;
		
		int currentScroll = left ? leftScroll : rightScroll;
		ScrollbarGeometry g = getScrollbarGeometry(entries.size(), visible,
			currentScroll, trackY1, trackY2);
		int mouseYInt = (int)mouseY;
		if(mouseYInt >= g.thumbY() && mouseYInt <= g.thumbY() + g.thumbHeight())
			dragThumbOffsetY = mouseYInt - g.thumbY();
		else
			dragThumbOffsetY = g.thumbHeight() / 2;
		
		if(left)
		{
			draggingLeftScrollbar = true;
			leftScroll = scrollFromMouse(entries.size(), visible, trackY1,
				trackY2, mouseYInt - dragThumbOffsetY);
		}else
		{
			draggingRightScrollbar = true;
			rightScroll = scrollFromMouse(entries.size(), visible, trackY1,
				trackY2, mouseYInt - dragThumbOffsetY);
		}
		
		return true;
	}
	
	public boolean mouseDragged(MouseButtonEvent event, double deltaX,
		double deltaY)
	{
		if(!(draggingLeftScrollbar || draggingRightScrollbar))
			return false;
		
		double mouseY = event.y();
		int listHeight = height - HEADER_HEIGHT - PADDING;
		int visible = Math.max(1,
			Math.max(0, listHeight - SEARCH_TO_LIST_GAP) / ITEM_HEIGHT);
		int listY = y + HEADER_HEIGHT;
		int trackY1 = listY + 2;
		int trackY2 = listY + listHeight - 2;
		
		if(draggingLeftScrollbar)
		{
			leftScroll = scrollFromMouse(filteredAvailable.size(), visible,
				trackY1, trackY2, (int)mouseY - dragThumbOffsetY);
		}
		
		if(draggingRightScrollbar)
		{
			rightScroll = scrollFromMouse(filteredSelected.size(), visible,
				trackY1, trackY2, (int)mouseY - dragThumbOffsetY);
		}
		
		return true;
	}
	
	public boolean mouseReleased(MouseButtonEvent event)
	{
		boolean released = draggingLeftScrollbar || draggingRightScrollbar;
		draggingLeftScrollbar = false;
		draggingRightScrollbar = false;
		dragThumbOffsetY = 0;
		return released;
	}
	
	public boolean mouseScrolled(double mouseX, double mouseY, double amount)
	{
		int listWidth = (width - GAP) / 2;
		int listY = y + HEADER_HEIGHT;
		int listHeight = height - HEADER_HEIGHT - PADDING;
		int leftX = x + PADDING;
		int rightX = x + listWidth + GAP / 2;
		int panelListWidth = listWidth - PADDING;
		int visible = Math.max(1,
			Math.max(0, listHeight - SEARCH_TO_LIST_GAP) / ITEM_HEIGHT);
		
		if(mouseY < listY || mouseY >= listY + listHeight)
			return false;
		
		if(mouseX >= leftX && mouseX < leftX + panelListWidth)
		{
			int max = Math.max(0, filteredAvailable.size() - visible);
			leftScroll = clamp(leftScroll - (int)amount, 0, max);
			return true;
		}
		
		if(mouseX >= rightX && mouseX < rightX + panelListWidth)
		{
			int max = Math.max(0, filteredSelected.size() - visible);
			rightScroll = clamp(rightScroll - (int)amount, 0, max);
			return true;
		}
		
		return false;
	}
	
	public boolean keyPressed(KeyEvent event)
	{
		return searchBox.keyPressed(event);
	}
	
	public boolean charTyped(CharacterEvent event)
	{
		return searchBox.charTyped(event);
	}
	
	private void updateFilteredLists()
	{
		String q = searchBox.getValue().toLowerCase(Locale.ROOT);
		filteredAvailable.clear();
		filteredSelected.clear();
		
		for(String packet : allPackets)
		{
			boolean matches =
				q.isEmpty() || packet.toLowerCase(Locale.ROOT).contains(q);
			if(!matches)
				continue;
			
			if(selectedPackets.contains(packet))
				filteredSelected.add(packet);
			else
				filteredAvailable.add(packet);
		}
		
		int visible =
			Math.max(1, (height - HEADER_HEIGHT - PADDING) / ITEM_HEIGHT);
		leftScroll = clamp(leftScroll, 0,
			Math.max(0, filteredAvailable.size() - visible));
		rightScroll = clamp(rightScroll, 0,
			Math.max(0, filteredSelected.size() - visible));
	}
	
	private static ScrollbarGeometry getScrollbarGeometry(int entriesCount,
		int visible, int scroll, int trackY1, int trackY2)
	{
		int trackHeight = trackY2 - trackY1;
		int maxScroll = Math.max(1, entriesCount - visible);
		int thumbHeight =
			Math.max(10, (int)(trackHeight * (visible / (double)entriesCount)));
		int thumbRange = Math.max(0, trackHeight - thumbHeight);
		int thumbY = trackY1 + (int)(thumbRange * (scroll / (double)maxScroll));
		return new ScrollbarGeometry(thumbY, thumbHeight);
	}
	
	private static int scrollFromMouse(int entriesCount, int visible,
		int trackY1, int trackY2, int proposedThumbY)
	{
		if(entriesCount <= visible)
			return 0;
		
		ScrollbarGeometry g =
			getScrollbarGeometry(entriesCount, visible, 0, trackY1, trackY2);
		int trackHeight = trackY2 - trackY1;
		int thumbRange = Math.max(1, trackHeight - g.thumbHeight());
		int clampedThumbY =
			clamp(proposedThumbY, trackY1, trackY2 - g.thumbHeight());
		double ratio = (clampedThumbY - trackY1) / (double)thumbRange;
		int maxScroll = Math.max(0, entriesCount - visible);
		return clamp((int)Math.round(ratio * maxScroll), 0, maxScroll);
	}
	
	private boolean isMouseInside(double mouseX, double mouseY)
	{
		return mouseX >= x && mouseX < x + width && mouseY >= y
			&& mouseY < y + height;
	}
	
	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
	
	private record ScrollbarGeometry(int thumbY, int thumbHeight)
	{}
}
