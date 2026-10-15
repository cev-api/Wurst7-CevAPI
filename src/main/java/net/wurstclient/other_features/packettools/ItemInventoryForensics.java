/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Collection;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.util.chunk.ChunkUtils;

/** Bounded event capture and passive inventory shadow model for PacketTools. */
public final class ItemInventoryForensics
{
	private static final int DEFAULT_MAX_PENDING_EVENTS = 4096;
	private static final Gson GSON =
		new GsonBuilder().disableHtmlEscaping().create();
	
	private final ArrayDeque<String> pending = new ArrayDeque<>();
	private final Map<String, ItemStackForensics.Snapshot> previousSlots =
		new HashMap<>();
	private final Map<String, Map<String, Object>> previousSlotContexts =
		new HashMap<>();
	private final Map<UUID, NearbyItemState> nearbyItems = new HashMap<>();
	private final Map<String, PendingTransaction> pendingTransactions =
		new LinkedHashMap<>();
	private String sessionId = UUID.randomUUID().toString();
	private String startedAt = Instant.now().toString();
	private long clientTick;
	private long eventCount;
	private final Map<String, Long> eventTypeCounts = new LinkedHashMap<>();
	private final Map<String, Long> diagnosticCounts = new LinkedHashMap<>();
	private final ArrayDeque<Map<String, Object>> recentHistory =
		new ArrayDeque<>();
	private long capturedBytes;
	private volatile long decodeErrorCount;
	
	public long getDiagnosticErrorCount()
	{
		return decodeErrorCount;
	}
	
	private long droppedEvents;
	private long totalDroppedEvents;
	private String lastMenuKey;
	private int maxPendingEvents = DEFAULT_MAX_PENDING_EVENTS;
	private long maxCaptureRecords = 50000;
	private long maxCaptureBytes = 256L * 1024 * 1024;
	private long correlationTimeoutMs = 2000;
	private boolean wasConnected;
	// ### ADDED ### Slot-scoped packet candidates; bounded and time-expiring.
	private final ArrayDeque<PendingSlotUpdate> pendingSlotUpdates =
		new ArrayDeque<>();
	private final Map<Packet<?>, String> receivedPacketEventIds =
		new IdentityHashMap<>();
	private final Map<String, String> postApplicationFingerprints =
		new LinkedHashMap<>();
	private final Map<String, String> preApplicationFingerprints =
		new LinkedHashMap<>();
	private final Set<String> emittedPayloadHashes = new LinkedHashSet<>();
	private java.util.function.Consumer<String> eventSink;
	private java.util.function.Supplier<Map<String, Object>> captureHealth;
	
	public void setCaptureHealth(
		java.util.function.Supplier<Map<String, Object>> health)
	{
		captureHealth = health;
	}
	
	private Map<String, Object> observationContext = Map.of();
	private final Map<String, ItemStack> cachedStacks = new HashMap<>();
	private net.minecraft.core.HolderLookup.Provider registryAccess;
	private String observedDimension;
	private long pendingBytes;
	private final Map<String, List<Map<String, Object>>> transactionTimelines =
		new LinkedHashMap<>();
	private long timelineOmissions;
	private final BookBotPerformanceAnalysis bookBotPerformance =
		new BookBotPerformanceAnalysis();
	
	private boolean captureIsComplete()
	{
		return diagnosticCounts.getOrDefault("decodeErrors", 0L) == 0
			&& totalDroppedEvents == 0 && droppedEvents == 0
			&& (captureHealth == null || Boolean.TRUE
				.equals(captureHealth.get().get("captureComplete")));
	}
	
	private final Map<String, Map<String, Object>> lastSlotEvents =
		new HashMap<>();
	private final Map<String, PendingTickState> pendingTickStates =
		new LinkedHashMap<>();
	
	public void setEventSink(java.util.function.Consumer<String> sink)
	{
		eventSink = sink;
	}
	
	public void recordObservation(Map<String, Object> fields)
	{
		Map<String, Object> event = baseEvent(String.valueOf(
			fields.getOrDefault("eventType", "diagnostic_observation")));
		event.putAll(fields);
		append(event);
	}
	
	public void withObservation(Map<String, Object> context, Runnable task)
	{
		observationContext = context;
		try
		{
			task.run();
		}finally
		{
			observationContext = Map.of();
		}
	}
	
	private ItemStackForensics.Snapshot captureStack(ItemStack stack,
		boolean components, boolean fullContent, int maxBytes)
	{
		return ItemStackForensics.capture(stack, components, fullContent,
			maxBytes, registryAccess);
	}
	
	/** Called only by the session worker, using client-thread copies. */
	public void reconcileCaptured(ForensicSnapshots.State state,
		boolean completeWorld, boolean fullContent, int maxBytes,
		Map<String, Object> timing)
	{
		registryAccess = state.registries();
		if(observedDimension != null
			&& !observedDimension.equals(state.dimension()))
		{
			previousSlots.clear();
			previousSlotContexts.clear();
			cachedStacks.clear();
			pendingTickStates.clear();
			lastSlotEvents.clear();
			recordObservation(Map.of("eventType", "world_scope_changed",
				"dimension", state.dimension()));
		}
		observedDimension = state.dimension();
		clientTick = state.clientTick();
		Map<String, Object> context = new LinkedHashMap<>(timing);
		context.put("timestamp", state.timestamp());
		context.put("monotonicNs", state.observedNs());
		context.put("thread", state.thread());
		context.put("dimension", state.dimension());
		context.put("clientTick", state.clientTick());
		context.putIfAbsent("captureStage", "CLIENT_TICK_RECONCILIATION");
		withObservation(context, () -> {
			Map<String, ItemStackForensics.Snapshot> now =
				new LinkedHashMap<>();
			Map<String, ItemStackForensics.Snapshot> before =
				new LinkedHashMap<>();
			if(completeWorld)
				before.putAll(previousSlots);
			state.stacks().forEach((key, stack) -> {
				ItemStackForensics.Snapshot old = previousSlots.get(key);
				if(old != null)
					before.put(key, old);
				ItemStack cached = cachedStacks.get(key);
				now.put(key,
					cached != null && ItemStack.matches(cached, stack)
						&& old != null ? old
							: captureStack(stack, true, fullContent, maxBytes));
				cachedStacks.put(key, stack);
			});
			captureCopiedEntityLifecycle(state, now, completeWorld);
			compareSlots(before, now, state.contexts(), false);
			if(completeWorld)
			{
				previousSlots.clear();
				previousSlotContexts.clear();
				cachedStacks.keySet().retainAll(now.keySet());
			}
			previousSlots.putAll(now);
			previousSlotContexts.putAll(state.contexts());
			if("STATE_APPLIED".equals(timing.get("captureStage")))
			{
				String packetId = String.valueOf(timing.get("logicalPacketId"));
				Map<String, String> fingerprints = new LinkedHashMap<>();
				now.forEach((key, snapshot) -> fingerprints.put(key,
					snapshot.fingerprint()));
				pendingTickStates.put(packetId, new PendingTickState(
					state.observedNs(), fingerprints, timing));
				while(pendingTickStates.size() > 1024)
					pendingTickStates
						.remove(pendingTickStates.keySet().iterator().next());
				Map<String, Object> applied =
					baseEvent("inventory_packet_application");
				applied.put("slotFingerprintsAfterHandler", fingerprints);
				applied.put("postApplicationStateAvailable", !now.isEmpty());
				append(applied);
			}
			if(completeWorld)
			{
				pendingTickStates.forEach((id, applied) -> {
					Map<String, Object> event =
						baseEvent("packet_next_tick_state");
					event.putAll(applied.timing);
					event.put("eventType", "packet_next_tick_state");
					event.put("nextClientTickNs", state.observedNs());
					event.put("timestamp", state.timestamp());
					event.put("monotonicNs", state.observedNs());
					event.put("clientTick", state.clientTick());
					event.put("thread", state.thread());
					event.put("captureStage", "NEXT_CLIENT_TICK");
					event.put("nextTickDelayNs",
						state.observedNs() - applied.appliedNs);
					Map<String, Object> persistence = new LinkedHashMap<>();
					applied.fingerprints
						.forEach((key, hash) -> persistence.put(key,
							now.containsKey(key)
								? hash.equals(now.get(key).fingerprint())
								: "UNKNOWN_SCOPE_NOT_OBSERVED"));
					event.put("statePersistsBySlot", persistence);
					append(event);
				});
				pendingTickStates.clear();
			}
		});
	}
	
