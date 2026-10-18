/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Minimal strict JSON parser used for JWT headers and payloads.
 *
 * <p>
 * Gson's normal {@link com.google.gson.JsonParser} silently keeps the last
 * value when an object contains duplicate keys. Duplicate security-critical
 * claims must be rejected, so this parser tracks names itself.
 */
enum StrictJson
{
	;
	
	private static final int MAX_DEPTH = 32;
	private static final int MAX_JSON_LENGTH = 32 * 1024;
	private static final int MAX_STRING_LENGTH = 8 * 1024;
	private static final int MAX_NUMBER_LENGTH = 64;
	
	static JsonElement parse(String json) throws EntitlementException
	{
		if(json == null || json.isEmpty())
			throw new EntitlementException("The token contains empty JSON.");
		
		if(json.length() > MAX_JSON_LENGTH)
			throw new EntitlementException(
				"The token contains oversized JSON.");
		
		try(JsonReader reader = new JsonReader(new StringReader(json)))
		{
			reader.setLenient(false);
			JsonElement element = read(reader, 0);
			if(reader.peek() != JsonToken.END_DOCUMENT)
				throw new EntitlementException(
					"The token contains trailing JSON data.");
			return element;
			
		}catch(IOException | IllegalStateException | NumberFormatException e)
		{
			throw new EntitlementException("The token contains malformed JSON.",
				e);
		}
	}
	
	private static JsonElement read(JsonReader reader, int depth)
		throws IOException, EntitlementException
	{
		if(depth > MAX_DEPTH)
			throw new EntitlementException(
				"The token's JSON is nested too deeply.");
		
		JsonToken token = reader.peek();
		switch(token)
		{
			case BEGIN_OBJECT:
			return readObject(reader, depth);
			
			case BEGIN_ARRAY:
			return readArray(reader, depth);
			
			case STRING:
			{
				String value = reader.nextString();
				if(value.length() > MAX_STRING_LENGTH)
					throw new EntitlementException(
						"The token contains an oversized string.");
				return new JsonPrimitive(value);
			}
			
			case NUMBER:
			{
				String value = reader.nextString();
				if(value.length() > MAX_NUMBER_LENGTH)
					throw new EntitlementException(
						"The token contains an oversized number.");
				return new JsonPrimitive(new BigDecimal(value));
			}
			
			case BOOLEAN:
			return new JsonPrimitive(reader.nextBoolean());
			
			case NULL:
			reader.nextNull();
			return JsonNull.INSTANCE;
			
			default:
			throw new EntitlementException(
				"The token contains an unexpected JSON value.");
		}
	}
	
	private static JsonObject readObject(JsonReader reader, int depth)
		throws IOException, EntitlementException
	{
		JsonObject object = new JsonObject();
		reader.beginObject();
		while(reader.hasNext())
		{
			String name = reader.nextName();
			if(object.has(name))
				throw new EntitlementException(
					"The token contains duplicate claim '" + name + "'.");
			object.add(name, read(reader, depth + 1));
		}
		reader.endObject();
		return object;
	}
	
	private static JsonArray readArray(JsonReader reader, int depth)
		throws IOException, EntitlementException
	{
		JsonArray array = new JsonArray();
		reader.beginArray();
		while(reader.hasNext())
			array.add(read(reader, depth + 1));
		reader.endArray();
		return array;
	}
}
