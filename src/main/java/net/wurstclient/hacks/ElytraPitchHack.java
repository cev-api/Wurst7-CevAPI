/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"elytra pitch", "pitch40", "elytra fly", "efly"})
public final class ElytraPitchHack extends Hack implements UpdateListener
{
	private static final double GROUND_WARNING_CLEARANCE = 10.0;
	private static final double GROUND_FLASH_CLEARANCE = 10.0;
	
	private final CheckboxSetting yawLock = new CheckboxSetting("Yaw lock",
		"Locks your yaw to the nearest 45-degree direction while gliding.",
		false);
	
	private final CheckboxSetting groundSafety = new CheckboxSetting(
		"Ground safety",
		"Pitches up and adds upward velocity when you are about to hit the ground.",
		false);
	
	private final CheckboxSetting thirdPerson = new CheckboxSetting(
		"Third person", "Switches to third-person view while gliding.", false);
	
	private final SliderSetting minimumStartHeight =
		new SliderSetting("Minimum start height",
			"The minimum clearance above the ground before ElytraPitch starts.",
			60, 0, 320, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	private final CheckboxSetting showStartHeightDistance =
		new CheckboxSetting("Show start-height distance",
			"Shows how many blocks remain before ElytraPitch can start.", true);
	
	private boolean started;
	private boolean constantPitch;
	private boolean lookingUp;
	private float lockedYaw;
	private float pitchToAdjust;
	private int tickDelay;
	private CameraType previousCameraType;
	
	public ElytraPitchHack()
	{
		super("ElytraPitch");
		setCategory(Category.MOVEMENT);
		addSetting(yawLock);
		addSetting(groundSafety);
		addSetting(thirdPerson);
		addSetting(minimumStartHeight);
		addSetting(showStartHeightDistance);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		resetFlightState();
		previousCameraType = MC.options.getCameraType();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		if(previousCameraType != null)
			MC.options.setCameraType(previousCameraType);
		previousCameraType = null;
		resetFlightState();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null || WURST.getHax().elytraWalkHack.isGroundSkating()
			|| !isWearingElytra(player) || !player.isFallFlying()
			|| player.isInLiquid())
		{
			resetFlightState();
			return;
		}
		
		if(isDescentCancelled())
		{
			resetFlightState();
			return;
		}
		
		if(!started && !hasSafeFlightHeight(player))
		{
			resetFlightState();
			return;
		}
		
		if(groundSafety.isChecked() && isAboutToHitGround(player))
		{
			emergencyAscend(player);
			return;
		}
		
		if(thirdPerson.isChecked())
			MC.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		
		if(yawLock.isChecked())
		{
			if(Float.isNaN(lockedYaw))
				lockedYaw = player.getYRot();
			player.setYRot(Math.round(lockedYaw / 45) * 45);
		}
		
		if(!started)
		{
			pitchToAdjust = 40;
			player.setXRot(pitchToAdjust);
			tickDelay = 0;
			started = true;
			constantPitch = true;
			lookingUp = false;
		}
		
		if(constantPitch)
			applyConstantPitch(player);
		else
			adjustPitch(player);
	}
	
	private boolean isDescentCancelled()
	{
		if(MC.options != null && MC.options.keyShift != null
			&& MC.options.keyShift.isDown())
			return true;
		
		if(MC.getWindow() == null)
			return false;
		
		return InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
			|| InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
	}
	
	private boolean isAboutToHitGround(LocalPlayer player)
	{
		double clearance = getGroundClearance(player);
		if(clearance <= GROUND_WARNING_CLEARANCE)
			return true;
		
		// Start recovering before the normal warning range when descending.
		if(!lookingUp && clearance <= 24.0)
			return true;
		
		double downwardSpeed = -player.getDeltaMovement().y;
		return downwardSpeed > 0.01 && clearance / downwardSpeed <= 30.0;
	}
	
