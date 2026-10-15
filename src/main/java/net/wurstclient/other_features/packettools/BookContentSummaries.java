/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * Shared bounded representation for book content found in packets or stacks.
 */
public final class BookContentSummaries
{
	private BookContentSummaries()
	{}
	
	/**
	 * Reads raw, unfiltered pages directly; no JSON codec or preview limits.
	 */
	public static Map<String, Object> fromStack(ItemStack stack)
	{
		WritableBookContent writable =
			stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
		if(stack.is(Items.WRITABLE_BOOK) && writable != null)
			return fromWritable(writable);
		WrittenBookContent written =
			stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if(written != null)
			return fromWritten(written);
		return writable == null ? null : fromWritable(writable);
	}
	
	public static Map<String, Object> fromComponent(Object content)
	{
		if(content instanceof WrittenBookContent written)
			return fromWritten(written);
		if(content instanceof WritableBookContent writable)
			return fromWritable(writable);
		return null;
	}
	
	public static boolean matchesStack(ItemStack stack, String expectedHash,
		int expectedPages, boolean signed)
	{
		if(expectedHash == null
			|| !stack.is(signed ? Items.WRITTEN_BOOK : Items.WRITABLE_BOOK))
			return false;
		List<String> pages;
		if(signed)
		{
			WrittenBookContent content =
				stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
			if(content == null || content.pages().size() != expectedPages)
				return false;
			pages = content.pages().stream().map(page -> page.raw().getString())
				.toList();
		}else
		{
			WritableBookContent content =
				stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
			if(content == null || content.pages().size() != expectedPages)
				return false;
			pages = content.pages().stream().map(page -> page.raw()).toList();
		}
		// The transfer path needs one combined hash, not per-page diagnostic
		// maps.
		MessageDigest combined = newSha256();
		for(String page : pages)
		{
			byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
			combined
				.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
			combined.update(bytes);
		}
		return expectedHash.equals(HexFormat.of().formatHex(combined.digest()));
	}
	
	public static Map<String, Object> fromWritten(WrittenBookContent content)
	{
		return fromStrings(
			content.pages().stream().map(page -> page.raw().getString())
				.toList(),
			content.title().raw(), content.author(), "SIGNED", true);
	}
	
	public static Map<String, Object> fromWritable(WritableBookContent content)
	{
		return fromStrings(
			content.pages().stream().map(page -> page.raw()).toList(), null,
			null, "UNSIGNED", true);
	}
	
	public static Map<String, Object> fromStrings(List<String> pages,
		String title, String author, String signedState, boolean complete)
	{
		List<Map<String, Object>> pageMetadata = new ArrayList<>();
		MessageDigest combined = newSha256();
		long chars = 0;
		long codePoints = 0;
		long utf8Bytes = 0;
		for(String page : pages)
		{
			byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
			chars += page.length();
			codePoints += page.codePointCount(0, page.length());
			utf8Bytes += bytes.length;
			combined
				.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
			combined.update(bytes);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("javaCharacterCount", page.length());
			metadata.put("unicodeCodePointCount",
				page.codePointCount(0, page.length()));
			metadata.put("utf8ByteCount", bytes.length);
			metadata.put("sha256", sha256(bytes));
			pageMetadata.add(metadata);
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("pageCount", pages.size());
		summary.put("pages", pageMetadata);
		summary.put("totalJavaCharacterCount", chars);
		summary.put("totalUnicodeCodePointCount", codePoints);
		summary.put("totalUtf8ByteCount", utf8Bytes);
		summary.put("combinedContentSha256",
			HexFormat.of().formatHex(combined.digest()));
		summary.put("titlePresent", title != null);
		if(title != null)
		{
			summary.put("titleCharacterCount", title.length());
			summary.put("title", title);
		}
		if(author != null)
			summary.put("author", author);
		summary.put("signedState", signedState);
		summary.put("completeDecoded", complete);
		summary.put("actualEncodedPacketSizeAvailable", false);
		summary.put("estimatedContentBytes", utf8Bytes);
		return summary;
	}
	
	public static Map<String, Object> fromJson(JsonElement component,
		String signedState)
	{
		JsonArray pageArray = findPages(component);
		List<String> pages = new ArrayList<>();
		if(pageArray != null)
			for(JsonElement page : pageArray)
				pages.add(pageText(page));
		String title = stringProperty(component, "title");
		String author = stringProperty(component, "author");
		return fromStrings(pages, title, author, signedState,
			pageArray != null && pages.size() == pageArray.size());
	}
	
	private static JsonArray findPages(JsonElement value)
	{
		if(value == null || !value.isJsonObject())
			return null;
		JsonObject object = value.getAsJsonObject();
		if(object.has("pages") && object.get("pages").isJsonArray())
			return object.getAsJsonArray("pages");
		for(Map.Entry<String, JsonElement> entry : object.entrySet())
		{
			JsonArray nested = findPages(entry.getValue());
			if(nested != null)
				return nested;
		}
		return null;
	}
	
	private static String stringProperty(JsonElement value, String key)
	{
		if(value == null || !value.isJsonObject())
			return null;
		JsonElement property = value.getAsJsonObject().get(key);
		if(property == null || property.isJsonNull())
			return null;
		if(property.isJsonPrimitive())
			return property.getAsString();
		if(property.isJsonObject())
		{
			JsonElement raw = property.getAsJsonObject().get("raw");
			if(raw != null && raw.isJsonPrimitive())
				return raw.getAsString();
		}
		return property.toString();
	}
	
	private static String pageText(JsonElement page)
	{
		if(page == null || page.isJsonNull())
			return "";
		if(page.isJsonPrimitive())
			return page.getAsString();
		if(page.isJsonObject())
		{
			JsonObject object = page.getAsJsonObject();
			if(object.has("raw"))
				return pageText(object.get("raw"));
			if(object.has("text") && object.get("text").isJsonPrimitive())
				return object.get("text").getAsString();
		}
		return page.toString();
	}
	
	private static MessageDigest newSha256()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}catch(NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
	
	private static String sha256(byte[] bytes)
	{
		return HexFormat.of().formatHex(newSha256().digest(bytes));
	}
}
