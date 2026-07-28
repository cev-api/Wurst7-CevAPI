/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.client.gui.Font;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.text.WText;

public final class EntityCountHack extends Hack
	implements UpdateListener, RenderListener, PacketInputListener
{
	private final EnumSetting<ViewMode> viewMode = new EnumSetting<>("View",
		WText.literal(
			"360 counts chunks all around you.\nPOV only counts chunks in front of where you are looking."),
		ViewMode.values(), ViewMode.POV);
	
	private final SliderSetting chunkRadius = new SliderSetting("Chunk range",
		WText.literal(
			"How far out to count chunks.\n1 means only your current chunk."),
		5, 1, 64, 1, v -> {
			int radius = (int)v;
			int diameter = radius * 2 - 1;
			return diameter + "x" + diameter;
		});
	
	private final CheckboxSetting onlyAboveThreshold = new CheckboxSetting(
		"Only above threshold",
		WText.literal(
			"Only draw chunk labels when the chunk count is at or above the threshold."),
		false);
	
	private final CheckboxSetting hideZero = new CheckboxSetting("Hide zero",
		WText.literal("Hide chunk labels that would show 0 entities."), true);
	
	private final CheckboxSetting highlightHighest =
		new CheckboxSetting("Highlight highest",
			WText.literal("Highlight the highest counted chunk label in red."),
			true);
	
	private final CheckboxSetting showLazyChunks = new CheckboxSetting(
		"Show lazy chunks",
		WText.literal(
			"Show labels for packet-tracked chunks outside the currently rendered area."),
		true);
	
	private final SliderSetting threshold = new SliderSetting("Threshold",
		WText.literal(
			"Minimum number of entities required before a label is shown when threshold mode is enabled."),
		1, 0, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting thresholdPersistence = new SliderSetting(
		"Threshold persistence",
		WText.literal(
			"Ticks a threshold must remain breached before it is detected."),
		30, 0, 100, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final CheckboxSetting villagersFilter =
		new CheckboxSetting("Filter: Villagers",
			WText.literal("Alert/detect villagers separately."), false);
	private final SliderSetting villagersThreshold = new SliderSetting(
		"Villagers threshold", WText.literal("Minimum villagers per chunk."),
		10, 0, 200, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting mobsFilter = new CheckboxSetting(
		"Filter: Mobs", WText.literal("Alert/detect mobs separately."), false);
	private final SliderSetting mobsThreshold = new SliderSetting(
		"Mobs threshold", WText.literal("Minimum mobs per chunk."), 30, 0, 200,
		1, ValueDisplay.INTEGER);
	private final CheckboxSetting animalsFilter =
		new CheckboxSetting("Filter: Animals",
			WText.literal("Alert/detect animals separately."), false);
	private final SliderSetting animalsThreshold = new SliderSetting(
		"Animals threshold", WText.literal("Minimum animals per chunk."), 20, 0,
		200, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting farmableAnimalsFilter =
		new CheckboxSetting("Filter: Farmable animals",
			WText.literal("Alert/detect chickens, cows, and sheep separately."),
			false);
	private final SliderSetting farmableAnimalsThreshold =
		new SliderSetting("Farmable animals threshold",
			WText.literal("Minimum chickens, cows, and sheep per chunk."), 10,
			0, 200, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting otherFilter =
		new CheckboxSetting("Filter: Other",
			WText.literal("Alert/detect a user-specified entity."), false);
	private final TextFieldSetting otherEntity =
		new TextFieldSetting("Other entity",
			"Entity ID or name to count (for example, warden).", "");
	private final SliderSetting otherThreshold =
		new SliderSetting("Other threshold",
			WText.literal("Minimum matching entities per chunk."), 3, 0, 200, 1,
			ValueDisplay.INTEGER);
	
	private final SliderSetting labelScale = new SliderSetting("Label scale",
		WText.literal("Size multiplier for the chunk labels."), 1, 0.5, 3, 0.05,
		ValueDisplay.PERCENTAGE);
	private final SliderSetting labelHeight = new SliderSetting("Label height",
		WText.literal("Vertical offset for count labels and chunk tracers."), 0,
		-64, 128, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final CheckboxSetting showTracers = new CheckboxSetting(
		"Show chunk tracers",
		WText.literal("Draw tracers to chunks whose labels are shown."), false);
	private final CheckboxSetting chatAlerts =
		new CheckboxSetting("Chat alerts",
			WText.literal(
				"Send a chat alert when a chunk reaches the threshold."),
			false);
	private final CheckboxSetting soundAlerts = new CheckboxSetting(
		"Sound alerts",
		WText.literal(
			"Play sounds for threshold chunks. Only works with Only above threshold."),
		false);
	
	private final HashMap<Integer, TrackedEntity> trackedEntities =
		new HashMap<>();
	private final HashMap<ChunkPos, Integer> chunkCounts = new HashMap<>();
	private final ArrayList<Packet<?>> pendingPackets = new ArrayList<>();
	private final ArrayList<ChunkLabel> visibleLabels = new ArrayList<>();
	private final ArrayList<ChunkLabel> lazyLabels = new ArrayList<>();
	private static final int ALERT_AREA_RADIUS_CHUNKS = 2;
	private final HashSet<String> alertedThresholdFilters = new HashSet<>();
	private final HashMap<String, Long> thresholdAboveSince = new HashMap<>();
	private final EnumMap<CountFilter, HashMap<ChunkPos, Integer>> filterCounts =
		new EnumMap<>(CountFilter.class);
	private int totalCount;
	private long tickCounter;
	private Object lastLevel;
	
	public EntityCountHack()
	{
		super("EntityCount");
		setCategory(Category.TOOLS);
		for(CountFilter filter : CountFilter.values())
			filterCounts.put(filter, new HashMap<>());
		addSetting(viewMode);
		addSetting(chunkRadius);
		addSetting(onlyAboveThreshold);
		addSetting(hideZero);
		addSetting(highlightHighest);
		addSetting(showLazyChunks);
		addSetting(threshold);
		addSetting(thresholdPersistence);
		addSetting(villagersFilter);
		addSetting(villagersThreshold);
		addSetting(mobsFilter);
		addSetting(mobsThreshold);
		addSetting(animalsFilter);
		addSetting(animalsThreshold);
		addSetting(farmableAnimalsFilter);
		addSetting(farmableAnimalsThreshold);
		addSetting(otherFilter);
		addSetting(otherEntity);
		addSetting(otherThreshold);
		addSetting(labelScale);
		addSetting(labelHeight);
		addSetting(showTracers);
		addSetting(chatAlerts);
		addSetting(soundAlerts);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + totalCount + "]";
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		clearTracking();
		tickCounter = 0;
		lastLevel = MC.level;
		refreshCounts();
		updateThresholdPersistence();
		checkThresholdAlerts();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		clearTracking();
		lastLevel = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level != lastLevel)
		{
			clearTracking();
			lastLevel = MC.level;
		}
		
		applyPendingPackets();
		refreshCounts();
		tickCounter++;
		updateThresholdPersistence();
		checkThresholdAlerts();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		pendingPackets.add(event.getPacket());
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.player == null || MC.level == null)
			return;
		
		for(ChunkLabel label : visibleLabels)
			drawWorldLabel(matrices, Integer.toString(label.count), label.x,
				label.y, label.z, colorForLabel(label));
		
		for(ChunkLabel label : lazyLabels)
			drawWorldLabel(matrices, Integer.toString(label.count), label.x,
				label.y, label.z, colorForLabel(label));
		
		if(showTracers.isChecked())
		{
			ArrayList<Vec3> ends = new ArrayList<>();
			for(ChunkLabel label : visibleLabels)
				ends.add(new Vec3(label.x, label.y, label.z));
			for(ChunkLabel label : lazyLabels)
				ends.add(new Vec3(label.x, label.y, label.z));
			if(!ends.isEmpty())
				RenderUtils.drawTracers("EntityCount", matrices, partialTicks,
					ends, 0xFFFFFF55, false);
		}
	}
	
	private void clearTracking()
	{
		trackedEntities.clear();
		chunkCounts.clear();
		for(HashMap<ChunkPos, Integer> counts : filterCounts.values())
			counts.clear();
		pendingPackets.clear();
		visibleLabels.clear();
		lazyLabels.clear();
		alertedThresholdFilters.clear();
		thresholdAboveSince.clear();
		totalCount = 0;
	}
	
	private void applyPendingPackets()
	{
		if(pendingPackets.isEmpty())
			return;
		
		ArrayList<Packet<?>> packets = new ArrayList<>(pendingPackets);
		pendingPackets.clear();
		for(Packet<?> packet : packets)
			rememberPacket(packet);
	}
	
	private void refreshCounts()
	{
		visibleLabels.clear();
		lazyLabels.clear();
		chunkCounts.clear();
		for(HashMap<ChunkPos, Integer> counts : filterCounts.values())
			counts.clear();
		totalCount = 0;
		
		if(MC.player == null || MC.level == null)
			return;
			
		// Packet sightings are only provisional. Once the client removes an
		// entity (or never actually creates it), getEntity() returns null. Drop
		// those entries before building counts so unloaded/ghost entities
		// cannot
		// trigger labels, chat alerts, sounds, or AutoFly stops.
		trackedEntities.entrySet()
			.removeIf(entry -> MC.level.getEntity(entry.getKey()) == null);
		
		// Keep nearby entities exact by syncing them from the live world.
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(entity == null || entity.isRemoved())
				continue;
			
			trackedEntities.put(entity.getId(),
				new TrackedEntity(entity.chunkPosition(), false));
			countFilteredEntity(entity);
		}
		
		for(TrackedEntity tracked : new ArrayList<>(trackedEntities.values()))
			chunkCounts.merge(tracked.chunkPos(), 1, Integer::sum);
		
		buildLabels();
	}
	
	private void countFilteredEntity(Entity entity)
	{
		ChunkPos chunk = entity.chunkPosition();
		String entityId =
			BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		incrementFilter(CountFilter.ALL, chunk);
		if(entityId.equals("minecraft:villager"))
			incrementFilter(CountFilter.VILLAGERS, chunk);
		if(entity instanceof Mob)
			incrementFilter(CountFilter.MOBS, chunk);
		if(entity instanceof Animal)
			incrementFilter(CountFilter.ANIMALS, chunk);
		if(entityId.equals("minecraft:chicken")
			|| entityId.equals("minecraft:cow")
			|| entityId.equals("minecraft:sheep"))
			incrementFilter(CountFilter.FARMABLE, chunk);
		if(matchesOtherEntity(entity))
			incrementFilter(CountFilter.OTHER, chunk);
	}
	
	private void incrementFilter(CountFilter filter, ChunkPos chunk)
	{
		filterCounts.get(filter).merge(chunk, 1, Integer::sum);
	}
	
	private boolean matchesOtherEntity(Entity entity)
	{
		String query = otherEntity.getValue();
		if(query == null || query.isBlank())
			return false;
		String id =
			BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		return containsIgnoreCase(id, query)
			|| containsIgnoreCase(entity.getName().getString(), query);
	}
	
	private boolean containsIgnoreCase(String value, String query)
	{
		return value != null && query != null
			&& value.toLowerCase(java.util.Locale.ROOT)
				.contains(query.trim().toLowerCase(java.util.Locale.ROOT));
	}
	
	private void buildLabels()
	{
		ChunkPos center = MC.player.chunkPosition();
		int radius = getRadius();
		int renderDistance =
			Math.max(2, MC.options.getEffectiveRenderDistance());
		double labelY = MC.player.getEyeY() + labelHeight.getValue();
		ArrayList<ChunkLabel> allLabels = new ArrayList<>();
		
		for(int dx = -radius; dx <= radius; dx++)
			for(int dz = -radius; dz <= radius; dz++)
			{
				int chunkX = center.x() + dx;
				int chunkZ = center.z() + dz;
				ChunkPos pos = new ChunkPos(chunkX, chunkZ);
				if(!isInSelectedView(pos))
					continue;
				
				boolean renderable = Math.abs(dx) <= renderDistance
					&& Math.abs(dz) <= renderDistance
					&& MC.level.hasChunk(chunkX, chunkZ);
				if(!renderable)
					continue;
				
				int count = chunkCounts.getOrDefault(pos, 0);
				totalCount += count;
				if(!shouldRenderLabel(count))
					continue;
				
				ChunkLabel label = new ChunkLabel(pos, count, labelY, false);
				visibleLabels.add(label);
				allLabels.add(label);
			}
		
		if(showLazyChunks.isChecked())
			for(Map.Entry<ChunkPos, Integer> entry : chunkCounts.entrySet())
			{
				ChunkPos pos = entry.getKey();
				int dx = pos.x() - center.x();
				int dz = pos.z() - center.z();
				if(Math.abs(dx) > radius || Math.abs(dz) > radius)
					continue;
				if(!isInSelectedView(pos))
					continue;
				if(Math.abs(dx) <= renderDistance
					&& Math.abs(dz) <= renderDistance
					&& MC.level.hasChunk(pos.x(), pos.z()))
					continue;
				
				int count = entry.getValue();
				totalCount += count;
				if(!shouldRenderLabel(count))
					continue;
				
				ChunkLabel label = new ChunkLabel(pos, count, labelY, true);
				lazyLabels.add(label);
				allLabels.add(label);
			}
		
		if(highlightHighest.isChecked())
			highlightHighestLabels(allLabels);
	}
	
	private boolean shouldRenderLabel(int count)
	{
		if(hideZero.isChecked() && count == 0)
			return false;
		
		return !onlyAboveThreshold.isChecked()
			|| count >= threshold.getValueI();
	}
	
	private void checkThresholdAlerts()
	{
		if(MC.player == null)
			return;
		
		ChunkPos playerChunk = MC.player.chunkPosition();
		List<ThresholdMatch> matches = getThresholdMatches();
		HashSet<String> nearbyFilters = new HashSet<>();
		for(ThresholdMatch match : matches)
			if(isInAlertArea(playerChunk, match.chunk()))
				nearbyFilters.add(match.filter());
				
		// A filter is re-armed only after leaving its current detection area.
		// This prevents one alert per neighbouring chunk while still allowing
		// the same alert when the player leaves and later comes back.
		alertedThresholdFilters.retainAll(nearbyFilters);
		for(ThresholdMatch match : matches)
		{
			if(!isInAlertArea(playerChunk, match.chunk()))
				continue;
			String filterKey = match.filter();
			if(!alertedThresholdFilters.add(filterKey))
				continue;
			ChunkPos chunk = match.chunk();
			int count = match.count();
			int x = chunk.getMiddleBlockX();
			int y = MC.player == null ? 0 : MC.player.blockPosition().getY();
			int z = chunk.getMiddleBlockZ();
			if(MC.level != null)
				for(Entity entity : MC.level.entitiesForRendering())
					if(entity != null && !entity.isRemoved()
						&& entity.chunkPosition().equals(chunk))
					{
						var pos = entity.blockPosition();
						x = pos.getX();
						y = pos.getY();
						z = pos.getZ();
						break;
					}
			if(chatAlerts.isChecked())
				ChatUtils.message("EntityCount: " + match.filter()
					+ " threshold " + match.threshold() + " broken (" + count
					+ " entities detected at " + x + ", " + y + ", " + z
					+ ").");
			
			if(soundAlerts.isChecked() && onlyAboveThreshold.isChecked()
				&& MC.level != null && MC.player != null)
			{
				var sound = BuiltInRegistries.SOUND_EVENT.getValue(
					Identifier.parse("minecraft:block.note_block.pling"));
				if(sound != null)
					MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
						MC.player.getZ(), sound, SoundSource.PLAYERS, 1.0F,
						1.0F, false);
			}
		}
	}
	
	private void updateThresholdPersistence()
	{
		HashSet<String> current = new HashSet<>();
		for(ThresholdMatch match : getRawThresholdMatches())
		{
			String key = thresholdKey(match);
			current.add(key);
			thresholdAboveSince.putIfAbsent(key, tickCounter);
		}
		
		thresholdAboveSince.keySet().retainAll(current);
	}
	
	private String thresholdKey(ThresholdMatch match)
	{
		return match.filter() + "@" + match.chunk();
	}
	
	private boolean isInAlertArea(ChunkPos center, ChunkPos candidate)
	{
		return Math.abs(candidate.x() - center.x()) <= ALERT_AREA_RADIUS_CHUNKS
			&& Math.abs(candidate.z() - center.z()) <= ALERT_AREA_RADIUS_CHUNKS;
	}
	
	public int getThreshold()
	{
		return threshold.getValueI();
	}
	
	public List<ThresholdMatch> getThresholdMatches()
	{
		ArrayList<ThresholdMatch> qualified = new ArrayList<>();
		for(ThresholdMatch match : getRawThresholdMatches())
		{
			Long since = thresholdAboveSince.get(thresholdKey(match));
			if(since != null
				&& tickCounter - since >= thresholdPersistence.getValueI())
				qualified.add(match);
		}
		return qualified;
	}
	
	private List<ThresholdMatch> getRawThresholdMatches()
	{
		if(!isEnabled())
			return List.of();
		ArrayList<ThresholdMatch> matches = new ArrayList<>();
		addThresholdMatches(matches, CountFilter.ALL, threshold.getValueI(),
			true, "all entities");
		addThresholdMatches(matches, CountFilter.VILLAGERS,
			villagersThreshold.getValueI(), villagersFilter.isChecked(),
			"villagers");
		addThresholdMatches(matches, CountFilter.MOBS,
			mobsThreshold.getValueI(), mobsFilter.isChecked(), "mobs");
		addThresholdMatches(matches, CountFilter.ANIMALS,
			animalsThreshold.getValueI(), animalsFilter.isChecked(), "animals");
		addThresholdMatches(matches, CountFilter.FARMABLE,
			farmableAnimalsThreshold.getValueI(),
			farmableAnimalsFilter.isChecked(), "farmable animals");
		addThresholdMatches(matches, CountFilter.OTHER,
			otherThreshold.getValueI(),
			otherFilter.isChecked() && !otherEntity.getValue().isBlank(),
			otherEntity.getValue().trim());
		return List.copyOf(matches);
	}
	
	private void addThresholdMatches(List<ThresholdMatch> output,
		CountFilter filter, int minimum, boolean enabled, String name)
	{
		if(!enabled)
			return;
		Map<ChunkPos, Integer> counts =
			filter == CountFilter.ALL ? chunkCounts : filterCounts.get(filter);
		for(Map.Entry<ChunkPos, Integer> entry : counts.entrySet())
			if(entry.getValue() >= minimum)
				output.add(new ThresholdMatch(entry.getKey(), entry.getValue(),
					name, minimum));
	}
	
	public Map<ChunkPos, Integer> getChunksAtOrAboveThreshold()
	{
		HashMap<ChunkPos, Integer> result = new HashMap<>();
		for(ThresholdMatch match : getThresholdMatches())
			result.merge(match.chunk(), match.count(), Math::max);
		return Map.copyOf(result);
	}
	
	private void highlightHighestLabels(ArrayList<ChunkLabel> allLabels)
	{
		int highest = Integer.MIN_VALUE;
		for(ChunkLabel label : allLabels)
			highest = Math.max(highest, label.count);
		
		if(highest == Integer.MIN_VALUE)
			return;
		
		for(ChunkLabel label : allLabels)
			if(label.count == highest)
				label.highlight = true;
	}
	
	private boolean isInSelectedView(ChunkPos pos)
	{
		if(viewMode.getSelected() == ViewMode.ALL)
			return true;
		if(pos.equals(MC.player.chunkPosition()))
			return true;
		
		Vec3 look = MC.player.getLookAngle();
		double chunkCenterX = pos.getMiddleBlockX() + 0.5;
		double chunkCenterZ = pos.getMiddleBlockZ() + 0.5;
		double dx = chunkCenterX - MC.player.getX();
		double dz = chunkCenterZ - MC.player.getZ();
		return dx * look.x + dz * look.z >= 0;
	}
	
	private int getRadius()
	{
		return chunkRadius.getValueI() - 1;
	}
	
	private void rememberPacket(Packet<?> packet)
	{
		if(packet instanceof ClientboundBundlePacket bundle)
		{
			for(Packet<?> subPacket : bundle.subPackets())
				rememberPacket(subPacket);
			return;
		}
		
		if(packet instanceof ClientboundAddEntityPacket add)
		{
			ChunkPos chunk = chunkFromCoordinates(add.getX(), add.getZ());
			trackedEntities.put(add.getId(), new TrackedEntity(chunk, true));
			return;
		}
		
		if(packet instanceof ClientboundRemoveEntitiesPacket remove)
		{
			for(int id : remove.getEntityIds())
				trackedEntities.remove(id);
			return;
		}
		
		if(packet instanceof ClientboundTeleportEntityPacket tp)
		{
			ChunkPos chunk = chunkFromTeleport(tp);
			if(chunk != null)
				updateTrackedEntity(tp.id(), chunk);
			return;
		}
		
		if(packet instanceof ClientboundEntityPositionSyncPacket sync)
		{
			ChunkPos chunk = chunkFromPositionSync(sync);
			if(chunk != null)
				updateTrackedEntity(sync.id(), chunk);
			return;
		}
		
		if(packet instanceof ClientboundMoveEntityPacket move)
		{
			int entityId = readPacketEntityId(move);
			if(entityId < 0)
				return;
			
			Entity live =
				MC.level != null ? MC.level.getEntity(entityId) : null;
			if(live != null)
				updateTrackedEntity(entityId, live.chunkPosition());
		}
	}
	
	private void updateTrackedEntity(int entityId, ChunkPos newChunk)
	{
		if(entityId < 0 || newChunk == null)
			return;
		
		TrackedEntity existing = trackedEntities.get(entityId);
		if(existing == null)
		{
			trackedEntities.put(entityId, new TrackedEntity(newChunk, true));
			return;
		}
		
		trackedEntities.put(entityId,
			new TrackedEntity(newChunk, existing.isLazy()));
	}
	
	private ChunkPos chunkFromTeleport(ClientboundTeleportEntityPacket packet)
	{
		Object change = tryInvokeNoArg(packet, "change");
		Object pos = tryInvokeNoArg(change, "position");
		if(pos == null)
			return null;
		
		Double x = readDoubleByMethods(pos, "x", "getX");
		Double z = readDoubleByMethods(pos, "z", "getZ");
		if(x == null || z == null)
			return null;
		
		return chunkFromCoordinates(x, z);
	}
	
	private ChunkPos chunkFromPositionSync(
		ClientboundEntityPositionSyncPacket packet)
	{
		Object values = tryInvokeNoArg(packet, "values");
		Object pos = tryInvokeNoArg(values, "position");
		if(pos == null)
			return null;
		
		Double x = readDoubleByMethods(pos, "x", "getX");
		Double z = readDoubleByMethods(pos, "z", "getZ");
		if(x == null || z == null)
			return null;
		
		return chunkFromCoordinates(x, z);
	}
	
	private ChunkPos chunkFromCoordinates(double x, double z)
	{
		return new ChunkPos(((int)Math.floor(x)) >> 4,
			((int)Math.floor(z)) >> 4);
	}
	
	private int readPacketEntityId(Object packet)
	{
		Integer id =
			readIntByMethods(packet, "id", "getId", "entityId", "getEntityId");
		return id == null ? -1 : id;
	}
	
	private Integer readIntByMethods(Object obj, String... names)
	{
		for(String name : names)
		{
			Object value = tryInvokeNoArg(obj, name);
			if(value instanceof Number number)
				return number.intValue();
		}
		return null;
	}
	
	private Double readDoubleByMethods(Object obj, String... names)
	{
		for(String name : names)
		{
			Object value = tryInvokeNoArg(obj, name);
			if(value instanceof Number number)
				return number.doubleValue();
		}
		return null;
	}
	
	private Object tryInvokeNoArg(Object obj, String methodName)
	{
		if(obj == null || methodName == null)
			return null;
		
		try
		{
			var method = obj.getClass().getMethod(methodName);
			return method.invoke(obj);
			
		}catch(ReflectiveOperationException ignored)
		{
			return null;
		}
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		
		// Anchor to a near point so perspective doesn't shrink text.
		Vec3 target = new Vec3(x, y, z);
		Vec3 dir = target.subtract(cam);
		double dist = dir.length();
		double lx = x;
		double ly = y;
		double lz = z;
		if(dist > 1.0)
		{
			double anchor = Math.min(dist, 12.0);
			Vec3 anchored = cam.add(dir.scale(anchor / dist));
			lx = anchored.x;
			ly = anchored.y;
			lz = anchored.z;
		}
		matrices.translate(lx - cam.x, ly - cam.y, lz - cam.z);
		
		Entity camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		
		matrices.mulPose(Axis.YP.rotationDegrees(180.0F));
		float scale = 0.025F * RenderUtils
			.getCappedWorldLabelScale(labelScale.getValueF(), dist);
		
		matrices.scale(scale, -scale, scale);
		
		Font font = MC.font;
		float halfWidth = font.width(text) / 2F;
		int bgAlpha = (int)(MC.options.getBackgroundOpacity(0.25F) * 255) << 24;
		var matrix = matrices.last().pose();
		int stroke = 0xCC000000;
		net.wurstclient.util.RenderUtils.drawOutlinedTextInBatch(font, text,
			-halfWidth, 0, argb, stroke, matrix, Font.DisplayMode.SEE_THROUGH,
			bgAlpha, 0xF000F0);
		matrices.popPose();
	}
	
	private int colorForLabel(ChunkLabel label)
	{
		if(label.highlight)
			return 0xFFFF5555;
		
		int count = label.count;
		boolean lazy = label.lazy;
		if(count >= threshold.getValueI())
			return lazy ? 0xFF5599FF : 0xFFFFFF55;
		return lazy ? 0xFF3366FF : 0xFFFFFFFF;
	}
	
	public record ThresholdMatch(ChunkPos chunk, int count, String filter,
		int threshold)
	{}
	
	private enum CountFilter
	{
		ALL,
		VILLAGERS,
		MOBS,
		ANIMALS,
		FARMABLE,
		OTHER
	}
	
	private enum ViewMode
	{
		ALL("360"),
		POV("POV");
		
		private final String name;
		
		ViewMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private static final class ChunkLabel
	{
		private final int count;
		private final boolean lazy;
		private final double x;
		private final double y;
		private final double z;
		private boolean highlight;
		
		private ChunkLabel(ChunkPos pos, int count, double y, boolean lazy)
		{
			this.count = count;
			this.lazy = lazy;
			x = pos.getMiddleBlockX() + 0.5;
			this.y = y;
			z = pos.getMiddleBlockZ() + 0.5;
		}
	}
	
	private record TrackedEntity(ChunkPos chunkPos, boolean isLazy)
	{}
}
