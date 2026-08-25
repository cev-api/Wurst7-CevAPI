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
import net.wurstclient.settings.TextFieldSetting;

public final class AutoNamesHack extends Hack implements UpdateListener
{
	private final TextFieldSetting prefix =
		new TextFieldSetting("Prefix", "Text before names.", "Who is");
	private final TextFieldSetting suffix =
		new TextFieldSetting("Suffix", "Text after names.", "?");
	private final TextFieldSetting color =
		new TextFieldSetting("Color", "Team color.", "red");
	private final CheckboxSetting targetSelf =
		new CheckboxSetting("Target self", "Include yourself.", true);
	private final CheckboxSetting rainbow =
		new CheckboxSetting("Rainbow", "Cycle team colors.", false);
	private final SliderSetting rainbowDelay =
		new SliderSetting("Rainbow delay", "Ticks between color changes.", 20,
			1, 100, 1, ValueDisplay.INTEGER);
	private int ticks;
	private int colorIndex;
	private final Queue<String> queue = new ArrayDeque<>();
	private int commandTicks;
	private final SliderSetting commandDelay = new SliderSetting(
		"Command delay", "Ticks between commands (26.2 spam-safe).", 3, 3, 20,
		1, ValueDisplay.INTEGER);
	private final String[] colors = {"red", "gold", "yellow", "green", "aqua",
		"blue", "light_purple", "white"};
	
	public AutoNamesHack()
	{
		super("AutoNames",
			"Applies team prefix, suffix, and colors to player names.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(prefix);
		addSetting(suffix);
		addSetting(color);
		addSetting(targetSelf);
		addSetting(rainbow);
		addSetting(rainbowDelay);
		addSetting(commandDelay);
	}
	
	@Override
	protected void onEnable()
	{
		ticks = rainbowDelay.getValueI();
		colorIndex = 0;
		EVENTS.add(UpdateListener.class, this);
		commandTicks = commandDelay.getValueI();
		queue.clear();
		enqueue("team add cevapi_names");
		apply();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.getConnection() == null)
			return;
		if(!queue.isEmpty())
		{
			if(commandTicks++ >= commandDelay.getValueI())
			{
				commandTicks = 0;
				MC.getConnection().sendCommand(queue.poll());
			}
			return;
		}
		if(rainbow.isChecked() && ++ticks >= rainbowDelay.getValueI())
		{
			ticks = 0;
			colorIndex = (colorIndex + 1) % colors.length;
			apply();
		}
	}
	
	private void apply()
	{
		String c = rainbow.isChecked() ? colors[colorIndex] : color.getValue();
		enqueue("team modify cevapi_names color " + c);
		String q = Character.toString(34);
		enqueue("team modify cevapi_names prefix {" + q + "text" + q + ":" + q
			+ prefix.getValue() + q + "}");
		enqueue("team modify cevapi_names suffix {" + q + "text" + q + ":" + q
			+ suffix.getValue() + q + "}");
		enqueue("team join cevapi_names " + (targetSelf.isChecked() ? "@a"
			: "@a[name=!" + MC.player.getName().getString() + "]"));
	}
	
	private void enqueue(String command)
	{
		if(command.length() <= 256)
			queue.add(command);
	}
}
