/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.proxy;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import net.wurstclient.WurstClient;

/**
 * Adds the selected proxy to Minecraft's normal connection pipeline.
 */
public enum ProxyConnection
{
	;
	
	private static final Logger LOGGER = LogUtils.getLogger();
	
	/**
	 * Adds a proxy handler before Minecraft configures its packet handlers.
	 * Keeping the normal {@code Connection.connect()} call intact is important
	 * for compatibility with mods that configure the connection there.
	 */
	public static void addSelectedProxyHandler(ChannelPipeline pipeline)
	{
		SocksProxy proxy =
			WurstClient.INSTANCE.getProxyManager().getSelectedProxy();
		if(proxy == null)
			return;
		
		proxy.validateCredentialsForSocks5();
		String endpoint = proxy.getHost() + ":" + proxy.getPort();
		LOGGER.info("[Proxy] Adding {} proxy {} to the connection pipeline.",
			proxy.getProtocol().getDisplayName(), endpoint);
		if(proxy.getProtocol() == ProxyProtocol.HTTP)
		{
			if(proxy.hasCredentials())
				pipeline.addFirst("wurst_http_proxy",
					new HttpProxyHandler(proxy.getAddress(),
						proxy.getUsername(), proxy.getPassword()));
			else
				pipeline.addFirst("wurst_http_proxy",
					new HttpProxyHandler(proxy.getAddress()));
		}else if(proxy.hasCredentials())
			pipeline.addFirst("wurst_socks5_proxy", new Socks5ProxyHandler(
				proxy.getAddress(), proxy.getUsername(), proxy.getPassword()));
		else
			pipeline.addFirst("wurst_socks5_proxy",
				new Socks5ProxyHandler(proxy.getAddress()));
	}
}
