/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Remembers requested hack states while activation of the firewall pauses them.
 */
public final class FirewallActivationPauses<T>
{
	private final Set<T> paused = new LinkedHashSet<>();
	private final Predicate<T> enabled;
	private final Consumer<T> disable;
	private final Consumer<T> enable;
	
	public FirewallActivationPauses(Predicate<T> enabled, Consumer<T> disable,
		Consumer<T> enable)
	{
		this.enabled = enabled;
		this.disable = disable;
		this.enable = enable;
	}
	
	public void pause(Iterable<T> hacks)
	{
		for(T hack : hacks)
		{
			if(!enabled.test(hack))
				continue;
			// Register before the callback so saving and HUD updates see a
			// temporary pause rather than a user turning the hack off.
			paused.add(hack);
			disable.accept(hack);
		}
	}
	
	public boolean requestEnable(T hack)
	{
		return paused.add(hack);
	}
	
	public boolean contains(T hack)
	{
		return paused.contains(hack);
	}
	
	public boolean isEmpty()
	{
		return paused.isEmpty();
	}
	
	public void restore()
	{
		for(T hack : new LinkedHashSet<>(paused))
		{
			paused.remove(hack);
			if(!enabled.test(hack))
				enable.accept(hack);
		}
	}
}
