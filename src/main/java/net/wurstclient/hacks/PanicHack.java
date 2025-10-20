/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.LinkedHashSet;
import java.util.Set;

import net.wurstclient.Category;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.hack.HackList;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"legit", "disable"})
@DontBlock
public final class PanicHack extends Hack
{
	private final Set<String> savedHackNames = new LinkedHashSet<>();
	
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
		int restored = 0;
		
		for(String name : savedHackNames)
		{
			Hack hack = hax.getHackByName(name);
			if(hack == null || hack == this)
			{
				missing.add(name);
				continue;
			}
			
			boolean wasEnabled = hack.isEnabled();
			hack.setEnabled(true);
			if(!wasEnabled && hack.isEnabled())
				restored++;
		}
		
		if(!missing.isEmpty())
			savedHackNames.removeAll(missing);
		
		if(restored > 0)
			ChatUtils.message("Restored " + restored + " hack"
				+ (restored == 1 ? "" : "s") + " from Panic.");
		else
			ChatUtils.message(
				"All saved Panic hacks are already enabled or blocked.");
	}
}
