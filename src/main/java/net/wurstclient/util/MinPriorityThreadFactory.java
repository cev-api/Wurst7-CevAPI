/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class MinPriorityThreadFactory implements ThreadFactory
{
	private static final int DEFAULT_PRIORITY = Thread.NORM_PRIORITY;
	private static final String PRIORITY_PROPERTY =
		"wurst.searchThreadPriority";
	private static final AtomicInteger poolNumber = new AtomicInteger(1);
	private final ThreadGroup group;
	private final AtomicInteger threadNumber = new AtomicInteger(1);
	private final String namePrefix;
	private final int threadPriority;
	
	public MinPriorityThreadFactory()
	{
		this(getConfiguredThreadPriority());
	}
	
	public MinPriorityThreadFactory(int threadPriority)
	{
		group = Thread.currentThread().getThreadGroup();
		namePrefix = "pool-min-" + poolNumber.getAndIncrement() + "-thread-";
		this.threadPriority = clampThreadPriority(threadPriority);
	}
	
	@Override
	public Thread newThread(Runnable r)
	{
		String name = namePrefix + threadNumber.getAndIncrement();
		Thread t = new Thread(group, r, name);
		t.setDaemon(true);
		t.setPriority(threadPriority);
		return t;
	}
	
	public static int getConfiguredThreadPriority()
	{
		String raw = System.getProperty(PRIORITY_PROPERTY);
		if(raw == null || raw.isBlank())
			return DEFAULT_PRIORITY;
		
		try
		{
			int parsed = Integer.parseInt(raw.trim());
			return clampThreadPriority(parsed);
			
		}catch(NumberFormatException e)
		{
			return DEFAULT_PRIORITY;
		}
	}
	
	public static int clampThreadPriority(int priority)
	{
		return Math.max(Thread.MIN_PRIORITY,
			Math.min(Thread.MAX_PRIORITY, priority));
	}
	
	public static ExecutorService newFixedThreadPool()
	{
		return newFixedThreadPool(getConfiguredThreadPriority());
	}
	
	public static ExecutorService newFixedThreadPool(int threadPriority)
	{
		return Executors.newFixedThreadPool(
			Runtime.getRuntime().availableProcessors(),
			new MinPriorityThreadFactory(threadPriority));
	}
}
