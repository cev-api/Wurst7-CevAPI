/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.wurstclient.WurstClient;

public final class ShadertoyBackgroundManager
{
	private static final Identifier SHADER_RESOURCE =
		Identifier.parse("wurst:shaders/core/title_shadertoy_background.fsh");
	private static final Pattern SHADERTOY_ID = Pattern
		.compile("(?:shadertoy\\.com/(?:view|embed)/|^)([A-Za-z0-9]{6})");
	private static final Pattern JSON_CODE =
		Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
	private static final HttpClient HTTP =
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	
	private ShadertoyBackgroundManager()
	{}
	
	public static boolean hasCustomShader()
	{
		return Files.isRegularFile(getGeneratedShaderPath());
	}
	
	public static Optional<Resource> getCustomShaderResource(Identifier id)
	{
		if(!SHADER_RESOURCE.equals(id) || !hasCustomShader())
			return Optional.empty();
		
		IoSupplier<InputStream> stream =
			() -> Files.newInputStream(getGeneratedShaderPath());
		return Optional.of(new Resource(null, stream));
	}
	
	public static Path getFolder()
	{
		return WurstClient.INSTANCE.getWurstFolder()
			.resolve("shadertoy-background");
	}
	
	public static Path getRawShaderPath()
	{
		return getFolder().resolve("custom.shadertoy.glsl");
	}
	
	public static Path getGeneratedShaderPath()
	{
		return getFolder().resolve("custom_title_shadertoy_background.fsh");
	}
	
	public static Path getPresetsFolder()
	{
		return getFolder().resolve("presets");
	}
	
