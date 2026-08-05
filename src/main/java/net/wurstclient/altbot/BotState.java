/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

/**
 * Connection state of a single protocol-bot account.
 */
public enum BotState
{
	/** No connection and no connection attempt in progress. */
	DISCONNECTED,
	
	/** Refreshing the account's Microsoft session / fetching a profile. */
	AUTHENTICATING,
	
	/** Opening the TCP connection and sending the login handshake. */
	CONNECTING,
	
	/** Login handshake phase. */
	LOGIN,
	
	/** Configuration phase (after Login Finished). */
	CONFIGURING,
	
	/** Fully connected and able to send packets. */
	PLAY,
	
	/** Being shut down. */
	DISCONNECTING,
	
	/** Something went wrong; see {@link AltBotState#getLastError()}. */
	FAILED;
}
