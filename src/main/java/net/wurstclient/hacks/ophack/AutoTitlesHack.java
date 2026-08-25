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

public final class AutoTitlesHack extends Hack implements UpdateListener
{
	private final CheckboxSetting excludeSelf = new CheckboxSetting(
		"Exclude self", "Exclude your own name from @a targets.", true);
	private final CheckboxSetting excludeFriends =
		new CheckboxSetting("Exclude friends",
			"Compatibility option for friend-aware targeting.", true);
	private final CheckboxSetting useDelay = new CheckboxSetting(
		"Use command delay", "Delay commands between sends.", false);
	private final SliderSetting delay = new SliderSetting("Command delay",
		"Ticks between commands.", 2, 1, 20, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting makeSubtitle =
		new CheckboxSetting("Make subtitle", "Send a subtitle.", true);
	private final CheckboxSetting makeActionbar = new CheckboxSetting(
		"Make actionbar", "Send an actionbar message.", true);
	private final TextFieldSetting title =
		new TextFieldSetting("Title", "Title text.", "Wurst7-CevAPI");
	private final TextFieldSetting subtitle =
		new TextFieldSetting("Subtitle", "Subtitle text.", "cevapi.dev");
	private final TextFieldSetting actionbar = new TextFieldSetting("Actionbar",
		"Actionbar text.", "Renovations in progress.");
	private final TextFieldSetting titleColor =
		new TextFieldSetting("Title color", "Title color.", "white");
	private final TextFieldSetting subtitleColor =
		new TextFieldSetting("Subtitle color", "Subtitle color.", "green");
	private final TextFieldSetting actionbarColor =
		new TextFieldSetting("Actionbar color", "Actionbar color.", "yellow");
	private final Queue<String> queue = new ArrayDeque<>();
	private int ticks;
	
	public AutoTitlesHack()
	{
		super("AutoTitles",
			"Sends title, subtitle, and actionbar commands to players.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(excludeSelf);
		addSetting(excludeFriends);
		addSetting(useDelay);
		addSetting(delay);
		addSetting(makeSubtitle);
		addSetting(makeActionbar);
		addSetting(title);
		addSetting(subtitle);
		addSetting(actionbar);
		addSetting(titleColor);
		addSetting(subtitleColor);
		addSetting(actionbarColor);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		String target = "@a";
		String q = Character.toString(34);
		if(excludeSelf.isChecked() && MC.player != null)
			target = "@a[name=!" + MC.player.getName().getString() + "]";
		queue.add("title " + target + " times 10 70 20");
		queue.add("title " + target + " title {" + q + "text" + q + ":" + q
			+ title.getValue() + q + "," + q + "color" + q + ":" + q
			+ titleColor.getValue() + q + "}");
		if(makeSubtitle.isChecked())
			queue.add("title " + target + " subtitle {" + q + "text" + q + ":"
				+ q + subtitle.getValue() + q + "," + q + "color" + q + ":" + q
				+ subtitleColor.getValue() + q + "}");
		if(makeActionbar.isChecked())
			queue.add("title " + target + " actionbar {" + q + "text" + q + ":"
				+ q + actionbar.getValue() + q + "," + q + "color" + q + ":" + q
				+ actionbarColor.getValue() + q + "}");
		ticks = useDelay.isChecked() ? 0 : delay.getValueI();
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
		if(useDelay.isChecked() && ticks++ < delay.getValueI())
			return;
		ticks = 0;
		String command = queue.poll();
		if(command.length() <= 256)
			MC.getConnection().sendCommand(command);
	}
}
