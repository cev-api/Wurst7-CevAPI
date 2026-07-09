/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.awt.Color;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.util.text.WText;

@SearchTags({"wurst options", "settings"})
@DontBlock
public final class WurstOptionsOtf extends OtherFeature
{
	private final EnumSetting<Location> location = new EnumSetting<>("Location",
		"description.wurst.setting.wurstoptions.location", Location.values(),
		Location.GAME_MENU);
	
	private final CheckboxSetting hackToggleChatFeedback =
		new CheckboxSetting("Hack toggle chat feedback",
			"Show a chat message when hacks are enabled or disabled.", true);
	
	private final CheckboxSetting customMojangLogoBackground =
		new CheckboxSetting("Custom Mojang logo background",
			"Use a custom background color behind the Mojang loading logo.",
			true);
	
	private final CheckboxSetting customMultiplayerLayout = new CheckboxSetting(
		"Custom multiplayer layout",
		"Use Wurst's custom multiplayer server panels and search bar.", true);
	
	private final ColorSetting mojangLogoBackgroundColor =
		new ColorSetting("Mojang logo background color", Color.BLACK);
	
	private final SettingGroup discordPresenceGroup =
		new SettingGroup("Discord Presence",
			WText.translated(
				"description.wurst.setting.wurstoptions.discord_presence"),
			false, true);
	
	private boolean linkedExtraSettings;
	
	public WurstOptionsOtf()
	{
		super("WurstOptions", "description.wurst.other_feature.wurstoptions");
		addSetting(location);
	}
	
	public void linkAdditionalSettings(DisableOtf disableOtf,
		CommandPrefixOtf commandPrefixOtf, ChangelogOtf changelogOtf,
		ConnectionLogOverlayOtf connectionLogOverlayOtf,
		NoTelemetryOtf noTelemetryOtf, NoChatReportsOtf noChatReportsOtf,
		ForceAllowChatsOtf forceAllowChatsOtf, VanillaSpoofOtf vanillaSpoofOtf,
		TranslationsOtf translationsOtf, WurstCapesOtf wurstCapesOtf,
		DiscordRpcOtf discordRpcOtf)
	{
		if(linkedExtraSettings)
			return;
		
		addSetting(new ButtonSetting("Changelog",
			"Open the latest Wurst changelog in your browser.",
			changelogOtf::doPrimaryAction));
		addSetting(commandPrefixOtf.getPrefixSetting());
		addSetting(discordPresenceGroup);
		addSetting(hackToggleChatFeedback);
		addSetting(customMojangLogoBackground);
		addSetting(customMultiplayerLayout);
		addSetting(mojangLogoBackgroundColor);
		addSetting(disableOtf.getHideEnableButtonSetting());
		addSetting(noTelemetryOtf.getDisableTelemetrySetting());
		addSetting(noChatReportsOtf.getDisableSignaturesSetting());
		addSetting(forceAllowChatsOtf.getForceAllowChatsSetting());
		addSetting(vanillaSpoofOtf.getSpoofSetting());
		addSetting(translationsOtf.getForceEnglish());
		addSetting(wurstCapesOtf.getCapesSetting());
		addSetting(connectionLogOverlayOtf.getConnectionLogSetting());
		addSetting(connectionLogOverlayOtf.getFontScaleSetting());
		addSetting(WURST.getHax().navigatorHack.backgroundOverlay);
		discordPresenceGroup.addChildren(discordRpcOtf.getEnabledSetting(),
			discordRpcOtf.getStatusMessageSetting(),
			discordRpcOtf.getShowServerIpSetting(),
			discordRpcOtf.getShowUsernameSetting());
		
		linkedExtraSettings = true;
	}
	
	public boolean isHackToggleChatFeedbackEnabled()
	{
		return hackToggleChatFeedback.isChecked();
	}
	
	public CheckboxSetting getHackToggleChatFeedbackSetting()
	{
		return hackToggleChatFeedback;
	}
	
	public CheckboxSetting getCustomMojangLogoBackgroundSetting()
	{
		return customMojangLogoBackground;
	}
	
	public ColorSetting getMojangLogoBackgroundColorSetting()
	{
		return mojangLogoBackgroundColor;
	}
	
	public CheckboxSetting getCustomMultiplayerLayoutSetting()
	{
		return customMultiplayerLayout;
	}
	
	public boolean isCustomMultiplayerLayoutEnabled()
	{
		return customMultiplayerLayout.isChecked();
	}
	
	public String getLocationName()
	{
		return location.getSelected().toString();
	}
	
	public void cycleLocation()
	{
		location.selectNext();
	}
	
	public boolean isVisibleInGameMenu()
	{
		return WURST.isEnabled()
			&& location.getSelected() == Location.GAME_MENU;
	}
	
	public boolean isVisibleInStatistics()
	{
		return WURST.isEnabled()
			&& location.getSelected() == Location.STATISTICS;
	}
	
	public Button.Builder buttonBuilder(OnPress onPress)
	{
		MutableComponent message = Component.literal("            Options");
		
		MutableComponent narration =
			Component.translatable("gui.narrate.button", "Wurst Options");
		
		Tooltip tooltip = Tooltip.create(Component.literal(getDescription()));
		
		return Button.builder(message, onPress)
			.createNarration(sup -> narration).tooltip(tooltip);
	}
	
	public void drawWurstLogoOnButton(GuiGraphicsExtractor context,
		Button wurstOptionsButton)
	{
		// Logo disabled — no longer rendered on the options button.
	}
	
	private enum Location
	{
		GAME_MENU("Game Menu"),
		STATISTICS("Statistics");
		
		private final String name;
		
		private Location(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
