/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.options;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Util;
import net.minecraft.util.Util.OS;
import net.wurstclient.WurstClient;
import net.wurstclient.altgui.TooManyHaxEditorScreen;
import net.wurstclient.altmanager.LoginException;
import net.wurstclient.altmanager.screens.AltManagerScreen;
import net.wurstclient.analytics.PlausibleAnalytics;
import net.wurstclient.commands.FriendsCmd;
import net.wurstclient.navigator.NavigatorMainScreen;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.other_features.ConnectionLogOverlayOtf;
import net.wurstclient.other_features.DiscordRpcOtf;
import net.wurstclient.other_features.VanillaSpoofOtf;
import net.wurstclient.other_features.WurstOptionsOtf;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.LastServerRememberer;
import net.wurstclient.util.WurstColors;

public final class WurstOptionsScreen extends Screen
{
	private static final int BUTTON_HEIGHT = 18;
	private static final int ROW_GAP = 1;
	private static final int COLUMN_GAP = 10;
	private static final int COLUMN_GAP_COMPACT = 6;
	
	private final Screen prevScreen;
	private final List<SectionHeader> sectionHeaders = new ArrayList<>();
	private final List<WurstOptionsButton> contentButtons = new ArrayList<>();
	
	private int columnCount;
	private int buttonWidth;
	private int[] columnX;
	private int[] nextRowByColumn;
	private int contentTop;
	private int contentBottom;
	private int backButtonY;
	private int scrollOffset;
	private int maxScroll;
	private boolean randomAltReconnectInProgress;
	
