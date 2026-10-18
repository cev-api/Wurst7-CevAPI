/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

/**
 * Thrown when a supporter entitlement cannot be trusted. The message is safe
 * to show in the GUI and must never contain the token itself.
 */
public final class EntitlementException extends Exception
{
	private final boolean expired;
	
	public EntitlementException(String message)
	{
		this(message, false);
	}
	
	public EntitlementException(String message, boolean expired)
	{
		super(message);
		this.expired = expired;
	}
	
	public EntitlementException(String message, Throwable cause)
	{
		super(message, cause);
		expired = false;
	}
	
	/**
	 * Whether the token was otherwise well-formed but has expired.
	 */
	public boolean isExpired()
	{
		return expired;
	}
}
