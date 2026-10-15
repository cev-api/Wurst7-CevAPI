/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.util.chunk.ChunkUtils;

/**
 * Cheap client-thread copies; all encoding/hashing is deferred to the session
 * worker.
 */
public final class ForensicSnapshots
{
	private ForensicSnapshots()
	{}
	
	public record State(Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts,
		HolderLookup.Provider registries, String dimension, long clientTick,
		long observedNs, String timestamp, String thread)
	{
		public long estimatedBytes()
		{
			long bytes = stacks.size() * 512L;
			for(ItemStack stack : stacks.values())
			{
				var writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
				if(writable != null)
					for(var page : writable.pages())
						bytes += page.raw().length() * 3L;
				var written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
				if(written != null)
					bytes += written.pages().size() * 32768L;
			}
			return Math.max(bytes, 1);
		}
	}
	
	private static State state(Minecraft mc, long tick,
		Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		return new State(stacks, contexts,
			mc.level == null ? null : mc.level.registryAccess(),
			mc.level == null ? "disconnected"
				: mc.level.dimension().identifier().toString(),
			tick, System.nanoTime(), Instant.now().toString(),
			Thread.currentThread().getName());
	}
	
	public static State world(Minecraft mc, long tick)
	{
		Map<String, ItemStack> stacks = new LinkedHashMap<>();
		Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
		if(mc.player == null || mc.level == null)
			return state(mc, tick, stacks, contexts);
		menu(mc, mc.player.inventoryMenu, stacks, contexts);
		if(mc.player.containerMenu != mc.player.inventoryMenu)
			menu(mc, mc.player.containerMenu, stacks, contexts);
		for(EquipmentSlot slot : EquipmentSlot.values())
		{
			int raw = switch(slot)
			{
				case HEAD -> 5;
				case CHEST -> 6;
				case LEGS -> 7;
				case FEET -> 8;
				case OFFHAND -> 45;
				case MAINHAND -> 36
					+ mc.player.getInventory().getSelectedSlot();
				default -> -1;
			};
			if(raw < 0)
				put("equipment:" + slot.getName(),
					mc.player.getItemBySlot(slot),
					Map.of("logicalSlot", "equipment." + slot.getName()),
					stacks, contexts);
		}
		ChunkUtils.getLoadedBlockEntities().forEach(block -> {
			if(block instanceof Container container)
				storage(block.getBlockPos(), block.getBlockState(), container,
					stacks, contexts);
		});
		for(var entity : mc.level.entitiesForRendering())
		{
			if(entity instanceof net.minecraft.world.entity.LivingEntity living
				&& entity != mc.player)
				for(EquipmentSlot equipment : EquipmentSlot.values())
					entityEquipment(mc, living, equipment, stacks, contexts);
			if(entity instanceof ItemEntity item && item.isAlive())
			{
				var pos = item.position();
				put("entity:" + item.getUUID(), item.getItem(),
					Map.of("entityId", item.getId(), "entityUuid",
						item.getUUID().toString(), "position",
						Map.of("x", pos.x, "y", pos.y, "z", pos.z)),
					stacks, contexts);
			}
		}
		return state(mc, tick, stacks, contexts);
	}
	
	public static State block(Minecraft mc, net.minecraft.core.BlockPos pos,
		long tick)
	{
		Map<String, ItemStack> stacks = new LinkedHashMap<>();
		Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
		if(mc.level != null
			&& mc.level.getBlockEntity(pos) instanceof Container container)
			storage(pos, mc.level.getBlockState(pos), container, stacks,
				contexts);
		return state(mc, tick, stacks, contexts);
	}
	
