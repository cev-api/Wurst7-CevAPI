/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.boatphase;

import java.util.ArrayDeque;
import java.util.Arrays;

public final class BoatPhaseMeter
{
	private static final int SIZE = 160;
	private final Sample[] history = new Sample[SIZE];
	private final ArrayDeque<Echo> echoes = new ArrayDeque<>();
	private int currentTick;
	private int echoReceiptTick = -100;
	private long echoReceiptNanos;
	private double echoBpt = Double.NaN, echoBps = Double.NaN;
	
	private record Echo(int tick, long nanos, double x, double z)
	{}
	
	private static final class Sample
	{
		final int tick;
		final long nanos;
		double distance, x, y, z;
		boolean endpoint;
		int packets;
		
		Sample(int tick, long nanos)
		{
			this.tick = tick;
			this.nanos = nanos;
		}
	}
	
	public void reset()
	{
		Arrays.fill(history, null);
		echoes.clear();
		currentTick = 0;
		echoReceiptTick = -100;
		echoReceiptNanos = 0;
		echoBpt = echoBps = Double.NaN;
	}
	
	public void tick(int tick, long nanos)
	{
		if(tick < currentTick)
			reset();
		currentTick = tick;
		if(sample(tick) == null)
			history[Math.floorMod(tick, SIZE)] = new Sample(tick, nanos);
	}
	
	private Sample sample(int tick)
	{
		Sample s = history[Math.floorMod(tick, SIZE)];
		return s != null && s.tick == tick ? s : null;
	}
	
	public void sent(int tick, long nanos, double fromX, double fromZ, double x,
		double y, double z)
	{
		if(!Double.isFinite(x + y + z + fromX + fromZ))
			return;
		tick(tick, nanos);
		Sample s = sample(tick);
		s.distance += Math.hypot(x - fromX, z - fromZ);
		s.x = x;
		s.y = y;
		s.z = z;
		s.endpoint = true;
		s.packets++;
	}
	
	public boolean echo(int receiptTick, long nanos, double x, double y,
		double z)
	{
		if(!Double.isFinite(x + y + z))
			return false;
		Sample matched = null;
		for(int t = currentTick; t >= Math.max(0, currentTick - 80); t--)
		{
			Sample s = sample(t);
			if(s != null && s.endpoint && Math.abs(x - s.x) < .002
				&& Math.abs(y - s.y) < .002 && Math.abs(z - s.z) < .002)
			{
				matched = s;
				break;
			}
		}
		if(matched == null)
			return false;
		Echo last = echoes.peekLast();
		if(last != null && matched.tick <= last.tick)
			return false;
		Echo end = new Echo(matched.tick, matched.nanos, x, z);
		echoes.addLast(end);
		while(echoes.size() > 1 && echoes.peekFirst().tick < end.tick - 20)
			echoes.removeFirst();
		Echo start = echoes.peekFirst();
		int dt = end.tick - start.tick;
		if(dt >= 10 && end.nanos > start.nanos)
		{
			double distance = Math.hypot(end.x - start.x, end.z - start.z);
			echoBpt = distance / dt;
			echoBps = distance / ((end.nanos - start.nanos) / 1e9);
			echoReceiptTick = receiptTick;
			echoReceiptNanos = nanos;
		}else
		{
			echoBpt = echoBps = Double.NaN;
		}
		return true;
	}
	
	public double sentBpt()
	{
		double total = 0;
		int count = 0;
		
		for(int t = currentTick - 1; t >= currentTick - 5; t--)
		{
			Sample s = sample(t);
			if(s != null)
			{
				total += s.distance;
				count++;
			}
		}
		return count == 0 ? 0 : total / count;
	}
	
	public double sentBps()
	{
		Sample end = sample(currentTick),
			start = sample(Math.max(1, currentTick - 20));
		if(start == null || end == null || end.nanos <= start.nanos)
			return 0;
		double distance = 0;
		for(int t = start.tick; t < end.tick; t++)
		{
			Sample s = sample(t);
			if(s != null)
				distance += s.distance;
		}
		return distance / ((end.nanos - start.nanos) / 1e9);
	}
	
	public int packetsLastTick()
	{
		Sample s = sample(currentTick - 1);
		return s == null ? 0 : s.packets;
	}
	
	public double echoBpt(long now)
	{
		return fresh(now) ? echoBpt : Double.NaN;
	}
	
	public double echoBps(long now)
	{
		return fresh(now) ? echoBps : Double.NaN;
	}
	
	private boolean fresh(long now)
	{
		return currentTick - echoReceiptTick <= 20 && now >= echoReceiptNanos
			&& now - echoReceiptNanos <= 1_000_000_000L;
	}
}