	public static String loadRawShader()
	{
		try
		{
			if(!Files.isRegularFile(getRawShaderPath()))
				return "";
			return Files.readString(getRawShaderPath(), StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			return "";
		}
	}
	
	public static String importFromUrl(String url) throws Exception
	{
		String shaderId = extractShaderId(url);
		String rawSource = downloadShaderSource(shaderId, url);
		saveCustomShader(rawSource);
		return "Loaded custom Shadertoy " + shaderId + ".";
	}
	
	public static String importFromSource(String rawSource) throws Exception
	{
		if(rawSource == null || rawSource.isBlank())
			throw new IllegalArgumentException("Paste Shadertoy code first.");
		
		saveCustomShader(rawSource);
		return "Loaded pasted Shadertoy code.";
	}
	
	public static List<Path> listPresets()
	{
		Path folder = getPresetsFolder();
		if(!Files.isDirectory(folder))
			return List.of();
		
		try(Stream<Path> stream = Files.list(folder))
		{
			return stream.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".glsl"))
				.sorted(Comparator.comparing(
					path -> path.getFileName().toString().toLowerCase()))
				.toList();
		}catch(IOException e)
		{
			return List.of();
		}
	}
	
	public static String savePreset(String name, String rawSource)
		throws IOException
	{
		String trimmed = name == null ? "" : name.trim();
		if(trimmed.isEmpty())
			throw new IllegalArgumentException("Preset name cannot be empty.");
		
		if(rawSource == null || rawSource.isBlank())
			throw new IllegalArgumentException(
				"Load or paste a custom shader before saving a preset.");
		
		String fileName = toPresetFileName(trimmed);
		Files.createDirectories(getPresetsFolder());
		Files.writeString(getPresetsFolder().resolve(fileName), rawSource,
			StandardCharsets.UTF_8);
		return "Saved preset '" + trimmed + "'.";
	}
	
	public static String loadPreset(Path path) throws Exception
	{
		if(path == null || !Files.isRegularFile(path))
			throw new IllegalArgumentException("Preset file not found.");
		
		String rawSource = Files.readString(path, StandardCharsets.UTF_8);
		saveCustomShader(rawSource);
		return "Loaded preset '" + getPresetDisplayName(path) + "'.";
	}
	
	public static String deletePreset(Path path) throws IOException
	{
		if(path == null || !Files.isRegularFile(path))
			throw new IllegalArgumentException("Preset file not found.");
		
		String name = getPresetDisplayName(path);
		Files.deleteIfExists(path);
		return "Deleted preset '" + name + "'.";
	}
	
	public static String getPresetDisplayName(Path path)
	{
		String fileName = path.getFileName().toString();
		return fileName.endsWith(".glsl")
			? fileName.substring(0, fileName.length() - 5) : fileName;
	}
	
	public static boolean isValidPresetName(String name)
	{
		String trimmed = name == null ? "" : name.trim();
		return !trimmed.isEmpty()
			&& trimmed.chars().noneMatch(ch -> "\\/:*?\"<>|".indexOf(ch) >= 0);
	}
	
	public static void clearCustomShader()
	{
		try
		{
			Files.deleteIfExists(getGeneratedShaderPath());
			Files.deleteIfExists(getRawShaderPath());
		}catch(IOException e)
		{
			throw new UncheckedIOException(e);
		}
		
		reloadResources();
	}
	
	private static void saveCustomShader(String rawSource) throws IOException
	{
		String generatedSource = convertToMinecraftShader(rawSource);
		
		Files.createDirectories(getFolder());
		Files.writeString(getRawShaderPath(), rawSource,
			StandardCharsets.UTF_8);
		Files.writeString(getGeneratedShaderPath(), generatedSource,
			StandardCharsets.UTF_8);
		
		reloadResources();
	}
	
	private static String extractShaderId(String url)
	{
		String trimmed = url == null ? "" : url.trim();
		Matcher matcher = SHADERTOY_ID.matcher(trimmed);
		if(!matcher.find())
			throw new IllegalArgumentException(
				"Expected a Shadertoy URL like https://www.shadertoy.com/view/Nfc3RM");
		return matcher.group(1);
	}
	
	private static String downloadShaderSource(String shaderId,
		String originalUrl) throws Exception
	{
		String apiKey = System.getProperty("wurst.shadertoyApiKey", "").trim();
		if(!apiKey.isEmpty())
		{
			String apiUrl = "https://www.shadertoy.com/api/v1/shaders/"
				+ shaderId + "?key=" + apiKey;
			Optional<String> apiSource = tryDownloadApiShader(apiUrl);
			if(apiSource.isPresent())
				return apiSource.get();
		}
		
		String pageUrl = originalUrl == null || originalUrl.isBlank()
			? "https://www.shadertoy.com/view/" + shaderId : originalUrl.trim();
		String html = get(pageUrl);
		Optional<String> pageSource = extractSourceFromPage(html);
		if(pageSource.isPresent())
			return pageSource.get();
		
		throw new IOException(
			"Could not find shader code in the Shadertoy page. If Shadertoy blocks the request, set -Dwurst.shadertoyApiKey=<key> and try again.");
	}
	
	private static Optional<String> tryDownloadApiShader(String url)
	{
		try
		{
			JsonObject root =
				JsonParser.parseString(get(url)).getAsJsonObject();
			JsonObject shader = root.getAsJsonObject("Shader");
			if(shader == null)
				return Optional.empty();
			
			JsonArray renderPasses = shader.getAsJsonArray("renderpass");
			return findImagePassCode(renderPasses);
		}catch(Exception e)
		{
			return Optional.empty();
		}
	}
	
	private static Optional<String> extractSourceFromPage(String html)
	{
		try
		{
			Matcher matcher = JSON_CODE.matcher(html);
			while(matcher.find())
			{
				String code = JsonParser
					.parseString("\"" + matcher.group(1) + "\"").getAsString();
				if(code.contains("mainImage"))
					return Optional.of(code);
			}
		}catch(Exception ignored)
		{}
		
		return Optional.empty();
	}
	
	private static Optional<String> findImagePassCode(JsonArray renderPasses)
	{
		if(renderPasses == null)
			return Optional.empty();
		
		for(JsonElement element : renderPasses)
		{
			if(!element.isJsonObject())
				continue;
			
			JsonObject pass = element.getAsJsonObject();
			String type =
				pass.has("type") ? pass.get("type").getAsString() : "";
			String code =
				pass.has("code") ? pass.get("code").getAsString() : "";
			if(("image".equals(type) || code.contains("mainImage"))
				&& !code.isBlank())
				return Optional.of(code);
		}
		
		return Optional.empty();
	}
	
	private static String get(String url) throws Exception
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(20))
			.header("User-Agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) WurstClient")
			.header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
			.GET().build();
		HttpResponse<String> response =
			HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if(response.statusCode() < 200 || response.statusCode() >= 300)
			throw new IOException(
				"Shadertoy returned HTTP " + response.statusCode() + ".");
		return response.body();
	}
	
	private static String convertToMinecraftShader(String source)
	{
		String body = source.replace("\r\n", "\n");
		body = body.replaceAll("(?m)^\\s*#version\\s+\\d+\\s*", "");
		body = body.replaceAll("(?m)^\\s*precision\\s+\\w+\\s+float\\s*;", "");
		
		if(!body.contains("mainImage"))
			throw new IllegalArgumentException(
				"Only single-pass Shadertoy shaders with mainImage() are supported.");
		
		return """
			#version 330
			
			in vec2 texCoord;
			in vec4 vertexColor;
			out vec4 fragColor;
			
			uniform sampler2D Sampler0;
			
			float titleTime()
			{
				vec2 packed = floor(vertexColor.rg * 255.0 + 0.5);
				return (packed.x * 256.0 + packed.y) / 20.0;
			}
			
			#define iTime titleTime()
			#define iTimeDelta 0.05
			#define iFrame 0
			#define iFrameRate 20.0
			#define iResolution vec3(1920.0, 1080.0, 1.0)
			#define iMouse vec4(0.0)
			#define iChannel0 Sampler0
			#define iChannel1 Sampler0
			#define iChannel2 Sampler0
			#define iChannel3 Sampler0
			
			""" + body + """
			
			void main()
			{
				mainImage(fragColor, texCoord * iResolution.xy);
			}
			""";
	}
	
	private static void reloadResources()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null)
			return;
		
		mc.execute(() -> mc.reloadResourcePacks());
	}
	
	private static String toPresetFileName(String name)
	{
		if(!isValidPresetName(name))
			throw new IllegalArgumentException(
				"Preset name contains invalid filename characters.");
		
		return name.trim() + ".glsl";
	}
}
