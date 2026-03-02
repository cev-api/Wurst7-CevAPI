/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;

public enum MovementMutationTracker
{
	;
	
	private static final String HACK_PACKAGE_PREFIX = "net.wurstclient.hacks.";
	private static final Map<Hack, MutationEvidence> RECENT_MUTATIONS =
		new ConcurrentHashMap<>();
	private static final Map<String, Hack> HACK_CLASS_LOOKUP =
		new ConcurrentHashMap<>();
	private static volatile int cachedHackCount = -1;
	
	public static void markLocalPlayerVelocityMutation(Entity entity,
		String source)
	{
		if(entity == null)
			return;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.player == null || entity != mc.player)
			return;
		
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst == null || wurst.getHax() == null || wurst.getOtfs() == null
			|| !wurst.getOtfs().packetFirewallOtf.isEnabled())
			return;
		
		rebuildLookupIfNeeded(wurst);
		SenderResolution sender = resolveSenderHackFromStack(wurst);
		if(sender == null)
			return;
		
		RECENT_MUTATIONS.put(sender.hack(), new MutationEvidence(
			System.currentTimeMillis(), sender.stackFrame(), source));
	}
	
	public static LinkedHashMap<Hack, MutationEvidence> getRecentMutations(
		long windowMs)
	{
		long now = System.currentTimeMillis();
		LinkedHashMap<Hack, MutationEvidence> out = new LinkedHashMap<>();
		
		RECENT_MUTATIONS.entrySet()
			.removeIf(entry -> !entry.getKey().isEnabled()
				|| now - entry.getValue().timestampMs() > windowMs);
		
		for(var entry : RECENT_MUTATIONS.entrySet())
			out.put(entry.getKey(), entry.getValue());
		
		return out;
	}
	
	public static void clear()
	{
		RECENT_MUTATIONS.clear();
	}
	
	private static void rebuildLookupIfNeeded(WurstClient wurst)
	{
		int count = wurst.getHax().countHax();
		if(cachedHackCount == count && !HACK_CLASS_LOOKUP.isEmpty())
			return;
		
		HACK_CLASS_LOOKUP.clear();
		for(Hack hack : wurst.getHax().getAllHax())
			HACK_CLASS_LOOKUP.put(hack.getClass().getName(), hack);
		
		cachedHackCount = count;
	}
	
	private static SenderResolution resolveSenderHackFromStack(
		WurstClient wurst)
	{
		StackTraceElement[] trace = Thread.currentThread().getStackTrace();
		for(StackTraceElement element : trace)
		{
			String className = element.getClassName();
			if(!className.startsWith(HACK_PACKAGE_PREFIX))
				continue;
			
			Hack hack = getHackFromClassName(className);
			if(hack == null || !hack.isEnabled()
				|| hack == wurst.getHax().panicHack)
				continue;
			
			String frame = className + "." + element.getMethodName() + ":"
				+ element.getLineNumber();
			return new SenderResolution(hack, frame);
		}
		
		return null;
	}
	
	private static Hack getHackFromClassName(String className)
	{
		Hack direct = HACK_CLASS_LOOKUP.get(className);
		if(direct != null)
			return direct;
		
		int index = className.indexOf('$');
		if(index < 0)
			return null;
		
		return HACK_CLASS_LOOKUP.get(className.substring(0, index));
	}
	
	private record SenderResolution(Hack hack, String stackFrame)
	{}
	
	public record MutationEvidence(long timestampMs, String stackFrame,
		String source)
	{}
}
