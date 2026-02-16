/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"ui settings", "chat overlay", "hud", "client chat"})
public final class ClientChatOverlayHack extends Hack
{
	private final SliderSetting transparency =
		new SliderSetting("Transparency", 35, 0, 100, 1, ValueDisplay.INTEGER);
	private final SliderSetting fadeOutTimeSeconds =
		new SliderSetting("Fade-out time", 10, 1, 60, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting routeToConsole =
		new CheckboxSetting("Route to game console", false);
	private final CheckboxSetting onlyWurstMessages =
		new CheckboxSetting("Only Wurst messages", false);
	private final TextFieldSetting forceClientKeywords = new TextFieldSetting(
		"Force client chat keywords",
		"Comma-separated keywords that force a message into client chat.", "");
	private final TextFieldSetting forceNormalKeywords = new TextFieldSetting(
		"Force normal chat keywords",
		"Comma-separated keywords that force a message into normal chat.", "");
	private final SliderSetting maxLines =
		new SliderSetting("Max lines", 10, 3, 30, 1, ValueDisplay.INTEGER);
	private final SliderSetting hudOffsetX = new SliderSetting("HUD X offset",
		0, -320, 320, 1, ValueDisplay.INTEGER);
	private final SliderSetting hudOffsetY = new SliderSetting("HUD Y offset",
		0, -240, 240, 1, ValueDisplay.INTEGER);
	
	public ClientChatOverlayHack()
	{
		super("ClientChatOverlay");
		setCategory(Category.OTHER);
		addSetting(transparency);
		addSetting(fadeOutTimeSeconds);
		addSetting(routeToConsole);
		addSetting(onlyWurstMessages);
		addSetting(forceClientKeywords);
		addSetting(forceNormalKeywords);
		addSetting(maxLines);
		addSetting(hudOffsetX);
		addSetting(hudOffsetY);
	}
	
	public int getTransparencyPercent()
	{
		return transparency.getValueI();
	}
	
	public long getFadeOutTimeMs()
	{
		return fadeOutTimeSeconds.getValueI() * 1000L;
	}
	
	public boolean isRoutingToConsole()
	{
		return routeToConsole.isChecked();
	}
	
	public boolean isOnlyWurstMessages()
	{
		return onlyWurstMessages.isChecked();
	}
	
	public boolean matchesForceClientKeyword(String text)
	{
		return matchesAnyKeyword(text, getForceClientKeywords());
	}
	
	public boolean matchesForceNormalKeyword(String text)
	{
		return matchesAnyKeyword(text, getForceNormalKeywords());
	}
	
	public int getMaxLines()
	{
		return maxLines.getValueI();
	}
	
	public int getHudOffsetX()
	{
		return hudOffsetX.getValueI();
	}
	
	public int getHudOffsetY()
	{
		return hudOffsetY.getValueI();
	}
	
	public void setHudOffsets(int x, int y)
	{
		hudOffsetX.setValue(x);
		hudOffsetY.setValue(y);
	}
	
	private Set<String> getForceClientKeywords()
	{
		return parseKeywords(forceClientKeywords.getValue());
	}
	
	private Set<String> getForceNormalKeywords()
	{
		return parseKeywords(forceNormalKeywords.getValue());
	}
	
	private static Set<String> parseKeywords(String value)
	{
		if(value == null || value.isBlank())
			return Set.of();
		
		return Arrays.stream(value.split(",")).map(String::trim)
			.filter(s -> !s.isEmpty()).map(String::toLowerCase)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}
	
	private static boolean matchesAnyKeyword(String text, Set<String> keywords)
	{
		if(text == null || text.isBlank() || keywords.isEmpty())
			return false;
		
		String lower = text.toLowerCase();
		for(String keyword : keywords)
			if(lower.contains(keyword))
				return true;
			
		return false;
	}
}
