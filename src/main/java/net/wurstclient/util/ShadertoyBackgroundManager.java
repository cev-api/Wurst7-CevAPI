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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
	private static final Pattern CHANNEL_USAGE =
		Pattern.compile("\\biChannel([0-3])\\b");
	private static final Identifier[] CUSTOM_CHANNEL_TEXTURE_IDS =
		new Identifier[]{Identifier.parse("wurst:shadertoy/channel0"),
			Identifier.parse("wurst:shadertoy/channel1"),
			Identifier.parse("wurst:shadertoy/channel2")};
	private static final Identifier BLANK_CHANNEL_TEXTURE_ID =
		Identifier.parse("wurst:shadertoy/blank");
	private static final HttpClient HTTP =
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static DynamicTexture[] loadedChannelTextures =
		new DynamicTexture[CUSTOM_CHANNEL_TEXTURE_IDS.length];
	private static DynamicTexture blankChannelTexture;
	
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
	
	public static Path getMetadataPath()
	{
		return getFolder().resolve("custom.shadertoy.json");
	}
	
	public static Path getChannelsFolder()
	{
		return getFolder().resolve("channels");
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
		ImportedShader importedShader = downloadShaderSource(shaderId, url);
		saveCustomShader(importedShader);
		return "Loaded custom Shadertoy " + shaderId + ".";
	}
	
	public static String importFromSource(String rawSource) throws Exception
	{
		if(rawSource == null || rawSource.isBlank())
			throw new IllegalArgumentException("Paste Shadertoy code first.");
		
		validateSupportedChannelUsage(rawSource, List.of(), true);
		saveCustomShader(new ImportedShader(rawSource, List.of()));
		return "Loaded pasted Shadertoy code.";
	}
	
	public static TextureSetup createCustomTextureSetup(
		AbstractTexture fallbackTexture)
	{
		ensureChannelTexturesLoaded();
		
		AbstractTexture sampler0 = loadedChannelTextures[0] != null
			? loadedChannelTextures[0] : fallbackTexture;
		AbstractTexture sampler1 = loadedChannelTextures[1] != null
			? loadedChannelTextures[1] : getBlankChannelTexture();
		AbstractTexture sampler2 = loadedChannelTextures[2] != null
			? loadedChannelTextures[2] : getBlankChannelTexture();
		
		return new TextureSetup(sampler0.getTextureView(),
			sampler1.getTextureView(), sampler2.getTextureView(),
			sampler0.getSampler(), sampler1.getSampler(),
			sampler2.getSampler());
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
		copyBundleToPreset(trimmed);
		return "Saved preset '" + trimmed + "'.";
	}
	
	public static String loadPreset(Path path) throws Exception
	{
		if(path == null || !Files.isRegularFile(path))
			throw new IllegalArgumentException("Preset file not found.");
		
		String rawSource = Files.readString(path, StandardCharsets.UTF_8);
		copyPresetBundle(path);
		ImportedShader importedShader =
			new ImportedShader(rawSource, loadStoredChannelInputs());
		saveCustomShader(importedShader);
		return "Loaded preset '" + getPresetDisplayName(path) + "'.";
	}
	
	public static String deletePreset(Path path) throws IOException
	{
		if(path == null || !Files.isRegularFile(path))
			throw new IllegalArgumentException("Preset file not found.");
		
		String name = getPresetDisplayName(path);
		Files.deleteIfExists(path);
		Files.deleteIfExists(getPresetsFolder().resolve(name + ".json"));
		deleteDirectory(getPresetsFolder().resolve(name + "_channels"));
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
			Files.deleteIfExists(getMetadataPath());
			deleteDirectory(getChannelsFolder());
		}catch(IOException e)
		{
			throw new UncheckedIOException(e);
		}
		
		clearLoadedChannelTextures();
		reloadResources();
	}
	
	private static void saveCustomShader(ImportedShader importedShader)
		throws IOException
	{
		validateSupportedChannelUsage(importedShader.rawSource(),
			importedShader.channelInputs(), false);
		String generatedSource = convertToMinecraftShader(importedShader);
		
		Files.createDirectories(getFolder());
		Files.writeString(getRawShaderPath(), importedShader.rawSource(),
			StandardCharsets.UTF_8);
		Files.writeString(getGeneratedShaderPath(), generatedSource,
			StandardCharsets.UTF_8);
		writeChannelMetadata(importedShader.channelInputs());
		writeChannelTextures(importedShader.channelInputs());
		
		clearLoadedChannelTextures();
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
	
	private static ImportedShader downloadShaderSource(String shaderId,
		String originalUrl) throws Exception
	{
		String apiKey = System.getProperty("wurst.shadertoyApiKey", "").trim();
		if(!apiKey.isEmpty())
		{
			String apiUrl = "https://www.shadertoy.com/api/v1/shaders/"
				+ shaderId + "?key=" + apiKey;
			Optional<ImportedShader> apiSource = tryDownloadApiShader(apiUrl);
			if(apiSource.isPresent())
				return apiSource.get();
		}
		
		String pageUrl = originalUrl == null || originalUrl.isBlank()
			? "https://www.shadertoy.com/view/" + shaderId : originalUrl.trim();
		String html = get(pageUrl);
		Optional<String> pageSource = extractSourceFromPage(html);
		if(pageSource.isPresent())
		{
			validateSupportedChannelUsage(pageSource.get(), List.of(), true);
			return new ImportedShader(pageSource.get(), List.of());
		}
		
		throw new IOException(
			"Could not find shader code in the Shadertoy page. If Shadertoy blocks the request, set -Dwurst.shadertoyApiKey=<key> and try again.");
	}
	
	private static Optional<ImportedShader> tryDownloadApiShader(String url)
	{
		try
		{
			JsonObject root =
				JsonParser.parseString(get(url)).getAsJsonObject();
			JsonObject shader = root.getAsJsonObject("Shader");
			if(shader == null)
				return Optional.empty();
			
			JsonArray renderPasses = shader.getAsJsonArray("renderpass");
			return findImagePass(renderPasses);
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
	
	private static Optional<ImportedShader> findImagePass(
		JsonArray renderPasses) throws Exception
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
				return Optional.of(new ImportedShader(code,
					parseChannelInputs(pass.getAsJsonArray("inputs"))));
		}
		
		return Optional.empty();
	}
	
	private static List<ChannelInput> parseChannelInputs(JsonArray inputs)
		throws Exception
	{
		if(inputs == null)
			return List.of();
		
		List<ChannelInput> channelInputs = new ArrayList<>();
		for(JsonElement element : inputs)
		{
			if(!element.isJsonObject())
				continue;
			
			JsonObject input = element.getAsJsonObject();
			int channel =
				input.has("channel") ? input.get("channel").getAsInt() : -1;
			if(channel < 0 || channel > 2)
				continue;
			
			String ctype =
				input.has("ctype") ? input.get("ctype").getAsString() : "";
			String src = input.has("src") ? input.get("src").getAsString() : "";
			if(!"texture".equalsIgnoreCase(ctype) || src.isBlank())
				continue;
			
			boolean vflip = false;
			if(input.has("sampler") && input.get("sampler").isJsonObject())
			{
				JsonObject sampler = input.getAsJsonObject("sampler");
				if(sampler.has("vflip"))
				{
					String flipValue = sampler.get("vflip").getAsString();
					vflip = "true".equalsIgnoreCase(flipValue)
						|| "1".equals(flipValue);
				}
			}
			
			String absoluteUrl = src.startsWith("http") ? src
				: "https://www.shadertoy.com" + src;
			byte[] imageBytes = getBytes(absoluteUrl);
			try(NativeImage image = NativeImage.read(imageBytes))
			{
				NativeImage output = image;
				if(vflip)
					output = flippedCopy(image, pixel -> pixel);
				try
				{
					Path tempPath =
						Files.createTempFile("wurst_shadertoy_channel", ".png");
					output.writeToFile(tempPath);
					byte[] pngBytes = Files.readAllBytes(tempPath);
					Files.deleteIfExists(tempPath);
					channelInputs.add(
						new ChannelInput(channel, "channel" + channel + ".png",
							output.getWidth(), output.getHeight(), pngBytes));
				}finally
				{
					if(output != image)
						output.close();
				}
			}
		}
		
		return channelInputs;
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
	
	private static byte[] getBytes(String url) throws Exception
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(20))
			.header("User-Agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) WurstClient")
			.header("Accept", "image/*,*/*;q=0.8").GET().build();
		HttpResponse<byte[]> response =
			HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if(response.statusCode() < 200 || response.statusCode() >= 300)
			throw new IOException(
				"Shadertoy returned HTTP " + response.statusCode() + ".");
		return response.body();
	}
	
	private static String convertToMinecraftShader(
		ImportedShader importedShader)
	{
		String body = importedShader.rawSource().replace("\r\n", "\n");
		body = body.replaceAll("(?m)^\\s*#version\\s+\\d+\\s*", "");
		body = body.replaceAll("(?m)^\\s*precision\\s+\\w+\\s+float\\s*;", "");
		
		if(!body.contains("mainImage"))
			throw new IllegalArgumentException(
				"Only single-pass Shadertoy shaders with mainImage() are supported.");
		
		String channelResolutionArray =
			buildChannelResolutionArray(importedShader.channelInputs());
		
		return """
			#version 330
			
			in vec2 texCoord;
			in vec4 vertexColor;
			out vec4 fragColor;
			
			uniform sampler2D Sampler0;
			uniform sampler2D Sampler1;
			uniform sampler2D Sampler2;
			
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
			#define iChannel1 Sampler1
			#define iChannel2 Sampler2
			#define iChannel3 Sampler2
			
			const vec3 iChannelResolution[4] = vec3[4](
			""" + channelResolutionArray + """
			);
			
			""" + body + """
			
			void main()
			{
				mainImage(fragColor, texCoord * iResolution.xy);
			}
			""";
	}
	
	private static String buildChannelResolutionArray(
		List<ChannelInput> channelInputs)
	{
		float[][] resolutions = new float[][]{{1920.0F, 1080.0F, 1.0F},
			{0.0F, 0.0F, 0.0F}, {0.0F, 0.0F, 0.0F}, {0.0F, 0.0F, 0.0F}};
		for(ChannelInput input : channelInputs)
			if(input.channel() >= 0 && input.channel() < resolutions.length)
				resolutions[input.channel()] =
					new float[]{input.width(), input.height(), 1.0F};
			
		StringBuilder builder = new StringBuilder();
		for(int i = 0; i < resolutions.length; i++)
		{
			if(i > 0)
				builder.append(",\n");
			float[] resolution = resolutions[i];
			builder.append("\t\t\tvec3(").append(resolution[0]).append(", ")
				.append(resolution[1]).append(", ").append(resolution[2])
				.append(")");
		}
		return builder.toString();
	}
	
	private static void reloadResources()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null)
			return;
		
		mc.execute(() -> mc.reloadResourcePacks());
	}
	
	private static void validateSupportedChannelUsage(String rawSource,
		List<ChannelInput> channelInputs, boolean missingChannelData)
	{
		Matcher matcher = CHANNEL_USAGE.matcher(rawSource);
		boolean usesAnyChannel = false;
		while(matcher.find())
		{
			usesAnyChannel = true;
			int channelIndex = Integer.parseInt(matcher.group(1));
			if(channelIndex >= 3)
				throw new IllegalArgumentException(
					"Shaders using iChannel3 are not supported yet. Up to 3 Shadertoy channels are supported.");
		}
		
		if(missingChannelData && usesAnyChannel)
			throw new IllegalArgumentException(
				"This shader uses external iChannel textures. Import it from a URL with a Shadertoy API key so Wurst can download the channel textures.");
		
		for(ChannelInput input : channelInputs)
			if(input.channel() >= 3)
				throw new IllegalArgumentException(
					"Shaders using iChannel3 are not supported yet. Up to 3 Shadertoy channels are supported.");
	}
	
	private static void writeChannelMetadata(List<ChannelInput> channelInputs)
		throws IOException
	{
		if(channelInputs.isEmpty())
		{
			Files.deleteIfExists(getMetadataPath());
			deleteDirectory(getChannelsFolder());
			return;
		}
		
		Files.createDirectories(getFolder());
		JsonObject root = new JsonObject();
		JsonArray channels = new JsonArray();
		for(ChannelInput input : channelInputs)
		{
			JsonObject channel = new JsonObject();
			channel.addProperty("channel", input.channel());
			channel.addProperty("file", input.fileName());
			channel.addProperty("width", input.width());
			channel.addProperty("height", input.height());
			channels.add(channel);
		}
		root.add("channels", channels);
		Files.writeString(getMetadataPath(), root.toString(),
			StandardCharsets.UTF_8);
	}
	
	private static void writeChannelTextures(List<ChannelInput> channelInputs)
		throws IOException
	{
		deleteDirectory(getChannelsFolder());
		if(channelInputs.isEmpty())
			return;
		
		Files.createDirectories(getChannelsFolder());
		for(ChannelInput input : channelInputs)
			Files.write(getChannelsFolder().resolve(input.fileName()),
				input.pngBytes());
	}
	
	private static List<ChannelInput> loadStoredChannelInputs()
		throws IOException
	{
		if(!Files.isRegularFile(getMetadataPath()))
			return List.of();
		
		JsonObject root = JsonParser
			.parseString(
				Files.readString(getMetadataPath(), StandardCharsets.UTF_8))
			.getAsJsonObject();
		JsonArray channels = root.getAsJsonArray("channels");
		if(channels == null)
			return List.of();
		
		List<ChannelInput> inputs = new ArrayList<>();
		for(JsonElement element : channels)
		{
			if(!element.isJsonObject())
				continue;
			JsonObject channel = element.getAsJsonObject();
			String fileName = channel.get("file").getAsString();
			Path file = getChannelsFolder().resolve(fileName);
			if(!Files.isRegularFile(file))
				continue;
			
			inputs.add(new ChannelInput(channel.get("channel").getAsInt(),
				fileName, channel.get("width").getAsInt(),
				channel.get("height").getAsInt(), Files.readAllBytes(file)));
		}
		return inputs;
	}
	
	private static void ensureChannelTexturesLoaded()
	{
		if(loadedChannelTextures[0] != null || loadedChannelTextures[1] != null
			|| loadedChannelTextures[2] != null)
			return;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc == null)
			return;
		
		try
		{
			for(ChannelInput input : loadStoredChannelInputs())
			{
				if(input.channel() < 0
					|| input.channel() >= loadedChannelTextures.length)
					continue;
				
				try(NativeImage image = NativeImage.read(input.pngBytes()))
				{
					DynamicTexture texture = new DynamicTexture(
						() -> "wurst_shadertoy_channel_" + input.channel(),
						image.mappedCopy(IntUnaryOperator.identity()));
					mc.getTextureManager().register(
						CUSTOM_CHANNEL_TEXTURE_IDS[input.channel()], texture);
					loadedChannelTextures[input.channel()] = texture;
				}
			}
		}catch(IOException e)
		{
			clearLoadedChannelTextures();
		}
	}
	
	private static AbstractTexture getBlankChannelTexture()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null)
			throw new IllegalStateException("Minecraft is not available.");
		
		if(blankChannelTexture == null)
		{
			try(NativeImage image = new NativeImage(1, 1, false))
			{
				image.setPixel(0, 0, 0xFF000000);
				blankChannelTexture =
					new DynamicTexture(() -> "wurst_shadertoy_blank_channel",
						image.mappedCopy(IntUnaryOperator.identity()));
				mc.getTextureManager().register(BLANK_CHANNEL_TEXTURE_ID,
					blankChannelTexture);
			}
		}
		
		return blankChannelTexture;
	}
	
	private static void clearLoadedChannelTextures()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc != null)
			for(int i = 0; i < CUSTOM_CHANNEL_TEXTURE_IDS.length; i++)
				mc.getTextureManager().release(CUSTOM_CHANNEL_TEXTURE_IDS[i]);
			
		for(int i = 0; i < loadedChannelTextures.length; i++)
		{
			if(loadedChannelTextures[i] != null)
				loadedChannelTextures[i].close();
			loadedChannelTextures[i] = null;
		}
	}
	
	private static void copyBundleToPreset(String presetName) throws IOException
	{
		Path presetMeta = getPresetsFolder().resolve(presetName + ".json");
		Path presetChannels =
			getPresetsFolder().resolve(presetName + "_channels");
		Files.deleteIfExists(presetMeta);
		deleteDirectory(presetChannels);
		
		if(Files.isRegularFile(getMetadataPath()))
			Files.copy(getMetadataPath(), presetMeta,
				StandardCopyOption.REPLACE_EXISTING);
		if(Files.isDirectory(getChannelsFolder()))
			copyDirectory(getChannelsFolder(), presetChannels);
	}
	
	private static void copyPresetBundle(Path presetPath) throws IOException
	{
		String presetName = getPresetDisplayName(presetPath);
		Path presetMeta = getPresetsFolder().resolve(presetName + ".json");
		Path presetChannels =
			getPresetsFolder().resolve(presetName + "_channels");
		
		Files.createDirectories(getFolder());
		if(Files.isRegularFile(presetMeta))
			Files.copy(presetMeta, getMetadataPath(),
				StandardCopyOption.REPLACE_EXISTING);
		else
			Files.deleteIfExists(getMetadataPath());
		
		deleteDirectory(getChannelsFolder());
		if(Files.isDirectory(presetChannels))
			copyDirectory(presetChannels, getChannelsFolder());
	}
	
	private static void copyDirectory(Path source, Path target)
		throws IOException
	{
		try(Stream<Path> stream = Files.walk(source))
		{
			for(Path sourcePath : stream.toList())
			{
				Path relative = source.relativize(sourcePath);
				Path targetPath = target.resolve(relative);
				if(Files.isDirectory(sourcePath))
					Files.createDirectories(targetPath);
				else
				{
					Files.createDirectories(targetPath.getParent());
					Files.copy(sourcePath, targetPath,
						StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
	
	private static void deleteDirectory(Path directory) throws IOException
	{
		if(!Files.isDirectory(directory))
			return;
		
		try(Stream<Path> stream = Files.walk(directory))
		{
			for(Path path : stream.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}
	
	private static NativeImage flippedCopy(NativeImage source,
		IntUnaryOperator pixelTransform)
	{
		NativeImage flipped =
			new NativeImage(source.getWidth(), source.getHeight(), false);
		for(int y = 0; y < source.getHeight(); y++)
			for(int x = 0; x < source.getWidth(); x++)
				flipped.setPixelABGR(x, source.getHeight() - 1 - y,
					pixelTransform.applyAsInt(source.getPixel(x, y)));
		return flipped;
	}
	
	private static String toPresetFileName(String name)
	{
		if(!isValidPresetName(name))
			throw new IllegalArgumentException(
				"Preset name contains invalid filename characters.");
		
		return name.trim() + ".glsl";
	}
	
	private record ImportedShader(String rawSource,
		List<ChannelInput> channelInputs)
	{}
	
	private record ChannelInput(int channel, String fileName, int width,
		int height, byte[] pngBytes)
	{}
}