	private void captureCopiedEntityLifecycle(ForensicSnapshots.State state,
		Map<String, ItemStackForensics.Snapshot> snapshots,
		boolean completeWorld)
	{
		Set<UUID> observed = new LinkedHashSet<>();
		state.contexts().forEach((key, fields) -> {
			if(!key.startsWith("entity:")
				|| !(fields.get("position") instanceof Map<?, ?> pos))
				return;
			UUID uuid =
				UUID.fromString(String.valueOf(fields.get("entityUuid")));
			observed.add(uuid);
			var snapshot = snapshots.get(key);
			if(snapshot == null)
				return;
			Vec3 position = new Vec3(((Number)pos.get("x")).doubleValue(),
				((Number)pos.get("y")).doubleValue(),
				((Number)pos.get("z")).doubleValue());
			NearbyItemState previous = nearbyItems.get(uuid);
			boolean changed = previous != null
				&& !previous.fingerprint.equals(snapshot.fingerprint());
			boolean moved = previous != null
				&& position.distanceToSqr(previous.position) >= 1;
			if(previous != null && !changed && !moved)
				return;
			nearbyItems.put(uuid,
				new NearbyItemState(snapshot.fingerprint(), position));
			var event = baseEvent("item_entity_lifecycle");
			event.putAll(fields);
			event.put("action", previous == null ? "observed_loaded"
				: changed ? "item_changed" : "moved");
			event.put("item", snapshot.details());
			event.put("fingerprint", snapshot.fingerprint());
			append(event);
		});
		if(completeWorld)
			for(UUID uuid : new ArrayList<>(nearbyItems.keySet()))
				if(!observed.contains(uuid))
				{
					nearbyItems.remove(uuid);
					var event = baseEvent("item_entity_lifecycle");
					event.put("action", "no_longer_loaded_or_removed");
					event.put("entityUuid", uuid.toString());
					event.put("interpretation",
						"Entity left observed scope or was removed; cause unknown");
					append(event);
				}
	}
	
	public void releaseSessionResources()
	{
		bookBotPerformance.clear();
		previousSlots.clear();
		previousSlotContexts.clear();
		cachedStacks.clear();
		pendingTickStates.clear();
		pendingSlotUpdates.clear();
		pendingTransactions.clear();
		lastSlotEvents.clear();
		observedDimension = null;
		receivedPacketEventIds.clear();
		preApplicationFingerprints.clear();
		postApplicationFingerprints.clear();
		emittedPayloadHashes.clear();
		transactionTimelines.clear();
		recentHistory.clear();
		nearbyItems.clear();
		registryAccess = null;
	}
	
	private record PendingTickState(long appliedNs,
		Map<String, String> fingerprints, Map<String, Object> timing)
	{}
	
	/**
	 * Capture an observation stage. It never claims that a send was
	 * transmitted.
	 */
	public synchronized void recordPacket(Packet<?> packet, String direction,
		String stage, List<String> debugTrace, PacketDumper dumper,
		boolean fullContent, int maxContentBytes, boolean correlate,
		int correlationTimeoutMs)
	{
		if(packet == null
			|| !isRelevantPacket(packet.getClass().getSimpleName()))
			return;
		Map<String, Object> event = baseEvent("packet_observation");
		String eventId = String.valueOf(event.get("eventId"));
		event.put("direction", direction);
		event.put("captureStage", stage);
		event.put("packetClass", packet.getClass().getName());
		event.put("packetId", PacketCatalog.formatPacketName(packet));
		event.putIfAbsent("packetObjectCorrelationId",
			Integer.toHexString(System.identityHashCode(packet)));
		if(debugTrace != null && !debugTrace.isEmpty())
			event.put("debugTrace", List.copyOf(debugTrace));
		try
		{
			event.put("packetDetails",
				GSON.fromJson(dumper.dumpForensicsRecord(packet, fullContent,
					maxContentBytes), Object.class));
		}catch(RuntimeException e)
		{
			event.put("diagnosticError", e.getClass().getSimpleName());
		}
		if(direction.equals("C2S"))
		{
			String transactionId =
				"itemtx-" + event.get("packetObjectCorrelationId");
			event.putIfAbsent("transactionId", transactionId);
			if(correlate)
			{
				long now = System.nanoTime();
				purgeExpiredTransactions(now, correlationTimeoutMs);
				pendingTransactions.put(transactionId,
					new PendingTransaction(now));
				while(pendingTransactions.size() > 128)
					pendingTransactions
						.remove(pendingTransactions.keySet().iterator().next());
			}
		}else if(correlate)
		{
			long now = System.nanoTime();
			purgeExpiredTransactions(now, correlationTimeoutMs);
			if(!pendingTransactions.isEmpty())
			{
				List<String> candidates =
					List.copyOf(pendingTransactions.keySet());
				event.put("relatedTransactionCandidates", candidates);
				event.put("correlationConfidence",
					candidates.size() == 1 ? "LOW" : "AMBIGUOUS");
				event.put("correlationBasis",
					"nearest preceding client item packet by time only");
			}
		}
		if(direction.equals("S2C")
			&& !observationContext.containsKey("logicalPacketId"))
			captureSlotUpdateCandidates(packet, eventId, dumper, fullContent,
				maxContentBytes);
		append(event);
		if(direction.equals("S2C") && isSlotUpdate(packet)
			&& !observationContext.containsKey("logicalPacketId"))
		{
			receivedPacketEventIds.put(packet, eventId);
			while(receivedPacketEventIds.size() > 512)
				receivedPacketEventIds
					.remove(receivedPacketEventIds.keySet().iterator().next());
		}
	}
	
	/** Records inventory state immediately before the vanilla handler runs. */
	public synchronized void recordPreApplication(Packet<?> packet)
	{
		if(!isSlotUpdate(packet) || !Minecraft.getInstance().isSameThread())
			return;
		Minecraft mc = Minecraft.getInstance();
		int containerId = packet instanceof ClientboundContainerSetSlotPacket p
			? p.getContainerId() : numberAccessor(packet, "containerId");
		AbstractContainerMenu menu = getMenu(mc, containerId);
		String eventId = receivedPacketEventIds.get(packet);
		if(menu == null || eventId == null)
			return;
		for(int rawSlot : packetSlots(packet))
			if(rawSlot == -1)
				preApplicationFingerprints.put(
					postApplicationKey(eventId, containerId, rawSlot),
					captureStack(menu.getCarried(), true, false, 0)
						.fingerprint());
			else if(rawSlot >= 0 && rawSlot < menu.slots.size())
				preApplicationFingerprints.put(
					postApplicationKey(eventId, containerId, rawSlot),
					captureStack(menu.getSlot(rawSlot).getItem(), true, false,
						0).fingerprint());
	}
	