	public static State affected(Minecraft mc, Packet<?> packet, long tick)
	{
		Map<String, ItemStack> stacks = new LinkedHashMap<>();
		Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
		if(mc.player == null || mc.level == null)
			return state(mc, tick, stacks, contexts);
		if(packet instanceof ClientboundContainerSetSlotPacket p)
		{
			if(p.getContainerId() == -2)
				playerSlot(mc, p.getSlot(), stacks, contexts);
			else
			{
				AbstractContainerMenu menu = menuFor(mc, p.getContainerId());
				if(menu != null)
					if(p.getContainerId() == -1 || p.getSlot() == -1)
						carried(menu, stacks, contexts);
					else
						slot(mc, menu, p.getSlot(), stacks, contexts);
			}
		}
		if(packet instanceof ClientboundSetPlayerInventoryPacket p)
			playerSlot(mc, p.slot(), stacks, contexts);
		if(packet instanceof ClientboundContainerSetContentPacket p)
		{
			AbstractContainerMenu menu = menuFor(mc, p.containerId());
			if(menu != null)
				menu(mc, menu, stacks, contexts);
		}
		if(packet instanceof ClientboundBlockEntityDataPacket p && mc.level
			.getBlockEntity(p.getPos()) instanceof Container container)
			storage(p.getPos(), mc.level.getBlockState(p.getPos()), container,
				stacks, contexts);
		if(packet instanceof ClientboundSetEquipmentPacket p
			&& mc.level.getEntity(p
				.getEntity()) instanceof net.minecraft.world.entity.LivingEntity entity)
			for(var pair : p.getSlots())
				entityEquipment(mc, entity, pair.getFirst(), stacks, contexts);
		if(packet instanceof ClientboundSetEntityDataPacket p
			&& mc.level.getEntity(p.id()) instanceof ItemEntity item)
			put("entity:" + item.getUUID(), item.getItem(),
				Map.of("entityId", item.getId(), "entityUuid",
					item.getUUID().toString(), "position", Map.of("x",
						item.getX(), "y", item.getY(), "z", item.getZ())),
				stacks, contexts);
		return state(mc, tick, stacks, contexts);
	}
	
	private static void entityEquipment(Minecraft mc,
		net.minecraft.world.entity.LivingEntity entity, EquipmentSlot equipment,
		Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		if(entity == mc.player)
		{
			int raw = switch(equipment)
			{
				case HEAD -> 5;
				case CHEST -> 6;
				case LEGS -> 7;
				case FEET -> 8;
				case OFFHAND -> 45;
				case MAINHAND -> 36
					+ mc.player.getInventory().getSelectedSlot();
				default -> -1;
			};
			if(raw >= 0)
			{
				slot(mc, mc.player.inventoryMenu, raw, stacks, contexts);
				return;
			}
			put("equipment:" + equipment.getName(),
				entity.getItemBySlot(equipment), Map.of("entityId",
					entity.getId(), "logicalSlot", equipment.getName()),
				stacks, contexts);
			return;
		}
		put("entity_equipment:" + entity.getUUID() + ":" + equipment.getName(),
			entity.getItemBySlot(equipment),
			Map.of("entityId", entity.getId(), "entityUuid",
				entity.getUUID().toString(), "logicalSlot",
				equipment.getName()),
			stacks, contexts);
	}
	
	private static AbstractContainerMenu menuFor(Minecraft mc, int id)
	{
		if(id == 0)
			return mc.player.inventoryMenu;
		if(id == -1)
			return mc.player.containerMenu;
		return id == mc.player.containerMenu.containerId
			? mc.player.containerMenu : null;
	}
	
	private static void playerSlot(Minecraft mc, int backing,
		Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		for(var slot : mc.player.inventoryMenu.slots)
			if(slot.container == mc.player.getInventory()
				&& slot.getContainerSlot() == backing)
				slot(mc, mc.player.inventoryMenu, slot.index, stacks, contexts);
	}
	
	private static void menu(Minecraft mc, AbstractContainerMenu menu,
		Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		for(int i = 0; i < menu.slots.size(); i++)
			slot(mc, menu, i, stacks, contexts);
		if(menu == mc.player.containerMenu)
			carried(menu, stacks, contexts);
	}
	
	private static void slot(Minecraft mc, AbstractContainerMenu menu, int raw,
		Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		if(raw < 0 || raw >= menu.slots.size())
			return;
		var slot = menu.getSlot(raw);
		String key = (menu == mc.player.inventoryMenu ? "player_inventory:"
			: "opened_container:") + menu.containerId + ":slot:" + raw;
		if(slot.container == mc.player.getInventory())
			for(var playerSlot : mc.player.inventoryMenu.slots)
				if(playerSlot.container == slot.container
					&& playerSlot.getContainerSlot() == slot.getContainerSlot())
				{
					key = "player_inventory:0:slot:" + playerSlot.index;
					break;
				}
		put(key, slot.getItem(),
			Map.of("containerId", menu.containerId, "rawSlot", raw, "stateId",
				menu.getStateId(), "logicalSlot",
				InventorySlotMapping.describe(menu, slot, mc),
				"backingContainerSlot", slot.getContainerSlot()),
			stacks, contexts);
	}
	
	private static void carried(AbstractContainerMenu menu,
		Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		put("carried_item", menu.getCarried(),
			Map.of("containerId", -1, "rawSlot", -1, "logicalSlot",
				"carried_item", "stateId", menu.getStateId()),
			stacks, contexts);
	}
	
