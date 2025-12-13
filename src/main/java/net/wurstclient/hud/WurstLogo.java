/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.wurstclient.WurstClient;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.other_features.WurstLogoOtf;
import net.wurstclient.util.GuiRenderStateHelper;
import net.wurstclient.util.RenderUtils;

public final class WurstLogo
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	
	public void render(GuiGraphics context)
	{
		WurstLogoOtf otf = WURST.getOtfs().wurstLogoOtf;
		if(!otf.isVisible())
			return;
		
		String version = getVersionString();
		String brand = NiceWurstModule.getBrandLabel("Wurst 7 CevAPI");
		Font tr = WurstClient.MC.font;
		
		// Measure and layout
		int brandWidth = tr.width(brand);
		int versionWidth = tr.width(version);
		int leftPadding = 4;
		int gap = 6;
		int rightPadding = 8;
		int bgWidth =
			leftPadding + brandWidth + gap + versionWidth + rightPadding;
		
		// background
		int bgColor;
		if(WURST.getHax().rainbowUiHack.isEnabled())
			bgColor = RenderUtils.toIntColor(WURST.getGui().getAcColor(), 0.5F);
		else
			bgColor = otf.getBackgroundColor();
		context.fill(0, 6, bgWidth, 17, bgColor);
		
		GuiRenderStateHelper.up(context);
		
		// brand and version strings
		int textY = 8;
		int brandX = leftPadding;
		int versionX = brandX + brandWidth + gap;
		context.drawString(tr, brand, brandX, textY, otf.getTextColor(), false);
		context.drawString(tr, version, versionX, textY, otf.getTextColor(),
			false);
		
		GuiRenderStateHelper.down(context);
	}
	
	private String getVersionString()
	{
		String version = "v" + WurstClient.VERSION;
		version += " MC" + WurstClient.MC_VERSION;
		
		if(WURST.getUpdater().isOutdated())
			version += " (outdated)";
		
		return version;
	}
}
