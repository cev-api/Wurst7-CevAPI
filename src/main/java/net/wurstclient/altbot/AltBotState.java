/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.util.UUID;

import net.wurstclient.altmanager.TokenAlt;

/**
 * Immutable snapshot of one bot's state, safe to read from the Minecraft
 * render thread while Netty threads update the live session.
 */
public final class AltBotState
{
	private final TokenAlt alt;
	private final String displayName;
	private final UUID uuid;
	private final String username;
	private final String server;
	private final BotState state;
	private final String lastError;
	private final long connectionStartMillis;
	private final boolean ready;
	private final boolean activeClient;
	private final double x;
	private final double y;
	private final double z;
	private final boolean havePos;
	
	AltBotState(TokenAlt alt, String displayName, UUID uuid, String username,
		String server, BotState state, String lastError,
		long connectionStartMillis, boolean ready, boolean activeClient,
		double x, double y, double z, boolean havePos)
	{
		this.alt = alt;
		this.displayName = displayName;
		this.uuid = uuid;
		this.username = username;
		this.server = server;
		this.state = state;
		this.lastError = lastError;
		this.connectionStartMillis = connectionStartMillis;
		this.ready = ready;
		this.activeClient = activeClient;
		this.x = x;
		this.y = y;
		this.z = z;
		this.havePos = havePos;
	}
	
	public TokenAlt getAlt()
	{
		return alt;
	}
	
	public String getDisplayName()
	{
		return displayName;
	}
	
	public UUID getUuid()
	{
		return uuid;
	}
	
	public String getUsername()
	{
		return username;
	}
	
	public String getServer()
	{
		return server;
	}
	
	public BotState getState()
	{
		return state;
	}
	
	public String getLastError()
	{
		return lastError;
	}
	
	public long getConnectionStartMillis()
	{
		return connectionStartMillis;
	}
	
	public boolean isReady()
	{
		return ready;
	}
	
	public boolean isActiveClient()
	{
		return activeClient;
	}
	
	public boolean hasPosition()
	{
		return havePos;
	}
	
	public double getX()
	{
		return x;
	}
	
	public double getY()
	{
		return y;
	}
	
	public double getZ()
	{
		return z;
	}
	
	/** @return a stable label such as "SomeAlt (a1b2c3d4)". */
	public String getLabel()
	{
		return AltBotUtils.getLabel(username, uuid);
	}
}
