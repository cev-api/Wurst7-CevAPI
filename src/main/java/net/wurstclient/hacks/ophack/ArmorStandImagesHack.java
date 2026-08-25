/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Queue;
import javax.imageio.ImageIO;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class ArmorStandImagesHack extends Hack implements UpdateListener
{
	private final TextFieldSetting imagePath = new TextFieldSetting(
		"Image path", "PNG file to convert into armor stands.", "");
	private final SliderSetting scale = new SliderSetting("Scale",
		"Pixels per block.", 1, 1, 8, 1, ValueDisplay.INTEGER);
	private final SliderSetting height = new SliderSetting("Height",
		"Image height above the player.", 2, -64, 128, 1, ValueDisplay.INTEGER);
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between pixel commands.", 3, 3, 20, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting clear = new CheckboxSetting("Clear images",
		"Kill previous image armor stands first.", true);
	private final Queue<String> queue = new ArrayDeque<>();
	private int ticks;
	
	public ArmorStandImagesHack()
	{
		super("ArmorStandImages",
			"Converts a PNG into colored armor-stand pixel displays.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(imagePath);
		addSetting(scale);
		addSetting(height);
		addSetting(delay);
		addSetting(clear);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		if(clear.isChecked())
			queue.add("kill @e[tag=CEVAPI_IMAGE,type=armor_stand]");
		try
		{
			BufferedImage image = ImageIO.read(new File(imagePath.getValue()));
			if(image == null)
				throw new IllegalArgumentException("Not a readable image");
			int step = Math.max(1, scale.getValueI());
			for(int y = 0; y < image.getHeight(); y += step)
				for(int x = 0; x < image.getWidth(); x += step)
				{
					int argb = image.getRGB(x, y);
					if(((argb >>> 24) & 255) < 32)
						continue;
					String color = String.format("%06x", argb & 0xFFFFFF);
					String q = Character.toString(34);
					String command = "execute at @s run summon armor_stand ~"
						+ x + " ~" + (height.getValueI() - y)
						+ " ~ {Invisible:1b,NoGravity:1b,Marker:1b,Tags:[" + q
						+ "CEVAPI_IMAGE" + q
						+ "],CustomNameVisible:1b,CustomName:'{" + q + "text"
						+ q + ":" + q + "█" + q + "," + q + "color" + q + ":"
						+ q + "#" + color + q + "}'}";
					if(command.length() <= 256)
						queue.add(command);
				}
		}catch(Exception ignored)
		{
			queue.clear();
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
