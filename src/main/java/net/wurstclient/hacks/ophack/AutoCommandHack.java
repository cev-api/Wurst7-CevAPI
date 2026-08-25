/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class AutoCommandHack extends Hack implements UpdateListener
{
	private final TextFieldSetting commands = new TextFieldSetting("Commands",
		"Semicolon-separated commands. Leading slashes are optional.",
		"deop @a[name=!{player}];whitelist off;pardon {player};op {player}");
	private final TextFieldSetting mode = new TextFieldSetting("Mode",
		"Manual or Loop. Manual runs once; Loop repeats after completion.",
		"Manual");
	private final TextFieldSetting macroName = new TextFieldSetting("Macro",
		"Reserved macro name for compatibility with CevAPI.", "op");
	private final SliderSetting delay = new SliderSetting("Command delay",
		"Ticks between commands.", 3, 3, 100, 1, ValueDisplay.INTEGER);
	private final SliderSetting permissionLevel = new SliderSetting(
		"Permission level",
		"Minimum local permission level hint. Server permission remains authoritative.",
		3, 0, 4, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting disableOnFinish = new CheckboxSetting(
		"Disable on finish", "Disable after one command sequence.", false);
	private final Queue<String> queue = new ArrayDeque<>();
	private int ticks;
	private boolean finished;
	
	public AutoCommandHack()
	{
		super("AutoCommand",
			"Runs configured commands with delay and loop behavior.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(commands);
		addSetting(mode);
		addSetting(macroName);
		addSetting(delay);
		addSetting(permissionLevel);
		addSetting(disableOnFinish);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		finished = false;
		ticks = 0;
		EVENTS.add(UpdateListener.class, this);
		fillQueue();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		queue.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.getConnection() == null)
			return;
		if(queue.isEmpty())
		{
			if(finished && !mode.getValue().equalsIgnoreCase("loop"))
			{
				if(disableOnFinish.isChecked())
					setEnabled(false);
				return;
			}
			if(finished)
				fillQueue();
		}
		if(queue.isEmpty())
			return;
		if(ticks++ < delay.getValueI())
			return;
		ticks = 0;
		String command = queue.poll();
		command = command.replace("{player}", MC.player.getName().getString());
		if(command.length() > 256)
		{
			queue.clear();
			finished = true;
			return;
		}
		while(command.startsWith("/"))
			command = command.substring(1).trim();
		if(!command.isBlank())
			MC.getConnection().sendCommand(command);
		if(queue.isEmpty())
			finished = true;
	}
	
	private void fillQueue()
	{
		queue.clear();
		Arrays.stream(commands.getValue().split(";")).map(String::trim)
			.filter(s -> !s.isBlank()).forEach(queue::add);
		finished = false;
		ticks = delay.getValueI();
	}
}
