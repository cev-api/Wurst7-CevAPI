/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.SpacerComponent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@SearchTags({"auto sign"})
@DontSaveState
public final class AutoSignHack extends Hack implements UpdateListener
{
	private static final int MAX_LINES = 4;
	private static final int MAX_CHARS_PER_LINE = 15;
	
	private String[] signText;
	private final TextFieldSetting presetText = new TextFieldSetting(
		"Preset sign text",
		"Type the text to write on signs. It will be wrapped to 4 lines with up to 15 characters each.",
		"", AutoSignHack::canFitOnSign);
	private final CheckboxSetting signAura = new CheckboxSetting("Sign Aura",
		"Automatically edit any nearby sign with the preset text.", false);
	private final SliderSetting auraRange = new SliderSetting("Aura Range",
		"Max reach in blocks to scan for signs.", 4, 1, 6, 0.5,
		ValueDisplay.DECIMAL);
	private final SliderSetting auraDelay = new SliderSetting("Aura Delay",
		"Ticks to wait between each auto-edit attempt.", 1, 1, 40, 1,
		ValueDisplay.INTEGER);
	private final CheckboxSetting auraFeedback =
		new CheckboxSetting("Chat Feedback",
			"Log sign edits (old text → new text + coordinates).", false);
	private final CheckboxSetting auraThroughWalls = new CheckboxSetting(
		"HandNoClip reach",
		"Allow the aura to target signs through walls when HandNoClip is on.",
		false);
	
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
	
	private int auraTimer;
	private int auraRotation;
	
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
		addSetting(signAura);
		addSetting(auraRange);
		addSetting(auraDelay);
		addSetting(auraThroughWalls);
		addSetting(auraFeedback);
		addSetting(presetsSetting);
		presetsSetting.setVisibleInGui(false);
		updatePresetDropdown();
	}
	
	@Override
	protected void onEnable()
	{
		auraRotation = 0;
		auraTimer = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		signText = null;
		auraRotation = 0;
		auraTimer = 0;
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
	
	@Override
	public void onUpdate()
	{
		if(!signAura.isChecked() || MC.player == null || MC.level == null
			|| MC.screen != null)
			return;
		
		String[] newText = getSignText();
		if(newText == null)
			return;
		
		if(auraTimer > 0)
		{
			auraTimer--;
			return;
		}
		
		List<BlockPos> candidates = findNearbySigns();
		if(candidates.isEmpty())
			return;
		
		int index = Math.floorMod(auraRotation, candidates.size());
		auraRotation++;
		BlockPos target = candidates.get(index);
		
		SignBlockEntity signEntity =
			(SignBlockEntity)MC.level.getBlockEntity(target);
		
		if(signEntity == null)
		{
			auraTimer = Math.max(1, auraDelay.getValueI());
			return;
		}
		
		String[] oldText = readSign(signEntity);
		if(linesMatch(oldText, newText))
		{
			auraTimer = Math.max(1, auraDelay.getValueI());
			return;
		}
		
		Vec3 hitVec = Vec3.atCenterOf(target);
		BlockHitResult hitResult =
			new BlockHitResult(hitVec, Direction.UP, target, false);
		InteractionSimulator.rightClickBlock(hitResult,
			InteractionHand.MAIN_HAND);
		auraTimer = Math.max(1, auraDelay.getValueI());
		
		reportSignEdit(target, oldText, newText);
	}
	
	private List<BlockPos> findNearbySigns()
	{
		List<BlockPos> results = new ArrayList<>();
		if(MC.player == null || MC.level == null)
			return results;
		
		double range = auraRange.getValue();
		double rangeSq = range * range;
		Vec3 playerPos = MC.player.getEyePosition(1.0F);
		BlockPos playerBlock = MC.player.blockPosition();
		int radius = (int)Math.ceil(range);
		
		for(int dx = -radius; dx <= radius; dx++)
		{
			for(int dy = -radius; dy <= radius; dy++)
			{
				for(int dz = -radius; dz <= radius; dz++)
				{
					BlockPos candidate = playerBlock.offset(dx, dy, dz);
					Vec3 center = Vec3.atCenterOf(candidate);
					double distSq = playerPos.distanceToSqr(center);
					if(distSq > rangeSq)
						continue;
					
					BlockState state = MC.level.getBlockState(candidate);
					if(!(state.getBlock() instanceof SignBlock))
						continue;
					
					boolean visible = BlockUtils.hasLineOfSight(center);
					if(!visible && canUseHandNoClip())
						visible = true;
					if(!visible)
						continue;
					
					results.add(candidate);
				}
			}
		}
		
		return results;
	}
	
	private boolean linesMatch(String[] current, String[] desired)
	{
		if(current == null || desired == null)
			return current == desired;
		
		for(int i = 0; i < MAX_LINES; i++)
		{
			String existing = i < current.length ? current[i] : "";
			String wanted = i < desired.length ? desired[i] : "";
			if(!Objects.equals(existing, wanted))
				return false;
		}
		
		return true;
	}
	
	private boolean canUseHandNoClip()
	{
		if(!auraThroughWalls.isChecked())
			return false;
		
		HandNoClipHack handNoClip = WURST.getHax().handNoClipHack;
		return handNoClip != null && handNoClip.isEnabled();
	}
	
	private String[] readSign(SignBlockEntity sign)
	{
		String[] lines = new String[MAX_LINES];
		if(sign == null)
		{
			for(int i = 0; i < MAX_LINES; i++)
				lines[i] = "";
			return lines;
		}
		
		SignText signText = sign.getFrontText();
		for(int i = 0; i < MAX_LINES; i++)
		{
			net.minecraft.network.chat.Component component =
				signText == null ? null : signText.getMessage(i, false);
			lines[i] = component == null ? "" : component.getString();
		}
		
		return lines;
	}
	
	private void reportSignEdit(BlockPos pos, String[] oldLines,
		String[] newLines)
	{
		if(!auraFeedback.isChecked() || pos == null)
			return;
		
		String oldText = formatLines(oldLines);
		String newText = formatLines(newLines);
		String message = String.format("Sign Aura: %s -> %s at %d %d %d",
			oldText, newText, pos.getX(), pos.getY(), pos.getZ());
		ChatUtils.message(message);
	}
	
	private String formatLines(String[] lines)
	{
		if(lines == null)
			return "<empty>";
		
		StringJoiner joiner = new StringJoiner(" | ");
		for(String line : lines)
		{
			if(line == null || line.isEmpty())
				continue;
			joiner.add(line);
		}
		
		String text = joiner.toString();
		return text.isEmpty() ? "<empty>" : text;
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
