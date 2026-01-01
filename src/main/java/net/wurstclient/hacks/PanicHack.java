/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.wurstclient.Category;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.hack.HackList;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonArray;
import net.wurstclient.util.json.WsonObject;
import net.wurstclient.util.text.WText;

@SearchTags({"legit", "disable"})
@DontBlock
public final class PanicHack extends Hack
{
	private final Map<String, Boolean> savedHackStates = new LinkedHashMap<>();
	private final Path snapshotFile =
		WURST.getWurstFolder().resolve("panic-snapshot.json");
	private boolean startupRestorePending;
	
	private final ButtonSetting restoreButton = new ButtonSetting(
		"Restore saved hacks",
		WText.literal(
			"Re-enables the hacks that were active when Panic was triggered."),
		this::restoreSavedHacks);
	
	public PanicHack()
	{
		super("Panic");
		setCategory(Category.OTHER);
		addSetting(restoreButton);
		addPossibleKeybind("panic restore", "Restore hacks saved by Panic");
		loadSnapshotFromDisk();
	}
	
	@Override
	protected void onEnable()
	{
		int saved = snapshotHackStates();
		disableOtherHacks();
		setEnabled(false);
		
		if(saved > 0)
			ChatUtils
				.message("Disabled " + saved + " hack" + (saved == 1 ? "" : "s")
					+ ". Use \"Restore saved hacks\" to re-enable them.");
		else
			ChatUtils.message("No other hacks were enabled.");
	}
	
	private int snapshotHackStates()
	{
		savedHackStates.clear();
		int enabledCount = 0;
		
		for(Hack hack : WURST.getHax().getAllHax())
		{
			if(hack == this)
				continue;
			
			boolean enabled = hack.isEnabled();
			if(enabled)
				enabledCount++;
			
			savedHackStates.put(hack.getName(), enabled);
		}
		
		persistSavedHacks();
		startupRestorePending = false;
		return enabledCount;
	}
	
	private void disableOtherHacks()
	{
		for(Hack hack : WURST.getHax().getAllHax())
			if(hack.isEnabled() && hack != this)
				hack.setEnabled(false);
	}
	
	public void restoreSavedHacks()
	{
		if(savedHackStates.isEmpty())
		{
			ChatUtils.error("There is no saved Panic state to restore.");
			return;
		}
		
		HackList hax = WURST.getHax();
		Set<String> missing = new LinkedHashSet<>();
		Set<String> blocked = new LinkedHashSet<>();
		int enabledRestored = 0;
		int disabledRestored = 0;
		Map<String, Boolean> remainingStates = new LinkedHashMap<>();
		
		for(Map.Entry<String, Boolean> entry : new LinkedHashMap<>(
			savedHackStates).entrySet())
		{
			String name = entry.getKey();
			boolean targetEnabled = entry.getValue();
			
			Hack hack = hax.getHackByName(name);
			if(hack == null || hack == this)
			{
				missing.add(name);
				continue;
			}
			
			boolean wasEnabled = hack.isEnabled();
			
			if(targetEnabled)
			{
				hack.setEnabled(true);
				
				if(!hack.isEnabled())
				{
					blocked.add(name);
					remainingStates.put(name, targetEnabled);
					continue;
				}
				
				if(!wasEnabled && hack.isEnabled())
					enabledRestored++;
				
				continue;
			}
			
			hack.setEnabled(false);
			
			if(hack.isEnabled())
			{
				blocked.add(name);
				remainingStates.put(name, targetEnabled);
				continue;
			}
			
			if(wasEnabled)
				disabledRestored++;
		}
		
		savedHackStates.clear();
		savedHackStates.putAll(remainingStates);
		
		if(enabledRestored > 0 || disabledRestored > 0)
		{
			String message =
				buildRestoreMessage(enabledRestored, disabledRestored);
			ChatUtils.message(message);
			
		}else if(savedHackStates.isEmpty())
			ChatUtils
				.message("All hacks already matched the saved Panic state.");
		
		if(!missing.isEmpty())
			ChatUtils
				.warning("Missing Panic hacks: " + String.join(", ", missing));
		
		if(!blocked.isEmpty())
			ChatUtils.warning("Still blocked: " + String.join(", ", blocked));
		
		persistSavedHacks();
	}
	
	private String buildRestoreMessage(int enabledRestored,
		int disabledRestored)
	{
		StringBuilder sb = new StringBuilder("Restored Panic state");
		
		if(enabledRestored > 0)
		{
			sb.append(": enabled ").append(enabledRestored).append(" hack")
				.append(enabledRestored == 1 ? "" : "s");
			
			if(disabledRestored > 0)
				sb.append(", ");
		}
		
		if(disabledRestored > 0)
			sb.append(enabledRestored > 0 ? "" : ": ").append("disabled ")
				.append(disabledRestored).append(" hack")
				.append(disabledRestored == 1 ? "" : "s");
		
		return sb.append('.').toString();
	}
	
	public void handleStartupRestore()
	{
		if(!startupRestorePending)
			return;
		
		startupRestorePending = false;
		
		if(savedHackStates.isEmpty())
		{
			deleteSnapshotFile();
			return;
		}
		
		restoreSavedHacks();
	}
	
	private void loadSnapshotFromDisk()
	{
		startupRestorePending = false;
		savedHackStates.clear();
		
		if(!Files.exists(snapshotFile))
			return;
		
		try
		{
			WsonArray wson = JsonUtils.parseFileToArray(snapshotFile);
			loadSnapshotEntries(wson);
			
			if(savedHackStates.isEmpty())
				deleteSnapshotFile();
			else
				startupRestorePending = true;
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't load panic snapshot");
			e.printStackTrace();
			deleteSnapshotFile();
		}
	}
	
	private void loadSnapshotEntries(WsonArray wson) throws JsonException
	{
		for(int i = 0; i < wson.size(); i++)
		{
			JsonElement element = wson.getElement(i);
			
			if(element.isJsonObject())
			{
				WsonObject obj = new WsonObject(element.getAsJsonObject());
				String name = obj.getString("name", null);
				boolean enabled = obj.getBoolean("enabled", true);
				
				if(name != null)
					savedHackStates.put(name, enabled);
				
				continue;
			}
			
			if(element.isJsonPrimitive()
				&& element.getAsJsonPrimitive().isString())
				savedHackStates.put(element.getAsString(), true);
		}
	}
	
	private void persistSavedHacks()
	{
		if(savedHackStates.isEmpty())
		{
			deleteSnapshotFile();
			return;
		}
		
		JsonArray json = new JsonArray();
		savedHackStates.forEach((name, enabled) -> {
			JsonObject obj = new JsonObject();
			obj.addProperty("name", name);
			obj.addProperty("enabled", enabled);
			json.add(obj);
		});
		
		try
		{
			Files.createDirectories(snapshotFile.getParent());
			JsonUtils.toJson(json, snapshotFile);
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't save panic snapshot");
			e.printStackTrace();
		}
	}
	
	private void deleteSnapshotFile()
	{
		try
		{
			Files.deleteIfExists(snapshotFile);
			
		}catch(IOException e)
		{
			System.out.println("Couldn't delete panic snapshot");
			e.printStackTrace();
		}
	}
}
