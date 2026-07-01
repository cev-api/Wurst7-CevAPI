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
import net.wurstclient.util.AutoBuildTemplate;
import net.wurstclient.util.AutoBuildTextTemplateFactory;
import net.wurstclient.util.ChatUtils;

public final class AutoBuildCmd extends Command
{
	public AutoBuildCmd()
	{
		super("autobuild",
			"Selects an AutoBuild template by file name or generates block text and enables AutoBuild.",
			".autobuild <template>", ".autobuild \"<text>\" <height>");
		setCategory(Category.BLOCKS);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0)
			throw new CmdSyntaxError();
		
		AutoBuildHack autoBuildHack = WURST.getHax().autoBuildHack;
		TextRequest textRequest = parseTextRequest(args);
		if(textRequest != null)
		{
			AutoBuildTemplate generatedTemplate = AutoBuildTextTemplateFactory
				.create(textRequest.text(), textRequest.height());
			autoBuildHack.selectGeneratedTemplate(generatedTemplate);
			
			if(!autoBuildHack.isEnabled())
				autoBuildHack.setEnabled(true);
			
			ChatUtils.message("AutoBuild ready with text \""
				+ textRequest.text().toUpperCase() + "\" at "
				+ textRequest.height()
				+ " blocks tall. Right-click to start building.");
			return;
		}
		
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
	
	private TextRequest parseTextRequest(String[] args) throws CmdSyntaxError
	{
		if(args.length < 2 || !args[0].startsWith("\""))
			return null;
		
		StringBuilder text = new StringBuilder(args[0]);
		int closingIndex = args[0].endsWith("\"") ? 0 : -1;
		for(int i = 1; i < args.length && closingIndex < 0; i++)
		{
			text.append(" ").append(args[i]);
			if(args[i].endsWith("\""))
				closingIndex = i;
		}
		
		if(closingIndex < 0)
			throw new CmdSyntaxError("Missing closing quote.");
		
		if(closingIndex + 1 >= args.length)
			throw new CmdSyntaxError("Missing text height.");
		
		if(closingIndex + 2 != args.length)
			throw new CmdSyntaxError("Syntax: .autobuild \"<text>\" <height>");
		
		String heightArg = args[closingIndex + 1];
		int height;
		try
		{
			height = Integer.parseInt(heightArg);
			
		}catch(NumberFormatException e)
		{
			throw new CmdSyntaxError("Height must be a whole number.");
		}
		
		String literal = text.toString();
		if(literal.length() < 2 || !literal.endsWith("\""))
			throw new CmdSyntaxError("Missing closing quote.");
		
		return new TextRequest(literal.substring(1, literal.length() - 1),
			height);
	}
	
	private record TextRequest(String text, int height)
	{}
}
