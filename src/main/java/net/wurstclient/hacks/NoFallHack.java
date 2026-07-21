/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"no fall"})
public final class NoFallHack extends Hack implements UpdateListener
{
	private final CheckboxSetting allowElytra = new CheckboxSetting(
		"Allow elytra", "description.wurst.setting.nofall.allow_elytra", false);
	
	private final CheckboxSetting pauseForMace =
		new CheckboxSetting("Pause for mace",
			"description.wurst.setting.nofall.pause_for_mace", false);
	
	private final CheckboxSetting pauseForFlight =
		new CheckboxSetting("Pause during Flight",
			"description.wurst.setting.nofall.pause_for_flight", false);
	
	private final SliderSetting minFallDistance =
		new SliderSetting("Min fall distance",
			"description.wurst.setting.nofall.min_fall_distance", 1, 0, 10, 0.1,
			ValueDisplay.DECIMAL.withSuffix("m").withLabel(0, "off"));
	
	private final SliderSetting minFallDistanceElytra =
		new SliderSetting("Min elytra fall distance",
			"description.wurst.setting.nofall.min_elytra_fall_distance", 2, 0,
			10, 0.1, ValueDisplay.DECIMAL.withSuffix("m").withLabel(0, "off"));
	
	public NoFallHack()
	{
		super("NoFall");
		setCategory(Category.MOVEMENT);
		addSetting(allowElytra);
		addSetting(pauseForMace);
		addSetting(pauseForFlight);
		addSetting(minFallDistance);
		addSetting(minFallDistanceElytra);
	}
	
	@Override
	public String getRenderName()
	{
		if(MC.player != null && isPaused())
			return getName() + " (paused)";
		
		return getName();
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().antiHungerHack.setEnabled(false);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(isPaused())
			return;
		
		// send packet to stop fall damage
		MC.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(
			true, MC.player.horizontalCollision));
	}
	
	private boolean isPaused()
	{
		// do nothing in creative mode, since there is no fall damage anyway
		LocalPlayer player = MC.player;
		if(player.getAbilities().invulnerable)
			return true;
		
		// pause when flying with elytra, unless allowed
		boolean fallFlying = player.isFallFlying();
		if(fallFlying && !allowElytra.isChecked())
			return true;
		
		// pause when holding a mace, if enabled
		if(pauseForMace.isChecked() && player.getMainHandItem().is(Items.MACE))
			return true;
			
		// Flight controls the player's vertical motion directly. In particular,
		// descending with Flight raises fallDistance even though the player has
		// not started a normal fall. Ground packets then use hunger, so wait
		// until a landing surface is close before protecting a fall.
		if(pauseForFlight.isChecked() && WURST.getHax().flightHack.isEnabled()
			&& !isCloseToGround(player))
			return true;
			
		// ignore small falls that can't cause damage,
		// unless CreativeFlight is enabled in survival mode
		boolean creativeFlying = WURST.getHax().creativeFlightHack.isEnabled()
			&& player.getAbilities().flying;
		if(!creativeFlying && player.fallDistance <= (fallFlying
			? minFallDistanceElytra.getValue() : minFallDistance.getValue()))
			return true;
		
		// attempt to fix elytra weirdness, if allowed
		if(fallFlying && player.isShiftKeyDown()
			&& !isFallingFastEnoughToCauseDamage(player))
			return true;
		
		return false;
	}
	
	private boolean isFallingFastEnoughToCauseDamage(LocalPlayer player)
	{
		return player.getDeltaMovement().y < -0.5;
	}
	
	private boolean isCloseToGround(LocalPlayer player)
	{
		if(MC.level == null)
			return false;
			
		// Leave enough room for Flight's next vertical movement, including at
		// its maximum configured speed, before it can reach the surface.
		double distance =
			WURST.getHax().flightHack.getActualVerticalSpeed() + 1;
		Vec3 start = new Vec3(player.getX(), player.getBoundingBox().minY,
			player.getZ());
		Vec3 end = start.add(0, -distance, 0);
		HitResult result = MC.level.clip(new ClipContext(start, end,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return result.getType() == HitResult.Type.BLOCK;
	}
}
