/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.wurstclient.WurstClient;

/** Global, server-independent storage for custom item presets. */
public final class CustomPresetStore
{
	private static final Type LIST_TYPE = new TypeToken<List<PresetData>>()
	{}.getType();
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path file = WurstClient.INSTANCE.getWurstFolder()
		.resolve("lootsorter").resolve("custom_presets.json");
	
	public List<CustomItemFilterPreset> load()
	{
		if(!Files.exists(file))
			return new ArrayList<>();
		try
		{
			List<PresetData> data = gson.fromJson(
				Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
			if(data == null)
				return new ArrayList<>();
			return data.stream().map(PresetData::toPreset).toList();
		}catch(IOException | RuntimeException e)
		{
			return new ArrayList<>();
		}
	}
	
	public void save(List<CustomItemFilterPreset> presets) throws IOException
	{
		Files.createDirectories(file.getParent());
		List<PresetData> data = presets.stream().map(PresetData::from).toList();
		Files.writeString(file, gson.toJson(data, LIST_TYPE),
			StandardCharsets.UTF_8);
	}
	
	private record PresetData(String name, List<String> included,
		List<String> excluded, List<String> itemTags, Boolean enchanted,
		Boolean damaged, Integer minimumDurability, Integer minimumEnchantment,
		Boolean customNamed, String requiredEnchantment, String material,
		Boolean treasureEnchantment, Boolean curse)
	{
		static PresetData from(CustomItemFilterPreset preset)
		{
			ItemFilterModifiers m = preset.getModifiers();
			return new PresetData(preset.getName(),
				List.copyOf(preset.getIncluded()),
				List.copyOf(preset.getExcluded()),
				List.copyOf(preset.getItemTags()),
				m == null ? null : m.enchanted(),
				m == null ? null : m.damaged(),
				m == null ? null : m.minimumDurabilityPercent(),
				m == null ? null : m.minimumEnchantmentLevel(),
				m == null ? null : m.customNamed(),
				m == null ? null : m.requiredEnchantmentId(),
				m == null ? null : m.material(),
				m == null ? null : m.treasureEnchantment(),
				m == null ? null : m.curse());
		}
		
		CustomItemFilterPreset toPreset()
		{
			return new CustomItemFilterPreset(name,
				new java.util.LinkedHashSet<>(
					included == null ? List.of() : included),
				new java.util.LinkedHashSet<>(
					excluded == null ? List.of() : excluded),
				new java.util.LinkedHashSet<>(
					itemTags == null ? List.of() : itemTags),
				new ItemFilterModifiers(enchanted, damaged, minimumDurability,
					minimumEnchantment, customNamed, requiredEnchantment,
					material, treasureEnchantment, curse));
		}
	}
}
