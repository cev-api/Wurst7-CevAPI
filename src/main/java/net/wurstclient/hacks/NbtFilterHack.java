/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import io.netty.buffer.Unpooled; // ### ADDED ###
import java.util.ArrayDeque; // ### ADDED ###
// ### ADDED ###
import java.util.Collection; // ### ADDED ###
import java.util.Collections; // ### ADDED ###
import java.util.HashMap; // ### ADDED ###
import java.util.HashSet; // ### ADDED ###
import java.util.IdentityHashMap; // ### ADDED ###
import java.util.List; // ### ADDED ###
import java.util.Locale; // ### ADDED ###
import java.util.Map; // ### ADDED ###
import java.util.Set; // ### ADDED ###
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag; // ### ADDED ###
import net.minecraft.network.RegistryFriendlyByteBuf; // ### ADDED ###
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket; // ###
																		// ADDED
																		// ###
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket; // ###
																			// ADDED
																			// ###
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket; // ###
																					// ADDED
																					// ###
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket; // ###
																				// ADDED
																				// ###
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket; // ###
																			// ADDED
																			// ###
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket; // ###
																				// ADDED
																				// ###
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket; // ###
																			// ADDED
																			// ###
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket; // ###
																		// ADDED
																		// ###
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket; // ###
																			// ADDED
																			// ###
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket; // ###
																		// ADDED
																		// ###
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket; // ###
																					// ADDED
																					// ###
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket; // ###
																			// ADDED
																			// ###
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket; // ###
																				// ADDED
																				// ###
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket; // ###
																			// ADDED
																			// ###
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket; // ###
																			// ADDED
																			// ###
import net.minecraft.world.item.ItemStack; // ### ADDED ###
import net.minecraft.world.level.ChunkPos; // ### ADDED ###
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"nbt filter", "nbtfilter", "chunk ban", "chunkban", "nbt bomb",
	"nbtbomb", "book ban", "bookban", "packet filter", "packetfilter",
	"anti ban", "antiban", "shulker ban", "shulkerban"})