	/** Records inventory state after the vanilla packet handler returns. */
	public synchronized void recordPostApplication(Packet<?> packet)
	{
		if(!isSlotUpdate(packet))
			return;
		Minecraft mc = Minecraft.getInstance();
		Map<String, Object> event = baseEvent("inventory_packet_application");
		event.put("captureStage", "CLIENT_PACKET_HANDLER_TAIL");
		event.put("packetId", PacketCatalog.formatPacketName(packet));
		event.put("receivedPacketEventId",
			receivedPacketEventIds.remove(packet));
		int containerId = packet instanceof ClientboundContainerSetSlotPacket p
			? p.getContainerId() : numberAccessor(packet, "containerId");
		int stateId = packet instanceof ClientboundContainerSetSlotPacket p
			? p.getStateId() : numberAccessor(packet, "stateId");
		event.put("containerId", containerId);
		event.put("stateId", stateId);
		AbstractContainerMenu menu = getMenu(mc, containerId);
		List<Map<String, Object>> observedSlots = new ArrayList<>();
		if(menu != null)
		{
			List<Integer> rawSlots = new ArrayList<>();
			if(packet instanceof ClientboundContainerSetSlotPacket p)
				rawSlots.add(p.getSlot());
			else
			{
				Object values = accessor(packet, "items");
				List<?> contents =
					values instanceof List<?> list ? list : List.of();
				for(int slot = 0; slot < contents.size(); slot++)
					rawSlots.add(slot);
			}
			for(int rawSlot : rawSlots)
			{
				if(rawSlot < -1 || rawSlot >= menu.slots.size())
					continue;
				ItemStack clientStack = rawSlot == -1 ? menu.getCarried()
					: menu.getSlot(rawSlot).getItem();
				Map<String, Object> observation = new LinkedHashMap<>();
				observation.put("rawSlot", rawSlot);
				observation.put("logicalSlot",
					rawSlot == -1 ? "carried_item" : InventorySlotMapping
						.describe(menu, menu.getSlot(rawSlot), mc));
				ItemStack serverItem = packetItem(packet, rawSlot);
				observation.put("serverProvidedItem",
					captureStack(serverItem, true, false, 0).details());
				ItemStackForensics.Snapshot snapshot =
					captureStack(clientStack, true, false, 0);
				observation.put("itemAfterHandler", snapshot.details());
				observation.put("fingerprintAfterHandler",
					snapshot.fingerprint());
				String receivedEventId =
					(String)event.get("receivedPacketEventId");
				if(receivedEventId != null)
				{
					String key = postApplicationKey(receivedEventId,
						containerId, rawSlot);
					observation.put("fingerprintBeforeHandler",
						preApplicationFingerprints.remove(key));
					postApplicationFingerprints.put(key,
						snapshot.fingerprint());
				}
				observedSlots.add(observation);
			}
		}
		while(postApplicationFingerprints.size() > 1024)
			postApplicationFingerprints
				.remove(postApplicationFingerprints.keySet().iterator().next());
		event.put("handlerCompleted", true);
		event.put("postApplicationStateAvailable", menu != null);
		event.put("postApplicationSlots", observedSlots);
		append(event);
	}
	
	private static AbstractContainerMenu getMenu(Minecraft mc, int containerId)
	{
		if(mc.player == null)
			return null;
		if(containerId == 0)
			return mc.player.inventoryMenu;
		if(containerId == -1)
			return mc.player.inventoryMenu;
		return mc.player.containerMenu.containerId == containerId
			? mc.player.containerMenu : null;
	}
	
	private static List<Integer> packetSlots(Packet<?> packet)
	{
		if(packet instanceof ClientboundContainerSetSlotPacket p)
			return List.of(p.getSlot());
		Object value = accessor(packet, "items");
		int size = value instanceof List<?> list ? list.size() : 0;
		List<Integer> slots = new ArrayList<>(size);
		for(int i = 0; i < size; i++)
			slots.add(i);
		return slots;
	}
	
	private static ItemStack packetItem(Packet<?> packet, int rawSlot)
	{
		if(packet instanceof ClientboundContainerSetSlotPacket p)
			return p.getItem();
		Object value = accessor(packet, "items");
		return value instanceof List<?> list && rawSlot < list.size()
			&& list.get(rawSlot) instanceof ItemStack stack ? stack
				: ItemStack.EMPTY;
	}
	
	private static boolean isSlotUpdate(Packet<?> packet)
	{
		return packet instanceof ClientboundContainerSetSlotPacket
			|| packet instanceof ClientboundContainerSetContentPacket;
	}
	
	// ### ADDED ### Bind incoming slot updates to their actual protocol slots.
	private void captureSlotUpdateCandidates(Packet<?> packet, String eventId,
		PacketDumper dumper, boolean fullContent, int maxContentBytes)
	{
		String name = packet.getClass().getSimpleName();
		if(!name.contains("ContainerSetSlot")
			&& !name.contains("ContainerSetContent"))
			return;
		Integer containerId = numberAccessor(packet, "containerId");
		Integer stateId = numberAccessor(packet, "stateId");
		if(containerId == null)
			return;
		long now = System.nanoTime();
		pendingSlotUpdates
			.removeIf(update -> now - update.timeNs > 5_000_000_000L);
		if(name.contains("ContainerSetSlot"))
		{
			Integer slot = numberAccessor(packet, "slot");
			Object stack = accessor(packet, "item");
			if(slot != null && stack instanceof ItemStack item)
				addSlotCandidate(eventId, containerId, stateId, slot, item, now,
					fullContent, maxContentBytes);
		}else
		{
			Object values = accessor(packet, "items");
			if(values instanceof List<?> items)
				for(int slot = 0; slot < items.size(); slot++)
					if(items.get(slot) instanceof ItemStack item)
						addSlotCandidate(eventId, containerId, stateId, slot,
							item, now, fullContent, maxContentBytes);
		}
		while(pendingSlotUpdates.size() > 512)
			pendingSlotUpdates.removeFirst();
	}
	
	private void addSlotCandidate(String eventId, int containerId,
		Integer stateId, int rawSlot, ItemStack stack, long now,
		boolean fullContent, int maxContentBytes)
	{
		pendingSlotUpdates.addLast(new PendingSlotUpdate(eventId, containerId,
			rawSlot, stateId == null ? -1 : stateId,
			captureStack(stack, true, fullContent, maxContentBytes)
				.fingerprint(),
			now));
	}
	
	private static Integer numberAccessor(Object target, String name)
	{
		Object value = accessor(target, name);
		return value instanceof Number number ? number.intValue() : null;
	}
	
	private static Object accessor(Object target, String name)
	{
		for(String candidate : List.of(name,
			"get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)))
			try
			{
				Method method = target.getClass().getMethod(candidate);
				return method.invoke(target);
			}catch(ReflectiveOperationException | RuntimeException ignored)
			{}
		return null;
	}
	
	private void purgeExpiredTransactions(long now, int timeoutMs)
	{
		correlationTimeoutMs = Math.max(50, timeoutMs);
		long cutoff = now - correlationTimeoutMs * 1_000_000L;
		Iterator<PendingTransaction> iterator =
			pendingTransactions.values().iterator();
		while(iterator.hasNext())
			if(iterator.next().timeNs < cutoff)
				iterator.remove();
	}
	
	/** Must be called on the client thread after packet handlers have run. */
	public synchronized void tick(boolean fullContent, int maxContentBytes)
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || mc.level == null)
		{
			if(wasConnected)
			{
				Map<String, Object> event = baseEvent("session_event");
				event.put("action", "client_world_disconnected");
				append(event);
			}
			wasConnected = false;
			previousSlots.clear();
			nearbyItems.clear();
			lastMenuKey = null;
			return;
		}
		wasConnected = true;
		clientTick++;
		
		Map<String, ItemStackForensics.Snapshot> now = new LinkedHashMap<>();
		Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
		AbstractContainerMenu inventoryMenu = mc.player.inventoryMenu;
		AbstractContainerMenu activeMenu = mc.player.containerMenu;
		captureMenu("player_inventory", inventoryMenu, now, contexts, true,
			fullContent, maxContentBytes);
		if(activeMenu != inventoryMenu)
			captureMenu("opened_container", activeMenu, now, contexts, true,
				fullContent, maxContentBytes);
		{
			for(EquipmentSlot slot : EquipmentSlot.values())
			{
				ItemStack stack = mc.player.getItemBySlot(slot);
				String key = "equipment:" + slot.getName();
				captureSlot(key, stack, now, contexts, true, fullContent,
					maxContentBytes);
				contexts.get(key).put("logicalSlot",
					"equipment." + slot.getName());
			}
		}
		// ### ADDED ### Observe all loaded client-visible storage block
		// entities.
		captureLoadedContainers(now, contexts, true, fullContent,
			maxContentBytes);
		compareSlots(previousSlots, now, contexts, true);
		previousSlots.clear();
		previousSlots.putAll(now);
		previousSlotContexts.clear();
		previousSlotContexts.putAll(contexts);
		String menuKey = activeMenu == inventoryMenu ? "player_inventory"
			: "container:" + activeMenu.containerId;
		if(lastMenuKey != null && !lastMenuKey.equals(menuKey))
		{
			Map<String, Object> event = baseEvent("container_event");
			event.put("action", "menu_changed");
			event.put("previousMenu", lastMenuKey);
			event.put("currentMenu", menuKey);
			append(event);
		}
		lastMenuKey = menuKey;
		captureLoadedItemEntities(mc, true, fullContent, maxContentBytes);
	}
	
