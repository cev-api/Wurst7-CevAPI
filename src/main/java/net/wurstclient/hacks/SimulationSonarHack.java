/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;

/**
 * Reports unexplained outer-ring simulation; it cannot identify the ticket
 * type.
 */
public final class SimulationSonarHack extends Hack
	implements PacketInputListener, UpdateListener, RenderListener
{
	public enum Confidence
	{
		Low,
		Medium,
		High
	}
	
	public enum ShapeMode
	{
		Sides,
		Lines,
		Both
	}
	
	private enum Evidence
	{
		ENTITY,
		MULTIPLE_ENTITIES,
		FALLING_BLOCK,
		FLUID,
		RANDOM_TICK,
		FIRE,
		ITEM,
		LIGHTNING,
		BLOCK,
		WEAK
	}
	
	private enum MovementMode
	{
		STATIONARY,
		MOVING,
		FLYING
	}
	
	private final CheckboxSetting render = new CheckboxSetting(
		"Render detected chunks", "Draws detected simulation chunks.", true);
	private final CheckboxSetting notify = new CheckboxSetting("Notify in chat",
		"Reports new detections and confidence escalations.", true);
	private final EnumSetting<Confidence> minimumRender = new EnumSetting<>(
		"Minimum render confidence", Confidence.values(), Confidence.Low);
	private final EnumSetting<Confidence> minimumNotify =
		new EnumSetting<>("Minimum notification confidence",
			Confidence.values(), Confidence.Medium);
	private final SliderSetting window = seconds("Detection window", 8, 2, 30);
	private final SliderSetting expiry =
		seconds("Detection expiry", 300, 30, 1800);
	private final SliderSetting renderHeight = new SliderSetting(
		"Render-Height-Offset", 0, -319, 319, 1, ValueDisplay.INTEGER);
	private final SliderSetting cooldown =
		seconds("Notification cooldown", 20, 1, 300);
	private final SliderSetting stationary =
		seconds("Stationary grace time", 3, 0, 15);
	private final SliderSetting pathSuppression =
		seconds("Recent-player-path suppression time", 15, 0, 60);
	private final SliderSetting loadGrace =
		seconds("Newly loaded chunk grace time", 15, 0, 120);
	// False-positive suppression for local movement and teleports.
	private final CheckboxSetting requireStationary =
		new CheckboxSetting("Require stationary player",
			"Only scores evidence after the local player has stopped moving.",
			true);
	private final SliderSetting teleportGrace =
		seconds("Teleport grace time", 12, 0, 60);
	private final CheckboxSetting ignorePlayers =
		new CheckboxSetting("Ignore activity explained by visible players",
			"Suppresses activity inside a visible player's simulation bubble.",
			true);
	private final CheckboxSetting showLow = new CheckboxSetting(
		"Show Low confidence", "Renders low confidence detections.", true);
	private final CheckboxSetting showMedium =
		new CheckboxSetting("Show Medium confidence",
			"Renders medium confidence detections.", true);
	private final CheckboxSetting showHigh = new CheckboxSetting(
		"Show High confidence", "Renders high confidence detections.", true);
	private final ColorSetting lowColor =
		new ColorSetting("Low confidence colour", new Color(179, 136, 255));
	private final ColorSetting mediumColor =
		new ColorSetting("Medium confidence colour", new Color(210, 210, 220));
	private final ColorSetting highColor =
		new ColorSetting("High confidence colour", new Color(255, 46, 209));
	private final SliderSetting fillOpacity =
		new SliderSetting("Fill opacity", 40, 0, 255, 1, ValueDisplay.INTEGER);
	private final SliderSetting outlineOpacity = new SliderSetting(
		"Outline opacity", 180, 0, 255, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting debug = new CheckboxSetting("Debug logging",
		"Rate-limits detector diagnostics.", false);
	private final EnumSetting<ShapeMode> shape =
		new EnumSetting<>("Shape Mode", ShapeMode.values(), ShapeMode.Both);
	
	private final Map<Long, State> states = new ConcurrentHashMap<>();
	private final Map<Integer, EntityState> entities =
		new ConcurrentHashMap<>();
	private final ArrayDeque<Position> playerPath = new ArrayDeque<>();
	// Loaded-chunk age and recent local simulation coverage are
	// tracked separately so unloaded detection markers can remain visible.
	private final Map<Long, Long> loadedChunks = new ConcurrentHashMap<>();
	private final Map<Long, Long> localCoverage = new ConcurrentHashMap<>();
	private ClientLevelMarker level;
	private int simulationDistance = -1, viewDistance = -1;
	private long stationarySince, lastDebug;
	private long lastPacketAccessorWarning;
	private long teleportSuppressedUntil;
	private long activationSuppressedUntil;
	private long lastCoverageRefresh;
	private boolean statusShown;
	private boolean thunder;
	private Vec3 lastPlayerPosition;
	private MovementMode movementMode = MovementMode.STATIONARY;
	
	private static SliderSetting seconds(String name, double value, double min,
		double max)
	{
		return new SliderSetting(name, value, min, max, 0.5,
			ValueDisplay.DECIMAL.withSuffix("s"));
	}
	
	public SimulationSonarHack()
	{
		super("SimulationSonar",
			"Detects unexplained outer-ring simulation sources.", false);
		setCategory(Category.INTEL);
		addSetting(render);
		addSetting(notify);
		addSetting(minimumRender);
		addSetting(minimumNotify);
		addSetting(window);
		addSetting(expiry);
		addSetting(renderHeight);
		addSetting(cooldown);
		addSetting(stationary);
		addSetting(pathSuppression);
		addSetting(loadGrace);
		addSetting(requireStationary);
		addSetting(teleportGrace);
		addSetting(ignorePlayers);
		addSetting(showLow);
		addSetting(showMedium);
		addSetting(showHigh);
		addSetting(lowColor);
		addSetting(mediumColor);
		addSetting(highColor);
		addSetting(fillOpacity);
		addSetting(outlineOpacity);
		addSetting(debug);
		addSetting(shape);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		clearTracking();
		if(MC.level != null)
		{
			level = new ClientLevelMarker(MC.level);
			refreshDistanceState();
			seedEntities();
			if(MC.player != null)
			{
				long now = System.currentTimeMillis();
				lastPlayerPosition = MC.player.position();
				stationarySince = now;
				activationSuppressedUntil =
					now + (long)(loadGrace.getValue() * 1000);
				recordPlayerPosition(MC.player.chunkPosition(), now);
			}
		}
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		reset();
	}
	
	private void reset()
	{
		clearTracking();
		simulationDistance = -1;
		viewDistance = -1;
		statusShown = false;
	}
	
	private void clearTracking()
	{
		states.clear();
		entities.clear();
		playerPath.clear();
		loadedChunks.clear();
		localCoverage.clear();
		level = null;
		stationarySince = 0;
		teleportSuppressedUntil = 0;
		activationSuppressedUntil = 0;
		lastCoverageRefresh = 0;
		lastPlayerPosition = null;
		movementMode = MovementMode.STATIONARY;
	}
	
	private void refreshDistanceState()
	{
		if(MC.level == null)
			return;
		if(simulationDistance < 0)
		{
			int serverDistance = MC.level.getServerSimulationDistance();
			if(serverDistance > 0)
				simulationDistance = serverDistance;
		}
		// The cache-radius packet is authoritative when available. The local
		// render distance is a safe fallback for servers that omit that packet.
		if(viewDistance < 0 && MC.options != null)
			viewDistance = Math.max(2, MC.options.renderDistance().get());
	}
	
	private void seedEntities()
	{
		if(MC.level == null)
			return;
		for(Entity entity : MC.level.entitiesForRendering())
			entities.put(entity.getId(),
				new EntityState(entity.getId(), entity.getType().toString(),
					entity.getUUID(), entity.position()));
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(isFreecamActive())
			return;
		
		Packet<?> p = event.getPacket();
		if(p instanceof ClientboundSetSimulationDistancePacket x)
		{
			simulationDistance = x.simulationDistance();
			statusShown = false;
			localCoverage.clear();
			return;
		}
		if(p instanceof ClientboundSetChunkCacheRadiusPacket x)
		{
			viewDistance = x.getRadius();
			statusShown = false;
			return;
		}
		// Position corrections are handled from the actual position delta in
		// updateLocalMovement(). Ordinary flight must not trigger teleport
		// grace.
		if(MC.level != null)
			refreshDistanceState();
		if(MC.level == null || MC.player == null || simulationDistance < 0
			|| viewDistance < 0)
			return;
		if(p instanceof ClientboundLevelChunkWithLightPacket x)
		{
			loaded(new ChunkPos(x.getX(), x.getZ()));
			return;
		}
		if(p instanceof ClientboundForgetLevelChunkPacket x)
		{
			forgotten(x.pos());
			return;
		}
		if(p instanceof ClientboundAddEntityPacket x)
		{
			EntityState entity =
				new EntityState(x.getId(), x.getType().toString(), x.getUUID(),
					new Vec3(x.getX(), x.getY(), x.getZ()));
			entities.put(x.getId(), entity);
			if(isFallingBlock(entity.type))
				add(chunkAt(BlockPos.containing(entity.position)), entity, 3,
					Evidence.FALLING_BLOCK);
			return;
		}
		if(p instanceof ClientboundRemoveEntitiesPacket x)
		{
			x.getEntityIds().forEach(entities::remove);
			return;
		}
		if(p instanceof ClientboundMoveEntityPacket x)
		{
			move(x);
			return;
		}
		if(p instanceof ClientboundEntityPositionSyncPacket x)
		{
			sync(x);
			return;
		}
		if(p instanceof ClientboundSetEntityMotionPacket x)
		{
			motion(x);
			return;
		}
		if(p instanceof ClientboundBlockUpdatePacket x)
		{
			block(x.getPos(), x.getBlockState());
			return;
		}
		if(p instanceof ClientboundSectionBlocksUpdatePacket x)
		{
			x.runUpdates(this::block);
			return;
		}
		if(p instanceof ClientboundLevelEventPacket x && !x.isGlobalEvent())
		{
			if(x.getType() == 1018 || x.getType() == 1024)
				add(chunkAt(x.getPos()), Evidence.LIGHTNING, 6,
					x.getPos().asLong());
		}
	}
	
	private static long key(ChunkPos pos)
	{
		return ((long)pos.x() << 32) ^ (pos.z() & 0xffffffffL);
	}
	
	private static ChunkPos chunkAt(BlockPos pos)
	{
		return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
	}
	
	private void loaded(ChunkPos pos)
	{
		long now = System.currentTimeMillis();
		loadedChunks.put(key(pos), now);
		State s = states.get(key(pos));
		if(s == null)
			return;
		s.loadedAt = now;
		s.loaded = true;
		s.clearLiveEvidence();
	}
	
	private void forgotten(ChunkPos pos)
	{
		loadedChunks.remove(key(pos));
		State s = states.get(key(pos));
		if(s == null)
			return;
		s.loaded = false;
		s.unloadedAt = System.currentTimeMillis();
		s.clearLiveEvidence();
		// The persistent display marker is deliberately retained until the
		// configured detection expiry.
	}
	
	private void markTeleport()
	{
		long now = System.currentTimeMillis();
		teleportSuppressedUntil = Math.max(teleportSuppressedUntil,
			now + (long)(teleportGrace.getValue() * 1000));
		stationarySince = now;
		playerPath.clear();
		lastPlayerPosition = null;
		for(State s : states.values())
			s.clearLiveEvidence();
	}
	
	private void move(ClientboundMoveEntityPacket p)
	{
		int id = packetId(p);
		Entity live = MC.level == null ? null : p.getEntity(MC.level);
		if(live != null)
			id = live.getId();
		EntityState e = entities.get(id);
		if(e == null && live != null)
		{
			e = new EntityState(live.getId(), live.getType().toString(),
				live.getUUID(), live.position());
			entities.put(live.getId(), e);
		}
		if(e == null)
			return;
		boolean moved = p.hasPosition()
			&& (p.getXa() != 0 || p.getYa() != 0 || p.getZa() != 0);
		if(moved)
		{
			e.position = e.position.add(p.getXa() / 4096.0, p.getYa() / 4096.0,
				p.getZa() / 4096.0);
			e.lastSeen = System.currentTimeMillis();
			Evidence kind = entityEvidence(e.type);
			add(chunkAt(BlockPos.containing(e.position)), e,
				kind == Evidence.WEAK ? .25
					: kind == Evidence.FALLING_BLOCK ? 3 : 2,
				kind);
		}
	}
	
	private void sync(ClientboundEntityPositionSyncPacket p)
	{
		Vec3 position = p.values().position();
		Vec3 movement = p.values().deltaMovement();
		Entity live = MC.level == null ? null : MC.level.getEntity(p.id());
		EntityState e = entities.computeIfAbsent(p.id(),
			id -> live == null ? new EntityState(id, "unknown", null, position)
				: new EntityState(id, live.getType().toString(), live.getUUID(),
					position));
		boolean moved = e.position.distanceToSqr(position) > .0001;
		e.position = position;
		e.velocity = movement;
		e.lastSeen = System.currentTimeMillis();
		if(moved)
		{
			Evidence kind = entityEvidence(e.type);
			add(chunkAt(BlockPos.containing(position)), e, kind == Evidence.WEAK
				? .25 : kind == Evidence.FALLING_BLOCK ? 3 : 2, kind);
		}
	}
	
	private void motion(ClientboundSetEntityMotionPacket p)
	{
		int id = packetId(p);
		EntityState e = entities.get(id);
		if(e == null && MC.level != null)
		{
			Entity live = MC.level.getEntity(id);
			if(live != null)
			{
				e = new EntityState(live.getId(), live.getType().toString(),
					live.getUUID(), live.position());
				entities.put(id, e);
			}
		}
		if(e == null)
			return;
		Vec3 v = packetMovement(p);
		if(v.lengthSqr() > .0001)
		{
			e.velocity = v;
			e.lastSeen = System.currentTimeMillis();
			add(chunkAt(BlockPos.containing(e.position)), e, .25,
				Evidence.WEAK);
		}
	}
	
	private static int packetId(Object packet)
	{
		try
		{
			for(String n : new String[]{"getEntityId", "getId", "id",
				"entityId"})
			{
				try
				{
					Method m = packet.getClass().getMethod(n);
					return ((Number)m.invoke(packet)).intValue();
				}catch(ReflectiveOperationException ignored)
				{}
			}
		}catch(Exception ignored)
		{}
		warnPacketAccessor("entity id", packet);
		return -1;
	}
	
	private static Vec3 packetMovement(Object packet)
	{
		try
		{
			for(String n : new String[]{"getMovement", "movement"})
			{
				try
				{
					Method m = packet.getClass().getMethod(n);
					return (Vec3)m.invoke(packet);
				}catch(ReflectiveOperationException ignored)
				{}
			}
		}catch(Exception ignored)
		{}
		warnPacketAccessor("entity movement", packet);
		return Vec3.ZERO;
	}
	
	private static void warnPacketAccessor(String what, Object packet)
	{
		// Accessor compatibility is retained for packet variants whose mapped
		// names differ, but failures are visible while debugging the detector.
		if(WURST == null || WURST.getHax() == null
			|| !WURST.getHax().simulationSonarHack.debug.isChecked())
			return;
		long now = System.currentTimeMillis();
		SimulationSonarHack sonar = WURST.getHax().simulationSonarHack;
		if(now - sonar.lastPacketAccessorWarning < 1000)
			return;
		sonar.lastPacketAccessorWarning = now;
		ChatUtils.message("[SimulationSonar] Could not read " + what + " from "
			+ packet.getClass().getSimpleName() + ".");
	}
	
	private void block(BlockPos pos, BlockState next)
	{
		if(MC.level == null)
			return;
		BlockState old = MC.level.getBlockState(pos);
		if(old.equals(next))
			return;
		boolean fluid =
			!old.getFluidState().isEmpty() || !next.getFluidState().isEmpty();
		String id = next.getBlock().toString();
		Evidence kind =
			fluid ? Evidence.FLUID : isRandomTick(id) ? Evidence.RANDOM_TICK
				: id.contains("fire") ? Evidence.FIRE : Evidence.BLOCK;
		add(chunkAt(pos), kind,
			kind == Evidence.BLOCK ? .15 : kind == Evidence.FLUID ? 1 : 2,
			pos.asLong());
		if(next.isAir() && isGravityBlock(old))
			add(chunkAt(pos), Evidence.FALLING_BLOCK, 3, pos.asLong());
	}
	
	private boolean isRandomTick(String id)
	{
		return id.contains("crop") || id.contains("sapling")
			|| id.contains("leaves") || id.contains("grass")
			|| id.contains("mycelium") || id.contains("snow")
			|| id.contains("ice") || id.contains("copper");
	}
	
	private boolean isGravityBlock(BlockState state)
	{
		if(state.is(Blocks.SAND) || state.is(Blocks.GRAVEL)
			|| state.is(Blocks.ANVIL))
			return true;
		String id = state.getBlock().toString();
		return id.contains("concrete_powder") || id.contains("dragon_egg")
			|| id.contains("pointed_dripstone");
	}
	
	private Evidence entityEvidence(String type)
	{
		if(isFallingBlock(type))
			return Evidence.FALLING_BLOCK;
		if(isTransientEntity(type))
			return Evidence.WEAK;
		return Evidence.ENTITY;
	}
	
	private boolean isFallingBlock(String type)
	{
		return type != null && type.contains("falling_block");
	}
	
	private boolean isTransientEntity(String type)
	{
		if(type == null)
			return false;
		return type.contains("arrow") || type.contains("trident")
			|| type.contains("snowball") || type.contains("egg")
			|| type.contains("potion") || type.contains("firework")
			|| type.contains("ender_pearl") || type.contains("fishing_bobber")
			|| type.contains("experience_orb");
	}
	
	private void add(ChunkPos pos, Evidence kind, double amount)
	{
		add(pos, null, amount, kind, Long.MIN_VALUE);
	}
	
	private void add(ChunkPos pos, Evidence kind, double amount, long sourceKey)
	{
		add(pos, null, amount, kind, sourceKey);
	}
	
	private void add(ChunkPos pos, EntityState entity, double amount)
	{
		add(pos, entity, amount, Evidence.ENTITY, Long.MIN_VALUE);
	}
	
	private void add(ChunkPos pos, EntityState entity, double amount,
		Evidence kind)
	{
		add(pos, entity, amount, kind, Long.MIN_VALUE);
	}
	
	// Evidence now uses movement, load, path, per-tick and
	// rolling-window safeguards before it can raise confidence.
	private void add(ChunkPos pos, EntityState entity, double amount,
		Evidence kind, long sourceKey)
	{
		if(MC.player == null || pos == null)
			return;
		long now = System.currentTimeMillis();
		if(!canScoreEvidence(now))
			return;
		ChunkPos player = MC.player.chunkPosition();
		int d = Math.max(Math.abs(pos.x() - player.x()),
			Math.abs(pos.z() - player.z()));
		if(d < simulationDistance + 2 || d > viewDistance
			|| explained(pos, player))
			return;
		if(!evidenceAllowedWhileMoving(kind, d))
			return;
		
		long chunkKey = key(pos);
		long loadedAt = loadedChunks.getOrDefault(chunkKey, 0L);
		if(loadedAt > 0 && now - loadedAt < loadGrace.getValue() * 1000)
			return;
		
		State s = states.computeIfAbsent(chunkKey, k -> new State(pos));
		s.loadedAt = loadedAt;
		s.loaded = loadedAt > 0;
		s.distance = d;
		long tick = now / 50;
		
		if(entity != null
			&& s.entityLastTick.getOrDefault(entity.id, -1L) == tick
			&& kind != Evidence.WEAK)
			return;
		long dedupeKey = sourceKey == Long.MIN_VALUE ? Long.MIN_VALUE
			: sourceKey ^ ((long)kind.ordinal() << 56);
		if(dedupeKey != Long.MIN_VALUE
			&& s.blockLastTick.getOrDefault(dedupeKey, -1L) == tick)
			return;
		
		if(s.lastTick != tick)
		{
			if(s.lastTick == tick - 1)
				s.consecutiveActiveTicks++;
			else
				s.consecutiveActiveTicks = 1;
			s.lastTick = tick;
			s.tickEvents = 0;
			s.tickWeights.clear();
		}
		if(s.tickEvents >= 12)
			return;
		
		double cap = perTickCap(kind);
		double used = s.tickWeights.getOrDefault(kind, 0D);
		double accepted = Math.min(amount, Math.max(0, cap - used));
		if(accepted <= 0)
			return;
		
		s.tickEvents++;
		s.activeTicks++;
		s.tickWeights.put(kind, used + accepted);
		if(entity != null)
		{
			s.entityLastTick.put(entity.id, tick);
			s.entityLastActive.put(entity.id, now);
			s.entities.add(entity.id);
			s.entityTypes.add(entity.type);
		}
		if(dedupeKey != Long.MIN_VALUE)
			s.blockLastTick.put(dedupeKey, tick);
		
		double stabilityMultiplier =
			now - stationarySince >= stationary.getValue() * 1000 ? 1.1 : .6;
		double points = accepted * multiplier(d) * stabilityMultiplier;
		s.samples.addLast(new EvidenceSample(now, tick, kind, points,
			entity == null ? -1 : entity.id));
		s.firstEvidence = s.firstEvidence == 0 ? now : s.firstEvidence;
		s.lastEvidence = now;
		
		if(entity != null)
			addMultipleEntityEvidence(s, now, tick, d);
		if(s.consecutiveActiveTicks == 5)
			s.samples.addLast(new EvidenceSample(now, tick, kind,
				2 * multiplier(d), entity == null ? -1 : entity.id));
	}
	
	private void addMultipleEntityEvidence(State s, long now, long tick, int d)
	{
		pruneEntityActivity(s, now);
		if(s.entityLastActive.size() < 2 || s.multipleEntityTick == tick)
			return;
		s.multipleEntityTick = tick;
		double points = 2 * multiplier(d);
		s.samples.addLast(new EvidenceSample(now, tick,
			Evidence.MULTIPLE_ENTITIES, points, -1));
	}
	
	private double perTickCap(Evidence kind)
	{
		return switch(kind)
		{
			case ENTITY -> 2;
			case MULTIPLE_ENTITIES -> 2;
			case FALLING_BLOCK -> 4;
			case FLUID -> 1;
			case RANDOM_TICK, FIRE -> 2;
			case ITEM -> 1;
			case LIGHTNING -> 6;
			case BLOCK -> .3;
			case WEAK -> .25;
		};
	}
	
	private boolean canScoreEvidence(long now)
	{
		if(simulationDistance < 0 || viewDistance < simulationDistance + 2)
			return false;
		if(now < activationSuppressedUntil || now < teleportSuppressedUntil)
			return false;
		return true;
	}
	
	private boolean evidenceAllowedWhileMoving(Evidence kind, int distance)
	{
		if(movementMode == MovementMode.STATIONARY)
			return true;
		boolean ordinary = kind == Evidence.BLOCK || kind == Evidence.WEAK
			|| kind == Evidence.RANDOM_TICK || kind == Evidence.FLUID
			|| kind == Evidence.ITEM;
		if(movementMode == MovementMode.FLYING
			&& distance <= simulationDistance + 2 && ordinary)
			return false;
		return !requireStationary.isChecked() || !ordinary;
	}
	
	private boolean explained(ChunkPos pos, ChunkPos local)
	{
		if(inRecentPath(pos))
			return true;
		if(!ignorePlayers.isChecked() || MC.level == null)
			return false;
		for(Player p : MC.level.players())
			if(p != MC.player && p.isAlive())
			{
				ChunkPos c = p.chunkPosition();
				if(Math.max(Math.abs(pos.x() - c.x()),
					Math.abs(pos.z() - c.z())) <= simulationDistance + 1)
					return true;
			}
		return Math.max(Math.abs(pos.x() - local.x()),
			Math.abs(pos.z() - local.z())) <= simulationDistance + 1;
	}
	
	private boolean inRecentPath(ChunkPos pos)
	{
		Long until = localCoverage.get(key(pos));
		return until != null && until > System.currentTimeMillis();
	}
	
	private double multiplier(int d)
	{
		return d <= simulationDistance + 2 ? 1 : d == simulationDistance + 3
			? 1.25 : d == simulationDistance + 4 ? 1.5 : 1.75;
	}
	
	@Override
	public void onUpdate()
	{
		if(isFreecamActive())
			return;
		
		if(MC.level == null || MC.player == null)
		{
			if(level != null)
				reset();
			return;
		}
		if(level == null)
		{
			level = new ClientLevelMarker(MC.level);
			refreshDistanceState();
			seedEntities();
			activationSuppressedUntil = System.currentTimeMillis()
				+ (long)(loadGrace.getValue() * 1000);
		}else if(level.value != MC.level)
		{
			clearTracking();
			simulationDistance = -1;
			viewDistance = -1;
			level = new ClientLevelMarker(MC.level);
			refreshDistanceState();
			seedEntities();
			activationSuppressedUntil = System.currentTimeMillis()
				+ (long)(loadGrace.getValue() * 1000);
		}
		refreshDistanceState();
		announceStatus();
		
		long now = System.currentTimeMillis();
		updateLocalMovement(now);
		ChunkPos p = MC.player.chunkPosition();
		if(playerPath.isEmpty() || !playerPath.peekLast().pos.equals(p))
			recordPlayerPosition(p, now);
		else if(now - lastCoverageRefresh >= 1000)
			recordLocalCoverage(p, now);
		
		while(!playerPath.isEmpty() && (playerPath.size() > 512 || now
			- playerPath.peekFirst().time > pathSuppression.getValue() * 1000))
			playerPath.removeFirst();
		pruneLocalCoverage(now);
		
		for(State s : states.values())
		{
			recalculate(s, now);
			updateConfidence(s, now);
			boolean markerExpired = s.lastMarked == 0
				|| now - s.lastMarked > expiry.getValue() * 1000;
			boolean liveExpired = s.lastEvidence == 0
				|| now - s.lastEvidence > window.getValue() * 1000;
			if(markerExpired && liveExpired)
				states.remove(key(s.pos), s);
		}
		pruneEntities(now);
	}
	
	private void updateLocalMovement(long now)
	{
		Vec3 current = MC.player.position();
		if(lastPlayerPosition == null)
		{
			lastPlayerPosition = current;
			stationarySince = now;
			return;
		}
		double distance = current.distanceTo(lastPlayerPosition);
		if(distance > .05)
			stationarySince = now;
		boolean flying = MC.player.getAbilities().flying
			|| MC.player.isFallFlying()
			|| (MC.player.isSprinting() && distance > .29) || distance > .45;
		movementMode = flying ? MovementMode.FLYING
			: now - stationarySince >= stationary.getValue() * 1000
				? MovementMode.STATIONARY : MovementMode.MOVING;
		// Only a genuinely large non-flight jump gets teleport grace. The
		// swept footprint below remains intact so it still explains old chunks.
		if(distance > 32 && !flying)
			teleportSuppressedUntil = Math.max(teleportSuppressedUntil,
				now + (long)(teleportGrace.getValue() * 1000));
		recordSweptCoverage(lastPlayerPosition, current, now);
		lastPlayerPosition = current;
	}
	
	private void recordSweptCoverage(Vec3 from, Vec3 to, long now)
	{
		int steps = Math.max(1, (int)Math.ceil(from.distanceTo(to) / 8));
		for(int i = 1; i <= steps; i++)
		{
			double t = i / (double)steps;
			recordLocalCoverage(chunkAt(BlockPos.containing(from.lerp(to, t))),
				now);
		}
	}
	
	private void recordPlayerPosition(ChunkPos pos, long now)
	{
		playerPath.addLast(new Position(pos, now));
		recordLocalCoverage(pos, now);
	}
	
	private void recordLocalCoverage(ChunkPos center, long now)
	{
		if(simulationDistance < 0)
			return;
		long until = now + (long)(pathSuppression.getValue() * 1000);
		int radius = simulationDistance + 1;
		for(int x = center.x() - radius; x <= center.x() + radius; x++)
			for(int z = center.z() - radius; z <= center.z() + radius; z++)
				localCoverage.merge(key(new ChunkPos(x, z)), until, Math::max);
		lastCoverageRefresh = now;
	}
	
	private void pruneLocalCoverage(long now)
	{
		for(Map.Entry<Long, Long> entry : localCoverage.entrySet())
			if(entry.getValue() <= now)
				localCoverage.remove(entry.getKey(), entry.getValue());
	}
	
	private void pruneEntities(long now)
	{
		long maxAge = Math.max(60000, (long)(expiry.getValue() * 1000));
		for(Map.Entry<Integer, EntityState> entry : entities.entrySet())
			if(now - entry.getValue().lastSeen > maxAge && (MC.level == null
				|| MC.level.getEntity(entry.getKey()) == null))
				entities.remove(entry.getKey(), entry.getValue());
	}
	
	private void recalculate(State s, long now)
	{
		long cutoff = now - (long)(window.getValue() * 1000);
		while(!s.samples.isEmpty() && s.samples.peekFirst().time < cutoff)
			s.samples.removeFirst();
		pruneEntityActivity(s, now);
		
		s.score = 0;
		s.counts.clear();
		s.weights.clear();
		s.recentActiveTicks = 0;
		s.recentStrongEvents = 0;
		s.recentStrongCategories = 0;
		s.recentEntityTicks = 0;
		s.recentEntities.clear();
		Set<Long> ticks = new HashSet<>();
		Set<Long> entityTicks = new HashSet<>();
		Set<Evidence> strongKinds = new HashSet<>();
		for(EvidenceSample sample : s.samples)
		{
			s.score += sample.points;
			s.counts.merge(sample.kind, 1, Integer::sum);
			s.weights.merge(sample.kind, sample.points, Double::sum);
			ticks.add(sample.tick);
			if(sample.entityId >= 0 && sample.kind != Evidence.WEAK)
			{
				s.recentEntities.add(sample.entityId);
				entityTicks.add(sample.tick);
			}
			if(isStrongEvidence(sample.kind))
			{
				s.recentStrongEvents++;
				strongKinds.add(sample.kind);
			}
		}
		s.recentActiveTicks = ticks.size();
		s.recentEntityTicks = entityTicks.size();
		s.recentStrongCategories = strongKinds.size();
		if(s.samples.isEmpty())
		{
			s.firstEvidence = 0;
			s.lastEvidence = 0;
		}else
		{
			s.firstEvidence = s.samples.peekFirst().time;
			s.lastEvidence = s.samples.peekLast().time;
		}
	}
	
	private void pruneEntityActivity(State s, long now)
	{
		long cutoff = now - (long)(window.getValue() * 1000);
		Iterator<Map.Entry<Integer, Long>> iterator =
			s.entityLastActive.entrySet().iterator();
		while(iterator.hasNext())
			if(iterator.next().getValue() < cutoff)
				iterator.remove();
	}
	
	private boolean isStrongEvidence(Evidence evidence)
	{
		return evidence != Evidence.WEAK && evidence != Evidence.BLOCK;
	}
	
	private void announceStatus()
	{
		if(statusShown)
			return;
		if(simulationDistance >= 0 && viewDistance >= 0)
		{
			ChatUtils.message(
				"[SimulationSonar] Ready: simulation=" + simulationDistance
					+ ", view=" + viewDistance + ", detection ring="
					+ (simulationDistance + 2) + "-" + viewDistance + ".");
			statusShown = true;
		}else
		{
			ChatUtils
				.message("[SimulationSonar] Waiting for server distance data. "
					+ "Detection is inactive.");
			statusShown = true;
		}
	}
	
	// Confidence requires persistence and evidence quality, while
	// display confidence remains latched for the marker lifetime.
	private void updateConfidence(State s, long now)
	{
		Confidence old = s.confidence;
		long span = s.firstEvidence == 0 ? 0 : s.lastEvidence - s.firstEvidence;
		boolean falling = s.counts.getOrDefault(Evidence.FALLING_BLOCK, 0) > 0;
		boolean lightning = s.counts.getOrDefault(Evidence.LIGHTNING, 0) > 0;
		boolean quality = s.recentStrongEvents >= 3 || falling || lightning;
		boolean distantQuality = s.distance < simulationDistance + 3
			|| s.recentEntityTicks >= 3 || s.recentStrongCategories >= 2;
		if(!distantQuality)
		{
			s.confidence = null;
			return;
		}
		
		s.confidence = null;
		if(s.score >= 6 && quality && s.recentActiveTicks >= 2 && span >= 250)
			s.confidence = Confidence.Low;
		if(s.score >= 14 && s.recentStrongEvents >= 6
			&& s.recentActiveTicks >= 5 && span >= 800)
			s.confidence = Confidence.Medium;
		if(s.score >= 28 && s.recentStrongEvents >= 12
			&& s.recentActiveTicks >= 10 && span >= 1500
			&& (s.recentEntities.size() >= 2 || s.recentStrongCategories >= 2
				|| falling))
			s.confidence = Confidence.High;
		
		if(s.confidence != null)
		{
			s.lastMarked = now;
			if(rank(s.confidence) > rank(s.displayConfidence))
				s.displayConfidence = s.confidence;
		}
		
		if(notify.isChecked() && s.confidence != null
			&& rank(s.confidence) >= rank(minimumNotify.getSelected())
			&& rank(s.confidence) > rank(old)
			&& now - s.lastNotify > cooldown.getValue() * 1000)
		{
			s.lastNotify = now;
			notify(s);
		}
		if(debug.isChecked() && s.confidence != null && now - lastDebug > 1000)
		{
			lastDebug = now;
			ChatUtils.message("[SimulationSonar] " + s.pos + " score="
				+ String.format("%.1f", s.score) + " " + s.confidence
				+ " distance=" + s.distance + " ticks=" + s.recentActiveTicks
				+ " strong=" + s.recentStrongEvents + " entities="
				+ s.recentEntities.size());
		}
	}
	
	private int rank(Confidence c)
	{
		return c == null ? 0 : c.ordinal() + 1;
	}
	
	private void notify(State s)
	{
		ChunkPos p = MC.player.chunkPosition();
		int dx = s.pos.x() - p.x(), dz = s.pos.z() - p.z();
		String dir = direction(dx, dz);
		double cx = s.pos.x() * 16 + 8, cz = s.pos.z() * 16 + 8;
		int blocks = (int)Math.round(
			Math.sqrt(MC.player.distanceToSqr(cx, MC.player.getY(), cz)));
		ChatUtils.message("[SimulationSonar] "
			+ s.confidence.toString().toUpperCase()
			+ " unexplained outer-ring simulation " + s.distance + " chunks "
			+ dir + ", " + blocks + " blocks away, in chunk " + s.pos.x() + ","
			+ s.pos.z() + ". Dominant evidence: " + prominent(s) + ". "
			+ s.recentEntities.size() + " moving entities, " + s.total()
			+ " recent events. Possible offset spectator or other chunk ticket.");
	}
	
	private String direction(int dx, int dz)
	{
		String vertical = dz < 0 ? "N" : dz > 0 ? "S" : "";
		String horizontal = dx > 0 ? "E" : dx < 0 ? "W" : "";
		String result = vertical + horizontal;
		return result.isEmpty() ? "HERE" : result;
	}
	
	private String prominent(State s)
	{
		Evidence best = Evidence.WEAK;
		double value = 0;
		for(var e : s.weights.entrySet())
			if(e.getValue() > value)
			{
				value = e.getValue();
				best = e.getKey();
			}
		return best.toString().toLowerCase().replace('_', ' ');
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(isFreecamActive() || !render.isChecked() || MC.player == null)
			return;
		long now = System.currentTimeMillis();
		double y = renderHeight.getValue();
		if(WURST != null && WURST.getHax().newerNewChunksHack.isEnabled())
			y += WURST.getHax().newerNewChunksHack.getRenderHeight();
		for(Confidence confidence : Confidence.values())
		{
			if(!allowed(confidence))
				continue;
			ColorSetting color = confidence == Confidence.High ? highColor
				: confidence == Confidence.Medium ? mediumColor : lowColor;
			for(State s : states.values())
			{
				if(s.displayConfidence != confidence || s.lastMarked == 0
					|| now - s.lastMarked > expiry.getValue() * 1000)
					continue;
				int distance = distanceFromPlayer(s.pos);
				double fade = fadeForDistance(s.pos);
				if(distance > viewDistance + 4 || fade <= 0)
					continue;
				AABB box = new AABB(s.pos.getMinBlockX(), y,
					s.pos.getMinBlockZ(), s.pos.getMinBlockX() + 16, y + 1,
					s.pos.getMinBlockZ() + 16);
				List<AABB> boxes = List.of(box);
				if(shape.getSelected() != ShapeMode.Lines)
					RenderUtils.drawSolidBoxes(matrices, boxes,
						color.getColorI(
							(int)Math.round(fillOpacity.getValue() * fade)),
						true);
				if(shape.getSelected() != ShapeMode.Sides)
					RenderUtils.drawOutlinedBoxes(matrices, boxes,
						color.getColorI(
							(int)Math.round(outlineOpacity.getValue() * fade)),
						true);
			}
		}
	}
	
	private boolean isFreecamActive()
	{
		return WURST != null && WURST.getHax() != null
			&& WURST.getHax().freecamHack.isMovingCamera();
	}
	
	private int distanceFromPlayer(ChunkPos pos)
	{
		if(MC.player == null)
			return Integer.MAX_VALUE;
		ChunkPos player = MC.player.chunkPosition();
		return Math.max(Math.abs(pos.x() - player.x()),
			Math.abs(pos.z() - player.z()));
	}
	
	private double fadeForDistance(ChunkPos pos)
	{
		int distance = distanceFromPlayer(pos);
		if(distance <= 2)
			return Math.max(0, distance / 2.0);
		if(viewDistance < 0 || distance <= viewDistance - 3)
			return 1;
		return Math.max(0, Math.min(1, (viewDistance + 4 - distance) / 7.0));
	}
	
	private boolean allowed(Confidence c)
	{
		return rank(c) >= rank(minimumRender.getSelected())
			&& (c != Confidence.Low || showLow.isChecked())
			&& (c != Confidence.Medium || showMedium.isChecked())
			&& (c != Confidence.High || showHigh.isChecked());
	}
	
	public boolean isChunkOverridden(ChunkPos pos)
	{
		State s = states.get(key(pos));
		long now = System.currentTimeMillis();
		return isEnabled() && render.isChecked() && s != null
			&& s.displayConfidence != null && s.lastMarked > 0
			&& now - s.lastMarked <= expiry.getValue() * 1000
			&& allowed(s.displayConfidence);
	}
	
	private static final class State
	{
		final ChunkPos pos;
		long loadedAt, unloadedAt, firstEvidence, lastEvidence, lastNotify;
		long lastTick, lastMarked, multipleEntityTick;
		double score;
		int tickEvents, activeTicks, consecutiveActiveTicks, distance;
		int recentActiveTicks, recentStrongEvents, recentStrongCategories;
		int recentEntityTicks;
		boolean loaded;
		Confidence confidence;
		Confidence displayConfidence;
		final Set<Integer> entities = new HashSet<>();
		final Set<String> entityTypes = new HashSet<>();
		final Set<Integer> recentEntities = new HashSet<>();
		final Map<Evidence, Integer> counts = new EnumMap<>(Evidence.class);
		final Map<Evidence, Double> weights = new EnumMap<>(Evidence.class);
		final Map<Evidence, Double> tickWeights = new EnumMap<>(Evidence.class);
		final Map<Integer, Long> entityLastActive = new HashMap<>();
		final Map<Integer, Long> entityLastTick = new HashMap<>();
		final Map<Long, Long> blockLastTick = new HashMap<>();
		final ArrayDeque<EvidenceSample> samples = new ArrayDeque<>();
		
		State(ChunkPos p)
		{
			pos = p;
		}
		
		void clearLiveEvidence()
		{
			samples.clear();
			counts.clear();
			weights.clear();
			tickWeights.clear();
			entityLastActive.clear();
			entityLastTick.clear();
			blockLastTick.clear();
			recentEntities.clear();
			score = 0;
			firstEvidence = 0;
			lastEvidence = 0;
			lastTick = 0;
			multipleEntityTick = 0;
			tickEvents = 0;
			activeTicks = 0;
			consecutiveActiveTicks = 0;
			recentActiveTicks = 0;
			recentStrongEvents = 0;
			recentStrongCategories = 0;
			recentEntityTicks = 0;
			confidence = null;
		}
		
		int total()
		{
			return counts.values().stream().mapToInt(Integer::intValue).sum();
		}
	}
	
	private static final class EntityState
	{
		final int id;
		final String type;
		final UUID uuid;
		Vec3 position, velocity = Vec3.ZERO;
		long lastSeen;
		
		EntityState(int i, String t, UUID u, Vec3 p)
		{
			id = i;
			type = t;
			uuid = u;
			position = p;
			lastSeen = System.currentTimeMillis();
		}
	}
	
	private record EvidenceSample(long time, long tick, Evidence kind,
		double points, int entityId)
	{}
	
	private record Position(ChunkPos pos, long time)
	{}
	
	private static final class ClientLevelMarker
	{
		final Object value;
		
		ClientLevelMarker(Object v)
		{
			value = v;
		}
	}
}
