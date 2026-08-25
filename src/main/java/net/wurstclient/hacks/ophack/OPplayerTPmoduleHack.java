/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

public final class OPplayerTPmoduleHack extends Hack
{
	private final CheckboxSetting teleportPlayersToMe = new CheckboxSetting(
		"TP players to you", "Reverse the teleport direction.", false);
	private final CheckboxSetting ignoreFriends = new CheckboxSetting(
		"Ignore friends", "Compatibility option for friend filtering.", true);
	private final CheckboxSetting disableIfNotOp =
		new CheckboxSetting("Disable if not OP",
			"Disable immediately when no connection is available.", false);
	private int currentPlayer;
	
	public OPplayerTPmoduleHack()
	{
		super("OPplayerTPmodule",
			"Teleports through online players one at a time.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(teleportPlayersToMe);
		addSetting(ignoreFriends);
		addSetting(disableIfNotOp);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null || MC.getConnection() == null)
		{
			setEnabled(false);
			return;
		}
		java.util.ArrayList<PlayerInfo> players =
			new java.util.ArrayList<>(MC.getConnection().getOnlinePlayers());
		players.removeIf(p -> p.getProfile().id().equals(MC.player.getUUID()));
		if(players.isEmpty())
		{
			setEnabled(false);
			return;
		}
		if(currentPlayer >= players.size())
			currentPlayer = 0;
		String target = players.get(currentPlayer++).getProfile().name();
		String self = MC.player.getName().getString();
		String command = teleportPlayersToMe.isChecked()
			? "tp " + target + " " + self : "tp " + self + " " + target;
		MC.getConnection().sendCommand(command);
		if(currentPlayer >= players.size())
			currentPlayer = 0;
		setEnabled(false);
	}
}
