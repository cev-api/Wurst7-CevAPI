/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

/**
 * A supporter entitlement that has passed all signature and claim checks.
 */
public record SupporterEntitlement(String token, String subject,
	String deviceId, long issuedAt, long expiresAt)
{
	public boolean isExpired(long nowMillis)
	{
		return expiresAt <= nowMillis / 1000L;
	}
}
