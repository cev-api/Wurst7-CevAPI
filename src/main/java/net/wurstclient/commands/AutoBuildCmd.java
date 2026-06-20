/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.List;
import net.wurstclient.Category;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.AutoBuildHack;
import net.wurstclient.util.ChatUtils;

public final class AutoBuildCmd extends Command
{
	public AutoBuildCmd()
	{
		super("autobuild",
			"Selects an AutoBuild template by file name and enables AutoBuild.",
			".autobuild <template>");
		setCategory(Category.OTHER);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0)
			throw new CmdSyntaxError();
		
		AutoBuildHack autoBuildHack = WURST.getHax().autoBuildHack;
		String templateName = String.join(" ", args);
		if(!autoBuildHack.selectTemplateByName(templateName))
		{
			List<String> available = autoBuildHack.getAvailableTemplateNames();
			String suffix = available.isEmpty() ? ""
				: " Available templates: " + String.join(", ", available);
			throw new CmdError("No AutoBuild template named \"" + templateName
				+ "\"." + suffix);
		}
		
		if(!autoBuildHack.isEnabled())
			autoBuildHack.setEnabled(true);
		
		ChatUtils.message("AutoBuild ready with template \"" + templateName
			+ "\". Right-click to start building.");
	}
}
