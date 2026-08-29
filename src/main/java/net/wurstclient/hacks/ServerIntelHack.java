/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.Screen;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.uiutils.UiUtilsCommandScannerScreen;

/** Opens the complete UI-Utils-style server intelligence dashboard. */
@SearchTags({"server intel", "plugin scanner", "legacy plugin scanner",
	"command scanner", "verbose server scan", "server scanner"})
public final class ServerIntelHack extends Hack
{
	public ServerIntelHack()
	{
		super("ServerIntel");
		setCategory(Category.INTEL);
	}
	
	@Override
	protected void onEnable()
	{
		Screen parent = MC.gui.screen();
		MC.gui.setScreen(new UiUtilsCommandScannerScreen(parent));
		setEnabled(false);
	}
}
