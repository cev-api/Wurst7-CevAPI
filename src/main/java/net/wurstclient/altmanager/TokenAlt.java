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
import net.wurstclient.proxy.SocksProxy;

public final class TokenAlt extends Alt
{
	private final String token;
	private volatile String refreshToken;
	private final String clientId;
	private String name;
	
	public TokenAlt(String token, String refreshToken, String name,
		boolean favorite)
	{
		this(token, refreshToken, name, favorite, "");
	}
	
	public TokenAlt(String token, String refreshToken, String name,
		boolean favorite, String clientId)
	{
		super(favorite);
		
		String normalizedToken = token == null ? "" : token.trim();
		String normalizedRefresh =
			refreshToken == null ? "" : refreshToken.trim();
		
		if(normalizedToken.isEmpty() && normalizedRefresh.isEmpty())
			throw new IllegalArgumentException();
		
		this.token = normalizedToken;
		this.refreshToken = normalizedRefresh;
		this.clientId = clientId == null ? "" : clientId.trim();
		this.name = name == null ? "" : name;
	}
	
	@Override
	public void login() throws LoginException
	{
		if(!refreshToken.isEmpty())
			refreshToken =
				MicrosoftLoginManager.loginWithRefreshTokenAndGetUpdatedToken(
					refreshToken, clientId);
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
		jsonAlt.addProperty("client_id", getEffectiveClientId());
		addLastValidated(jsonAlt);
		addProxyAssociation(jsonAlt);
		
		String key = "token_"
			+ Integer.toHexString(Objects.hash(token, refreshToken, name));
		json.add(key, jsonAlt);
	}
	
	@Override
	public String exportAsTXT()
	{
		return "token:" + token + ":" + refreshToken + ":" + name + ":"
			+ getEffectiveClientId();
	}
	
	private String getEffectiveClientId()
	{
		return clientId.isEmpty() ? MicrosoftLoginManager.getDefaultClientId()
			: clientId;
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
	
	/**
	 * Authenticates this account without updating the game session.
	 * Refresh-token
	 * rotation is retained so the next export remains valid.
	 */
	public MinecraftProfile authenticateWithoutSession() throws LoginException
	{
		return authenticateWithoutSession(null);
	}
	
	public MinecraftProfile authenticateWithoutSession(SocksProxy proxy)
		throws LoginException
	{
		MicrosoftLoginManager.setAuthenticationProxy(proxy);
		try
		{
			if(refreshToken.isEmpty())
			{
				MinecraftProfile profile = MicrosoftLoginManager
					.authenticateTokenWithoutSession(token);
				markValidatedNow();
				return profile;
			}
			
			MicrosoftLoginManager.RefreshTokenAuthResult result =
				MicrosoftLoginManager
					.authenticateRefreshTokenWithResultWithoutSession(
						refreshToken, clientId);
			refreshToken = result.getRefreshToken();
			MinecraftProfile profile = result.getProfile();
			markValidatedNow();
			return profile;
		}finally
		{
			MicrosoftLoginManager.clearAuthenticationProxy();
		}
	}
	
	public String getRefreshToken()
	{
		return refreshToken;
	}
	
	public String getClientId()
	{
		return clientId;
	}
	
	void setName(String name)
	{
		this.name = name == null ? "" : name;
	}
	
	@Override
	public int hashCode()
	{
		return Objects.hash(token, refreshToken, clientId);
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
			&& Objects.equals(refreshToken, other.refreshToken)
			&& Objects.equals(clientId, other.clientId);
	}
}
