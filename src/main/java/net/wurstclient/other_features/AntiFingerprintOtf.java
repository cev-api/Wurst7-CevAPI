/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.util.List;

import net.cevapi.config.AntiFingerprintConfig;
import net.cevapi.config.AntiFingerprintConfigScreen;
import net.cevapi.security.ResourcePackProtector;
import net.minecraft.client.gui.screens.Screen;
import net.wurstclient.Category;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.Setting;

public final class AntiFingerprintOtf extends OtherFeature
{
	private final AntiFingerprintConfig config =
		ResourcePackProtector.getConfig();
	
	public AntiFingerprintOtf()
	{
		super("Anti-Fingerprint",
			"Adds protections against resource-pack based fingerprinting attempts.");
		
		List<Setting> settings = config.getAllSettings();
		for(Setting setting : settings)
			addSetting(setting);
	}
	
	@Override
	public Category getCategory()
	{
		return Category.OTHER;
	}
	
	@Override
	public String getPrimaryAction()
	{
		return "Open Anti-Fingerprint panel";
	}
	
	@Override
	public void doPrimaryAction()
	{
		Screen parent = MC.screen;
		MC.setScreen(new AntiFingerprintConfigScreen(parent));
	}
}
