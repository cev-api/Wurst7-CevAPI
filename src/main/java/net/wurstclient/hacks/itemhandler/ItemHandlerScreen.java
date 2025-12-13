/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.itemhandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.wurstclient.WurstClient;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.util.RenderUtils;
// (no extra imports required)

public class ItemHandlerScreen extends Screen
{
	private final Screen previous;
	private final ItemHandlerHack hack;
	
	private ListGui listGui;
	private Button pickButton;
	private Button closeButton;
	
	public ItemHandlerScreen(Screen prev, ItemHandlerHack hack)
	{
		super(Component.empty());
		this.previous = prev;
		this.hack = hack;
	}
	
	@Override
	protected void init()
	{
		int top = 40;
		int listHeight = height - 96;
		
		listGui =
			new ListGui(Minecraft.getInstance(), width, listHeight, top, 36);
		addWidget(listGui);
		
		int gap = 10;
		int wReject = 140, wPick = 140, wTrace = 140, wClose = 100;
		int total = wReject + wPick + wTrace + wClose + gap * 3;
		int startX = (width - total) / 2;
		
		rejectButton = Button
			.builder(Component.literal("Reject Selected Items"),
				b -> rejectSelected())
			.bounds(startX, height - 40, wReject, 20).build();
		addRenderableWidget(rejectButton);
		
		pickButton = Button
			.builder(Component.literal("Pick Selected Items"),
				b -> pickSelected())
			.bounds(startX + wReject + gap, height - 40, wPick, 20).build();
		addRenderableWidget(pickButton);
		
		traceButton = Button
			.builder(Component.literal("Trace Selected Items"),
				b -> traceSelected())
			.bounds(startX + wReject + gap + wPick + gap, height - 40, wTrace,
				20)
			.build();
		addRenderableWidget(traceButton);
		
		closeButton = Button.builder(Component.literal("Close"), b -> onClose())
			.bounds(startX + wReject + gap + wPick + gap + wTrace + gap,
				height - 40, wClose, 20)
			.build();
		addRenderableWidget(closeButton);
		
		listGui.ensureSelection();
		updateButtons();
	}
	
	private void pickSelected()
	{
		// Start a pick session for selected items and guide pickup.
		List<ListGui.Entry> selected = listGui.getSelectedEntries();
		if(selected.isEmpty())
			return;
		
		java.util.Set<String> desired = new java.util.LinkedHashSet<>();
		for(ListGui.Entry e : selected)
		{
			String k = e.selectionKey();
			if(k != null)
				desired.add(k);
		}
		hack.beginPickFilterSession(desired);
		ChatUtils
			.message("Pick filter: drop non-targets until selected is picked.");
		onClose();
	}
	
	private void rejectSelected()
	{
		List<ListGui.Entry> selected = listGui.getSelectedEntries();
		if(selected.isEmpty())
			return;
		
		List<ItemHandlerHack.GroundItem> items =
			selected.stream().flatMap(e -> e.groundItems().stream())
				.collect(Collectors.toList());
		
		hack.addRejectedRulesFromItems(items);
		ChatUtils
			.message("Reject rules added for " + items.size() + " stacks.");
		onClose();
	}
	
	private Button traceButton;
	
	private void traceSelected()
	{
		List<ListGui.Entry> selected = listGui.getSelectedEntries();
		if(selected.isEmpty())
			return;
		int enabled = 0, disabled = 0;
		for(ListGui.Entry e : selected)
		{
			String id = e.selectionKey();
			if(id == null)
				continue;
			boolean was = hack.isTraced(id);
			hack.toggleTracedItem(id);
			if(was)
				disabled++;
			else
				enabled++;
		}
		ChatUtils.message("Tracing: +" + enabled + "  -" + disabled);
		// keep the GUI open and refresh so the traced indicator updates
		listGui.reloadFromHack();
		updateButtons();
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(previous);
	}
	
	@Override
	public void tick()
	{
		super.tick();
		listGui.reloadFromHack();
		updateButtons();
	}
	