	private double getGroundClearance(LocalPlayer player)
	{
		if(MC.level == null)
			return Double.POSITIVE_INFINITY;
		
		int groundY =
			MC.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				(int)Math.floor(player.getX()), (int)Math.floor(player.getZ()));
		return player.getY() - groundY;
	}
	
	public double getGroundClearance()
	{
		LocalPlayer player = MC.player;
		return player == null ? Double.POSITIVE_INFINITY
			: getGroundClearance(player);
	}
	
	private void emergencyAscend(LocalPlayer player)
	{
		lookingUp = true;
		constantPitch = true;
		tickDelay = 0;
		pitchToAdjust = -40;
		player.setXRot(pitchToAdjust);
		
		var velocity = player.getDeltaMovement();
		player.setDeltaMovement(velocity.x, Math.max(velocity.y, 0.25),
			velocity.z);
	}
	
	private boolean hasSafeFlightHeight(LocalPlayer player)
	{
		if(MC.level == null || player.onGround())
			return false;
		
		return getGroundClearance(player) >= getMinimumStartHeight();
	}
	
	public boolean isWearingElytra()
	{
		return MC.player != null && isWearingElytra(MC.player);
	}
	
	private boolean isWearingElytra(LocalPlayer player)
	{
		return player.getItemBySlot(EquipmentSlot.CHEST)
			.getItem() == Items.ELYTRA;
	}
	
	public int getBlocksUntilStartHeight()
	{
		return (int)Math
			.ceil(Math.max(0, getMinimumStartHeight() - getGroundClearance()));
	}
	
	private double getMinimumStartHeight()
	{
		return minimumStartHeight.getValue();
	}
	
	public boolean isValidElytraHeight()
	{
		LocalPlayer player = MC.player;
		return player != null && isWearingElytra(player)
			&& player.isFallFlying() && !player.isInLiquid() && started;
	}
	
	public String getFlightStatusText()
	{
		if(!isWearingElytra())
			return null;
		
		if(!isValidElytraHeight())
		{
			String waiting = "Waiting for start height";
			if(showStartHeightDistance.isChecked())
				waiting += " - " + getBlocksUntilStartHeight() + " blocks";
			return waiting;
		}
		
		String direction = lookingUp ? "Ascending" : "Descending";
		return direction + " - " + formatGroundClearance(getGroundClearance());
	}
	
	@Override
	public String getStatusText()
	{
		if(!isWearingElytra())
			return null;
		
		return isValidElytraHeight() ? null : "[Waiting]";
	}
	
	@Override
	public int getStatusTextColor()
	{
		if(!isWearingElytra() || !isValidElytraHeight())
			return 0xFF55FF55;
		
		return getGroundClearance() <= GROUND_WARNING_CLEARANCE ? 0xFFFF3333
			: 0xFF55FF55;
	}
	
	public boolean isGroundStatusFlashing()
	{
		return isValidElytraHeight()
			&& getGroundClearance() <= GROUND_FLASH_CLEARANCE;
	}
	
	public boolean isGroundStatusVisible()
	{
		return !isGroundStatusFlashing()
			|| (System.currentTimeMillis() / 200L) % 2L == 0L;
	}
	
	private static String formatGroundClearance(double clearance)
	{
		return String.format(Locale.ROOT, "%.1f", Math.max(0.0, clearance));
	}
	
	private void applyConstantPitch(LocalPlayer player)
	{
		if(lookingUp)
		{
			if(tickDelay < 30)
			{
				pitchToAdjust = -40;
				player.setXRot(pitchToAdjust);
			}else
			{
				lookingUp = false;
				constantPitch = false;
				tickDelay = 0;
			}
		}else if(tickDelay < 100)
		{
			pitchToAdjust = 40;
			player.setXRot(pitchToAdjust);
		}else
		{
			lookingUp = true;
			constantPitch = false;
			tickDelay = 0;
		}
		
		tickDelay++;
	}
	
	private void adjustPitch(LocalPlayer player)
	{
		pitchToAdjust += lookingUp ? -2 : 2;
		player.setXRot(pitchToAdjust);
		if(pitchToAdjust <= -40 || pitchToAdjust >= 40)
		{
			pitchToAdjust = Math.clamp(pitchToAdjust, -40, 40);
			constantPitch = true;
		}
	}
	
	private void resetFlightState()
	{
		started = false;
		constantPitch = true;
		lookingUp = false;
		lockedYaw = Float.NaN;
		pitchToAdjust = 0;
		tickDelay = 0;
	}
}
