/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.wurstclient.util.json.JsonUtils;

public enum MinecraftServicesApi
{
	;
	
	private static final URL PROFILE_URL =
		createURL("https://api.minecraftservices.com/minecraft/profile");
	
	private static final URL NAME_CHANGE_URL = createURL(
		"https://api.minecraftservices.com/minecraft/profile/namechange");
	
	private static final String RENAME_URL_PREFIX =
		"https://api.minecraftservices.com/minecraft/profile/name/";
	
	private static final URL SKIN_URL =
		createURL("https://api.minecraftservices.com/minecraft/profile/skins");
	
	public static ProfileData getProfile(String mcAccessToken)
		throws ApiException
	{
		ApiResponse response =
			sendJsonRequest("GET", PROFILE_URL, mcAccessToken, null);
		
		if(!response.isSuccess())
			throw new ApiException(response.statusCode,
				mapProfileError(response.statusCode, response.getErrorText()));
		
		ProfileData profile = parseProfileData(response.json);
		if(profile == null || profile.name().isBlank())
			throw new ApiException(response.statusCode,
				"Profile endpoint returned no account name.");
		
		return profile;
	}
	
	public static NameChangeInfo getNameChangeInfo(String mcAccessToken)
		throws ApiException
	{
		ApiResponse response =
			sendJsonRequest("GET", NAME_CHANGE_URL, mcAccessToken, null);
		
		if(!response.isSuccess())
			throw new ApiException(response.statusCode, mapNameChangeInfoError(
				response.statusCode, response.getErrorText()));
		
		boolean allowed = JsonUtils
			.getAsBoolean(response.getJsonElement("nameChangeAllowed"), true);
		String changedAt =
			JsonUtils.getAsString(response.getJsonElement("changedAt"), "");
		String createdAt =
			JsonUtils.getAsString(response.getJsonElement("createdAt"), "");
		
		return new NameChangeInfo(allowed, changedAt, createdAt);
	}
	
	public static ProfileData changeName(String mcAccessToken, String newName)
		throws ApiException
	{
		String trimmedName = newName == null ? "" : newName.trim();
		if(trimmedName.isEmpty())
			throw new ApiException("New name cannot be empty.");
		
		String encodedName =
			URLEncoder.encode(trimmedName, StandardCharsets.UTF_8);
		URL url = createURL(RENAME_URL_PREFIX + encodedName);
		
		ApiResponse response = sendJsonRequest("PUT", url, mcAccessToken, null);
		if(!response.isSuccess())
			throw new ApiException(response.statusCode, mapNameChangeError(
				response.statusCode, response.getErrorText()));
		
		return getProfile(mcAccessToken);
	}
	
	public static SkinChangeResult changeSkinFromUrl(String mcAccessToken,
		String skinUrl, SkinVariant variant) throws ApiException
	{
		String trimmedUrl = skinUrl == null ? "" : skinUrl.trim();
		if(trimmedUrl.isEmpty())
			throw new ApiException("Skin URL cannot be empty.");
		
		JsonObject payload = new JsonObject();
		payload.addProperty("variant", variant.apiValue);
		payload.addProperty("url", trimmedUrl);
		
		ApiResponse response = sendJsonRequest("POST", SKIN_URL, mcAccessToken,
			payload.toString());
		if(!response.isSuccess())
			throw new ApiException(response.statusCode,
				mapSkinError(response.statusCode, response.getErrorText()));
		
		ProfileData profile = parseProfileData(response.json);
		if(profile == null || profile.name().isBlank())
			profile = getProfile(mcAccessToken);
		
		return new SkinChangeResult(profile, trimmedUrl, variant);
	}
	
	private static ApiResponse sendJsonRequest(String method, URL url,
		String mcAccessToken, String body) throws ApiException
	{
		if(mcAccessToken == null || mcAccessToken.isBlank())
			throw new ApiException("Minecraft access token cannot be empty.");
		
		try
		{
			HttpURLConnection connection =
				(HttpURLConnection)url.openConnection();
			connection.setRequestMethod(method);
			connection.setRequestProperty("Authorization",
				"Bearer " + mcAccessToken.trim());
			connection.setRequestProperty("Accept", "application/json");
			
			if(body != null)
			{
				byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
				connection.setRequestProperty("Content-Type",
					"application/json; charset=UTF-8");
				connection.setRequestProperty("Content-Length",
					"" + bodyBytes.length);
				connection.setDoOutput(true);
				
				try(OutputStream output = connection.getOutputStream())
				{
					output.write(bodyBytes);
				}
			}
			
			int statusCode = connection.getResponseCode();
			String responseBody = readResponseBody(connection, statusCode);
			JsonObject json = parseJsonObject(responseBody);
			
			return new ApiResponse(statusCode, responseBody, json);
			
		}catch(IOException e)
		{
			throw new ApiException("Connection failed: " + e.getMessage(), e);
		}
	}
	
