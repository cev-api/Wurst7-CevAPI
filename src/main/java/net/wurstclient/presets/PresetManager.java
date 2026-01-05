/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.presets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.wurstclient.WurstClient;

public final class PresetManager
{
	private static final List<String> PRESET_FILES = List.of(
		"enabled-hacks.json", "favourites.json", "settings.json",
		"keybinds.json", "windows.json", "preferences.json", "toomanyhax.json");
	
	private final WurstClient wurst;
	private final Path wurstFolder;
	private final Path presetsFolder;
	
	public PresetManager(WurstClient wurst, Path wurstFolder)
	{
		this.wurst = wurst;
		this.wurstFolder = wurstFolder;
		presetsFolder = wurstFolder.resolve("presets");
	}
	
	public Path getPresetsFolder()
	{
		return presetsFolder;
	}
	
	public ArrayList<Path> listPresets()
	{
		if(!Files.isDirectory(presetsFolder))
			return new ArrayList<>();
		
		try(Stream<Path> files = Files.list(presetsFolder))
		{
			return files.filter(Files::isDirectory)
				.collect(Collectors.toCollection(ArrayList::new));
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public void savePreset(String name) throws IOException
	{
		Path presetFolder = getPresetFolder(name);
		Files.createDirectories(presetFolder);
		
		saveCurrentState();
		copyCurrentFilesToPreset(presetFolder);
	}
	
	public void seedBundledPresetIfNone(String name) throws IOException
	{
		if(name == null || name.isBlank())
			return;
		
		if(!listPresets().isEmpty())
			return;
		
		installBundledPreset(name);
	}
	
	public void installBundledPreset(String name) throws IOException
	{
		if(name == null || name.isBlank())
			return;
		
		Path presetFolder = getPresetFolder(name);
		if(Files.isDirectory(presetFolder))
			return;
		
		boolean copiedAny = false;
		for(String fileName : PRESET_FILES)
		{
			String resourcePath = "/wurst/presets/" + name + "/" + fileName;
			try(InputStream in =
				PresetManager.class.getResourceAsStream(resourcePath))
			{
				if(in == null)
					continue;
				
				if(!copiedAny)
					Files.createDirectories(presetFolder);
				
				Files.copy(in, presetFolder.resolve(fileName),
					StandardCopyOption.REPLACE_EXISTING);
				copiedAny = true;
			}
		}
	}
	
	public void loadPreset(String name) throws IOException
	{
		Path presetFolder = getPresetFolder(name);
		if(!Files.isDirectory(presetFolder))
			throw new NoSuchFileException(name);
		
		copyPresetFilesToCurrent(presetFolder);
		reloadCurrentState();
	}
	
	public void deletePreset(String name) throws IOException
	{
		Path presetFolder = getPresetFolder(name);
		if(!Files.isDirectory(presetFolder))
			throw new NoSuchFileException(name);
		
		try(Stream<Path> walk = Files.walk(presetFolder))
		{
			List<Path> paths = walk.sorted(
				(a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()))
				.collect(Collectors.toList());
			for(Path path : paths)
				Files.deleteIfExists(path);
		}
	}
	
	private Path getPresetFolder(String name)
	{
		return presetsFolder.resolve(name);
	}
	
	private void saveCurrentState()
	{
		wurst.saveSettings();
		wurst.getHax().saveEnabledHax();
		wurst.getHax().saveFavoriteHax();
		wurst.getKeybinds().save();
	}
	
	private void reloadCurrentState()
	{
		wurst.reloadSettings();
		wurst.getHax().tooManyHaxHack.loadBlockedHacksFile();
		wurst.getHax().reloadEnabledHax();
		wurst.getHax().reloadFavoriteHax();
		wurst.getKeybinds().reload();
		wurst.getNavigator().reloadPreferences();
		wurst.getGui().init();
	}
	
	private void copyCurrentFilesToPreset(Path presetFolder) throws IOException
	{
		for(String fileName : PRESET_FILES)
		{
			Path source = wurstFolder.resolve(fileName);
			Path target = presetFolder.resolve(fileName);
			copyIfExists(source, target);
		}
	}
	
	private void copyPresetFilesToCurrent(Path presetFolder) throws IOException
	{
		for(String fileName : PRESET_FILES)
		{
			Path source = presetFolder.resolve(fileName);
			Path target = wurstFolder.resolve(fileName);
			copyIfExists(source, target);
		}
	}
	
	private void copyIfExists(Path source, Path target) throws IOException
	{
		if(!Files.exists(source))
			return;
		
		Files.createDirectories(target.getParent());
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
	}
}
