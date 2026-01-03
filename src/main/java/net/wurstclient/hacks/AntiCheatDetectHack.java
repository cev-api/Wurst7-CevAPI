/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.ServerObserver;

public final class AntiCheatDetectHack extends Hack
{
	private String lastAnnounced;
	private long lastAnnouncedMs;
	private static final long ANNOUNCE_COOLDOWN_MS = 3000L;
	private final CheckboxSetting setbackDetection = new CheckboxSetting(
		"SetbackDetection",
		"Disables packet-based hacks when the server sends a setback.", true);
	private final CheckboxSetting suppressUnknown =
		new CheckboxSetting("Suppress unknown alerts",
			"description.wurst.setting.anticheatdetect.suppress_unknown", true);
	
	public AntiCheatDetectHack()
	{
		super("AntiCheatDetect");
		setCategory(Category.OTHER);
		addSetting(setbackDetection);
		addSetting(suppressUnknown);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getServerObserver().requestCaptureIfNeeded();
		alertAboutAntiCheat();
	}
	
	public void completed()
	{
		if(isEnabled())
			alertAboutAntiCheat();
	}
	
	private void alertAboutAntiCheat()
	{
		ServerObserver observer = WURST.getServerObserver();
		String antiCheat = observer.guessAntiCheat(observer.getServerAddress());
		
		if(antiCheat == null)
			return;
		
		if("Unknown".equalsIgnoreCase(antiCheat) && suppressUnknown.isChecked())
			return;
		
		long now = System.currentTimeMillis();
		if(antiCheat.equalsIgnoreCase(lastAnnounced)
			&& now - lastAnnouncedMs < ANNOUNCE_COOLDOWN_MS)
			return;
		
		lastAnnounced = antiCheat;
		lastAnnouncedMs = now;
		MC.execute(() -> ChatUtils.message(WURST
			.translate("message.wurst.anticheatdetect.detected", antiCheat)));
	}
	
	public boolean isSetbackDetectionEnabled()
	{
		return setbackDetection.isChecked();
	}
}