	private static String readResponseBody(HttpURLConnection connection,
		int statusCode) throws IOException
	{
		InputStream input = statusCode >= 400 ? connection.getErrorStream()
			: connection.getInputStream();
		
		if(input == null)
			return "";
		
		try(input)
		{
			InputStreamReader reader =
				new InputStreamReader(input, StandardCharsets.UTF_8);
			BufferedReader bufferedReader = new BufferedReader(reader);
			return bufferedReader.lines().collect(Collectors.joining("\n"));
		}
	}
	
	private static JsonObject parseJsonObject(String body)
	{
		if(body == null || body.isBlank())
			return null;
		
		try
		{
			JsonElement parsed = JsonParser.parseString(body);
			if(parsed.isJsonObject())
				return parsed.getAsJsonObject();
			
		}catch(RuntimeException ignored)
		{}
		
		return null;
	}
	
	private static ProfileData parseProfileData(JsonObject json)
	{
		if(json == null)
			return null;
		
		String id = JsonUtils.getAsString(json.get("id"), "");
		String name = JsonUtils.getAsString(json.get("name"), "");
		
		String activeSkinUrl = "";
		String activeSkinVariant = "";
		JsonElement skinsElement = json.get("skins");
		if(skinsElement != null && skinsElement.isJsonArray())
		{
			for(JsonElement e : skinsElement.getAsJsonArray())
			{
				if(e == null || !e.isJsonObject())
					continue;
				
				JsonObject skin = e.getAsJsonObject();
				String url = JsonUtils.getAsString(skin.get("url"), "");
				if(url.isBlank())
					continue;
				
				String variant = JsonUtils.getAsString(skin.get("variant"), "");
				String state = JsonUtils.getAsString(skin.get("state"), "");
				
				if(activeSkinUrl.isBlank())
				{
					activeSkinUrl = url;
					activeSkinVariant = variant;
				}
				
				if("ACTIVE".equalsIgnoreCase(state))
				{
					activeSkinUrl = url;
					activeSkinVariant = variant;
					break;
				}
			}
		}
		
		return new ProfileData(id, name, activeSkinUrl, activeSkinVariant);
	}
	
	private static String mapProfileError(int statusCode, String details)
	{
		String detail = trimDetail(details);
		String lower = detail.toLowerCase(Locale.ROOT);
		
		if(statusCode == 401 || statusCode == 403 || lower.contains("invalid")
			|| lower.contains("unauthorized") || lower.contains("forbidden"))
			return "Token is not authorized for this Minecraft account.";
		
		if(statusCode == 429 || lower.contains("too many")
			|| lower.contains("rate"))
			return "Rate-limited by Minecraft services. Please wait and retry.";
		
		if(detail.isBlank())
			return "Failed to fetch Minecraft profile (HTTP " + statusCode
				+ ").";
		
		return detail;
	}
	
	private static String mapNameChangeInfoError(int statusCode, String details)
	{
		String detail = trimDetail(details);
		if(detail.isBlank())
			return "Couldn't fetch name-change limits (HTTP " + statusCode
				+ ").";
		
		return detail;
	}
	
	private static String mapNameChangeError(int statusCode, String details)
	{
		String detail = trimDetail(details);
		String lower = detail.toLowerCase(Locale.ROOT);
		
		if(statusCode == 429 || lower.contains("too many")
			|| lower.contains("rate"))
			return "Name change failed: rate-limited by Minecraft services.";
		
		if(statusCode == 401 || statusCode == 403 || lower.contains("invalid")
			|| lower.contains("unauthorized") || lower.contains("forbidden"))
			return "Name change failed: token is no longer authorized.";
		
		if(lower.contains("duplicate") || lower.contains("taken")
			|| lower.contains("already"))
			return "Name change failed: that name is already taken.";
		
		if(lower.contains("not allowed") || lower.contains("cooldown")
			|| lower.contains("namechangeallowed")
			|| lower.contains("constraint"))
			return "Name change failed: Mojang is currently blocking name changes for this account.";
		
		if(lower.contains("invalid") || lower.contains("illegal")
			|| lower.contains("format"))
			return "Name change failed: invalid name format.";
		
		if(detail.isBlank())
			return "Name change failed (HTTP " + statusCode + ").";
		
		return detail;
	}
	
