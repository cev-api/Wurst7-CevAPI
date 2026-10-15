/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Immutable, deterministic description of the item data visible to this client.
 */
public final class ItemStackForensics
{
	private static final Gson GSON =
		new GsonBuilder().disableHtmlEscaping().create();
	
	private ItemStackForensics()
	{}
	
	public static Snapshot capture(ItemStack stack, boolean includeComponents,
		boolean includeFullContent, int maxContentBytes)
	{
		Minecraft mc = Minecraft.getInstance();
		return capture(stack, includeComponents, includeFullContent,
			maxContentBytes, mc == null || mc.player == null ? null
				: mc.player.registryAccess());
	}
	
	public static Snapshot capture(ItemStack stack, boolean includeComponents,
		boolean includeFullContent, int maxContentBytes,
		net.minecraft.core.HolderLookup.Provider registries)
	{
		if(stack == null || stack.isEmpty())
		{
			Map<String, Object> empty = new LinkedHashMap<>();
			empty.put("item", "minecraft:air");
			empty.put("count", 0);
			String canonical = GSON.toJson(empty);
			return new Snapshot("minecraft:air", 0, empty, Map.of(),
				sha256("minecraft:air"), sha256(canonical), sha256(""),
				canonical);
		}
		
		String itemId =
			BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		Map<String, Object> details =
			RegistryDecoder.decodeItemStackDetailed(stack, 0, null);
		details.put("maxStackSize", stack.getMaxStackSize());
		details.put("maxDamage", stack.getMaxDamage());
		if(stack.isDamageableItem())
		{
			details.put("damage", stack.getDamageValue());
			details.put("durabilityRemaining",
				Math.max(0, stack.getMaxDamage() - stack.getDamageValue()));
		}
		
		Map<String, Object> components = new TreeMap<>();
		if(includeComponents)
		{
			try
			{
				for(TypedDataComponent<?> component : stack.getComponents())
				{
					String id = BuiltInRegistries.DATA_COMPONENT_TYPE
						.getKey(component.type()).toString();
					Map<String, Object> value = new LinkedHashMap<>();
					value.put("state", "PRESENT");
					value.put("runtimeType",
						component.value().getClass().getName());
					Map<String, Object> bookSummary =
						BookContentSummaries.fromComponent(component.value());
					if(bookSummary != null)
						value.put("bookContentSummary", bookSummary);
					if(registries == null)
						value.put("state", "UNAVAILABLE");
					else
					{
						var encoded = component.encodeValue(registries
							.createSerializationContext(JsonOps.INSTANCE));
						JsonElement json =
							encoded.resultOrPartial().orElse(null);
						if(json == null)
							value.put("state", "UNAVAILABLE");
						else
						{
							String canonicalValue =
								GSON.toJson(normalize(json));
							value.put("serializedChars",
								canonicalValue.length());
							value.put("valueSha256", sha256(canonicalValue));
							int byteLength = canonicalValue
								.getBytes(StandardCharsets.UTF_8).length;
							value.put("serializedBytes", byteLength);
							if(includeFullContent
								&& byteLength <= maxContentBytes)
								value.put("value", json);
							else if(includeFullContent)
							{
								value.put("valueState", "OMITTED_BY_LIMIT");
								value.put("omittedBytesLimit", maxContentBytes);
							}
						}
					}
					components.put(id, value);
				}
			}catch(RuntimeException e)
			{
				components.put("_decodeError", Map.of("state", "UNAVAILABLE",
					"message", e.getClass().getSimpleName()));
			}
		}
		details.put("components", components);
		// ### MODIFIED ### Hash semantic item data, not logging representation.
		Map<String, Object> semantic = new LinkedHashMap<>();
		semantic.put("itemId", itemId);
		semantic.put("count", stack.getCount());
		// Display names, decoded previews and diagnostics are not semantic
		// state. Damage, names, enchantments, etc. are covered by components.
		Map<String, Object> componentFingerprints = new TreeMap<>();
		components.forEach((id, value) -> {
			if(!(value instanceof Map<?, ?> component))
				return;
			Map<String, Object> comparable = new LinkedHashMap<>();
			comparable.put("state", component.get("state"));
			comparable.put("valueSha256", component.get("valueSha256"));
			componentFingerprints.put(id, comparable);
		});
		semantic.put("components", componentFingerprints);
		String canonical = GSON.toJson(normalize(semantic));
		String itemFingerprint = sha256(itemId);
		String fingerprint = sha256(canonical);
		String content = "";
		for(String key : List.of("minecraft:written_book_content",
			"minecraft:writable_book_content", "minecraft:container",
			"minecraft:bundle_contents"))
		{
			if(components.get(key) instanceof Map<?, ?> component)
				content += key + "=" + component.get("valueSha256");
		}
		return new Snapshot(itemId, stack.getCount(), details, components,
			itemFingerprint, fingerprint, sha256(content), canonical);
	}
	
	private static Object normalize(Object value)
	{
		if(value instanceof Map<?, ?> map)
		{
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((key, item) -> sorted.put(String.valueOf(key),
				normalize(item)));
			return sorted;
		}
		if(value instanceof Collection<?> collection)
		{
			List<Object> normalized = new ArrayList<>(collection.size());
			collection.forEach(item -> normalized.add(normalize(item)));
			return normalized;
		}
		if(value instanceof JsonObject object)
		{
			Map<String, Object> sorted = new TreeMap<>();
			object.entrySet()
				.forEach(e -> sorted.put(e.getKey(), normalize(e.getValue())));
			return sorted;
		}
		if(value instanceof JsonArray array)
		{
			List<Object> normalized = new ArrayList<>(array.size());
			array.forEach(item -> normalized.add(normalize(item)));
			return normalized;
		}
		if(value instanceof JsonPrimitive primitive)
		{
			if(primitive.isBoolean())
				return primitive.getAsBoolean();
			if(primitive.isNumber())
				return primitive.getAsNumber();
			return primitive.getAsString();
		}
		return value;
	}
	
	static String sha256(String value)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}catch(NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
	
	private static Map<String, Object> immutableMap(Map<String, Object> source)
	{
		Map<String, Object> copy = new LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(key, immutableValue(value)));
		return Collections.unmodifiableMap(copy);
	}
	
	private static Object immutableValue(Object value)
	{
		if(value instanceof Map<?, ?> map)
		{
			Map<String, Object> copy = new LinkedHashMap<>();
			map.forEach((key, child) -> copy.put(String.valueOf(key),
				immutableValue(child)));
			return Collections.unmodifiableMap(copy);
		}
		if(value instanceof Collection<?> collection)
		{
			List<Object> copy = new ArrayList<>(collection.size());
			collection.forEach(child -> copy.add(immutableValue(child)));
			return Collections.unmodifiableList(copy);
		}
		if(value instanceof JsonElement json)
			return immutableValue(normalize(json));
		return value;
	}
	
	static String fingerprintCanonical(Object value)
	{
		return sha256(GSON.toJson(normalize(value)));
	}
	
	public record Snapshot(String itemId, int count,
		Map<String, Object> details, Map<String, Object> components,
		String identityFingerprint, String fingerprint,
		String contentFingerprint, String canonicalJson)
	{
		public Snapshot
		{
			details = immutableMap(details);
			components = immutableMap(components);
		}
	}
}
