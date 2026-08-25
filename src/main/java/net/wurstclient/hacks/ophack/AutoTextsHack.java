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
import java.util.concurrent.ThreadLocalRandom;
import java.util.Queue;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class AutoTextsHack extends Hack implements UpdateListener
{
	private final TextFieldSetting texts =
		new TextFieldSetting("Texts", "Semicolon-separated text lines.",
			"Hello From Wurst7-CevAPI;cevapi.dev");
	private final TextFieldSetting color =
		new TextFieldSetting("Color", "Text color.", "red");
	private final SliderSetting radius = new SliderSetting("Radius",
		"Spawn radius.", 8, 1, 32, 1, ValueDisplay.INTEGER);
	private final SliderSetting height = new SliderSetting("Height",
		"Height above the player.", 0, -8, 32, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting randomHeight = new CheckboxSetting(
		"Height variation", "Vary each display height.", false);
	private final SliderSetting delay = new SliderSetting("Spawn delay",
		"Ticks between displays.", 3, 3, 20, 1, ValueDisplay.INTEGER);
	private final Queue<String> queue = new ArrayDeque<>();
	private int ticks;
	
	public AutoTextsHack()
	{
		super("AutoTexts",
			"Spawns configurable text display entities around the player.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(texts);
		addSetting(color);
		addSetting(radius);
		addSetting(height);
		addSetting(randomHeight);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		String configuredTexts = texts.getValue()
			.replace("Mountains of Lava Inc", "Wurst7-CevAPI")
			.replace("MOUNTAINSOFLAVAINC", "Cevapi")
			.replace("https://www.youtube.com/@mountainsoflavainc.6913",
				"cevapi.dev")
			.replace("www.youtube.com/@mountainsoflavainc.6913", "cevapi.dev")
			.replace("youtube.com/@mountainsoflavainc.6913", "cevapi.dev")
			.replace("https://www.cevapi.dev/", "cevapi.dev");
		if(!configuredTexts.equals(texts.getValue()))
			texts.setValue(configuredTexts);
		String q = Character.toString(34);
		int y = height.getValueI();
		for(String line : configuredTexts.split(";"))
		{
			line = line.trim();
			if(line.isBlank())
				continue;
			int x = ThreadLocalRandom.current().nextInt(-radius.getValueI(),
				radius.getValueI() + 1);
			int z = ThreadLocalRandom.current().nextInt(-radius.getValueI(),
				radius.getValueI() + 1);
			String command = "execute at @s run summon text_display ~" + x
				+ " ~" + y + " ~" + z + " {text:" + q + line.replace(q, "") + q
				+ ",color:" + q + color.getValue() + q + ",billboard:" + q
				+ "center" + q + "}";
			if(command.length() <= 256)
				queue.add(command);
			if(randomHeight.isChecked())
				y++;
		}
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
		MC.getConnection().sendCommand(queue.poll());
	}
}
