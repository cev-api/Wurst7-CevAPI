/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.resources.Identifier;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SoundListSetting;

public final class SoundMuteHack extends Hack
{
	private final SoundListSetting mutedSounds = new SoundListSetting(
		"Muted sounds", "Select individual registered sounds to mute.");
	
	public SoundMuteHack()
	{
		super("SoundMute");
		setCategory(Category.RENDER);
		addSetting(mutedSounds);
	}
	
	public boolean isMuted(Identifier id)
	{
		return isEnabled() && mutedSounds.contains(id);
	}
}
