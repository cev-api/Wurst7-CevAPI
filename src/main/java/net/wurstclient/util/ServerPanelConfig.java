/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

public final class ServerPanelConfig
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final int DEFAULT_PANEL = 1;
	
	private final String[] panelTitles = {"Servers", "Favorites", "Testing"};
	private final Map<String, Integer> panels = new HashMap<>();
	private final boolean[] panelVisible = {true, true, true};
	private final ArrayList<Integer> closedPanels = new ArrayList<>();
	
	public String getTitle(int panel)
	{
		return panelTitles[panel];
	}
	
	public void setTitle(int panel, String title)
	{
		panelTitles[panel] = title.isBlank() ? "Panel " + (panel + 1) : title;
	}
	
	public int getPanel(ServerData server)
	{
		return panels.getOrDefault(key(server), DEFAULT_PANEL);
	}
	
	public void setPanel(ServerData server, int panel)
	{
		panels.put(key(server), Math.clamp(panel, 0, 2));
	}
	
	public boolean isPanelVisible(int panel)
	{
		return panelVisible[Math.clamp(panel, 0, 2)];
	}
	
	public void closePanel(int panel)
	{
		if(panel == DEFAULT_PANEL || !isPanelVisible(panel))
			return;
		
		panelVisible[panel] = false;
		closedPanels.remove(Integer.valueOf(panel));
		closedPanels.add(panel);
	}
	
	public boolean reopenLastPanel()
	{
		while(!closedPanels.isEmpty())
		{
			int panel = closedPanels.removeLast();
			if(panel == DEFAULT_PANEL || isPanelVisible(panel))
				continue;
			
			panelVisible[panel] = true;
			return true;
		}
		
		for(int panel = 0; panel < panelVisible.length; panel++)
			if(panel != DEFAULT_PANEL && !panelVisible[panel])
			{
				panelVisible[panel] = true;
				return true;
			}
		
		return false;
	}
	
	public boolean hasClosedPanels()
	{
		for(int panel = 0; panel < panelVisible.length; panel++)
			if(panel != DEFAULT_PANEL && !panelVisible[panel])
				return true;
			
		return false;
	}
	
	public void remove(ServerData server)
	{
		panels.remove(key(server));
	}
	
	public void clearServers()
	{
		panels.clear();
	}
	
	public static ServerPanelConfig load(Minecraft minecraft)
	{
		Path path = getPath(minecraft);
		if(!Files.isRegularFile(path))
			return new ServerPanelConfig();
		
		try(Reader reader = Files.newBufferedReader(path))
		{
			ServerPanelConfig config =
				GSON.fromJson(reader, ServerPanelConfig.class);
			if(config == null)
				return new ServerPanelConfig();
			
			config.panelVisible[DEFAULT_PANEL] = true;
			config.closedPanels.removeIf(panel -> panel == null || panel < 0
				|| panel >= config.panelVisible.length || panel == DEFAULT_PANEL
				|| config.panelVisible[panel]);
			return config;
			
		}catch(IOException | RuntimeException e)
		{
			return new ServerPanelConfig();
		}
	}
	
	public void save(Minecraft minecraft)
	{
		Path path = getPath(minecraft);
		try
		{
			Files.createDirectories(path.getParent());
			try(Writer writer = Files.newBufferedWriter(path))
			{
				GSON.toJson(this, writer);
			}
		}catch(IOException e)
		{
			// The multiplayer screen should stay usable even if config saving
			// fails because of a filesystem permission issue.
		}
	}
	
	private static Path getPath(Minecraft minecraft)
	{
		return minecraft.gameDirectory.toPath().resolve("wurst")
			.resolve("server-panels.json");
	}
	
	private static String key(ServerData server)
	{
		return server.ip.toLowerCase() + "\n" + server.name.toLowerCase();
	}
}
