/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"elytra flight", "elytra fly", "elytrafly"})
public final class ElytraFlightHack extends Hack implements UpdateListener
{
	private final SliderSetting horizontalSpeed = new SliderSetting(
		"Horizontal speed", 1, 0, 5, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting verticalSpeed = new SliderSetting(
		"Vertical speed", 0.5, 0, 5, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoStart = new CheckboxSetting("Auto start",
		"Automatically starts flying when you press jump.", true);
	
	private final CheckboxSetting vanillaMomentum = new CheckboxSetting(
		"Vanilla momentum",
		"Preserves vanilla glide momentum instead of overriding it.", false);
	
	private int jumpTimer;
	
	public ElytraFlightHack()
	{
		super("ElytraFlight");
		setCategory(Category.MOVEMENT);
		addSetting(horizontalSpeed);
		addSetting(verticalSpeed);
		addSetting(autoStart);
		addSetting(vanillaMomentum);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		jumpTimer = 0;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		if(jumpTimer > 0)
			jumpTimer--;
		
		if(!player.canGlide())
			return;
		
		if(!player.isFallFlying())
		{
			if(autoStart.isChecked() && MC.options.keyJump.isDown())
				doAutoStart(player);
			return;
		}
		
		Vec3 velocity = player.getDeltaMovement();
		double motionY = vanillaMomentum.isChecked() ? velocity.y : 0;
		if(MC.options.keyJump.isDown())
			motionY = verticalSpeed.getValue();
		else if(MC.options.keyShift.isDown())
			motionY = -verticalSpeed.getValue();
		
		Vec2 input = player.input.getMoveVector();
		double inputX = input.x;
		double inputZ = input.y;
		double len = Math.sqrt(inputX * inputX + inputZ * inputZ);
		if(len > 1)
		{
			inputX /= len;
			inputZ /= len;
		}
		
		double speed = horizontalSpeed.getValue();
		float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
		double sin = Mth.sin(yawRad);
		double cos = Mth.cos(yawRad);
		
		double motionX = vanillaMomentum.isChecked() ? velocity.x : 0;
		double motionZ = vanillaMomentum.isChecked() ? velocity.z : 0;
		if(len > 1e-5)
		{
			double addX = (inputX * cos - inputZ * sin) * speed;
			double addZ = (inputZ * cos + inputX * sin) * speed;
			if(vanillaMomentum.isChecked())
			{
				motionX += addX;
				motionZ += addZ;
			}else
			{
				motionX = addX;
				motionZ = addZ;
			}
		}
		
		player.setDeltaMovement(new Vec3(motionX, motionY, motionZ));
		player.fallDistance = 0;
	}
	
	private void doAutoStart(LocalPlayer player)
	{
		if(jumpTimer <= 0 && player.onGround())
		{
			jumpTimer = 20;
			player.setJumping(false);
			player.setSprinting(true);
			player.jumpFromGround();
		}
		
		sendStartPacket(player);
	}
	
	private void sendStartPacket(LocalPlayer player)
	{
		ServerboundPlayerCommandPacket packet =
			new ServerboundPlayerCommandPacket(player,
				ServerboundPlayerCommandPacket.Action.START_FALL_FLYING);
		player.connection.send(packet);
	}
}