	private void captureLoadedContainers(
		Map<String, ItemStackForensics.Snapshot> now,
		Map<String, Map<String, Object>> contexts, boolean components,
		boolean fullContent, int maxContentBytes)
	{
		ChunkUtils.getLoadedBlockEntities().forEach(blockEntity -> {
			if(!(blockEntity instanceof Container container))
				return;
			String blockId = BuiltInRegistries.BLOCK
				.getKey(blockEntity.getBlockState().getBlock()).toString();
			String position = blockEntity.getBlockPos().toShortString();
			for(int slot = 0; slot < container.getContainerSize(); slot++)
			{
				String key = "block_entity:" + position + ":slot:" + slot;
				captureSlot(key, container.getItem(slot), now, contexts,
					components, fullContent, maxContentBytes);
				Map<String, Object> context = contexts.get(key);
				context.put("logicalSlot",
					"block_entity_container[" + slot + "]");
				context.put("blockId", blockId);
				context.put("blockState", RegistryDecoder
					.decodeBlockStateDetailed(blockEntity.getBlockState()));
				context.put("blockPosition", position);
				context.put("containerIdentity",
					blockEntity.getClass().getName());
			}
		});
	}
	
	private void captureMenu(String label, AbstractContainerMenu menu,
		Map<String, ItemStackForensics.Snapshot> now,
		Map<String, Map<String, Object>> contexts, boolean components,
		boolean fullContent, int maxContentBytes)
	{
		int containerId = menu.containerId;
		int stateId = readStateId(menu);
		String prefix = label + ":" + containerId;
		for(int slot = 0; slot < menu.slots.size(); slot++)
		{
			String key = prefix + ":slot:" + slot;
			var menuSlot = menu.getSlot(slot);
			captureSlot(key, menuSlot.getItem(), now, contexts, components,
				fullContent, maxContentBytes);
			contexts.get(key).put("rawSlot", slot);
			contexts.get(key).put("backingContainerSlot",
				menuSlot.getContainerSlot());
			contexts.get(key).put("logicalSlot", InventorySlotMapping
				.describe(menu, menuSlot, Minecraft.getInstance()));
			contexts.get(key).put("stateId", stateId);
		}
		String carriedKey = prefix + ":carried";
		captureSlot(carriedKey, menu.getCarried(), now, contexts, components,
			fullContent, maxContentBytes);
		contexts.get(carriedKey).put("logicalSlot", "carried_item");
		contexts.get(carriedKey).put("containerId", -1);
		contexts.get(carriedKey).put("rawSlot", -1);
		contexts.get(carriedKey).put("stateId", stateId);
	}
	
	private void captureSlot(String key, ItemStack stack,
		Map<String, ItemStackForensics.Snapshot> now,
		Map<String, Map<String, Object>> contexts, boolean components,
		boolean fullContent, int maxContentBytes)
	{
		now.put(key,
			captureStack(stack, components, fullContent, maxContentBytes));
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("slotKey", key);
		context.put("containerId", parseContainerId(key));
		context.put("rawSlot", null);
		context.put("logicalSlot", "unknown");
		context.put("stateId", -1);
		contexts.put(key, context);
	}
	
