/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import com.google.gson.JsonObject;

/**
 * Locally stored supporter session data. The refresh token is a credential and
 * must only ever be written through {@link SupporterCredentialStore}.
 */
final class SupporterData
{
	String deviceId = "";
	String refreshToken = "";
	String entitlement = "";
	long entitlementExpiresAt;
	long lastRefreshAt;
	boolean hideMenuBrand;
	boolean vanillaMenuLogo;
	
	boolean hasRefreshCredentials()
	{
		return !deviceId.isBlank() && !refreshToken.isBlank();
	}
	
	JsonObject toJson()
	{
		JsonObject json = new JsonObject();
		json.addProperty("device_id", deviceId);
		json.addProperty("refresh_token", refreshToken);
		json.addProperty("entitlement", entitlement);
		json.addProperty("entitlement_expires_at", entitlementExpiresAt);
		json.addProperty("last_refresh_at", lastRefreshAt);
		json.addProperty("hide_menu_brand", hideMenuBrand);
		json.addProperty("vanilla_menu_logo", vanillaMenuLogo);
		return json;
	}
	
	static SupporterData fromJson(JsonObject json)
	{
		SupporterData data = new SupporterData();
		data.deviceId = getString(json, "device_id");
		data.refreshToken = getString(json, "refresh_token");
		data.entitlement = getString(json, "entitlement");
		data.entitlementExpiresAt = getLong(json, "entitlement_expires_at");
		data.lastRefreshAt = getLong(json, "last_refresh_at");
		data.hideMenuBrand = getBoolean(json, "hide_menu_brand");
		data.vanillaMenuLogo = getBoolean(json, "vanilla_menu_logo");
		return data;
	}
	
	private static boolean getBoolean(JsonObject json, String name)
	{
		return json != null && json.has(name)
			&& json.get(name).isJsonPrimitive()
			&& json.get(name).getAsJsonPrimitive().isBoolean()
			&& json.get(name).getAsBoolean();
	}
	
	private static String getString(JsonObject json, String name)
	{
		if(json == null || !json.has(name) || !json.get(name).isJsonPrimitive()
			|| !json.get(name).getAsJsonPrimitive().isString())
			return "";
		return json.get(name).getAsString();
	}
	
	private static long getLong(JsonObject json, String name)
	{
		if(json == null || !json.has(name) || !json.get(name).isJsonPrimitive()
			|| !json.get(name).getAsJsonPrimitive().isNumber())
			return 0L;
		try
		{
			return json.get(name).getAsLong();
			
		}catch(NumberFormatException e)
		{
			return 0L;
		}
	}
}