	private Button rejectButton;
	
	private void updateButtons()
	{
		boolean has = listGui.hasSelection();
		pickButton.active = has;
		rejectButton.active = has;
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		// Render list first so it appears behind buttons
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		context.drawCenteredString(font, "Item Handler", width / 2, 12,
			0xFFFFFFFF);
		
		// Render children/widgets (buttons, etc.) like other screens
		for(net.minecraft.client.gui.components.Renderable r : renderables)
			r.render(context, mouseX, mouseY, partialTicks);
		
		// Tooltip for pick button when inactive
		if(pickButton.isHoveredOrFocused() && !pickButton.active)
			context.renderComponentTooltip(font,
				java.util.Arrays
					.asList(Component.literal("Select items to pick.")),
				mouseX, mouseY);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	private static final class ListGui
		extends MultiSelectEntryListWidget<ListGui.Entry>
	{
		private final ItemHandlerHack hack =
			WurstClient.INSTANCE.getHax().itemHandlerHack;
		
		public ListGui(Minecraft client, int width, int height, int top,
			int itemHeight)
		{
			super(client, width, height, top, itemHeight);
			reloadFromHack();
			ensureSelection();
		}
		
		public void reloadFromHack()
		{
			var prev = captureState();
			clearEntries();
			List<ItemHandlerHack.GroundItem> raw =
				hack.getTrackedItems().stream()
					.filter(g -> g.distance() <= hack.getPopupRange()).toList();
			// Group by registry ID and aggregate counts and items. XP orbs
			// are merged only when xp amount matches and items are close.
			Map<String, Aggregated> groups = new LinkedHashMap<>();
			int syntheticIndex = 0;
			for(ItemHandlerHack.GroundItem gi : raw)
			{
				String baseId =
					net.wurstclient.util.ItemUtils.getStackId(gi.stack());
				if(baseId == null)
					baseId =
						net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(gi.stack().getItem()).toString();
				
				// detect synthetic XP metadata
				boolean isXp = false;
				int xpAmount = -1;
				try
				{
					if(net.wurstclient.util.ItemUtils.isSyntheticXp(gi.stack()))
					{
						isXp = true;
						xpAmount = net.wurstclient.util.ItemUtils
							.getXpAmount(gi.stack());
					}
				}catch(Throwable ignored)
				{}
				
				if(!isXp)
				{
					Aggregated a = groups.get(baseId);
					if(a == null)
					{
						a = new Aggregated(baseId, baseId, gi.stack().copy());
						groups.put(baseId, a);
					}
					a.add(gi);
				}else
				{
					// try to merge into an existing synthetic group with same
					// xp and within 5 blocks of any item in that group
					Aggregated match = null;
					for(Aggregated a : groups.values())
					{
						if(!a.itemId.equals(baseId))
							continue;
						// compare rep's xp
						int repXp = -1;
						if(!a.items.isEmpty())
							repXp = net.wurstclient.util.ItemUtils
								.getXpAmount(a.items.get(0).stack());
						if(repXp != xpAmount)
							continue;
						// proximity check (<= 5.0 blocks) against any item in
						// the group
						boolean close = false;
						for(ItemHandlerHack.GroundItem existing : a.items)
						{
							if(existing.position()
								.distanceTo(gi.position()) <= 5.0)
							{
								close = true;
								break;
							}
						}
						if(!close)
							continue;
						match = a;
						break;
					}
					if(match != null)
						match.add(gi);
					else
					{
						String key = baseId + ":xp:" + xpAmount + ":"
							+ (syntheticIndex++);
						String selId = baseId + ":xp:" + xpAmount;
						Aggregated a =
							new Aggregated(baseId, selId, gi.stack().copy());
						groups.put(key, a);
						a.add(gi);
					}
				}
			}
			// Preserve insertion order; add entries sorted by closest distance
			groups.values().stream()
				.sorted(java.util.Comparator
					.comparingDouble(Aggregated::closestDistance))
				.map(a -> new Entry(this, a)).forEach(this::addEntry);
			if(prev != null)
				restoreState(prev);
			else
				ensureSelection();
		}
		
		@Override
		protected String getSelectionKey(Entry entry)
		{
			return entry.selectionKey();
		}
		
		private static final class Aggregated
		{
			final String itemId;
			final String selectionId;
			final ItemStack rep;
			final List<ItemHandlerHack.GroundItem> items = new ArrayList<>();
			int total;
			double closest = Double.MAX_VALUE;
			
			Aggregated(String itemId, String selectionId, ItemStack rep)
			{
				this.itemId = itemId;
				this.selectionId = selectionId;
				this.rep = rep;
				this.total = 0;
			}
			
			void add(ItemHandlerHack.GroundItem gi)
			{
				items.add(gi);
				total += gi.stack().getCount();
				if(gi.distance() < closest)
					closest = gi.distance();
			}
			
			double closestDistance()
			{
				return closest;
			}
		}
		
		private final class Entry
			extends MultiSelectEntryListWidget.Entry<Entry>
		{
			private final ListGui parent;
			private final Aggregated group;
			
			Entry(ListGui parent, Aggregated group)
			{
				super(parent);
				this.parent = parent;
				this.group = group;
			}
			
			@Override
			public String selectionKey()
			{
				return group.selectionId != null ? group.selectionId
					: group.itemId;
			}
			
			@Override
			public Component getNarration()
			{
				String name = group.rep.getHoverName().getString();
				return Component.translatable("narrator.select",
					name + " x" + group.total);
			}
			
			@Override
			public void render(GuiGraphics context, int index, int y, int x,
				int entryWidth, int entryHeight, int mouseX, int mouseY,
				boolean hovered, float tickDelta)
			{
				int contentX = x + 1;
				int contentY = y + 1;
				RenderUtils.drawItem(context, group.rep, contentX, contentY,
					true);
				
				Font tr = minecraft.font;
				context.drawString(tr, group.rep.getHoverName().getString(),
					contentX + 36, contentY + 2, 0xFFFFFFFF, false);
				String regId =
					net.wurstclient.util.ItemUtils.getStackId(group.rep);
				if(regId == null)
					regId = net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(group.rep.getItem()).toString();
				context.drawString(tr, regId, contentX + 36, contentY + 12,
					0xFF909090, false);
				
				String dist = ((int)Math.round(group.closest)) + " blocks";
				int distX = contentX + parent.getRowWidth()
					- (int)Math.round(tr.width(dist) * 0.9) - 4;
				RenderUtils.drawScaledText(context, tr, dist, distX,
					contentY + 2, 0xFFBBBBBB, false, 0.9);
				
				String cnt = String.valueOf(group.total);
				int iconX = contentX, iconY = contentY;
				int cW = tr.width(cnt);
				int textX = iconX + 24 - cW + 2;
				int textY = iconY + 24 - 4;
				context.drawString(tr, cnt, textX + 1, textY + 1, 0xFF000000,
					false);
				context.drawString(tr, cnt, textX, textY, 0xFFFFFFFF, false);
				
				String traceId = group.selectionId != null ? group.selectionId
					: group.itemId;
				if(hack.isTraced(traceId))
				{
					float[] r = RenderUtils.getRainbowColor();
					int col = RenderUtils.toIntColor(r, 1.0f);
					RenderUtils.drawBorder2D(context, iconX - 1, iconY - 1,
						iconX + 25, iconY + 25, col);
					RenderUtils.drawBorder2D(context, iconX, iconY, iconX + 24,
						iconY + 24, col);
					context.drawString(tr, "TRACED", contentX + 36,
						contentY + 22, 0xFF55FF55, false);
				}
			}
			
			@SuppressWarnings("unused")
			List<Integer> entityIds()
			{
				return group.items.stream().map(g -> g.entityId())
					.collect(Collectors.toList());
			}
			
			List<ItemHandlerHack.GroundItem> groundItems()
			{
				return group.items;
			}
		}
	}
}
