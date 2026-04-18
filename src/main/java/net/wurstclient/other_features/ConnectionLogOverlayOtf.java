/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.other_feature.OtherFeature;

public final class ConnectionLogOverlayOtf extends OtherFeature
{
	private final CheckboxSetting showConnectionLog =
		new CheckboxSetting("Connection log overlay", true);
	private final SliderSetting fontScale = new SliderSetting(
		"Overlay Font Size", 0.7, 0.5, 3.0, 0.05, ValueDisplay.DECIMAL);
	
	public ConnectionLogOverlayOtf()
	{
		super("ConnectionLogOverlay",
			"description.wurst.other_feature.connection_log_overlay");
		addSetting(showConnectionLog);
		addSetting(fontScale);
	}
	
	public CheckboxSetting getConnectionLogSetting()
	{
		return showConnectionLog;
	}
	
	public boolean isConnectionLogEnabled()
	{
		return showConnectionLog.isChecked();
	}
	
	public double getFontScale()
	{
		return fontScale.getValue();
	}
	
	public SliderSetting getFontScaleSetting()
	{
		return fontScale;
	}
}
