/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.damagesource.DamageSource;
import java.util.Locale;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"anti void", "void"})
public final class AntiVoidHack extends Hack implements UpdateListener
{
	private static final long STARTUP_GRACE_TICKS = 40L; // ~2s after join
	private final CheckboxSetting useAirWalk = new CheckboxSetting(
		"Use AirWalk",
		"Prevents falling into the void/lava by air-walking instead of rubberbanding.",
		false);
	
	private final CheckboxSetting detectLava = new CheckboxSetting(
		"Detect lava",
		"Also prevents falling into lava when it is directly below you.", true);
	
	private final CheckboxSetting gateAtVoidLevel = new CheckboxSetting(
		"Respond only at void level",
		"Only trigger when reaching the standard void level (End: -60, Others: -125).\n"
			+ "For lava, triggers one block above the lava surface.",
		false);
	
	private final CheckboxSetting useFlight = new CheckboxSetting("Use Flight",
		"When triggered, enable Flight instead of rubberbanding/AirWalk.",
		false);
	
	private final CheckboxSetting autoEnableOnOutOfWorld =
		new CheckboxSetting("Auto-enable on out_of_world",
			"Automatically enables AntiVoid and rescues to the fixed void level"
				+ " when taking out_of_world damage.",
			false);
	
	private final SliderSetting lavaBufferBlocks = new SliderSetting(
		"Lava buffer (blocks)", 2, 0, 12, 1, ValueDisplay.INTEGER);
	
	// Fixed thresholds are used; no per-dimension sliders.
	
	private final CheckboxSetting autoEnableByHeight = new CheckboxSetting(
		"Auto-enable by height",
		"Automatically enables AntiVoid when your Y is within a safety band below 0.\n"
			+ "Defaults: End -65..0, Others -125..-60.",
		true);
	
	private Vec3 lastSafePos;
	private boolean airWalkActive;
	private double airWalkY;
	private boolean rescueActive;
	private boolean jumpWasDown;
	private int lastHurtTimeSeen;
	private long lastAutoRescueMs;
	
	// Always-on update listener (registered in constructor)
	private final UpdateListener alwaysListener = new UpdateListener()
	{
		@Override
		public void onUpdate()
		{
			LocalPlayer p = MC.player;
			if(p == null)
				return;
			// Avoid enabling during early startup before other hacks restore
			if(MC.level == null || MC.level.getGameTime() < STARTUP_GRACE_TICKS)
			{
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			// We still allow auto-enable at thresholds even if flight is
			// active.
			int ht = p.hurtTime;
			if(autoEnableOnOutOfWorld.isChecked() && ht > lastHurtTimeSeen)
			{
				DamageSource src = p.getLastDamageSource();
				if(isOutOfWorldDamage(src))
				{
					long now = System.currentTimeMillis();
					if(now - lastAutoRescueMs > 500)
					{
						if(!isEnabled())
							setEnabled(true);
						rescueToFixedLevel(p);
						lastAutoRescueMs = now;
					}
				}
			}
			// Height-based auto-enable at fixed thresholds while falling
			if(autoEnableByHeight.isChecked() && MC.level != null)
			{
				double y = p.getY();
				boolean falling =
					p.getDeltaMovement().y < 0 && p.fallDistance > 2F;
				double threshold = fixedVoidLevel();
				if(falling && y <= threshold && !isEnabled())
					setEnabled(true);
				// If flight is active and we're above threshold, keep AntiVoid
				// off
				if(isFlyingHackEnabled() && y > threshold && isEnabled())
					setEnabled(false);
			}
			lastHurtTimeSeen = ht;
		}
	};
	
	public AntiVoidHack()
	{
		super("AntiVoid");
		setCategory(Category.MOVEMENT);
		addSetting(useAirWalk);
		addSetting(detectLava);
		addSetting(gateAtVoidLevel);
		addSetting(useFlight);
		addSetting(autoEnableOnOutOfWorld);
		addSetting(autoEnableByHeight);
		addSetting(lavaBufferBlocks);
		// Always-on listener to catch out_of_world damage even when disabled
		EVENTS.add(UpdateListener.class, alwaysListener);
	}
	
	@Override
	protected void onEnable()
	{
		lastSafePos = null;
		airWalkActive = false;
		airWalkY = Double.NaN;
		rescueActive = false;
		jumpWasDown = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		lastSafePos = null;
		airWalkActive = false;
		airWalkY = Double.NaN;
		rescueActive = false;
		jumpWasDown = false;
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
			return;
		
		if(player.connection == null)
			return;
		
		if(airWalkActive && isBackOnSurface(player))
		{
			airWalkActive = false;
			airWalkY = Double.NaN;
			rescueActive = false;
			jumpWasDown = false;
		}
		
		if(!airWalkActive && (player.onGround() || player.isInWater()
			|| player.isInLava() || player.onClimbable()))
			rescueActive = false;
			
		// Do not force-disable here; alwaysListener handles
		// flight-vs-threshold.
		
		if(player.onGround() && !player.isInWater() && !player.isInLava())
			lastSafePos = player.position();
		
		if(player.isFallFlying())
			return;
		
		if(airWalkActive)
		{
			applyAirWalk(player);
			return;
		}
		
		if(player.getDeltaMovement().y >= 0 || player.fallDistance <= 2F)
			return;
		
		if(!isOverVoid(player) && !isOverLava(player))
			return;
		
		if(useFlight.isChecked())
		{
			// Hand off to Flight and stop our intervention
			rescueActive = true;
			var hax = WURST.getHax();
			if(!hax.flightHack.isEnabled())
				hax.flightHack.setEnabled(true);
			return;
		}
		
		if(useAirWalk.isChecked())
		{
			airWalkActive = true;
			rescueActive = true;
			airWalkY = player.getY();
			applyAirWalk(player);
			return;
		}
		
		if(lastSafePos == null)
			return;
		
		rescueActive = true;
		player.setDeltaMovement(0, 0, 0);
		player.fallDistance = 0;
		player.setOnGround(true);
		player.setPos(lastSafePos.x, lastSafePos.y, lastSafePos.z);
		
		player.connection.send(
			new ServerboundMovePlayerPacket.Pos(lastSafePos.x, lastSafePos.y,
				lastSafePos.z, true, player.horizontalCollision));
	}
	
	private void applyAirWalk(LocalPlayer player)
	{
		if(Double.isNaN(airWalkY))
			airWalkY = player.getY();
		
		boolean jumpDown = MC.options.keyJump.isDown();
		if(jumpDown && !jumpWasDown)
		{
			double targetY = airWalkY + 1.0;
			AABB box =
				player.getBoundingBox().move(0, targetY - player.getY(), 0);
			
			if(MC.level.noCollision(player, box))
				airWalkY = targetY;
		}
		jumpWasDown = jumpDown;
		
		Vec3 v = player.getDeltaMovement();
		player.setDeltaMovement(v.x, 0, v.z);
		player.setOnGround(true);
		player.fallDistance = 0;
		
		if(Math.abs(player.getY() - airWalkY) > 1e-4)
			player.setPos(player.getX(), airWalkY, player.getZ());
	}
	
	private boolean isBackOnSurface(LocalPlayer player)
	{
		if(player.onGround())
			return true;
		AABB checkBox = player.getBoundingBox().move(0, -0.05, 0);
		return BlockUtils.getBlockCollisions(checkBox).findAny().isPresent();
	}
	
	private boolean isFlyingHackEnabled()
	{
		return WURST.getHax().flightHack.isEnabled()
			|| WURST.getHax().creativeFlightHack.isEnabled()
			|| WURST.getHax().elytraFlightHack.isEnabled()
			|| WURST.getHax().jetpackHack.isEnabled();
	}
	
	private boolean isOverVoid(LocalPlayer player)
	{
		double voidY = fixedVoidLevel();
		if(gateAtVoidLevel.isChecked() && player.getY() > voidY)
			return false;
		if(player.getY() <= voidY && !player.isInWater() && !player.isInLava())
			return true;
		
		int startY = player.getBlockY();
		int minY = MC.level.getMinY();
		int endY = Math.max(minY, Mth.floor(voidY));
		
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = startY; y >= endY; y--)
		{
			pos.set(player.getBlockX(), y, player.getBlockZ());
			BlockState state = MC.level.getBlockState(pos);
			
			if(!state.getFluidState().isEmpty())
				return false;
			
			if(!state.isAir())
				return false;
		}
		
		return true;
	}
	
