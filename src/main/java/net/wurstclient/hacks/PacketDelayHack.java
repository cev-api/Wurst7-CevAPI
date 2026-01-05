/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"LagSwitch", "lag switch"})
public final class PacketDelayHack extends Hack
	implements UpdateListener, PacketInputListener, PacketOutputListener
{
	private final CheckboxSetting delayS2c = new CheckboxSetting("Delay S2C",
		"Delays incoming server packets.", true);
	private final CheckboxSetting delayC2s = new CheckboxSetting("Delay C2S",
		"Delays outgoing client packets.", true);
	private final CheckboxSetting timeDelay = new CheckboxSetting("Time delay",
		"Release queued packets once the delay has passed.", false);
	private final SliderSetting delay = new SliderSetting("Delay",
		"How long to wait in ticks before releasing queued packets.", 0, 0, 200,
		1, ValueDisplay.INTEGER);
	
	private final ArrayDeque<PacketAndTime> s2cQueue = new ArrayDeque<>();
	private final ArrayDeque<PacketAndTime> c2sQueue = new ArrayDeque<>();
	private boolean flushingC2s;
	
	public PacketDelayHack()
	{
		super("PacketDelay");
		setCategory(Category.OTHER);
		addSetting(delayS2c);
		addSetting(delayC2s);
		addSetting(timeDelay);
		addSetting(delay);
	}
	
	@Override
	public String getRenderName()
	{
		int total = s2cQueue.size() + c2sQueue.size();
		return getName() + " [" + total + "]";
	}
	
	@Override
	protected void onEnable()
	{
		s2cQueue.clear();
		c2sQueue.clear();
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		
		flushQueues(true);
	}
	
	@Override
	public void onUpdate()
	{
		if(timeDelay.isChecked())
			flushQueues(false);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!delayS2c.isChecked())
			return;
		
		long time = MC.level != null ? MC.level.getGameTime() : 0;
		s2cQueue.addLast(new PacketAndTime(event.getPacket(), time));
		event.cancel();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(flushingC2s || !delayC2s.isChecked())
			return;
		
		long time = MC.level != null ? MC.level.getGameTime() : 0;
		c2sQueue.addLast(new PacketAndTime(event.getPacket(), time));
		event.cancel();
	}
	
	private void flushQueues(boolean forceAll)
	{
		if(forceAll || delay.getValueI() <= 0)
			forceAll = true;
		
		long now = MC.level != null ? MC.level.getGameTime() : 0;
		
		if(forceAll || shouldReleaseQueue(s2cQueue, now, delay.getValueI()))
		{
			while(!s2cQueue.isEmpty())
				applyPacket(s2cQueue.removeFirst().packet);
		}
		
		if(forceAll || shouldReleaseQueue(c2sQueue, now, delay.getValueI()))
		{
			sendQueuedPackets();
		}
	}
	
	private boolean shouldReleaseQueue(ArrayDeque<PacketAndTime> queue,
		long now, int delayTicks)
	{
		if(queue.isEmpty())
			return false;
		
		return queue.peekFirst().time <= now - delayTicks;
	}
	
	private void sendQueuedPackets()
	{
		if(c2sQueue.isEmpty())
			return;
		
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			c2sQueue.clear();
			return;
		}
		
		flushingC2s = true;
		try
		{
			while(!c2sQueue.isEmpty())
				connection.send(c2sQueue.removeFirst().packet);
			
		}finally
		{
			flushingC2s = false;
		}
	}
	
	private void applyPacket(Packet<?> packet)
	{
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
			return;
		
		@SuppressWarnings("unchecked")
		Packet<ClientPacketListener> typedPacket =
			(Packet<ClientPacketListener>)packet;
		typedPacket.handle(connection);
	}
	
	private static class PacketAndTime
	{
		private final Packet<?> packet;
		private final long time;
		
		private PacketAndTime(Packet<?> packet, long time)
		{
			this.packet = packet;
			this.time = time;
		}
	}
}
