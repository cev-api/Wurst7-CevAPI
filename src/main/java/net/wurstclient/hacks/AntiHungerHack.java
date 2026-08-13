/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.PacketUtils;

@DontSaveState
@SearchTags({"anti hunger"})
public final class AntiHungerHack extends Hack implements PacketOutputListener
{
	public AntiHungerHack()
	{
		super("AntiHunger");
		setCategory(Category.MOVEMENT);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().noFallHack.setEnabled(false);
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!(event.getPacket() instanceof ServerboundMovePlayerPacket packet))
			return;
			
		// NoFall runs before output listeners. During AutoFly, a descending
		// packet may deliberately be grounded to stop server fall damage; do
		// not overwrite that final protection with AntiHunger's airborne flag.
		if(packet.isOnGround() && WURST.getHax().autoFlyHack.isEnabled()
			&& WURST.getHax().noFallHack.isEnabled())
			return;
		
		if(!MC.player.onGround() || MC.player.fallDistance > 0.5)
			return;
		
		if(MC.gameMode.isDestroying())
			return;
		
		event.setPacket(PacketUtils.modifyOnGround(packet, false));
	}
}
