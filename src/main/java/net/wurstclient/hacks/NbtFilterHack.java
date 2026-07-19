/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.ConnectionPacketOutputListener.ConnectionPacketOutputEvent;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"nbt filter", "nbtfilter", "chunk ban", "chunkban", "nbt bomb",
	"nbtbomb", "book ban", "bookban", "packet filter", "packetfilter",
	"anti ban", "antiban", "shulker ban", "shulkerban"})
@net.wurstclient.hack.DontSaveState
public final class NbtFilterHack extends Hack implements PacketInputListener,
	RightClickListener, PacketOutputListener, ConnectionPacketOutputListener
{
	private final SliderSetting maxBlockEntityNbtSizeKb = new SliderSetting(
		"Block entity limit", "Reject block/entity data larger than this.", 4,
		1, 1024, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix(" KB"));
	private final SliderSetting maxItemStackNbtSizeKb = new SliderSetting(
		"Item stack limit", "Reject individual items larger than this.", 64, 1,
		8192, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix(" KB"));
	private final SliderSetting maxSuspiciousPacketSizeMb = new SliderSetting(
		"Packet size limit", "Reject suspicious NBT packets larger than this.",
		2, 0.1, 32, 0.1, SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting inventoryNbtLimitMb = new SliderSetting(
		"Inventory overload limit",
		"Do not open containers when your whole inventory exceeds this size.",
		2, 0.5, 32, 0.5, SliderSetting.ValueDisplay.DECIMAL);
	private final CheckboxSetting blockOverloadedContainers =
		new CheckboxSetting("Block overloaded containers",
			"Cancel container interaction before a large inventory can create an oversized packet.",
			true);
	private final CheckboxSetting failClosedOnExceptions =
		new CheckboxSetting("Block packets on errors",
			"Cancel a packet if safely inspecting it fails.", true);
	private final SettingGroup limits = new SettingGroup("Limits",
		WText.literal("Size limits for incoming NBT and item data."), true,
		false);
	private final SettingGroup interaction = new SettingGroup(
		"Container safety",
		WText.literal(
			"Prevent oversized inventory packets before opening containers."),
		true, false);
	private final SettingGroup advanced = new SettingGroup("Advanced",
		WText.literal("Conservative error handling for unusual packets."),
		false, false);
	
	private int chunksBlocked;
	private int blockEntitiesBlocked;
	private int bundlesScanned;
	private int entityMetadataBlocked;
	private int itemStacksBlocked;
	private int quarantinedChunks;
	private int quarantinedEntities;
	private int quietBlockedPackets;
	private long lastStatusMessageMs;
	private long lastInventoryWarningMs;
	private final Set<ChunkPos> bannedChunks = new HashSet<>();
	private final Set<Integer> bannedEntityIds = new HashSet<>();
	private final Map<Integer, ChunkPos> entityIdToChunk = new HashMap<>();
	
	public NbtFilterHack()
	{
		super("NBTFilter");
		setCategory(Category.OTHER);
		limits.addChildren(maxBlockEntityNbtSizeKb, maxItemStackNbtSizeKb,
			maxSuspiciousPacketSizeMb);
		interaction.addChildren(inventoryNbtLimitMb, blockOverloadedContainers);
		advanced.addChild(failClosedOnExceptions);
		addSetting(limits);
		addSetting(interaction);
		addSetting(advanced);
		addSetting(maxBlockEntityNbtSizeKb);
		addSetting(maxItemStackNbtSizeKb);
		addSetting(maxSuspiciousPacketSizeMb);
		addSetting(inventoryNbtLimitMb);
		addSetting(blockOverloadedContainers);
		addSetting(failClosedOnExceptions);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(ConnectionPacketOutputListener.class, this);
		chunksBlocked = 0;
		blockEntitiesBlocked = 0;
		bundlesScanned = 0;
		entityMetadataBlocked = 0;
		itemStacksBlocked = 0;
		quarantinedChunks = 0;
		quarantinedEntities = 0;
		quietBlockedPackets = 0;
		lastStatusMessageMs = 0;
		bannedChunks.clear();
		bannedEntityIds.clear();
		entityIdToChunk.clear();
		ChatUtils
			.message("NBTFilter enabled - watching for dangerous packets.");
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(ConnectionPacketOutputListener.class, this);
		if(chunksBlocked > 0 || blockEntitiesBlocked > 0
			|| entityMetadataBlocked > 0 || itemStacksBlocked > 0)
		{
			ChatUtils.message("NBTFilter session: " + chunksBlocked
				+ " chunk(s), " + blockEntitiesBlocked + " block entity(ies), "
				+ entityMetadataBlocked + " entity metadata packet(s), "
				+ itemStacksBlocked
				+ " item stack carrier packet(s) blocked. Bundles checked: "
				+ bundlesScanned + ". Quarantined: " + quarantinedChunks
				+ " chunk(s), " + quarantinedEntities + " entity/entities.");
		}
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(!blockOverloadedContainers.isChecked() || MC.player == null
			|| MC.level == null
			|| !(MC.hitResult instanceof BlockHitResult hit))
			return;
		
		if(!(MC.level.getBlockEntity(
			hit.getBlockPos()) instanceof BaseContainerBlockEntity))
			return;
		
		long total = getInventoryNbtSize();
		if(total <= inventoryNbtLimitBytes())
			return;
		
		event.cancel();
		warnInventoryOverload(total);
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!isContainerActionPacket(event.getPacket()))
			return;
		if(shouldBlockContainerAction(event.getPacket()))
			event.cancel();
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		if(!isContainerActionPacket(event.getPacket()))
			return;
		if(shouldBlockContainerAction(event.getPacket()))
			event.cancel();
	}
	
	private boolean isContainerActionPacket(Packet<?> packet)
	{
		if(packet instanceof ServerboundContainerClickPacket
			|| packet instanceof ServerboundContainerButtonClickPacket
			|| packet instanceof ServerboundUseItemOnPacket)
			return true;
			
		// Keep this compatible with mappings and helper packets that use a
		// different concrete class name but still belong to a container.
		return packet != null && packet.getClass().getSimpleName()
			.toLowerCase(Locale.ROOT).contains("container");
	}
	
	private boolean shouldBlockContainerAction(Packet<?> packet)
	{
		if(!blockOverloadedContainers.isChecked())
			return false;
		
		if(isInventoryOverloaded())
			return true;
		
		InspectionResult result = checkItemStackCarrierPacket(packet);
		if(!result.dangerous())
			return false;
		
		ChatUtils.message("\u00a7c[NBTFilter] Container packet blocked: "
			+ result.reason() + formatResultDetails(result));
		return true;
	}
	
	/**
	 * Last-resort guard used directly by the connection mixin, immediately
	 * before Minecraft queues an outbound packet for Netty encoding.
	 */
	public static boolean shouldCancelOutgoingPacket(Packet<?> packet)
	{
		try
		{
			NbtFilterHack hack = getActiveInstance();
			return hack != null && hack.isContainerActionPacket(packet)
				&& hack.shouldBlockContainerAction(packet);
		}catch(Throwable ignored)
		{
			return false;
		}
	}
	
	private boolean isInventoryOverloaded()
	{
		if(!blockOverloadedContainers.isChecked())
			return false;
		
		long total = getInventoryNbtSize();
		if(total <= inventoryNbtLimitBytes())
			return false;
		
		warnInventoryOverload(total);
		return true;
	}
	
	private void warnInventoryOverload(long total)
	{
		long now = System.currentTimeMillis();
		if(now - lastInventoryWarningMs < 1000L)
			return;
		lastInventoryWarningMs = now;
		ChatUtils.message("\u00a7c[NBTFilter] Container action blocked: your "
			+ "inventory contains " + formatBytes(total)
			+ " of NBT data (limit " + formatBytes(inventoryNbtLimitBytes())
			+ ").");
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		
		// This hack is intended for inventory/container protection. Do not
		// inspect chunks, block entities, entity metadata, or bundles here:
		// those packets can contain server-controlled recursive data and are
		// unrelated to preventing oversized inventory/container packets.
		if(!(packet instanceof ClientboundContainerSetContentPacket)
			&& !(packet instanceof ClientboundContainerSetSlotPacket)
			&& !(packet instanceof ClientboundSetEntityDataPacket))
			return;
		
		InspectionResult result = checkItemStackCarrierPacket(packet);
		if(!result.dangerous())
			return;
		
		event.cancel();
		// The server may have opened the screen before sending its contents.
		// Remove that screen as well so the oversized container data cannot be
		// interacted with after its contents packet was rejected.
		if(MC.gui.screen() instanceof AbstractContainerScreen<?>)
			MC.gui.setScreen(null);
		recordBlocked(result);
	}
	
	private InspectionResult inspectPacket(Packet<?> packet)
	{
		if(packet == null)
			return InspectionResult.safe();
		
		try
		{
			if(packet instanceof ClientboundRemoveEntitiesPacket remove)
			{
				for(int id : remove.getEntityIds())
				{
					bannedEntityIds.remove(id);
					entityIdToChunk.remove(id);
				}
				return InspectionResult.safe();
			}
			
			if(packet instanceof ClientboundBundlePacket bundle)
				return checkBundlePacket(bundle);
			
			InspectionResult quarantineHit = checkQuarantinePacket(packet);
			if(quarantineHit.dangerous())
				return quarantineHit;
			
			updateTrackedEntityChunk(packet);
			InspectionResult trackedQuarantineHit =
				checkTrackedEntityQuarantine(packet);
			if(trackedQuarantineHit.dangerous())
				return trackedQuarantineHit;
			
			return inspectDangerousPacket(packet);
			
		}catch(Throwable t)
		{
			if(failClosedOnExceptions.isChecked())
				return InspectionResult.dangerous(PacketKind.PACKET,
					"inspection exception: " + t.getClass().getSimpleName(), -1,
					null, null);
			
			return InspectionResult.safe();
		}
	}
	
	private boolean isDangerousPacket(Packet<?> packet)
	{
		return inspectDangerousPacket(packet).dangerous();
	}
	
	private InspectionResult inspectDangerousPacket(Packet<?> packet)
	{
		if(packet instanceof ClientboundLevelChunkWithLightPacket chunkPkt)
			return checkChunkPacket(chunkPkt);
		
		if(packet instanceof ClientboundBlockEntityDataPacket bePkt)
			return checkBlockEntityPacket(bePkt);
		
		if(packet instanceof ClientboundSetEntityDataPacket metadata)
			return checkEntityMetadataPacket(metadata);
		
		if(packet instanceof ClientboundContainerSetContentPacket content)
			return checkItemStackCarrierPacket(content);
		if(packet instanceof ClientboundContainerSetSlotPacket slot)
			return checkItemStackCarrierPacket(slot);
		if(packet instanceof ClientboundSetEquipmentPacket equipment)
			return checkEquipmentPacket(equipment);
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkChunkPacket(
		ClientboundLevelChunkWithLightPacket packet)
	{
		ChunkPos chunkPos = new ChunkPos(packet.getX(), packet.getZ());
		if(isBannedChunk(chunkPos))
			return InspectionResult.dangerous(PacketKind.CHUNK,
				"quarantined chunk", -1, chunkPos, null);
		
		try
		{
			int sizeBytes =
				packet.getChunkData().getReadBuffer().readableBytes();
			double sizeMB = sizeBytes / (1024.0 * 1024.0);
			
			if(sizeMB > maxSuspiciousPacketSizeMb.getValue())
			{
				quarantineChunk(chunkPos);
				return InspectionResult.dangerous(PacketKind.CHUNK,
					"chunk payload "
						+ String.format(Locale.ROOT, "%.2f", sizeMB) + " MB",
					sizeBytes, chunkPos, null);
			}
			
			InspectionResult beResult = sanitizeChunkBlockEntityRecords(
				packet.getChunkData(), chunkPos);
			if(beResult.dangerous())
				return beResult;
			
		}catch(Throwable t)
		{
			if(failClosedOnExceptions.isChecked())
			{
				quarantineChunk(chunkPos);
				return InspectionResult.dangerous(PacketKind.CHUNK,
					"chunk inspection exception: "
						+ t.getClass().getSimpleName(),
					-1, chunkPos, null);
			}
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkChunkBlockEntityRecords(Object chunkData,
		ChunkPos fallbackChunk)
	{
		return sanitizeChunkBlockEntityRecords(chunkData, fallbackChunk);
	}
	
	private InspectionResult sanitizeChunkBlockEntityRecords(Object chunkData,
		ChunkPos fallbackChunk)
	{
		Object records = getChunkBlockEntityRecords(chunkData);
		if(!(records instanceof Iterable<?> iterable))
		{
			return InspectionResult.safe();
		}
		
		int strippedRecords = 0;
		boolean unsafeRecordFound = false;
		var iterator = iterable.iterator();
		
		while(iterator.hasNext())
		{
			Object record = iterator.next();
			CompoundTag tag = findCompoundTag(record);
			if(tag == null)
				continue;
			
			InspectionResult result = checkTagAgainstLimits(tag,
				PacketKind.CHUNK, fallbackChunk, null);
			if(result.dangerous())
			{
				unsafeRecordFound = true;
				int removed = true && true ? removeCurrentRecord(iterator,
					chunkData, records, record, fallbackChunk) : 0;
				if(removed > 0)
				{
					strippedRecords += removed;
					if(removed > 1)
						break;
					continue;
				}
				
			}
		}
		
		if(strippedRecords > 0)
			debugSanitizedChunk(fallbackChunk, strippedRecords);
		
		if(unsafeRecordFound && shouldHardQuarantineChunks())
			quarantineChunk(fallbackChunk);
		
		return InspectionResult.safe();
	}
	
	private Object getChunkBlockEntityRecords(Object chunkData)
	{
		for(String methodName : new String[]{"getBlockEntitiesData",
			"blockEntitiesData", "getBlockEntityData", "blockEntityData",
			"getBlockEntities", "blockEntities"})
		{
			Object records = invokeNoArg(chunkData, methodName);
			if(records instanceof Iterable<?>)
				return records;
		}
		
		if(chunkData == null)
			return null;
		
		for(var field : chunkData.getClass().getDeclaredFields())
		{
			if(!Iterable.class.isAssignableFrom(field.getType()))
				continue;
			
			try
			{
				field.setAccessible(true);
				Object records = field.get(chunkData);
				if(records instanceof Iterable<?>)
					return records;
				
			}catch(Throwable ignored)
			{}
		}
		
		return null;
	}
	
	private int removeCurrentRecord(java.util.Iterator<?> iterator,
		Object chunkData, Object records, Object record, ChunkPos chunk)
	{
		try
		{
			iterator.remove();
			return 1;
			
		}catch(Throwable ignored)
		{}
		
		if(records instanceof Collection<?> collection)
		{
			try
			{
				return collection.remove(record) ? 1 : 0;
				
			}catch(Throwable ignored)
			{}
		}
		
		return replaceDangerousBlockEntityRecords(chunkData, records, chunk);
	}
	
	private int replaceDangerousBlockEntityRecords(Object chunkData,
		Object records, ChunkPos chunk)
	{
		if(!(records instanceof Iterable<?> iterable))
			return 0;
		
		java.util.ArrayList<Object> safeRecords = new java.util.ArrayList<>();
		int removed = 0;
		for(Object candidate : iterable)
		{
			CompoundTag tag = findCompoundTag(candidate);
			InspectionResult result = tag == null ? InspectionResult.safe()
				: checkTagAgainstLimits(tag, PacketKind.CHUNK, chunk, null);
			if(result.dangerous())
			{
				removed++;
				continue;
			}
			
			safeRecords.add(candidate);
		}
		
		if(removed <= 0)
			return 0;
		
		if(records instanceof Collection<?> collection)
		{
			try
			{
				collection.clear();
				@SuppressWarnings({"rawtypes", "unchecked"})
				Collection raw = collection;
				raw.addAll(safeRecords);
				return removed;
				
			}catch(Throwable ignored)
			{}
		}
		
		if(replaceChunkBlockEntityRecordsField(chunkData, safeRecords))
			return removed;
		
		return 0;
	}
	
	private boolean replaceChunkBlockEntityRecordsField(Object chunkData,
		java.util.List<Object> safeRecords)
	{
		if(chunkData == null)
			return false;
		
		for(var field : chunkData.getClass().getDeclaredFields())
		{
			if(!List.class.isAssignableFrom(field.getType()))
				continue;
			
			try
			{
				field.setAccessible(true);
				field.set(chunkData, safeRecords);
				return true;
				
			}catch(Throwable ignored)
			{}
		}
		
		return false;
	}
	
	private void debugSanitizedChunk(ChunkPos chunk, int strippedRecords)
	{
		if(MC.player == null)
			return;
		
		ChatUtils.message("\u00a7c[NBTFilter] Sanitized chunk [" + chunkX(chunk)
			+ ", " + chunkZ(chunk) + "] - stripped " + strippedRecords
			+ " dangerous block entity record(s).");
	}
	
	private InspectionResult checkBlockEntityPacket(
		ClientboundBlockEntityDataPacket packet)
	{
		ChunkPos chunkPos = ChunkPos.containing(packet.getPos());
		if(isBannedChunk(chunkPos))
			return InspectionResult.dangerous(PacketKind.BLOCK_ENTITY,
				"block entity in quarantined chunk", -1, chunkPos, null);
		
		CompoundTag tag = packet.getTag();
		if(tag == null)
			return InspectionResult.safe();
		
		InspectionResult result =
			checkTagAgainstLimits(tag, PacketKind.BLOCK_ENTITY, chunkPos, null);
		if(result.dangerous())
		{
			if(shouldHardQuarantineChunks())
				quarantineChunk(chunkPos);
			return result;
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkEntityMetadataPacket(
		ClientboundSetEntityDataPacket packet)
	{
		int entityId = packet.id();
		if(isBannedEntity(entityId))
			return InspectionResult.dangerous(PacketKind.ENTITY_METADATA,
				"quarantined entity metadata", -1,
				entityIdToChunk.get(entityId), entityId);
		
		List<?> dataList = getPacketDataList(packet);
		if(dataList == null)
			return InspectionResult.safe();
		
		for(Object dataValue : dataList)
		{
			Object value = extractDataValue(dataValue);
			InspectionResult result = checkObjectForDangerousPayload(value,
				PacketKind.ENTITY_METADATA, entityIdToChunk.get(entityId),
				entityId);
			if(!result.dangerous())
				continue;
			
			quarantineEntity(entityId);
			ChunkPos chunk = entityIdToChunk.get(entityId);
			if(chunk != null)
				quarantineChunk(chunk);
			return result;
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkItemStackCarrierPacket(Object packet)
	{
		long total = 0;
		for(ItemStack stack : findItemStacks(packet))
		{
			InspectionResult result = checkItemStackAgainstLimits(stack,
				PacketKind.ITEM_STACK, null, null);
			if(result.dangerous())
				return result;
			
			total = saturatingAdd(total, estimateItemStackNbtSize(stack));
			if(total > inventoryNbtLimitBytes())
				return InspectionResult.dangerous(PacketKind.ITEM_STACK,
					"container contents exceed the NBT limit", total, null,
					null);
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkEquipmentPacket(
		ClientboundSetEquipmentPacket p)
	{
		int entityId = readPacketEntityId(p);
		if(isBannedEntity(entityId))
			return InspectionResult.dangerous(PacketKind.ITEM_STACK,
				"equipment for quarantined entity", -1,
				entityIdToChunk.get(entityId), entityId);
		
		for(ItemStack stack : findItemStacks(p))
		{
			InspectionResult result = checkItemStackAgainstLimits(stack,
				PacketKind.ITEM_STACK, entityIdToChunk.get(entityId), entityId);
			if(result.dangerous())
			{
				quarantineEntity(entityId);
				return result;
			}
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkBundlePacket(ClientboundBundlePacket bundle)
	{
		bundlesScanned++;
		
		for(Packet<?> sub : bundle.subPackets())
		{
			InspectionResult result = inspectPacket(sub);
			if(result.dangerous())
				return result.withKind(PacketKind.BUNDLE);
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkQuarantinePacket(Packet<?> packet)
	{
		ChunkPos affectedChunk = getAffectedChunk(packet);
		if(affectedChunk != null && isBannedChunk(affectedChunk))
			return InspectionResult.dangerous(PacketKind.CHUNK,
				"follow-up packet for quarantined chunk", -1, affectedChunk,
				null);
		
		int entityId = readPacketEntityId(packet);
		if(entityId >= 0 && isBannedEntity(entityId))
			return InspectionResult.dangerous(PacketKind.ENTITY_METADATA,
				"follow-up packet for quarantined entity", -1,
				entityIdToChunk.get(entityId), entityId);
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkTrackedEntityQuarantine(Packet<?> packet)
	{
		int entityId = readPacketEntityId(packet);
		if(entityId >= 0 && isBannedEntity(entityId))
			return InspectionResult.dangerous(PacketKind.ENTITY_METADATA,
				"entity spawned or moved inside quarantined chunk", -1,
				entityIdToChunk.get(entityId), entityId);
		
		return InspectionResult.safe();
	}
	
	private ChunkPos getAffectedChunk(Packet<?> packet)
	{
		if(packet instanceof ClientboundLevelChunkWithLightPacket p)
			return new ChunkPos(p.getX(), p.getZ());
		
		if(packet instanceof ClientboundBlockEntityDataPacket p)
			return ChunkPos.containing(p.getPos());
		
		if(packet instanceof ClientboundBlockUpdatePacket p)
			return ChunkPos.containing(p.getPos());
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket p)
			return getSectionUpdateChunk(p);
		
		if(packet instanceof ClientboundLightUpdatePacket)
		{
			Integer x = readIntByMethods(packet, "getX", "x");
			Integer z = readIntByMethods(packet, "getZ", "z");
			if(x != null && z != null)
				return new ChunkPos(x, z);
		}
		
		return null;
	}
	
	private ChunkPos getSectionUpdateChunk(
		ClientboundSectionBlocksUpdatePacket packet)
	{
		Object sectionPos = invokeNoArg(packet, "getSectionPos");
		if(sectionPos == null)
			sectionPos = invokeNoArg(packet, "sectionPos");
		if(sectionPos == null)
			sectionPos = readFieldByTypeName(packet, "SectionPos");
		
		Object chunk = invokeNoArg(sectionPos, "chunk");
		if(chunk instanceof ChunkPos chunkPos)
			return chunkPos;
		
		Integer x = readIntByMethods(sectionPos, "x", "getX", "getXChunk");
		Integer z = readIntByMethods(sectionPos, "z", "getZ", "getZChunk");
		if(x != null && z != null)
			return new ChunkPos(x, z);
		
		return null;
	}
	
	private void updateTrackedEntityChunk(Packet<?> packet)
	{
		if(packet instanceof ClientboundAddEntityPacket add)
		{
			int id = add.getId();
			ChunkPos chunk = chunkFromCoordinates(add.getX(), add.getZ());
			entityIdToChunk.put(id, chunk);
			if(isBannedChunk(chunk))
				quarantineEntity(id);
			return;
		}
		
		int entityId = readPacketEntityId(packet);
		if(entityId < 0)
			return;
		
		ChunkPos newChunk = null;
		if(packet instanceof ClientboundTeleportEntityPacket tp)
		{
			Object change = tp.change();
			Object pos = invokeNoArg(change, "position");
			newChunk = chunkFromVecLike(pos);
		}else if(packet instanceof ClientboundEntityPositionSyncPacket sync)
		{
			Object values = sync.values();
			Object pos = invokeNoArg(values, "position");
			newChunk = chunkFromVecLike(pos);
		}else if(packet instanceof ClientboundMoveEntityPacket)
			newChunk = entityIdToChunk.get(entityId);
		
		if(newChunk == null)
			return;
		
		entityIdToChunk.put(entityId, newChunk);
		if(isBannedChunk(newChunk))
			quarantineEntity(entityId);
	}
	
	private ChunkPos chunkFromVecLike(Object vec)
	{
		if(vec == null)
			return null;
		
		Double x = readDoubleByMethods(vec, "x", "getX");
		Double z = readDoubleByMethods(vec, "z", "getZ");
		if(x == null || z == null)
			return null;
		
		return chunkFromCoordinates(x, z);
	}
	
	private ChunkPos chunkFromCoordinates(double x, double z)
	{
		return new ChunkPos(((int)Math.floor(x)) >> 4,
			((int)Math.floor(z)) >> 4);
	}
	
	private void recordBlocked(InspectionResult result)
	{
		switch(result.kind())
		{
			case CHUNK -> chunksBlocked++;
			case BLOCK_ENTITY -> blockEntitiesBlocked++;
			case ENTITY_METADATA -> entityMetadataBlocked++;
			case ITEM_STACK -> itemStacksBlocked++;
			case BUNDLE ->
				{
				}
			case PACKET ->
				{
				}
		}
		
		quietBlockedPackets++;
		if(MC.player == null)
			return;
		
		long now = System.currentTimeMillis();
		if(now - lastStatusMessageMs < 1000L && quietBlockedPackets < 10)
			return;
		
		lastStatusMessageMs = now;
		quietBlockedPackets = 0;
		ChatUtils
			.message("\u00a7c[NBTFilter] Blocked " + result.kind().displayName
				+ ": " + result.reason() + formatResultDetails(result));
	}
	
	private String formatResultDetails(InspectionResult result)
	{
		StringBuilder details = new StringBuilder();
		if(result.chunk() != null)
			details.append(" chunk [").append(chunkX(result.chunk()))
				.append(", ").append(chunkZ(result.chunk())).append("]");
		if(result.entityId() != null)
			details.append(" entity #").append(result.entityId());
		if(result.estimatedBytes() >= 0)
			details.append(" size ")
				.append(formatBytes(result.estimatedBytes()));
		return details.toString();
	}
	
	private int chunkX(ChunkPos chunk)
	{
		return chunk == null ? 0 : chunk.x();
	}
	
	private int chunkZ(ChunkPos chunk)
	{
		return chunk == null ? 0 : chunk.z();
	}
	
	private boolean isBannedChunk(ChunkPos chunk)
	{
		return shouldHardQuarantineChunks() && chunk != null
			&& bannedChunks.contains(chunk);
	}
	
	private boolean isBannedEntity(int entityId)
	{
		return entityId >= 0 && bannedEntityIds.contains(entityId);
	}
	
	private void quarantineChunk(ChunkPos chunk)
	{
		if(!shouldHardQuarantineChunks() || chunk == null)
			return;
		
		if(bannedChunks.add(chunk))
			quarantinedChunks++;
	}
	
	private boolean shouldHardQuarantineChunks()
	{
		return false;
	}
	
	private void quarantineEntity(int entityId)
	{
		if(entityId < 0)
			return;
		
		if(bannedEntityIds.add(entityId))
			quarantinedEntities++;
	}
	
	private InspectionResult checkTagAgainstLimits(CompoundTag tag,
		PacketKind kind, ChunkPos chunk, Integer entityId)
	{
		try
		{
			long estimatedSize = estimateTagSize(tag);
			if(isDangerousTag(tag))
				return InspectionResult.dangerous(kind, "dangerous NBT tag",
					estimatedSize, chunk, entityId);
			
		}catch(Throwable t)
		{
			if(failClosedOnExceptions.isChecked())
				return InspectionResult.dangerous(kind,
					"NBT inspection exception: " + t.getClass().getSimpleName(),
					-1, chunk, entityId);
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkItemStackAgainstLimits(ItemStack stack,
		PacketKind kind, ChunkPos chunk, Integer entityId)
	{
		try
		{
			if(isDangerousItemStack(stack))
				return InspectionResult.dangerous(kind,
					"dangerous item stack NBT/components",
					estimateItemStackNbtSize(stack), chunk, entityId);
			
		}catch(Throwable t)
		{
			if(failClosedOnExceptions.isChecked())
				return InspectionResult
					.dangerous(kind,
						"item stack inspection exception: "
							+ t.getClass().getSimpleName(),
						-1, chunk, entityId);
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkObjectForDangerousPayload(Object value,
		PacketKind kind, ChunkPos chunk, Integer entityId)
	{
		if(value instanceof ItemStack stack)
			return checkItemStackAgainstLimits(stack, kind, chunk, entityId);
		
		if(value instanceof CompoundTag tag)
			return checkTagAgainstLimits(tag, kind, chunk, entityId);
		
		long estimated = estimateObjectNbtOrComponentSize(value);
		if(estimated > maxItemStackBytes()
			|| estimated > maxSuspiciousPacketBytes())
			return InspectionResult.dangerous(kind,
				"large NBT/component-like metadata value", estimated, chunk,
				entityId);
		
		for(ItemStack stack : findItemStacks(value))
		{
			InspectionResult result =
				checkItemStackAgainstLimits(stack, kind, chunk, entityId);
			if(result.dangerous())
				return result;
		}
		
		CompoundTag tag = findCompoundTag(value);
		if(tag != null)
			return checkTagAgainstLimits(tag, kind, chunk, entityId);
		
		return InspectionResult.safe();
	}
	
	private boolean isDangerousTag(CompoundTag tag)
	{
		if(tag == null)
			return false;
		
		long estimatedSize = estimateTagSize(tag);
		if(estimatedSize > maxBlockEntityBytes()
			|| estimatedSize > maxSuspiciousPacketBytes())
			return true;
		
		ArrayDeque<Tag> queue = new ArrayDeque<>();
		queue.add(tag);
		int visited = 0;
		
		while(!queue.isEmpty() && visited++ < 4096)
		{
			Tag current = queue.removeFirst();
			if(current instanceof CompoundTag compound)
			{
				if(compound.sizeInBytes() > maxBlockEntityBytes())
					return true;
				
				for(String key : getTagKeys(compound))
				{
					Tag child = compound.get(key);
					if(child == null)
						continue;
					
					if(isSuspiciousItemKey(key)
						&& estimateObjectNbtOrComponentSize(
							child) > maxItemStackBytes())
						return true;
					
					queue.add(child);
				}
			}else if(current instanceof Iterable<?> iterable)
			{
				for(Object child : iterable)
					if(child instanceof Tag childTag)
						queue.add(childTag);
			}
		}
		
		return false;
	}
	
	private boolean isDangerousItemStack(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		long size = estimateItemStackNbtSize(stack);
		return size > maxItemStackBytes() || size > maxSuspiciousPacketBytes();
	}
	
	private long estimateItemStackNbtSize(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return 0;
		
		if(MC.level == null)
			return Long.MAX_VALUE;
		
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
			Unpooled.buffer(), MC.level.registryAccess());
		try
		{
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
			return buf.writerIndex();
			
		}catch(Throwable t)
		{
			return Long.MAX_VALUE;
			
		}finally
		{
			buf.release();
		}
	}
	
	private long estimateObjectNbtOrComponentSize(Object value)
	{
		return estimateObjectNbtOrComponentSize(value,
			Collections.newSetFromMap(new IdentityHashMap<>()), 0);
	}
	
	private long estimateObjectNbtOrComponentSize(Object value,
		Set<Object> seen, int depth)
	{
		if(value == null)
			return 0;
		if(depth > 32 || !seen.add(value))
			return maxSuspiciousPacketBytes() + 1;
		if(value instanceof ItemStack stack)
			return estimateItemStackNbtSize(stack);
		if(value instanceof CompoundTag tag)
			return tag.sizeInBytes();
		if(value instanceof Tag tag)
			return estimateTagSize(tag);
		if(value instanceof Collection<?> collection)
		{
			long total = 0;
			for(Object entry : collection)
			{
				total = saturatingAdd(total,
					estimateObjectNbtOrComponentSize(entry, seen, depth + 1));
				if(total > maxSuspiciousPacketBytes())
					return total;
			}
			return total;
		}
		return 0;
	}
	
	private long getInventoryNbtSize()
	{
		if(MC.player == null)
			return 0;
		var inventory = MC.player.getInventory();
		long total = 0;
		for(int i = 0; i < inventory.getContainerSize(); i++)
		{
			ItemStack stack = inventory.getItem(i);
			total = saturatingAdd(total, estimateItemStackNbtSize(stack));
			if(total > inventoryNbtLimitBytes())
				return total;
		}
		return total;
	}
	
	private long saturatingAdd(long left, long right)
	{
		if(right > Long.MAX_VALUE - left)
			return Long.MAX_VALUE;
		return left + right;
	}
	
	private long estimateTagSize(Tag tag)
	{
		if(tag == null)
			return 0;
			
		// Do not call Tag#sizeInBytes here. It recursively visits the whole
		// structure and can overflow the client stack on deliberately deep NBT.
		ArrayDeque<Tag> pending = new ArrayDeque<>();
		Set<Tag> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		pending.add(tag);
		long total = 0;
		int visited = 0;
		long limit = maxSuspiciousPacketBytes();
		
		while(!pending.isEmpty())
		{
			Tag current = pending.removeFirst();
			if(!seen.add(current) || ++visited > 4096)
				return limit + 1;
				
			// This is intentionally conservative. It only needs to distinguish
			// safe data from data large enough to reject.
			total = saturatingAdd(total, 16);
			if(current instanceof CompoundTag compound)
			{
				for(String key : getTagKeys(compound))
				{
					total = saturatingAdd(total, key.length() * 2L + 4);
					Tag child = compound.get(key);
					if(child != null)
						pending.addLast(child);
				}
			}else if(current instanceof Iterable<?> iterable)
			{
				for(Object child : iterable)
					if(child instanceof Tag childTag)
						pending.addLast(childTag);
			}
			
			if(total > limit)
				return total;
		}
		
		return total;
	}
	
	private boolean isSuspiciousItemKey(String key)
	{
		if(key == null)
			return false;
		
		String lower = key.toLowerCase(Locale.ROOT);
		return lower.contains("item") || lower.contains("items")
			|| lower.contains("book") || lower.contains("pages")
			|| lower.contains("container") || lower.contains("components")
			|| lower.contains("tag") || lower.contains("blockentitytag");
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
	
	private Object extractDataValue(Object dataValue)
	{
		Object value = invokeNoArg(dataValue, "value");
		return value == null ? dataValue : value;
	}
	
	private Collection<ItemStack> findItemStacks(Object obj)
	{
		Set<ItemStack> result =
			Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		collectItemStacks(obj, result, seen, 0);
		return result;
	}
	
	private void collectItemStacks(Object obj, Set<ItemStack> result,
		Set<Object> seen, int depth)
	{
		if(obj == null || depth > 5 || seen.contains(obj))
			return;
		
		seen.add(obj);
		if(obj instanceof ItemStack stack)
		{
			result.add(stack);
			return;
		}
		
		if(obj instanceof Iterable<?> iterable)
		{
			for(Object child : iterable)
				collectItemStacks(child, result, seen, depth + 1);
			return;
		}
		
		Object value = invokeNoArg(obj, "value");
		if(value != null && value != obj)
			collectItemStacks(value, result, seen, depth + 1);
		
		for(String methodName : new String[]{"getItem", "getItems", "items",
			"contents", "slots", "equipment", "getSlots", "getCarriedItem",
			"carriedItem"})
		{
			Object child = invokeNoArg(obj, methodName);
			if(child != null && child != obj)
				collectItemStacks(child, result, seen, depth + 1);
		}
	}
	
	private CompoundTag findCompoundTag(Object obj)
	{
		if(obj instanceof CompoundTag tag)
			return tag;
		
		for(String methodName : new String[]{"tag", "getTag", "getNbt", "nbt",
			"getCompoundTag"})
		{
			Object value = invokeNoArg(obj, methodName);
			if(value instanceof CompoundTag tag)
				return tag;
		}
		
		Object fieldValue = readFieldByTypeName(obj, "CompoundTag");
		if(fieldValue instanceof CompoundTag tag)
			return tag;
		
		return null;
	}
	
	private Collection<String> getTagKeys(CompoundTag tag)
	{
		if(tag == null)
			return Collections.emptySet();
		
		for(String methodName : new String[]{"getAllKeys", "getKeys",
			"getKeySet"})
		{
			try
			{
				Object result =
					tag.getClass().getMethod(methodName).invoke(tag);
				if(result instanceof Collection<?> collection)
				{
					HashSet<String> keys = new HashSet<>();
					for(Object entry : collection)
						if(entry instanceof String s)
							keys.add(s);
					return keys;
				}
			}catch(ReflectiveOperationException ignored)
			{}
		}
		
		return Collections.emptySet();
	}
	
	private int readPacketEntityId(Packet<?> packet)
	{
		if(packet instanceof ClientboundSetEntityDataPacket metadata)
			return metadata.id();
		if(packet instanceof ClientboundAddEntityPacket add)
			return add.getId();
		if(packet instanceof ClientboundTeleportEntityPacket tp)
			return tp.id();
		if(packet instanceof ClientboundEntityPositionSyncPacket sync)
			return sync.id();
		if(packet instanceof ClientboundMoveEntityPacket move)
			return readPacketEntityId(move);
		if(packet instanceof ClientboundSetEntityMotionPacket motion)
			return readPacketEntityId(motion);
		if(packet instanceof ClientboundRotateHeadPacket rotate)
			return readPacketEntityId(rotate);
		if(packet instanceof ClientboundEntityEventPacket event)
			return readPacketEntityId(event);
		if(packet instanceof ClientboundSetEquipmentPacket equipment)
			return readPacketEntityIdReflective(equipment);
		
		return readPacketEntityIdReflective(packet);
	}
	
	private int readPacketEntityId(Object packet)
	{
		return readPacketEntityIdReflective(packet);
	}
	
	private int readPacketEntityIdReflective(Object packet)
	{
		Integer id =
			readIntByMethods(packet, "id", "getId", "entityId", "getEntityId");
		return id == null ? -1 : id;
	}
	
	private Integer readIntByMethods(Object obj, String... names)
	{
		for(String name : names)
		{
			Object value = invokeNoArg(obj, name);
			if(value instanceof Number number)
				return number.intValue();
		}
		return null;
	}
	
	private Double readDoubleByMethods(Object obj, String... names)
	{
		for(String name : names)
		{
			Object value = invokeNoArg(obj, name);
			if(value instanceof Number number)
				return number.doubleValue();
		}
		return null;
	}
	
	private Object invokeNoArg(Object obj, String methodName)
	{
		if(obj == null || methodName == null)
			return null;
		
		try
		{
			var method = obj.getClass().getMethod(methodName);
			return method.invoke(obj);
			
		}catch(Throwable ignored)
		{
			return null;
		}
	}
	
	private Object readFieldByTypeName(Object obj, String typeNamePart)
	{
		if(obj == null || typeNamePart == null)
			return null;
		
		for(var field : obj.getClass().getDeclaredFields())
		{
			if(!field.getType().getSimpleName().contains(typeNamePart))
				continue;
			
			try
			{
				field.setAccessible(true);
				return field.get(obj);
				
			}catch(Throwable ignored)
			{}
		}
		
		return null;
	}
	
	private long maxBlockEntityBytes()
	{
		return maxBlockEntityNbtSizeKb.getValueI() * 1024L;
	}
	
	private long maxItemStackBytes()
	{
		return maxItemStackNbtSizeKb.getValueI() * 1024L;
	}
	
	private long maxSuspiciousPacketBytes()
	{
		return (long)(maxSuspiciousPacketSizeMb.getValue() * 1024D * 1024D);
	}
	
	private long inventoryNbtLimitBytes()
	{
		return (long)(inventoryNbtLimitMb.getValue() * 1024D * 1024D);
	}
	
	private String formatBytes(long bytes)
	{
		if(bytes < 0)
			return "unknown";
		if(bytes < 1024)
			return bytes + " B";
		
		double kib = bytes / 1024D;
		if(kib < 1024)
			return String.format(Locale.ROOT, "%.2f KB", kib);
		
		return String.format(Locale.ROOT, "%.2f MB", kib / 1024D);
	}
	
	public static boolean shouldDropRawClientboundPacket(
		ProtocolInfo<?> protocolInfo, ByteBuf buf)
	{
		NbtFilterHack hack = getActiveInstance();
		if(hack == null || protocolInfo == null || buf == null)
			return false;
		
		if(protocolInfo.id() != ConnectionProtocol.PLAY
			|| protocolInfo.flow() != PacketFlow.CLIENTBOUND)
			return false;
		
		RawPacketId rawId = readRawPacketId(buf);
		if(rawId == null)
			return false;
		
		int payloadBytes = buf.readableBytes() - rawId.bytesRead();
		if(payloadBytes <= hack.maxSuspiciousPacketBytes())
			return false;
		
		Integer chunkPacketId = resolveChunkPacketId(protocolInfo);
		return chunkPacketId != null && rawId.id() == chunkPacketId;
	}
	
	private static NbtFilterHack getActiveInstance()
	{
		try
		{
			NbtFilterHack hack = WURST.getHax().nbtFilterHack;
			return hack != null && hack.isEnabled() ? hack : null;
			
		}catch(Throwable t)
		{
			return null;
		}
	}
	
	private static RawPacketId readRawPacketId(ByteBuf buf)
	{
		int readerIndex = buf.readerIndex();
		int readable = buf.readableBytes();
		int value = 0;
		int bytesRead = 0;
		
		while(bytesRead < Math.min(5, readable))
		{
			byte current = buf.getByte(readerIndex + bytesRead);
			value |= (current & 0x7F) << (bytesRead * 7);
			bytesRead++;
			
			if((current & 0x80) == 0)
				return new RawPacketId(value, bytesRead);
		}
		
		return null;
	}
	
	private static Integer resolveChunkPacketId(ProtocolInfo<?> protocolInfo)
	{
		Object map = findObject2IntMap(protocolInfo.codec(),
			Collections.newSetFromMap(new IdentityHashMap<>()), 0);
		if(map == null)
			return null;
		
		try
		{
			Object contains =
				map.getClass().getMethod("containsKey", Object.class).invoke(
					map, GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT);
			if(!(contains instanceof Boolean present) || !present)
				return null;
			
			Object id = map.getClass().getMethod("getInt", Object.class).invoke(
				map, GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT);
			if(id instanceof Number number)
				return number.intValue();
			
		}catch(Throwable ignored)
		{}
		
		return null;
	}
	
	private static Object findObject2IntMap(Object obj, Set<Object> seen,
		int depth)
	{
		if(obj == null || depth > 6 || seen.contains(obj))
			return null;
		
		seen.add(obj);
		if(obj.getClass().getName().contains("Object2Int"))
			return obj;
		
		for(var field : obj.getClass().getDeclaredFields())
		{
			Class<?> type = field.getType();
			if(type.isPrimitive() || type.isEnum()
				|| type.getName().startsWith("java.lang"))
				continue;
			
			try
			{
				field.setAccessible(true);
				Object value = field.get(obj);
				if(value == null)
					continue;
				
				if(value.getClass().getName().contains("Object2Int"))
					return value;
				
				Object nested = findObject2IntMap(value, seen, depth + 1);
				if(nested != null)
					return nested;
				
			}catch(Throwable ignored)
			{}
		}
		
		return null;
	}
	
	private enum PacketKind
	{
		CHUNK("chunk"),
		BLOCK_ENTITY("block entity"),
		ENTITY_METADATA("entity metadata"),
		ITEM_STACK("item stack carrier"),
		BUNDLE("bundle"),
		PACKET("packet");
		
		private final String displayName;
		
		private PacketKind(String displayName)
		{
			this.displayName = displayName;
		}
	}
	
	private record InspectionResult(boolean dangerous, PacketKind kind,
		String reason, long estimatedBytes, ChunkPos chunk, Integer entityId)
	{
		private static InspectionResult safe()
		{
			return new InspectionResult(false, PacketKind.PACKET, "", -1, null,
				null);
		}
		
		private static InspectionResult dangerous(PacketKind kind,
			String reason, long estimatedBytes, ChunkPos chunk,
			Integer entityId)
		{
			return new InspectionResult(true, kind, reason, estimatedBytes,
				chunk, entityId);
		}
		
		private InspectionResult withKind(PacketKind newKind)
		{
			return new InspectionResult(dangerous, newKind, reason,
				estimatedBytes, chunk, entityId);
		}
		
		private InspectionResult withChunk(ChunkPos newChunk)
		{
			return new InspectionResult(dangerous, kind, reason, estimatedBytes,
				newChunk, entityId);
		}
	}
	
	private record RawPacketId(int id, int bytesRead)
	{}
}
