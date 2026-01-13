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
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"PacketRate", "RateLimit", "packets per second", "pps"})
public final class PacketRateHack extends Hack
	implements PacketOutputListener, UpdateListener
{
	private final CheckboxSetting limiterEnabled =
		new CheckboxSetting("Enable limiter",
			"Turn off to only monitor packet rate without limiting it.", true);
	
	private final SliderSetting limit = new SliderSetting("Limit",
		"Max outgoing packets per second.\n0 = unlimited", 100, 0, 1000, 1,
		ValueDisplay.INTEGER);
	
	private final ArrayDeque<Packet<?>> queue = new ArrayDeque<>();
	private final ArrayDeque<Long> sentTimes = new ArrayDeque<>();
	private boolean flushing;
	private double tokens;
	private long lastRefillMs;
	
	public PacketRateHack()
	{
		super("PacketRate");
		setCategory(Category.OTHER);
		addSetting(limiterEnabled);
		addSetting(limit);
	}
	
	@Override
	public String getRenderName()
	{
		long now = System.currentTimeMillis();
		pruneSentTimes(now);
		int rate = sentTimes.size();
		
		if(!limiterEnabled.isChecked())
			return getName() + " [" + rate + "/s]";
		
		if(limit.getValueI() <= 0)
			return getName() + " [" + rate + "/s]";
		
		return getName() + " [" + rate + "/s | lim " + limit.getValueI() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		sentTimes.clear();
		tokens = 0;
		lastRefillMs = System.currentTimeMillis();
		
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		
		flushAll();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(flushing)
			return;
		
		Packet<?> packet = event.getPacket();
		
		if(!shouldLimit())
		{
			recordSent(System.currentTimeMillis());
			return;
		}
		
		if(isKeepAlive(packet))
		{
			recordSent(System.currentTimeMillis());
			return;
		}
		
		if(limit.getValueI() <= 0)
		{
			recordSent(System.currentTimeMillis());
			return;
		}
		
		refillTokens();
		if(!queue.isEmpty())
		{
			queue.addLast(packet);
			event.cancel();
			return;
		}
		
		if(tokens >= 1)
		{
			tokens -= 1;
			recordSent(System.currentTimeMillis());
			return;
		}
		
		queue.addLast(packet);
		event.cancel();
	}
	
	@Override
	public void onUpdate()
	{
		if(!shouldLimit())
		{
			queue.clear();
			tokens = 0;
			lastRefillMs = System.currentTimeMillis();
			pruneSentTimes(lastRefillMs);
			return;
		}
		
		if(limit.getValueI() <= 0)
		{
			flushAll();
			pruneSentTimes(System.currentTimeMillis());
			return;
		}
		
		refillTokens();
		sendQueuedPackets();
		pruneSentTimes(System.currentTimeMillis());
	}
	
	private void sendQueuedPackets()
	{
		if(queue.isEmpty())
			return;
		
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			queue.clear();
			return;
		}
		
		flushing = true;
		try
		{
			while(!queue.isEmpty())
			{
				refillTokens();
				if(tokens < 1)
					break;
				
				Packet<?> packet = queue.removeFirst();
				tokens -= 1;
				connection.send(packet);
				recordSent(System.currentTimeMillis());
			}
			
		}finally
		{
			flushing = false;
		}
	}
	
	private void flushAll()
	{
		if(queue.isEmpty())
			return;
		
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			queue.clear();
			return;
		}
		
		flushing = true;
		try
		{
			while(!queue.isEmpty())
			{
				connection.send(queue.removeFirst());
				recordSent(System.currentTimeMillis());
			}
			
		}finally
		{
			flushing = false;
		}
	}
	
	private void recordSent(long now)
	{
		sentTimes.addLast(now);
		pruneSentTimes(now);
	}
	
	private void pruneSentTimes(long now)
	{
		long cutoff = now - 1000;
		while(!sentTimes.isEmpty() && sentTimes.peekFirst() <= cutoff)
			sentTimes.removeFirst();
	}
	
	private void refillTokens()
	{
		int limitValue = limit.getValueI();
		if(limitValue <= 0)
		{
			lastRefillMs = System.currentTimeMillis();
			return;
		}
		
		long now = System.currentTimeMillis();
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
