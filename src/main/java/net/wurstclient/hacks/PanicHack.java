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
			"Turns Panic off and restores the hacks that were active when it was enabled."),
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
		// Hack#setEnabled already makes repeated enables a no-op. Keep the
		// snapshot alive for the entire Panic-on period so it cannot be
		// overwritten by another enable action.
		int saved = savedHackStates.isEmpty() ? snapshotHackStates()
			: countSavedEnabledHacks();
		disableOtherHacks();
		
		if(saved > 0)
			ChatUtils
				.message("Disabled " + saved + " hack" + (saved == 1 ? "" : "s")
					+ ". Use \"Restore saved hacks\" to re-enable them.");
		else
			ChatUtils.message("No other hacks were enabled.");
	}
	
	private int countSavedEnabledHacks()
	{
		return (int)savedHackStates.values().stream()
			.filter(Boolean::booleanValue).count();
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
		// The toggle is the authoritative restore action. This also makes the
		// settings button and `.panic restore` behave exactly like switching
		// Panic off.
		if(isEnabled())
		{
			setEnabled(false);
			return;
		}
		
		restoreSnapshot();
	}
	
	@Override
	protected void onDisable()
	{
		restoreSnapshot();
	}
	
	private void restoreSnapshot()
	{
		if(savedHackStates.isEmpty())
		{
			return;
		}
		
		HackList hax = WURST.getHax();
		Set<String> missing = new LinkedHashSet<>();
		Set<String> blocked = new LinkedHashSet<>();
		int enabledRestored = 0;
		int disabledRestored = 0;
		int disabledDuringPanic = 0;
		
		// Anything enabled while Panic was active is not part of the original
		// snapshot and must disappear before the saved state is reapplied.
		for(Hack hack : hax.getAllHax())
			if(hack != this && hack.isEnabled())
			{
				hack.setEnabled(false);
				if(!hack.isEnabled())
					disabledDuringPanic++;
			}
		
		for(Map.Entry<String, Boolean> entry : savedHackStates.entrySet())
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
				continue;
			}
			
			if(wasEnabled)
				disabledRestored++;
		}
		
		if(enabledRestored > 0 || disabledRestored > 0
			|| disabledDuringPanic > 0)
		{
			String message = buildRestoreMessage(enabledRestored,
				disabledRestored, disabledDuringPanic);
			ChatUtils.message(message);
		}else
			ChatUtils
				.message("All hacks already matched the saved Panic state.");
		
		if(!missing.isEmpty())
			ChatUtils
				.warning("Missing Panic hacks: " + String.join(", ", missing));
		
		if(!blocked.isEmpty())
			ChatUtils.warning("Still blocked: " + String.join(", ", blocked));
		
		savedHackStates.clear();
		startupRestorePending = false;
		persistSavedHacks();
	}
	
	private String buildRestoreMessage(int enabledRestored,
		int disabledRestored, int disabledDuringPanic)
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
		
		if(disabledDuringPanic > 0)
			sb.append(enabledRestored > 0 || disabledRestored > 0 ? ", " : ": ")
				.append("removed ").append(disabledDuringPanic).append(" hack")
				.append(disabledDuringPanic == 1 ? "" : "s")
				.append(" enabled during Panic");
		
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
