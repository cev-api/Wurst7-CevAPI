/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import static net.wurstclient.WurstClient.MC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// use internal SliderSetting.ValueDisplay; external GUI class not available

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import net.wurstclient.Category;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.wurstclient.events.RenderListener;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.minecraft.world.phys.AABB;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.ChatUtils;

public final class CoordLoggerHack extends Hack
	implements PacketInputListener, RenderListener
{
	// Settings
	
	// Distance threshold for chat/filters
	private final SliderSetting minDistance =
		new SliderSetting("Minimum distance",
			"Minimum distance (in blocks) before logging an event.", 10.0, 0.0,
			500.0, 1.0, SliderSetting.ValueDisplay.INTEGER);
	
	// Teleports (kept for teleport logging behaviour)
	private final CheckboxSetting playerTeleports = new CheckboxSetting(
		"Player teleports",
		"Log player teleports when they move more than the minimum distance.",
		true);
	
	private final CheckboxSetting logAllEntityTeleports = new CheckboxSetting(
		"All entity teleports", "Log teleports of non-player entities.", false);
	
	private final CheckboxSetting logPortals =
		new CheckboxSetting("Portals", "Log portal/gateway events.", true);
	
	private final CheckboxSetting logRedstone =
		new CheckboxSetting("Redstone/technical",
			"Log redstone and technical events (dispensers, etc).", false);
	
	private final CheckboxSetting logMobs = new CheckboxSetting("Mobs",
		"Log mob-related events (wither, dragon, etc).", true);
	
	private final CheckboxSetting logMisc = new CheckboxSetting("Misc",
		"Log miscellaneous ambient/utility events.", true);
	
	private final CheckboxSetting logUnknown = new CheckboxSetting(
		"Unknown events", "Log events with unknown IDs.", false);
	
	private final CheckboxSetting onlyGlobal =
		new CheckboxSetting("Only global events",
			"Only handle events that are marked global by the server.", false);
	
	// Debug and rendering
	private final CheckboxSetting debugLevelEvents = new CheckboxSetting(
		"Debug level events",
		"Print debug info for every ClientboundLevelEventPacket (id, pos, global flag, player pos).",
		false);
	
	private final CheckboxSetting tracersEnabled = new CheckboxSetting(
		"Draw tracers",
		"When enabled, draw tracer lines from the player to the event marker.",
		true);
	
	private final ColorSetting espColor = new ColorSetting("ESP color",
		"Color of the ESP boxes and tracers.", new Color(153, 0, 204));
	
	// --------------- INTERNAL STATE --------------- //
	
	// Rendering / markers
	private static final double NEAR_EVENT_DISTANCE = 128.0;
	private static final double ARROW_LENGTH = 20.0;
	private static final long MARKER_LIFETIME_MS = 60000L; // 60 seconds default
	private static final double PROXIMITY_RADIUS = 5.0; // blocks
	private static final long PROXIMITY_REQUIRED_MS = 10000L; // 10 seconds
																// within radius
																// to
																// auto-remove
	private static final long WITHER_DEDUPE_WINDOW_MS = 2500L;
	private static final double WITHER_DEDUPE_DISTANCE = 4.0;
	
	private final List<EventMarker> markers = new CopyOnWriteArrayList<>();
	private long lastWitherSpawnLogMs;
	private Vec3 lastWitherSpawnPos;
	
	private enum EventCategory
	{
		PORTALS,
		REDSTONE,
		MOBS,
		MISC
	}
	
	private enum EventScope
	{
		LOCAL,
		GLOBAL
	}
	
	private record EventMeta(String name, EventCategory category,
		EventScope expectedScope)
	{}
	
	private static final Map<Integer, EventMeta> TRACKED_EVENTS =
		new HashMap<>();
	
	static
	{
		// Exact events requested in the detection overview.
		TRACKED_EVENTS.put(1038, new EventMeta("SOUND_END_PORTAL_SPAWN",
			EventCategory.PORTALS, EventScope.GLOBAL));
		TRACKED_EVENTS.put(2003, new EventMeta("PARTICLES_EYE_OF_ENDER_DEATH",
			EventCategory.REDSTONE, EventScope.LOCAL));
		TRACKED_EVENTS.put(1023, new EventMeta("SOUND_WITHER_BOSS_SPAWN",
			EventCategory.MOBS, EventScope.GLOBAL));
		TRACKED_EVENTS.put(2001, new EventMeta("PARTICLES_DESTROY_BLOCK",
			EventCategory.MISC, EventScope.LOCAL));
		TRACKED_EVENTS.put(1022, new EventMeta("WITHER_BREAK_BLOCK",
			EventCategory.MOBS, EventScope.LOCAL));
	}
	
	private static final class EventMarker
	{
		// original event vector (block-centered). For far markers we recompute
		// the visible arrow endpoint each render based on the player's eye
		// position so the direction remains correct as the player moves.
		final Vec3 eventVec;
		final boolean far;
		final long createdAt;
		long proximityEnteredAt; // 0 = not currently within proximity
		final int id;
		final String name;
		final EventCategory cat;
		
		EventMarker(Vec3 eventVec, boolean far, int id, String name,
			EventCategory cat)
		{
			this.eventVec = eventVec;
			this.far = far;
			this.createdAt = System.currentTimeMillis();
			this.proximityEnteredAt = 0L;
			this.id = id;
			this.name = name;
			this.cat = cat;
		}
	}
	
	// --------------- CONSTRUCTOR --------------- //
	
	public CoordLoggerHack()
	{
		super("CoordLogger");
		setCategory(Category.INTEL);
		
		// Settings in ClickGUI
		addSetting(minDistance);
		addSetting(playerTeleports);
		addSetting(logAllEntityTeleports);
		
		addSetting(logPortals);
		addSetting(logRedstone);
		addSetting(logMobs);
		addSetting(logMisc);
		addSetting(logUnknown);
		addSetting(onlyGlobal);
		addSetting(debugLevelEvents);
		addSetting(tracersEnabled);
		addSetting(espColor);
	}
	
	// --------------- LIFECYCLE --------------- //
	
	@Override
	protected void onEnable()
	{
		markers.clear();
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		markers.clear();
	}
	
	// --------------- PACKET HANDLING --------------- //
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		try
		{
			Packet<?> packet = event.getPacket();
			
			// Teleports
			if(packet instanceof ClientboundTeleportEntityPacket tp)
			{
				handleTeleport(tp);
				return; // don't fall-through and accidentally process other
						// packet
						// types
			}
			
			// World events (WorldEvents constants)
			if(packet instanceof ClientboundLevelEventPacket lvl)
			{
				handleLevelEvent(lvl);
				return;
			}
			
			if(packet instanceof ClientboundSoundPacket sound)
			{
				handleSoundPacket(sound);
				return;
			}
		}catch(Throwable t)
		{
			// Prevent exceptions from bubbling up to the EventManager (which
			// turns them into a ReportedException / crash report). Schedule
			// the chat message on the client thread to avoid RenderSystem
			// "called from wrong thread" errors.
			String shortMsg = "[CoordLogger] Exception in packet handler: "
				+ t.getClass().getSimpleName() + ": " + t.getMessage();
			if(MC != null)
				MC.execute(() -> ChatUtils.message(shortMsg));
			else
				ChatUtils.message(shortMsg);
			t.printStackTrace();
		}
	}
	
	// --------------- RATE LIMITING / DEDUPLICATION --------------- //
	
	// last logged position per entity id
	private final Map<Integer, Vec3> lastLoggedPos = new ConcurrentHashMap<>();
	// last logged time per entity id (ms)
	private final Map<Integer, Long> lastLoggedTime = new ConcurrentHashMap<>();
	// minimum ms between logged teleports for the same entity to avoid spam
	private static final long TELEPORT_LOG_COOLDOWN_MS = 2000L;
	
	// --------------- TELEPORT LOGIC --------------- //
	
	private void handleTeleport(ClientboundTeleportEntityPacket packet)
	{
		if(MC.level == null)
			return;
		
		int entityId = packet.id();
		// ignore our own player teleports (server -> client teleport for our
		// player can happen frequently)
		if(MC.player != null && entityId == MC.player.getId())
			return;
		
		Entity entity = MC.level.getEntity(entityId);
		
		// Mojmap names
		Vec3 oldPos =
			entity == null ? lastLoggedPos.get(entityId) : entity.position();
		if(packet.change() == null || packet.change().position() == null)
			return;
		Vec3 newPos = packet.change().position();
		
		double dist = oldPos == null ? Double.NaN : oldPos.distanceTo(newPos);
		if(!Double.isNaN(dist) && dist < minDistance.getValue())
			return;
			
		// rate-limit / dedupe: if we logged this entity recently and the last
		// logged
		// position is still within the minimum distance, skip to avoid spam
		long now = System.currentTimeMillis();
		Long lastTime = lastLoggedTime.get(entityId);
		Vec3 lastPos = lastLoggedPos.get(entityId);
		if(lastTime != null && (now - lastTime) < TELEPORT_LOG_COOLDOWN_MS)
		{
			if(lastPos != null
				&& lastPos.distanceTo(newPos) < minDistance.getValue())
			{
				return;
			}
		}
		
		boolean isPlayer = entity instanceof Player;
		
		if(isPlayer && !playerTeleports.isChecked())
			return;
		
		if(!isPlayer && !logAllEntityTeleports.isChecked())
			return;
		
		String entityName = entity == null ? ("Entity#" + entityId)
			: entity.getName().getString();
		String delta = Double.isNaN(dist) ? "n/a" : String.format("%.1f", dist);
		String msg = String.format(
			"[CoordLogger] Teleport: %s (id=%d) -> x=%.1f, y=%.1f, z=%.1f (Δ=%s)",
			entityName, entityId, newPos.x, newPos.y, newPos.z, delta);
		
		if(MC != null)
			MC.execute(() -> ChatUtils.message(msg));
		else
			ChatUtils.message(msg);
		
		// update last logged state
		lastLoggedTime.put(entityId, now);
		lastLoggedPos.put(entityId, newPos);
	}
	
	// --------------- WORLD EVENT LOGIC --------------- //
	
	private void handleLevelEvent(ClientboundLevelEventPacket packet)
	{
		// Global-only filter
		if(packet == null)
			return;
		if(onlyGlobal.isChecked() && !packet.isGlobalEvent())
			return;
		
		BlockPos pos = packet.getPos();
		if(pos == null)
			return;
		double dist = distanceToPlayer(pos);
		
		if(dist < minDistance.getValue())
			return;
		
		int id = packet.getType(); // Mojmap: getType() == WorldEvents constant
		EventMeta meta = TRACKED_EVENTS.get(id);
		if(meta == null)
		{
			if(!logUnknown.isChecked())
				return;
			meta = new EventMeta("UNKNOWN", EventCategory.MISC,
				packet.isGlobalEvent() ? EventScope.GLOBAL : EventScope.LOCAL);
		}
		
		// Debugging: optionally print raw packet info so we can inspect what
		// coordinates the server actually sent vs the player's position.
		if(debugLevelEvents.isChecked())
		{
			final BlockPos ppos = packet.getPos();
			double px = MC.player == null ? Double.NaN : MC.player.position().x;
			double py = MC.player == null ? Double.NaN : MC.player.position().y;
			double pz = MC.player == null ? Double.NaN : MC.player.position().z;
			String dbg = String.format(
				"[CoordLogger:DEBUG] id=%d global=%b pktPos=(%d,%d,%d) player=(%.1f,%.1f,%.1f) dist=%.1f",
				id, packet.isGlobalEvent(),
				(ppos == null ? Integer.MIN_VALUE : ppos.getX()),
				(ppos == null ? Integer.MIN_VALUE : ppos.getY()),
				(ppos == null ? Integer.MIN_VALUE : ppos.getZ()), px, py, pz,
				dist);
			if(MC != null)
				MC.execute(() -> ChatUtils.message(dbg));
			else
				ChatUtils.message(dbg);
		}
		
		EventCategory cat = meta.category();
		
		if(cat == EventCategory.PORTALS && !logPortals.isChecked())
			return;
		if(cat == EventCategory.REDSTONE && !logRedstone.isChecked())
			return;
		if(cat == EventCategory.MOBS && !logMobs.isChecked())
			return;
		if(cat == EventCategory.MISC && !logMisc.isChecked())
			return;
		
		String catName = cat.name();
		String expectedScope = meta.expectedScope().name();
		String actualScope = packet.isGlobalEvent() ? "GLOBAL" : "LOCAL";
		Vec3 eventVec = Vec3.atCenterOf(pos);
		String directionInfo = MC.player == null ? "n/a"
			: getDirectionInfo(MC.player.position(), eventVec);
		if(id == 1023)
			rememberWitherSpawn(eventVec);
		
		String extraData = "";
		if(id == 2001)
			extraData = " blockStateId=" + packet.getData();
		
		String scopeNote = actualScope.equals(expectedScope) ? ""
			: String.format(" scopeMismatch(expected=%s, got=%s)",
				expectedScope, actualScope);
		
		final String msg = String.format(
			"[CoordLogger] LevelEvent id=%d (%s, %s, scope=%s)%s at x=%d, y=%d, z=%d%s (dist=%.1f, %s)",
			id, meta.name(), catName, actualScope, scopeNote, pos.getX(),
			pos.getY(), pos.getZ(), extraData, dist, directionInfo);
		
		// Create a render marker for this event (near vs far visual)
		if(MC != null && MC.player != null)
		{
			boolean isFar = dist > NEAR_EVENT_DISTANCE;
			EventMarker em =
				new EventMarker(eventVec, isFar, id, meta.name(), cat);
			markers.add(em);
			
			if(MC != null)
				MC.execute(() -> ChatUtils.message(msg));
			else
				ChatUtils.message(msg);
		}
	}
	
	private void handleSoundPacket(ClientboundSoundPacket packet)
	{
		if(packet == null || !logMobs.isChecked())
			return;
		
		Identifier soundKey =
			BuiltInRegistries.SOUND_EVENT.getKey(packet.getSound().value());
		if(soundKey == null)
			return;
		
		String soundId = soundKey.toString();
		if(!soundId.contains("wither") || !soundId.contains("spawn"))
			return;
		
		Vec3 soundPos = new Vec3(packet.getX(), packet.getY(), packet.getZ());
		if(shouldSkipWitherSpawnByDedupe(soundPos))
			return;
		
		double dist =
			MC.player == null ? 0.0 : MC.player.position().distanceTo(soundPos);
		if(dist < minDistance.getValue())
			return;
		
		String directionInfo = MC.player == null ? "n/a"
			: getDirectionInfo(MC.player.position(), soundPos);
		String msg = String.format(
			"[CoordLogger] Wither spawn sound at x=%.1f, y=%.1f, z=%.1f (dist=%.1f, %s)",
			soundPos.x, soundPos.y, soundPos.z, dist, directionInfo);
		
		boolean isFar = dist > NEAR_EVENT_DISTANCE;
		markers.add(new EventMarker(soundPos, isFar, 1023,
			"SOUND_WITHER_BOSS_SPAWN", EventCategory.MOBS));
		rememberWitherSpawn(soundPos);
		
		if(MC != null)
			MC.execute(() -> ChatUtils.message(msg));
		else
			ChatUtils.message(msg);
	}
	
	private void rememberWitherSpawn(Vec3 pos)
	{
		lastWitherSpawnPos = pos;
		lastWitherSpawnLogMs = System.currentTimeMillis();
	}
	
	private boolean shouldSkipWitherSpawnByDedupe(Vec3 pos)
	{
		if(lastWitherSpawnPos == null)
			return false;
		
		long age = System.currentTimeMillis() - lastWitherSpawnLogMs;
		return age <= WITHER_DEDUPE_WINDOW_MS
			&& lastWitherSpawnPos.distanceTo(pos) <= WITHER_DEDUPE_DISTANCE;
	}
	
	private double distanceToPlayer(BlockPos pos)
	{
		if(MC.player == null)
			return 0.0;
		
		Vec3 playerPos = MC.player.position();
		Vec3 eventPos =
			new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		return playerPos.distanceTo(eventPos);
	}
	
	/**
	 * Returns one of the 8 compass directions (N, NE, E, SE, S, SW, W, NW)
	 * from 'from' pointing towards 'to'.
	 */
	private static String getDirectionInfo(Vec3 from, Vec3 to)
	{
		Vec3 delta = to.subtract(from);
		double len = delta.length();
		if(len < 1e-6)
			return "dir=HERE h=0.0deg v=0.0deg";
		
		Vec3 dir = delta.scale(1.0 / len);
		double horizontalAngle = Math.toDegrees(Math.atan2(dir.x, dir.z));
		double verticalAngle = Math.toDegrees(Math.asin(dir.y));
		return String.format("dir=%s h=%.1fdeg v=%.1fdeg",
			getCompassDirection(from, to), horizontalAngle, verticalAngle);
	}
	
	private static String getCompassDirection(Vec3 from, Vec3 to)
	{
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double deg = Math.toDegrees(Math.atan2(dz, dx)); // 0 = +X (east)
		if(deg < 0)
			deg += 360.0;
		// Map to 8 sectors: E, NE, N, NW, W, SW, S, SE
		int idx = (int)Math.round(deg / 45.0) % 8;
		switch(idx)
		{
			case 0:
			return "E";
			case 1:
			return "NE";
			case 2:
			return "N";
			case 3:
			return "NW";
			case 4:
			return "W";
			case 5:
			return "SW";
			case 6:
			return "S";
			case 7:
			default:
			return "SE";
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		long now = System.currentTimeMillis();
		float[] col = espColor.getColorF();
		int color = RenderUtils.toIntColor(col, 0.9F);
		for(EventMarker m : markers)
		{
			if(now - m.createdAt > MARKER_LIFETIME_MS)
			{
				markers.remove(m);
				continue;
			}
			
			// compute current visible marker position
			Vec3 markerPos = m.far ? RotationUtils.getEyesPos()
				.add(m.eventVec.subtract(RotationUtils.getEyesPos()).normalize()
					.scale(ARROW_LENGTH))
				: m.eventVec;
			
			// If player is standing on the actual event block, remove marker
			if(MC.player != null)
			{
				BlockPos playerBlock =
					new BlockPos((int)Math.floor(MC.player.position().x),
						(int)Math.floor(MC.player.position().y),
						(int)Math.floor(MC.player.position().z));
				BlockPos eventBlock =
					new BlockPos((int)Math.floor(m.eventVec.x),
						(int)Math.floor(m.eventVec.y),
						(int)Math.floor(m.eventVec.z));
				if(playerBlock.equals(eventBlock)
					|| MC.player.position().distanceTo(m.eventVec) <= 1.0)
				{
					markers.remove(m);
					continue;
				}
				
				// proximity handling: remove after PROXIMITY_REQUIRED_MS of
				// continuous proximity within PROXIMITY_RADIUS
			}
			double proxDist = MC.player == null ? Double.POSITIVE_INFINITY
				: MC.player.position().distanceTo(markerPos);
			if(proxDist <= PROXIMITY_RADIUS)
			{
				if(m.proximityEnteredAt == 0L)
					m.proximityEnteredAt = now;
				else if(now - m.proximityEnteredAt >= PROXIMITY_REQUIRED_MS)
				{
					markers.remove(m);
					continue;
				}
			}else
			{
				m.proximityEnteredAt = 0L;
			}
			
			// draw tracer if enabled
			if(tracersEnabled.isChecked())
			{
				RenderUtils.drawTracer(matrixStack, partialTicks, markerPos,
					color, false);
			}
			
			// draw a simple block-sized box for all markers
			AABB box = new AABB(markerPos.x - 0.5, markerPos.y - 0.5,
				markerPos.z - 0.5, markerPos.x + 0.5, markerPos.y + 0.5,
				markerPos.z + 0.5);
			RenderUtils.drawSolidBox(matrixStack, box, color, false);
		}
	}
}
