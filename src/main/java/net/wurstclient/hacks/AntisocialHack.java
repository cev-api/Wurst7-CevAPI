/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.entity.player.PlayerEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.PlayerRangeAlertManager;

@SearchTags({"antisocial", "auto logout", "auto quit", "afk logout"})
public final class AntisocialHack extends Hack
	implements PlayerRangeAlertManager.Listener
{
	private final EnumSetting<AutoLeaveHack.Mode> mode = new EnumSetting<>(
		"Mode",
		"\u00a7lQuit\u00a7r mode just quits the game normally.\n"
			+ "Bypasses NoCheat+ but not CombatLog.\n\n"
			+ "\u00a7lChars\u00a7r mode sends a special chat message that"
			+ " causes the server to kick you.\n"
			+ "Bypasses NoCheat+ and some versions of CombatLog.\n\n"
			+ "\u00a7lSelfHurt\u00a7r mode sends the packet for attacking"
			+ " another player, but with yourself as both the attacker and the"
			+ " target, causing the server to kick you.\n"
			+ "Bypasses both CombatLog and NoCheat+.",
		AutoLeaveHack.Mode.values(), AutoLeaveHack.Mode.QUIT);
	private final CheckboxSetting disableAutoReconnect = new CheckboxSetting(
		"Disable AutoReconnect", "Turns off AutoReconnect after Antisocial"
			+ " disconnects you so you stay hidden.",
		true);
	private final CheckboxSetting ignoreNpcs =
		new CheckboxSetting("Ignore NPCs",
			"Skips players that don't show up on the tab list (matches"
				+ " PlayerESP's ignore-NPC logic).",
			true);
	
	private final PlayerRangeAlertManager alertManager =
		WURST.getPlayerRangeAlertManager();
	private boolean triggered;
	
	public AntisocialHack()
	{
		super("Antisocial");
		setCategory(Category.COMBAT);
		addSetting(mode);
		addSetting(disableAutoReconnect);
		addSetting(ignoreNpcs);
	}
	
	@Override
	protected void onEnable()
	{
		alertManager.addListener(this);
	}
	
	@Override
	protected void onDisable()
	{
		alertManager.removeListener(this);
		triggered = false;
	}
	
	@Override
	public void onPlayerEnter(PlayerEntity player,
		PlayerRangeAlertManager.PlayerInfo info)
	{
		if(triggered)
			return;
		
		if(ignoreNpcs.isChecked() && info.isProbablyNpc())
			return;
		
		triggered = true;
		
		String intruder =
			player == null ? info.getName() : player.getName().getString();
		ChatUtils.message("\u00a76Antisocial:\u00a7r " + intruder
			+ " entered your range. Leaving via "
			+ mode.getSelected().toString() + " mode.");
		
		mode.getSelected().leave();
		
		if(disableAutoReconnect.isChecked())
			WURST.getHax().autoReconnectHack.setEnabled(false);
		
		setEnabled(false);
	}
	
	@Override
	public void onPlayerExit(PlayerRangeAlertManager.PlayerInfo info)
	{
		// not needed
	}
}