	private static String mapSkinError(int statusCode, String details)
	{
		String detail = trimDetail(details);
		String lower = detail.toLowerCase(Locale.ROOT);
		
		if(statusCode == 429 || lower.contains("too many")
			|| lower.contains("rate"))
			return "Skin change failed: rate-limited by Minecraft services.";
		
		if(statusCode == 401 || statusCode == 403 || lower.contains("invalid")
			|| lower.contains("unauthorized") || lower.contains("forbidden"))
			return "Skin change failed: token is no longer authorized.";
		
		if(lower.contains("url") || lower.contains("download")
			|| lower.contains("fetch") || lower.contains("image")
			|| lower.contains("png"))
			return "Skin change failed: URL could not be fetched as a valid skin.";
		
		if(lower.contains("variant"))
			return "Skin change failed: invalid skin model variant.";
		
		if(detail.isBlank())
			return "Skin change failed (HTTP " + statusCode + ").";
		
		return detail;
	}
	
	private static String trimDetail(String details)
	{
		if(details == null)
			return "";
		
		String trimmed = details.trim().replace('\r', ' ').replace('\n', ' ');
		if(trimmed.length() > 200)
			return trimmed.substring(0, 197) + "...";
		
		return trimmed;
	}
	
	private static URL createURL(String url)
	{
		try
		{
			return URI.create(url).toURL();
			
		}catch(MalformedURLException e)
		{
			throw new IllegalArgumentException(e);
		}
	}
	
	public enum SkinVariant
	{
		CLASSIC("classic"),
		SLIM("slim");
		
		private final String apiValue;
		
		SkinVariant(String apiValue)
		{
			this.apiValue = apiValue;
		}
		
		public String getApiValue()
		{
			return apiValue;
		}
		
		public String getLabel()
		{
			return this == SLIM ? "Slim" : "Classic";
		}
		
		public SkinVariant next()
		{
			return this == CLASSIC ? SLIM : CLASSIC;
		}
	}
	
	public static final class ApiException extends Exception
	{
		private final int statusCode;
		
		public ApiException(String message, Throwable cause)
		{
			super(message, cause);
			statusCode = -1;
		}
		
		public ApiException(String message)
		{
			super(message);
			statusCode = -1;
		}
		
		public ApiException(int statusCode, String message)
		{
			super(message);
			this.statusCode = statusCode;
		}
		
		public int getStatusCode()
		{
			return statusCode;
		}
	}
	
	public record ProfileData(String id, String name, String activeSkinUrl,
		String activeSkinVariant)
	{}
	
	public record NameChangeInfo(boolean allowed, String changedAt,
		String createdAt)
	{}
	
	public record SkinChangeResult(ProfileData profile, String requestedUrl,
		SkinVariant requestedVariant)
	{}
	
	private static final class ApiResponse
	{
		private final int statusCode;
		private final String body;
		private final JsonObject json;
		
		private ApiResponse(int statusCode, String body, JsonObject json)
		{
			this.statusCode = statusCode;
			this.body = body == null ? "" : body;
			this.json = json;
		}
		
		private boolean isSuccess()
		{
			return statusCode >= 200 && statusCode < 300;
		}
		
		private JsonElement getJsonElement(String key)
		{
			if(json == null || key == null || key.isBlank())
				return null;
			
			return json.get(key);
		}
		
		private String getErrorText()
		{
			if(json == null)
				return body;
			
			String[] keys =
				{"errorMessage", "message", "error", "errorType", "details"};
			for(String key : keys)
			{
				JsonElement value = json.get(key);
				if(value == null || value.isJsonNull())
					continue;
				
				if(value.isJsonPrimitive())
				{
					String text = value.getAsString();
					if(!text.isBlank())
						return text;
					continue;
				}
				
				String text = value.toString();
				if(!text.isBlank() && !"[]".equals(text) && !"{}".equals(text))
					return text;
			}
			
			return body;
		}
	}
}
