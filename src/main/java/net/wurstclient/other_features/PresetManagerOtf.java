/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.options.PresetManagerScreen;
import net.wurstclient.other_feature.OtherFeature;

@SearchTags({"PresetManager", "preset manager", "Presets", "profiles"})
@DontBlock
public final class PresetManagerOtf extends OtherFeature
{
	public PresetManagerOtf()
	{
		super("Presets",
			"Manage full Wurst presets for hacks, UI, keybinds, and more.");
	}
	
	@Override
	public String getPrimaryAction()
	{
		return "Open Preset Manager";
	}
	
	@Override
	public void doPrimaryAction()
	{
		MC.setScreen(new PresetManagerScreen(MC.screen));
	}
}
