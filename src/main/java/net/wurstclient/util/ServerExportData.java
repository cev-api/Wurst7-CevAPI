/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;

public final class ServerExportData
{
	private ServerExportData()
	{}
	
	public static final class ExportedServerList
	{
		public String format = "wurst-multiplayer-servers";
		public int formatVersion = 2;
		public String exportedAt;
		public String[] panelTitles;
		public List<ExportedServer> servers = new ArrayList<>();
		
		public ExportedServerList(int panelCount)
		{
			panelTitles = new String[panelCount];
		}
		
		public ExportedServerList()
		{}
	}
	
	public static final class ExportedServer
	{
		public String name;
		public String ip;
		public String motd;
		public String status;
		public String version;
		public int protocol;
		public long ping;
		public int onlinePlayers;
		public int maxPlayers;
		public int panel;
		public String panelTitle;
		public int savedIndex;
		public String type;
		public String state;
		public String resourcePackStatus;
		public boolean lan;
		public boolean realm;
		public String iconBase64;
		public List<String> playerList = new ArrayList<>();
		public List<ExportedPlayer> playerSample = new ArrayList<>();
		
		public static ExportedServer from(ServerData server, int panel,
			String panelTitle, int savedIndex)
		{
			ExportedServer exported = new ExportedServer();
			exported.name = server.name;
			exported.ip = server.ip;
			exported.motd = server.motd != null ? server.motd.getString() : "";
			exported.status =
				server.status != null ? server.status.getString() : "";
			exported.version =
				server.version != null ? server.version.getString() : "";
			exported.protocol = server.protocol;
			exported.ping = server.ping;
			exported.type = server.type().name();
			exported.state = server.state().name();
			exported.resourcePackStatus = server.getResourcePackStatus().name();
			exported.lan = server.isLan();
			exported.realm = server.isRealm();
			byte[] icon = server.getIconBytes();
			if(icon != null)
				exported.iconBase64 = Base64.getEncoder().encodeToString(icon);
			if(server.playerList != null)
				for(Component player : server.playerList)
					exported.playerList.add(player.getString());
			if(server.players != null)
			{
				exported.onlinePlayers = server.players.online();
				exported.maxPlayers = server.players.max();
				for(NameAndId player : server.players.sample())
					exported.playerSample.add(ExportedPlayer.from(player));
			}
			exported.panel = panel;
			exported.panelTitle = panelTitle;
			exported.savedIndex = savedIndex;
			return exported;
		}
	}
	
	public static final class ExportedPlayer
	{
		public String id;
		public String name;
		
		public static ExportedPlayer from(NameAndId player)
		{
			ExportedPlayer exported = new ExportedPlayer();
			exported.id = player.id().toString();
			exported.name = player.name();
			return exported;
		}
	}
}
