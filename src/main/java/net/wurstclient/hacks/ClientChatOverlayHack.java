/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.ISimpleOption;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"ui settings", "chat overlay", "hud", "client chat"})
public final class ClientChatOverlayHack extends Hack implements UpdateListener
{
	private final SliderSetting transparency =
		new SliderSetting("Transparency", 35, 0, 100, 1, ValueDisplay.INTEGER);
	private final SliderSetting fadeOutTimeSeconds =
		new SliderSetting("Fade-out time", 10, 1, 60, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting routeToConsole =
		new CheckboxSetting("Route to game console", false);
	private final CheckboxSetting onlyWurstMessages =
		new CheckboxSetting("Only Wurst messages", false);
	private final CheckboxSetting extraPanelForWurstMessages =
		new CheckboxSetting("Extra panel for Wurst-only messages", false);
	private final CheckboxSetting holdTabOpensChat = new CheckboxSetting(
		"Hold Tab opens chat",
		"While held, opens vanilla chat like pressing T. Releasing Tab closes it.",
		false);
	private final TextFieldSetting forceClientKeywords = new TextFieldSetting(
		"Force client chat keywords",
		"Comma-separated keywords that force a message into client chat.", "");
	private final TextFieldSetting forceNormalKeywords = new TextFieldSetting(
		"Force normal chat keywords",
		"Comma-separated keywords that force a message into normal chat.", "");
	private final CheckboxSetting colorUsernames =
		new CheckboxSetting("Color usernames",
			"Colors only the sender username in captured player chat.", false);
	private final CheckboxSetting chatHeads = new CheckboxSetting("Chat heads",
		"Shows the sender's player head before player chat messages.", false);
	private final CheckboxSetting useServerColors = new CheckboxSetting(
		"Use server username colors",
		"Follows colors supplied by the server for chat usernames when available.",
		false);
	private final CheckboxSetting randomOwnUsernameColor = new CheckboxSetting(
		"Random own username color",
		"Uses a generated color for your username instead of the fixed color.",
		false);
	private final ColorSetting ownUsernameColor = new ColorSetting(
		"Own username color",
		"Fixed color for your own username when Color usernames is enabled.",
		new Color(0x55FFFF));
	private final SliderSetting maxLines =
		new SliderSetting("Max lines", 10, 3, 30, 1, ValueDisplay.INTEGER);
	private final SliderSetting chatFontScale =
		new SliderSetting("Chat font size",
			"description.wurst.setting.clientchatoverlay.chat_font_scale", 1,
			0.5, 2, 0.05, ValueDisplay.DECIMAL.withSuffix("x"));
	private final ColorSetting defaultTextColor = new ColorSetting(
		"Default text color",
		"Base text color for overlay chat lines without explicit color styling.",
		new Color(0xC0C0C0));
	private final SliderSetting hudOffsetX = new SliderSetting("HUD X offset",
		0, -320, 320, 1, ValueDisplay.INTEGER);
	private final SliderSetting hudOffsetY = new SliderSetting("HUD Y offset",
		0, -240, 240, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting colorCommandText =
		new CheckboxSetting("Color command text",
			"Colors the chat input text orange when you are typing a Wurst"
				+ " command (starting with the command prefix, e.g. '.').",
			false);
	
	private final ColorSetting commandTextColor =
		new ColorSetting("Command text color",
			"The color to use for chat input text when typing a Wurst command.",
			new Color(0xFFAA00));
	
	public ClientChatOverlayHack()
	{
		super("ClientChatOverlay");
		setCategory(Category.CHAT);
		addSetting(transparency);
		addSetting(fadeOutTimeSeconds);
		addSetting(routeToConsole);
		addSetting(onlyWurstMessages);
		addSetting(extraPanelForWurstMessages);
		addSetting(holdTabOpensChat);
		addSetting(forceClientKeywords);
		addSetting(forceNormalKeywords);
		addSetting(colorUsernames);
		addSetting(chatHeads);
		addSetting(useServerColors);
		addSetting(randomOwnUsernameColor);
		addSetting(ownUsernameColor);
		addSetting(maxLines);
		addSetting(chatFontScale);
		addSetting(defaultTextColor);
		addSetting(hudOffsetX);
		addSetting(hudOffsetY);
		addSetting(colorCommandText);
		addSetting(commandTextColor);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(!(MC.gui.screen() instanceof ChatScreen))
			return;
		
		if(!colorCommandText.isChecked())
			return;
		
		EditBox input = getChatInput();
		if(input == null)
			return;
		
		String text = input.getValue();
		boolean isCommand =
			text != null && !text.isEmpty() && !text.startsWith("/");
		if(isCommand)
		{
			String prefix = getCommandPrefix();
			isCommand =
				prefix != null && !prefix.isEmpty() && text.startsWith(prefix);
		}
		
		if(isCommand)
			input.setTextColor(getCommandTextColorI());
		else
			input.setTextColor(0xFFE0E0E0);
	}
	
	private EditBox getChatInput()
	{
		try
		{
			java.lang.reflect.Field field =
				ChatScreen.class.getDeclaredField("input");
			field.setAccessible(true);
			return (EditBox)field.get(MC.gui.screen());
		}catch(Exception e)
		{
			return null;
		}
	}
	
	private String getCommandPrefix()
	{
		try
		{
			return WURST.getOtfs().commandPrefixOtf.getPrefixSetting()
				.getSelected().toString();
		}catch(Throwable ignored)
		{
			return ".";
		}
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
	
	public boolean isExtraPanelForWurstMessages()
	{
		return extraPanelForWurstMessages.isChecked();
	}
	
	public boolean shouldHoldTabOpenChat()
	{
		return holdTabOpensChat.isChecked();
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
	
	public boolean shouldColorUsernames()
	{
		return colorUsernames.isChecked();
	}
	
	public boolean shouldShowChatHeads()
	{
		return chatHeads.isChecked();
	}
	
	public boolean shouldUseServerColors()
	{
		return useServerColors.isChecked();
	}
	
	public boolean shouldRandomizeOwnUsernameColor()
	{
		return randomOwnUsernameColor.isChecked();
	}
	
	public int getOwnUsernameColorI()
	{
		return ownUsernameColor.getColorI() & 0x00FFFFFF;
	}
	
	public double getChatFontScale()
	{
		return chatFontScale.getValue();
	}
	
	public SliderSetting getChatFontScaleSetting()
	{
		return chatFontScale;
	}
	
	public int getDefaultTextColorI()
	{
		return defaultTextColor.getColorI() & 0x00FFFFFF;
	}
	
	public void resetVanillaChatScale()
	{
		if(MC == null || MC.options == null)
			return;
		
		ISimpleOption.get(MC.options.chatScale()).forceSetValue(1.0);
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
	
	public boolean shouldColorCommandText()
	{
		return colorCommandText.isChecked();
	}
	
	public int getCommandTextColorI()
	{
		return commandTextColor.getColorI();
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
