/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
	private final SliderSetting speedThreshold =
		new SliderSetting("Speed threshold (blocks/s)", 9.0, 5.0, 40.0, 0.5,
			ValueDisplay.DECIMAL);
	
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
	private ClientWorld lastWorld;
	
	public CheatDetectorHack()
	{
		super("CheatDetector");
		setCategory(Category.OTHER);
		addSetting(detectSpeed);
		addSetting(speedThreshold);
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
		lastWorld = MC.world;
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
		if(MC.world == null || MC.player == null)
		{
			playerStats.clear();
			lastWorld = null;
			return;
		}
		
		if(MC.world != lastWorld)
		{
			playerStats.clear();
			lastWorld = MC.world;
		}
		
		tickCounter++;
		Set<UUID> seen = new HashSet<>();
		
		for(PlayerEntity other : MC.world.getPlayers())
		{
			if(other == MC.player || other.isRemoved())
				continue;
			
			UUID id = other.getUuid();
			seen.add(id);
			
			PlayerStats stats =
				playerStats.computeIfAbsent(id, uuid -> new PlayerStats());
			processPlayer(other, stats);
		}
		
		playerStats.keySet().removeIf(id -> !seen.contains(id));
	}
	
	private void processPlayer(PlayerEntity player, PlayerStats stats)
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
	
	private void checkSpeed(PlayerEntity player, PlayerStats stats,
		double horizontalPerSecond)
	{
		if(!detectSpeed.isChecked())
			return;
		
		if(isUsingElytra(player) || player.hasVehicle()
			|| player.isTouchingWater() || player.isSwimming())
			return;
		
		if(player.hasStatusEffect(StatusEffects.SPEED)
			&& horizontalPerSecond <= speedThreshold.getValue() * 1.2)
			return;
		
		if(horizontalPerSecond <= speedThreshold.getValue())
			return;
		
		if(tickCounter - stats.lastSpeedAlertTick < ALERT_COOLDOWN_TICKS)
			return;
		
		stats.lastSpeedAlertTick = tickCounter;
		sendAlert(player,
			String.format(Locale.ROOT, "suspected of speed (%s blocks/s)",
				formatDouble(horizontalPerSecond)));
	}
	
	private void updateFlightPattern(PlayerEntity player, PlayerStats stats,
		double dy)
	{
		double clearance = getClearanceAboveGround(player, 16);
		
		if(!player.isOnGround() && !player.isTouchingWater()
			&& player.getVehicle() == null && !player.isClimbing())
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
	
	private void checkFlight(PlayerEntity player, PlayerStats stats, double dy,
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
		
		boolean airborne = !player.isOnGround() && !player.isTouchingWater()
			&& player.getVehicle() == null && !player.isClimbing()
			&& !player.hasStatusEffect(StatusEffects.SLOW_FALLING)
			&& !player.hasStatusEffect(StatusEffects.LEVITATION);
		
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
	
	private void checkBoatFly(PlayerEntity player, PlayerStats stats)
	{
		if(!detectBoatFly.isChecked())
		{
			stats.boatAirTicks = 0;
			return;
		}
		
		Entity vehicle = player.getVehicle();
		if(!(vehicle instanceof BoatEntity boat))
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
		
		Vec3d vel = boat.getVelocity();
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
	
	private void checkAura(PlayerEntity player, PlayerStats stats)
	{
		if(!detectAura.isChecked())
		{
			stats.swingTicks.clear();
			stats.swingIntervals.clear();
			stats.lastSwingTick = 0L;
			stats.lastSwingProgress = player.getHandSwingProgress(1.0F);
			return;
		}
		
		float swingProgress = player.getHandSwingProgress(1.0F);
		
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
	
	private void sendAlert(PlayerEntity player, String reason)
	{
		String name = player.getName().getString();
		ChatUtils.message("CheatDetector: " + name + " " + reason);
	}
	
	private String formatDouble(double value)
	{
		return String.format(Locale.ROOT, "%.1f", value);
	}
	
	private double getClearanceAboveGround(Entity entity, int maxDepth)
	{
		ClientWorld world = (ClientWorld)entity.getEntityWorld();
		Box box = entity.getBoundingBox();
		double entityBottom = box.minY;
		
		int minX = MathHelper.floor(box.minX);
		int maxX = MathHelper.ceil(box.maxX);
		int minZ = MathHelper.floor(box.minZ);
		int maxZ = MathHelper.ceil(box.maxZ);
		
		int maxY = MathHelper.floor(entityBottom);
		int minY = Math.max(world.getBottomY(),
			MathHelper.floor(entityBottom - maxDepth));
		
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
						if(!fluid.isEmpty() && (fluid.isIn(FluidTags.WATER)
							|| fluid.isIn(FluidTags.LAVA)))
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
	
	private boolean isBoatSupported(BoatEntity boat)
	{
		ClientWorld world = (ClientWorld)boat.getEntityWorld();
		Box box = boat.getBoundingBox();
		double sampleMinY = box.minY - 0.2;
		double sampleMaxY = box.minY - 0.05;
		
		int minX = MathHelper.floor(box.minX);
		int maxX = MathHelper.floor(box.maxX);
		int minY = MathHelper.floor(sampleMinY);
		int maxY = MathHelper.floor(sampleMaxY);
		int minZ = MathHelper.floor(box.minZ);
		int maxZ = MathHelper.floor(box.maxZ);
		
		for(int x = minX; x <= maxX; x++)
			for(int y = minY; y <= maxY; y++)
				for(int z = minZ; z <= maxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = world.getBlockState(pos);
					if(!state.getCollisionShape(world, pos).isEmpty())
						return true;
					
					FluidState fluid = world.getFluidState(pos);
					if(!fluid.isEmpty() && fluid.isIn(FluidTags.WATER))
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
		
		private void resetPosition(PlayerEntity player)
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
			lastSwingProgress = player.getHandSwingProgress(1.0F);
		}
	}
	
	private boolean isUsingElytra(PlayerEntity player)
	{
		if(player.isGliding())
			return true;
		
		if(player.isOnGround())
			return false;
		
		ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
		return !chest.isEmpty() && chest.isOf(Items.ELYTRA);
	}
}
