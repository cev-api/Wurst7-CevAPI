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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.damagesource.DamageSource;
import java.util.Locale;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"anti void", "void"})
public final class AntiVoidHack extends Hack implements UpdateListener
{
	private static final long STARTUP_GRACE_TICKS = 40L; // ~2s after join
	private final SliderSetting overworldFalseFloorY = new SliderSetting(
		"Overworld floor Y",
		"Block Y for the fake Overworld floor. The walkable surface is one block above this.",
		-68, -100, -64, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting netherFalseFloorY = new SliderSetting(
		"Nether floor Y",
		"Block Y for the fake Nether floor. The walkable surface is one block above this.",
		-40, -40, -4, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting endFalseFloorY = new SliderSetting(
		"End floor Y",
		"Block Y for the fake End floor. The walkable surface is one block above this.",
		-60, -60, 0, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting lavaFalseFloor = new CheckboxSetting(
		"Lava false floor",
		"Turns lava lakes into solid client-side floors and adds clearance above them to prevent fire damage.",
		true);
	
	private final CheckboxSetting autoEnableOnOutOfWorld =
		new CheckboxSetting("Auto-enable on out_of_world",
			"Automatically enables AntiVoid and rescues to the fixed void level"
				+ " when taking out_of_world damage.",
			false);
	
	private final SliderSetting lavaFloorClearance = new SliderSetting(
		"Lava floor clearance (blocks)", 1, 1, 4, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting autoAdjustLavaFloor = new CheckboxSetting(
		"Auto-adjust lava floor height",
		"Reduces the invisible clearance above lava when a ceiling is too low, leaving only the lava block solid so tunnels remain passable.",
		true);
	
	// Nether/End thresholds are fixed; Overworld uses the floor slider.
	
	private int lastHurtTimeSeen;
	private boolean flightEnabledByAntiVoid;
	private boolean rescueAboveFloor;
	private boolean rescueInProgress;
	
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
				if(!WURST.getHax().flightHack.isEnabled())
				{
					WURST.getHax().flightHack.setEnabled(true);
					flightEnabledByAntiVoid = true;
				}
				hurtAlerted = false;
				rescueAboveFloor = false;
				rescueInProgress = true;
				launchesActive = true;
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
			
			double floorY = falseFloorY();
			if(Double.isNaN(floorY))
			{
				launchesActive = false;
				rescueAboveFloor = false;
				rescueInProgress = false;
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			double aboveFloorY = floorY + 2.0;
			if(rescueAboveFloor)
			{
				// Give the client one clear position above the false floor,
				// then settle onto its surface with a grounded packet.
				p.setDeltaMovement(0, 0, 0);
				p.setOnGround(true);
				p.setPos(p.getX(), floorY, p.getZ());
				p.connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(),
					floorY, p.getZ(), true, p.horizontalCollision));
				launchesActive = false;
				rescueAboveFloor = false;
				lastHurtTimeSeen = p.hurtTime;
				return;
			}
			
			if(p.getY() >= aboveFloorY)
			{
				rescueAboveFloor = true;
				return;
			}
			
			// Launch upward: move the player client-side AND send
			// flying position packets the server will accept.
			double targetY = Math.min(p.getY() + 0.6, aboveFloorY);
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
		addSetting(overworldFalseFloorY);
		addSetting(netherFalseFloorY);
		addSetting(endFalseFloorY);
		addSetting(autoEnableOnOutOfWorld);
		addSetting(lavaFalseFloor);
		addSetting(lavaFloorClearance);
		addSetting(autoAdjustLavaFloor);
		// Always-on listener to catch out_of_world damage even when disabled
		EVENTS.add(UpdateListener.class, alwaysListener);
	}
	
	@Override
	protected void onEnable()
	{
		flightEnabledByAntiVoid = false;
		rescueAboveFloor = false;
		rescueInProgress = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		if(flightEnabledByAntiVoid && WURST.getHax().flightHack.isEnabled())
			WURST.getHax().flightHack.setEnabled(false);
		flightEnabledByAntiVoid = false;
		rescueAboveFloor = false;
		rescueInProgress = false;
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
			return;
		
		if(player.connection == null)
			return;
			
		// The always-on listener owns movement until it has reached the
		// clear position above the false floor and settled onto it.
		if(rescueInProgress)
			return;
		
		if(player.isFallFlying())
			return;
		
		if(applyFalseFloor(player))
			return;
		
		// Detect falling into the void
		if(player.getDeltaMovement().y >= 0 || player.fallDistance <= 2F)
			return;
		
		if(!isOverVoid(player))
			return;
		
		// Alert on rescue
		ChatUtils.message(
			"Falling into void (Y=" + (int)player.getY() + "), rescuing...");
		
		var hax = WURST.getHax();
		if(!hax.flightHack.isEnabled())
		{
			hax.flightHack.setEnabled(true);
			flightEnabledByAntiVoid = true;
			ChatUtils.message("Enabled Flight to escape void.");
		}
		return;
	}
	
	private boolean applyFalseFloor(LocalPlayer player)
	{
		if(MC.level == null)
			return false;
		
		double floorY = falseFloorY();
		if(Double.isNaN(floorY))
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
	
	private double falseFloorY()
	{
		if(MC.level.dimension() == Level.OVERWORLD)
			return overworldFalseFloorY.getValue() + 1.0;
		if(MC.level.dimension() == Level.NETHER)
			return netherFalseFloorY.getValue() + 1.0;
		if(MC.level.dimension() == Level.END)
			return endFalseFloorY.getValue() + 1.0;
		return Double.NaN;
	}
	
	/**
	 * Used by the collision mixin to make the configured void-floor layer a
	 * real client-side collision plane.
	 */
	public boolean shouldMakeFalseFloor(BlockPos pos, BlockState state)
	{
		if(!isEnabled() || !state.isAir() || MC.level == null)
			return false;
		
		int floorBlockY;
		if(MC.level.dimension() == Level.OVERWORLD)
			floorBlockY = overworldFalseFloorY.getValueI();
		else if(MC.level.dimension() == Level.NETHER)
			floorBlockY = netherFalseFloorY.getValueI();
		else if(MC.level.dimension() == Level.END)
			floorBlockY = endFalseFloorY.getValueI();
		else
			return false;
		
		return pos.getY() == floorBlockY;
	}
	
	/**
	 * Used by the collision mixin to create a local floor over lava. The lava
	 * block itself and the configured number of air blocks above it are solid,
	 * so the player cannot enter the damage range above a lava lake.
	 */
	public boolean shouldMakeLavaFloor(BlockGetter world, BlockPos pos,
		BlockState state)
	{
		if(!isEnabled() || !lavaFalseFloor.isChecked())
			return false;
		
		if(state.getFluidState().is(FluidTags.LAVA))
			return true;
		if(!state.isAir())
			return false;
		
		int clearance = lavaFloorClearance.getValueI();
		return hasExposedLavaBelow(world, pos, clearance);
	}
	
	private boolean hasExposedLavaBelow(BlockGetter world, BlockPos pos,
		int clearance)
	{
		for(int i = 1; i <= clearance; i++)
		{
			BlockPos lavaPos = pos.below(i);
			BlockState lavaState = world.getBlockState(lavaPos);
			
			if(!lavaState.getFluidState().is(FluidTags.LAVA))
			{
				// A solid block or another fluid blocks access to everything
				// below it. Do not let this scan see through terrain.
				if(!lavaState.isAir())
					return false;
				continue;
			}
			
			BlockState aboveLava = world.getBlockState(lavaPos.above());
			if(!aboveLava.isAir())
				return false;
			
			int effectiveClearance = clearance;
			if(autoAdjustLavaFloor.isChecked())
			{
				int availableAir = 0;
				BlockPos airPos = lavaPos.above();
				// Only enough air is relevant to calculate the requested
				// clearance. Scanning until the first solid block can become an
				// unbounded loop in an open cavern or above the world ceiling.
				int maxAirToCheck = clearance + 2;
				while(availableAir < maxAirToCheck
					&& world.getBlockState(airPos).isAir())
				{
					availableAir++;
					airPos = airPos.above();
				}
				
				// Leave two air blocks above the adjusted floor so a normal
				// player can still pass through a low tunnel.
				effectiveClearance =
					Math.min(clearance, Math.max(0, availableAir - 2));
			}
			
			return i <= effectiveClearance;
		}
		
		return false;
	}
	
	private boolean isOverVoid(LocalPlayer player)
	{
		double voidY = fixedVoidLevel();
		if(player.getY() > voidY)
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
	
	private double fixedVoidLevel()
	{
		if(MC.level == null)
			return overworldFalseFloorY.getValue();
		String key = MC.level.dimension().identifier().getPath();
		if("the_end".equals(key))
			return -60.0;
		if("the_nether".equals(key))
			return -60.0;
		// Overworld
		return overworldFalseFloorY.getValue();
	}
	
	// No height band method needed; using fixed thresholds.
	
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
