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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.WurstClient;

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
	private long capturedBytes;
	private long droppedEvents;
	private long totalDroppedEvents;
	private String lastMenuKey;
	private int maxPendingEvents = DEFAULT_MAX_PENDING_EVENTS;
	private long maxCaptureRecords = 50000;
	private long maxCaptureBytes = 256L * 1024 * 1024;
	private long correlationTimeoutMs = 2000;
	private boolean wasConnected;
	private String recentServerInventoryEventId;
	private String recentServerInventoryPacketId;
	private long recentServerInventoryPacketNs;
	
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
		event.put("packetObjectCorrelationId",
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
			event.put("transactionId", transactionId);
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
			&& isInventorySyncPacket(packet.getClass().getSimpleName()))
		{
			recentServerInventoryEventId = eventId;
			recentServerInventoryPacketId =
				String.valueOf(event.get("packetId"));
			recentServerInventoryPacketNs = System.nanoTime();
		}
		append(event);
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
	public synchronized void tick(boolean playerInventory,
		boolean openedContainers, boolean equipment, boolean droppedItems,
		boolean components, boolean fullContent, boolean autoAnomalyDetection,
		int maxContentBytes, int radius, int snapshotFrequency)
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
		if(clientTick % Math.max(1, snapshotFrequency) != 0)
			return;
		
		Map<String, ItemStackForensics.Snapshot> now = new LinkedHashMap<>();
		Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
		AbstractContainerMenu inventoryMenu = mc.player.inventoryMenu;
		AbstractContainerMenu activeMenu = mc.player.containerMenu;
		if(playerInventory)
			captureMenu("player_inventory", inventoryMenu, now, contexts,
				components, fullContent, maxContentBytes);
		if(openedContainers && activeMenu != inventoryMenu)
			captureMenu("opened_container", activeMenu, now, contexts,
				components, fullContent, maxContentBytes);
		if(equipment)
		{
			for(EquipmentSlot slot : EquipmentSlot.values())
			{
				ItemStack stack = mc.player.getItemBySlot(slot);
				String key = "equipment:" + slot.getName();
				captureSlot(key, stack, now, contexts, components, fullContent,
					maxContentBytes);
				contexts.get(key).put("logicalSlot",
					"equipment." + slot.getName());
			}
		}
		compareSlots(previousSlots, now, contexts, autoAnomalyDetection);
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
		if(droppedItems)
			captureNearbyItems(mc, radius, components, fullContent,
				maxContentBytes);
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
			contexts.get(key).put("rawSlot", menuSlot.getContainerSlot());
			contexts.get(key).put("logicalSlot",
				logicalSlotFor(menuSlot, Minecraft.getInstance()));
			contexts.get(key).put("stateId", stateId);
		}
		String carriedKey = prefix + ":carried";
		captureSlot(carriedKey, menu.getCarried(), now, contexts, components,
			fullContent, maxContentBytes);
		contexts.get(carriedKey).put("logicalSlot", "carried_item");
		contexts.get(carriedKey).put("stateId", stateId);
	}
	
	private static String logicalSlotFor(
		net.minecraft.world.inventory.Slot slot, Minecraft mc)
	{
		int index = slot.index;
		if(mc.player != null && slot.container == mc.player.getInventory())
		{
			if(index >= 0 && index <= 8)
				return "hotbar[" + index + "]";
			if(index >= 9 && index <= 35)
				return "main_inventory[" + (index - 9) + "]";
			if(index >= 36 && index <= 39)
				return "equipment_inventory[" + (index - 36) + "]";
			if(index == 40)
				return "offhand";
			return "player_inventory[" + index + "]";
		}
		return slot.container.getClass().getSimpleName() + "[" + index + "]";
	}
	
	private void captureSlot(String key, ItemStack stack,
		Map<String, ItemStackForensics.Snapshot> now,
		Map<String, Map<String, Object>> contexts, boolean components,
		boolean fullContent, int maxContentBytes)
	{
		now.put(key, ItemStackForensics.capture(stack, components, fullContent,
			maxContentBytes));
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
		Set<String> keys = new LinkedHashSet<>(before.keySet());
		keys.addAll(after.keySet());
		for(String key : keys)
		{
			ItemStackForensics.Snapshot old = before.get(key);
			ItemStackForensics.Snapshot current = after.get(key);
			if(old != null && current != null
				&& old.fingerprint().equals(current.fingerprint()))
				continue;
			Map<String, Object> event = baseEvent("item_mutation");
			event.put("slotKey", key);
			event.put("slotContext", currentContexts.getOrDefault(key,
				previousSlotContexts.getOrDefault(key, Map.of())));
			event.put("captureStage", "client_tick_snapshot");
			event.put("stateSource", "client_observed_after_tick");
			boolean followsServerInventoryPacket =
				isRecentServerInventoryPacket();
			if(followsServerInventoryPacket)
			{
				event.put("relatedServerPacketEventId",
					recentServerInventoryEventId);
				event.put("relatedServerPacketId",
					recentServerInventoryPacketId);
				event.put("correlationConfidence", "MEDIUM");
				event.put("correlationBasis",
					"inventory packet observed before this client-tick snapshot");
			}
			event.put("itemBefore", old == null ? null : old.details());
			event.put("itemAfter", current == null ? null : current.details());
			event.put("fingerprintBefore",
				old == null ? null : old.fingerprint());
			event.put("fingerprintAfter",
				current == null ? null : current.fingerprint());
			event.put("componentDiff", componentDiff(old, current));
			if(old == null)
				event.put("change", "observed_or_opened");
			else if(current == null)
				event.put("change", "no_longer_observed");
			else
				event.put("change", "state_changed");
			append(event);
			if(autoAnomalyDetection && followsServerInventoryPacket)
			{
				Map<String, Object> anomaly = baseEvent("anomaly_detection");
				anomaly.put("anomalyType", "server_state_change_candidate");
				anomaly.put("severity", "informational");
				anomaly.put("relatedSlotKey", key);
				anomaly.put("relatedServerPacketEventId",
					recentServerInventoryEventId);
				anomaly.put("interpretation",
					"A local inventory change followed an observed server inventory packet within the configured window.");
				anomaly.put("cause", "unknown");
				append(anomaly);
			}
		}
	}
	
	private boolean isRecentServerInventoryPacket()
	{
		return recentServerInventoryEventId != null && System.nanoTime()
			- recentServerInventoryPacketNs <= correlationTimeoutMs
				* 1_000_000L;
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
			if(java.util.Objects.equals(old, current))
				continue;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("componentId", id);
			entry.put("beforeState", componentState(old));
			entry.put("afterState", componentState(current));
			entry.put("before", old);
			entry.put("after", current);
			diff.add(entry);
		}
		return diff;
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
	
	private void captureNearbyItems(Minecraft mc, int radius,
		boolean components, boolean fullContent, int maxContentBytes)
	{
		AABB box = mc.player.getBoundingBox().inflate(Math.max(1, radius));
		Set<UUID> observed = new LinkedHashSet<>();
		for(var entity : mc.level.getEntities(mc.player, box,
			e -> e instanceof ItemEntity item && item.isAlive()))
		{
			ItemEntity item = (ItemEntity)entity;
			UUID uuid = item.getUUID();
			observed.add(uuid);
			ItemStackForensics.Snapshot snapshot = ItemStackForensics.capture(
				item.getItem(), components, fullContent, maxContentBytes);
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
			event.put("action", previous == null ? "observed_nearby"
				: changed ? "item_changed" : "moved_nearby");
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
				event.put("action", "no_longer_in_monitoring_radius");
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
		event.put("schemaVersion", 1);
		event.put("sessionId", sessionId);
		event.put("eventId", "itemforensics-" + (eventCount + 1));
		event.put("transactionId", null);
		event.put("timestamp", Instant.now().toString());
		event.put("monotonicNs", System.nanoTime());
		event.put("clientTick", clientTick);
		event.put("eventType", type);
		return event;
	}
	
	private synchronized void append(Map<String, Object> event)
	{
		String serialized = GSON.toJson(event);
		long bytes =
			serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
				+ 1L;
		if(eventCount >= maxCaptureRecords || pending.size() >= maxPendingEvents
			|| capturedBytes + bytes > maxCaptureBytes)
		{
			droppedEvents++;
			totalDroppedEvents++;
			return;
		}
		eventCount++;
		capturedBytes += bytes;
		pending.addLast(serialized);
	}
	
	public synchronized void setMaxPendingEvents(int maximum)
	{
		maxPendingEvents = Math.max(128, maximum);
	}
	
	public synchronized void startNewSession()
	{
		pending.clear();
		pendingTransactions.clear();
		previousSlots.clear();
		previousSlotContexts.clear();
		nearbyItems.clear();
		sessionId = UUID.randomUUID().toString();
		startedAt = Instant.now().toString();
		clientTick = 0;
		eventCount = 0;
		capturedBytes = 0;
		droppedEvents = 0;
		totalDroppedEvents = 0;
		lastMenuKey = null;
		wasConnected = false;
		recentServerInventoryEventId = null;
		recentServerInventoryPacketId = null;
		recentServerInventoryPacketNs = 0;
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
			|| value.contains("recipe") || value.contains("carrieditem");
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
			result.add(pending.removeFirst());
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
	
	public synchronized String buildSummary(boolean redactServerAddress,
		Map<String, Object> captureSettings)
	{
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("schemaVersion", 1);
		summary.put("sessionId", sessionId);
		summary.put("captureStartedAt", startedAt);
		summary.put("captureEndedAt", Instant.now().toString());
		summary.put("minecraftVersion",
			net.minecraft.SharedConstants.getCurrentVersion().id());
		summary.put("clientVersion", WurstClient.VERSION);
		summary.put("serverAddress", getServerAddress(redactServerAddress));
		summary.put("capturedEventCount", eventCount);
		summary.put("capturedBytes", capturedBytes);
		summary.put("maximumCaptureBytes", maxCaptureBytes);
		summary.put("pendingEventCount", pending.size());
		summary.put("droppedEventCount", totalDroppedEvents);
		summary.put("captureComplete",
			totalDroppedEvents == 0 && droppedEvents == 0);
		summary.put("captureSettings", captureSettings);
		summary.put("eventStreamFile", "item-forensics-<session>.jsonl");
		summary.put("notes",
			List.of("Packet observations record interception stages;"
				+ " a client send hook does not prove network transmission.",
				"Inventory snapshots are local observations after client ticks;"
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
			.append(getServerAddress(redactServerAddress))
			.append("\n- Captured records: ").append(eventCount)
			.append("\n- Captured records: ").append(eventCount)
			.append("\n- Captured bytes: ").append(capturedBytes)
			.append("\n- Dropped records: ").append(totalDroppedEvents)
			.append("\n- Capture complete: ")
			.append(totalDroppedEvents == 0 && droppedEvents == 0)
			.append("\n- Event timeline: `session-").append(sessionId)
			.append(".jsonl`\n\n## Capture settings\n");
		captureSettings.forEach((key, value) -> report.append("- ").append(key)
			.append(": `").append(value).append("`\n"));
		report.append(
			"\nPacket records identify the client interception stage. A send-stage observation does not prove transmission. Temporal packet links are candidates, and the client cannot establish server-side causes.\n");
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
}