	private void compareSlots(Map<String, ItemStackForensics.Snapshot> before,
		Map<String, ItemStackForensics.Snapshot> after,
		Map<String, Map<String, Object>> currentContexts,
		boolean autoAnomalyDetection)
	{
		Map<String, List<String>> removed = new LinkedHashMap<>(),
			added = new LinkedHashMap<>();
		before.forEach((key, old) -> {
			var next = after.get(key);
			if(next == null)
				return;
			if(!old.itemId().equals("minecraft:air")
				&& next.itemId().equals("minecraft:air"))
				removed.computeIfAbsent(old.fingerprint(),
					unused -> new ArrayList<>()).add(key);
			if(old.itemId().equals("minecraft:air")
				&& !next.itemId().equals("minecraft:air"))
				added.computeIfAbsent(next.fingerprint(),
					unused -> new ArrayList<>()).add(key);
		});
		removed.forEach((hash, sources) -> {
			var destinations = added.get(hash);
			if(destinations == null)
				return;
			var transfer = baseEvent("item_transfer_observation");
			transfer.put("itemFingerprint", hash);
			transfer.put("sourceSlotCandidates", sources);
			transfer.put("destinationSlotCandidates", destinations);
			transfer.put("confidence",
				sources.size() == 1 && destinations.size() == 1
					? "MATCHING_FULL_STACK_RELOCATION_IN_SAME_STATE_OBSERVATION"
					: "INDETERMINATE_MULTIPLE_IDENTICAL_STACKS");
			transfer.put("serverOperationConfirmed", false);
			append(transfer);
		});
		Set<String> keys = new LinkedHashSet<>(before.keySet());
		keys.addAll(after.keySet());
		for(String key : keys)
		{
			ItemStackForensics.Snapshot old = before.get(key);
			ItemStackForensics.Snapshot current = after.get(key);
			if(old != null && current != null
				&& old.fingerprint().equals(current.fingerprint()))
				continue;
			// Opening/loading/unloading an inventory is a scope observation,
			// not evidence that an item mutated or was deleted.
			Map<String, Object> event = baseEvent(old == null || current == null
				? "slot_observation" : "item_mutation");
			event.put("slotKey", key);
			if(observationContext
				.get("slotInteractions") instanceof Map<?, ?> matches
				&& matches.get(key) instanceof Map<?, ?> interaction)
			{
				event.put("relatedEarlierInteraction", interaction);
				event.put("relationshipConfidence",
					"SAME_SLOT_EARLIER_ACTION_CANDIDATE_NOT_REMOTE_CAUSALITY");
				if(interaction
					.get("actionRequestedNs") instanceof Number requested
					&& event.get("packetArrivalNs") instanceof Number arrival
					&& arrival.longValue() >= requested.longValue())
					event.put("earlierActionToPacketArrivalNs",
						arrival.longValue() - requested.longValue());
			}
			event.put("slotContext", currentContexts.getOrDefault(key,
				previousSlotContexts.getOrDefault(key, Map.of())));
			event.putIfAbsent("captureStage", "client_tick_snapshot");
			event.put("stateSource",
				observationContext.containsKey("logicalPacketId")
					? "client_handler_observation"
					: "client_tick_reconciliation");
			List<PendingSlotUpdate> matchingPackets =
				matchingSlotUpdates(key, currentContexts.get(key), current);
			if(!matchingPackets.isEmpty())
			{
				event.put("relatedServerPacketEventIds",
					matchingPackets.stream().map(PendingSlotUpdate::eventId)
						.distinct().toList());
				boolean handlerMatched = matchingPackets.stream()
					.anyMatch(update -> Objects.equals(
						postApplicationFingerprints
							.get(postApplicationKey(update.eventId,
								update.containerId, update.rawSlot)),
						current.fingerprint()));
				event.put("correlationConfidence",
					handlerMatched ? "CONFIRMED_HANDLER_STATE_MATCH"
						: "PROBABLE_SLOT_AND_VALUE_MATCH");
				event.put("correlationBasis",
					"same container, raw slot, state ordering, and semantic item fingerprint; packet application is not directly observed");
			}
			event.put("comparisonComplete",
				old != null && current != null && !hasUnavailableComponents(old)
					&& !hasUnavailableComponents(current));
			event.put("itemBefore", old == null ? null : old.details());
			event.put("itemAfter", current == null ? null : current.details());
			if(current != null)
				for(Object component : current.components().values())
					if(component instanceof Map<?, ?> fields && fields
						.get("bookContentSummary") instanceof Map<?, ?> book)
						event.put("itemBookContentAfter", book);
			event.put("fingerprintBefore",
				old == null ? null : old.fingerprint());
			event.put("fingerprintAfter",
				current == null ? null : current.fingerprint());
			event.put("componentDiff", componentDiff(old, current));
			if(old == null)
				event.put("change", "observed_or_opened");
			else if(current == null)
				event.put("change", "scope_no_longer_observed");
			else if(!old.itemId().equals("minecraft:air")
				&& current.itemId().equals("minecraft:air"))
				event.put("change", "item_removed");
			else if(!old.itemId().equals(current.itemId()))
				event.put("change", "item_replaced");
			else if(old.count() != current.count())
				event.put("change", "item_count_changed");
			else if(!componentDiff(old, current).isEmpty())
				event.put("change", "component_contents_changed");
			else
				event.put("change", "state_changed");
			if(old != null && current != null)
			{
				Map<String, Object> earlier = lastSlotEvents.get(key);
				if(earlier != null)
				{
					event.put("previousSlotChange", earlier);
					Object a = earlier.get("packetArrivalNs"),
						b = event.get("packetArrivalNs");
					if(a instanceof Number first && b instanceof Number second
						&& second.longValue() >= first.longValue())
						event.put("sincePreviousSlotPacketArrivalNs",
							second.longValue() - first.longValue());
				}
				if(observationContext.containsKey("logicalPacketId"))
				{
					event.put("relatedServerPacketEventIds",
						List.of(observationContext.get("logicalPacketId")));
					event.put("correlationConfidence",
						"EXACT_PACKET_HANDLER_AND_SLOT");
					event.put("correlationBasis",
						"Affected slot copied before and after this packet handler; no remote execution order inferred");
				}
				Map<String, Object> history = new LinkedHashMap<>();
				for(String field : List.of("eventId", "logicalPacketId",
					"packetArrivalNs", "handlerStartNs", "stateAppliedNs",
					"monotonicNs", "fingerprintAfter", "change"))
					if(event.get(field) != null)
						history.put(field, event.get(field));
				lastSlotEvents.put(key, history);
			}else if(current == null)
				lastSlotEvents.remove(key);
			append(event);
			for(PendingSlotUpdate update : matchingPackets)
				pendingSlotUpdates.remove(update);
			if(autoAnomalyDetection && matchingPackets.isEmpty() && old != null
				&& current != null)
			{
				Map<String, Object> anomaly = baseEvent("anomaly_detection");
				anomaly.put("anomalyType",
					"unexplained_client_observed_change");
				anomaly.put("severity", "informational");
				anomaly.put("relatedSlotKey", key);
				anomaly.put("interpretation",
					"The observed item changed without a matching observed update for this exact slot; normal client operations may explain it.");
				anomaly.put("cause", "uncertain");
				append(anomaly);
			}
		}
	}
	
	private static String postApplicationKey(String eventId, int containerId,
		int rawSlot)
	{
		return eventId + ":" + containerId + ":" + rawSlot;
	}
	
	private List<PendingSlotUpdate> matchingSlotUpdates(String key,
		Map<String, Object> context, ItemStackForensics.Snapshot current)
	{
		if(context == null || current == null
			|| !(context.get("rawSlot") instanceof Number rawSlot)
			|| !(context.get("containerId") instanceof Number containerId))
			return List.of();
		long now = System.nanoTime();
		pendingSlotUpdates
			.removeIf(update -> now - update.timeNs > 5_000_000_000L);
		List<PendingSlotUpdate> matches = new ArrayList<>();
		int currentState =
			context.get("stateId") instanceof Number n ? n.intValue() : -1;
		for(PendingSlotUpdate update : pendingSlotUpdates)
			if(update.containerId == containerId.intValue()
				&& update.rawSlot == rawSlot.intValue()
				&& (currentState < 0 || update.stateId < 0
					|| update.stateId <= currentState)
				&& update.itemFingerprint.equals(current.fingerprint()))
				matches.add(update);
		return matches;
	}
	
	private static boolean hasUnavailableComponents(
		ItemStackForensics.Snapshot snapshot)
	{
		return snapshot.components().values().stream()
			.anyMatch(value -> value instanceof Map<?, ?> map
				&& "UNAVAILABLE".equals(map.get("state")));
	}
	
	private static int parseContainerId(String key)
	{
		String[] parts = key.split(":");
		if(parts.length < 2 || parts[0].equals("equipment"))
			return -1;
		try
		{
			return Integer.parseInt(parts[1]);
		}catch(NumberFormatException e)
		{
			return -1;
		}
	}
	
	static List<Map<String, Object>> componentDiff(
		ItemStackForensics.Snapshot before, ItemStackForensics.Snapshot after)
	{
		Map<String, Object> a = before == null ? Map.of() : before.components();
		Map<String, Object> b = after == null ? Map.of() : after.components();
		Set<String> ids = new LinkedHashSet<>(a.keySet());
		ids.addAll(b.keySet());
		List<Map<String, Object>> diff = new ArrayList<>();
		for(String id : ids)
		{
			Object old = a.get(id);
			Object current = b.get(id);
			String oldSemantic = componentSemanticFingerprint(old);
			String newSemantic = componentSemanticFingerprint(current);
			if(java.util.Objects.equals(oldSemantic, newSemantic))
				continue;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("componentId", id);
			entry.put("beforeState", componentState(old));
			entry.put("afterState", componentState(current));
			entry.put("before", old);
			entry.put("after", current);
			entry.put("comparisonComplete",
				isComparableComponent(old) && isComparableComponent(current));
			diff.add(entry);
		}
		return diff;
	}
	
	// ### ADDED ### Ignore payload previews and serializer metadata during
	// diffs.
	private static String componentSemanticFingerprint(Object value)
	{
		if(value == null)
			return "ABSENT";
		if(value instanceof Map<?, ?> map)
		{
			Object state = map.get("state");
			Object hash = map.get("valueSha256");
			Object valueState = map.get("valueState");
			if(hash instanceof String)
				return state + ":" + hash;
			if("UNAVAILABLE".equals(state)
				|| "OMITTED_BY_LIMIT".equals(valueState))
				return "INCOMPLETE:" + state + ":" + valueState;
		}
		return ItemStackForensics.fingerprintCanonical(value);
	}
	
	private static boolean isComparableComponent(Object value)
	{
		if(value == null)
			return true;
		if(value instanceof Map<?, ?> map)
			return map.get("valueSha256") instanceof String
				|| !"UNAVAILABLE".equals(map.get("state"));
		return true;
	}
	
