/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
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
	private Button ignoreButton;
	
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
		int wIgnore = 140, wReject = 140, wPick = 140, wTrace = 140,
			wClose = 100;
		int total = wIgnore + wReject + wPick + wTrace + wClose + gap * 4;
		int startX = (width - total) / 2;
		
		ignoreButton = Button
			.builder(Component.literal("Ignore Selected Items"),
				b -> ignoreSelected())
			.bounds(startX, height - 40, wIgnore, 20).build();
		addRenderableWidget(ignoreButton);
		
		rejectButton = Button
			.builder(Component.literal("Reject Selected Items"),
				b -> rejectSelected())
			.bounds(startX + wIgnore + gap, height - 40, wReject, 20).build();
		addRenderableWidget(rejectButton);
		
		pickButton = Button
			.builder(Component.literal("Pick Selected Items"),
				b -> pickSelected())
			.bounds(startX + wIgnore + gap + wReject + gap, height - 40, wPick,
				20)
			.build();
		addRenderableWidget(pickButton);
		
		traceButton = Button
			.builder(Component.literal("Trace Selected Items"),
				b -> traceSelected())
			.bounds(startX + wIgnore + gap + wReject + gap + wPick + gap,
				height - 40, wTrace, 20)
			.build();
		addRenderableWidget(traceButton);
		
		closeButton = Button.builder(Component.literal("Close"), b -> onClose())
			.bounds(startX + wIgnore + gap + wReject + gap + wPick + gap
				+ wTrace + gap, height - 40, wClose, 20)
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
			String id = e.itemId();
			if(id != null)
				desired.add(id);
		}
		if(desired.isEmpty())
			return;
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
		if(items.isEmpty())
			return;
		
		hack.addRejectedRulesFromItems(items);
		ChatUtils
			.message("Reject rules added for " + items.size() + " stacks.");
		onClose();
	}
	
	private void ignoreSelected()
	{
		List<ListGui.Entry> selected = listGui.getSelectedEntries();
		if(selected.isEmpty())
			return;
		
		List<ItemHandlerHack.GroundItem> items =
			selected.stream().flatMap(e -> e.groundItems().stream())
				.collect(Collectors.toList());
		if(items.isEmpty())
			return;
		
		hack.addIgnoredItemsFromItems(items);
		ChatUtils.message(
			"ItemESP ignore entries added for " + items.size() + " stacks.");
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
			String id = e.traceId();
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
		ignoreButton.active = has;
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
			context.setComponentTooltipForNextFrame(font,
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
			List<ItemHandlerHack.NearbySign> signs = hack.getTrackedSigns();
			// Group by registry ID and aggregate counts and items. XP orbs
			// are merged only when xp amount matches and items are close.
			Map<String, Aggregated> groups = new LinkedHashMap<>();
			int syntheticIndex = 0;
			for(ItemHandlerHack.GroundItem gi : raw)
			{
				String baseId = gi.baseId();
				
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
					String traceId = gi.traceId();
					String key = baseId;
					String label = gi.sourceLabel();
					if(label != null && !label.isBlank())
						key = key + "|" + label;
					if(traceId != null && !traceId.isBlank()
						&& !traceId.equals(baseId))
						key = key + "|" + traceId;
					Aggregated a = groups.get(key);
					if(a == null)
					{
						a = new Aggregated(baseId, key,
							traceId != null ? traceId : baseId,
							gi.stack().copy(), gi.displayName());
						groups.put(key, a);
					}
					a.add(gi);
				}else
				{
					// try to merge into an existing synthetic group with same
					// xp and within 5 blocks of any item in that group
					String traceId = baseId + ":xp:" + xpAmount;
					Aggregated match = null;
					for(Aggregated a : groups.values())
					{
						if(!a.itemId.equals(baseId))
							continue;
						if(!traceId.equals(a.traceId))
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
						String selId = key;
						Aggregated a = new Aggregated(baseId, selId, traceId,
							gi.stack().copy(), gi.displayName());
						groups.put(key, a);
						a.add(gi);
					}
				}
			}
			
			for(ItemHandlerHack.NearbySign sign : signs)
			{
				if(sign == null || sign.icon() == null || sign.text() == null)
					continue;
				String key = "sign:" + sign.pos().asLong();
				Aggregated a = new Aggregated(key, key,
					ItemHandlerHack.getSignTraceId(sign.pos()),
					sign.icon().copy(), "Sign: " + sign.text());
				a.closest = sign.distance();
				a.isSign = true;
				groups.put(key, a);
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
		public int getRowWidth()
		{
			return Math.max(300, width / 2);
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
			final String traceId;
			final ItemStack rep;
			final String displayName;
			final List<ItemHandlerHack.GroundItem> items = new ArrayList<>();
			int total;
			double closest = Double.MAX_VALUE;
			boolean isSign;
			
			Aggregated(String itemId, String selectionId, String traceId,
				ItemStack rep, String displayName)
			{
				this.itemId = itemId;
				this.selectionId = selectionId;
				this.traceId = traceId;
				this.rep = rep;
				this.displayName = displayName;
				this.total = 0;
				this.isSign = false;
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
				String name = group.displayName;
				return Component.translatable("narrator.select",
					name + " x" + group.total);
			}
			
			@Override
			public void renderContent(GuiGraphics context, int mouseX,
				int mouseY, boolean hovered, float tickDelta)
			{
				int x = getContentX();
				int y = getContentY();
				RenderUtils.drawItem(context, group.rep, x + 1, y + 1, true);
				
				Font tr = minecraft.font;
				context.drawString(tr, group.displayName, x + 36, y + 2,
					0xFFFFFFFF, false);
				// Optional subtitle line: enchantments or registry ID.
				String subtitle = "";
				if(hack.isShowEnchantmentsInNames())
					subtitle = hack.getEnchantmentSummary(group.rep);
				if(subtitle.isBlank() && hack.isShowRegistryName())
				{
					subtitle =
						net.wurstclient.util.ItemUtils.getStackId(group.rep);
					if(subtitle == null)
						subtitle =
							net.minecraft.core.registries.BuiltInRegistries.ITEM
								.getKey(group.rep.getItem()).toString();
				}
				if(!subtitle.isBlank())
					context.drawString(tr, subtitle, x + 36, y + 12, 0xFF909090,
						false);
				
				// Right-aligned integer distance (slightly smaller)
				String dist = ((int)Math.round(group.closest)) + " blocks";
				int distX = getContentX() + parent.getRowWidth()
					- (int)Math.round(tr.width(dist) * 0.9) - 4;
				RenderUtils.drawScaledText(context, tr, dist, distX, y + 2,
					0xFFBBBBBB, false, 0.9);
				
				// Count overlay on icon (bottom-right) with shadow
				String cnt = String.valueOf(group.total);
				int iconX = x + 1, iconY = y + 1;
				int cW = tr.width(cnt);
				// Nudge further down and right for better readability
				int textX = iconX + 24 - cW + 2; // ~3px further right vs
													// previous
				int textY = iconY + 24 - 4; // ~3px further down vs previous
				context.drawString(tr, cnt, textX + 1, textY + 1, 0xFF000000,
					false);
				context.drawString(tr, cnt, textX, textY, 0xFFFFFFFF, false);
				
				// Traced indicator: thicker rainbow border and label
				if(hack.isTraced(group.traceId))
				{
					float[] r = RenderUtils.getRainbowColor();
					int col = RenderUtils.toIntColor(r, 1.0f);
					RenderUtils.drawBorder2D(context, iconX - 1, iconY - 1,
						iconX + 25, iconY + 25, col);
					RenderUtils.drawBorder2D(context, iconX, iconY, iconX + 24,
						iconY + 24, col);
					context.drawString(tr, "TRACED", x + 36, y + 22, 0xFF55FF55,
						false);
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
				if(group.isSign)
					return java.util.Collections.emptyList();
				
				return group.items.stream().filter(
					g -> g.sourceType() == ItemHandlerHack.SourceType.GROUND
						|| g.sourceType() == ItemHandlerHack.SourceType.XP_ORB)
					.collect(Collectors.toList());
			}
			
			String itemId()
			{
				if(group.isSign)
					return null;
				if(net.wurstclient.util.ItemUtils.isSyntheticXp(group.rep))
					return null;
				return group.itemId;
			}
			
			String traceId()
			{
				return group.traceId;
			}
		}
	}
}
