/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.Color;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.DeathListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;
import net.wurstclient.waypoints.WaypointsManager;

@SearchTags({"waypoint", "waypoints", "marker"})
public final class WaypointsHack extends Hack
	implements RenderListener, DeathListener,
	net.wurstclient.events.UpdateListener, ChatInputListener, GUIRenderListener
{
	private final WaypointsManager manager;
	private static final DateTimeFormatter TIME_FMT =
		DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final double DISTANCE_SLIDER_INFINITE = 10001.0;
	private static final String[] BOSS_HUD_METHOD_NAMES =
		{"getBossOverlay", "getBossHud", "getBossBarHud", "getBossBars"};
	private static final String[] BOSS_BAR_FIELD_NAMES = {"events", "bossBars"};
	private static Method bossHudMethod;
	private static boolean bossHudMethodResolved;
	private static Field bossBarsField;
	private static boolean bossBarsFieldResolved;
	private static final double PORTAL_DUPLICATE_RADIUS = 5.0;
	private static final double PORTAL_DUPLICATE_RADIUS_SQ =
		PORTAL_DUPLICATE_RADIUS * PORTAL_DUPLICATE_RADIUS;
	private static final long PORTAL_PAIR_TIMEOUT_MS = 30000;
	private static final Pattern PORTAL_NAME_PATTERN =
		Pattern.compile("^Portal (\\d+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern END_PORTAL_NAME_PATTERN =
		Pattern.compile("^End Portal (\\d+)$", Pattern.CASE_INSENSITIVE);
	private static final long PORTAL_RECORD_COOLDOWN_MS = 2000L;
	
	private String worldId = "default";
	private boolean hasLoadedWorldData;
	private BlockPos lastDeathAt;
	private long lastDeathCreatedMs;
	// Track recent death times to avoid duplicates per player
	private final Map<UUID, Long> otherDeathCooldown = new HashMap<>();
	// Track current dead state for edge detection
	private final Set<UUID> knownDead = new HashSet<>();
	// Guard to avoid handling our own injected chat messages
	private boolean sendingOwnChat = false;
	
	private final SliderSetting waypointRenderDistance = new SliderSetting(
		"Waypoint render distance", 127, 0, DISTANCE_SLIDER_INFINITE, 1,
		ValueDisplay.INTEGER.withLabel(DISTANCE_SLIDER_INFINITE, "Infinite"));
	private final SliderSetting fadeDistance = new SliderSetting(
		"Waypoint fade distance", 20, 0, 1000, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxDeathPositions = new SliderSetting(
		"Max death positions", 4, 0, 20, 1, ValueDisplay.INTEGER);
	private final SliderSetting labelScale = new SliderSetting("Label scale",
		1.0, 0.5, 5.0, 0.1, ValueDisplay.DECIMAL);
	private final CheckboxSetting chatOnDeath =
		new CheckboxSetting("Say death position in chat", true);
	private final CheckboxSetting createDeathWaypoints =
		new CheckboxSetting("Create death waypoints", true);
	private final CheckboxSetting trackOtherDeaths =
		new CheckboxSetting("Track other players' deaths", false);
	private final CheckboxSetting deathWaypointLines =
		new CheckboxSetting("Death waypoint lines", true)
		{
			@Override
			public void update()
			{
				super.update();
				applyDeathWaypointLinesSetting();
			}
		};
	private final ColorSetting deathColor =
		new ColorSetting("Death waypoint color", new java.awt.Color(0xFF4444));
	private final CheckboxSetting compassMode =
		new CheckboxSetting("Compass mode (top bar)", false);
	private final SliderSetting compassIconRange = new SliderSetting(
		"Compass icon range", 5000, 0, DISTANCE_SLIDER_INFINITE, 1,
		ValueDisplay.INTEGER.withLabel(DISTANCE_SLIDER_INFINITE, "Infinite"));
	private final SliderSetting compassBackgroundOpacity = new SliderSetting(
		"Compass background opacity", 50, 0, 100, 1, ValueDisplay.INTEGER);
	// Opacity slider for compass text (0-100%)
	private final SliderSetting compassOpacity = new SliderSetting(
		"Compass text opacity", 100, 0, 100, 1, ValueDisplay.INTEGER);
	private final SliderSetting beaconRenderDistance = new SliderSetting(
		"Beacon render distance", 1000, 0, DISTANCE_SLIDER_INFINITE, 1,
		ValueDisplay.INTEGER.withLabel(DISTANCE_SLIDER_INFINITE, "Infinite"));
	// Compass position sliders (percent of screen)
	private final SliderSetting compassXPercent =
		new SliderSetting("Compass X %", 50, 0, 100, 1, ValueDisplay.INTEGER);
	private final SliderSetting compassYPercent =
		new SliderSetting("Compass Y %", 3, 0, 100, 1, ValueDisplay.INTEGER);
	// Show player XYZ above the compass
	private final CheckboxSetting showPlayerCoordsAboveCompass =
		new CheckboxSetting("Show player XYZ above compass", false);
	private final CheckboxSetting recordPortals = new CheckboxSetting(
		"Record portals",
		"Automatically create a waypoint whenever you travel through a portal.",
		true);
	private final ColorSetting portalWaypointColor =
		new ColorSetting("Portal waypoint color", new Color(0xB780FF));
	
	private final Set<java.util.UUID> tempWaypoints = new HashSet<>();
	private BlockPos lastPortalRecordedPos;
	private long lastPortalRecordedMs;
	private BlockPos lastPortalDetectionPos;
	private String pendingPortalName;
	private PortalKind pendingPortalKind;
	private WaypointDimension pendingPortalOrigin;
	private long pendingPortalStartMs;
	
	public WaypointsHack()
	{
		super("Waypoints");
		setCategory(Category.RENDER);
		this.manager = new WaypointsManager(WURST.getWurstFolder());
		addSetting(new net.wurstclient.settings.WaypointsSetting(
			"Manage waypoints", this.manager));
		
		addSetting(waypointRenderDistance);
		addSetting(fadeDistance);
		addSetting(beaconRenderDistance);
		addSetting(labelScale);
		addSetting(compassMode);
		addSetting(showPlayerCoordsAboveCompass);
		addSetting(compassIconRange);
		addSetting(compassXPercent);
		addSetting(compassYPercent);
		addSetting(compassOpacity);
		addSetting(compassBackgroundOpacity);
		addSetting(createDeathWaypoints);
		addSetting(chatOnDeath);
		addSetting(deathWaypointLines);
		addSetting(trackOtherDeaths);
		addSetting(maxDeathPositions);
		addSetting(deathColor);
		addSetting(recordPortals);
		addSetting(portalWaypointColor);
	}
	
	@Override
	protected void onEnable()
	{
		ensureWorldData();
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(net.wurstclient.events.UpdateListener.class, this);
		EVENTS.add(DeathListener.class, this);
		EVENTS.add(ChatInputListener.class, this);
		// Register GUI render listener for compass bar
		EVENTS.add(GUIRenderListener.class, this);
		otherDeathCooldown.clear();
		knownDead.clear();
	}
	
	@Override
	protected void onDisable()
	{
		saveExcludingTemporaries();
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(net.wurstclient.events.UpdateListener.class, this);
		EVENTS.remove(DeathListener.class, this);
		EVENTS.remove(ChatInputListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		otherDeathCooldown.clear();
		knownDead.clear();
	}
	
	// Add a temporary waypoint that should not be persisted. Returns the
	// created waypoint UUID.
	public java.util.UUID addTemporaryWaypoint(Waypoint w)
	{
		ensureWorldData();
		manager.addOrUpdate(w);
		tempWaypoints.add(w.getUuid());
		// Do not persist permanently; but update file without temporaries
		saveExcludingTemporaries();
		return w.getUuid();
	}
	
	public void removeTemporaryWaypoint(java.util.UUID uuid)
	{
		ensureWorldData();
		// remove from manager and from temp tracking
		// find waypoint by uuid
		Waypoint toRemove = null;
		for(Waypoint wp : new ArrayList<>(manager.all()))
			if(wp.getUuid().equals(uuid))
			{
				toRemove = wp;
				break;
			}
		if(toRemove != null)
		{
			manager.remove(toRemove);
			tempWaypoints.remove(uuid);
			saveExcludingTemporaries();
		}
	}
	
	public void addWaypointFromCommand(Waypoint waypoint)
	{
		if(waypoint == null)
			return;
		ensureWorldData();
		manager.addOrUpdate(waypoint);
		saveExcludingTemporaries();
	}
	
	private void saveExcludingTemporaries()
	{
		if(tempWaypoints.isEmpty())
		{
			manager.save(worldId);
			return;
		}
		// Backup temporary waypoints
		ArrayList<Waypoint> backups = new ArrayList<>();
		for(Waypoint w : new ArrayList<>(manager.all()))
			if(tempWaypoints.contains(w.getUuid()))
				backups.add(w);
		// Remove temporaries from manager
		for(Waypoint w : backups)
			manager.remove(w);
		// Save without temporaries
		manager.save(worldId);
		// Re-add temporaries
		for(Waypoint w : backups)
			manager.addOrUpdate(w);
	}
	
	@Override
	public void onUpdate()
	{
		ensureWorldData();
		updatePortalAutoRecording();
		// Detect deaths of other players
		if(trackOtherDeaths.isChecked() && MC.level != null
			&& MC.player != null)
		{
			long now = System.currentTimeMillis();
			for(var p : MC.level.players())
			{
				if(p == MC.player)
					continue;
				UUID id = p.getUUID();
				boolean deadNow = p.getHealth() <= 0 || p.isDeadOrDying();
				boolean wasDead = knownDead.contains(id);
				if(deadNow && !wasDead)
				{
					knownDead.add(id);
					long last = otherDeathCooldown.getOrDefault(id, 0L);
					if(now - last >= 10000)
					{
						BlockPos at = p.blockPosition().above(2);
						Waypoint w = new Waypoint(UUID.randomUUID(), now);
						String name = p.getName().getString();
						w.setName("Death of " + name + " "
							+ TIME_FMT.format(LocalTime.now()));
						w.setIcon("skull");
						w.setColor(deathColor.getColorI());
						w.setPos(at);
						w.setDimension(currentDim());
						w.setActionWhenNear(Waypoint.ActionWhenNear.DELETE);
						w.setActionWhenNearDistance(4);
						w.setLines(deathWaypointLines.isChecked());
						// Always save and prune
						manager.addOrUpdate(w);
						pruneDeaths();
						saveExcludingTemporaries();
						// Announce in chat if enabled (guard recursion)
						if(chatOnDeath.isChecked())
						{
							sendingOwnChat = true;
							try
							{
								MC.player.displayClientMessage(
									Component.literal(
										name + " died at " + at.getX() + ", "
											+ at.getY() + ", " + at.getZ()),
									false);
							}finally
							{
								sendingOwnChat = false;
							}
						}
						otherDeathCooldown.put(id, now);
					}
				}else if(!deadNow && wasDead)
				{
					knownDead.remove(id);
				}
			}
			// removed: pruneTempOtherDeaths();
		}
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(sendingOwnChat)
			return; // don't react to our own injected messages
		if(!trackOtherDeaths.isChecked() || MC.level == null
			|| MC.player == null)
			return;
		String msg = event.getComponent().getString();
		if(msg == null || msg.isEmpty())
			return;
		String lower = msg.toLowerCase(Locale.ROOT);
		if(lower.contains("left the game") || lower.contains("joined the game"))
			return; // ignore login/logout messages
			
		// Ignore achievement / advancement messages which often start with
		// "<player> made the achievement" or similar. These should not
		// be treated as death messages and were causing false death
		// waypoints when servers include coordinates in achievement chat.
		if(lower.contains("made the achievement")
			|| lower.contains("made the advancement")
			|| lower.contains("got the achievement")
			|| lower.contains("got the advancement")
			|| lower.contains("earned the achievement")
			|| lower.contains("earned the advancement")
			|| lower.contains("has made the advancement")
			|| lower.contains("has made the achievement")
			|| (lower.contains("advancement") && lower.contains("completed"))
			|| (lower.contains("completed")
				&& (lower.contains("challenge") || lower.contains("advancement")
					|| lower.contains("achievement"))))
			return;
		
		long now = System.currentTimeMillis();
		// Try to match standard death messages: "<name> ..."
		for(var p : MC.level.players())
		{
			if(p == MC.player)
				continue;
			String name = p.getName().getString();
			if(name == null || name.isEmpty())
				continue;
			if(!msg.startsWith(name + " "))
				continue;
			UUID id = p.getUUID();
			long last = otherDeathCooldown.getOrDefault(id, 0L);
			if(now - last < 10000)
				return; // cooldown
			BlockPos at = p.blockPosition().above(2);
			Waypoint w = new Waypoint(UUID.randomUUID(), now);
			w.setName(
				"Death of " + name + " " + TIME_FMT.format(LocalTime.now()));
			w.setIcon("skull");
			w.setColor(deathColor.getColorI());
			w.setPos(at);
			w.setDimension(currentDim());
			w.setActionWhenNear(Waypoint.ActionWhenNear.DELETE);
			w.setActionWhenNearDistance(4);
			w.setLines(deathWaypointLines.isChecked());
			// Always save and prune
			manager.addOrUpdate(w);
			pruneDeaths();
			saveExcludingTemporaries();
			otherDeathCooldown.put(id, now);
			
			// If chat is enabled, append coordinates to the incoming line
			if(chatOnDeath.isChecked())
			{
				// Safe-append to avoid immutable siblings crash
				MutableComponent oldText =
					(MutableComponent)event.getComponent();
				MutableComponent newText =
					MutableComponent.create(oldText.getContents());
				newText.setStyle(oldText.getStyle());
				oldText.getSiblings().forEach(newText::append);
				newText.append(
					" at " + at.getX() + ", " + at.getY() + ", " + at.getZ());
				event.setComponent(newText);
			}
			return;
		}
	}
	
	private void updatePortalAutoRecording()
	{
		if(!recordPortals.isChecked())
		{
			lastPortalDetectionPos = null;
			clearPendingPortal();
			return;
		}
		
		BlockPos portal = detectPortalBlockNearPlayer();
		if(portal == null)
		{
			lastPortalDetectionPos = null;
			clearPendingPortalIfExpired();
			return;
		}
		
		if(lastPortalDetectionPos != null
			&& lastPortalDetectionPos.equals(portal))
			return;
		
		lastPortalDetectionPos = portal;
		recordPortalWaypoint(portal);
	}
	
	private void clearPendingPortal()
	{
		pendingPortalName = null;
		pendingPortalKind = null;
		pendingPortalOrigin = null;
		pendingPortalStartMs = 0L;
	}
	
	private void clearPendingPortalIfExpired()
	{
		if(pendingPortalName == null)
			return;
		if(System.currentTimeMillis()
			- pendingPortalStartMs > PORTAL_PAIR_TIMEOUT_MS)
			clearPendingPortal();
	}
	
	private BlockPos detectPortalBlockNearPlayer()
	{
		if(MC.player == null || MC.level == null)
			return null;
		
		AABB box = MC.player.getBoundingBox().inflate(0.05);
		int minX = (int)Math.floor(box.minX);
		int minY = (int)Math.floor(box.minY);
		int minZ = (int)Math.floor(box.minZ);
		int maxX = (int)Math.floor(box.maxX);
		int maxY = (int)Math.floor(box.maxY);
		int maxZ = (int)Math.floor(box.maxZ);
		
		for(int x = minX; x <= maxX; x++)
			for(int y = minY; y <= maxY; y++)
				for(int z = minZ; z <= maxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					if(classifyPortal(pos) != null)
						return pos.immutable();
					BlockPos below = pos.below();
					if(classifyPortal(below) != null)
						return below.immutable();
				}
			
		return null;
	}
	
	private void recordPortalWaypoint(BlockPos portalPos)
	{
		if(!recordPortals.isChecked() || portalPos == null)
			return;
		if(MC.player == null || MC.level == null)
			return;
		PortalKind kind = classifyPortal(portalPos);
		if(kind == null)
			return;
		long now = System.currentTimeMillis();
		if(lastPortalRecordedPos != null
			&& lastPortalRecordedPos.distSqr(portalPos) <= 1
			&& now - lastPortalRecordedMs < PORTAL_RECORD_COOLDOWN_MS)
			return;
		
		ensureWorldData();
		WaypointDimension dim = currentDim();
		BlockPos immutable = portalPos.immutable();
		boolean linkingPending =
			pendingPortalName != null && pendingPortalKind == kind
				&& pendingPortalOrigin != null && pendingPortalOrigin != dim
				&& now - pendingPortalStartMs <= PORTAL_PAIR_TIMEOUT_MS;
		
		String prefix = kind == PortalKind.END ? "End Portal" : "Portal";
		boolean isEndDimension = dim == WaypointDimension.END;
		String name;
		if(linkingPending)
			name = pendingPortalName;
		else if(kind == PortalKind.END && isEndDimension)
			name = "End Portal";
		else
			name = prefix + " " + nextPortalIndex(prefix, dim);
		
		if(hasNearbyPortalWaypoint(immutable, dim))
		{
			if(linkingPending)
				clearPendingPortal();
			return;
		}
		
		Waypoint waypoint = new Waypoint(UUID.randomUUID(), now);
		waypoint.setName(name);
		waypoint.setPos(immutable);
		waypoint.setDimension(dim);
		waypoint.setColor(portalWaypointColor.getColorI());
		waypoint.setOpposite(false);
		waypoint.setIcon("diamond");
		waypoint.setLines(false);
		waypoint.setBeaconMode(Waypoint.BeaconMode.OFF);
		manager.addOrUpdate(waypoint);
		saveExcludingTemporaries();
		
		lastPortalRecordedPos = immutable;
		lastPortalRecordedMs = now;
		
		if(linkingPending)
			clearPendingPortal();
		else if(kind == PortalKind.NETHER)
		{
			pendingPortalName = name;
			pendingPortalKind = kind;
			pendingPortalOrigin = dim;
			pendingPortalStartMs = now;
		}
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.player == null || MC.level == null)
			return;
		
		var list = new ArrayList<>(manager.all());
		double beaconRange = beaconRenderDistance.getValue();
		boolean beaconInfinite = beaconRange >= DISTANCE_SLIDER_INFINITE;
		boolean beaconsEnabled = beaconInfinite || beaconRange > 0.0;
		double beaconRangeSq = beaconInfinite ? Double.POSITIVE_INFINITY
			: beaconRange * beaconRange;
		// removed: tempOtherDeathWps rendering since we always save now
		for(Waypoint w : list)
		{
			if(!w.isVisible())
				continue;
			
			BlockPos wp = worldSpace(w);
			if(wp == null)
				continue;
			
			double distSq = MC.player.distanceToSqr(wp.getX() + 0.5,
				wp.getY() + 0.5, wp.getZ() + 0.5);
			double dist = Math.sqrt(distSq);
			double trd = waypointRenderDistance.getValue();
			boolean infiniteLabels = trd >= DISTANCE_SLIDER_INFINITE;
			boolean allowBySlider = trd > 0 && dist <= trd;
			boolean renderLabel = infiniteLabels || allowBySlider;
			boolean beyondMaxVisible =
				distSq > (double)(w.getMaxVisible() * w.getMaxVisible());
			// Don't skip entirely if the waypoint has lines enabled - lines
			// should render regardless of label visibility/distance.
			if(beyondMaxVisible && !renderLabel && !w.isLines())
				continue;
				
			// draw tracer + box if enabled. Lines should be rendered
			// regardless of beyondMaxVisible so users can always see
			// the tracer when the "lines" option is enabled.
			if(w.isLines())
			{
				RenderUtils.drawTracer(matrices, partialTicks,
					new Vec3(wp.getX() + 0.5, wp.getY() + 0.5, wp.getZ() + 0.5),
					applyFade(w.getColor(), distSq), false);
				RenderUtils.drawOutlinedBoxes(matrices,
					java.util.List.of(new AABB(wp)),
					applyFade(w.getColor(), distSq), false);
			}
			
			if(!beyondMaxVisible)
			{
				// Near action with 1s cooldown after creation
				if(System.currentTimeMillis() - w.getCreatedAt() >= 1000
					&& distSq <= (double)(w.getActionWhenNearDistance()
						* w.getActionWhenNearDistance()))
				{
					switch(w.getActionWhenNear())
					{
						case HIDE -> w.setVisible(false);
						case DELETE -> manager.remove(w);
						default ->
							{
							}
					}
				}
				
				Waypoint.BeaconMode beaconMode = waypointBeaconMode(w);
				if(beaconsEnabled && beaconMode != Waypoint.BeaconMode.OFF)
				{
					if(beaconInfinite || distSq <= beaconRangeSq)
						drawBeaconBeam(matrices, wp,
							applyFade(w.getColor(), distSq), beaconMode);
				}
			}
			
			if(renderLabel)
			{
				String title = w.getName() == null ? "" : w.getName();
				String icon = iconChar(w.getIcon());
				if(!icon.isEmpty())
					title = icon + (title.isEmpty() ? "" : " " + title);
				String distanceText = (int)dist + " blocks";
				double baseY = wp.getY() + 1.2;
				double lx = wp.getX() + 0.5;
				double ly = baseY;
				double lz = wp.getZ() + 0.5;
				// Anchor at a near, fixed distance when either:
				// - Infinite labels are enabled (override distance completely),
				// or
				// - The label is extremely far away (to avoid engine culling),
				// even if we're still within the slider distance.
				boolean needAnchor = infiniteLabels || dist > 256.0;
				if(needAnchor)
				{
					Vec3 cam = RenderUtils.getCameraPos();
					Vec3 target = new Vec3(lx, ly, lz);
					Vec3 dir = target.subtract(cam);
					double len = dir.length();
					if(len > 1e-3)
					{
						double anchor = Math.min(len, 12.0);
						Vec3 anchored = cam.add(dir.scale(anchor / len));
						lx = anchored.x;
						ly = anchored.y;
						lz = anchored.z;
					}
				}
				float scale = (float)labelScale.getValue();
				boolean anchored = needAnchor;
				// When not anchored, compensate by distance so on-screen size
				// remains approximately constant.
				if(!anchored)
				{
					Vec3 cam2 = RenderUtils.getCameraPos();
					double dLabel = cam2.distanceTo(new Vec3(lx, ly, lz));
					double compensate = Math.max(1.0, dLabel * 0.1);
					scale *= (float)compensate;
				}
				// Keep a constant 10px separation using local pixel offset
				float sepPx = 10.0f;
				drawWorldLabel(matrices, title, lx, ly, lz,
					applyFade(w.getColor(), distSq), scale, -sepPx);
				drawWorldLabel(matrices, distanceText, lx, ly, lz,
					applyFade(w.getColor(), distSq), (float)(scale * 0.9f), 0f);
			}
		}
	}
	
	@Override
	public void onDeath()
	{
		if(MC.player == null)
			return;
		
		BlockPos at = MC.player.blockPosition().above(2);
		long now = System.currentTimeMillis();
		if(lastDeathAt != null && lastDeathAt.equals(at)
			&& now - lastDeathCreatedMs < 10000)
			return; // avoid multiple waypoints per death screen
			
		// Optional death chat, regardless of creating a waypoint
		if(chatOnDeath.isChecked())
			MC.player.displayClientMessage(Component.literal(
				"Died at " + at.getX() + ", " + at.getY() + ", " + at.getZ()),
				false);
		
		if(createDeathWaypoints.isChecked())
		{
			Waypoint w = new Waypoint(UUID.randomUUID(), now);
			w.setName("Death " + TIME_FMT.format(LocalTime.now()));
			w.setIcon("skull");
			w.setColor(deathColor.getColorI());
			w.setPos(at);
			w.setDimension(currentDim());
			w.setActionWhenNear(Waypoint.ActionWhenNear.DELETE);
			w.setActionWhenNearDistance(4);
			w.setLines(deathWaypointLines.isChecked());
			manager.addOrUpdate(w);
			pruneDeaths();
			saveExcludingTemporaries();
		}
		
		lastDeathAt = at;
		lastDeathCreatedMs = now;
	}
	
	private WaypointDimension currentDim()
	{
		if(MC.level == null)
			return WaypointDimension.OVERWORLD;
		String key = MC.level.dimension().location().getPath();
		return switch(key)
		{
			case "the_nether" -> WaypointDimension.NETHER;
			case "the_end" -> WaypointDimension.END;
			default -> WaypointDimension.OVERWORLD;
		};
	}
	
	private String resolveWorldId()
	{
		ServerData s = MC.getCurrentServer();
		if(s != null && s.ip != null && !s.ip.isEmpty())
			return s.ip.replace(':', '_');
		return "singleplayer";
	}
	
	private void ensureWorldData()
	{
		String wid = resolveWorldId();
		if(!hasLoadedWorldData)
		{
			worldId = wid;
			manager.load(worldId);
			applyDeathWaypointLinesSetting();
			lastPortalDetectionPos = null;
			clearPendingPortal();
			hasLoadedWorldData = true;
			return;
		}
		
		if(!wid.equals(worldId))
		{
			saveExcludingTemporaries();
			worldId = wid;
			manager.load(worldId);
			applyDeathWaypointLinesSetting();
			lastPortalDetectionPos = null;
			clearPendingPortal();
		}
	}
	
	private BlockPos worldSpace(Waypoint w)
	{
		WaypointDimension pd = currentDim();
		WaypointDimension wd = w.getDimension();
		BlockPos p = w.getPos();
		if(pd == wd)
			return p;
		if(!w.isOpposite())
			return null;
		// Convert between Overworld and Nether
		if(pd == WaypointDimension.OVERWORLD && wd == WaypointDimension.NETHER)
			return new BlockPos(p.getX() * 8, p.getY(), p.getZ() * 8);
		if(pd == WaypointDimension.NETHER && wd == WaypointDimension.OVERWORLD)
			return new BlockPos(p.getX() / 8, p.getY(), p.getZ() / 8);
		return null;
	}
	
	private PortalKind classifyPortal(BlockPos pos)
	{
		if(pos == null || MC.level == null)
			return null;
		var state = MC.level.getBlockState(pos);
		if(state.is(Blocks.NETHER_PORTAL))
			return PortalKind.NETHER;
		if(state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY))
			return PortalKind.END;
		return null;
	}
	
	private boolean hasNearbyPortalWaypoint(BlockPos pos, WaypointDimension dim)
	{
		double maxSq = PORTAL_DUPLICATE_RADIUS_SQ;
		for(Waypoint w : manager.all())
		{
			if(w.getDimension() != dim)
				continue;
			if(pos.distSqr(w.getPos()) <= maxSq)
				return true;
		}
		return false;
	}
	
	private int nextPortalIndex(String prefix, WaypointDimension targetDim)
	{
		Pattern pattern = "End Portal".equals(prefix) ? END_PORTAL_NAME_PATTERN
			: PORTAL_NAME_PATTERN;
		int max = 0;
		for(Waypoint w : manager.all())
		{
			String name = w.getName();
			if(name == null)
				continue;
			if("End Portal".equals(prefix) && targetDim != WaypointDimension.END
				&& w.getDimension() == WaypointDimension.END)
				continue;
			Matcher matcher = pattern.matcher(name);
			if(!matcher.matches())
				continue;
			try
			{
				int num = Integer.parseInt(matcher.group(1));
				if(num > max)
					max = num;
			}catch(NumberFormatException ignored)
			{}
		}
		return max + 1;
	}
	
	private void pruneDeaths()
	{
		var all = manager.all();
		var deaths = new ArrayList<Waypoint>();
		for(Waypoint w : all)
			if(w.getName() != null && w.getName().startsWith("Death "))
				deaths.add(w);
		if(deaths.size() <= maxDeathPositions.getValueI())
			return;
		deaths.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
		int remove = deaths.size() - maxDeathPositions.getValueI();
		for(int i = 0; i < remove; i++)
			manager.remove(deaths.get(i));
	}
	
	private void applyDeathWaypointLinesSetting()
	{
		if(manager == null)
			return;
		
		boolean linesEnabled = deathWaypointLines.isChecked();
		boolean changed = false;
		for(Waypoint w : manager.all())
		{
			String name = w.getName();
			if(name == null)
				continue;
			
			if(name.startsWith("Death ") || name.startsWith("Death of "))
				if(w.isLines() != linesEnabled)
				{
					w.setLines(linesEnabled);
					changed = true;
				}
		}
		
		if(changed)
			saveExcludingTemporaries();
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb, float scale, float offsetPx)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		matrices.mulPose(MC.getEntityRenderDispatcher().cameraOrientation());
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		// After scaling, translate by pixel offset to separate lines
		// consistently.
		matrices.translate(0, offsetPx, 0);
		Font tr = MC.font;
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		float w = tr.width(text) / 2F;
		int bg = (int)(MC.options.getBackgroundOpacity(0.25F) * 255) << 24;
		var matrix = matrices.last().pose();
		tr.drawInBatch(text, -w, 0, argb, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, bg, 0xF000F0);
		vcp.endBatch();
		matrices.popPose();
	}
	
	private void drawBeaconBeam(PoseStack matrices, BlockPos pos, int color,
		Waypoint.BeaconMode mode)
	{
		if(MC.level == null)
			return;
		Waypoint.BeaconMode safeMode =
			mode == null ? Waypoint.BeaconMode.OFF : mode;
		if(safeMode == Waypoint.BeaconMode.OFF)
			return;
		int minY = MC.level.getMinBuildHeight();
		int maxY = MC.level.getMaxBuildHeight() + 1;
		double baseX = pos.getX();
		double baseZ = pos.getZ();
		double centerX = baseX + 0.5;
		double centerZ = baseZ + 0.5;
		boolean depthTest = safeMode != Waypoint.BeaconMode.ESP;
		int rgb = color & 0x00FFFFFF;
		int alpha = (color >>> 24) & 0xFF;
		if(alpha <= 0)
			alpha = 64;
		java.util.ArrayList<RenderUtils.ColoredBox> boxes =
			new java.util.ArrayList<>();
		boxes
			.add(new RenderUtils.ColoredBox(
				new AABB(centerX - 0.18, minY, centerZ - 0.18, centerX + 0.18,
					maxY, centerZ + 0.18),
				withAlpha(rgb, Math.min(255, alpha + 100))));
		boxes
			.add(
				new RenderUtils.ColoredBox(
					new AABB(centerX - 0.32, minY, centerZ - 0.05,
						centerX + 0.32, maxY, centerZ + 0.05),
					withAlpha(rgb, alpha / 2)));
		boxes
			.add(
				new RenderUtils.ColoredBox(
					new AABB(centerX - 0.05, minY, centerZ - 0.32,
						centerX + 0.05, maxY, centerZ + 0.32),
					withAlpha(rgb, alpha / 2)));
		boxes.add(new RenderUtils.ColoredBox(
			new AABB(baseX, minY, baseZ, baseX + 1, maxY, baseZ + 1),
			withAlpha(rgb, Math.max(30, alpha / 6))));
		RenderUtils.drawSolidBoxes(matrices, boxes, depthTest);
		if(safeMode == Waypoint.BeaconMode.ESP)
		{
			RenderUtils.drawOutlinedBoxes(matrices,
				java.util.List.of(
					new AABB(baseX, minY, baseZ, baseX + 1, maxY, baseZ + 1)),
				withAlpha(rgb, Math.max(alpha, 120)), false);
		}
	}
	
	private Waypoint.BeaconMode waypointBeaconMode(Waypoint waypoint)
	{
		Waypoint.BeaconMode mode = waypoint.getBeaconMode();
		return mode == null ? Waypoint.BeaconMode.OFF : mode;
	}
	
	private static int withAlpha(int rgb, int alpha)
	{
		int clamped = Math.max(0, Math.min(255, alpha));
		return (clamped << 24) | (rgb & 0x00FFFFFF);
	}
	
	private int applyFade(int argb, double distSq)
	{
		double fade = fadeDistance.getValue();
		if(fade <= 0)
			return argb;
		double dist = Math.sqrt(distSq);
		if(dist >= fade)
			return argb;
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		int na = (int)Math.max(0, Math.min(a, a * (dist / fade)));
		return (na << 24) | (r << 16) | (g << 8) | b;
	}
	
	private String iconChar(String icon)
	{
		if(icon == null)
			return "";
		switch(icon.toLowerCase())
		{
			case "square":
			return "■";
			case "circle":
			return "●";
			case "triangle":
			return "▲";
			case "triangle_down":
			return "▼";
			case "star":
			return "★";
			case "diamond":
			return "♦";
			case "skull":
			return "☠";
			case "heart":
			return "♥";
			case "check":
			return "✓";
			case "x":
			return "✗";
			case "arrow_down":
			return "↓";
			case "sun":
			return "☀";
			case "snowflake":
			return "❄";
			default:
			return "";
		}
	}
	
	public void openManager()
	{
		ensureWorldData();
		MC.setScreen(new net.wurstclient.clickgui.screens.WaypointsScreen(
			MC.screen, manager));
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
	{
		if(!compassMode.isChecked() || MC.player == null || MC.level == null)
			return;
		
		Font tr = MC.font;
		int sw = context.guiWidth();
		int sh = context.guiHeight();
		// Position from settings (percent of screen)
		int centerX =
			(int)Math.round(sw * (compassXPercent.getValue() / 100.0));
		int baseBarY =
			(int)Math.round(sh * (compassYPercent.getValue() / 100.0));
		int barY = adjustCompassYForOverlays(context, baseBarY);
		int barH = 8; // thinner (was 12)
		int pad = 6;
		int halfWidth = Math.min(120, Math.max(60, sw / 4)); // shorter
		double visibleAngle = 90.0; // degrees each side
		double selectFov = 6.0; // degrees for selection
		
		// background bar
		double bgOpacity = Math.max(0.0,
			Math.min(1.0, compassBackgroundOpacity.getValue() / 100.0));
		int bgAlpha = (int)Math.round(255 * bgOpacity);
		int bgColor = (bgAlpha << 24);
		context.fill(centerX - halfWidth - pad, barY - 2,
			centerX + halfWidth + pad, barY + barH + 2, bgColor);
		
		// Optionally draw player coords above the bar
		if(showPlayerCoordsAboveCompass.isChecked())
		{
			String dir = cardinalFromYaw(MC.player.getYRot());
			int ix = (int)Math.floor(MC.player.getX());
			int iy = (int)Math.floor(MC.player.getY());
			int iz = (int)Math.floor(MC.player.getZ());
			String coords = dir + ": " + ix + " " + iy + " " + iz;
			int cw = tr.width(coords);
			int cx = centerX - cw / 2;
			int cy = Math.max(2, barY - 13); // was 12, now 13
			context.drawString(tr, coords, cx, cy, 0xFFFFFFFF, false);
		}
		
		var list = new ArrayList<>(manager.all());
		ArrayList<WaypointEntry> entries = new ArrayList<>();
		double iconRange = compassIconRange.getValue();
		boolean infiniteIconRange = iconRange >= DISTANCE_SLIDER_INFINITE;
		double iconRangeSq = iconRange * iconRange;
		double bestAbs = Double.MAX_VALUE;
		WaypointEntry best = null;
		
		double px = MC.player.getX();
		double pz = MC.player.getZ();
		double playerYaw = MC.player.getYRot();
		
		for(Waypoint w : list)
		{
			if(!w.isVisible())
				continue;
			BlockPos wpb = worldSpace(w);
			if(wpb == null)
				continue;
			double dx = (wpb.getX() + 0.5) - px;
			double dz = (wpb.getZ() + 0.5) - pz;
			double distSq = MC.player.distanceToSqr(wpb.getX() + 0.5,
				wpb.getY() + 0.5, wpb.getZ() + 0.5);
			int waypointMax = w.getMaxVisible();
			double waypointMaxSq = (double)(waypointMax * waypointMax);
			boolean beyondWaypointMax = distSq > waypointMaxSq;
			if(!infiniteIconRange)
			{
				if(iconRange <= 0 && distSq > 0)
					continue;
				if(iconRange > 0 && distSq > iconRangeSq)
					continue;
				if(iconRange <= waypointMax && beyondWaypointMax)
					continue;
			}
			
			// Compute horizontal signed angle between player's forward vector
			// and the vector to the waypoint. This is robust to Minecraft yaw
			// conventions and avoids axis-misalignment issues.
			double len = Math.sqrt(dx * dx + dz * dz);
			if(len < 1e-6)
				continue;
			double nx = dx / len;
			double nz = dz / len;
			double yawRad = Math.toRadians(playerYaw);
			// Player forward vector in Minecraft: (-sin(yaw), cos(yaw))
			double fx = -Math.sin(yawRad);
			double fz = Math.cos(yawRad);
			// Signed angle: atan2(cross, dot)
			double cross = fx * nz - fz * nx;
			double dot = fx * nx + fz * nz;
			double delta = Math.toDegrees(Math.atan2(cross, dot));
			if(Math.abs(delta) > visibleAngle)
				continue;
			
			double x = centerX + (delta / visibleAngle) * halfWidth;
			WaypointEntry e = new WaypointEntry(w, x, delta);
			entries.add(e);
			double ad = Math.abs(delta);
			if(ad < bestAbs)
			{
				bestAbs = ad;
				best = e;
			}
		}
		
		// If best within small fov, mark selected
		WaypointEntry selected = null;
		if(best != null && Math.abs(best.delta) <= selectFov)
			selected = best;
		
		// Draw icons
		for(WaypointEntry e : entries)
		{
			int ix = (int)Math.round(e.x);
			if(selected != null && e.w.getUuid().equals(selected.w.getUuid()))
				ix = centerX; // center selected
			String icon = iconChar(e.w.getIcon());
			if(icon == null)
				icon = "";
			int color = e.w.getColor();
			int iconW = tr.width(icon);
			int iconY =
				(int)Math.round(barY + barH / 2.0 - tr.lineHeight / 2.0);
			context.drawString(tr, icon, ix - iconW / 2, iconY, color, false);
		}
		
		// Draw selected name and distance
		if(selected != null)
		{
			String title =
				selected.w.getName() == null ? "" : selected.w.getName();
			String icon = iconChar(selected.w.getIcon());
			if(icon != null && !icon.isEmpty())
				title = icon + (title.isEmpty() ? "" : " " + title);
			// distance in blocks
			BlockPos wpb = worldSpace(selected.w);
			int distBlocks = 0;
			if(wpb != null)
				distBlocks = (int)Math
					.round(Math.sqrt(MC.player.distanceToSqr(wpb.getX() + 0.5,
						wpb.getY() + 0.5, wpb.getZ() + 0.5)));
			String distText = distBlocks + " blocks";
			int tw = tr.width(title);
			int dw = tr.width(distText);
			int titleX = centerX - tw / 2;
			int distX = centerX - dw / 2;
			int titleY = barY + barH + 2;
			int distY = titleY + 10;
			// apply compass opacity to text
			double opaText =
				Math.max(0.0, Math.min(1.0, compassOpacity.getValue() / 100.0));
			int aText = (int)Math.round(255 * opaText);
			int textColor = (aText << 24) | 0x00FFFFFF;
			context.drawString(tr, title, titleX, titleY, textColor, false);
			context.drawString(tr, distText, distX, distY, textColor, false);
		}
	}
	
	private int adjustCompassYForOverlays(GuiGraphics context, int baseY)
	{
		int adjusted = baseY;
		int bossBarBottom = getBossBarBottom(context);
		if(bossBarBottom > 0)
		{
			int margin = showPlayerCoordsAboveCompass.isChecked() ? 10 : 2;
			adjusted = Math.max(adjusted, bossBarBottom + margin);
		}
		return adjusted;
	}
	
	private int getBossBarBottom(GuiGraphics context)
	{
		if(MC.gui == null)
			return 0;
		
		Object bossBarHud = getBossHud();
		if(bossBarHud == null)
			return 0;
		
		int barCount = getBossBarCount(bossBarHud);
		if(barCount == 0)
			return 0;
		
		int screenHeight = context.guiHeight();
		int maxY = screenHeight / 3;
		// Vanilla boss bars start at y=12 and advance by 19px per entry.
		int y = 12;
		for(int i = 0; i < barCount; i++)
		{
			if(y >= maxY)
				return maxY;
			y += 10; // default is 19px height, but using 10px as it looks
						// nicer, however,
						// when there's two boss bars it will overlap
			
		}
		return Math.min(y, maxY);
	}
	
	private static Object getBossHud()
	{
		Method method = getBossHudMethod();
		if(method == null || MC.gui == null)
			return null;
		
		try
		{
			return method.invoke(MC.gui);
		}catch(Exception e)
		{
			return null;
		}
	}
	
	private static Method getBossHudMethod()
	{
		if(bossHudMethodResolved)
			return bossHudMethod;
		bossHudMethodResolved = true;
		
		if(MC.gui == null)
			return null;
		
		for(String name : BOSS_HUD_METHOD_NAMES)
		{
			try
			{
				Method method = MC.gui.getClass().getMethod(name);
				method.setAccessible(true);
				bossHudMethod = method;
				return method;
			}catch(NoSuchMethodException ignored)
			{}
		}
		
		return null;
	}
	
	private static int getBossBarCount(Object bossHud)
	{
		Field field = getBossBarsField(bossHud);
		if(field == null)
			return 0;
		
		try
		{
			Object value = field.get(bossHud);
			if(value instanceof Map<?, ?> map)
				return map.size();
			if(value instanceof Collection<?> collection)
				return collection.size();
		}catch(IllegalAccessException ignored)
		{}
		
		return 0;
	}
	
	private static Field getBossBarsField(Object bossHud)
	{
		if(bossBarsFieldResolved)
			return bossBarsField;
		bossBarsFieldResolved = true;
		
		if(bossHud == null)
			return null;
		
		Class<?> cls = bossHud.getClass();
		for(String name : BOSS_BAR_FIELD_NAMES)
		{
			try
			{
				Field field = cls.getDeclaredField(name);
				field.setAccessible(true);
				bossBarsField = field;
				return field;
			}catch(NoSuchFieldException ignored)
			{}
		}
		
		for(Field field : cls.getDeclaredFields())
		{
			Class<?> type = field.getType();
			if(Map.class.isAssignableFrom(type)
				|| Collection.class.isAssignableFrom(type))
			{
				field.setAccessible(true);
				bossBarsField = field;
				return field;
			}
		}
		
		return null;
	}
	
	private static String cardinalFromYaw(double yaw)
	{
		// Normalize yaw to [0,360)
		double norm = yaw % 360.0;
		if(norm < 0)
			norm += 360.0;
		int idx = (int)Math.floor((norm + 22.5) / 45.0) % 8;
		switch(idx)
		{
			case 0:
			return "S";
			case 1:
			return "SW";
			case 2:
			return "W";
			case 3:
			return "NW";
			case 4:
			return "N";
			case 5:
			return "NE";
			case 6:
			return "E";
			case 7:
			return "SE";
			default:
			return "?";
		}
	}
	
	private static final class WaypointEntry
	{
		final Waypoint w;
		final double x;
		final double delta;
		
		WaypointEntry(Waypoint w, double x, double delta)
		{
			this.w = w;
			this.x = x;
			this.delta = delta;
		}
	}
	
	private enum PortalKind
	{
		NETHER,
		END
	}
}
