/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

public final class ModMenuTextUtils
{
	private static final Pattern SHOWING_PATTERN = Pattern
		.compile("showing\\s+(\\d+)\\s*/\\s*\\d+", Pattern.CASE_INSENSITIVE);
	
	private ModMenuTextUtils()
	{}
	
	public static String adjustModCountText(String raw)
	{
		if(raw == null || !shouldHideModMenuCount())
			return raw;
		
		String adjusted = raw.replaceAll("(\\b\\d+)\\s*/\\s*\\d+\\b", "$1");
		Matcher matcher = SHOWING_PATTERN.matcher(adjusted);
		if(!matcher.find())
			return adjusted;
		
		String count = matcher.group(1);
		String lower = adjusted.toLowerCase();
		String suffix = lower.contains("mods")
			? ("1".equals(count) ? " mod" : " mods") : "";
		String replacement = "Showing " + count + suffix;
		return matcher.replaceFirst(replacement);
	}
	
	public static boolean shouldHideModMenuCount()
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null)
			return false;
		
		Minecraft mc = WurstClient.MC;
		if(mc == null || mc.screen == null)
			return false;
		
		boolean isModMenuScreen = mc.screen.getClass().getName()
			.equals("com.terraformersmc.modmenu.gui.ModsScreen");
		if(!isModMenuScreen)
			return false;
		
		boolean hideWurstModMenu = hax.hideWurstHack != null
			&& hax.hideWurstHack.shouldHideFromModMenu();
		boolean hideModMenu =
			hax.hideModMenuHack != null && hax.hideModMenuHack.isEnabled();
		return hideWurstModMenu || hideModMenu;
	}
}
