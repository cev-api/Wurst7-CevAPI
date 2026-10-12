/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.Screen;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.uiutils.UiUtilsDupeDbUpdater;
import net.wurstclient.uiutils.UiUtilsSettings;
import net.wurstclient.uiutils.UiUtilsVulnerablePlugins;
import net.wurstclient.util.text.WText;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.uiutils.UiUtilsCommandScannerScreen;

/** Opens the complete UI-Utils-style server intelligence dashboard. */
@SearchTags({"server intel", "plugin scanner", "legacy plugin scanner",
	"command scanner", "verbose server scan", "server scanner"})
public final class ServerIntelHack extends Hack
{
	private boolean dupeDbSettingsReady;
	private final TextFieldSetting dupeDbApiKey = new TextFieldSetting(
		"DupeDB API key", WText.literal("API key for database updates."), "");
	private final CheckboxSetting dupeDbEnabled = new CheckboxSetting(
		"Enable DupeDB updates",
		WText.literal(
			"Use DupeDB's plugin vulnerability database alongside the local list."),
		false)
	{
		@Override
		public void update()
		{
			if(!dupeDbSettingsReady)
				return;
			UiUtilsSettings.get().dupeDbEnabled = isChecked();
			UiUtilsSettings.save();
			UiUtilsVulnerablePlugins.reload();
		}
	};
	private final CheckboxSetting dupeDbScanOnLaunch = new CheckboxSetting(
		"Scan DupeDB on game launch",
		WText.literal(
			"Check for database updates when the game launches. Turn OFF for manual updates only."),
		true)
	{
		@Override
		public void update()
		{
			if(!dupeDbSettingsReady)
				return;
			UiUtilsSettings.get().dupeDbScanOnLaunch = isChecked();
			UiUtilsSettings.save();
		}
	};
	
	private final CheckboxSetting dupeDbCheckServerIp = new CheckboxSetting(
		"Check current server IP",
		WText.literal(
			"When enabled, send the joined server address to DupeDB to check exploit submissions and verified sightings. Cached reports work without this option."),
		true)
	{
		@Override
		public void update()
		{
			if(!dupeDbSettingsReady)
				return;
			UiUtilsSettings.get().dupeDbCheckServerIp = isChecked();
			UiUtilsSettings.save();
			if(!isChecked() && UiUtilsDupeDbUpdater.isServerLookupActive())
				UiUtilsDupeDbUpdater.stopScan();
		}
	};
	
	public ServerIntelHack()
	{
		super("ServerIntel");
		setCategory(Category.INTEL);
		dupeDbApiKey.setMaxLength(512);
		dupeDbApiKey.setMasked(true);
		dupeDbApiKey.setChangeListener(value -> {
			if(!dupeDbSettingsReady)
				return;
			UiUtilsSettings.get().dupeDbApiKey = value;
			UiUtilsSettings.save();
		});
		addSetting(dupeDbApiKey);
		addSetting(dupeDbEnabled);
		addSetting(dupeDbScanOnLaunch);
		addSetting(dupeDbCheckServerIp);
		ButtonSetting serverLookup = new ButtonSetting("Check server in DupeDB",
			WText.literal(
				"Check the current server, or stop its running lookup."),
			UiUtilsDupeDbUpdater::toggleServerLookup);
		serverLookup
			.setButtonText(() -> UiUtilsDupeDbUpdater.isServerLookupActive()
				? "STOP server lookup" : "Check server in DupeDB");
		addSetting(serverLookup);
		ButtonSetting update = new ButtonSetting("Scan / update DupeDB now",
			WText.literal(
				"Check for database updates, or stop a running update."),
			UiUtilsDupeDbUpdater::toggleRefresh);
		update.setButtonText(() -> UiUtilsDupeDbUpdater.isActive()
			? "STOP DupeDB update" : "Scan / update DupeDB now");
		addSetting(update);
	}
	
	/**
	 * Migrate the existing shared preferences after UI-Utils settings have
	 * loaded.
	 */
	public void initDupeDbSettings()
	{
		dupeDbApiKey.setValue(UiUtilsSettings.get().dupeDbApiKey);
		dupeDbEnabled.setChecked(UiUtilsSettings.get().dupeDbEnabled);
		dupeDbScanOnLaunch.setChecked(UiUtilsSettings.get().dupeDbScanOnLaunch);
		dupeDbCheckServerIp
			.setChecked(UiUtilsSettings.get().dupeDbCheckServerIp);
		dupeDbSettingsReady = true;
	}
	
	@Override
	protected void onEnable()
	{
		Screen parent = MC.gui.screen();
		MC.gui.setScreen(new UiUtilsCommandScannerScreen(parent));
		setEnabled(false);
	}
}
