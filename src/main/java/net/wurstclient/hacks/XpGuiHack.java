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
import net.wurstclient.xpgui.XpGuiScreen;

@DontSaveState
@DontBlock
@SearchTags({"xp gui", "xpgui", "windows xp gui", "start menu", "taskbar gui",
	"hack menu"})
public final class XpGuiHack extends Hack
{
	public XpGuiHack()
	{
		super("XPGUI");
	}
	
	@Override
	protected void onEnable()
	{
		if(!(MC.screen instanceof XpGuiScreen))
			MC.setScreen(new XpGuiScreen());
		
		setEnabled(false);
	}
}
