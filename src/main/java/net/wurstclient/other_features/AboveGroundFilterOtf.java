/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;

@SearchTags({"above ground", "esp filter"})
public final class AboveGroundFilterOtf extends OtherFeature
	implements UpdateListener
{
	private final CheckboxSetting enabled = new CheckboxSetting(
		"Above-ground ESP filter",
		"When enabled, ESP/search hacks that support the filter will only show results at or above the configured Y level.",
		false);
	
	private final SliderSetting yLevel = new SliderSetting("Above ground Y", 62,
		0, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	
	private boolean lastEnabled = false;
	private int lastY = 62;
	
	public AboveGroundFilterOtf()
	{
		super("Above-ground ESP filter",
			"Global toggle + Y for per-hack above-ground filters.");
		addSetting(enabled);
		addSetting(yLevel);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		boolean e = enabled.isChecked();
		int y = yLevel.getValueI();
		if(e != lastEnabled)
		{
			WurstClient.INSTANCE.getHax().setAboveGroundFilterEnabled(e);
			lastEnabled = e;
		}
		if(y != lastY)
		{
			WurstClient.INSTANCE.getHax().setAboveGroundFilterY(y);
			lastY = y;
		}
	}
}
