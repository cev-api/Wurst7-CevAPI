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

public final class AutoDisplaysHack extends Hack implements UpdateListener
{
	private final TextFieldSetting text = new TextFieldSetting("Text",
		"Text displayed around players.", "Your server is being renovated!");
	private final TextFieldSetting block = new TextFieldSetting("Block",
		"Block for block displays.", "black_concrete");
	private final TextFieldSetting mode =
		new TextFieldSetting("Mode", "TEXT or BLOCK.", "TEXT");
	private final SliderSetting distance = new SliderSetting("Distance",
		"Distance from each player.", 3, 1, 16, 1, ValueDisplay.INTEGER);
	private final SliderSetting brightness = new SliderSetting("Brightness",
		"Display brightness.", 15, 0, 15, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting clearExisting = new CheckboxSetting(
		"Clear existing", "Kill previous tagged displays first.", true);
	private final CheckboxSetting delayCommands = new CheckboxSetting(
		"Use command delay", "Delay commands between sends.", false);
	private final SliderSetting delay = new SliderSetting("Command delay",
		"Ticks between commands.", 2, 1, 20, 1, ValueDisplay.INTEGER);
	private final Queue<String> queue = new ArrayDeque<>();
	private int ticks;
	
	public AutoDisplaysHack()
	{
		super("AutoDisplays", "Summons text or block displays around players.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(text);
		addSetting(block);
		addSetting(mode);
		addSetting(distance);
		addSetting(brightness);
		addSetting(clearExisting);
		addSetting(delayCommands);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		String q = Character.toString(34);
		if(clearExisting.isChecked())
		{
			queue.add("kill @e[tag=CEVAPI_DISPLAY,type=text_display]");
			queue.add("kill @e[tag=CEVAPI_DISPLAY,type=block_display]");
		}
		if(mode.getValue().equalsIgnoreCase("BLOCK"))
		{
			queue.add(
				"execute at @a run summon block_display ~ ~1 ~ {block_state:{Name:"
					+ q + "minecraft:" + block.getValue() + q
					+ "},brightness:{sky:" + brightness.getValueI() + ",block:"
					+ brightness.getValue() + "},Tags:[" + q + "CEVAPI_DISPLAY"
					+ q + "]}");
		}else
		{
			String escaped = text.getValue().replace(q, "\\" + q);
			queue.add("execute at @a run summon text_display ~ ~1 ~-"
				+ distance.getValueI() + " {text:" + q + escaped + q
				+ ",brightness:{sky:" + brightness.getValueI() + ",block:"
				+ brightness.getValue() + "},Tags:[" + q + "CEVAPI_DISPLAY" + q
				+ "]}");
			queue.add("execute at @a run summon text_display ~ ~1 ~"
				+ distance.getValueI() + " {text:" + q + escaped + q
				+ ",brightness:{sky:" + brightness.getValueI() + ",block:"
				+ brightness.getValue() + "},Tags:[" + q + "CEVAPI_DISPLAY" + q
				+ "]}");
		}
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
		if(delayCommands.isChecked() && ticks++ < delay.getValueI())
			return;
		ticks = 0;
		String command = queue.poll();
		if(command.length() <= 256)
			MC.getConnection().sendCommand(command);
	}
}
