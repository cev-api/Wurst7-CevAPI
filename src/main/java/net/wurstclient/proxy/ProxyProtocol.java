/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.proxy;

/** Proxy protocols supported by the multiplayer connection manager. */
public enum ProxyProtocol
{
	SOCKS5("SOCKS5"),
	HTTP("HTTP CONNECT");
	
	private final String displayName;
	
	ProxyProtocol(String displayName)
	{
		this.displayName = displayName;
	}
	
	public String getDisplayName()
	{
		return displayName;
	}
}
