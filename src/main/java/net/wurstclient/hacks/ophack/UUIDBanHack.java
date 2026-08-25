/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class UUIDBanHack extends Hack implements UpdateListener
{
	private final TextFieldSetting player =
		new TextFieldSetting("Player", "Player name or UUID to ban.", "");
	private final TextFieldSetting command = new TextFieldSetting("Ban command",
		"Command template; {player} is replaced.", "ban {player}");
	private final CheckboxSetting allPlayers = new CheckboxSetting(
		"All players", "Ban every online player except yourself.", false);
	private final CheckboxSetting ignoreFriends = new CheckboxSetting(
		"Ignore friends", "Compatibility option for friend filtering.", true);
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between ban commands.", 20, 0, 100, 1, ValueDisplay.INTEGER);
	private final java.util.ArrayDeque<String> queue =
		new java.util.ArrayDeque<>();
	private int ticks;
	
	public UUIDBanHack()
	{
		super("UUIDBan", "Queues UUID/name ban commands for selected players.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(player);
		addSetting(command);
		addSetting(allPlayers);
		addSetting(ignoreFriends);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		if(allPlayers.isChecked() && MC.getConnection() != null)
			for(var p : MC.getConnection().getOnlinePlayers())
				if(MC.player == null
					|| !p.getProfile().id().equals(MC.player.getUUID()))
					queue.add(command.getValue().replace("{player}",
						p.getProfile().name()));
				else if(!player.getValue().isBlank())
					queue.add(command.getValue().replace("{player}",
						player.getValue()));
		ticks = delay.getValueI();
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
		String value = queue.poll();
		while(value.startsWith("/"))
			value = value.substring(1);
		if(value.length() <= 256)
			MC.getConnection().sendCommand(value);
	}
}
