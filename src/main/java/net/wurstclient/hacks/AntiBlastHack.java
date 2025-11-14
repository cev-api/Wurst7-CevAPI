/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"anti explosion", "anti blast", "no explosion knockback",
	"no blast knockback", "anti wind charge", "wind charge"})
public final class AntiBlastHack extends Hack
{
	private final SliderSetting hStrength =
		new SliderSetting("Horizontal Strength",
			"How far to reduce horizontal explosion knockback.\n"
				+ "-100% = double knockback\n" + "0% = normal knockback\n"
				+ "100% = no knockback\n" + ">100% = reverse knockback",
			1, -1, 2, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting vStrength =
		new SliderSetting("Vertical Strength",
			"How far to reduce vertical explosion knockback.\n"
				+ "-100% = double knockback\n" + "0% = normal knockback\n"
				+ "100% = no knockback\n" + ">100% = reverse knockback",
			1, -1, 2, 0.01, ValueDisplay.PERCENTAGE);
	
	public AntiBlastHack()
	{
		super("AntiBlast");
		setCategory(Category.COMBAT);
		addSetting(hStrength);
		addSetting(vStrength);
	}
	
	public Vec3d modifyKnockback(double defaultX, double defaultY,
		double defaultZ)
	{
		if(!isEnabled())
			return new Vec3d(defaultX, defaultY, defaultZ);
		
		double horizontalMultiplier = 1 - hStrength.getValue();
		double verticalMultiplier = 1 - vStrength.getValue();
		
		return new Vec3d(defaultX * horizontalMultiplier,
			defaultY * verticalMultiplier, defaultZ * horizontalMultiplier);
	}
}
