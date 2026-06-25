/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.navigator.NavigatorMainScreen;
import net.wurstclient.settings.CheckboxSetting;

@DontSaveState
@DontBlock
@SearchTags({"ClickGUI", "click gui", "SearchGUI", "search gui", "HackMenu",
	"hack menu"})
public final class NavigatorHack extends Hack
{
	public final CheckboxSetting backgroundOverlay =
		new CheckboxSetting("Background overlay",
			"Darkens the background when Navigator is open.", true);
	
	public NavigatorHack()
	{
		super("Navigator");
		addSetting(backgroundOverlay);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.gui == null)
		{
			setEnabled(false);
			return;
		}
		
		if(!(MC.gui.screen() instanceof NavigatorMainScreen))
			MC.gui.setScreen(new NavigatorMainScreen());
		
		setEnabled(false);
	}
	
	public boolean isBackgroundOverlayEnabled()
	{
		return backgroundOverlay.isChecked();
	}
}
