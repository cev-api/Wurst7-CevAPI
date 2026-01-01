/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.clickgui.components.SpacerComponent;
import net.wurstclient.clickgui.Component;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

@SearchTags({"auto sign"})
@DontSaveState
public final class AutoSignHack extends Hack
{
	private static final int MAX_LINES = 4;
	private static final int MAX_CHARS_PER_LINE = 15;
	
	private String[] signText;
	private final TextFieldSetting presetText = new TextFieldSetting(
		"Preset sign text",
		"Type the text to write on signs. It will be wrapped to 4 lines with up to 15 characters each.",
		"", AutoSignHack::canFitOnSign);
	
	// Preset management
	private final TextFieldSetting presetName = new TextFieldSetting(
		"Preset name", "", s -> s == null || s.trim().length() <= 64);
	private final StringDropdownSetting presetSelect =
		new StringDropdownSetting("Saved presets", "");
	private final ButtonSetting savePresetButton;
	private final ButtonSetting loadPresetButton;
	private final ButtonSetting deletePresetButton;
	private final ButtonSetting applyNowButton;
	
	// Hidden persistence backing for presets
	private final PresetsSetting presetsSetting = new PresetsSetting();
	
	public AutoSignHack()
	{
		super("AutoSign");
		setCategory(Category.BLOCKS);
		// Initialize buttons now that presetsSetting exists
		savePresetButton = new ButtonSetting("Save preset", () -> {
			String name = presetName.getValue() == null ? ""
				: presetName.getValue().trim();
			String text = presetText.getValue();
			if(name.isEmpty())
				name = suggestPresetName();
			if(text == null)
				text = "";
			String[] wrapped = wrapToSign(text);
			if(wrapped == null)
				return;
			presetsSetting.put(name, text);
			updatePresetDropdown();
			presetSelect.setSelected(name);
			applyTextNow();
		});
		loadPresetButton = new ButtonSetting("Load preset", () -> {
			String sel = presetSelect.getSelected();
			if(sel == null || sel.isEmpty())
				return;
			String text = presetsSetting.get(sel);
			if(text == null)
				return;
			if(presetText.isValidValue(text))
				presetText.setValue(text);
			applyTextNow();
		});
		deletePresetButton = new ButtonSetting("Delete preset", () -> {
			String sel = presetSelect.getSelected();
			if(sel == null || sel.isEmpty())
				return;
			presetsSetting.remove(sel);
			updatePresetDropdown();
			applyTextNow();
		});
		applyNowButton =
			new ButtonSetting("Apply text now", this::applyTextNow);
		
		addSetting(presetText);
		addSetting(presetName);
		addSetting(presetSelect);
		addSetting(savePresetButton);
		addSetting(loadPresetButton);
		addSetting(deletePresetButton);
		addSetting(applyNowButton);
		addSetting(presetsSetting);
		presetsSetting.setVisibleInGui(false);
		updatePresetDropdown();
	}
	
	@Override
	protected void onDisable()
	{
		signText = null;
	}
	
	public String[] getSignText()
	{
		if(!isEnabled())
			return null;
		// If a preset is selected in the dropdown, always prefer it.
		String selected = presetSelect.getSelected();
		if(selected != null && !selected.isEmpty())
		{
			String ptext = presetsSetting.get(selected);
			if(ptext != null && !ptext.isEmpty())
			{
				String[] wrappedSelected = wrapToSign(ptext);
				if(wrappedSelected != null)
					return wrappedSelected;
			}
		}
		
		// If we already captured a template from the first edited sign while
		// the
		// hack is enabled, use that for subsequent signs.
		if(signText != null)
			return signText;
		
		// Otherwise fall back to the persisted preset, if any.
		String preset = presetText.getValue();
		if(preset == null || preset.isEmpty())
			return null;
		
		String[] wrapped = wrapToSign(preset);
		return wrapped == null ? null : wrapped;
	}
	
	public void setSignText(String[] signText)
	{
		if(isEnabled() && this.signText == null)
			this.signText = signText;
	}
	
	// Validates whether the full text can be wrapped into a sign
	private static boolean canFitOnSign(String text)
	{
		if(text == null)
			return true; // allow empty
		String[] wrapped = wrapToSign(text);
		return wrapped != null;
	}
	
