/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.util.ARGB;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.TrackedWaypointAzimuthAccessor;
import net.wurstclient.mixin.TrackedWaypointIconAccessor;
import net.wurstclient.mixin.WaypointIconColorAccessor;
import net.wurstclient.hacks.locator.LocatorObservation;
import net.wurstclient.hacks.locator.LocatorTriangulator;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"locator", "triangulation", "player locator", "waypoint"})
public final class LocatorTriangulationHack extends Hack
	implements PacketInputListener, UpdateListener, GUIRenderListener
{
	private enum State
	{
		SEARCHING,
		CANDIDATE,
		LOCKED,
		FACE_TARGET,
		TURN_PERPENDICULAR,
		MOVING,
		CALCULATING,
		RESULT,
		LOST
	}
	
	private record Waypoint(UUID uuid, float yawRadians, int color,
		long updatedAt)
	{}
	
	private final Map<UUID, Waypoint> waypoints = new HashMap<>();
	private final Map<UUID, List<LocatorObservation>> history = new HashMap<>();
	private final SliderSetting acquisitionAngle = new SliderSetting(
		"Acquisition angle", 7, 1, 30, 1, ValueDisplay.INTEGER.withSuffix("°"));
	private final SliderSetting holdTime = new SliderSetting("Look hold time",
		3, 1, 10, 1, ValueDisplay.INTEGER.withSuffix("s"));
	private final StringDropdownSetting baselineSide =
		new StringDropdownSetting("Baseline side",
			"Choose the perpendicular turn direction.");
	private final SliderSetting initialBaseline = new SliderSetting(
		"Initial baseline", 256, 32, 8192, 32, ValueDisplay.INTEGER);
	private final SliderSetting maximumBaseline = new SliderSetting(
		"Maximum baseline", 8192, 256, 32768, 256, ValueDisplay.INTEGER);
	private final CheckboxSetting autoExtend =
		new CheckboxSetting("Auto extend baseline",
			"Keep moving when parallax is too small.", true);
	private final CheckboxSetting hud =
		new CheckboxSetting("HUD instructions", true);
	private final CheckboxSetting chatResults =
		new CheckboxSetting("Chat results", true);
	private final CheckboxSetting debug = new CheckboxSetting("Debug", false);
	private final CheckboxSetting namesOnly = new CheckboxSetting("Names only",
		"Show locator names without locking targets or triangulating.", false);
	private UUID target;
	private UUID hovered;
	private int hoveredColor = 0xFFFFFFFF;
	private String dimension;
	private State state = State.SEARCHING;
	private UUID candidate;
	private long candidateSince;
	private long lostSince;
	private double targetYaw;
	private double baselineYaw;
	private double startX, startZ;
	private double lastSampleX, lastSampleZ, lastSampleYaw;
	private long lastSampleAt;
	private long resultSince;
	private double requestedBaseline;
	private LocatorTriangulator.Result result;
	private String message = "LOCATOR | POINT AT THE LOCATOR DOT";
	
	public LocatorTriangulationHack()
	{
		super("Triangulator",
			"Uses remote locator-bar bearings and player movement to estimate the coordinates of distant players.",
			false);
		setCategory(Category.INTEL);
		baselineSide.setOptions(List.of("AUTO", "RIGHT", "LEFT"));
		baselineSide.setSelected("RIGHT");
		addSetting(acquisitionAngle);
		addSetting(holdTime);
		addSetting(baselineSide);
		addSetting(initialBaseline);
		addSetting(maximumBaseline);
		addSetting(autoExtend);
		addSetting(hud);
		addSetting(chatResults);
		addSetting(debug);
		addSetting(namesOnly);
	}
	
	@Override
	protected void onEnable()
	{
		waypoints.clear();
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		waypoints.clear();
		target = candidate = null;
		lostSince = 0;
		resultSince = 0;
		state = State.SEARCHING;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		if(!(packet instanceof ClientboundTrackedWaypointPacket p))
			return;
		TrackedWaypoint w = p.waypoint();
		UUID id = w.id().left().orElse(null);
		if(id == null)
			return;
		if(!(w instanceof TrackedWaypointAzimuthAccessor azimuth))
		{
			waypoints.remove(id);
			if(id.equals(target))
			{
				markTargetLost();
			}
			return;
		}
		waypoints.put(id, new Waypoint(id, azimuth.wurst$getAngle(),
			waypointColor(w, id), System.currentTimeMillis()));
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.getConnection() == null)
			return;
		refreshVanillaWaypoints();
		String currentDimension = MC.level.dimension().identifier().toString();
		if(dimension != null && !dimension.equals(currentDimension)
			&& target != null)
		{
			state = State.SEARCHING;
			target = null;
			result = null;
			ChatUtils
				.message("[Locator] TARGET SESSION RESET - DIMENSION CHANGED");
		}
		dimension = currentDimension;
		if(state == State.RESULT && result != null
			&& System.currentTimeMillis() - resultSince >= 5000)
		{
			target = candidate = null;
			result = null;
			resultSince = 0;
			state = State.SEARCHING;
			message = "LOCATOR | POINT AT THE LOCATOR DOT";
		}
		if(namesOnly.isChecked())
		{
			target = candidate = null;
			result = null;
			state = State.SEARCHING;
			hovered = findBestWaypoint();
			message = null;
			return;
		}
		acquire();
		if(target == null)
			return;
		Waypoint wp = waypoints.get(target);
		if(wp == null || System.currentTimeMillis() - wp.updatedAt() > 10000)
		{
			if(state != State.LOST)
			{
				lostSince = System.currentTimeMillis();
				state = State.LOST;
				message = name(target) + " | TARGET LOST";
			}else if(System.currentTimeMillis() - lostSince >= 3000)
			{
				target = candidate = null;
				result = null;
				lostSince = 0;
				state = State.SEARCHING;
				message = "LOCATOR | POINT AT THE LOCATOR DOT";
			}
			return;
		}
		lostSince = 0;
		targetYaw = wp.yawRadians();
		if(state == State.LOCKED || state == State.FACE_TARGET)
		{
			state = State.FACE_TARGET;
			double currentYaw = MC.player.getYRot() * Math.PI / 180;
			if(angleDegrees(currentYaw, targetYaw) < 8)
			{
				baselineYaw =
					targetYaw + (baselineSide.getSelected().equals("LEFT")
						? -Math.PI / 2 : Math.PI / 2);
				state = State.TURN_PERPENDICULAR;
				message = name(target) + " | "
					+ directionArrow(currentYaw, baselineYaw);
				return;
			}
			message = name(target) + " | "
				+ directionArrow(currentYaw, targetYaw) + " FACE TARGET";
		}
		if(state == State.TURN_PERPENDICULAR
			&& angleDegrees(MC.player.getYRot() * Math.PI / 180,
				baselineYaw) < 15)
			beginBaseline();
		else if(state == State.TURN_PERPENDICULAR)
			message = name(target) + " | " + directionArrow(
				MC.player.getYRot() * Math.PI / 180, baselineYaw);
		if(state == State.MOVING)
			collect();
	}
	
	private void markTargetLost()
	{
		if(state != State.LOST)
			lostSince = System.currentTimeMillis();
		state = State.LOST;
		message = name(target) + " | TARGET LOST";
	}
	
	private void refreshVanillaWaypoints()
	{
		waypoints.clear();
		MC.getConnection().getWaypointManager().forEachWaypoint(MC.player,
			waypoint -> {
				UUID id = waypoint.id().left().orElse(null);
				if(id == null)
					return;
				if(waypoint instanceof TrackedWaypointAzimuthAccessor azimuth)
					waypoints.put(id,
						new Waypoint(id, azimuth.wurst$getAngle(),
							waypointColor(waypoint, id),
							System.currentTimeMillis()));
			});
	}
	
	private void acquire()
	{
		UUID best = findBestWaypoint();
		hovered = best;
		if(best != null)
			hoveredColor = waypoints.get(best).color();
		if(best == null)
		{
			candidate = null;
			if(target == null)
				message = "LOCATOR | POINT AT THE LOCATOR DOT";
			return;
		}
		if(best.equals(target))
		{
			candidate = null;
			return;
		}
		if(!best.equals(candidate))
		{
			candidate = best;
			candidateSince = System.currentTimeMillis();
			if(target == null)
			{
				state = State.CANDIDATE;
				message = name(best) + " | HOLD 3.0s TO CONFIRM";
			}else
				message = name(best) + " | HOLD 3.0s TO SWITCH";
			return;
		}
		updateCandidateMessage();
		if(System.currentTimeMillis() - candidateSince < holdTimeMillis())
			return;
		if(!best.equals(target))
		{
			target = best;
			candidate = null;
			result = null;
			resultSince = 0;
			requestedBaseline = initialBaseline.getValue();
			state = State.LOCKED;
			message = name(target) + " | ACQUIRED";
		}
	}
	
	private UUID findBestWaypoint()
	{
		double camera =
			MC.player == null ? 0 : MC.player.getYRot() * Math.PI / 180;
		UUID best = null;
		double bestAngle = acquisitionAngle.getValue();
		for(Waypoint w : waypoints.values())
		{
			if(MC.getConnection().getPlayerInfo(w.uuid()) == null)
				continue;
			double a = angleDegrees(camera, w.yawRadians());
			if(a < bestAngle)
			{
				bestAngle = a;
				best = w.uuid();
			}
		}
		hovered = best;
		if(best != null)
			hoveredColor = waypoints.get(best).color();
		return best;
	}
	
	private void updateCandidateMessage()
	{
		if(candidate == null)
			return;
		long delay = holdTimeMillis();
		double remaining = Math.max(0,
			(delay - (System.currentTimeMillis() - candidateSince)) / 1000.0);
		message =
			name(candidate) + " | HOLD " + String.format("%.1fs", remaining)
				+ (target == null ? " TO CONFIRM" : " TO SWITCH");
	}
	
	private void beginBaseline()
	{
		startX = MC.player.getX();
		startZ = MC.player.getZ();
		lastSampleX = startX;
		lastSampleZ = startZ;
		lastSampleAt = 0;
		Waypoint wp = waypoints.get(target);
		if(wp != null)
			history.computeIfAbsent(target, k -> new ArrayList<>())
				.add(new LocatorObservation(target, startX, startZ,
					System.currentTimeMillis(), wp.yawRadians(), dimension));
		state = State.MOVING;
		message = name(target) + " | MOVE FORWARD | " + (int)requestedBaseline
			+ " BLOCKS";
	}
	
	private void collect()
	{
		Waypoint wp = waypoints.get(target);
		if(wp == null)
			return;
		double x = MC.player.getX(), z = MC.player.getZ();
		double currentYaw = MC.player.getYRot() * Math.PI / 180;
		if(angleDegrees(currentYaw, baselineYaw) > 45)
		{
			message =
				name(target) + " | " + directionArrow(currentYaw, baselineYaw);
			return;
		}
		if((Math.hypot(x - lastSampleX, z - lastSampleZ) >= 8
			|| angleDegrees(lastSampleYaw, wp.yawRadians()) >= 0.15)
			&& System.currentTimeMillis() - lastSampleAt >= 250)
		{
			history.computeIfAbsent(target, k -> new ArrayList<>())
				.add(new LocatorObservation(target, x, z,
					System.currentTimeMillis(), wp.yawRadians(), dimension));
			lastSampleX = x;
			lastSampleZ = z;
			lastSampleYaw = wp.yawRadians();
			lastSampleAt = System.currentTimeMillis();
		}
		result =
			LocatorTriangulator.fit(history.getOrDefault(target, List.of()));
		double directionX = -Math.sin(baselineYaw);
		double directionZ = Math.cos(baselineYaw);
		double deltaX = x - startX;
		double deltaZ = z - startZ;
		double along = deltaX * directionX + deltaZ * directionZ;
		double crossTrack = deltaX * directionZ - deltaZ * directionX;
		boolean drifting =
			Math.abs(crossTrack) > Math.max(8, Math.abs(along) * .35);
		if(result != null && along >= initialBaseline.getValue() * .8)
			finish();
		else if(along >= requestedBaseline && (!autoExtend.isChecked()
			|| requestedBaseline >= maximumBaseline.getValue()))
			finish();
		else if(along >= requestedBaseline)
		{
			requestedBaseline =
				Math.min(maximumBaseline.getValue(), requestedBaseline * 2);
			if(chatResults.isChecked())
				ChatUtils.component(Component.literal("[Locator] "
					+ name(target)
					+ ": not enough distance/parallax yet; extending the baseline to "
					+ compact(requestedBaseline) + " blocks."));
			message = name(target) + " | PARALLAX TOO SMALL | KEEP GOING | "
				+ Math.max(0, (int)(requestedBaseline - along))
				+ " MORE BLOCKS";
		}else if(drifting)
			message = name(target) + " | " + (crossTrack > 0 ? "<---" : "--->")
				+ " | "
				+ Math.max(0, (int)(requestedBaseline - Math.max(0, along)))
				+ " BLOCKS REMAINING";
		else
			message = name(target) + " | MOVE FORWARD | "
				+ Math.max(0, (int)(requestedBaseline - along)) + " BLOCKS";
	}
	
	private void finish()
	{
		state = State.CALCULATING;
		if(result == null)
		{
			message = name(target) + " | NOT ENOUGH PARALLAX";
			if(chatResults.isChecked())
			{
				ChatUtils
					.component(Component.literal("[Locator] Could not estimate "
						+ name(target) + " reliably."));
				ChatUtils.component(
					Component.literal("[Locator] Not enough parallax "
						+ "after " + compact(maximumBaseline.getValue())
						+ " blocks of baseline movement."));
				ChatUtils
					.component(Component.literal("[Locator] Keep the target "
						+ "moving or run another refinement pass."));
			}
			state = State.RESULT;
			return;
		}
		state = State.RESULT;
		resultSince = System.currentTimeMillis();
		message = name(target) + " | ~X " + compact(result.x()) + " Z "
			+ compact(result.z()) + " | ±" + compact(result.uncertainty());
		if(chatResults.isChecked())
		{
			String refined = history.getOrDefault(target, List.of())
				.size() > result.sampleCount() ? "Refined " : "";
			ChatUtils
				.message(refined + name(target) + " approximate position:");
			ChatUtils.message(
				"X " + compact(result.x()) + "  Z " + compact(result.z()));
			ChatUtils.message(
				"Uncertainty: ±" + compact(result.uncertainty()) + " blocks");
			ChatUtils.message("Samples: " + result.sampleCount()
				+ " | Baseline: " + compact(result.baseline())
				+ " | Confidence: " + result.confidence());
		}
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!hud.isChecked() || message == null && hovered == null)
			return;
		Font font = MC.font;
		String[] lines =
			message == null ? new String[0] : message.split(" \\| ");
		int y = context.guiHeight() / 2 + 12;
		UUID selectedId = target != null ? target : hovered;
		if(selectedId != null)
		{
			String selected = "[ " + name(selectedId) + " ]";
			drawHudLine(context, font, selected, y, colorOf(selectedId));
			y += 10;
		}
		if(candidate != null && !candidate.equals(target))
		{
			String changing =
				(target == null ? "Selecting Player: " : "Changing Player: ")
					+ name(candidate) + " - " + holdRemaining();
			drawHudLine(context, font, changing, y, colorOf(candidate));
			y += 10;
		}
		for(String line : lines)
		{
			if((target != null && line.equals(name(target))
				|| candidate != null && line.equals(name(candidate)))
				|| line.startsWith("HOLD "))
				continue;
			y += 10;
			drawHudLine(context, font, line, y, 0xFFFFFFFF);
		}
		if(debug.isChecked())
		{
			List<LocatorObservation> samples = target == null ? List.of()
				: history.getOrDefault(target, List.of());
			String fit = result == null ? "none" : "±"
				+ compact(result.uncertainty()) + " " + result.confidence();
			String debugText =
				"DEBUG " + state + " | samples=" + samples.size() + " | target="
					+ (target == null ? "none" : "locked") + " | fit=" + fit;
			drawHudLine(context, font, debugText, y + 10, 0xFFFFAA55);
		}
	}
	
	private static void drawHudLine(GuiGraphicsExtractor context, Font font,
		String text, int y, int color)
	{
		int x = context.guiWidth() / 2 - font.width(text) / 2;
		context.fill(x - 3, y - 2, x + font.width(text) + 3,
			y + font.lineHeight, 0x90000000);
		context.text(font, text, x, y, color, true);
	}
	
	private String name(UUID id)
	{
		var info = MC.getConnection() == null ? null
			: MC.getConnection().getPlayerInfo(id);
		return info == null || info.getProfile() == null
			? id.toString().substring(0, 8) : info.getProfile().name();
	}
	
	private int waypointColor(TrackedWaypoint waypoint, UUID id)
	{
		if(!(waypoint instanceof TrackedWaypointIconAccessor iconAccessor))
			return generatedColor(id);
		if(iconAccessor
			.wurst$getIcon() instanceof WaypointIconColorAccessor colorAccessor)
		{
			var color = colorAccessor.wurst$getColor();
			if(color.isPresent())
				return color.get();
		}
		return generatedColor(id);
	}
	
	private int colorOf(UUID id)
	{
		Waypoint waypoint = waypoints.get(id);
		if(waypoint != null)
			return waypoint.color();
		return 0xFFFFFFFF;
	}
	
	private static int generatedColor(UUID id)
	{
		return ARGB.setBrightness(ARGB.color(255, id.hashCode()), .9F);
	}
	
	private String holdRemaining()
	{
		long delay = holdTimeMillis();
		double remaining = Math.max(0,
			(delay - (System.currentTimeMillis() - candidateSince)) / 1000.0);
		return String.format("%.1fs", remaining);
	}
	
	private long holdTimeMillis()
	{
		return holdTime.getValueI() * 1000L;
	}
	
	private static double angleDegrees(double a, double b)
	{
		return Math
			.toDegrees(Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))));
	}
	
	private static String directionArrow(double current, double desired)
	{
		double delta = Math.sin(desired - current);
		if(Math.abs(delta) < Math.sin(Math.toRadians(15)))
			return "| CENTERED |";
		return delta >= 0 ? "--->" : "<---";
	}
	
	private static String compact(double n)
	{
		double step = Math.abs(n) >= 1000 ? 100 : Math.abs(n) >= 100 ? 10 : 1;
		return String.format("%,.0f", Math.round(n / step) * step);
	}
}
