/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;

public final class CheatDetectorHack extends Hack implements UpdateListener
{
	private static final double TELEPORT_DISTANCE_THRESHOLD = 6.0;
	private static final double FLIGHT_VERTICAL_TOLERANCE = 0.08;
	private static final double BOAT_VERTICAL_TOLERANCE = 0.05;
	private static final int ALERT_COOLDOWN_TICKS = 200;
	private static final int AURA_WINDOW_TICKS = 40;
	private static final int FLIGHT_GROUND_GRACE_TICKS = 8;
	
	private final CheckboxSetting detectSpeed =
		new CheckboxSetting("Detect speed", true);
	// New: use more fine-grained speed configuration inspired by VelocityGuard
	private final SliderSetting speedThreshold =
		new SliderSetting("Speed threshold (blocks/s)", 10.0, 5.0, 80.0, 0.1,
			ValueDisplay.DECIMAL);
	private final SliderSetting cancelDuration = new SliderSetting(
		"Cancel movement duration (s)", 1, 0, 10, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting latencyCompensationEnabled =
		new CheckboxSetting("Latency compensation", true);
	// latency compensation factors (multipliers for allowed speed)
	private final SliderSetting latencyVeryLowFactor = new SliderSetting(
		"very-low-ping factor", 2.9, 1.0, 10.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting latencyLowFactor = new SliderSetting(
		"low-ping factor", 2.9, 1.0, 10.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting latencyMediumFactor = new SliderSetting(
		"medium-ping factor", 3.3, 1.0, 12.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting latencyHighFactor = new SliderSetting(
		"high-ping factor", 3.6, 1.0, 15.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting latencyVeryHighFactor = new SliderSetting(
		"very-high-ping factor", 4.6, 1.0, 20.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting latencyExtremeFactor = new SliderSetting(
		"extreme-ping factor", 5.7, 1.0, 25.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting latencyUltraFactor = new SliderSetting(
		"ultra-ping factor", 6.6, 1.0, 30.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting latencyInsaneFactor = new SliderSetting(
		"insane-ping factor", 7.5, 1.0, 40.0, 0.1, ValueDisplay.DECIMAL);
	
	// Burst tolerance - consecutive violations allowed before alert
	private final SliderSetting burstDefault = new SliderSetting(
		"burst-tolerance default", 19, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstVeryLow = new SliderSetting(
		"burst-tolerance very-low-ping", 20, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstLow = new SliderSetting(
		"burst-tolerance low-ping", 21, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstMedium = new SliderSetting(
		"burst-tolerance medium-ping", 22, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstHigh = new SliderSetting(
		"burst-tolerance high-ping", 24, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstVeryHigh = new SliderSetting(
		"burst-tolerance very-high-ping", 27, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstExtreme = new SliderSetting(
		"burst-tolerance extreme-ping", 30, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstUltra = new SliderSetting(
		"burst-tolerance ultra-ping", 33, 1, 200, 1, ValueDisplay.INTEGER);
	private final SliderSetting burstInsane = new SliderSetting(
		"burst-tolerance insane-ping", 35, 1, 200, 1, ValueDisplay.INTEGER);
	
	// Knockback and special movement multipliers
	private final SliderSetting knockbackMultiplier = new SliderSetting(
		"knockback multiplier", 6.0, 1.0, 20.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting knockbackDuration = new SliderSetting(
		"knockback duration (ms)", 1000, 0, 10000, 100, ValueDisplay.INTEGER);
	
	private final SliderSetting riptideMultiplier = new SliderSetting(
		"riptide multiplier", 1.5, 1.0, 10.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting riptideDuration = new SliderSetting(
		"riptide duration (ms)", 3000, 0, 10000, 100, ValueDisplay.INTEGER);
	
	private final SliderSetting elytraGlidingMultiplier = new SliderSetting(
		"elytra gliding multiplier", 1.5, 1.0, 10.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting elytraLandingDuration =
		new SliderSetting("elytra landing duration (ms)", 1500, 0, 10000, 100,
			ValueDisplay.INTEGER);
	
	private final SliderSetting vehicleSpeedMultiplier = new SliderSetting(
		"vehicle speed multiplier", 1.9, 1.0, 10.0, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting vehicleIceSpeedMultiplier =
		new SliderSetting("vehicle ice speed multiplier", 4.3, 1.0, 20.0, 0.1,
			ValueDisplay.DECIMAL);
	
	// Extra buffer applied to all speed checks
	private final SliderSetting bufferMultiplier = new SliderSetting(
		"buffer multiplier", 1.2, 1.0, 3.0, 0.01, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting detectFlight =
		new CheckboxSetting("Detect flight", true);
	private final SliderSetting flightAirTicks = new SliderSetting(
		"Flight tick threshold", 40, 10, 200, 5, ValueDisplay.INTEGER);
	private final SliderSetting flightClearanceThreshold = new SliderSetting(
		"Flight clearance (blocks)", 6.0, 3.0, 32.0, 0.5, ValueDisplay.DECIMAL);
	private final SliderSetting flightPatternTicks = new SliderSetting(
		"Flight sustain ticks", 16, 4, 80, 1, ValueDisplay.INTEGER);
	private final SliderSetting flightAscendDistance =
		new SliderSetting("Flight ascend distance (blocks)", 5.0, 2.0, 40.0,
			0.5, ValueDisplay.DECIMAL);
	private final SliderSetting flightAscendRate =
		new SliderSetting("Flight ascend rate (blocks/tick)", 0.25, 0.05, 1.0,
			0.05, ValueDisplay.DECIMAL);
	private final SliderSetting flightHorizontalSpeed =
		new SliderSetting("Flight horizontal speed (blocks/s)", 1.0, 0.2, 5.0,
			0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting detectBoatFly =
		new CheckboxSetting("Detect boat fly", true);
	private final SliderSetting boatAirTicks = new SliderSetting(
		"Boat-air tick threshold", 25, 5, 200, 5, ValueDisplay.INTEGER);
	private final SliderSetting boatClearanceThreshold = new SliderSetting(
		"Boat clearance (blocks)", 4.0, 2.0, 24.0, 0.5, ValueDisplay.DECIMAL);
	private final SliderSetting boatPatternTicks = new SliderSetting(
		"Boat sustain ticks", 10, 3, 60, 1, ValueDisplay.INTEGER);
	private final SliderSetting boatHorizontalSpeed =
		new SliderSetting("Boat horizontal speed (blocks/s)", 0.5, 0.1, 4.0,
			0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting detectAura =
		new CheckboxSetting("Detect killaura", true);
	private final SliderSetting auraThreshold =
		new SliderSetting("Swing threshold (per second)", 12.0, 6.0, 30.0, 0.5,
			ValueDisplay.DECIMAL);
	
	private final Map<UUID, PlayerStats> playerStats = new HashMap<>();
	private long tickCounter;
	private ClientLevel lastWorld;
	
	public CheatDetectorHack()
	{
		super("CheatDetector");
		setCategory(Category.OTHER);
		addSetting(detectSpeed);
		addSetting(speedThreshold);
		addSetting(cancelDuration);
		addSetting(latencyCompensationEnabled);
		addSetting(latencyVeryLowFactor);
		addSetting(latencyLowFactor);
		addSetting(latencyMediumFactor);
		addSetting(latencyHighFactor);
		addSetting(latencyVeryHighFactor);
		addSetting(latencyExtremeFactor);
		addSetting(latencyUltraFactor);
		addSetting(latencyInsaneFactor);
		addSetting(burstDefault);
		addSetting(burstVeryLow);
		addSetting(burstLow);
		addSetting(burstMedium);
		addSetting(burstHigh);
		addSetting(burstVeryHigh);
		addSetting(burstExtreme);
		addSetting(burstUltra);
		addSetting(burstInsane);
		addSetting(knockbackMultiplier);
		addSetting(knockbackDuration);
		addSetting(riptideMultiplier);
		addSetting(riptideDuration);
		addSetting(elytraGlidingMultiplier);
		addSetting(elytraLandingDuration);
		addSetting(vehicleSpeedMultiplier);
		addSetting(vehicleIceSpeedMultiplier);
		addSetting(bufferMultiplier);
		addSetting(detectFlight);
		addSetting(flightAirTicks);
		addSetting(flightClearanceThreshold);
		addSetting(flightPatternTicks);
		addSetting(flightAscendDistance);
		addSetting(flightAscendRate);
		addSetting(flightHorizontalSpeed);
		addSetting(detectBoatFly);
		addSetting(boatAirTicks);
		addSetting(boatClearanceThreshold);
		addSetting(boatPatternTicks);
		addSetting(boatHorizontalSpeed);
		addSetting(detectAura);
		addSetting(auraThreshold);
	}
	
	@Override
	protected void onEnable()
	{
		tickCounter = 0L;
		lastWorld = MC.level;
		playerStats.clear();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		playerStats.clear();
		lastWorld = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null)
		{
			playerStats.clear();
			lastWorld = null;
			return;
		}
		
		if(MC.level != lastWorld)
		{
			playerStats.clear();
			lastWorld = MC.level;
		}
		
		tickCounter++;
		Set<UUID> seen = new HashSet<>();
		
		for(Player other : MC.level.players())
		{
			if(other == MC.player || other.isRemoved())
				continue;
			
			UUID id = other.getUUID();
			seen.add(id);
			
			PlayerStats stats =
				playerStats.computeIfAbsent(id, uuid -> new PlayerStats());
			processPlayer(other, stats);
		}
		
		playerStats.keySet().removeIf(id -> !seen.contains(id));
	}
	
	private void processPlayer(Player player, PlayerStats stats)
	{
		if(player.isSpectator() || player.isCreative())
		{
			stats.resetPosition(player);
			return;
		}
		
		if(!stats.hasLastPosition)
		{
			stats.resetPosition(player);
			return;
		}
		
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		
		double dx = x - stats.lastX;
		double dy = y - stats.lastY;
		double dz = z - stats.lastZ;
		double horizontalPerTick = Math.sqrt(dx * dx + dz * dz);
		double horizontalPerSecond = horizontalPerTick * 20.0;
		
		if(horizontalPerTick > TELEPORT_DISTANCE_THRESHOLD
			|| Math.abs(dy) > TELEPORT_DISTANCE_THRESHOLD)
		{
			stats.ignoreMovementTicks = 5;
		}else if(stats.ignoreMovementTicks > 0)
			stats.ignoreMovementTicks--;
		
		if(stats.ignoreMovementTicks == 0)
			checkSpeed(player, stats, horizontalPerSecond);
		
		updateFlightPattern(player, stats, dy);
		checkFlight(player, stats, dy, horizontalPerSecond);
		checkBoatFly(player, stats);
		checkAura(player, stats);
		
		stats.lastX = x;
		stats.lastY = y;
		stats.lastZ = z;
	}
	
	private void checkSpeed(Player player, PlayerStats stats,
		double horizontalPerSecond)
	{
		if(!detectSpeed.isChecked())
			return;
		
		// ignore if elytra or in vehicle or swimming - handle separately
		if(player.isInWater() || player.isSwimming())
			return;
		
		// compute base allowed speed with buffer
		double allowed =
			speedThreshold.getValue() * bufferMultiplier.getValue();
		
		// adjust for elytra
		if(isUsingElytra(player))
			allowed *= elytraGlidingMultiplier.getValue();
		
		// adjust for vehicles
		Entity vehicle = player.getVehicle();
		if(vehicle != null)
		{
			allowed *= vehicleSpeedMultiplier.getValue();
			// boats on ice can be much faster - rudimentary check: block under
			// vehicle
			try
			{
				int bx = Mth.floor(vehicle.getX());
				int bz = Mth.floor(vehicle.getZ());
				int by = Mth.floor(vehicle.getY()) - 1;
				BlockState under = ((ClientLevel)vehicle.level())
					.getBlockState(new BlockPos(bx, by, bz));
				String id =
					under.getBlock().toString().toLowerCase(Locale.ROOT);
				if(id.contains("ice"))
					allowed *= vehicleIceSpeedMultiplier.getValue();
			}catch(Exception ignore)
			{}
		}
		
		// status effect speed gives small allowance
		if(player.hasEffect(MobEffects.SPEED))
			allowed *= 1.2;
		
		// latency compensation
		int ping = getPlayerPing(player);
		if(latencyCompensationEnabled.isChecked() && ping >= 0)
			allowed *= getLatencyFactorForPing(ping);
		
		// final check
		if(horizontalPerSecond <= allowed)
		{
			// reset violation counter
			stats.speedViolationCount = 0;
			return;
		}
		
		// increase violation counter
		stats.speedViolationCount++;
		
		int allowedBurst = getBurstToleranceForPing(ping);
		if(stats.speedViolationCount < allowedBurst)
			return; // within burst tolerance
			
		if(tickCounter - stats.lastSpeedAlertTick < ALERT_COOLDOWN_TICKS)
			return;
		
		stats.lastSpeedAlertTick = tickCounter;
		sendAlert(player,
			String.format(Locale.ROOT,
				"suspected of speed (%.1f blocks/s) [allowed %.1f]",
				horizontalPerSecond, allowed));
		
		// reset after alert
		stats.speedViolationCount = 0;
	}
	
	private void updateFlightPattern(Player player, PlayerStats stats,
		double dy)
	{
		double clearance = getClearanceAboveGround(player, 16);
		
		if(!player.onGround() && !player.isInWater()
			&& player.getVehicle() == null && !player.onClimbable())
		{
			if(clearance >= flightClearanceThreshold.getValue())
			{
				stats.sustainedAltitudeTicks =
					Math.min(stats.sustainedAltitudeTicks + 1, 1000);
			}else if(stats.sustainedAltitudeTicks > 0)
				stats.sustainedAltitudeTicks--;
			
			if(dy > flightAscendRate.getValue())
				stats.ascendedDistance += dy;
			else if(dy < -0.05 && stats.ascendedDistance > 0.0)
				stats.ascendedDistance =
					Math.max(0.0, stats.ascendedDistance + dy);
			
		}else
		{
			stats.sustainedAltitudeTicks = 0;
			stats.ascendedDistance = 0.0;
		}
		
		stats.lastClearance = clearance;
	}
	
	private void checkFlight(Player player, PlayerStats stats, double dy,
		double horizontalPerSecond)
	{
		if(!detectFlight.isChecked())
		{
			stats.airborneTicks = 0;
			return;
		}
		
		if(isUsingElytra(player))
		{
			stats.airborneTicks = 0;
			return;
		}
		
		boolean airborne = !player.onGround() && !player.isInWater()
			&& player.getVehicle() == null && !player.onClimbable()
			&& !player.hasEffect(MobEffects.SLOW_FALLING)
			&& !player.hasEffect(MobEffects.LEVITATION);
		
		if(airborne)
		{
			stats.airborneTicks++;
			stats.groundBufferTicks = 0;
		}else if(stats.airborneTicks > 0)
		{
			stats.groundBufferTicks++;
			if(stats.groundBufferTicks <= FLIGHT_GROUND_GRACE_TICKS)
			{
				// allow brief ground touches without fully resetting
				stats.airborneTicks = Math.max(0, stats.airborneTicks - 1);
			}else
			{
				stats.airborneTicks = 0;
			}
		}else
		{
			stats.airborneTicks = 0;
			stats.groundBufferTicks = 0;
		}
		
		if(stats.airborneTicks < flightAirTicks.getValue())
			return;
		
		if(Math.abs(dy) > FLIGHT_VERTICAL_TOLERANCE
			&& stats.sustainedAltitudeTicks < flightPatternTicks.getValueI())
			return;
		
		boolean meetsHorizontal =
			horizontalPerSecond >= flightHorizontalSpeed.getValue();
		boolean meetsPattern =
			stats.sustainedAltitudeTicks >= flightPatternTicks.getValueI()
				|| stats.ascendedDistance >= flightAscendDistance.getValue();
		
		if(!meetsHorizontal && !meetsPattern)
			return;
		
		if(tickCounter - stats.lastFlightAlertTick < ALERT_COOLDOWN_TICKS)
			return;
		
		stats.lastFlightAlertTick = tickCounter;
		sendAlert(player, "suspected of flying");
	}
	
	private void checkBoatFly(Player player, PlayerStats stats)
	{
		if(!detectBoatFly.isChecked())
		{
			stats.boatAirTicks = 0;
			return;
		}
		
		Entity vehicle = player.getVehicle();
		if(!(vehicle instanceof Boat boat))
		{
			stats.boatAirTicks = 0;
			return;
		}
		
		boolean supported = isBoatSupported(boat);
		
		if(!supported)
			stats.boatAirTicks++;
		else
		{
			stats.boatAirTicks = 0;
			stats.boatAltitudeTicks = 0;
		}
		
		double clearance = getClearanceAboveGround(boat, 16);
		if(!supported && clearance >= boatClearanceThreshold.getValue())
			stats.boatAltitudeTicks =
				Math.min(stats.boatAltitudeTicks + 1, 1000);
		else if(stats.boatAltitudeTicks > 0)
			stats.boatAltitudeTicks--;
		
		if(stats.boatAirTicks < boatAirTicks.getValue())
			return;
		
		Vec3 vel = boat.getDeltaMovement();
		double horizontalSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
		boolean meetsHorizontal =
			horizontalSpeed >= boatHorizontalSpeed.getValue();
		boolean meetsAltitude =
			stats.boatAltitudeTicks >= boatPatternTicks.getValueI()
				|| clearance >= boatClearanceThreshold.getValue();
		
		if(!meetsHorizontal && !meetsAltitude
			&& Math.abs(vel.y) < BOAT_VERTICAL_TOLERANCE)
			return;
		
		if(tickCounter - stats.lastBoatAlertTick < ALERT_COOLDOWN_TICKS)
			return;
		
		stats.lastBoatAlertTick = tickCounter;
		sendAlert(player, "suspected of boat-flying");
	}
	
	private void checkAura(Player player, PlayerStats stats)
	{
		if(!detectAura.isChecked())
		{
			stats.swingTicks.clear();
			stats.swingIntervals.clear();
			stats.lastSwingTick = 0L;
			stats.lastSwingProgress = player.getAttackAnim(1.0F);
			return;
		}
		
		float swingProgress = player.getAttackAnim(1.0F);
		
		if(stats.lastSwingProgress > 0.6F && swingProgress < 0.2F)
		{
			stats.swingTicks.addLast(tickCounter);
			
			if(stats.lastSwingTick != 0L)
			{
				long interval = tickCounter - stats.lastSwingTick;
				stats.swingIntervals.addLast(interval);
				while(stats.swingIntervals.size() > 12)
					stats.swingIntervals.removeFirst();
			}
			
			stats.lastSwingTick = tickCounter;
		}
		
		stats.lastSwingProgress = swingProgress;
		
		while(!stats.swingTicks.isEmpty()
			&& tickCounter - stats.swingTicks.peekFirst() > AURA_WINDOW_TICKS)
			stats.swingTicks.removeFirst();
		
		if(isUsingElytra(player))
		{
			stats.swingTicks.clear();
			stats.swingIntervals.clear();
			stats.lastSwingTick = 0L;
			return;
		}
		
		if(stats.swingTicks.size() < 2)
			return;
		
		long first = stats.swingTicks.peekFirst();
		long last = stats.swingTicks.peekLast();
		long span = Math.max(1L, last - first);
		double swingsPerSecond =
			(stats.swingTicks.size() - 1) * 20.0 / (double)span;
		
		if(stats.swingIntervals.size() >= 3)
		{
			double sum = 0.0;
			for(long interval : stats.swingIntervals)
				sum += interval;
			
			if(sum > 0)
			{
				double average = sum / stats.swingIntervals.size();
				double cpsFromIntervals = 20.0 / average;
				swingsPerSecond = Math.max(swingsPerSecond, cpsFromIntervals);
			}
		}
		
		if(swingsPerSecond <= auraThreshold.getValue())
			return;
		
		if(tickCounter - stats.lastAuraAlertTick < ALERT_COOLDOWN_TICKS)
			return;
		
		stats.lastAuraAlertTick = tickCounter;
		sendAlert(player, String.format(Locale.ROOT,
			"suspected of killaura (%.1f swings/s)", swingsPerSecond));
	}
	
	private void sendAlert(Player player, String reason)
	{
		String name = player.getName().getString();
		ChatUtils.message("CheatDetector: " + name + " " + reason);
	}
	
	private String formatDouble(double value)
	{
		return String.format(Locale.ROOT, "%.1f", value);
	}
	
	/**
	 * Try to obtain the player's ping/latency in milliseconds. Returns -1 if
	 * unavailable.
	 */
	private int getPlayerPing(Player player)
	{
		try
		{
			var handler = MC.getConnection();
			if(handler == null)
				return -1;
			var entry = handler.getPlayerInfo(player.getUUID());
			if(entry == null)
				return -1;
				
			// try common method names via reflection to be resilient across
			// mappings
			try
			{
				java.lang.reflect.Method m =
					entry.getClass().getMethod("getLatency");
				Object o = m.invoke(entry);
				if(o instanceof Integer)
					return (Integer)o;
				if(o instanceof Long)
					return ((Long)o).intValue();
			}catch(NoSuchMethodException ignored)
			{}
			
			try
			{
				java.lang.reflect.Method m =
					entry.getClass().getMethod("getLatencyMs");
				Object o = m.invoke(entry);
				if(o instanceof Integer)
					return (Integer)o;
				if(o instanceof Long)
					return ((Long)o).intValue();
			}catch(NoSuchMethodException ignored)
			{}
			
			// fallback: try field "latency" if present
			try
			{
				java.lang.reflect.Field f =
					entry.getClass().getDeclaredField("latency");
				f.setAccessible(true);
				Object o = f.get(entry);
				if(o instanceof Integer)
					return (Integer)o;
				if(o instanceof Long)
					return ((Long)o).intValue();
			}catch(NoSuchFieldException ignored)
			{}
		}catch(Throwable t)
		{ /* ignore */ }
		return -1;
	}
	
	private double getLatencyFactorForPing(int pingMs)
	{
		if(pingMs < 0)
			return 1.0;
		if(pingMs <= 50)
			return latencyVeryLowFactor.getValue();
		if(pingMs <= 100)
			return latencyLowFactor.getValue();
		if(pingMs <= 200)
			return latencyMediumFactor.getValue();
		if(pingMs <= 300)
			return latencyHighFactor.getValue();
		if(pingMs <= 500)
			return latencyVeryHighFactor.getValue();
		if(pingMs <= 750)
			return latencyExtremeFactor.getValue();
		if(pingMs <= 1000)
			return latencyUltraFactor.getValue();
		return latencyInsaneFactor.getValue();
	}
	
	private int getBurstToleranceForPing(int pingMs)
	{
		if(pingMs < 0)
			return (int)burstDefault.getValueI();
		if(pingMs <= 50)
			return (int)burstVeryLow.getValueI();
		if(pingMs <= 100)
			return (int)burstLow.getValueI();
		if(pingMs <= 200)
			return (int)burstMedium.getValueI();
		if(pingMs <= 300)
			return (int)burstHigh.getValueI();
		if(pingMs <= 500)
			return (int)burstVeryHigh.getValueI();
		if(pingMs <= 750)
			return (int)burstExtreme.getValueI();
		if(pingMs <= 1000)
			return (int)burstUltra.getValueI();
		return (int)burstInsane.getValueI();
	}
	
	private double getClearanceAboveGround(Entity entity, int maxDepth)
	{
		ClientLevel world = (ClientLevel)entity.level();
		AABB box = entity.getBoundingBox();
		double entityBottom = box.minY;
		
		int minX = Mth.floor(box.minX);
		int maxX = Mth.ceil(box.maxX);
		int minZ = Mth.floor(box.minZ);
		int maxZ = Mth.ceil(box.maxZ);
		
		int maxY = Mth.floor(entityBottom);
		int minY =
			Math.max(world.getMinY(), Mth.floor(entityBottom - maxDepth));
		
		for(int y = maxY; y >= minY; y--)
		{
			boolean foundSupport = false;
			
			for(int x = minX; x <= maxX && !foundSupport; x++)
				for(int z = minZ; z <= maxZ && !foundSupport; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = world.getBlockState(pos);
					if(!state.getCollisionShape(world, pos).isEmpty())
						foundSupport = true;
					else
					{
						FluidState fluid = world.getFluidState(pos);
						if(!fluid.isEmpty() && (fluid.is(FluidTags.WATER)
							|| fluid.is(FluidTags.LAVA)))
							foundSupport = true;
					}
				}
			
			if(foundSupport)
			{
				double blockTop = y + 1.0;
				return Math.max(0.0, entityBottom - blockTop);
			}
		}
		
		return maxDepth;
	}
	
	private boolean isBoatSupported(Boat boat)
	{
		ClientLevel world = (ClientLevel)boat.level();
		AABB box = boat.getBoundingBox();
		double sampleMinY = box.minY - 0.2;
		double sampleMaxY = box.minY - 0.05;
		
		int minX = Mth.floor(box.minX);
		int maxX = Mth.floor(box.maxX);
		int minY = Mth.floor(sampleMinY);
		int maxY = Mth.floor(sampleMaxY);
		int minZ = Mth.floor(box.minZ);
		int maxZ = Mth.floor(box.maxZ);
		
		for(int x = minX; x <= maxX; x++)
			for(int y = minY; y <= maxY; y++)
				for(int z = minZ; z <= maxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = world.getBlockState(pos);
					if(!state.getCollisionShape(world, pos).isEmpty())
						return true;
					
					FluidState fluid = world.getFluidState(pos);
					if(!fluid.isEmpty() && fluid.is(FluidTags.WATER))
						return true;
				}
			
		return false;
	}
	
	private static final class PlayerStats
	{
		private double lastX;
		private double lastY;
		private double lastZ;
		private boolean hasLastPosition;
		private int ignoreMovementTicks;
		private int airborneTicks;
		private int groundBufferTicks;
		private int boatAirTicks;
		private int boatAltitudeTicks;
		private int sustainedAltitudeTicks;
		private double ascendedDistance;
		private double lastClearance;
		private float lastSwingProgress;
		private final ArrayDeque<Long> swingTicks = new ArrayDeque<>();
		private final ArrayDeque<Long> swingIntervals = new ArrayDeque<>();
		private long lastSwingTick;
		private long lastSpeedAlertTick;
		private long lastFlightAlertTick;
		private long lastBoatAlertTick;
		private long lastAuraAlertTick;
		// consecutive speed violations
		private int speedViolationCount;
		
		private void resetPosition(Player player)
		{
			lastX = player.getX();
			lastY = player.getY();
			lastZ = player.getZ();
			hasLastPosition = true;
			ignoreMovementTicks = 0;
			airborneTicks = 0;
			groundBufferTicks = 0;
			boatAirTicks = 0;
			boatAltitudeTicks = 0;
			sustainedAltitudeTicks = 0;
			ascendedDistance = 0.0;
			lastClearance = 0.0;
			swingTicks.clear();
			swingIntervals.clear();
			lastSwingTick = 0L;
			lastSwingProgress = player.getAttackAnim(1.0F);
			speedViolationCount = 0;
		}
	}
	
	private boolean isUsingElytra(Player player)
	{
		if(player.isFallFlying())
			return true;
		
		if(player.onGround())
			return false;
		
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		return !chest.isEmpty() && chest.is(Items.ELYTRA);
	}
}
