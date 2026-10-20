/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

/** Tracks transitions so an idle scanner cancels its work only once. */
public final class ScanActivity
{
	private Object context;
	private int version;
	private boolean suspended;
	
	public boolean suspend(Runnable cancel)
	{
		if(suspended)
			return false;
		suspended = true;
		context = null;
		cancel.run();
		return true;
	}
	
	public boolean resume(Object nextContext, int nextVersion, Runnable cancel)
	{
		java.util.Objects.requireNonNull(nextContext);
		boolean changed =
			suspended || context != nextContext || version != nextVersion;
		if(changed)
			cancel.run();
		suspended = false;
		context = nextContext;
		version = nextVersion;
		return changed;
	}
	
	public boolean isCurrent(Object currentContext)
	{
		return !suspended && context != null && context == currentContext;
	}
}
