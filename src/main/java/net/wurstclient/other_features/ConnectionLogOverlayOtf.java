/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.other_feature.OtherFeature;

public final class ConnectionLogOverlayOtf extends OtherFeature
{
	private final CheckboxSetting showConnectionLog =
		new CheckboxSetting("Connection log overlay", true);
	
	public ConnectionLogOverlayOtf()
	{
		super("ConnectionLogOverlay",
			"description.wurst.other_feature.connection_log_overlay");
		addSetting(showConnectionLog);
	}
	
	public CheckboxSetting getConnectionLogSetting()
	{
		return showConnectionLog;
	}
	
	public boolean isConnectionLogEnabled()
	{
		return showConnectionLog.isChecked();
	}
}
