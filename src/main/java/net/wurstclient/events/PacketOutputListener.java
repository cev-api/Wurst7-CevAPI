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
import net.wurstclient.util.HackActivityTracker;

public interface PacketOutputListener extends Listener
{
	public void onSentPacket(PacketOutputEvent event);
	
	public static class PacketOutputEvent
		extends CancellableEvent<PacketOutputListener>
	{
		private Packet<?> packet;
		private final List<String> debugTrace = new ArrayList<>();
		
		public PacketOutputEvent(Packet<?> packet)
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
		public void fire(ArrayList<PacketOutputListener> listeners)
		{
			for(PacketOutputListener listener : listeners)
			{
				HackActivityTracker.markActive(listener);
				if(listener instanceof net.wurstclient.hack.Hack hack)
					debugTrace.add(hack.getName());
				listener.onSentPacket(this);
				
				if(isCancelled())
					break;
			}
		}
		
		@Override
		public Class<PacketOutputListener> getListenerType()
		{
			return PacketOutputListener.class;
		}
	}
}
