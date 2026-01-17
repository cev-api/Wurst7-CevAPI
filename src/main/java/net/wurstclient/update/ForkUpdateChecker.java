/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.update;

import java.net.URI;
import java.net.URLConnection;
import net.wurstclient.WurstClient;
import net.wurstclient.config.BuildConfig;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonArray;
import net.wurstclient.util.json.WsonObject;

public final class ForkUpdateChecker implements UpdateListener
{
	private Thread thread;
	private volatile String statusSuffix = "";
	
	@Override
	public void onUpdate()
	{
		if(thread == null)
		{
			thread = new Thread(this::checkForUpdates, "ForkUpdateChecker");
			thread.start();
			return;
		}
		
		if(thread.isAlive())
			return;
		
		WurstClient.INSTANCE.getEventManager().remove(UpdateListener.class,
			this);
	}
	
	public void startIfNeeded()
	{
		if(thread != null)
			return;
		
		thread = new Thread(this::checkForUpdates, "ForkUpdateChecker");
		thread.start();
	}
	
	public String getStatusSuffix()
	{
		return statusSuffix;
	}
	
	private void checkForUpdates()
	{
		if(System.getProperty("fabric.client.gametest") != null)
			return;
		
		if(BuildConfig.GH_REPO_ID == null || BuildConfig.GH_REPO_ID.isBlank())
			return;
		
		Version currentRelease =
			new Version(stripTagPrefix(BuildConfig.FORK_RELEASE_VERSION));
		Version latestRelease = null;
		
		try
		{
			latestRelease = fetchLatestRelease();
			
		}catch(Exception e)
		{
			System.err.println("[ForkUpdateChecker] An error occurred!");
			e.printStackTrace();
			return;
		}
		
		if(latestRelease == null || latestRelease.isInvalid()
			|| currentRelease.isInvalid())
			return;
		
		if(currentRelease.isLowerThan(latestRelease))
		{
			statusSuffix =
				" (Out of Date: Latest Version is " + latestRelease + ")";
			return;
		}
	}
	
	private Version fetchLatestRelease() throws Exception
	{
		String releasesUrl = "https://api.github.com/repos/"
			+ BuildConfig.GH_REPO_ID + "/releases";
		WsonArray releases =
			JsonUtils.parseConnectionToArray(openGitHubConnection(releasesUrl));
		
		for(WsonObject release : releases.getAllObjects())
		{
			if(release.getBoolean("draft", false))
				continue;
			
			if(release.getBoolean("prerelease", false))
				continue;
			
			String tagName = release.getString("tag_name", "");
			return new Version(stripTagPrefix(tagName));
		}
		
		return null;
	}
	
	private URLConnection openGitHubConnection(String url) throws Exception
	{
		URLConnection connection = URI.create(url).toURL().openConnection();
		connection.setRequestProperty("Accept", "application/vnd.github+json");
		connection.setRequestProperty("User-Agent", "Wurst7-CevAPI");
		return connection;
	}
	
	private String stripTagPrefix(String version)
	{
		if(version == null)
			return "";
		
		return version.startsWith("v") ? version.substring(1) : version;
	}
}
