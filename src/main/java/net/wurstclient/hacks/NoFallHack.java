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
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.autoflypath.PathFlightRuntime;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.PacketUtils;

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
	private double lastPlayerY;
	private boolean hasLastPlayerY;
	private double lastSentPositionY;
	private boolean hasLastSentPositionY;
	
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
		hasLastPlayerY = false;
		hasLastSentPositionY = false;
		WURST.getHax().antiHungerHack.setEnabled(false);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		hasLastPlayerY = false;
		hasLastSentPositionY = false;
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null || player.connection == null)
			return;
		
		boolean actuallyDescending =
			hasLastPlayerY && player.getY() < lastPlayerY - 1.0E-4;
		lastPlayerY = player.getY();
		hasLastPlayerY = true;
		
		// Fall protection is applied to the next real movement packet below.
		// Sending a separate StatusOnly(true) packet races that movement packet
		// on high-latency servers and can make the server end the fall early.
	}
	
	/**
	 * Applies Flight's NoFall spoof to the actual movement packet immediately
	 * before it enters {@code Connection.send()}. This deliberately does not
	 * create another packet: the server must see the grounded flag together
	 * with the descending position, before that movement can add fall distance.
	 */
	public ServerboundMovePlayerPacket protectFlightMovementPacket(
		ServerboundMovePlayerPacket packet)
	{
		if(!packet.hasPosition())
			return packet;
		
		double y = packet.getY(lastSentPositionY);
		boolean descending =
			hasLastSentPositionY && y < lastSentPositionY - 1.0E-4;
		lastSentPositionY = y;
		hasLastSentPositionY = true;
		
		if(!isEnabled() || !descending || !isSafeToSpoofFlightMovement())
			return packet;
		
		if(isPaused(descending))
			return packet;
		
		return PacketUtils.modifyOnGround(packet, true);
	}
	
	/** Clears the packet baseline when another movement hack changes modes. */
	public void resetMovementTracking()
	{
		hasLastSentPositionY = false;
		hasLastPlayerY = false;
	}
	
	/**
	 * Ends the fake fall after MaceDMG has received the server's smash
	 * confirmation. This packet is sent after the attack has been processed,
	 * so it cannot be mistaken for the pre-smash spoof.
	 */
	public void confirmMaceSmashLanding()
	{
		LocalPlayer player = MC.player;
		if(!isEnabled() || player == null || player.connection == null)
			return;
		
		resetMovementTracking();
		player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true,
			player.horizontalCollision));
	}
	
	private boolean isSafeToSpoofFlightMovement()
	{
		LocalPlayer player = MC.player;
		return player != null && player.connection != null
			&& !player.getAbilities().invulnerable && !player.isFallFlying()
			&& !player.isInWater() && !player.isInLava()
			&& !player.onClimbable() && !player.isPassenger();
	}
	
	private boolean isPaused()
	{
		return isPaused(false);
	}
	
	private boolean isPaused(boolean actuallyDescending)
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
			
		// Path flight restores the regular Flight hack as it shuts down. Keep
		// NoFall active for the brief landing transition, otherwise the
		// restored
		// Flight hack can make this fall look like a normal paused flight.
		if(PathFlightRuntime.isLandingProtectionActive())
			return false;
		
		boolean flightActive = WURST.getHax().flightHack.isEnabled()
			|| WURST.getHax().creativeFlightHack.isEnabled()
			|| PathFlightRuntime.isPathFlightActive();
		if(pauseForFlight.isChecked() && flightActive)
		{
			// Keep NoFall active while Flight is deliberately descending.
			// Use the intended direction instead of velocity, which Flight
			// resets every tick.
			boolean descending = WURST.getHax().flightHack.isDescending()
				|| WURST.getHax().creativeFlightHack.isDescending()
				|| PathFlightRuntime.isPathFlightDescending()
				|| actuallyDescending;
			if(!descending)
				return true;
		}
		
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
	
}
