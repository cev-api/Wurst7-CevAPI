/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.UUID;

/**
 * One Shelf activation owns one edit and one transfer, including terminal
 * states.
 */
public final class BookBotActivation
{
	private final String id = UUID.randomUUID().toString();
	private boolean submitted;
	private boolean transferred;
	private boolean cancelled;
	
	public String id()
	{
		return id;
	}
	
	public boolean submitted()
	{
		return submitted;
	}
	
	public boolean submit()
	{
		if(cancelled || submitted)
			return false;
		submitted = true;
		return true;
	}
	
	public boolean transfer()
	{
		if(cancelled || !submitted || transferred)
			return false;
		transferred = true;
		return true;
	}
	
	public void cancel()
	{
		cancelled = true;
	}
}
