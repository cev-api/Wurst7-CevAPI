/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

import java.util.ArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ScanActivityRegressionTest
{
	public static void main(String[] args)
	{
		ScanActivity activity = new ScanActivity();
		Object overworld = new Object();
		Object replacementOverworld = new Object();
		AtomicInteger resets = new AtomicInteger();
		ArrayList<Integer> queuedPackets = new ArrayList<>();
		AtomicReference<FutureTask<Void>> pendingScan = new AtomicReference<>();
		Runnable cancel = () -> {
			resets.incrementAndGet();
			queuedPackets.clear();
			if(pendingScan.get() != null)
				pendingScan.get().cancel(false);
		};
		check(!activity.isCurrent(overworld),
			"A scanner cannot render results before adopting a world");
		check(activity.resume(overworld, 1, cancel), "First world must reset");
		check(activity.isCurrent(overworld), "Active world should be current");
		int initialResets = resets.get();
		pendingScan.set(new FutureTask<>(() -> null));
		queuedPackets.add(1);
		check(!activity.resume(overworld, 1, cancel),
			"Steady ticks must retain current scan and queued updates");
		check(queuedPackets.size() == 1 && resets.get() == initialResets,
			"Steady ticks must not repeatedly clear work");
		check(activity.suspend(cancel), "First pause must cancel work");
		check(queuedPackets.isEmpty() && pendingScan.get().isCancelled(),
			"Suspension must clear queued packets and cancel pending scans");
		check(!activity.isCurrent(overworld), "Paused results cannot render");
		int pausedResets = resets.get();
		for(int tick = 0; tick < 1000; tick++)
			check(!activity.suspend(cancel),
				"An idle tick must not reset again");
		check(resets.get() == pausedResets, "Idle cleanup must run once");
		check(activity.resume(overworld, 1, cancel),
			"Returning to the same world after pause must restart scanning");
		queuedPackets.add(2);
		check(activity.resume(overworld, 2, cancel) && queuedPackets.isEmpty(),
			"Target selection changes must discard prior-query results/updates");
		queuedPackets.add(3);
		check(activity.resume(replacementOverworld, 2, cancel),
			"Replacement worlds must reset even with the same target selection");
		check(
			!activity.isCurrent(overworld)
				&& activity.isCurrent(replacementOverworld)
				&& queuedPackets.isEmpty(),
			"Old-world results and packets must not survive a world replacement");
		activity.suspend(cancel);
		check(!activity.isCurrent(null),
			"Disconnect cannot expose stale results");
		System.out.println("Scan activity regressions passed.");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
