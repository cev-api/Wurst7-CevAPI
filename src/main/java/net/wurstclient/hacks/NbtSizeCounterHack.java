/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.HandledScreenAccessor;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.RenderUtils;

@SearchTags({"NBT size", "container size", "chest size", "packet size"})
public final class NbtSizeCounterHack extends Hack
	implements PacketOutputListener
{
	private final CheckboxSetting showScreenOverlay =
		new CheckboxSetting("Screen overlay",
			"Shows the current container size while a container GUI is open.",
			true);
	private final CheckboxSetting showLastPacket =
		new CheckboxSetting("Show last packet",
			"Shows the last outgoing container click/close packet size.", true);
	
	private long currentInventoryBytes;
	private int currentContainerId = -1;
	private int currentExternalSlots;
	private String currentContainerKey = "unknown";
	private final Map<String, Long> interactedContainers = new HashMap<>();
	private String pendingContainerKey = "unknown";
	private long pendingContainerBytes;
	private long lastPacketBytes;
	private String lastPacketName = "";
	private int overlayX = Integer.MIN_VALUE;
	private int overlayY = Integer.MIN_VALUE;
	private int overlayWidth;
	private int overlayHeight;
	private boolean dragging;
	private int dragOffsetX;
	private int dragOffsetY;
	
	public NbtSizeCounterHack()
	{
		super("NbtSizeCounter");
		setCategory(Category.TOOLS);
		addSetting(showScreenOverlay);
		addSetting(showLastPacket);
	}
	
	@Override
	public String getRenderName()
	{
		String total = formatBytes(getSessionTotalBytes());
		if(currentInventoryBytes > 0)
			return getName() + " [" + formatBytes(currentInventoryBytes)
				+ " | total " + total + "]";
		
		if(getSessionTotalBytes() > 0)
			return getName() + " [total " + total + "]";
		
		return getName();
	}
	
	@Override
	protected void onEnable()
	{
		resetCounters();
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		resetCounters();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		Packet<?> packet = event.getPacket();
		
		if(packet instanceof ServerboundContainerClickPacket clickPacket)
		{
			lastPacketBytes = getPacketBodySize(clickPacket);
			lastPacketName = "Click";
			return;
		}
		
		if(packet instanceof ServerboundContainerClosePacket closePacket)
		{
			lastPacketBytes = getPacketBodySize(closePacket);
			lastPacketName = "Close";
			
			if(closePacket.getContainerId() == currentContainerId
				&& currentInventoryBytes > 0)
				finalizeCurrentContainer();
		}
	}
	
	public void onContainerScreenClosed(AbstractContainerScreen<?> screen)
	{
		if(screen != null)
			updateCurrentContainer(screen);
		
		finalizeCurrentContainer();
	}
	
	public void renderOnHandledScreen(AbstractContainerScreen<?> screen,
		GuiGraphicsExtractor context)
	{
		if(!showScreenOverlay.isChecked())
			return;
		
		updateCurrentContainer(screen);
		
		HandledScreenAccessor accessor = (HandledScreenAccessor)screen;
		Font font = MC.font;
		List<String> lines = new ArrayList<>();
		lines.add("NBT/components: " + formatBytes(currentInventoryBytes));
		lines.add("Container slots: " + currentExternalSlots);
		
		if(showLastPacket.isChecked() && lastPacketBytes > 0)
			lines.add(
				lastPacketName + " packet: " + formatBytes(lastPacketBytes));
		
		lines.add("Session total: " + formatBytes(getSessionTotalBytes())
			+ " / " + interactedContainers.size());
		
		int width = 0;
		for(String line : lines)
			width = Math.max(width, font.width(line));
		
		overlayWidth = width + 6;
		overlayHeight = lines.size() * (font.lineHeight + 1) + 5;
		
		if(overlayX == Integer.MIN_VALUE || overlayY == Integer.MIN_VALUE)
		{
			overlayX = accessor.getX();
			overlayY = accessor.getY() - overlayHeight - 6;
			if(overlayY < 4)
				overlayY = accessor.getY() + accessor.getBackgroundHeight() + 4;
		}
		
		int x = overlayX;
		int y = overlayY;
		RenderUtils.fill2D(context, x - 3, y - 3, x + width + 3,
			y + lines.size() * (font.lineHeight + 1) + 2, 0x90000000);
		
		for(int i = 0; i < lines.size(); i++)
			context.text(font, Component.literal(lines.get(i)), x,
				y + i * (font.lineHeight + 1), 0xFFE6E6E6, true);
	}
	
	public boolean handleMouseClick(MouseButtonEvent event)
	{
		if(!showScreenOverlay.isChecked() || event.button() != 0)
			return false;
		
		int mouseX = (int)Math.round(event.x());
		int mouseY = (int)Math.round(event.y());
		if(!isMouseOverOverlay(mouseX, mouseY))
			return false;
		
		dragging = true;
		dragOffsetX = mouseX - overlayX;
		dragOffsetY = mouseY - overlayY;
		return true;
	}
	
	public boolean handleMouseDrag(MouseButtonEvent event)
	{
		if(!dragging)
			return false;
		
		overlayX = (int)Math.round(event.x()) - dragOffsetX;
		overlayY = (int)Math.round(event.y()) - dragOffsetY;
		return true;
	}
	
	public boolean handleMouseRelease()
	{
		if(!dragging)
			return false;
		
		dragging = false;
		return true;
	}
	
	private void updateCurrentContainer(AbstractContainerScreen<?> screen)
	{
		AbstractContainerMenu menu = screen.getMenu();
		if(menu == null)
		{
			currentContainerId = -1;
			currentInventoryBytes = 0;
			currentExternalSlots = 0;
			currentContainerKey = "unknown";
			return;
		}
		
		currentContainerId = menu.containerId;
		currentContainerKey = createContainerKey(screen, menu);
		List<ItemStack> stacks = getExternalStacks(menu);
		currentExternalSlots = stacks.size();
		currentInventoryBytes = getStackListPayloadSize(stacks);
		
		// Keep a close-safe snapshot so totals still work if the menu gets torn
		// down before close hooks can re-read it.
		if(currentInventoryBytes > 0)
		{
			pendingContainerKey = currentContainerKey;
			pendingContainerBytes = currentInventoryBytes;
		}
	}
	
	private String createContainerKey(AbstractContainerScreen<?> screen,
		AbstractContainerMenu menu)
	{
		StringBuilder key = new StringBuilder();
		key.append(screen.getClass().getSimpleName());
		key.append('|').append(menu.slots.size());
		key.append('|').append(screen.getTitle().getString());
		
		if(MC.level != null)
			key.append('|').append(MC.level.dimension().identifier());
		
		int x = getMixedFieldInt(screen, "chestX", Integer.MIN_VALUE);
		int y = getMixedFieldInt(screen, "chestY", Integer.MIN_VALUE);
		int z = getMixedFieldInt(screen, "chestZ", Integer.MIN_VALUE);
		if(x != Integer.MIN_VALUE && y != Integer.MIN_VALUE
			&& z != Integer.MIN_VALUE)
			key.append('|').append(x).append(',').append(y).append(',')
				.append(z);
		
		return key.toString();
	}
	
	private int getMixedFieldInt(Object obj, String fieldName, int fallback)
	{
		try
		{
			var field = obj.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			return field.getInt(obj);
			
		}catch(Throwable t)
		{
			return fallback;
		}
	}
	
	private List<ItemStack> getExternalStacks(AbstractContainerMenu menu)
	{
		int totalSlots = menu.slots.size();
		int externalSlots = totalSlots;
		
		if(MC.player != null && menu != MC.player.inventoryMenu
			&& totalSlots > 36)
			externalSlots = totalSlots - 36;
		
		List<ItemStack> stacks = new ArrayList<>(externalSlots);
		for(int i = 0; i < externalSlots && i < totalSlots; i++)
			stacks.add(menu.slots.get(i).getItem());
		
		return stacks;
	}
	
	private long getStackListPayloadSize(List<ItemStack> stacks)
	{
		if(MC.level == null)
			return 0;
		
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
			Unpooled.buffer(), MC.level.registryAccess());
		try
		{
			buf.writeVarInt(stacks.size());
			for(ItemStack stack : stacks)
				ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,
					stack == null ? ItemStack.EMPTY : stack);
			
			return buf.writerIndex();
			
		}catch(Throwable t)
		{
			return 0;
			
		}finally
		{
			buf.release();
		}
	}
	
	private long getPacketBodySize(ServerboundContainerClickPacket packet)
	{
		if(MC.level == null)
			return 0;
		
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
			Unpooled.buffer(), MC.level.registryAccess());
		try
		{
			ServerboundContainerClickPacket.STREAM_CODEC.encode(buf, packet);
			return buf.writerIndex();
			
		}catch(Throwable t)
		{
			return 0;
			
		}finally
		{
			buf.release();
		}
	}
	
	private long getPacketBodySize(ServerboundContainerClosePacket packet)
	{
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try
		{
			ServerboundContainerClosePacket.STREAM_CODEC.encode(buf, packet);
			return buf.writerIndex();
			
		}catch(Throwable t)
		{
			return 0;
			
		}finally
		{
			buf.release();
		}
	}
	
	private void resetCounters()
	{
		currentInventoryBytes = 0;
		currentContainerId = -1;
		currentExternalSlots = 0;
		currentContainerKey = "unknown";
		interactedContainers.clear();
		pendingContainerKey = "unknown";
		pendingContainerBytes = 0;
		lastPacketBytes = 0;
		lastPacketName = "";
		dragging = false;
		overlayX = Integer.MIN_VALUE;
		overlayY = Integer.MIN_VALUE;
	}
	
	private void finalizeCurrentContainer()
	{
		long bytes = currentInventoryBytes > 0 ? currentInventoryBytes
			: pendingContainerBytes;
		if(bytes <= 0)
			return;
		
		String key = currentInventoryBytes > 0 ? currentContainerKey
			: pendingContainerKey;
		interactedContainers.put(key, bytes);
		currentInventoryBytes = 0;
		currentContainerId = -1;
		currentExternalSlots = 0;
		currentContainerKey = "unknown";
		pendingContainerKey = "unknown";
		pendingContainerBytes = 0;
	}
	
	private long getSessionTotalBytes()
	{
		long total = 0;
		for(long value : interactedContainers.values())
			total += value;
		return total;
	}
	
	private boolean isMouseOverOverlay(int mouseX, int mouseY)
	{
		return overlayX != Integer.MIN_VALUE && overlayY != Integer.MIN_VALUE
			&& mouseX >= overlayX - 3 && mouseX <= overlayX + overlayWidth
			&& mouseY >= overlayY - 3 && mouseY <= overlayY + overlayHeight;
	}
	
	private static String formatBytes(long bytes)
	{
		if(bytes < 1024)
			return bytes + " B";
		
		double kib = bytes / 1024D;
		if(kib < 1024)
			return String.format("%.2f KB", kib);
		
		return String.format("%.2f MB", kib / 1024D);
	}
}
