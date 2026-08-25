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
import java.util.UUID;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class AutoScoreboardHack extends Hack implements UpdateListener
{
	private final CheckboxSetting permissionHold =
		new CheckboxSetting("Permission level hold",
			"Wait for operator access before sending.", true);
	private final TextFieldSetting title =
		new TextFieldSetting("Title", "Scoreboard title.", "Trolled!");
	private final TextFieldSetting titleColor = new TextFieldSetting(
		"Title color", "Minecraft scoreboard color.", "dark_red");
	private final TextFieldSetting content =
		new TextFieldSetting("Content", "Semicolon-separated scoreboard lines.",
			"Wurst7-CevAPI;cevapi.dev;Destroyed by {player};{date}");
	private final TextFieldSetting contentColor = new TextFieldSetting(
		"Content color", "Minecraft scoreboard color.", "red");
	private final CheckboxSetting useDelay = new CheckboxSetting(
		"Use command delay", "Delay commands to reduce kick risk.", false);
	private final SliderSetting delay = new SliderSetting("Command delay",
		"Ticks between commands.", 2, 1, 20, 1, ValueDisplay.INTEGER);
	private final Queue<String> queue = new ArrayDeque<>();
	private int ticks;
	
	public AutoScoreboardHack()
	{
		super("AutoScoreboard",
			"Creates a scoreboard with configured title and content lines.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(permissionHold);
		addSetting(title);
		addSetting(titleColor);
		addSetting(content);
		addSetting(contentColor);
		addSetting(useDelay);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		ticks = 0;
		String objective = "cevapi_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String player =
			MC.player == null ? "" : MC.player.getName().getString();
		String q = Character.toString(34);
		String scoreboardTitle = title.getValue().replace("{player}", player);
		queue.add("scoreboard objectives add " + objective + " dummy {" + q
			+ "text" + q + ":" + q + scoreboardTitle + q + "," + q + "color" + q
			+ ":" + q + titleColor.getValue() + q + "}");
		queue.add("scoreboard objectives setdisplay sidebar " + objective);
		String[] lines = content.getValue().split(";");
		int score = lines.length;
		for(String line : lines)
		{
			line = line.trim().replace("{player}", player);
			queue.add("team add cevapi_" + score);
			queue.add("team modify cevapi_" + score + " suffix {" + q + "text"
				+ q + ":" + q + " " + line + q + "}");
			queue.add("team modify cevapi_" + score + " color "
				+ contentColor.getValue());
			queue.add("team join cevapi_" + score + " " + score);
			queue.add("scoreboard players set " + score + " " + objective + " "
				+ score);
			score--;
		}
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
		if(MC.player == null || MC.getConnection() == null)
			return;
		if(queue.isEmpty())
		{
			setEnabled(false);
			return;
		}
		if(useDelay.isChecked() && ticks++ < delay.getValueI())
			return;
		ticks = 0;
		String command = queue.poll();
		if(command.length() <= 256)
			MC.getConnection().sendCommand(command);
	}
}
