/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.world.entity.player.Player;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.PlayerRangeAlertManager;

@SearchTags({"antisocial", "auto logout", "auto quit", "afk logout"})
public final class AntisocialHack extends Hack
	implements PlayerRangeAlertManager.Listener
{
	public static enum Mode
	{
		QUIT("Quit"),
		CHARS("Chars"),
		SELFHURT("SelfHurt"),
		COMMAND("Command");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lQuit\u00a7r mode just quits the game normally.\n"
			+ "Bypasses NoCheat+ but not CombatLog.\n\n"
			+ "\u00a7lChars\u00a7r mode sends a special chat message that"
			+ " causes the server to kick you.\n"
			+ "Bypasses NoCheat+ and some versions of CombatLog.\n\n"
			+ "\u00a7lSelfHurt\u00a7r mode sends the packet for attacking"
			+ " another player, but with yourself as both the attacker and the"
			+ " target, causing the server to kick you.\n"
			+ "Bypasses both CombatLog and NoCheat+.\n\n"
			+ "\u00a7lCommand\u00a7r mode runs a custom command of your choice.",
		Mode.values(), Mode.QUIT);
	private final CheckboxSetting disableAutoReconnect = new CheckboxSetting(
		"Disable AutoReconnect", "Turns off AutoReconnect after Antisocial"
			+ " disconnects you so you stay hidden.",
		true);
	private final CheckboxSetting ignoreNpcs =
		new CheckboxSetting("Ignore NPCs",
			"Skips players that don't show up on the tab list (matches"
				+ " PlayerESP's ignore-NPC logic).",
			true);
	private final CheckboxSetting ignoreFriends = new CheckboxSetting(
		"Ignore friends",
		"Won't leave when the detected player is on your friends list.", true);
	
	private final TextFieldSetting command = new TextFieldSetting("Command",
		"Used in Command mode. If it starts with '/', it will be sent as a command.",
		"");
	
	private final PlayerRangeAlertManager alertManager =
		WURST.getPlayerRangeAlertManager();
	private boolean triggered;
	
	public AntisocialHack()
	{
		super("Antisocial");
		setCategory(Category.OTHER);
		addSetting(mode);
		addSetting(command);
		addSetting(disableAutoReconnect);
		addSetting(ignoreNpcs);
		addSetting(ignoreFriends);
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
	public void onPlayerEnter(Player player,
		PlayerRangeAlertManager.PlayerInfo info)
	{
		if(triggered)
			return;
		
		if(ignoreNpcs.isChecked() && info.isProbablyNpc())
			return;
		
		if(ignoreFriends.isChecked()
			&& (player != null && WURST.getFriends().isFriend(player)
				|| WURST.getFriends().contains(info.getName())))
			return;
		
		triggered = true;
		
		String intruder =
			player == null ? info.getName() : player.getName().getString();
		
		Mode selectedMode = mode.getSelected();
		ChatUtils.message("\u00a76Antisocial:\u00a7r " + intruder
			+ " entered your range. Leaving via " + selectedMode + " mode.");
		
		leave(selectedMode);
	}
	
	private void leave(Mode selectedMode)
	{
		switch(selectedMode)
		{
			case QUIT -> AutoLeaveHack.Mode.QUIT.leave();
			case CHARS -> AutoLeaveHack.Mode.CHARS.leave();
			case SELFHURT -> AutoLeaveHack.Mode.SELFHURT.leave();
			case COMMAND ->
			{
				if(!runLeaveCommand())
				{
					ChatUtils.error(
						"Antisocial: Command mode selected but command is empty. Falling back to Quit mode.");
					AutoLeaveHack.Mode.QUIT.leave();
				}
			}
		}
		
		if(disableAutoReconnect.isChecked())
			WURST.getHax().autoReconnectHack.setEnabled(false);
		
		setEnabled(false);
	}
	
	private boolean runLeaveCommand()
	{
		if(MC == null || MC.getConnection() == null)
			return false;
		
		String value = command.getValue();
		if(value == null)
			return false;
		
		String trimmed = value.trim();
		if(trimmed.isEmpty())
			return false;
		
		if(trimmed.startsWith("/"))
			MC.getConnection().sendCommand(trimmed.substring(1));
		else
			MC.getConnection().sendChat(trimmed);
		
		return true;
	}
	
	@Override
	public void onPlayerExit(PlayerRangeAlertManager.PlayerInfo info)
	{
		// not needed
	}
}
