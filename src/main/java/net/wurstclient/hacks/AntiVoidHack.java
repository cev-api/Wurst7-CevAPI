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
import net.minecraft.world.level.Level;
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
import net.wurstclient.util.ChatUtils;
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
	
	private final CheckboxSetting falseFloor = new CheckboxSetting(
		"False floor",
		"Treat the Overworld, Nether and End as if they had a solid floor below you.",
		false);
	
	private final SliderSetting overworldFalseFloorY = new SliderSetting(
		"Overworld floor Y",
		"Block Y for the fake Overworld floor. The walkable surface is one block above this.",
		-4, -64, 320, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting netherFalseFloorY = new SliderSetting(
		"Nether floor Y",
		"Block Y for the fake Nether floor. The walkable surface is one block above this.",
		-40, -40, -4, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting endFalseFloorY = new SliderSetting(
		"End floor Y",
		"Block Y for the fake End floor. The walkable surface is one block above this.",
		-60, -60, 0, 1, ValueDisplay.INTEGER);
	
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
			true);
	
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
	private boolean flightEnabledByAntiVoid;
	
	// Always-on update listener (registered in constructor)
	private final UpdateListener alwaysListener = new UpdateListener()
	{
		private boolean hurtAlerted;
		private boolean launchesActive;
		
		@Override
		public void onUpdate()
		{
			LocalPlayer p = MC.player;
			if(p == null)
				return;
			if(MC.level == null || MC.level.getGameTime() < STARTUP_GRACE_TICKS)
			{
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			if(p.connection == null)
			{
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			
			// Auto-enable on out_of_world damage
			if(autoEnableOnOutOfWorld.isChecked()
				&& p.hurtTime > lastHurtTimeSeen
				&& isOutOfWorldDamage(p.getLastDamageSource()))
			{
				if(!isEnabled())
				{
					setEnabled(true);
					ChatUtils.message("Void damage! Enabled AntiVoid.");
				}
				if(useFlight.isChecked()
					&& !WURST.getHax().flightHack.isEnabled())
				{
					WURST.getHax().flightHack.setEnabled(true);
					flightEnabledByAntiVoid = true;
				}
				hurtAlerted = false;
				launchesActive = true;
			}
			
			// Height-based auto-enable
			if(autoEnableByHeight.isChecked() && MC.level != null)
			{
				double y = p.getY();
				if(p.getDeltaMovement().y < 0 && p.fallDistance > 2F
					&& y <= fixedVoidLevel() && !isEnabled())
				{
					setEnabled(true);
					ChatUtils.message("Below void threshold (Y=" + (int)y
						+ "), enabled AntiVoid.");
				}
			}
			
			// Launch: every tick AntiVoid is on and player is below
			// safe Y, force-send flying position packets to move up.
			// Flying packets (onGround=false) are accepted by servers
			// unlike grounded teleports.
			if(!isEnabled())
			{
				launchesActive = false;
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			
			if(!launchesActive)
			{
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			
			double safeY = rescueTargetY();
			boolean stillDamaged =
				p.hurtTime > 0 || isOutOfWorldDamage(p.getLastDamageSource());
			if(!stillDamaged && p.getY() >= safeY)
			{
				launchesActive = false;
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			
			// Launch upward: move the player client-side AND send
			// flying position packets the server will accept.
			double targetY = p.getY() + 0.6;
			p.setPos(p.getX(), targetY, p.getZ());
			p.setDeltaMovement(p.getDeltaMovement().x, 0.42,
				p.getDeltaMovement().z);
			p.fallDistance = 0;
			
			// Send as flying (onGround=false) — server trusts these
			p.connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(),
				targetY, p.getZ(), false, p.horizontalCollision));
			
			if(!hurtAlerted)
			{
				ChatUtils.message(
					"Launching out of void (Y=" + (int)targetY + ")...");
				hurtAlerted = true;
			}
			
			launchesActive = true;
			lastHurtTimeSeen = p.hurtTime;
		}
	};
	
	public AntiVoidHack()
	{
		super("AntiVoid");
		setCategory(Category.MOVEMENT);
		addSetting(useAirWalk);
		addSetting(falseFloor);
		addSetting(overworldFalseFloorY);
		addSetting(netherFalseFloorY);
		addSetting(endFalseFloorY);
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
		flightEnabledByAntiVoid = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		if(flightEnabledByAntiVoid && WURST.getHax().flightHack.isEnabled())
			WURST.getHax().flightHack.setEnabled(false);
		flightEnabledByAntiVoid = false;
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
		
		if(player.onGround() && !player.isInWater() && !player.isInLava())
			lastSafePos = player.position();
		
		if(player.isFallFlying())
			return;
		
		if(applyFalseFloor(player))
			return;
		
		if(airWalkActive)
		{
			applyAirWalk(player);
			return;
		}
		
		// Detect falling into void/lava
		if(player.getDeltaMovement().y >= 0 || player.fallDistance <= 2F)
			return;
		
		boolean overVoid = isOverVoid(player);
		boolean overLava = isOverLava(player);
		
		if(!overVoid && !overLava)
			return;
		
		// Alert on rescue
		{
			String cause = overVoid ? "void" : "lava";
			ChatUtils.message("Falling into " + cause + " (Y="
				+ (int)player.getY() + "), rescuing...");
		}
		
		if(useFlight.isChecked())
		{
			var hax = WURST.getHax();
			if(!hax.flightHack.isEnabled())
			{
				hax.flightHack.setEnabled(true);
				ChatUtils.message("Enabled Flight to escape "
					+ (overVoid ? "void" : "lava") + ".");
			}
			rescueActive = true;
			return;
		}
		
		if(useAirWalk.isChecked())
		{
			if(!airWalkActive)
			{
				ChatUtils.message("Enabled AirWalk to escape "
					+ (overVoid ? "void" : "lava") + ".");
				airWalkActive = true;
				rescueActive = true;
				airWalkY = player.getY();
			}
			applyAirWalk(player);
			return;
		}
		
		// Fallback: rubberband to last safe position
		if(lastSafePos == null)
			return;
		
		if(!rescueActive)
		{
			ChatUtils
				.message("No safe Y — rubberbanding to last ground position.");
			rescueActive = true;
		}
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
	
	private boolean applyFalseFloor(LocalPlayer player)
	{
		if(!falseFloor.isChecked() || MC.level == null)
			return false;
		
		double floorY;
		if(MC.level.dimension() == Level.OVERWORLD)
			floorY = overworldFalseFloorY.getValue() + 1.0;
		else if(MC.level.dimension() == Level.NETHER)
			floorY = netherFalseFloorY.getValue() + 1.0;
		else if(MC.level.dimension() == Level.END)
			floorY = endFalseFloorY.getValue() + 1.0;
		else
			return false;
		
		if(player.isInWater() || player.isInLava() || player.onClimbable())
			return false;
		
		if(player.getY() > floorY)
			return false;
		
		Vec3 v = player.getDeltaMovement();
		player.setDeltaMovement(v.x, Math.max(0, v.y), v.z);
		player.setOnGround(true);
		player.fallDistance = 0;
		if(Math.abs(player.getY() - floorY) > 1e-4)
			player.setPos(player.getX(), floorY, player.getZ());
		
		return true;
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
	
	/**
	 * Returns a safe Y level above void damage based on fixedVoidLevel().
	 * Overworld: -117 (4 blocks above -121 damage), Nether/End: -57.
	 */
	private double rescueTargetY()
	{
		return fixedVoidLevel() + 10.0;
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
