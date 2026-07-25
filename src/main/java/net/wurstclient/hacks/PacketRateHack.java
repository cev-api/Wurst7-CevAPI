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
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.ConnectionPacketOutputListener.ConnectionPacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"PacketRate", "RateLimit", "packets per second", "pps"})
public final class PacketRateHack extends Hack
	implements ConnectionPacketOutputListener, UpdateListener
{
	/*
	 * This deliberately uses the connection-level event instead of also
	 * listening to PacketOutputListener. ClientPacketListener.send() normally
	 * reaches Connection.send(), so registering at both levels would process
	 * the same packet twice. The connection-level event also catches direct
	 * Connection.send() calls.
	 */
	private final CheckboxSetting limiterEnabled =
		new CheckboxSetting("Enable limiter",
			"Turn off to only monitor packet rate without limiting it.", true);
	private final CheckboxSetting dropExcess = new CheckboxSetting(
		"Drop excess packets",
		"Drop packets that exceed the limit instead of queueing them.", false);
	
	private final SliderSetting limit = new SliderSetting("Limit",
		"Max outgoing packets per second.\n0 = unlimited", 100, 0, 1000, 1,
		ValueDisplay.INTEGER);
	
	private final ArrayDeque<Packet<?>> queue = new ArrayDeque<>();
	private final Object queueLock = new Object();
	private final ArrayDeque<Long> sentTimes = new ArrayDeque<>();
	private final Object sentTimesLock = new Object();
	private final ThreadLocal<Boolean> bypassLimiter =
		ThreadLocal.withInitial(() -> false);
	private double tokens;
	private long lastRefillMs;
	
	public PacketRateHack()
	{
		super("PacketRate");
		setCategory(Category.TOOLS);
		addSetting(limiterEnabled);
		addSetting(dropExcess);
		addSetting(limit);
	}
	
	@Override
	public String getRenderName()
	{
		long now = System.currentTimeMillis();
		pruneSentTimes(now);
		int rate;
		synchronized(sentTimesLock)
		{
			rate = sentTimes.size();
		}
		
		if(!limiterEnabled.isChecked())
			return getName() + " [" + rate + "/s]";
		
		if(limit.getValueI() <= 0)
			return getName() + " [" + rate + "/s]";
		
		String mode = dropExcess.isChecked() ? "drop" : "queue";
		return getName() + " [" + rate + "/s | lim " + limit.getValueI() + " | "
			+ mode + "]";
	}
	
	@Override
	protected void onEnable()
	{
		synchronized(queueLock)
		{
			queue.clear();
			tokens = 0;
			lastRefillMs = System.currentTimeMillis();
		}
		synchronized(sentTimesLock)
		{
			sentTimes.clear();
		}
		
		EVENTS.add(ConnectionPacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ConnectionPacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		
		flushAll();
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		if(bypassLimiter.get())
			return;
		
		Packet<?> packet = event.getPacket();
		long now = System.currentTimeMillis();
		
		if(!shouldLimit())
		{
			recordSent(now);
			return;
		}
		
		if(isKeepAlive(packet))
		{
			recordSent(now);
			return;
		}
		
		int limitValue = limit.getValueI();
		if(limitValue <= 0)
		{
			recordSent(now);
			return;
		}
		
		boolean allowed = false;
		synchronized(queueLock)
		{
			refillTokensLocked(now, limitValue);
			
			if(dropExcess.isChecked())
			{
				// If the setting was changed while packets were queued, do not
				// release the old queue in drop mode.
				queue.clear();
				if(tokens >= 1)
				{
					tokens -= 1;
					allowed = true;
				}
			}else if(!queue.isEmpty())
			{
				queue.addLast(packet);
			}else if(tokens >= 1)
			{
				tokens -= 1;
				allowed = true;
			}else
			{
				queue.addLast(packet);
			}
		}
		
		if(allowed)
		{
			recordSent(now);
			return;
		}
		
		event.cancel();
	}
	
	@Override
	public void onUpdate()
	{
		if(!shouldLimit())
		{
			synchronized(queueLock)
			{
				queue.clear();
				tokens = 0;
				lastRefillMs = System.currentTimeMillis();
			}
			pruneSentTimes(lastRefillMs);
			return;
		}
		
		if(limit.getValueI() <= 0)
		{
			flushAll();
			pruneSentTimes(System.currentTimeMillis());
			return;
		}
		
		int limitValue = limit.getValueI();
		synchronized(queueLock)
		{
			refillTokensLocked(System.currentTimeMillis(), limitValue);
			if(dropExcess.isChecked())
				queue.clear();
		}
		sendQueuedPackets();
		pruneSentTimes(System.currentTimeMillis());
	}
	
	private void sendQueuedPackets()
	{
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			synchronized(queueLock)
			{
				queue.clear();
			}
			return;
		}
		
		while(true)
		{
			Packet<?> packet;
			synchronized(queueLock)
			{
				if(queue.isEmpty())
					return;
				
				refillTokensLocked(System.currentTimeMillis(),
					limit.getValueI());
				if(tokens < 1)
					return;
				
				packet = queue.removeFirst();
				tokens -= 1;
			}
			
			sendBypassingLimiter(connection, packet);
			recordSent(System.currentTimeMillis());
		}
	}
	
	private void flushAll()
	{
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			synchronized(queueLock)
			{
				queue.clear();
			}
			return;
		}
		
		while(true)
		{
			Packet<?> packet;
			synchronized(queueLock)
			{
				if(queue.isEmpty())
					return;
				packet = queue.removeFirst();
			}
			
			sendBypassingLimiter(connection, packet);
			recordSent(System.currentTimeMillis());
		}
	}
	
	private void sendBypassingLimiter(ClientPacketListener connection,
		Packet<?> packet)
	{
		boolean wasBypassing = bypassLimiter.get();
		bypassLimiter.set(true);
		try
		{
			connection.send(packet);
		}finally
		{
			if(wasBypassing)
				bypassLimiter.set(true);
			else
				bypassLimiter.remove();
		}
	}
	
	private void recordSent(long now)
	{
		synchronized(sentTimesLock)
		{
			sentTimes.addLast(now);
			pruneSentTimesLocked(now);
		}
	}
	
	private void pruneSentTimes(long now)
	{
		synchronized(sentTimesLock)
		{
			pruneSentTimesLocked(now);
		}
	}
	
	private void pruneSentTimesLocked(long now)
	{
		long cutoff = now - 1000;
		while(!sentTimes.isEmpty())
		{
			Long first = sentTimes.peekFirst();
			if(first == null || first > cutoff)
				break;
			sentTimes.removeFirst();
		}
	}
	
	private void refillTokensLocked(long now, int limitValue)
	{
		if(limitValue <= 0)
		{
			lastRefillMs = now;
			return;
		}
		
		long elapsed = now - lastRefillMs;
		if(elapsed <= 0)
			return;
		
		double refill = (elapsed / 1000D) * limitValue;
		tokens = Math.min(limitValue, tokens + refill);
		lastRefillMs = now;
	}
	
	private boolean shouldLimit()
	{
		return limiterEnabled.isChecked() && MC.getConnection() != null;
	}
	
	private static boolean isKeepAlive(Packet<?> packet)
	{
		String name = packet.getClass().getSimpleName();
		return "ServerboundKeepAlivePacket".equals(name)
			|| "ClientboundKeepAlivePacket".equals(name);
	}
}
