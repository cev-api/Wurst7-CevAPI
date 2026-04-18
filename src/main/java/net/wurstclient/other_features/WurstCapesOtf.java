/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.DontBlock;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;

@DontBlock
public final class WurstCapesOtf extends OtherFeature
{
	private final CheckboxSetting capes = new CheckboxSetting("Custom Capes",
		"description.wurst.setting.wurstcapes.custom_capes", true);
	
	public WurstCapesOtf()
	{
		super("WurstCapes",
			"Wurst has its own capes! Only Wurst users can see them.");
		addSetting(capes);
	}
	
	public CheckboxSetting getCapesSetting()
	{
		return capes;
	}
	
	@Override
	public boolean isEnabled()
	{
		return capes.isChecked();
	}
	
	@Override
	public String getPrimaryAction()
	{
		return isEnabled() ? "Disable" : "Enable";
	}
	
	@Override
	public void doPrimaryAction()
	{
		capes.setChecked(!capes.isChecked());
	}
}
