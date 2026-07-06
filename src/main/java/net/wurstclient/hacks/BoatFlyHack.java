/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;

@SearchTags({"boat fly", "BoatFlight", "boat flight", "EntitySpeed",
	"entity speed"})
public final class BoatFlyHack extends Hack implements UpdateListener
{
	private final CheckboxSetting changeForwardSpeed = new CheckboxSetting(
		"Change Forward Speed",
		"Allows \u00a7eForward Speed\u00a7r to be changed, disables smooth acceleration.",
		false);
	
	private final SliderSetting forwardSpeed = new SliderSetting(
		"Forward Speed", 1, 0.05, 15, 0.05, SliderSetting.ValueDisplay.DECIMAL);
	
	private final SliderSetting upwardSpeed = new SliderSetting("Upward Speed",
		0.3, 0, 5, 0.05, SliderSetting.ValueDisplay.DECIMAL);
	
	private final SliderSetting downwardSpeed = new SliderSetting(
		"Downward Speed", 0.3, 0, 5, 0.05, SliderSetting.ValueDisplay.DECIMAL);
	
	private final CheckboxSetting usePlayerRotation = new CheckboxSetting(
		"Use Player Rotation",
		"Moves the boat based on where you are looking instead of where the boat is facing.",
		true);
	
	private final CheckboxSetting allowStrafing = new CheckboxSetting(
		"Allow Strafing",
		"Allows left, right, and backwards movement instead of forward-only movement.",
		true);
	
	private final CheckboxSetting stopDrift = new CheckboxSetting("Stop Drift",
		"Stops horizontal boat movement when no movement keys are pressed.",
		true);
	
	public BoatFlyHack()
	{
		super("BoatFly");
		setCategory(Category.MOVEMENT);
		addSetting(changeForwardSpeed);
		addSetting(forwardSpeed);
		addSetting(upwardSpeed);
		
		addSetting(downwardSpeed);
		addSetting(usePlayerRotation);
		addSetting(allowStrafing);
		addSetting(stopDrift);
	}
	
	@Override
	protected void onEnable()
	{
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
		// check if riding
		if(!MC.player.isPassenger())
			return;
		
		Entity vehicle = MC.player.getVehicle();
		
		if(vehicle == null)
			return;
		
		Vec3 velocity = vehicle.getDeltaMovement();
		
		// default motion
		double motionX = velocity.x;
		double motionY = 0;
		double motionZ = velocity.z;
		
		// up/down
		if(MC.options.keyJump.isDown())
			motionY = upwardSpeed.getValue();
		else if(MC.options.keyShift.isDown())
			motionY = -downwardSpeed.getValue();
		
		// horizontal movement
		if(changeForwardSpeed.isChecked())
		{
			double forward = 0;
			double strafe = 0;
			
			if(MC.options.keyUp.isDown())
				forward++;
			
			if(allowStrafing.isChecked())
			{
				if(MC.options.keyDown.isDown())
					forward--;
				
				if(MC.options.keyLeft.isDown())
					strafe++;
				
				if(MC.options.keyRight.isDown())
					strafe--;
			}
			
			if(forward != 0 || strafe != 0)
			{
				double speed = forwardSpeed.getValue();
				
				float yaw = usePlayerRotation.isChecked() ? MC.player.getYRot()
					: vehicle.getYRot();
				float yawRad = yaw * Mth.DEG_TO_RAD;
				
				double forwardX = Mth.sin(-yawRad);
				double forwardZ = Mth.cos(yawRad);
				double strafeX = Mth.cos(yawRad);
				double strafeZ = Mth.sin(yawRad);
				
				double moveX = forwardX * forward + strafeX * strafe;
				double moveZ = forwardZ * forward + strafeZ * strafe;
				
				double length = Math.sqrt(moveX * moveX + moveZ * moveZ);
				
				if(length > 0)
				{
					moveX /= length;
					moveZ /= length;
				}
				
				motionX = moveX * speed;
				motionZ = moveZ * speed;
			}else if(stopDrift.isChecked())
			{
				motionX = 0;
				motionZ = 0;
			}
		}
		
		// apply motion
		vehicle.setDeltaMovement(motionX, motionY, motionZ);
	}
}