	private static String componentState(Object value)
	{
		if(value == null)
			return "ABSENT";
		if(value instanceof Map<?, ?> map
			&& map.get("state") instanceof String state)
			return state;
		return "PRESENT";
	}
	
	private void captureLoadedItemEntities(Minecraft mc, boolean components,
		boolean fullContent, int maxContentBytes)
	{
		Set<UUID> observed = new LinkedHashSet<>();
		for(var entity : mc.level.entitiesForRendering())
		{
			if(!(entity instanceof ItemEntity item) || !item.isAlive())
				continue;
			UUID uuid = item.getUUID();
			observed.add(uuid);
			ItemStackForensics.Snapshot snapshot = captureStack(item.getItem(),
				components, fullContent, maxContentBytes);
			String fingerprint = snapshot.fingerprint();
			Vec3 pos = item.position();
			NearbyItemState previous =
				nearbyItems.put(uuid, new NearbyItemState(fingerprint, pos));
			boolean changed =
				previous != null && !fingerprint.equals(previous.fingerprint);
			boolean moved =
				previous != null && pos.distanceToSqr(previous.position) >= 1;
			if(previous != null && !changed && !moved)
				continue;
			Map<String, Object> event = baseEvent("item_entity_lifecycle");
			event.put("action", previous == null ? "observed_loaded"
				: changed ? "item_changed" : "moved");
			event.put("entityId", item.getId());
			event.put("entityUuid", uuid.toString());
			event.put("position", Map.of("x", pos.x, "y", pos.y, "z", pos.z));
			if(previous != null)
				event.put("positionBefore", Map.of("x", previous.position.x,
					"y", previous.position.y, "z", previous.position.z));
			event.put("item", snapshot.details());
			event.put("fingerprint", fingerprint);
			append(event);
		}
		for(UUID uuid : new ArrayList<>(nearbyItems.keySet()))
			if(!observed.contains(uuid))
			{
				NearbyItemState previous = nearbyItems.remove(uuid);
				Map<String, Object> event = baseEvent("item_entity_lifecycle");
				event.put("action", "no_longer_loaded_or_removed");
				event.put("entityUuid", uuid.toString());
				event.put("positionBefore", Map.of("x", previous.position.x,
					"y", previous.position.y, "z", previous.position.z));
				event.put("interpretation",
					"The entity left the observed area or was removed; the cause is not exposed here.");
				append(event);
			}
	}
	
	private record NearbyItemState(String fingerprint, Vec3 position)
	{}
	
	private static int readStateId(AbstractContainerMenu menu)
	{
		try
		{
			return ((Number)menu.getClass().getMethod("getStateId")
				.invoke(menu)).intValue();
		}catch(ReflectiveOperationException | RuntimeException ignored)
		{
			return -1;
		}
	}
	
	private Map<String, Object> baseEvent(String type)
	{
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("schemaVersion", 2);
		event.put("sessionId", sessionId);
		event.put("eventId", "itemforensics-" + (eventCount + 1));
		event.put("transactionId", null);
		event.put("timestamp", Instant.now().toString());
		event.put("monotonicNs", System.nanoTime());
		event.put("clientTick", clientTick);
		event.put("thread", Thread.currentThread().getName());
		event.putAll(observationContext);
		event.put("eventType", type);
		return event;
	}
	
	private synchronized void append(Map<String, Object> event)
	{
		List<Map<String, Object>> payloadEvents = new ArrayList<>();
		Map<String, Object> compact = compactContent(event, payloadEvents, 0);
		appendRaw(compact);
		for(Map<String, Object> payloadEvent : payloadEvents)
			appendRaw(payloadEvent);
	}
	
	private Map<String, Object> compactContent(Map<String, Object> event,
		List<Map<String, Object>> payloadEvents, int depth)
	{
		Map<String, Object> compact = new LinkedHashMap<>();
		event.forEach((key, value) -> compact.put(key,
			compactValue(value, payloadEvents, depth + 1)));
		return compact;
	}
	
	private Object compactValue(Object value,
		List<Map<String, Object>> payloadEvents, int depth)
	{
		if(depth > 64)
			return Map.of("_truncated", true, "reason", "MAX_DEDUP_DEPTH");
		if(value instanceof String text && text.length() > 1024)
		{
			String hash = ItemStackForensics.sha256(text);
			if(emittedPayloadHashes.size() >= 8192)
				emittedPayloadHashes
					.remove(emittedPayloadHashes.iterator().next());
			if(emittedPayloadHashes.add("text:" + hash))
			{
				var payload = baseEvent("content_payload");
				payload.put("payloadReference", "payload-text-" + hash);
				payload.put("contentSha256", hash);
				payload.put("payload", text);
				payloadEvents.add(payload);
			}
			return Map.of("payloadReference", "payload-text-" + hash, "sha256",
				hash, "length", text.length());
		}
		if(value instanceof JsonElement)
			return value;
		if(value instanceof Map<?, ?> map)
		{
			Map<String, Object> compact = new LinkedHashMap<>();
			Object hash = map.get("valueSha256");
			if(hash instanceof String contentHash && map.containsKey("value"))
			{
				Object payload = map.get("value");
				map.forEach((key, child) -> {
					if(!"value".equals(String.valueOf(key)))
						compact.put(String.valueOf(key),
							compactValue(child, payloadEvents, depth + 1));
				});
				compact.put("payloadReference", "payload-" + contentHash);
				if(emittedPayloadHashes.size() >= 8192)
					emittedPayloadHashes
						.remove(emittedPayloadHashes.iterator().next());
				if(emittedPayloadHashes.add(contentHash))
				{
					Map<String, Object> payloadEvent =
						baseEvent("content_payload");
					payloadEvent.put("payloadReference",
						"payload-" + contentHash);
					payloadEvent.put("contentSha256", contentHash);
					payloadEvent.put("payload", payload);
					payloadEvents.add(payloadEvent);
				}
				return compact;
			}
			map.forEach((key, child) -> compact.put(String.valueOf(key),
				compactValue(child, payloadEvents, depth + 1)));
			return compact;
		}
		if(value instanceof Collection<?> collection)
		{
			List<Object> compact = new ArrayList<>(collection.size());
			for(Object child : collection)
				compact.add(compactValue(child, payloadEvents, depth + 1));
			return compact;
		}
		return value;
	}
	
