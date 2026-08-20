/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.events;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.event.CancellableEvent;
import net.wurstclient.event.Listener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.util.HackActivityTracker;

/**
 * Similar to {@link PacketOutputListener}, but also captures packets that are
 * sent before the client has finished connecting to the server. Most hacks
 * should use {@link PacketOutputListener} instead.
 */
public interface ConnectionPacketOutputListener extends Listener
{
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event);
	
	/**
	 * Similar to {@link PacketOutputEvent}, but also captures packets that are
	 * sent before the client has finished connecting to the server. Most hacks
	 * should use {@link PacketOutputEvent} instead.
	 */
	public static class ConnectionPacketOutputEvent
		extends CancellableEvent<ConnectionPacketOutputListener>
	{
		private Packet<?> packet;
		private final List<String> debugTrace = new ArrayList<>();
		
		public ConnectionPacketOutputEvent(Packet<?> packet)
		{
			this.packet = packet;
		}
		
		public Packet<?> getPacket()
		{
			return packet;
		}
		
		public void setPacket(Packet<?> packet)
		{
			this.packet = packet;
		}
		
		public void addDebugSource(String source)
		{
			if(source != null && !source.isBlank())
				debugTrace.add(source);
		}
		
		public List<String> getDebugTrace()
		{
			return List.copyOf(debugTrace);
		}
		
		@Override
		public void fire(ArrayList<ConnectionPacketOutputListener> listeners)
		{
			for(ConnectionPacketOutputListener listener : listeners)
			{
				HackActivityTracker.markActive(listener);
				if(listener instanceof net.wurstclient.hack.Hack hack)
					debugTrace.add(hack.getName());
				listener.onSentConnectionPacket(this);
				
				if(isCancelled())
					break;
			}
		}
		
		@Override
		public Class<ConnectionPacketOutputListener> getListenerType()
		{
			return ConnectionPacketOutputListener.class;
		}
	}
}
