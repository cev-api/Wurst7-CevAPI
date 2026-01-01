/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

// ### ADDED IMPORTS ###
import static net.wurstclient.WurstClient.MC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// use internal SliderSetting.ValueDisplay; external GUI class not available

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
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
import net.wurstclient.events.PacketInputListener; // ### ADDED ###
import net.wurstclient.events.PacketInputListener.PacketInputEvent; // ### ADDED
																	// ###
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
	
	private final List<EventMarker> markers = new CopyOnWriteArrayList<>();
	
	private enum EventCategory
	{
		DOORS,
		PORTALS,
		REDSTONE,
		MOBS,
		MISC
	}
	
	private static final Map<Integer, EventCategory> EVENT_CATEGORIES =
		new HashMap<>(); // id -> category
	private static final Map<Integer, String> EVENT_NAMES = new HashMap<>(); // id
																				// ->
																				// constant
																				// name
	
	static
	{
		// ########## DIRECT COPY OF WorldEvents IDS (with names) ##########
		// Doors / trapdoors
		register(1005, "IRON_DOOR_OPENS", EventCategory.DOORS);
		register(1011, "IRON_DOOR_CLOSES", EventCategory.DOORS);
		register(1006, "WOODEN_DOOR_OPENS", EventCategory.DOORS);
		register(1012, "WOODEN_DOOR_CLOSES", EventCategory.DOORS);
		register(1007, "WOODEN_TRAPDOOR_OPENS", EventCategory.DOORS);
		register(1013, "WOODEN_TRAPDOOR_CLOSES", EventCategory.DOORS);
		register(1008, "FENCE_GATE_OPENS", EventCategory.DOORS);
		register(1014, "FENCE_GATE_CLOSES", EventCategory.DOORS);
		
		// Portals / gateways
		register(1032, "TRAVEL_THROUGH_PORTAL", EventCategory.PORTALS);
		register(1038, "END_PORTAL_OPENED", EventCategory.PORTALS);
		register(3000, "END_GATEWAY_SPAWNS", EventCategory.PORTALS);
		register(1503, "END_PORTAL_FRAME_FILLED", EventCategory.PORTALS);
		
		// Redstone / technical
		register(2000, "DISPENSER_ACTIVATED", EventCategory.REDSTONE);
		register(1000, "DISPENSER_DISPENSES", EventCategory.REDSTONE);
		register(1001, "DISPENSER_FAILS", EventCategory.REDSTONE);
		register(1002, "DISPENSER_LAUNCHES_PROJECTILE", EventCategory.REDSTONE);
		register(1500, "COMPOSTER_USED", EventCategory.REDSTONE);
		register(1501, "LAVA_EXTINGUISHED", EventCategory.REDSTONE);
		register(1502, "REDSTONE_TORCH_BURNS_OUT", EventCategory.REDSTONE);
		register(1504, "POINTED_DRIPSTONE_DRIPS", EventCategory.REDSTONE);
		register(1046, "POINTED_DRIPSTONE_DRIPS_LAVA_INTO_CAULDRON",
			EventCategory.REDSTONE);
		register(1047, "POINTED_DRIPSTONE_DRIPS_WATER_INTO_CAULDRON",
			EventCategory.REDSTONE);
		register(1505, "BONE_MEAL_USED", EventCategory.REDSTONE);
		register(2003, "EYE_OF_ENDER_BREAKS", EventCategory.REDSTONE);
		register(1003, "EYE_OF_ENDER_LAUNCHES", EventCategory.REDSTONE);
		register(2005, "PLANT_FERTILIZED", EventCategory.REDSTONE);
		register(2006, "DRAGON_BREATH_CLOUD_SPAWNS", EventCategory.REDSTONE);
		register(2004, "SPAWNER_SPAWNS_MOB", EventCategory.REDSTONE);
		register(3002, "ELECTRICITY_SPARKS", EventCategory.REDSTONE);
		register(3003, "BLOCK_WAXED", EventCategory.REDSTONE);
		register(3004, "WAX_REMOVED", EventCategory.REDSTONE);
		register(3005, "BLOCK_SCRAPED", EventCategory.REDSTONE);
		
		// Mobs / combat
		register(1018, "BLAZE_SHOOTS", EventCategory.MOBS);
		register(1017, "ENDER_DRAGON_SHOOTS", EventCategory.MOBS);
		register(1016, "GHAST_SHOOTS", EventCategory.MOBS);
		register(1015, "GHAST_WARNS", EventCategory.MOBS);
		register(1023, "WITHER_SPAWNS", EventCategory.MOBS);
		register(1024, "WITHER_SHOOTS", EventCategory.MOBS);
		register(1022, "WITHER_BREAKS_BLOCK", EventCategory.MOBS);
		register(1028, "ENDER_DRAGON_DIES", EventCategory.MOBS);
		register(2008, "ENDER_DRAGON_BREAKS_BLOCK", EventCategory.MOBS);
		register(3001, "ENDER_DRAGON_RESURRECTED", EventCategory.MOBS);
		register(1025, "BAT_TAKES_OFF", EventCategory.MOBS);
		register(1026, "ZOMBIE_INFECTS_VILLAGER", EventCategory.MOBS);
		register(1027, "ZOMBIE_VILLAGER_CURED", EventCategory.MOBS);
		register(1019, "ZOMBIE_ATTACKS_WOODEN_DOOR", EventCategory.MOBS);
		register(1020, "ZOMBIE_ATTACKS_IRON_DOOR", EventCategory.MOBS);
		register(1021, "ZOMBIE_BREAKS_WOODEN_DOOR", EventCategory.MOBS);
		register(1040, "ZOMBIE_CONVERTS_TO_DROWNED", EventCategory.MOBS);
		register(1041, "HUSK_CONVERTS_TO_ZOMBIE", EventCategory.MOBS);
		register(1048, "SKELETON_CONVERTS_TO_STRAY", EventCategory.MOBS);
		register(1039, "PHANTOM_BITES", EventCategory.MOBS);
		
		// Misc / ambience / utility
		register(1029, "ANVIL_DESTROYED", EventCategory.MISC);
		register(1030, "ANVIL_USED", EventCategory.MISC);
		register(1031, "ANVIL_LANDS", EventCategory.MISC);
		register(1033, "CHORUS_FLOWER_GROWS", EventCategory.MISC);
		register(1034, "CHORUS_FLOWER_DIES", EventCategory.MISC);
		register(1004, "FIREWORK_ROCKET_SHOOTS", EventCategory.MISC);
		register(1009, "FIRE_EXTINGUISHED", EventCategory.MISC);
		register(1504, "POINTED_DRIPSTONE_DRIPS", EventCategory.MISC); // also
																		// in
																		// redstone,
																		// keep
																		// mapping
		register(1045, "POINTED_DRIPSTONE_LANDS", EventCategory.MISC);
		register(1010, "MUSIC_DISC_PLAYED", EventCategory.MISC);
		register(1035, "BREWING_STAND_BREWS", EventCategory.MISC);
		register(1042, "GRINDSTONE_USED", EventCategory.MISC);
		register(1043, "LECTERN_BOOK_PAGE_TURNED", EventCategory.MISC);
		register(1044, "SMITHING_TABLE_USED", EventCategory.MISC);
		register(1505, "BONE_MEAL_USED", EventCategory.MISC);
		register(1501, "LAVA_EXTINGUISHED", EventCategory.MISC);
		register(2001, "BLOCK_BROKEN", EventCategory.MISC);
		register(2002, "SPLASH_POTION_SPLASHED", EventCategory.MISC);
		register(2007, "INSTANT_SPLASH_POTION_SPLASHED", EventCategory.MISC);
		register(2009, "WET_SPONGE_DRIES_OUT", EventCategory.MISC);
	}
	
	private static void register(int id, String name, EventCategory category)
	{
		EVENT_NAMES.put(id, name);
		EVENT_CATEGORIES.put(id, category);
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
		super("CoordLogger"); // ### MODIFIED ### (Hack(String) only)
		setCategory(Category.OTHER); // you can move it to a better category if
										// you want
		
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
		if(entity == null)
			return;
		
		// Mojmap names
		Vec3 oldPos = entity.position();
		if(packet.change() == null || packet.change().position() == null)
			return;
		Vec3 newPos = packet.change().position();
		
		double dist = oldPos.distanceTo(newPos);
		if(dist < minDistance.getValue())
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
		
		String entityName = entity.getName().getString();
		String msg = String.format(
			"[CoordLogger] Teleport: %s (id=%d) -> x=%.1f, y=%.1f, z=%.1f (Δ=%.1f)",
			entityName, entity.getId(), newPos.x, newPos.y, newPos.z, dist);
		
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
		
		// Restrict to specific events: END_PORTAL_OPENED (1038),
		// EYE_OF_ENDER_BREAKS (2003), WITHER_SPAWNS (1023), BLOCK_BROKEN (2001)
		if(id != 1038 && id != 2003 && id != 1023 && id != 2001)
			return;
			
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
		
		String name = EVENT_NAMES.getOrDefault(id, "UNKNOWN");
		EventCategory cat = EVENT_CATEGORIES.get(id);
		
		if(cat == null)
		{
			if(!logUnknown.isChecked())
				return;
		}else
		{
			
			if(cat == EventCategory.PORTALS && !logPortals.isChecked())
				return;
			if(cat == EventCategory.REDSTONE && !logRedstone.isChecked())
				return;
			if(cat == EventCategory.MOBS && !logMobs.isChecked())
				return;
			if(cat == EventCategory.MISC && !logMisc.isChecked())
				return;
		}
		
		String catName = (cat == null ? "UNKNOWN_CAT" : cat.name());
		
		// base message (direction appended later for far events when possible)
		final String baseMsg = String.format(
			"[CoordLogger] WorldEvent id=%d (%s, %s) at x=%d, y=%d, z=%d (dist=%.1f)",
			id, name, catName, pos.getX(), pos.getY(), pos.getZ(), dist);
		
		// Create a render marker for this event (near vs far visual)
		if(MC != null && MC.player != null)
		{
			Vec3 eventVec =
				new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			boolean isFar = dist > NEAR_EVENT_DISTANCE;
			EventMarker em = new EventMarker(eventVec, isFar, id, name, cat);
			markers.add(em);
			
			// Append compass direction for far events
			String finalMsg = baseMsg;
			if(isFar && MC != null && MC.player != null)
			{
				String dirStr =
					getCompassDirection(MC.player.position(), eventVec);
				finalMsg = baseMsg + " dir=" + dirStr;
			}
			final String toSend = finalMsg;
			if(MC != null)
				MC.execute(() -> ChatUtils.message(toSend));
			else
				ChatUtils.message(toSend);
		}
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
