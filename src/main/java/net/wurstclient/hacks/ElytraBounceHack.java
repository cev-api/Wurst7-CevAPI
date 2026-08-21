/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"elytra bounce", "elytrabounce", "elytra highway"})
public final class ElytraBounceHack extends Hack
	implements UpdateListener, PacketInputListener
{
	private final CheckboxSetting instantStart = new CheckboxSetting(
		"Auto start", "Automatically starts gliding while falling.", true);
	private final SliderSetting pitch = new SliderSetting("Pitch",
		"Pitch to use while bouncing with an elytra.", 85, 0, 90, 0.5,
		ValueDisplay.DECIMAL);
	private final SliderSetting timeout = new SliderSetting("Timeout",
		"Minimum delay between automatic glide-start attempts.", 0.5, 0.1, 1,
		0.1, ValueDisplay.DECIMAL.withSuffix("s"));
	private final CheckboxSetting autoJump = new CheckboxSetting("Auto jump",
		"Automatically performs the double-jump bounce sequence.", true);
	private final SliderSetting firstJumpTicks =
		new SliderSetting("First jump ticks",
			"How many ticks the first jump input is held during a bounce.", 1,
			1, 20, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final SliderSetting betweenJumpsTicks =
		new SliderSetting("Jump gap ticks",
			"How many ticks Jump is released between the two bounce inputs.", 1,
			1, 20, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final SliderSetting secondJumpTicks =
		new SliderSetting("Second jump ticks",
			"How many ticks the second jump input is held during a bounce.", 1,
			1, 20, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final SliderSetting correctionDelay =
		new SliderSetting("Correction delay",
			"How long to wait after a server position correction before trying"
				+ " to bounce or glide again.",
			7, 0, 20, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final CheckboxSetting sprint = new CheckboxSetting("Sprint",
		"Sprints while travelling with an elytra.", true);
	private final CheckboxSetting autoRun = new CheckboxSetting("Auto run",
		"Automatically holds forward while travelling with an elytra.", true);
	private final CheckboxSetting chunkStop = new CheckboxSetting("Chunk stop",
		"Stops movement before flying into an unloaded chunk.", true);
	private final CheckboxSetting speedCap = new CheckboxSetting("Speed cap",
		"Stops forward movement when the configured speed is exceeded.", false);
	private final SliderSetting maxSpeed = new SliderSetting("Max speed",
		"Maximum speed before Auto run applies the brake.", 110, 0, 350, 5,
		ValueDisplay.INTEGER.withSuffix(" km/h"));
	
	private int startCooldown;
	
	private int correctionCooldown;
	private int bounceTicks;
	private BounceState bounceState = BounceState.NONE;
	private boolean wasFallFlying;
	
	private boolean pressedJump;
	private boolean pressedForward;
	private boolean pressedBack;
	
	public ElytraBounceHack()
	{
		super("ElytraBounce");
		setCategory(Category.MOVEMENT);
		addSetting(pitch);
		addSetting(timeout);
		addSetting(autoJump);
		
		addSetting(firstJumpTicks);
		addSetting(betweenJumpsTicks);
		addSetting(secondJumpTicks);
		
		addSetting(sprint);
		addSetting(autoRun);
		addSetting(instantStart);
		addSetting(speedCap);
		addSetting(maxSpeed);
		addSetting(chunkStop);
		
		addSetting(correctionDelay);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		
		EVENTS.add(PacketInputListener.class, this);
		
		startCooldown = 0;
		
		correctionCooldown = 0;
		wasFallFlying = false;
		bounceState = BounceState.NONE;
		bounceTicks = 0;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		EVENTS.remove(PacketInputListener.class, this);
		
		resetBounce();
		
		releaseControlledKeys();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		boolean hasElytra =
			player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
		if(!hasElytra)
		{
			wasFallFlying = false;
			resetBounce();
			
			releaseControlledKeys();
			return;
		}
		
		if(startCooldown > 0)
			startCooldown--;
		
		if(correctionCooldown > 0)
			correctionCooldown--;
		
		if(chunkStop.isChecked() && player.isFallFlying()
			&& !isChunkAheadLoaded(player))
		{
			releaseRunKeys();
			return;
		}
		
		if(autoJump.isChecked() && correctionCooldown == 0)
		{
			if(bounceState == BounceState.NONE && player.onGround())
				startBounce();
			
			updateBounce(player);
		}else
			resetBounce();
		
		if(sprint.isChecked())
			player.setSprinting(true);
		
		controlAutoRun(player);
		
		if(!player.isFallFlying())
		{
			tryStartGliding(player);
			
			// ### ADDED ###
			wasFallFlying = false;
			return;
		}
		
		player.setXRot((float)pitch.getValue());
		
		wasFallFlying = true;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!(event.getPacket() instanceof ClientboundPlayerPositionPacket))
			return;
		
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		correctionCooldown = correctionDelay.getValueI();
		startCooldown = correctionCooldown;
		
		resetBounce();
		
		if(player.isFallFlying())
			player.stopFallFlying();
	}
	
	private void startBounce()
	{
		bounceState = BounceState.FIRST_JUMP;
		bounceTicks = firstJumpTicks.getValueI();
	}
	
	private void updateBounce(LocalPlayer player)
	{
		switch(bounceState)
		{
			case NONE:
			setJumpDown(false);
			break;
			
			case FIRST_JUMP:
			setJumpDown(true);
			
			player.input.makeJump();
			
			bounceTicks--;
			if(bounceTicks <= 0)
			{
				bounceState = BounceState.JUMP_DELAY;
				bounceTicks = betweenJumpsTicks.getValueI();
			}
			break;
			
			case JUMP_DELAY:
			setJumpDown(false);
			
			bounceTicks--;
			if(bounceTicks <= 0)
			{
				bounceState = BounceState.SECOND_JUMP;
				bounceTicks = secondJumpTicks.getValueI();
			}
			break;
			
			case SECOND_JUMP:
			setJumpDown(true);
			
			player.input.makeJump();
			
			bounceTicks--;
			if(bounceTicks <= 0)
				bounceState = BounceState.NONE;
			break;
		}
	}
	
	private void tryStartGliding(LocalPlayer player)
	{
		if(!instantStart.isChecked() || player.onGround()
			|| player.getDeltaMovement().y >= 0 || startCooldown > 0
			|| correctionCooldown > 0 || bounceState != BounceState.NONE)
			return;
		
		startCooldown = (int)Math.ceil(timeout.getValue() * 20);
		player.connection.send(new ServerboundPlayerCommandPacket(player,
			ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
	}
	
	private void controlAutoRun(LocalPlayer player)
	{
		
		if(!autoRun.isChecked())
		{
			releaseRunKeys();
			return;
		}
		
		double speed = player.getDeltaMovement().length() * 72;
		boolean brake = speedCap.isChecked() && speed > maxSpeed.getValue();
		MC.options.keyUp.setDown(!brake);
		MC.options.keyDown.setDown(brake);
		pressedForward = !brake;
		pressedBack = brake;
	}
	
	private boolean isChunkAheadLoaded(LocalPlayer player)
	{
		double lookAheadTicks = 10;
		
		double targetX =
			player.getX() + player.getDeltaMovement().x * lookAheadTicks;
		double targetZ =
			player.getZ() + player.getDeltaMovement().z * lookAheadTicks;
		
		int chunkX = (int)Math.floor(targetX / 16.0);
		int chunkZ = (int)Math.floor(targetZ / 16.0);
		
		return MC.level.hasChunk(chunkX, chunkZ);
	}
	
	private void setJumpDown(boolean down)
	{
		MC.options.keyJump.setDown(down);
		pressedJump = down;
	}
	
	private void resetBounce()
	{
		bounceState = BounceState.NONE;
		bounceTicks = 0;
		
		if(pressedJump)
		{
			MC.options.keyJump.setDown(false);
			pressedJump = false;
		}
	}
	
	private void releaseRunKeys()
	{
		if(pressedForward)
			MC.options.keyUp.setDown(false);
		if(pressedBack)
			MC.options.keyDown.setDown(false);
		
		pressedForward = false;
		pressedBack = false;
	}
	
	private void releaseControlledKeys()
	{
		if(pressedJump)
			MC.options.keyJump.setDown(false);
		
		releaseRunKeys();
		
		pressedJump = false;
	}
	
	private enum BounceState
	{
		NONE,
		FIRST_JUMP,
		JUMP_DELAY,
		SECOND_JUMP
	}
}
