/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.HandledScreenAccessor;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;

@SearchTags({"NBT size", "container size", "chest size", "packet size"})
public final class NbtSizeCounterHack extends Hack implements
	PacketOutputListener, PacketInputListener, UpdateListener, RenderListener
{
	private enum SizeUnit
	{
		KB(1024L),
		MB(1024L * 1024L);
		
		private final long bytes;
		
		SizeUnit(long bytes)
		{
			this.bytes = bytes;
		}
	}
	
	private final CheckboxSetting showScreenOverlay =
		new CheckboxSetting("Screen overlay",
			"Shows the current container size while a container GUI is open.",
			true);
	private final CheckboxSetting showLastPacket =
		new CheckboxSetting("Show last packet",
			"Shows the last outgoing container click/close packet size.", true);
	private final CheckboxSetting showFloorItemLabels =
		new CheckboxSetting("Floor item labels",
			"Shows estimated NBT/components size above dropped items.", true);
	private final SliderSetting ignoreFloorItemsBelowKb =
		new SliderSetting("Ignore below (KB)",
			"Ignore dropped items smaller than this estimated size.", 0, 0,
			4096, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting includeFloorInTotals =
		new CheckboxSetting("Add floor items to totals",
			"Adds dropped-item counts and bytes to the total summary.", true);
	private final CheckboxSetting preventChunkEntry = new CheckboxSetting(
		"Block heavy chunk entry",
		"Prevents entering chunks over the configured floor-item size threshold (unless already in such a chunk).",
		false);
	private final SliderSetting chunkLimitValue = new SliderSetting(
		"Chunk limit value", "Threshold for heavy-chunk entry blocking.", 512,
		1, 32768, 1, ValueDisplay.INTEGER);
	private final EnumSetting<SizeUnit> chunkLimitUnit =
		new EnumSetting<>("Chunk limit unit", SizeUnit.values(), SizeUnit.KB);
	private final CheckboxSetting savedOverlayPosition =
		new CheckboxSetting("Saved overlay position", false);
	private final SliderSetting savedOverlayX = new SliderSetting(
		"Saved overlay X", 0, -4000, 4000, 1, ValueDisplay.INTEGER);
	private final SliderSetting savedOverlayY = new SliderSetting(
		"Saved overlay Y", 0, -4000, 4000, 1, ValueDisplay.INTEGER);
	
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
	
	private final List<FloorItemEstimate> floorItems = new ArrayList<>();
	private final Map<Long, Long> floorChunkBytes = new HashMap<>();
	private final Map<Integer, PacketEstimate> packetItemEstimates =
		new HashMap<>();
	private long floorTotalBytes;
	private int floorTotalCount;
	private ChunkPos previousChunk;
	private Vec3 previousSafePos;
	
	public NbtSizeCounterHack()
	{
		super("NbtSizeCounter");
		setCategory(Category.TOOLS);
		addSetting(showScreenOverlay);
		addSetting(showLastPacket);
		addSetting(showFloorItemLabels);
		addSetting(ignoreFloorItemsBelowKb);
		addSetting(includeFloorInTotals);
		addSetting(preventChunkEntry);
		addSetting(chunkLimitValue);
		addSetting(chunkLimitUnit);
		addHiddenPositionSetting(savedOverlayPosition);
		addHiddenPositionSetting(savedOverlayX);
		addHiddenPositionSetting(savedOverlayY);
	}
	
	@Override
	public String getRenderName()
	{
		String total = formatBytes(getSessionTotalBytes());
		if(currentInventoryBytes > 0)
			return getName() + " [" + formatBytes(currentInventoryBytes)
				+ " | total " + total + "]";
		
		if(getSessionTotalBytes() > 0 || (includeFloorInTotals.isChecked()
			&& (floorTotalBytes > 0 || floorTotalCount > 0)))
		{
			if(includeFloorInTotals.isChecked())
				return getName() + " [total " + total + " + floor "
					+ formatBytes(floorTotalBytes) + " (" + floorTotalCount
					+ ")]";
			return getName() + " [total " + total + "]";
		}
		
		return getName();
	}
	
	@Override
	protected void onEnable()
	{
		resetCounters();
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
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
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(MC.level == null)
			return;
		
		if(!(event
			.getPacket() instanceof ClientboundSetEntityDataPacket update))
			return;
		
		if(!(MC.level.getEntity(update.id()) instanceof ItemEntity))
			return;
		
		ItemStack stack = extractItemStackFromEntityDataPacket(update);
		if(stack == null)
			return;
		
		long bytes = getItemStackPayloadSize(stack);
		if(bytes <= 0)
			return;
		
		packetItemEstimates.put(update.id(),
			new PacketEstimate(bytes, System.currentTimeMillis()));
	}
	
	@Override
	public void onUpdate()
	{
		scanFloorItems();
		if(preventChunkEntry.isChecked())
			enforceChunkEntryGuard();
		else
			updateSafeChunkTracking();
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(!showFloorItemLabels.isChecked() || MC.level == null
			|| MC.player == null)
			return;
		
		for(FloorItemEstimate estimate : floorItems)
		{
			ItemEntity entity = estimate.item();
			if(entity == null || !entity.isAlive())
				continue;
			
			Vec3 pos = entity.position().add(0, entity.getBbHeight() + 0.35, 0);
			String src = estimate.packetDerived() ? "[packet]" : "[local]";
			drawWorldLabel(matrices, formatBytes(estimate.bytes()) + " " + src,
				pos.x, pos.y, pos.z, 0xFFFFFFFF);
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
		
		if(overlayX == Integer.MIN_VALUE || overlayY == Integer.MIN_VALUE)
		{
			if(savedOverlayPosition.isChecked())
			{
				overlayX = savedOverlayX.getValueI();
				overlayY = savedOverlayY.getValueI();
			}
		}
		
		updateCurrentContainer(screen);
		
		HandledScreenAccessor accessor = (HandledScreenAccessor)screen;
		Font font = MC.font;
		List<String> lines = new ArrayList<>();
		lines.add("NBT/components: " + formatBytes(currentInventoryBytes));
		lines.add("Container slots: " + currentExternalSlots);
		lines.add("Floor items: " + floorTotalCount + " ("
			+ formatBytes(floorTotalBytes) + ")");
		
		if(showLastPacket.isChecked() && lastPacketBytes > 0)
			lines.add(
				lastPacketName + " packet: " + formatBytes(lastPacketBytes));
		
		long sessionTotal = getSessionTotalBytes();
		if(includeFloorInTotals.isChecked())
			sessionTotal += floorTotalBytes;
		lines.add("Session total: " + formatBytes(sessionTotal) + " / "
			+ interactedContainers.size());
		
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
		savedOverlayPosition.setChecked(true);
		savedOverlayX.setValue(overlayX);
		savedOverlayY.setValue(overlayY);
		return true;
	}
	
	private void addHiddenPositionSetting(
		net.wurstclient.settings.Setting setting)
	{
		setting.setVisibleInGui(false);
		addSetting(setting);
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
	
	private long getItemStackPayloadSize(ItemStack stack)
	{
		if(MC.level == null)
			return 0;
		
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
			Unpooled.buffer(), MC.level.registryAccess());
		try
		{
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
		floorItems.clear();
		floorChunkBytes.clear();
		packetItemEstimates.clear();
		floorTotalBytes = 0;
		floorTotalCount = 0;
		previousChunk = null;
		previousSafePos = null;
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
	
	private long minFloorItemBytes()
	{
		return ignoreFloorItemsBelowKb.getValueI() * 1024L;
	}
	
	private long chunkLimitBytes()
	{
		return (long)chunkLimitValue.getValueI()
			* chunkLimitUnit.getSelected().bytes;
	}
	
	private void scanFloorItems()
	{
		floorItems.clear();
		floorChunkBytes.clear();
		floorTotalBytes = 0;
		floorTotalCount = 0;
		
		ClientLevel level = MC.level;
		if(level == null)
			return;
		
		long minBytes = minFloorItemBytes();
		long now = System.currentTimeMillis();
		for(var entity : level.entitiesForRendering())
		{
			if(!(entity instanceof ItemEntity itemEntity)
				|| !itemEntity.isAlive())
				continue;
			
			long localBytes = getItemStackPayloadSize(itemEntity.getItem());
			PacketEstimate packetEstimate =
				packetItemEstimates.get(itemEntity.getId());
			boolean packetFresh = packetEstimate != null
				&& now - packetEstimate.timestampMs() <= 15000L;
			long bytes = packetFresh ? packetEstimate.bytes() : localBytes;
			if(bytes < minBytes)
				continue;
			
			floorItems
				.add(new FloorItemEstimate(itemEntity, bytes, packetFresh));
			floorTotalCount++;
			floorTotalBytes += bytes;
			
			ChunkPos chunk = itemEntity.chunkPosition();
			long key = chunkKey(chunk);
			floorChunkBytes.merge(key, bytes, Long::sum);
		}
		
		packetItemEstimates.entrySet()
			.removeIf(e -> now - e.getValue().timestampMs() > 30000L);
	}
	
	private void updateSafeChunkTracking()
	{
		if(MC.player == null)
			return;
		
		previousChunk = MC.player.chunkPosition();
		previousSafePos = MC.player.position();
	}
	
	private void enforceChunkEntryGuard()
	{
		if(MC.player == null)
			return;
		
		ChunkPos current = MC.player.chunkPosition();
		if(previousChunk == null)
		{
			previousChunk = current;
			previousSafePos = MC.player.position();
			return;
		}
		
		if(current.equals(previousChunk))
		{
			previousSafePos = MC.player.position();
			return;
		}
		
		long limit = chunkLimitBytes();
		long targetBytes = floorChunkBytes.getOrDefault(chunkKey(current), 0L);
		if(targetBytes > limit)
		{
			long previousBytes =
				floorChunkBytes.getOrDefault(chunkKey(previousChunk), 0L);
			
			// unless they're already in heavy chunks
			if(previousBytes <= limit && previousSafePos != null)
			{
				MC.player.setPos(previousSafePos.x, previousSafePos.y,
					previousSafePos.z);
				Vec3 v = MC.player.getDeltaMovement();
				MC.player.setDeltaMovement(0, v.y, 0);
			}
			return;
		}
		
		previousChunk = current;
		previousSafePos = MC.player.position();
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb)
	{
		if(text == null || text.isEmpty())
			return;
		
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		Vec3 target = new Vec3(x, y, z);
		Vec3 dir = target.subtract(cam);
		double dist = dir.length();
		double lx = x;
		double ly = y;
		double lz = z;
		
		// Anchor all labels to a near point so they stay readable
		// regardless of distance (perspective shrinks distant objects).
		if(dist > 1.0)
		{
			double anchor = Math.min(dist, 12.0);
			Vec3 anchored = cam.add(dir.scale(anchor / dist));
			lx = anchored.x;
			ly = anchored.y;
			lz = anchored.z;
		}
		
		matrices.translate(lx - cam.x, ly - cam.y, lz - cam.z);
		Camera camera = MC.gameRenderer.mainCamera();
		if(camera != null)
		{
			matrices.mulPose(
				com.mojang.math.Axis.YP.rotationDegrees(-camera.yRot()));
			matrices.mulPose(
				com.mojang.math.Axis.XP.rotationDegrees(camera.xRot()));
		}
		matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
		
		float scale = 0.025F;
		scale *= RenderUtils.getCappedWorldLabelScale(0.65F, dist);
		matrices.scale(scale, -scale, scale);
		
		Font tr = MC.font;
		float w = tr.width(text) / 2F;
		int baseAlpha = (argb >>> 24) & 0xFF;
		int bgAlpha =
			(int)Math.round(MC.options.getBackgroundOpacity(0.25F) * baseAlpha);
		int bg = (bgAlpha << 24);
		var matrix = matrices.last().pose();
		int stroke = (Math.max(0, Math.min(255, baseAlpha)) << 24);
		
		net.wurstclient.util.RenderUtils.drawOutlinedTextInBatch(tr, text, -w,
			0, argb, stroke, matrix, Font.DisplayMode.SEE_THROUGH, bg,
			0xF000F0);
		matrices.popPose();
	}
	
	private long chunkKey(ChunkPos pos)
	{
		return ((long)pos.x() << 32) ^ (pos.z() & 0xFFFFFFFFL);
	}
	
	private ItemStack extractItemStackFromEntityDataPacket(
		ClientboundSetEntityDataPacket packet)
	{
		List<?> dataList = getPacketDataList(packet);
		if(dataList == null)
			return null;
		
		for(Object dataValue : dataList)
		{
			if(dataValue == null)
				continue;
			
			Object value = invokeNoArg(dataValue, "value");
			if(value instanceof ItemStack stack)
				return stack;
		}
		
		return null;
	}
	
	private List<?> getPacketDataList(ClientboundSetEntityDataPacket packet)
	{
		Object result = invokeNoArg(packet, "packedItems");
		if(result instanceof List<?> list)
			return list;
		
		result = invokeNoArg(packet, "unpackedData");
		if(result instanceof List<?> list)
			return list;
		
		for(var method : packet.getClass().getMethods())
		{
			if(method.getParameterCount() != 0
				|| !List.class.isAssignableFrom(method.getReturnType()))
				continue;
			
			try
			{
				Object value = method.invoke(packet);
				if(value instanceof List<?> list)
					return list;
				
			}catch(Throwable ignored)
			{}
		}
		
		return null;
	}
	
	private Object invokeNoArg(Object obj, String methodName)
	{
		try
		{
			var method = obj.getClass().getMethod(methodName);
			return method.invoke(obj);
			
		}catch(Throwable ignored)
		{
			return null;
		}
	}
	
	private record FloorItemEstimate(ItemEntity item, long bytes,
		boolean packetDerived)
	{}
	
	private record PacketEstimate(long bytes, long timestampMs)
	{}
}
