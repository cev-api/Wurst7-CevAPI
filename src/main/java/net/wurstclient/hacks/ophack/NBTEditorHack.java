/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.mojang.serialization.DataResult;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.NBTEditorScreen;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.text.WText;

public final class NBTEditorHack extends Hack
{
	private static final String EMPTY_ITEM =
		"{id:\"minecraft:cod\",count:1,components:{}}";
	private static final java.util.List<String> BUILT_IN_PRESET_NAMES =
		java.util.List.of("Charged Creeper Wand", "Fireball Wand",
			"Speed Hack Rod", "TNT Dropper", "Wither Wand",
			"Wurst7-CevAPI Legit OP Kit", "Wurst7-CevAPI OP Kit");
	private String editorText = formatNbt(EMPTY_ITEM);
	private String lastEditorMessage = "";
	private final PresetsSetting presets = new PresetsSetting();
	private final TextFieldSetting presetName = new TextFieldSetting(
		"Preset name", "", s -> s != null && s.trim().length() <= 64);
	
	public NBTEditorHack()
	{
		super("NBTEditor", "Read, edit, create, and apply item NBT.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(new ButtonSetting("Open NBT editor", this::openEditor));
		addSetting(presetName);
		addSetting(
			new ButtonSetting("Save held item preset", this::saveHeldPreset));
		addSetting(presets);
		presets.setVisibleInGui(false);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.getConnection() == null || MC.player == null)
		{
			setEnabled(false);
			return;
		}
		openEditor();
	}
	
	private void openEditor()
	{
		if(MC.getConnection() == null || MC.player == null)
		{
			setEnabled(false);
			return;
		}
		editorText = readHeldItem();
		MC.gui.setScreen(new NBTEditorScreen(MC.gui.screen(), this));
	}
	
	private void saveHeldPreset()
	{
		if(MC.player == null)
		{
			ChatUtils.error("No player is available.");
			return;
		}
		String name = presetName.getValue().trim();
		String held = readHeldItem();
		if(name.isEmpty())
		{
			lastEditorMessage = "Enter a preset name first.";
			return;
		}
		if(savePreset(name, held))
		{}
	}
	
	public String formatNbt(String text)
	{
		StringBuilder result = new StringBuilder();
		int indent = 0;
		boolean quoted = false;
		boolean escaped = false;
		for(int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if(quoted)
			{
				result.append(c);
				if(escaped)
					escaped = false;
				else if(c == '\\')
					escaped = true;
				else if(c == '"')
					quoted = false;
				continue;
			}
			if(c == '"')
			{
				quoted = true;
				result.append(c);
			}else if(c == '{' || c == '[')
			{
				result.append(c).append('\n');
				indent++;
				appendIndent(result, indent);
			}else if(c == '}' || c == ']')
			{
				trimTrailingSpace(result);
				result.append('\n');
				indent = Math.max(0, indent - 1);
				appendIndent(result, indent);
				result.append(c);
			}else if(c == ',')
			{
				trimTrailingSpace(result);
				result.append(',').append('\n');
				appendIndent(result, indent);
			}else if(c == ':')
			{
				trimTrailingSpace(result);
				result.append(": ");
			}else if(!Character.isWhitespace(c))
				result.append(c);
		}
		return result.toString();
	}
	
	private void appendIndent(StringBuilder result, int indent)
	{
		for(int i = 0; i < indent; i++)
			result.append("    ");
	}
	
	private void trimTrailingSpace(StringBuilder result)
	{
		while(result.length() > 0 && (result.charAt(result.length() - 1) == ' '
			|| result.charAt(result.length() - 1) == '\n'))
			result.setLength(result.length() - 1);
	}
	
	/**
	 * Removes whitespace outside quoted SNBT strings without changing values.
	 */
	public String minifyNbt(String text)
	{
		if(text == null || text.isEmpty())
			return "";
		StringBuilder result = new StringBuilder(text.length());
		boolean quoted = false;
		boolean escaped = false;
		for(int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if(quoted)
			{
				result.append(c);
				if(escaped)
					escaped = false;
				else if(c == '\\')
					escaped = true;
				else if(c == '"')
					quoted = false;
			}else if(c == '"')
			{
				quoted = true;
				result.append(c);
			}else if(!Character.isWhitespace(c))
				result.append(c);
		}
		return result.toString();
	}
	
	public String getEditorText()
	{
		return editorText;
	}
	
	public void setEditorText(String text)
	{
		editorText = text;
	}
	
