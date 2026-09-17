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
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.BlockBreakingProgressListener;
import net.wurstclient.events.BlockBreakingProgressListener.BlockBreakingProgressEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.PacketUtils;
import net.wurstclient.util.ChatUtils;

@SearchTags({"air miner", "airborne mining", "airborne mining"})
public final class AirMinerHack extends Hack implements UpdateListener,
	PacketOutputListener, BlockBreakingProgressListener
{
	private final CheckboxSetting whileGliding =
		new CheckboxSetting("While gliding",
			"description.wurst.setting.airminer.while_gliding", false);
	private final SliderSetting maxSpeed = new SliderSetting("Max speed",
		"description.wurst.setting.airminer.max_speed", 1, 0, 3, .05,
		ValueDisplay.DECIMAL);
	private final SliderSetting claimWindow = new SliderSetting("Claim window",
		"description.wurst.setting.airminer.claim_window", 2, 1, 10, 1,
		ValueDisplay.INTEGER);
	private final CheckboxSetting chatInfo = new CheckboxSetting("Chat info",
		"description.wurst.setting.airminer.chat_info", false);
	private int claimTicks;
	private int claims;
	private boolean lying;
	private boolean injecting;
	
	public AirMinerHack()
	{
		super("AirMiner");
		setCategory(Category.OTHER);
		addSetting(whileGliding);
		addSetting(maxSpeed);
		addSetting(claimWindow);
		addSetting(chatInfo);
	}
	
	@Override
	protected void onEnable()
	{
		claimTicks = 0;
		claims = 0;
		lying = false;
		injecting = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(BlockBreakingProgressListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(BlockBreakingProgressListener.class, this);
		if(lying)
			restore();
		claimTicks = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(claimTicks > 0 && --claimTicks == 0 && lying)
			restore();
	}
	
	@Override
	public void onBlockBreakingProgress(BlockBreakingProgressEvent event)
	{
		if(canClaim())
			claim();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!injecting
			&& event.getPacket() instanceof ServerboundPlayerActionPacket action
			&& (action
				.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
				|| action
					.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)
			&& canClaim())
			claim();
		if(injecting || claimTicks <= 0
			|| !(event
				.getPacket() instanceof ServerboundMovePlayerPacket packet)
			|| !packet.hasPosition())
			return;
		event.setPacket(PacketUtils.modifyOnGround(packet, true));
	}
	
	private boolean canClaim()
	{
		LocalPlayer player = MC.player;
		if(player == null || MC.getConnection() == null || player.onGround()
			|| player.isCreative() || player.isSpectator())
			return false;
		if(player.isFallFlying() && !whileGliding.isChecked())
			return false;
		return player.getDeltaMovement().horizontalDistance() <= maxSpeed
			.getValue();
	}
	
	private void claim()
	{
		LocalPlayer player = MC.player;
		if(player == null || MC.getConnection() == null)
			return;
		injecting = true;
		try
		{
			MC.getConnection()
				.send(new ServerboundMovePlayerPacket.PosRot(player.getX(),
					player.getY(), player.getZ(), player.getYRot(),
					player.getXRot(), true, player.horizontalCollision));
		}finally
		{
			injecting = false;
		}
		claimTicks = claimWindow.getValueI();
		lying = true;
		claims++;
		if(chatInfo.isChecked())
			ChatUtils.message("AirMiner ground claim #" + claims + " active.");
	}
	
	private void restore()
	{
		lying = false;
		LocalPlayer player = MC.player;
		if(player == null || MC.getConnection() == null)
			return;
		injecting = true;
		try
		{
			MC.getConnection()
				.send(new ServerboundMovePlayerPacket.PosRot(player.getX(),
					player.getY(), player.getZ(), player.getYRot(),
					player.getXRot(), player.onGround(),
					player.horizontalCollision));
		}finally
		{
			injecting = false;
		}
	}
	
	public void claimForMining()
	{
		if(canClaim())
			claim();
	}
	
	public boolean shouldBoostMining()
	{
		return isEnabled() && canClaim();
	}
}