	// Wraps the given text into up to 4 lines, each max 15 chars. Respects
	// explicit newlines. Breaks long words when necessary. Returns null if it
	// can't fit into 4 lines.
	private static String[] wrapToSign(String text)
	{
		if(text == null)
			return null;
		
		String[] lines = new String[MAX_LINES];
		int lineIndex = 0;
		StringBuilder current = new StringBuilder();
		
		int i = 0;
		while(i < text.length())
		{
			char c = text.charAt(i);
			if(c == '\r')
			{
				// skip carriage returns
				i++;
				continue;
			}
			if(c == '\n')
			{
				// commit current line
				if(lineIndex >= MAX_LINES)
					return null;
				lines[lineIndex++] = current.toString();
				current.setLength(0);
				i++;
				continue;
			}
			
			// collect next word (sequence of non-space, non-newline)
			int start = i;
			while(i < text.length())
			{
				char ch = text.charAt(i);
				if(ch == ' ' || ch == '\n' || ch == '\r')
					break;
				i++;
			}
			String word = text.substring(start, i);
			
			// handle spaces between words
			boolean needSpace = current.length() > 0;
			
			// If the word is empty (multiple spaces), just add a space if
			// possible
			if(word.isEmpty())
			{
				if(needSpace)
				{
					if(current.length() + 1 <= MAX_CHARS_PER_LINE)
						current.append(' ');
					else
					{
						if(lineIndex >= MAX_LINES)
							return null;
						lines[lineIndex++] = current.toString();
						current.setLength(0);
					}
				}
				// skip extra spaces
				while(i < text.length() && text.charAt(i) == ' ')
					i++;
				continue;
			}
			
			// If the word itself is longer than the line width, hard-wrap it
			int pos = 0;
			while(pos < word.length())
			{
				int remainingInLine = MAX_CHARS_PER_LINE - current.length();
				int take = Math.min(remainingInLine, word.length() - pos);
				
				// If the word doesn't fit at all on an empty line, split
				if(remainingInLine == MAX_CHARS_PER_LINE
					&& word.length() - pos > MAX_CHARS_PER_LINE)
				{
					current.append(word, pos, pos + take);
					pos += take;
					if(lineIndex >= MAX_LINES)
						return null;
					lines[lineIndex++] = current.toString();
					current.setLength(0);
					continue;
				}
				
				// If the whole remaining word fits (with preceding space if
				// needed), place it
				if(needSpace && current.length() + 1 + word.length()
					- pos <= MAX_CHARS_PER_LINE)
				{
					current.append(' ').append(word.substring(pos));
					pos = word.length();
					break;
				}
				
				if(!needSpace && word.length() - pos <= remainingInLine)
				{
					current.append(word.substring(pos));
					pos = word.length();
					break;
				}
				
				// Otherwise, move to next line
				if(lineIndex >= MAX_LINES)
					return null;
				lines[lineIndex++] = current.toString();
				current.setLength(0);
				needSpace = false;
			}
			
			// skip spaces after the word (one space is handled above when
			// placing next word)
			while(i < text.length() && text.charAt(i) == ' ')
				i++;
		}
		
		// commit last line
		if(lineIndex >= MAX_LINES)
			return null;
		lines[lineIndex++] = current.toString();
		
		// Ensure array has exactly 4 entries
		for(int j = lineIndex; j < MAX_LINES; j++)
			lines[j] = "";
		
		// Validate lengths just to be safe
		for(int j = 0; j < MAX_LINES; j++)
			if(lines[j] != null && lines[j].length() > MAX_CHARS_PER_LINE)
				return null;
			
		return lines;
	}
	
	private void updatePresetDropdown()
	{
		presetSelect.setOptions(presetsSetting.names());
	}
	
	private String suggestPresetName()
	{
		int i = 1;
		while(true)
		{
			String n = "Preset " + i;
			if(!presetsSetting.contains(n))
				return n;
			i++;
		}
	}
	
	private void applyTextNow()
	{
		// Clear captured template so the new preset/text becomes effective on
		// next sign
		signText = null;
	}
	
	// Hidden Setting that persists name->text presets
	private final class PresetsSetting extends net.wurstclient.settings.Setting
	{
		private final LinkedHashMap<String, String> presets =
			new LinkedHashMap<>();
		
		private PresetsSetting()
		{
			super("Sign presets (internal)",
				net.wurstclient.util.text.WText.empty());
		}
		
		public void put(String name, String text)
		{
			if(name == null || text == null)
				return;
			presets.put(name, text);
			WURST.saveSettings();
		}
		
		public void remove(String name)
		{
			if(name == null)
				return;
			presets.remove(name);
			WURST.saveSettings();
		}
		
		public boolean contains(String name)
		{
			return presets.containsKey(name);
		}
		
		public java.util.Collection<String> names()
		{
			return presets.keySet().stream().collect(Collectors.toList());
		}
		
		public String get(String name)
		{
			return presets.get(name);
		}
		
		@Override
		public Component getComponent()
		{
			return new SpacerComponent(0, 0);
		}
		
		@Override
		public void fromJson(JsonElement json)
		{
			presets.clear();
			if(json != null && json.isJsonObject())
			{
				JsonObject obj = json.getAsJsonObject();
				for(Map.Entry<String, JsonElement> e : obj.entrySet())
				{
					String name = e.getKey();
					JsonElement v = e.getValue();
					if(name == null || !v.isJsonPrimitive())
						continue;
					String text = v.getAsString();
					presets.put(name, text);
				}
			}
			updatePresetDropdown();
		}
		
		@Override
		public JsonElement toJson()
		{
			JsonObject obj = new JsonObject();
			for(Map.Entry<String, String> e : presets.entrySet())
				obj.add(e.getKey(), new JsonPrimitive(e.getValue()));
			return obj;
		}
		
		@Override
		public JsonObject exportWikiData()
		{
			JsonObject json = new JsonObject();
			json.addProperty("name", getName());
			json.addProperty("description", getDescription());
			json.addProperty("type", "SignPresets");
			return json;
		}
		
		@Override
		public java.util.Set<net.wurstclient.keybinds.PossibleKeybind> getPossibleKeybinds(
			String featureName)
		{
			return new java.util.LinkedHashSet<>();
		}
	}
}
