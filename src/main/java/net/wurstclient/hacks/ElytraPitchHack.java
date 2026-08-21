/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.CameraType;
import net.minecraft.client.player.LocalPlayer;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"elytra pitch", "pitch40", "elytra fly", "efly"})
public final class ElytraPitchHack extends Hack implements UpdateListener
{
	private final CheckboxSetting yawLock = new CheckboxSetting("Yaw lock",
		"Locks your yaw to the nearest 45-degree direction while gliding.",
		false);
	
	private final CheckboxSetting thirdPerson = new CheckboxSetting(
		"Third person", "Switches to third-person view while gliding.", false);
	
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
		addSetting(thirdPerson);
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
		if(player == null || !player.isFallFlying() || player.isInLiquid())
		{
			resetFlightState();
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