	private void appendRaw(Map<String, Object> event)
	{
		event.put("eventId", "itemforensics-" + (eventCount + 1));
		String serialized = GSON.toJson(event);
		long bytes =
			serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
				+ 1L;
		if(eventSink == null && (pending.size() >= maxPendingEvents
			|| pendingBytes + bytes > maxCaptureBytes))
		{
			droppedEvents++;
			totalDroppedEvents++;
			return;
		}
		eventCount++;
		bookBotPerformance.accept(event);
		eventTypeCounts.merge(String.valueOf(event.get("eventType")), 1L,
			Long::sum);
		countDiagnosticEvent(event);
		Map<String, Object> historyEntry = new LinkedHashMap<>();
		for(String key : List.of("eventId", "timestamp", "eventType", "name",
			"packetId", "action", "change", "anomalyType", "slotKey",
			"containerId", "stateId", "relatedServerPacketEventIds",
			"receivedPacketEventId", "correlationConfidence", "shelfPosition",
			"shelfSlot", "contentSha256", "reason", "outcome",
			"matchesSubmittedContent", "contentConfirmedNs",
			"interactionRequestedNs", "editSubmittedNs", "monotonicNs",
			"logicalPacketId", "transactionId", "captureStage",
			"packetArrivalNs", "handlerStartNs", "handlerEndNs",
			"stateAppliedNs", "transportSubmittedNs",
			"sincePreviousSlotPacketArrivalNs", "relatedEarlierInteraction",
			"relationshipConfidence", "fingerprintBefore", "fingerprintAfter",
			"packetClass", "actionRequestedNs", "sendRequestedNs",
			"itemBookContentAfter", "earlierActionToPacketArrivalNs",
			"bookGeneratedObservedNs"))
			if(event.containsKey(key))
				historyEntry.put(key, event.get(key));
		Object timelineId = event.get("transactionId");
		if(timelineId == null && event
			.get("relatedEarlierInteraction") instanceof Map<?, ?> candidate)
			timelineId = candidate.get("transactionId");
		if(timelineId instanceof String transaction)
		{
			if(!transactionTimelines.containsKey(transaction)
				&& transactionTimelines.size() >= 128)
			{
				transactionTimelines
					.remove(transactionTimelines.keySet().iterator().next());
				timelineOmissions++;
			}
			var timeline = transactionTimelines.computeIfAbsent(transaction,
				unused -> new ArrayList<>());
			if(timeline.size() < 128)
				timeline.add(new LinkedHashMap<>(historyEntry));
			else
				timelineOmissions++;
		}
		recentHistory.addLast(historyEntry);
		while(recentHistory.size() > 200)
			recentHistory.removeFirst();
		capturedBytes += bytes;
		if(eventSink != null)
			eventSink.accept(serialized);
		else
		{
			pending.addLast(serialized);
			pendingBytes += bytes;
		}
	}
	
	private void countDiagnosticEvent(Map<String, Object> event)
	{
		String type = String.valueOf(event.get("eventType"));
		if(type.equals("packet_observation"))
			incrementMetric("packetsObserved");
		if(type.equals("item_transfer_observation"))
			incrementMetric("containerTransferObservations");
		if(type.equals("inventory_packet_application"))
			incrementMetric("inventoryPacketsApplied");
		if(type.equals("item_mutation"))
		{
			incrementMetric("inventoryMutations");
			if(event.containsKey("logicalPacketId"))
				incrementMetric("serverPacketAppliedItemChanges");
			if("item_removed".equals(event.get("change")))
				incrementMetric("itemRemovals");
			if(event.get("componentDiff") instanceof Collection<?> diff
				&& !diff.isEmpty())
				incrementMetric("componentChanges");
			if(Boolean.FALSE.equals(event.get("comparisonComplete")))
				incrementMetric("incompleteComparisons");
		}
		if(type.equals("anomaly_detection")
			&& "unexplained_client_observed_change"
				.equals(event.get("anomalyType")))
			incrementMetric("unexplainedChanges");
		if(event.get("name") instanceof String name)
			incrementMetric("external." + name);
		if(event.get("outcome") instanceof String outcome)
			incrementMetric("storageOutcome." + outcome);
		if(type.equals("item_entity_lifecycle"))
			incrementMetric("droppedItemEvents");
		if(event.containsKey("diagnosticError"))
		{
			decodeErrorCount++;
			incrementMetric("decodeErrors");
		}
	}
	
	private void incrementMetric(String name)
	{
		diagnosticCounts.merge(name, 1L, Long::sum);
	}
	
	public synchronized void setMaxPendingEvents(int maximum)
	{
		maxPendingEvents = Math.max(128, maximum);
	}
	
	public synchronized void startNewSession()
	{
		pending.clear();
		pendingBytes = 0;
		releaseSessionResources();
		emittedPayloadHashes.clear();
		pendingTransactions.clear();
		pendingSlotUpdates.clear();
		receivedPacketEventIds.clear();
		postApplicationFingerprints.clear();
		previousSlots.clear();
		previousSlotContexts.clear();
		nearbyItems.clear();
		sessionId = UUID.randomUUID().toString();
		startedAt = Instant.now().toString();
		clientTick = 0;
		eventCount = 0;
		eventTypeCounts.clear();
		diagnosticCounts.clear();
		recentHistory.clear();
		capturedBytes = 0;
		droppedEvents = 0;
		totalDroppedEvents = 0;
		lastMenuKey = null;
		wasConnected = false;
	}
	
	public synchronized void setMaxCaptureRecords(long maximum)
	{
		maxCaptureRecords = Math.max(1000, maximum);
	}
	
	public synchronized void setMaxCaptureBytes(long maximum)
	{
		maxCaptureBytes = Math.max(1024 * 1024, maximum);
	}
	
	public synchronized void setCorrelationTimeoutMs(int timeoutMs)
	{
		correlationTimeoutMs = Math.max(50, timeoutMs);
	}
	
	private static boolean isRelevantPacket(String name)
	{
		String value = name.toLowerCase(java.util.Locale.ROOT);
		return value.contains("container") || value.contains("inventory")
			|| value.contains("item") || value.contains("equipment")
			|| value.contains("book") || value.contains("useitem")
			|| value.contains("interact") || value.contains("entitydata")
			|| value.contains("playeraction") || value.contains("creative")
			|| value.contains("recipe") || value.contains("carrieditem")
			|| value.contains("block");
	}
	
	private static boolean isInventorySyncPacket(String name)
	{
		String value = name.toLowerCase(java.util.Locale.ROOT);
		return value.contains("container") || value.contains("inventory")
			|| value.contains("equipment") || value.contains("setslot")
			|| value.contains("setcontent");
	}
	
	public synchronized List<String> drain(int maximum)
	{
		List<String> result =
			new ArrayList<>(Math.min(maximum, pending.size()));
		while(result.size() < maximum && !pending.isEmpty())
		{
			String line = pending.removeFirst();
			pendingBytes -=
				line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
					+ 1L;
			result.add(line);
		}
		if(droppedEvents > 0 && result.size() < maximum)
		{
			Map<String, Object> overflow = baseEvent("diagnostic_overflow");
			overflow.put("eventId",
				"itemforensics-overflow-" + System.nanoTime());
			overflow.put("droppedRecords", droppedEvents);
			droppedEvents = 0;
			result.add(GSON.toJson(overflow));
		}
		return result;
	}
	
	public synchronized void recordOutputDrops(long count)
	{
		if(count <= 0)
			return;
		droppedEvents += count;
		totalDroppedEvents += count;
	}
	
	public synchronized void recordExternalEvent(String name,
		Map<String, Object> fields)
	{
		Map<String, Object> event = baseEvent("external_forensics_event");
		event.put("name", name);
		event.putAll(fields);
		append(event);
	}
	
