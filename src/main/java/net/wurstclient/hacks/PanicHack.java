/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.JsonArray;

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
import net.wurstclient.util.text.WText;

@SearchTags({"legit", "disable"})
@DontBlock
public final class PanicHack extends Hack
{
	private final Set<String> savedHackNames = new LinkedHashSet<>();
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
		int saved = snapshotEnabledHacks();
		disableOtherHacks();
		setEnabled(false);
		
		if(saved > 0)
			ChatUtils
				.message("Disabled " + saved + " hack" + (saved == 1 ? "" : "s")
					+ ". Use \"Restore saved hacks\" to re-enable them.");
		else
			ChatUtils.message("No other hacks were enabled.");
	}
	
	private int snapshotEnabledHacks()
	{
		savedHackNames.clear();
		
		for(Hack hack : WURST.getHax().getAllHax())
			if(hack.isEnabled() && hack != this)
				savedHackNames.add(hack.getName());
			
		persistSavedHacks();
		startupRestorePending = false;
		return savedHackNames.size();
	}
	
	private void disableOtherHacks()
	{
		for(Hack hack : WURST.getHax().getAllHax())
			if(hack.isEnabled() && hack != this)
				hack.setEnabled(false);
	}
	
	public void restoreSavedHacks()
	{
		if(savedHackNames.isEmpty())
		{
			ChatUtils.error("There is no saved Panic state to restore.");
			return;
		}
		
		HackList hax = WURST.getHax();
		Set<String> missing = new LinkedHashSet<>();
		Set<String> blocked = new LinkedHashSet<>();
		int restored = 0;
		
		for(String name : new LinkedHashSet<>(savedHackNames))
		{
			Hack hack = hax.getHackByName(name);
			if(hack == null || hack == this)
			{
				missing.add(name);
				continue;
			}
			
			boolean wasEnabled = hack.isEnabled();
			hack.setEnabled(true);
			
			if(!hack.isEnabled())
			{
				blocked.add(name);
				continue;
			}
			
			if(!wasEnabled && hack.isEnabled())
				restored++;
		}
		
		savedHackNames.clear();
		savedHackNames.addAll(blocked);
		
		if(restored > 0)
			ChatUtils.message("Restored " + restored + " hack"
				+ (restored == 1 ? "" : "s") + " from Panic.");
		else if(savedHackNames.isEmpty())
			ChatUtils.message("All saved Panic hacks are already enabled.");
		
		if(!missing.isEmpty())
			ChatUtils
				.warning("Missing Panic hacks: " + String.join(", ", missing));
		
		if(!savedHackNames.isEmpty())
			ChatUtils
				.warning("Still blocked: " + String.join(", ", savedHackNames));
		
		persistSavedHacks();
	}
	
	public void handleStartupRestore()
	{
		if(!startupRestorePending)
			return;
		
		startupRestorePending = false;
		
		if(savedHackNames.isEmpty())
		{
			deleteSnapshotFile();
			return;
		}
		
		restoreSavedHacks();
	}
	
	private void loadSnapshotFromDisk()
	{
		startupRestorePending = false;
		savedHackNames.clear();
		
		if(!Files.exists(snapshotFile))
			return;
		
		try
		{
			WsonArray wson = JsonUtils.parseFileToArray(snapshotFile);
			savedHackNames.addAll(wson.getAllStrings());
			
			if(savedHackNames.isEmpty())
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
	
	private void persistSavedHacks()
	{
		if(savedHackNames.isEmpty())
		{
			deleteSnapshotFile();
			return;
		}
		
		JsonArray json = new JsonArray();
		savedHackNames.forEach(json::add);
		
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
