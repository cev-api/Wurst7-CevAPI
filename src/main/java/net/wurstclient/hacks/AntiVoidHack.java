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
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;

@SearchTags({"anti void", "void"})
public final class AntiVoidHack extends Hack implements UpdateListener
{
	private final SliderSetting voidLevel = new SliderSetting("Void level",
		"Y level where the void begins.", 0, -256, 0, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting useAirWalk = new CheckboxSetting(
		"Use AirWalk",
		"Prevents falling into the void/lava by air-walking instead of rubberbanding.",
		false);
	
	private final CheckboxSetting detectLava = new CheckboxSetting(
		"Detect lava",
		"Also prevents falling into lava when it is directly below you.", true);
	
	private Vec3 lastSafePos;
	private boolean airWalkActive;
	private double airWalkY;
	private boolean rescueActive;
	private boolean jumpWasDown;
	
	public AntiVoidHack()
	{
		super("AntiVoid");
		setCategory(Category.MOVEMENT);
		addSetting(voidLevel);
		addSetting(useAirWalk);
		addSetting(detectLava);
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
		
		if(rescueActive && isFlyingHackEnabled())
		{
			setEnabled(false);
			return;
		}
		
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
		double voidY = voidLevel.getValue();
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
				return state.getFluidState().is(FluidTags.LAVA);
			
			if(!state.isAir())
				return false;
		}
		
		return false;
	}
}
