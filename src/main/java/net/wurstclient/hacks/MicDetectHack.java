/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;

/** Announces decoded Simple Voice Chat PlayerSoundPacket activity. */
@SearchTags({"mic", "microphone", "voice chat", "simple voice chat",
	"sound detect"})
public final class MicDetectHack extends Hack
{
	private final SliderSetting timeout = new SliderSetting("Timeout",
		"Minimum number of seconds between alerts for the same player.", 3, 0,
		60, 0.5, ValueDisplay.DECIMAL);
	private final Map<UUID, Long> lastAlerts = new ConcurrentHashMap<>();
	
	public MicDetectHack()
	{
		super("MicDetect");
		setCategory(Category.INTEL);
		addSetting(timeout);
	}
	
	@Override
	protected void onDisable()
	{
		lastAlerts.clear();
	}
	
	/**
	 * Called by the passive SVC decoder hook, possibly off the client thread.
	 */
	public void onPlayerSound(UUID senderUuid, double voiceDistance)
	{
		if(!isEnabled() || senderUuid == null
			|| !Double.isFinite(voiceDistance))
			return;
		
		long now = System.currentTimeMillis();
		long timeoutMillis = Math.round(timeout.getValue() * 1000.0);
		Long last = lastAlerts.get(senderUuid);
		if(last != null && now - last < timeoutMillis)
			return;
		lastAlerts.put(senderUuid, now);
		
		MC.execute(() -> announce(senderUuid, voiceDistance));
	}
	
	private void announce(UUID senderUuid, double voiceDistance)
	{
		if(!isEnabled() || MC.getConnection() == null)
			return;
		
		PlayerInfo info = MC.getConnection().getPlayerInfo(senderUuid);
		String name = info != null && info.getProfile() != null
			? info.getProfile().name() : senderUuid.toString();
		ChatUtils.message("MicDetect: " + name + " made a sound within "
			+ formatDistance(voiceDistance) + " blocks.");
	}
	
	private static String formatDistance(double distance)
	{
		return distance == Math.rint(distance) ? String.valueOf((int)distance)
			: String.format(java.util.Locale.ROOT, "%.1f", distance);
	}
}
