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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.PacketUtils;

@SearchTags({"air walk", "airwalker"})
public final class AirWalkHack extends Hack
	implements UpdateListener, PacketOutputListener
{
	private double hoverY = Double.NaN;
	private boolean jumpWasDown;
	
	public AirWalkHack()
	{
		super("AirWalk");
		setCategory(Category.MOVEMENT);
	}
	
	@Override
	protected void onEnable()
	{
		hoverY = Double.NaN;
		jumpWasDown = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		hoverY = Double.NaN;
		jumpWasDown = false;
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
			return;
		
		if(isFlyingHackEnabled())
		{
			setEnabled(false);
			return;
		}
		
		if(player.onGround() || player.isInWater() || player.isInLava()
			|| player.onClimbable() || player.isPassenger())
		{
			hoverY = Double.NaN;
			return;
		}
		
		if(Double.isNaN(hoverY))
			hoverY = player.getY();
		
		boolean jumpDown = MC.options.keyJump.isDown();
		if(jumpDown && !jumpWasDown)
		{
			double targetY = hoverY + 1.0;
			AABB box =
				player.getBoundingBox().move(0, targetY - player.getY(), 0);
			
			if(MC.level.noCollision(player, box))
				hoverY = targetY;
		}
		jumpWasDown = jumpDown;
		
		Vec3 v = player.getDeltaMovement();
		player.setDeltaMovement(v.x, 0, v.z);
		player.setOnGround(true);
		player.fallDistance = 0;
		
		if(Math.abs(player.getY() - hoverY) > 1e-4)
			player.setPos(player.getX(), hoverY, player.getZ());
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!(event.getPacket() instanceof ServerboundMovePlayerPacket packet))
			return;
		
		event.setPacket(PacketUtils.modifyOnGround(packet, true));
	}
	
	private boolean isFlyingHackEnabled()
	{
		return WURST.getHax().flightHack.isEnabled()
			|| WURST.getHax().creativeFlightHack.isEnabled()
			|| WURST.getHax().elytraFlightHack.isEnabled()
			|| WURST.getHax().jetpackHack.isEnabled();
	}
}