	private boolean isOverLava(LocalPlayer player)
	{
		if(!detectLava.isChecked())
			return false;
		Integer lavaY = lavaYBelow(player);
		if(lavaY == null)
			return false;
		double buffer = lavaBufferBlocks.getValue();
		return player.getY() <= lavaY + buffer;
	}
	
	private Integer lavaYBelow(LocalPlayer player)
	{
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int startY = player.getBlockY();
		int minY = MC.level.getMinY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = startY; y >= minY; y--)
		{
			pos.set(x, y, z);
			BlockState state = MC.level.getBlockState(pos);
			if(!state.getFluidState().isEmpty())
			{
				if(state.getFluidState().is(FluidTags.LAVA))
					return y;
				return null; // other fluid blocks detection stops search
			}
			if(!state.isAir())
				return null; // solid found before lava
		}
		return null;
	}
	
	private double fixedVoidLevel()
	{
		if(MC.level == null)
			return -120.0;
		String key = MC.level.dimension().identifier().getPath();
		if("the_end".equals(key))
			return -60.0;
		if("the_nether".equals(key))
			return -60.0;
		// Overworld
		return -120.0;
	}
	
	// No height band method needed; using fixed thresholds.
	
	private void rescueToFixedLevel(LocalPlayer player)
	{
		double targetY = fixedVoidLevel() + 1.0;
		Vec3 here = player.position();
		player.setDeltaMovement(0, 0, 0);
		player.fallDistance = 0;
		player.setOnGround(true);
		player.setPos(here.x, targetY, here.z);
		if(player.connection != null)
		{
			player.connection.send(new ServerboundMovePlayerPacket.Pos(here.x,
				targetY, here.z, true, player.horizontalCollision));
		}
		lastSafePos = new Vec3(here.x, targetY, here.z);
		// If configured, immediately hold the player using AirWalk after rescue
		if(useAirWalk.isChecked())
		{
			airWalkActive = true;
			rescueActive = true;
			airWalkY = targetY;
		}
	}
	
	private boolean isOutOfWorldDamage(DamageSource src)
	{
		if(src == null)
			return false;
		String id = src.getMsgId();
		if(id == null)
			return false;
		String norm = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		// Accept common forms: out_of_world, outOfWorld, minecraft:out_of_world
		return norm.endsWith("outofworld");
	}
}