@net.wurstclient.hack.DontSaveState
public final class NbtFilterHack extends Hack
	implements PacketInputListener, UpdateListener
{
	private final SliderSetting maxChunkSize = new SliderSetting(
		"Max chunk size", "Chunk packets larger than this (in MB) are blocked.",
		1.5, 0.1, 10, 0.1, SliderSetting.ValueDisplay.DECIMAL);
	
	private final CheckboxSetting filterBlockEntities =
		new CheckboxSetting("Filter block entities",
			"Block entity data packets with NBT > 4 KB are cancelled.", true);
	
	private final CheckboxSetting filterBundles = new CheckboxSetting(
		"Unwrap bundles",
		"Inspect inside ClientboundBundlePacket and filter dangerous sub-packets.",
		true);
	
	// ### ADDED ###
	private final SliderSetting maxBlockEntityNbtSizeKb =
		new SliderSetting("Max block entity NBT size",
			"Block entity NBT larger than this (in KB) is cancelled.", 4, 1,
			1024, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix(" KB"));
	
	// ### ADDED ###
	private final SliderSetting maxItemStackNbtSizeKb = new SliderSetting(
		"Max item stack NBT size",
		"ItemStack NBT/components larger than this (in KB) are cancelled.", 64,
		1, 8192, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix(" KB"));
	
	// ### ADDED ###
	private final SliderSetting maxSuspiciousPacketSizeMb =
		new SliderSetting("Max suspicious packet size",
			"NBT/item carrier packets larger than this (in MB) are cancelled.",
			2, 0.1, 32, 0.1, SliderSetting.ValueDisplay.DECIMAL);
	
	// ### ADDED ###
	private final CheckboxSetting filterEntityMetadata = new CheckboxSetting(
		"Filter entity metadata",
		"Inspect entity metadata packets for dangerous NBT/item stacks.", true);
	
	// ### ADDED ###
	private final CheckboxSetting filterItemStackCarrierPackets =
		new CheckboxSetting("Filter item stack carrier packets",
			"Inspect container, slot, and equipment packets that carry item stacks.",
			true);
	
	// ### ADDED ###
	private final CheckboxSetting quarantineBadChunks = new CheckboxSetting(
		"Quarantine bad chunks",
		"Keep cancelling follow-up packets for chunks that contained dangerous data.",
		true);
	
	// ### ADDED ###
	private final CheckboxSetting quarantineBadEntities = new CheckboxSetting(
		"Quarantine bad entities",
		"Keep cancelling follow-up packets for entities that carried dangerous data.",
		true);
	
	// ### ADDED ###
	private final CheckboxSetting failClosedOnExceptions = new CheckboxSetting(
		"Fail closed on NBT/read exceptions",
		"Cancel packets when NBT/item/chunk inspection throws an exception.",
		false);
	
	// ### ADDED ###
	private final CheckboxSetting debugChunkBlockEntityRecords =
		new CheckboxSetting("Debug chunk BE records",
			"Prints how many embedded block entity records were found in each chunk packet.",
			false);
	
	private int chunksBlocked;
	private int blockEntitiesBlocked;
	private int bundlesScanned;
	private int entityMetadataBlocked; // ### ADDED ###
	private int itemStacksBlocked; // ### ADDED ###
	private int quarantinedChunks; // ### ADDED ###
	private int quarantinedEntities; // ### ADDED ###
	private int quietBlockedPackets; // ### ADDED ###
	private long lastStatusMessageMs; // ### ADDED ###
	
	// ### ADDED ###
	private final Set<ChunkPos> bannedChunks = new HashSet<>();
	private final Set<Integer> bannedEntityIds = new HashSet<>();
	private final Map<Integer, ChunkPos> entityIdToChunk = new HashMap<>();
	
	public NbtFilterHack()
	{
		super("NBTFilter");
		// No category = unlisted, accessible via search/navigator
		addSetting(maxChunkSize);
		addSetting(filterBlockEntities);
		addSetting(filterBundles);
		addSetting(maxBlockEntityNbtSizeKb); // ### ADDED ###
		addSetting(maxItemStackNbtSizeKb); // ### ADDED ###
		addSetting(maxSuspiciousPacketSizeMb); // ### ADDED ###
		addSetting(filterEntityMetadata); // ### ADDED ###
		addSetting(filterItemStackCarrierPackets); // ### ADDED ###
		addSetting(quarantineBadChunks); // ### ADDED ###
		addSetting(quarantineBadEntities); // ### ADDED ###
		addSetting(failClosedOnExceptions); // ### ADDED ###
		addSetting(debugChunkBlockEntityRecords); // ### ADDED ###
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		chunksBlocked = 0;
		blockEntitiesBlocked = 0;
		bundlesScanned = 0;
		entityMetadataBlocked = 0; // ### ADDED ###
		itemStacksBlocked = 0; // ### ADDED ###
		quarantinedChunks = 0; // ### ADDED ###
		quarantinedEntities = 0; // ### ADDED ###
		quietBlockedPackets = 0; // ### ADDED ###
		lastStatusMessageMs = 0; // ### ADDED ###
		bannedChunks.clear(); // ### ADDED ###
		bannedEntityIds.clear(); // ### ADDED ###
		entityIdToChunk.clear(); // ### ADDED ###
		ChatUtils
			.message("NBTFilter enabled - watching for dangerous packets.");
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		if(chunksBlocked > 0 || blockEntitiesBlocked > 0
			|| entityMetadataBlocked > 0 || itemStacksBlocked > 0) // ###
																	// MODIFIED
																	// ###
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
	public void onUpdate()
	{
		// nothing needed on tick
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		// ### MODIFIED ### Dangerous login/loading packets must be inspected
		// before MC.player exists. Only chat/status output is player-gated.
		Packet<?> packet = event.getPacket();
		
		InspectionResult result = inspectPacket(packet);
		if(!result.dangerous())
			return;
		
		event.cancel();
		recordBlocked(result);
	}
	
	// ### ADDED ###
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
			
			if(filterBundles.isChecked()
				&& packet instanceof ClientboundBundlePacket bundle)
				return checkBundlePacket(bundle);
			
			InspectionResult quarantineHit = checkQuarantinePacket(packet);
			if(quarantineHit.dangerous())
				return quarantineHit;
			
			updateTrackedEntityChunk(packet);
			InspectionResult trackedQuarantineHit =
				checkTrackedEntityQuarantine(packet); // ### ADDED ###
			if(trackedQuarantineHit.dangerous()) // ### ADDED ###
				return trackedQuarantineHit; // ### ADDED ###
				
			return inspectDangerousPacket(packet); // ### MODIFIED ###
			
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
		return inspectDangerousPacket(packet).dangerous(); // ### MODIFIED ###
	}
	
	// ### ADDED ###
	private InspectionResult inspectDangerousPacket(Packet<?> packet)
	{
		// Chunk packet: check data size and embedded block entities
		if(packet instanceof ClientboundLevelChunkWithLightPacket chunkPkt)
			return checkChunkPacket(chunkPkt);
		
		// Block entity data: check NBT size
		if(filterBlockEntities.isChecked()
			&& packet instanceof ClientboundBlockEntityDataPacket bePkt)
			return checkBlockEntityPacket(bePkt);
		
		if(filterEntityMetadata.isChecked()
			&& packet instanceof ClientboundSetEntityDataPacket metadata)
			return checkEntityMetadataPacket(metadata);
		
		if(filterItemStackCarrierPackets.isChecked())
		{
			if(packet instanceof ClientboundContainerSetContentPacket content)
				return checkItemStackCarrierPacket(content);
			
			if(packet instanceof ClientboundContainerSetSlotPacket slot)
				return checkItemStackCarrierPacket(slot);
			
			if(packet instanceof ClientboundSetEquipmentPacket equipment)
				return checkEquipmentPacket(equipment);
		}
		
		return InspectionResult.safe();
	}
	
	private InspectionResult checkChunkPacket(
		ClientboundLevelChunkWithLightPacket packet) // ### MODIFIED ###
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
			
			if(sizeMB > maxChunkSize.getValue()
				|| sizeMB > maxSuspiciousPacketSizeMb.getValue()) // ###
																	// MODIFIED
																	// ###
			{
				quarantineChunk(chunkPos);
				return InspectionResult.dangerous(PacketKind.CHUNK,
					"chunk payload "
						+ String.format(Locale.ROOT, "%.2f", sizeMB) + " MB",
					sizeBytes, chunkPos, null);
			}
			
			InspectionResult beResult =
				checkChunkBlockEntityRecords(packet.getChunkData(), chunkPos);
			if(beResult.dangerous())
				return beResult;
			
		}catch(Throwable t)
		{
			// ### MODIFIED ### Configurable emergency fail-closed behavior.
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
	
	// ### ADDED ###
	private InspectionResult checkChunkBlockEntityRecords(Object chunkData,
		ChunkPos fallbackChunk)
	{
		Object records = getChunkBlockEntityRecords(chunkData); // ### MODIFIED
																// ###
		if(!(records instanceof Iterable<?> iterable))
		{
			debugChunkBlockEntityRecordCount(fallbackChunk, -1); // ### ADDED
																	// ###
			return InspectionResult.safe();
		}
		
		int recordsFound = 0; // ### ADDED ###
		for(Object record : iterable)
		{
			recordsFound++; // ### ADDED ###
			CompoundTag tag = findCompoundTag(record);
			if(tag == null)
				continue;
			
			InspectionResult result = checkTagAgainstLimits(tag,
				PacketKind.CHUNK, fallbackChunk, null);
			if(result.dangerous())
			{
				quarantineChunk(fallbackChunk);
				return result.withChunk(fallbackChunk);
			}
		}
		
		debugChunkBlockEntityRecordCount(fallbackChunk, recordsFound); // ###
																		// ADDED
																		// ###
		return InspectionResult.safe();
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private void debugChunkBlockEntityRecordCount(ChunkPos chunk, int count)
	{
		if(!debugChunkBlockEntityRecords.isChecked() || MC.player == null)
			return;
		
		String countText = count < 0 ? "unavailable" : String.valueOf(count);
		ChatUtils.message("\u00a77[NBTFilter] chunk [" + chunkX(chunk) + ", "
			+ chunkZ(chunk) + "] block entity records found: " + countText);
	}
	
	private InspectionResult checkBlockEntityPacket(
		ClientboundBlockEntityDataPacket packet) // ### MODIFIED ###
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
			quarantineChunk(chunkPos);
			return result;
		}
		
		return InspectionResult.safe();
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private InspectionResult checkItemStackCarrierPacket(Object packet)
	{
		for(ItemStack stack : findItemStacks(packet))
		{
			InspectionResult result = checkItemStackAgainstLimits(stack,
				PacketKind.ITEM_STACK, null, null);
			if(result.dangerous())
				return result;
		}
		
		return InspectionResult.safe();
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private InspectionResult checkTrackedEntityQuarantine(Packet<?> packet)
	{
		int entityId = readPacketEntityId(packet);
		if(entityId >= 0 && isBannedEntity(entityId))
			return InspectionResult.dangerous(PacketKind.ENTITY_METADATA,
				"entity spawned or moved inside quarantined chunk", -1,
				entityIdToChunk.get(entityId), entityId);
		
		return InspectionResult.safe();
	}
	
	// ### ADDED ###
	private ChunkPos getAffectedChunk(Packet<?> packet)
	{
		if(packet instanceof ClientboundLevelChunkWithLightPacket p)
			return new ChunkPos(p.getX(), p.getZ());
		
		if(packet instanceof ClientboundBlockEntityDataPacket p)
			return ChunkPos.containing(p.getPos());
		
		if(packet instanceof ClientboundBlockUpdatePacket p)
			return ChunkPos.containing(p.getPos());
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket p)
			return getSectionUpdateChunk(p); // ### MODIFIED ###
			
		if(packet instanceof ClientboundLightUpdatePacket)
		{
			Integer x = readIntByMethods(packet, "getX", "x");
			Integer z = readIntByMethods(packet, "getZ", "z");
			if(x != null && z != null)
				return new ChunkPos(x, z);
		}
		
		return null;
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private void updateTrackedEntityChunk(Packet<?> packet)
	{
		if(packet instanceof ClientboundAddEntityPacket add)
		{
			int id = add.getId();
			ChunkPos chunk = chunkFromCoordinates(add.getX(), add.getZ());
			entityIdToChunk.put(id, chunk);
			if(isBannedChunk(chunk) && quarantineBadEntities.isChecked())
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
		if(isBannedChunk(newChunk) && quarantineBadEntities.isChecked())
			quarantineEntity(entityId);
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private ChunkPos chunkFromCoordinates(double x, double z)
	{
		return new ChunkPos(((int)Math.floor(x)) >> 4,
			((int)Math.floor(z)) >> 4);
	}
	
	// ### ADDED ###
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
				} // ### MODIFIED ###
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
	
	// ### ADDED ###
	private String formatResultDetails(InspectionResult result)
	{
		StringBuilder details = new StringBuilder();
		if(result.chunk() != null)
			details.append(" chunk [").append(chunkX(result.chunk()))
				.append(", ").append(chunkZ(result.chunk())).append("]"); // ###
																			// MODIFIED
																			// ###
		if(result.entityId() != null)
			details.append(" entity #").append(result.entityId());
		if(result.estimatedBytes() >= 0)
			details.append(" size ")
				.append(formatBytes(result.estimatedBytes()));
		return details.toString();
	}
	
	// ### ADDED ###
	private int chunkX(ChunkPos chunk)
	{
		return chunk == null ? 0 : chunk.x();
	}
	
	// ### ADDED ###
	private int chunkZ(ChunkPos chunk)
	{
		return chunk == null ? 0 : chunk.z();
	}
	
	// ### ADDED ###
	private boolean isBannedChunk(ChunkPos chunk)
	{
		return quarantineBadChunks.isChecked() && chunk != null
			&& bannedChunks.contains(chunk);
	}
	
	// ### ADDED ###
	private boolean isBannedEntity(int entityId)
	{
		return quarantineBadEntities.isChecked() && entityId >= 0
			&& bannedEntityIds.contains(entityId);
	}
	
	// ### ADDED ###
	private void quarantineChunk(ChunkPos chunk)
	{
		if(!quarantineBadChunks.isChecked() || chunk == null)
			return;
		
		if(bannedChunks.add(chunk))
			quarantinedChunks++;
	}
	
	// ### ADDED ###
	private void quarantineEntity(int entityId)
	{
		if(!quarantineBadEntities.isChecked() || entityId < 0)
			return;
		
		if(bannedEntityIds.add(entityId))
			quarantinedEntities++;
	}
	
	// ### ADDED ###
	private InspectionResult checkTagAgainstLimits(CompoundTag tag,
		PacketKind kind, ChunkPos chunk, Integer entityId)
	{
		try
		{
			if(isDangerousTag(tag))
				return InspectionResult.dangerous(kind, "dangerous NBT tag",
					tag.sizeInBytes(), chunk, entityId);
			
		}catch(Throwable t)
		{
			if(failClosedOnExceptions.isChecked())
				return InspectionResult.dangerous(kind,
					"NBT inspection exception: " + t.getClass().getSimpleName(),
					-1, chunk, entityId);
		}
		
		return InspectionResult.safe();
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private boolean isDangerousTag(CompoundTag tag)
	{
		if(tag == null)
			return false;
		
		if(tag.sizeInBytes() > maxBlockEntityBytes()
			|| tag.sizeInBytes() > maxSuspiciousPacketBytes())
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
	
	// ### ADDED ###
	private boolean isDangerousItemStack(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		long size = estimateItemStackNbtSize(stack);
		return size > maxItemStackBytes() || size > maxSuspiciousPacketBytes();
	}
	
	// ### ADDED ###
	private long estimateItemStackNbtSize(ItemStack stack)
	{
		if(stack == null || stack.isEmpty()) // ### MODIFIED ###
			return 0;
		
		if(MC.level == null) // ### ADDED ###
			return failClosedOnExceptions.isChecked() ? maxItemStackBytes() + 1
				: 0;
		
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
			Unpooled.buffer(), MC.level.registryAccess());
		try
		{
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
			return buf.writerIndex();
			
		}catch(Throwable t)
		{
			return failClosedOnExceptions.isChecked() ? maxItemStackBytes() + 1
				: 0;
			
		}finally
		{
			buf.release();
		}
	}
	
	// ### ADDED ###
	private long estimateObjectNbtOrComponentSize(Object value)
	{
		if(value == null)
			return 0;
		
		if(value instanceof ItemStack stack)
			return estimateItemStackNbtSize(stack);
		
		if(value instanceof CompoundTag tag)
			return tag.sizeInBytes();
		
		if(value instanceof Tag tag) // ### MODIFIED ###
			return estimateTagSize(tag);
		
		if(value instanceof Collection<?> collection)
		{
			long total = 0;
			for(Object entry : collection)
				total += estimateObjectNbtOrComponentSize(entry);
			return total;
		}
		
		return failClosedOnExceptions.isChecked()
			? maxSuspiciousPacketBytes() + 1 : 0; // ### MODIFIED ###
	}
	
	// ### ADDED ###
	private long estimateTagSize(Tag tag)
	{
		if(tag == null)
			return 0;
		
		Object size = invokeNoArg(tag, "sizeInBytes");
		if(size instanceof Number number)
			return number.longValue();
		
		return failClosedOnExceptions.isChecked()
			? maxSuspiciousPacketBytes() + 1 : 0;
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private Object extractDataValue(Object dataValue)
	{
		Object value = invokeNoArg(dataValue, "value");
		return value == null ? dataValue : value;
	}
	
	// ### ADDED ###
	private Collection<ItemStack> findItemStacks(Object obj) // ### MODIFIED ###
	{
		Set<ItemStack> result =
			Collections.newSetFromMap(new IdentityHashMap<>()); // ### MODIFIED
																// ###
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>()); // ###
																				// MODIFIED
																				// ###
		collectItemStacks(obj, result, seen, 0);
		return result;
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
		
		return null;
	}
	
	// ### ADDED ###
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
			{
				// fall through to try next method
			}
		}
		
		return Collections.emptySet();
	}
	
	// ### ADDED ###
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
			return readPacketEntityId(move); // ### MODIFIED ###
		if(packet instanceof ClientboundSetEntityMotionPacket motion)
			return readPacketEntityId(motion); // ### MODIFIED ###
		if(packet instanceof ClientboundRotateHeadPacket rotate)
			return readPacketEntityId(rotate); // ### MODIFIED ###
		if(packet instanceof ClientboundEntityEventPacket event)
			return readPacketEntityId(event); // ### MODIFIED ###
		if(packet instanceof ClientboundSetEquipmentPacket equipment)
			return readPacketEntityIdReflective(equipment); // ### ADDED ###
			
		return readPacketEntityIdReflective(packet); // ### MODIFIED ###
	}
	
	// ### ADDED ###
	private int readPacketEntityId(Object packet)
	{
		return readPacketEntityIdReflective(packet); // ### MODIFIED ###
	}
	
	// ### ADDED ###
	private int readPacketEntityIdReflective(Object packet)
	{
		Integer id =
			readIntByMethods(packet, "id", "getId", "entityId", "getEntityId");
		return id == null ? -1 : id;
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
	private long maxBlockEntityBytes()
	{
		return maxBlockEntityNbtSizeKb.getValueI() * 1024L;
	}
	
	// ### ADDED ###
	private long maxItemStackBytes()
	{
		return maxItemStackNbtSizeKb.getValueI() * 1024L;
	}
	
	// ### ADDED ###
	private long maxSuspiciousPacketBytes()
	{
		return (long)(maxSuspiciousPacketSizeMb.getValue() * 1024D * 1024D);
	}
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
	
	// ### ADDED ###
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
}
