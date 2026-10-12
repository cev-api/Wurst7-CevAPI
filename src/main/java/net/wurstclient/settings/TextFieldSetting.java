/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.TextFieldEditButton;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.text.WText;

public final class TextFieldSetting extends Setting
{
	private String value = "";
	private final String defaultValue;
	private final Predicate<String> validator;
	private int maxLength = Integer.MAX_VALUE;
	private boolean masked;
	private java.util.function.Consumer<String> changeListener = value -> {};
	
	public TextFieldSetting(String name, WText description, String defaultValue,
		Predicate<String> validator)
	{
		super(name, description);
		
		Objects.requireNonNull(defaultValue);
		Objects.requireNonNull(validator);
		if(!validator.test(defaultValue))
			throw new IllegalArgumentException(
				"Default value is not valid: " + defaultValue);
		
		value = defaultValue;
		this.defaultValue = defaultValue;
		this.validator = validator;
	}
	
	public TextFieldSetting(String name, String descriptionKey,
		String defaultValue, Predicate<String> validator)
	{
		this(name, WText.translated(descriptionKey), defaultValue, validator);
	}
	
	public TextFieldSetting(String name, String defaultValue,
		Predicate<String> validator)
	{
		this(name, WText.empty(), defaultValue, validator);
	}
	
	public TextFieldSetting(String name, WText description, String defaultValue)
	{
		this(name, description, defaultValue, s -> true);
	}
	
	public TextFieldSetting(String name, String descriptionKey,
		String defaultValue)
	{
		this(name, WText.translated(descriptionKey), defaultValue, s -> true);
	}
	
	public TextFieldSetting(String name, String defaultValue)
	{
		this(name, WText.empty(), defaultValue, s -> true);
	}
	
	/**
	 * @return this setting's value. Cannot be null.
	 */
	public String getValue()
	{
		return value;
	}
	
	public String getDefaultValue()
	{
		return defaultValue;
	}
	
	/**
	 * Sets this setting's value. Fails silently if the given value is invalid.
	 */
	public void setValue(String value)
	{
		if(value == null)
			return;
		
		if(this.value.equals(value))
			return;
		
		if(!validator.test(value))
			return;
		
		this.value = value;
		changeListener.accept(value);
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void setChangeListener(java.util.function.Consumer<String> listener)
	{
		changeListener = Objects.requireNonNull(listener);
	}
	
	public void setMasked(boolean masked)
	{
		this.masked = masked;
	}
	
	public boolean isMasked()
	{
		return masked;
	}
	
	public String getDisplayValue()
	{
		return masked ? "*".repeat(value.length()) : value;
	}
	
	public boolean isValidValue(String value)
	{
		if(value == null)
			return false;
		
		return validator.test(value);
	}
	
	public int getMaxLength()
	{
		return maxLength;
	}
	
	public void setMaxLength(int maxLength)
	{
		if(maxLength < 0)
			throw new IllegalArgumentException(
				"maxLength must not be negative");
		this.maxLength = maxLength;
	}
	
	public void resetToDefault()
	{
		value = defaultValue;
		changeListener.accept(value);
		WurstClient.INSTANCE.saveSettings();
	}
	
	@Override
	public Component getComponent()
	{
		return new TextFieldEditButton(this);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		// Older API-key buttons persisted null before becoming inline fields.
		if(masked && (json == null || json.isJsonNull()))
			return;
		try
		{
			String newValue = JsonUtils.getAsString(json);
			if(!validator.test(newValue))
				throw new JsonException();
			
			value = newValue;
			changeListener.accept(value);
			
		}catch(JsonException e)
		{
			e.printStackTrace();
			resetToDefault();
		}
	}
	
	@Override
	public JsonElement toJson()
	{
		return new JsonPrimitive(value);
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "TextField");
		json.addProperty("defaultValue", defaultValue);
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		return new LinkedHashSet<>();
	}
}