	public String readHeldItem()
	{
		DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
			RegistryOps.create(NbtOps.INSTANCE, MC.player.registryAccess()),
			MC.player.getMainHandItem());
		Tag tag = encoded.result().orElse(null);
		if(tag == null)
		{
			lastEditorMessage = "Could not serialize held item: " + encoded
				.error().map(DataResult.Error::message).orElse("unknown error");
			return editorText;
		}
		editorText = formatNbt(new SnbtPrinterTagVisitor().visit(tag));
		lastEditorMessage = "Loaded held item.";
		return editorText;
	}
	
	public String newItem()
	{
		editorText = formatNbt(EMPTY_ITEM);
		lastEditorMessage = "Created new item.";
		return editorText;
	}
	
	public boolean savePreset(String name, String text)
	{
		if(name == null || name.trim().isEmpty())
		{
			lastEditorMessage = "Enter a preset name first.";
			return false;
		}
		String error = validatePreset(text);
		if(error != null)
		{
			lastEditorMessage = "Cannot save preset: " + error;
			return false;
		}
		presets.put(name.trim(), text.trim());
		lastEditorMessage = "Saved preset: " + name.trim();
		return true;
	}
	
	private CompoundTag parseItemTag(String text) throws Exception
	{
		try
		{
			return TagParser.parseCompoundFully(text);
		}catch(Exception snbtError)
		{
			try
			{
				JsonElement json = JsonParser.parseString(text);
				net.minecraft.nbt.Tag converted =
					JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
				if(converted instanceof CompoundTag compound)
					return compound;
			}catch(Exception ignored)
			{}
			throw snbtError;
		}
	}
	
	private String validatePreset(String text)
	{
		try
		{
			CompoundTag tag = parseItemTag(text);
			if(!(tag.get("id") instanceof StringTag))
				return "missing a string 'id' field";
			if(!(tag.get("count") instanceof NumericTag))
				return "missing a numeric 'count' field";
			return null;
		}catch(Exception e)
		{
			String message = e.getMessage();
			return message == null || message.isBlank() ? "invalid SNBT"
				: message;
		}
	}
	
	public java.util.Collection<String> presetNames()
	{
		return presets.names();
	}
	
	public void deletePreset(String name)
	{
		presets.delete(name);
	}
	
	public String loadPreset(String name)
	{
		String value = name == null ? null : presets.get(name.trim());
		if(value == null)
		{
			lastEditorMessage = "No saved preset named \"" + name + "\".";
			return null;
		}
		editorText = value;
		lastEditorMessage = "Loaded preset: " + name.trim();
		return value;
	}
	
	/**
	 * Matches Item Editor's raw-data path: parse SNBT first, then JSON, and
	 * retain Mojang's codec diagnostic instead of replacing it with an empty
	 * stack. This preserves registry errors inside nested components.
	 */
	private ItemDecodeResult decodeItem(String text)
	{
		String snbtError;
		try
		{
			ItemDecodeResult result =
				decodeTag(TagParser.parseCompoundFully(text));
			if(result.success())
				return result;
			snbtError = result.error();
		}catch(Exception e)
		{
			snbtError = errorMessage(e, "Invalid SNBT");
		}
		
		try
		{
			JsonElement json = JsonParser.parseString(text);
			Tag tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
			if(tag instanceof CompoundTag compound)
				return decodeTag(compound);
			return new ItemDecodeResult(ItemStack.EMPTY,
				"Item data must be an object.");
		}catch(Exception e)
		{
			return new ItemDecodeResult(ItemStack.EMPTY,
				snbtError + " | JSON: " + errorMessage(e, "invalid JSON"));
		}
	}
	
	private ItemDecodeResult decodeTag(Tag tag)
	{
		DataResult<ItemStack> result = ItemStack.CODEC.parse(
			RegistryOps.create(NbtOps.INSTANCE, MC.player.registryAccess()),
			tag);
		return result.result().map(stack -> new ItemDecodeResult(stack, null))
			.orElseGet(() -> new ItemDecodeResult(ItemStack.EMPTY,
				result.error().map(DataResult.Error::message).orElse(
					"Minecraft rejected this item-stack representation.")));
	}
	
	private String errorMessage(Exception e, String fallback)
	{
		String message = e.getMessage();
		return message == null || message.isBlank() ? fallback : message;
	}
	
	private record ItemDecodeResult(ItemStack stack, String error)
	{
		private boolean success()
		{
			return stack != null && !stack.isEmpty() && error == null;
		}
	}
	
	private void normalizeLegacyComponents(CompoundTag tag)
	{
		for(String key : tag.keySet())
		{
			Tag value = tag.get(key);
			if(value instanceof CompoundTag compound)
			{
				if(key.equals("minecraft:enchantments")
					&& !compound.contains("levels"))
				{
					CompoundTag levels = new CompoundTag();
					for(String enchantment : compound.keySet())
						if(!enchantment.equals("show_in_tooltip"))
							if(compound.get(enchantment) instanceof Tag level)
								levels.put(enchantment, level.copy());
					CompoundTag normalized = new CompoundTag();
					normalized.put("levels", levels);
					if(compound
						.get("show_in_tooltip") instanceof Tag showTooltip)
						normalized.put("show_in_tooltip", showTooltip.copy());
					tag.put(key, normalized);
					continue;
				}
				normalizeLegacyComponents(compound);
			}else if(value instanceof net.minecraft.nbt.ListTag list)
				for(Tag child : list)
					if(child instanceof CompoundTag childCompound)
						normalizeLegacyComponents(childCompound);
		}
	}
	
	public String validate(String text)
	{
		ItemDecodeResult result = decodeItem(text);
		return result.success() ? null : result.error();
	}
	
	public String getLastEditorMessage()
	{
		return lastEditorMessage;
	}
	
	public boolean apply(String text)
	{
		ItemDecodeResult decoded = decodeItem(text);
		if(!decoded.success())
		{
			lastEditorMessage = "Invalid item data: " + decoded.error();
			return false;
		}
		try
		{
			ItemStack stack = decoded.stack();
			if(!MC.player.hasInfiniteMaterials())
			{
				lastEditorMessage =
					"Creative mode is required to apply item NBT.";
				
				return false;
			}
			editorText = text;
			lastEditorMessage = "Applied item stack.";
			InventoryUtils.setCreativeStack(
				MC.player.getInventory().getSelectedSlot(), stack);
			return true;
		}catch(Exception e)
		{
			lastEditorMessage = "Could not apply item data: "
				+ errorMessage(e, "unknown error");
			
			return false;
		}
	}
	
	private boolean isValidItem(String text)
	{
		String error = validate(text);
		if(error != null)
		{
			ChatUtils.error(error);
			return false;
		}
		return true;
	}
	
	private final class PresetsSetting extends net.wurstclient.settings.Setting
	{
		private final Path folder =
			WurstClient.INSTANCE.getWurstFolder().resolve("nbt-presets");
		
		private PresetsSetting()
		{
			super("NBT presets (internal)", WText.empty());
			try
			{
				Files.createDirectories(folder);
			}catch(Exception e)
			{
				ChatUtils.error(
					"Could not create NBT preset folder: " + e.getMessage());
			}
		}
		
		java.util.Collection<String> names()
		{
			java.util.Set<String> names =
				new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
			names.addAll(BUILT_IN_PRESET_NAMES);
			try(java.util.stream.Stream<Path> paths = Files.list(folder))
			{
				paths
					.filter(
						path -> path.getFileName().toString().endsWith(".snbt"))
					.map(path -> path.getFileName().toString().substring(0,
						path.getFileName().toString().length() - 5))
					.forEach(names::add);
			}catch(Exception ignored)
			{}
			return java.util.List.copyOf(names);
		}
		
		void put(String name, String value)
		{
			try
			{
				Files.createDirectories(folder);
				Files.writeString(folder.resolve(fileName(name)), value,
					StandardCharsets.UTF_8);
				
			}catch(Exception e)
			{
				lastEditorMessage = "Could not save preset: " + e.getMessage();
			}
		}
		
		String get(String name)
		{
			try
			{
				Path savedPreset = folder.resolve(fileName(name));
				if(Files.isRegularFile(savedPreset))
					return Files.readString(savedPreset,
						StandardCharsets.UTF_8);
			}catch(Exception ignored)
			{}
			return getBuiltInPreset(name);
		}
		
		private String getBuiltInPreset(String name)
		{
			if(name == null || !BUILT_IN_PRESET_NAMES.contains(name.trim()))
				return null;
			String resource = "/assets/wurst/nbt-presets/" + fileName(name);
			try(java.io.InputStream input =
				NBTEditorHack.class.getResourceAsStream(resource))
			{
				return input == null ? null
					: new String(input.readAllBytes(), StandardCharsets.UTF_8);
			}catch(Exception ignored)
			{
				return null;
			}
		}
		
		void delete(String name)
		{
			try
			{
				Files.deleteIfExists(folder.resolve(fileName(name)));
			}catch(Exception e)
			{
				ChatUtils
					.error("Could not delete NBT preset: " + e.getMessage());
			}
		}
		
		private String fileName(String name)
		{
			String safe = name.trim().replaceAll("[<>:\"/\\\\|?*]", "_");
			return safe + ".snbt";
		}
		
		@Override
		public net.wurstclient.clickgui.Component getComponent()
		{
			return new net.wurstclient.clickgui.components.SpacerComponent(0,
				0);
		}
		
		@Override
		public void resetToDefault()
		{
			// Presets are stored as files.
		}
		
		@Override
		public void fromJson(JsonElement json)
		{
			// Presets are stored as files.
		}
		
		@Override
		public JsonElement toJson()
		{
			return new JsonObject();
		}
		
		@Override
		public JsonObject exportWikiData()
		{
			JsonObject result = new JsonObject();
			result.addProperty("name", getName());
			result.addProperty("description", getDescription());
			result.addProperty("type", "NBTPresets");
			return result;
		}
		
		@Override
		public java.util.Set<net.wurstclient.keybinds.PossibleKeybind> getPossibleKeybinds(
			String featureName)
		{
			return new java.util.LinkedHashSet<>();
		}
	}
}
