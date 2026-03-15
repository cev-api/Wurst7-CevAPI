/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager;

import java.util.Objects;

import com.google.gson.JsonObject;
import net.wurstclient.WurstClient;

public final class TokenAlt extends Alt
{
	private final String token;
	private final String refreshToken;
	private String name;
	
	public TokenAlt(String token, String refreshToken, String name,
		boolean favorite)
	{
		super(favorite);
		
		String normalizedToken = token == null ? "" : token.trim();
		String normalizedRefresh =
			refreshToken == null ? "" : refreshToken.trim();
		
		if(normalizedToken.isEmpty() && normalizedRefresh.isEmpty())
			throw new IllegalArgumentException();
		
		this.token = normalizedToken;
		this.refreshToken = normalizedRefresh;
		this.name = name == null ? "" : name;
	}
	
	@Override
	public void login() throws LoginException
	{
		if(!refreshToken.isEmpty())
			MicrosoftLoginManager.loginWithRefreshToken(refreshToken);
		else
			MicrosoftLoginManager.loginWithToken(token);
		
		name = getNameFromSession();
	}
	
	private String getNameFromSession()
	{
		String sessionName = WurstClient.MC.getUser().getName();
		
		if(sessionName == null || sessionName.isEmpty())
			throw new RuntimeException(
				"Login returned " + (sessionName == null ? "null" : "empty")
					+ " username. This shouldn't be possible!");
		
		return sessionName;
	}
	
	@Override
	public void exportAsJson(JsonObject json)
	{
		JsonObject jsonAlt = new JsonObject();
		jsonAlt.addProperty("type", "token");
		jsonAlt.addProperty("token", token);
		jsonAlt.addProperty("refresh_token", refreshToken);
		jsonAlt.addProperty("name", name);
		jsonAlt.addProperty("starred", isFavorite());
		
		String key = "token_"
			+ Integer.toHexString(Objects.hash(token, refreshToken, name));
		json.add(key, jsonAlt);
	}
	
	@Override
	public String exportAsTXT()
	{
		return "token:" + token + ":" + refreshToken + ":" + name;
	}
	
	@Override
	public String getName()
	{
		return name;
	}
	
	@Override
	public String getDisplayName()
	{
		return name.isEmpty() ? "token alt" : name;
	}
	
	public String getToken()
	{
		return token;
	}
	
	public String getRefreshToken()
	{
		return refreshToken;
	}
	
	void setName(String name)
	{
		this.name = name == null ? "" : name;
	}
	
	@Override
	public int hashCode()
	{
		return Objects.hash(token, refreshToken);
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if(this == obj)
			return true;
		
		if(!(obj instanceof TokenAlt))
			return false;
		
		TokenAlt other = (TokenAlt)obj;
		return Objects.equals(token, other.token)
			&& Objects.equals(refreshToken, other.refreshToken);
	}
}