	public WurstOptionsScreen(Screen prevScreen)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
	}
	
	@Override
	public void init()
	{
		sectionHeaders.clear();
		contentButtons.clear();
		scrollOffset = 0;
		layoutColumns();
		
		addCoreSection();
		addPrivacySection();
		addToolsSection();
		addLinksSection();
		
		backButtonY = height - 26;
		int viewTop = contentTop - 2;
		int viewBottom = backButtonY - 8;
		int visibleHeight = Math.max(1, viewBottom - viewTop);
		int totalHeight =
			Math.max(0, contentBottom - contentTop + BUTTON_HEIGHT);
		maxScroll = Math.max(0, totalHeight - visibleHeight);
		
		new WurstOptionsButton(width / 2 - 110, backButtonY, 220, 20,
			() -> "Back", "Return to the previous screen.",
			b -> minecraft.setScreen(prevScreen));
		applyScrollLayout();
	}
	
	private void layoutColumns()
	{
		if(width >= 860)
			columnCount = 4;
		else if(width >= 640)
			columnCount = 3;
		else if(width >= 460)
			columnCount = 2;
		else
			columnCount = 1;
		
		int gap = columnCount >= 3 ? COLUMN_GAP : COLUMN_GAP_COMPACT;
		int horizontalPadding = columnCount >= 3 ? 16 : 8;
		int availableWidth = Math.max(120,
			width - horizontalPadding * 2 - (columnCount - 1) * gap);
		int maxButtonWidth =
			columnCount == 1 ? 300 : columnCount == 2 ? 240 : 196;
		buttonWidth = Math.max(120,
			Math.min(maxButtonWidth, availableWidth / columnCount));
		
		int totalWidth = columnCount * buttonWidth + (columnCount - 1) * gap;
		int left = Math.max(4, (width - totalWidth) / 2);
		contentTop = Math.max(56, height / 8);
		contentBottom = contentTop;
		
		columnX = new int[columnCount];
		nextRowByColumn = new int[columnCount];
		for(int i = 0; i < columnCount; i++)
		{
			columnX[i] = left + i * (buttonWidth + gap);
			nextRowByColumn[i] = contentTop + 16;
		}
	}
	
	private int beginSection(String title, int preferredColumn)
	{
		int column = preferredColumn % columnCount;
		if(nextRowByColumn[column] > contentTop + 16)
			nextRowByColumn[column] += BUTTON_HEIGHT; // explicit empty row
			
		int sectionY = nextRowByColumn[column];
		sectionHeaders.add(new SectionHeader(title,
			columnX[column] + buttonWidth / 2, sectionY));
		nextRowByColumn[column] += 13; // reserved header row height
		return column;
	}
	
	private void addCoreSection()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		FriendsCmd friendsCmd = wurst.getCmds().friendsCmd;
		CheckboxSetting middleClickFriends = friendsCmd.getMiddleClickFriends();
		PlausibleAnalytics plausible = wurst.getPlausible();
		WurstOptionsOtf options = wurst.getOtfs().wurstOptionsOtf;
		VanillaSpoofOtf vanillaSpoof = wurst.getOtfs().vanillaSpoofOtf;
		CheckboxSetting forceEnglish =
			wurst.getOtfs().translationsOtf.getForceEnglish();
		CheckboxSetting capes = wurst.getOtfs().wurstCapesOtf.getCapesSetting();
		CheckboxSetting forceAllowChats =
			wurst.getOtfs().forceAllowChatsOtf.getForceAllowChatsSetting();
		CheckboxSetting hackToggleFeedback =
			options.getHackToggleChatFeedbackSetting();
		CheckboxSetting hideEnableButton =
			wurst.getOtfs().disableOtf.getHideEnableButtonSetting();
		var hideWurstHack = wurst.getHax().hideWurstHack;
		
		int column = beginSection("Core", 0);
		
		addButton(column,
			() -> "Options Location: " + options.getLocationName(),
			"Choose where the Wurst Options button appears (Game Menu or Statistics).",
			b -> options.cycleLocation());
		
		addButton(column,
			() -> "Click Friends: " + onOff(middleClickFriends.isChecked()),
			middleClickFriends.getWrappedDescription(220),
			b -> middleClickFriends
				.setChecked(!middleClickFriends.isChecked()));
		
		addButton(column, () -> "Count Users: " + onOff(plausible.isEnabled()),
			"Anonymous usage analytics to help prioritize support and version compatibility.",
			b -> plausible.setEnabled(!plausible.isEnabled()));
		
		addButton(column,
			() -> "Hack Toggle Chat: " + onOff(hackToggleFeedback.isChecked()),
			"Show chat feedback when hacks are enabled or disabled.",
			b -> hackToggleFeedback
				.setChecked(!hackToggleFeedback.isChecked()));
		
		addButton(column,
			() -> "Hide Enable Button: " + onOff(hideEnableButton.isChecked()),
			hideEnableButton.getDescription(),
			b -> hideEnableButton.setChecked(!hideEnableButton.isChecked()));
		
		addButton(column,
			() -> "HideWurst: " + onOff(hideWurstHack.isEnabled()),
			"Quick toggle for HideWurst (stealth rendering behavior).",
			b -> hideWurstHack.setEnabled(!hideWurstHack.isEnabled()));
		
		addButton(column,
			() -> "Command Prefix: " + wurst.getOtfs().commandPrefixOtf
				.getPrefixSetting().getSelected().toString(),
			"Cycle through available command prefixes.",
			b -> wurst.getOtfs().commandPrefixOtf.getPrefixSetting()
				.selectNext());
		
		addButton(column,
			() -> "Spoof Vanilla: " + onOff(vanillaSpoof.isEnabled()),
			vanillaSpoof.getDescription(), b -> vanillaSpoof.doPrimaryAction());
		
		addButton(column,
			() -> "Force English: " + onOff(forceEnglish.isChecked()),
			forceEnglish.getDescription(),
			b -> forceEnglish.setChecked(!forceEnglish.isChecked()));
		
		// Vanilla splash text toggle (default OFF = hidden)
		addButton(column, () -> {
			boolean showSplash =
				minecraft.options.hideSplashTexts().get() ? false : true;
			return "Vanilla Splash Text: " + onOff(showSplash);
		}, "Show or hide the vanilla yellow splash text on the title screen.",
			b -> {
				boolean hide = minecraft.options.hideSplashTexts().get();
				minecraft.options.hideSplashTexts().set(!hide);
				// Persist the change immediately
				try
				{
					minecraft.options.save();
				}catch(Exception ignored)
				{}
			});
		
		addButton(column, () -> "Custom Capes: " + onOff(capes.isChecked()),
			capes.getDescription(), b -> capes.setChecked(!capes.isChecked()));
		
		addButton(column,
			() -> "Force Allow Chats: " + onOff(forceAllowChats.isChecked()),
			forceAllowChats.getDescription(),
			b -> forceAllowChats.setChecked(!forceAllowChats.isChecked()));
	}
	
	private void addPrivacySection()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		CheckboxSetting disableTelemetry =
			wurst.getOtfs().noTelemetryOtf.getDisableTelemetrySetting();
		CheckboxSetting disableSignatures =
			wurst.getOtfs().noChatReportsOtf.getDisableSignaturesSetting();
		CheckboxSetting unsafeChatToast =
			wurst.getOtfs().noChatReportsOtf.getUnsafeChatToast();
		ConnectionLogOverlayOtf connectionLogOtf =
			wurst.getOtfs().connectionLogOverlayOtf;
		CheckboxSetting connectionLog =
			connectionLogOtf.getConnectionLogSetting();
		SliderSetting connectionFontScale =
			connectionLogOtf.getFontScaleSetting();
		DiscordRpcOtf discord = wurst.getOtfs().discordRpcOtf;
		var antiFingerprintConfig =
			net.cevapi.security.ResourcePackProtector.getConfig();
		
		int column = beginSection("Presence & Privacy", 1);
		
		addButton(column,
			() -> "Discord Presence: "
				+ onOff(discord.getEnabledSetting().isChecked()),
			discord.getEnabledSetting().getDescription(),
			b -> discord.getEnabledSetting()
				.setChecked(!discord.getEnabledSetting().isChecked()));
		
		addButton(column,
			() -> "Discord Status: " + shorten(
				discord.getStatusMessageSetting().getSelected().toString(), 22),
			"Cycle Discord status message preset.",
			b -> discord.getStatusMessageSetting().selectNext());
		
		addButton(column,
			() -> "Discord Show Server: "
				+ onOff(discord.getShowServerIpSetting().isChecked()),
			discord.getShowServerIpSetting().getDescription(),
			b -> discord.getShowServerIpSetting()
				.setChecked(!discord.getShowServerIpSetting().isChecked()));
		
		addButton(column,
			() -> "Discord Show Username: "
				+ onOff(discord.getShowUsernameSetting().isChecked()),
			discord.getShowUsernameSetting().getDescription(),
			b -> discord.getShowUsernameSetting()
				.setChecked(!discord.getShowUsernameSetting().isChecked()));
		
		addButton(column,
			() -> "Disable Telemetry: " + onOff(disableTelemetry.isChecked()),
			disableTelemetry.getDescription(),
			b -> disableTelemetry.setChecked(!disableTelemetry.isChecked()));
		
		addButton(column,
			() -> "Disable Signatures: " + onOff(disableSignatures.isChecked()),
			disableSignatures.getDescription(),
			b -> disableSignatures.setChecked(!disableSignatures.isChecked()));
		
		addButton(column,
			() -> "Unsafe Chat Toast: " + onOff(unsafeChatToast.isChecked()),
			unsafeChatToast.getDescription(),
			b -> unsafeChatToast.setChecked(!unsafeChatToast.isChecked()));
		
		addButton(column,
			() -> "Connection Log: " + onOff(connectionLog.isChecked()),
			connectionLogOtf.getDescription(),
			b -> connectionLog.setChecked(!connectionLog.isChecked()));
		
		addButton(column,
			() -> "Connection Font: " + String.format(Locale.ROOT, "%.2fx",
				connectionFontScale.getValue()),
			"Cycle connection log overlay font size.",
			b -> cycleSlider(connectionFontScale));
		
		addButton(column,
			() -> "Show RP Buttons: " + onOff(
				antiFingerprintConfig.shouldShowResourcePackBypassButtons()),
			"Show Bypass Resource Pack and Force Deny on the Multiplayer screen.",
			b -> antiFingerprintConfig.getShowResourcePackBypassButtonsSetting()
				.setChecked(!antiFingerprintConfig
					.shouldShowResourcePackBypassButtons()));
		
		if(NiceWurstModule.showAntiFingerprintControls())
			addButton(column, () -> "Anti-Fingerprint",
				"Open Anti-Fingerprint controls for resource-pack handling.",
				b -> minecraft.setScreen(
					new net.cevapi.config.AntiFingerprintConfigScreen(this)));
	}
	
	private void addToolsSection()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		
		int column = beginSection("Tools & Recovery", 2);
		
		addButton(column, () -> "Presets",
			"Manage full Wurst presets for hacks, UI, keybinds, and more.",
			b -> minecraft.setScreen(new PresetManagerScreen(this)));
		
		addButton(column, () -> "Keybinds", "Open Keybind Manager.",
			b -> minecraft.setScreen(new KeybindManagerScreen(this)));
		
		if(!NiceWurstModule.isActive())
		{
			addButton(column, () -> "Alt Manager",
				"Open Alt Manager and account tools.", b -> minecraft.setScreen(
					new AltManagerScreen(this, wurst.getAltManager())));
			
			addButton(column,
				() -> "Toggle Random Reconnect: " + onOff(wurst.getAltManager()
					.isDisconnectRandomAltReconnectEnabled()),
				"Controls whether Disconnected screen shows \"Reconnect as Random Alt\".",
				b -> wurst.getAltManager()
					.setDisconnectRandomAltReconnectEnabled(
						!wurst.getAltManager()
							.isDisconnectRandomAltReconnectEnabled()));
			
			addButton(column, this::getRandomAltReconnectLabel,
				"Log into a random saved alt, then reconnect to the last server.",
				b -> reconnectAsRandomAlt());
		}
		
		addButton(column, () -> "Reconnect Last Server",
			"Reconnect immediately to the last remembered multiplayer server.",
			b -> LastServerRememberer.reconnect(this));
		
		if(!NiceWurstModule.isActive())
			addButton(column, () -> "Reconnect as Random Name",
				"Use OfflineSettings to reconnect with a random offline-style player name.",
				b -> wurst.getHax().offlineSettingsHack
					.reconnectWithRandomName(this));
		
		addButton(column, () -> "Advanced Packet Tools",
			"Open packet logging/deny/delay UI.",
			b -> wurst.getOtfs().packetToolsOtf.openScreen());
		
		addButton(column, () -> "Hack Debugger",
			"Open Navigator to toggle hacks/settings while out of game.",
			b -> minecraft.setScreen(new NavigatorMainScreen()));
		
		addButton(column, () -> "Blocked Hacks Editor",
			"Open TooManyHax blocklist editor (safe outside game).",
			b -> minecraft.setScreen(new TooManyHaxEditorScreen(this,
				wurst.getHax().tooManyHaxHack)));
		
		addButton(column, () -> "Panic: Disable All Hacks",
			"Immediately disable all hacks and store a snapshot.",
			b -> wurst.getHax().panicHack.setEnabled(true));
		
		addButton(column, () -> "Panic: Restore Snapshot",
			"Restore hacks saved by Panic.",
			b -> wurst.getHax().panicHack.restoreSavedHacks());
		
		addButton(column, () -> "Reload Wurst",
			"Reload settings, enabled/favorites, keybinds, navigator preferences, blocked hax, and ClickGUI window layout from disk.",
			b -> {
				wurst.reloadFromDisk();
				if(WurstClient.MC != null && WurstClient.MC.player != null)
					ChatUtils.message("Reloaded settings from disk.");
			});
		
		addButton(column, () -> "Open Wurst Folder",
			"Open the Wurst configuration folder in your file explorer.",
			b -> Util.getPlatform().openFile(wurst.getWurstFolder().toFile()));
	}
	
	private void addLinksSection()
	{
		OS os = Util.getPlatform();
		int column = beginSection("Links", 3);
		
		String primaryLabel =
			NiceWurstModule.isActive() ? "NiceWurst GitHub" : "CevAPI GitHub";
		String primaryUrl =
			NiceWurstModule.isActive() ? "https://github.com/cev-api/NiceWurst"
				: "https://github.com/cev-api/Wurst7-CevAPI";
		
		addButton(column, () -> primaryLabel, "Open the main fork repository.",
			b -> os.openUri(primaryUrl));
		
		addButton(column, () -> "CevAPI Discord", "discord.gg/wDgqxkAKFQ",
			b -> os.openUri("https://discord.gg/wDgqxkAKFQ"));
		
		addButton(column, () -> "Wurst Website", "WurstClient.net",
			b -> os.openUri("https://www.wurstclient.net/options-website/"));
		
		addButton(column, () -> "Wurst Wiki", "Wurst.Wiki",
			b -> os.openUri("https://www.wurstclient.net/options-wiki/"));
		
		addButton(column, () -> "WurstForum", "WurstForum.net",
			b -> os.openUri("https://www.wurstclient.net/options-forum/"));
		
		addButton(column, () -> "Twitter", "@Wurst_Imperium",
			b -> os.openUri("https://www.wurstclient.net/options-twitter/"));
		
		addButton(column, () -> "Changelog",
			"Open latest release notes/changelog.",
			b -> WurstClient.INSTANCE.getOtfs().changelogOtf.doPrimaryAction());
	}
	
	private void addButton(int column, Supplier<String> messageSupplier,
		String tooltip, Button.OnPress pressAction)
	{
		int y = nextRowByColumn[column];
		WurstOptionsButton button = new WurstOptionsButton(columnX[column], y,
			buttonWidth, BUTTON_HEIGHT, messageSupplier, tooltip, pressAction);
		contentButtons.add(button);
		nextRowByColumn[column] += BUTTON_HEIGHT + ROW_GAP;
		contentBottom = Math.max(contentBottom, y + BUTTON_HEIGHT);
	}
	
	private void applyScrollLayout()
	{
		for(WurstOptionsButton button : contentButtons)
			button.setY(button.getBaseY() - scrollOffset);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
		double scrollY)
	{
		if(maxScroll <= 0)
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		
		scrollOffset -= (int)Math.round(scrollY * 18.0);
		if(scrollOffset < 0)
			scrollOffset = 0;
		if(scrollOffset > maxScroll)
			scrollOffset = maxScroll;
		applyScrollLayout();
		return true;
	}
	
	private static void cycleSlider(SliderSetting slider)
	{
		double next = slider.getValue() + slider.getIncrement();
		if(next > slider.getMaximum() + 1e-9)
			next = slider.getMinimum();
		slider.setValue(next);
	}
	
	private String getRandomAltReconnectLabel()
	{
		return randomAltReconnectInProgress ? "Random Alt Reconnect: Working..."
			: "Reconnect as Random Alt";
	}
	
	private void reconnectAsRandomAlt()
	{
		if(randomAltReconnectInProgress)
			return;
		
		if(WurstClient.INSTANCE.getAltManager().getList().isEmpty())
		{
			ChatUtils.error("No alts available in Alt Manager.");
			return;
		}
		
		if(LastServerRememberer.getLastServer() == null)
		{
			ChatUtils.error("No last server remembered yet.");
			return;
		}
		
		randomAltReconnectInProgress = true;
		Thread worker = new Thread(() -> {
			LoginException error = null;
			String altName = "";
			
			try
			{
				var alt = WurstClient.INSTANCE.getAltManager()
					.loginRandomUntilSuccess();
				altName = alt.getDisplayName();
				
			}catch(LoginException e)
			{
				error = e;
			}
			
			String selectedAltName = altName;
			LoginException reconnectError = error;
			minecraft.execute(() -> {
				randomAltReconnectInProgress = false;
				
				if(reconnectError != null)
				{
					ChatUtils.error("Random alt login failed: "
						+ reconnectError.getMessage());
					return;
				}
				
				ChatUtils.message(
					"Logged in as " + selectedAltName + ", reconnecting...");
				LastServerRememberer.reconnect(this);
			});
		}, "Wurst Options Random Alt Reconnect");
		
		worker.setDaemon(true);
		worker.start();
	}
	
	private static String onOff(boolean enabled)
	{
		return enabled ? "ON" : "OFF";
	}
	
	private static String shorten(String text, int maxLen)
	{
		if(text == null || text.length() <= maxLen)
			return text == null ? "" : text;
		return text.substring(0, Math.max(0, maxLen - 3)) + "...";
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(prevScreen);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		renderOptionsBackground(context, mouseX, mouseY, partialTicks);
		renderTitles(context);
		applyScrollLayout();
		
		int viewTop = contentTop - 2;
		int viewBottom = backButtonY - 8;
		int viewLeft = columnX[0] - 6;
		int viewRight = columnX[columnCount - 1] + buttonWidth + 6;
		
		context.enableScissor(viewLeft, viewTop, viewRight, viewBottom);
		renderSectionHeaders(context, viewTop, viewBottom);
		for(WurstOptionsButton button : contentButtons)
			button.extractRenderState(context, mouseX, mouseY, partialTicks);
		context.disableScissor();
		
		for(Renderable drawable : renderables)
			if(!(drawable instanceof WurstOptionsButton wo)
				|| !contentButtons.contains(wo))
				drawable.extractRenderState(context, mouseX, mouseY,
					partialTicks);
			
		renderScrollbar(context, viewRight + 4, viewTop, viewBottom);
		
		renderButtonTooltip(context, mouseX, mouseY);
	}
	
	private void renderOptionsBackground(GuiGraphics context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.fillGradient(0, 0, width, height, 0xDA10131B, 0xE0121B29);
		
		for(int i = 0; i < columnCount; i++)
		{
			int x1 = columnX[i] - 4;
			int x2 = columnX[i] + buttonWidth + 4;
			int y1 = contentTop - 2;
			int y2 = backButtonY - 8;
			context.fill(x1, y1, x2, y2, 0x66111111);
			context.fill(x1, y1, x2, y1 + 1, 0x884A4A4A);
			context.fill(x1, y2 - 1, x2, y2, 0x884A4A4A);
			context.fill(x1, y1, x1 + 1, y2, 0x884A4A4A);
			context.fill(x2 - 1, y1, x2, y2, 0x884A4A4A);
		}
	}
	
	private void renderTitles(GuiGraphics context)
	{
		Font tr = minecraft.font;
		int middleX = width / 2;
		int titleY = 32;
		
		String title =
			NiceWurstModule.isActive() ? "NiceWurst Options" : "Wurst Options";
		context.centeredText(tr, title, middleX, titleY, CommonColors.WHITE);
		context.centeredText(tr, "Feature-rich controls and recovery tools",
			middleX, titleY + 12, CommonColors.LIGHT_GRAY);
	}
	
	private void renderSectionHeaders(GuiGraphicsExtractor context, int viewTop,
		int viewBottom)
	{
		Font tr = minecraft.font;
		for(SectionHeader header : sectionHeaders)
		{
			int y = header.y() - scrollOffset;
			if(y + tr.lineHeight < viewTop || y > viewBottom)
				continue;
			context.centeredText(tr, header.title(), header.centerX(), y,
				WurstColors.VERY_LIGHT_GRAY);
		}
	}
	
	private void renderScrollbar(GuiGraphicsExtractor context, int x, int top,
		int bottom)
	{
		if(maxScroll <= 0)
			return;
		
		int barH = Math.max(20, bottom - top);
		int thumbH = Math.max(18,
			(int)Math.round(barH * (barH / (double)(barH + maxScroll))));
		int thumbTop = top + (int)Math.round(
			(barH - thumbH) * (scrollOffset / (double)Math.max(1, maxScroll)));
		
		context.fill(x, top, x + 6, bottom, 0x66303030);
		context.fill(x, thumbTop, x + 6, thumbTop + thumbH, 0xAA808080);
	}
	
	private void renderButtonTooltip(GuiGraphics context, int mouseX,
		int mouseY)
	{
		for(AbstractWidget button : Screens.getButtons(this))
		{
			if(!(button instanceof WurstOptionsButton))
				continue;
			
			boolean hovered = button.visible && mouseX >= button.getX()
				&& mouseX < button.getX() + button.getWidth()
				&& mouseY >= button.getY()
				&& mouseY < button.getY() + button.getHeight();
			if(!hovered)
				continue;
			
			WurstOptionsButton woButton = (WurstOptionsButton)button;
			if(woButton.tooltip.isEmpty())
				continue;
			
			context.setComponentTooltipForNextFrame(font, woButton.tooltip,
				mouseX, mouseY);
			break;
		}
	}
	
	private record SectionHeader(String title, int centerX, int y)
	{}
	
	private final class WurstOptionsButton extends Button
	{
		private final Supplier<String> messageSupplier;
		private final List<Component> tooltip;
		private final int baseY;
		
		private WurstOptionsButton(int x, int y, int w, int h,
			Supplier<String> messageSupplier, String tooltip,
			OnPress pressAction)
		{
			super(x, y, w, h, Component.literal(messageSupplier.get()),
				pressAction, Button.DEFAULT_NARRATION);
			this.messageSupplier = messageSupplier;
			this.baseY = y;
			
			String normalizedTooltip = tooltip == null ? "" : tooltip.trim();
			if(normalizedTooltip.isEmpty())
				normalizedTooltip = buildFallbackTooltip(messageSupplier.get());
			
			if(normalizedTooltip.isEmpty())
			{
				this.tooltip = Arrays.asList();
			}else
			{
				String[] lines =
					ChatUtils.wrapText(normalizedTooltip, 220).split("\n");
				Component[] wrapped = new Component[lines.length];
				for(int i = 0; i < lines.length; i++)
					wrapped[i] = Component.literal(lines[i]);
				this.tooltip = Arrays.asList(wrapped);
			}
			
			addRenderableWidget(this);
		}
		
		private int getBaseY()
		{
			return baseY;
		}
		
		@Override
		public void onPress(InputWithModifiers context)
		{
			super.onPress(context);
			setMessage(Component.literal(messageSupplier.get()));
		}
		
		@Override
		protected void renderContents(GuiGraphics drawContext, int i, int j,
			float f)
		{
			renderDefaultSprite(drawContext);
			renderDefaultLabel(drawContext.textRendererForWidget(this,
				GuiGraphics.HoveredTextEffects.NONE));
		}
		
		private static String buildFallbackTooltip(String label)
		{
			if(label == null)
				return "Open this option.";
			
			String clean = label.replace('\n', ' ').trim();
			if(clean.isEmpty())
				return "Open this option.";
			
			int colon = clean.indexOf(':');
			if(colon > 0)
				clean = clean.substring(0, colon).trim();
			
			if(clean.isEmpty())
				return "Open this option.";
			
			return "Configure " + clean + ".";
		}
	}
}