	private static void storage(net.minecraft.core.BlockPos pos,
		net.minecraft.world.level.block.state.BlockState state,
		Container container, Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		for(int i = 0; i < container.getContainerSize(); i++)
			put("block_entity:" + pos.toShortString() + ":slot:" + i,
				container.getItem(i),
				Map.of("rawSlot", i, "logicalSlot",
					"block_entity_container[" + i + "]", "blockPosition",
					pos.toShortString(), "blockId",
					BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
					"blockState",
					RegistryDecoder.decodeBlockStateDetailed(state),
					"containerIdentity", container.getClass().getName()),
				stacks, contexts);
	}
	
	private static void put(String key, ItemStack stack,
		Map<String, Object> context, Map<String, ItemStack> stacks,
		Map<String, Map<String, Object>> contexts)
	{
		if(stacks.putIfAbsent(key, stack.copy()) == null)
		{
			Map<String, Object> fields = new LinkedHashMap<>(context);
			fields.put("slotKey", key);
			contexts.put(key, fields);
		}
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static net.minecraft.network.syncher.SynchedEntityData.DataValue<?> copyDataValue(
		net.minecraft.network.syncher.SynchedEntityData.DataValue value)
	{
		return new net.minecraft.network.syncher.SynchedEntityData.DataValue(
			value.id(), value.serializer(),
			value.serializer().copy(value.value()));
	}
	
	public static long estimatedPacketBytes(Packet<?> packet)
	{
		java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
		if(packet instanceof ClientboundContainerSetSlotPacket p)
			stacks.add(p.getItem());
		else if(packet instanceof ClientboundContainerSetContentPacket p)
		{
			stacks.addAll(p.items());
			stacks.add(p.carriedItem());
		}else if(packet instanceof ClientboundSetPlayerInventoryPacket p)
			stacks.add(p.contents());
		else if(packet instanceof net.minecraft.network.protocol.game.ServerboundEditBookPacket p)
			return 1024L + p.pages().stream()
				.mapToLong(page -> page.length() * 3L).sum();
		else
			return 65536;
		return new State(Map.of(), Map.of(), null, "", 0, 0, "", "")
			.estimatedBytes() + stacks.stream().mapToLong(stack -> {
				var book = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
				var written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
				return 512L
					+ (book == null ? 0
						: book.pages().stream()
							.mapToLong(page -> page.raw().length() * 3L).sum())
					+ (written == null ? 0 : written.pages().size() * 32768L);
			}).sum();
	}
	
	public static Map<String, Object> freezeFields(Map<String, Object> fields)
	{
		Map<String, Object> copy = new LinkedHashMap<>();
		fields.forEach((key, value) -> copy.put(key, freezeValue(value)));
		return java.util.Collections.unmodifiableMap(copy);
	}
	
	private static Object freezeValue(Object value)
	{
		if(value instanceof Map<?, ?> map)
		{
			Map<String, Object> copy = new LinkedHashMap<>();
			map.forEach((key, child) -> copy.put(String.valueOf(key),
				freezeValue(child)));
			return java.util.Collections.unmodifiableMap(copy);
		}
		if(value instanceof java.util.Collection<?> list)
		{
			List<Object> copy = new ArrayList<>();
			for(Object child : list)
				copy.add(freezeValue(child));
			return java.util.Collections.unmodifiableList(copy);
		}
		if(value instanceof com.google.gson.JsonElement json)
			return json.deepCopy();
		return value;
	}
	
	public static Packet<?> freezePacket(Packet<?> packet)
	{
		if(packet instanceof ClientboundContainerSetSlotPacket p)
			return new ClientboundContainerSetSlotPacket(p.getContainerId(),
				p.getStateId(), p.getSlot(), p.getItem().copy());
		if(packet instanceof ClientboundContainerSetContentPacket p)
			return new ClientboundContainerSetContentPacket(p.containerId(),
				p.stateId(), p.items().stream().map(ItemStack::copy).toList(),
				p.carriedItem().copy());
		if(packet instanceof ClientboundSetPlayerInventoryPacket p)
			return new ClientboundSetPlayerInventoryPacket(p.slot(),
				p.contents().copy());
		if(packet instanceof ClientboundSetEntityDataPacket p)
			return new ClientboundSetEntityDataPacket(p.id(), p.packedItems()
				.stream().map(ForensicSnapshots::copyDataValue).toList());
		if(packet instanceof ClientboundSetEquipmentPacket p)
			return new ClientboundSetEquipmentPacket(p.getEntity(),
				p.getSlots().stream()
					.map(pair -> com.mojang.datafixers.util.Pair
						.of(pair.getFirst(), pair.getSecond().copy()))
					.toList());
		return packet;
	}
}
