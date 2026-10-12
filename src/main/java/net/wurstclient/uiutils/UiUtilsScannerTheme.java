/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import net.wurstclient.WurstClient;
import net.wurstclient.util.RenderUtils;

/** Resolves scanner colors from the current ClickGUI settings. */
public final class UiUtilsScannerTheme
{
	private UiUtilsScannerTheme()
	{}
	
	public static int color(String role, int fallback)
	{
		var hax = WurstClient.INSTANCE.getHax();
		if(hax == null || hax.clickGuiHack == null)
			return fallback;
		var theme = hax.clickGuiHack;
		if(role.equals("text"))
			return (fallback & 0xFF000000) | (theme.getTextColor() & 0xFFFFFF);
		float[] rgb = switch(role)
		{
			case "accent" -> theme.getEnabledHackColor();
			case "border" -> hax.rainbowUiHack.isEnabled()
				? RenderUtils.getRainbowColor() : theme.getAccentColor();
			case "header" -> theme.getTopBarColor();
			case "input" -> theme.getDropdownBackgroundColor();
			default -> theme.getBackgroundColor();
		};
		return (fallback & 0xFF000000) | (Math.round(rgb[0] * 255F) << 16)
			| (Math.round(rgb[1] * 255F) << 8) | Math.round(rgb[2] * 255F);
	}
}