	static Map<String, Object> transactionSummary(
		List<Map<String, Object>> entries)
	{
		List<Map<String, Object>> ordered = new ArrayList<>(entries);
		ordered.sort(java.util.Comparator
			.comparingLong(ItemInventoryForensics::timelineNs));
		Map<String, Long> times = new LinkedHashMap<>();
		String outcome = "INDETERMINATE";
		for(var row : ordered)
		{
			String name = String.valueOf(row.get("name")),
				stage = String.valueOf(row.get("captureStage")),
				packet = String.valueOf(row.get("packetClass"));
			if(row.get("bookGeneratedObservedNs") instanceof Number n)
				times.putIfAbsent("bookGeneratedObservedNs", n.longValue());
			if(stage.equals("ACTION_REQUESTED") && packet.contains("EditBook"))
				putTime(times, "editRequestedNs", row, "actionRequestedNs");
			if(stage.equals("TRANSPORT_SUBMITTED")
				&& packet.contains("EditBook"))
				putTime(times, "editSubmittedNs", row, "transportSubmittedNs");
			if(stage.equals("TRANSPORT_SUBMITTED")
				&& packet.contains("UseItemOn"))
				putTime(times, "shelfSubmittedNs", row, "transportSubmittedNs");
			if(row.get("itemBookContentAfter") instanceof Map<?, ?> book
				&& book.get("pageCount") instanceof Number count
				&& count.intValue() > 0)
				putTime(times, "populatedBookArrivalNs", row,
					"packetArrivalNs");
			if("item_removed".equals(row.get("change")))
				putTime(times, "removalArrivalNs", row, "packetArrivalNs");
			if(row.get("contentConfirmedNs") instanceof Number n)
				times.putIfAbsent("contentConfirmedNs", n.longValue());
			if(row.get("interactionRequestedNs") instanceof Number n)
				times.putIfAbsent("shelfRequestedNs", n.longValue());
			if(stage.equals("STATE_APPLIED")
				&& packet.contains("BlockEntityData"))
				putTime(times, "shelfStateAppliedNs", row, "stateAppliedNs");
			if(name.equals("BookBot.shelf_server_update"))
				outcome = String.valueOf(row.get("outcome"));
		}
		Map<String, Long> intervals = new LinkedHashMap<>();
		delta(intervals, times, "editRequestToSubmissionNs", "editRequestedNs",
			"editSubmittedNs");
		delta(intervals, times, "editSubmissionToPopulatedArrivalNs",
			"editSubmittedNs", "populatedBookArrivalNs");
		delta(intervals, times, "contentVerificationToShelfRequestNs",
			"contentConfirmedNs", "shelfRequestedNs");
		delta(intervals, times, "shelfRequestToSubmissionNs",
			"shelfRequestedNs", "shelfSubmittedNs");
		delta(intervals, times, "populatedArrivalToRemovalArrivalNs",
			"populatedBookArrivalNs", "removalArrivalNs");
		delta(intervals, times, "removalArrivalRelativeToShelfRequestNs",
			"shelfRequestedNs", "removalArrivalNs");
		delta(intervals, times, "shelfSubmissionToRemovalArrivalNs",
			"shelfSubmittedNs", "removalArrivalNs");
		delta(intervals, times, "shelfSubmissionToStateAppliedNs",
			"shelfSubmittedNs", "shelfStateAppliedNs");
		return Map.of("events", ordered, "observedStageTimes", times,
			"measuredIntervalsNs", intervals, "outcome", outcome,
			"serverExecutionOrder", "UNKNOWN; client observations only");
	}
	
	private static long timelineNs(Map<String, Object> row)
	{
		for(String field : List.of("contentConfirmedNs",
			"interactionRequestedNs", "bookGeneratedObservedNs", "monotonicNs"))
			if(row.get(field) instanceof Number n)
				return n.longValue();
		return Long.MAX_VALUE;
	}
	
	private static void putTime(Map<String, Long> times, String name,
		Map<String, Object> row, String field)
	{
		if(row.get(field) instanceof Number n)
			times.putIfAbsent(name, n.longValue());
	}
	
	private static void delta(Map<String, Long> out, Map<String, Long> times,
		String name, String start, String end)
	{
		if(times.containsKey(start) && times.containsKey(end))
			out.put(name, times.get(end) - times.get(start));
	}
	
	public synchronized String buildSummary(boolean redactServerAddress,
		Map<String, Object> captureSettings)
	{
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("schemaVersion", 2);
		summary.put("sessionId", sessionId);
		summary.put("captureStartedAt", startedAt);
		summary.put("captureEndedAt", Instant.now().toString());
		summary.put("minecraftVersion",
			net.minecraft.SharedConstants.getCurrentVersion().id());
		summary.put("clientVersion", WurstClient.VERSION);
		summary.put("serverAddress",
			captureSettings.getOrDefault("serverAddress", "redacted"));
		summary.put("capturedEventCount", eventCount);
		summary.put("eventTypeCounts", new LinkedHashMap<>(eventTypeCounts));
		summary.put("sessionMetrics", new LinkedHashMap<>(diagnosticCounts));
		summary.put("recentEventHistory", List.copyOf(recentHistory));
		Map<String, Object> timelines = new LinkedHashMap<>();
		transactionTimelines.forEach(
			(id, entries) -> timelines.put(id, transactionSummary(entries)));
		summary.put("transactionTimelines", timelines);
		summary.put("bookBotPerformance", bookBotPerformance.summary());
		summary.put("timelineEntriesOmittedByMemoryBound", timelineOmissions);
		summary.put("capturedBytes", capturedBytes);
		summary.put("maximumCaptureBytes", maxCaptureBytes);
		summary.put("pendingEventCount", pending.size());
		summary.put("droppedEventCount", totalDroppedEvents);
		summary.put("captureComplete", captureIsComplete());
		if(captureHealth != null)
			summary.put("captureHealth", captureHealth.get());
		summary.put("captureSettings", captureSettings);
		summary.put("eventStreamFile", "session-" + sessionId + ".jsonl");
		summary.put("notes", List.of(
			"Packet observations record interception stages;"
				+ " a client send hook does not prove network transmission.",
			"Inventory snapshots are copied at actual packet handler hooks and reconciled on client ticks;"
				+ " the client does not expose server-side causes."));
		return GSON.toJson(summary);
	}
	
	public synchronized String buildMarkdownSummary(boolean redactServerAddress,
		Map<String, Object> captureSettings)
	{
		StringBuilder report = new StringBuilder();
		report.append("# Item & Inventory Forensics Session\n\n")
			.append("- Session: `").append(sessionId).append("`\n")
			.append("- Started: ").append(startedAt).append("\n")
			.append("- Exported: ").append(Instant.now()).append("\n")
			.append("- Minecraft: ")
			.append(net.minecraft.SharedConstants.getCurrentVersion().id())
			.append("\n- Client: ").append(WurstClient.VERSION)
			.append("\n- Server: ")
			.append(captureSettings.getOrDefault("serverAddress", "redacted"))
			.append("\n- Captured records: ").append(eventCount)
			.append("\n- Captured bytes: ").append(capturedBytes)
			.append("\n- Legacy-buffer dropped records: ")
			.append(totalDroppedEvents).append("\n- Capture complete: ")
			.append(captureIsComplete()).append("\n- Event timeline: `session-")
			.append(sessionId).append(".jsonl`\n\n## Capture settings\n");
		captureSettings.forEach((key, value) -> report.append("- ").append(key)
			.append(": `").append(value).append("`\n"));
		report.append("\n## Event counts\n");
		eventTypeCounts.forEach((type, count) -> report.append("- ")
			.append(type).append(": ").append(count).append("\n"));
		report.append("\n## Session metrics\n");
		diagnosticCounts.forEach((name, count) -> report.append("- ")
			.append(name).append(": ").append(count).append("\n"));
		report.append("\n## Recent event history\n");
		for(Map<String, Object> entry : recentHistory)
		{
			report.append("- ").append(entry.get("timestamp")).append(" `")
				.append(entry.get("eventId")).append("` ")
				.append(entry.get("eventType"));
			if(entry.containsKey("action"))
				report.append(" â€” ").append(entry.get("action"));
			if(entry.containsKey("change"))
				report.append(" â€” ").append(entry.get("change"));
			report.append("\n");
		}
		report.append(
			"\nPacket records identify the client interception stage. A send-stage observation does not prove transmission. Temporal packet links are candidates, and the client cannot establish server-side causes.\n");
		report.append(bookBotPerformance.markdown());
		return report.toString();
	}
	
	private static String getServerAddress(boolean redact)
	{
		Minecraft mc = Minecraft.getInstance();
		var server = mc.getCurrentServer();
		if(server == null || server.ip == null)
			return mc.isLocalServer() ? "singleplayer" : "unknown";
		return redact ? "redacted" : server.ip;
	}
	
	public String getSessionId()
	{
		return sessionId;
	}
	
	private record PendingTransaction(long timeNs)
	{}
	
	private record PendingSlotUpdate(String eventId, int containerId,
		int rawSlot, int stateId, String itemFingerprint, long timeNs)
	{}
}
