/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import net.minecraft.network.chat.Component;
import net.wurstclient.proxy.SocksProxy;
import net.wurstclient.util.BanMemoryManager.ConnectionContext;

public final class BanHistoryRegressionTest
{
	public static void main(String[] args) throws Exception
	{
		Path folder = Files.createTempDirectory("wurst-ban-history-test-");
		try
		{
			SocksProxy first = SocksProxy
				.parse("socks5://alice:verySecret@proxy.example:1080");
			SocksProxy otherCredentials = SocksProxy
				.parse("socks5://bob:anotherSecret@proxy.example:1080");
			SocksProxy second =
				SocksProxy.parse("socks5://second.example:1080");
			BanMemoryManager memory = new BanMemoryManager(folder);
			memory.rememberAttempt(new ConnectionContext("one.example:25565",
				"One", "PlayerOne", "uuid-one", "ip|203.0.113.7",
				"SOCKS5 proxy exit IP proxy.example:1080",
				BanMemoryManager.proxyKey(first)));
			memory.rememberDisconnect(
				Component.literal("You are banned for griefing"));
			check(memory.getProxyBanHistory(first).size() == 1,
				"Exact proxy should match its recorded public exit IP");
			check(memory.getProxyBanHistory(otherCredentials).isEmpty(),
				"Different proxy credentials must not inherit an unrelated exit-IP ban");
			check(
				memory.getProxyBanHistory(first).getFirst().type()
					.equals("Used during account ban"),
				"An account ban must not be presented as a confirmed IP ban");
			memory.rememberAttempt(new ConnectionContext("one.example:25565",
				"One", "PlayerTwo", "uuid-two", "ip|203.0.113.7",
				"SOCKS5 proxy exit IP second.example:1080",
				BanMemoryManager.proxyKey(second)));
			check(memory.getProxyBanHistory(second).size() == 1,
				"A second proxy using the same banned public IP must retain the association");
			memory.rememberAttempt(new ConnectionContext("two.example:25565",
				"Two", "PlayerOne", "uuid-one", "ip|203.0.113.8", "public IP"));
			memory.rememberDisconnect(
				Component.literal("Your IP address is banned"));
			check(memory.getAccountBanHistory("playerone").size() == 2,
				"Alt history must span servers and match names case-insensitively");
			check(
				memory.getAccountBanHistory("NotPlayerOne").isEmpty()
					&& memory.getAccountBanHistory("").isEmpty(),
				"Unknown or unrelated alts must not match");
			check(
				memory.getDirectBanHistory().getFirst().type().equals("IP ban"),
				"Explicit direct IP bans must retain their classification");
			memory.rememberAttempt(new ConnectionContext("legacy.example:25565",
				"Legacy", "LegacyPlayer", "",
				"proxy|SOCKS5|legacy-proxy.example|1080",
				"proxy legacy-proxy.example:1080"));
			memory.rememberDisconnect(Component.literal("You are banned"));
			Files.writeString(folder.resolve("corrupt.json"), "invalid json");
			BanMemoryManager reloaded = new BanMemoryManager(folder);
			reloaded.loadHistory();
			check(
				reloaded.getAccountBanHistory("PlayerOne").size() == 2
					&& reloaded.getProxyBanHistory(first).size() == 1
					&& reloaded.getProxyBanHistory(second).size() == 1,
				"Account and proxy history must survive restarting the manager");
			check(
				reloaded
					.getProxyBanHistory(
						SocksProxy.parse("legacy-proxy.example:1080"))
					.size() == 1,
				"Existing records without proxy aliases must remain readable");
			check(
				reloaded
					.getProxyBanHistory(
						SocksProxy.parse("http://legacy-proxy.example:1080"))
					.isEmpty(),
				"Legacy proxy records must retain their protocol association");
			String tooltip = BanMemoryManager
				.historyTooltip(reloaded.getAccountBanHistory("PlayerOne"))
				.stream().map(Component::getString)
				.reduce("", (a, b) -> a + "\n" + b);
			check(
				tooltip.contains("one.example:25565")
					&& tooltip.contains("two.example:25565"),
				"Tooltips must identify the banned servers");
			try(var files = Files.list(folder))
			{
				for(Path file : files.toList())
					check(!Files.readString(file).contains("verySecret"),
						"Proxy credentials must not be written to ban history");
			}
			System.out.println(
				"Ban history checks passed: per-server alts, proxy exit-IP associations, credentials isolation, direct IPs, legacy records, restart and corrupt-file handling.");
		}finally
		{
			check(
				folder.toAbsolutePath().normalize()
					.startsWith(Path.of(System.getProperty("java.io.tmpdir"))
						.toAbsolutePath().normalize())
					&& folder.getFileName().toString()
						.startsWith("wurst-ban-history-test-"),
				"Cleanup must remain inside the test temp directory");
			try(var files = Files.walk(folder))
			{
				for(Path file : files.sorted(Comparator.reverseOrder())
					.toList())
					Files.delete(file);
			}
		}
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
