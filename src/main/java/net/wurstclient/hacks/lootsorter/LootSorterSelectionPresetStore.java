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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.wurstclient.WurstClient;

/**
 * Persists reusable source and destination selections independently. Keeping
 * the two collections in one document makes replacing one kind preserve every
 * preset of the other kind.
 */
public final class LootSorterSelectionPresetStore
{
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path file = WurstClient.INSTANCE.getWurstFolder()
		.resolve("lootsorter").resolve("selection-presets.json");
	
	public Presets load()
	{
		if(!Files.exists(file))
			return Presets.empty();
		try
		{
			Presets presets = gson.fromJson(
				Files.readString(file, StandardCharsets.UTF_8), Presets.class);
			return presets == null ? Presets.empty() : presets;
		}catch(IOException | RuntimeException e)
		{
			return Presets.empty();
		}
	}
	
	public void save(Presets presets) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file,
			gson.toJson(presets == null ? Presets.empty() : presets),
			StandardCharsets.UTF_8);
	}
	
	public Optional<SourcePreset> findSource(String name)
	{
		return load().sources().stream()
			.filter(preset -> preset.name().equalsIgnoreCase(name)).findFirst();
	}
	
	public Optional<DestinationPreset> findDestination(String name)
	{
		return load().destinations().stream()
			.filter(preset -> preset.name().equalsIgnoreCase(name)).findFirst();
	}
	
	public void saveSource(SourcePreset preset) throws IOException
	{
		Presets current = load();
		List<SourcePreset> sources = new ArrayList<>(current.sources());
		sources.removeIf(
			existing -> existing.name().equalsIgnoreCase(preset.name()));
		sources.add(preset);
		save(new Presets(sources, current.destinations()));
	}
	
	public void saveDestination(DestinationPreset preset) throws IOException
	{
		Presets current = load();
		List<DestinationPreset> destinations =
			new ArrayList<>(current.destinations());
		destinations.removeIf(
			existing -> existing.name().equalsIgnoreCase(preset.name()));
		destinations.add(preset);
		save(new Presets(current.sources(), destinations));
	}
	
	public boolean deleteSource(String name) throws IOException
	{
		Presets current = load();
		List<SourcePreset> sources = new ArrayList<>(current.sources());
		boolean removed =
			sources.removeIf(preset -> preset.name().equalsIgnoreCase(name));
		if(removed)
			save(new Presets(sources, current.destinations()));
		return removed;
	}
	
	public boolean deleteDestination(String name) throws IOException
	{
		Presets current = load();
		List<DestinationPreset> destinations =
			new ArrayList<>(current.destinations());
		boolean removed = destinations
			.removeIf(preset -> preset.name().equalsIgnoreCase(name));
		if(removed)
			save(new Presets(current.sources(), destinations));
		return removed;
	}
	
	public record Presets(List<SourcePreset> sources,
		List<DestinationPreset> destinations)
	{
		public Presets
		{
			sources = sources == null ? List.of() : List.copyOf(sources);
			destinations =
				destinations == null ? List.of() : List.copyOf(destinations);
		}
		
		static Presets empty()
		{
			return new Presets(List.of(), List.of());
		}
	}
	
	public record SourcePreset(String name, String serverIdentifier,
		String dimensionKey, String dimensionType,
		List<LootSorterProfile.ContainerPos> sources,
		List<SourceContentsSnapshot> sourceContents)
	{
		public SourcePreset
		{
			sources = sources == null ? List.of() : List.copyOf(sources);
			sourceContents = sourceContents == null ? List.of()
				: List.copyOf(sourceContents);
		}
	}
	
	public record DestinationPreset(String name, String serverIdentifier,
		String dimensionKey, String dimensionType,
		List<LootSorterProfile.DestinationProfile> destinations)
	{
		public DestinationPreset
		{
			destinations =
				destinations == null ? List.of() : List.copyOf(destinations);
		}
	}
}
