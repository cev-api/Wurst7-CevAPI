/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.wurstclient.altmanager.Encryption;

/** Stores and tests the proxies configured for multiplayer. */
public final class ProxyManager
{
	private static final int TEST_TIMEOUT_MS = 5000;
	private static final String GAME_TEST_HOST = "mc.hypixel.net";
	private static final int GAME_TEST_PORT = 25565;
	private static final Logger LOGGER = LogUtils.getLogger();
	
	private final Path path;
	private final Path encryptionFolder;
	private final ArrayList<SocksProxy> proxies = new ArrayList<>();
	private volatile SocksProxy selectedProxy;
	private Encryption encryption;
	
	public ProxyManager(Path path, Path encryptionFolder)
	{
		this.path = path;
		this.encryptionFolder = encryptionFolder;
		load();
	}
	
	public synchronized List<SocksProxy> getProxies()
	{
		return Collections.unmodifiableList(new ArrayList<>(proxies));
	}
	
	public SocksProxy getSelectedProxy()
	{
		return selectedProxy;
	}
	
	public synchronized boolean add(SocksProxy proxy)
	{
		proxy.validateCredentialsForSocks5();
		if(proxies.contains(proxy))
			return false;
		
		proxies.add(proxy);
		save();
		return true;
	}
	
	public synchronized ImportResult importLines(List<String> lines)
	{
		int added = 0;
		int duplicates = 0;
		int invalid = 0;
		for(String line : lines)
		{
			if(line == null || line.isBlank() || line.trim().startsWith("#"))
				continue;
			
			try
			{
				SocksProxy proxy = SocksProxy.parse(line);
				proxy.validateCredentialsForSocks5();
				if(proxies.contains(proxy))
					duplicates++;
				else
				{
					proxies.add(proxy);
					added++;
				}
				
			}catch(IllegalArgumentException e)
			{
				invalid++;
			}
		}
		
		if(added > 0)
			save();
		
		return new ImportResult(added, duplicates, invalid);
	}
	
	public synchronized void select(SocksProxy proxy)
	{
		if(!proxies.contains(proxy))
			throw new IllegalArgumentException(
				"Proxy is no longer in the list.");
		
		selectedProxy = proxy;
		save();
	}
	
	public synchronized void clearSelection()
	{
		if(selectedProxy == null)
			return;
		
		selectedProxy = null;
		save();
	}
	
	public synchronized void remove(SocksProxy proxy)
	{
		if(!proxies.remove(proxy))
			return;
		
		if(proxy.equals(selectedProxy))
			selectedProxy = null;
		
		save();
	}
	
	public String test(SocksProxy proxy) throws IOException
	{
		String endpoint = proxy.getHost() + ":" + proxy.getPort();
		try
		{
			return testSocks5(proxy, endpoint);
		}catch(IOException socksError)
		{
			LOGGER.warn("[Proxy] SOCKS5 test failed for {}: {}", endpoint,
				socksError.toString());
			try
			{
				return testHttpConnect(proxy, endpoint);
			}catch(IOException httpError)
			{
				httpError.addSuppressed(socksError);
				LOGGER.error("[Proxy] All tests failed for {}.", endpoint,
					httpError);
				throw httpError;
			}
		}catch(RuntimeException e)
		{
			LOGGER.error("[Proxy] Test setup failed for {}: {}", endpoint,
				e.toString(), e);
			throw e;
		}
	}
	
	private String testSocks5(SocksProxy proxy, String endpoint)
		throws IOException
	{
		proxy.validateCredentialsForSocks5();
		LOGGER.info("[Proxy] Testing SOCKS5 proxy {}.", endpoint);
		try(Socket socket = new Socket())
		{
			socket.connect(proxy.getAddress(), TEST_TIMEOUT_MS);
			LOGGER.info("[Proxy] TCP connection to {} established.", endpoint);
			socket.setSoTimeout(TEST_TIMEOUT_MS);
			InputStream input = socket.getInputStream();
			OutputStream output = socket.getOutputStream();
			
			if(proxy.hasCredentials())
				output.write(new byte[]{5, 2, 0, 2});
			else
				output.write(new byte[]{5, 1, 0});
			output.flush();
			
			if(readByte(input) != 5)
				throw new IOException(
					"The proxy did not return a SOCKS5 response.");
			
			int method = readByte(input);
			LOGGER.info("[Proxy] {} selected SOCKS5 auth method {}.", endpoint,
				method);
			if(method == 255)
				throw new IOException(
					"The proxy rejected all authentication methods.");
			if(method == 2)
			{
				authenticate(input, output, proxy);
				LOGGER.info("[Proxy] SOCKS5 authentication succeeded for {}.",
					endpoint);
			}else if(method != 0)
				throw new IOException(
					"Unsupported SOCKS5 authentication method.");
			
			testSocks5Connection(input, output);
			
			setProtocol(proxy, ProxyProtocol.SOCKS5);
			LOGGER.info("[Proxy] SOCKS5 test succeeded for {}.", endpoint);
			return method == 2
				? "Valid for Minecraft multiplayer and authenticated."
				: "Valid for Minecraft multiplayer.";
		}catch(IOException e)
		{
			LOGGER.error("[Proxy] SOCKS5 test failed for {}: {}", endpoint,
				e.toString(), e);
			throw e;
		}
	}
	
	private String testHttpConnect(SocksProxy proxy, String endpoint)
		throws IOException
	{
		LOGGER.info("[Proxy] Testing HTTP CONNECT proxy {}.", endpoint);
		try(Socket socket = new Socket())
		{
			socket.connect(proxy.getAddress(), TEST_TIMEOUT_MS);
			LOGGER.info("[Proxy] TCP connection to {} established.", endpoint);
			socket.setSoTimeout(TEST_TIMEOUT_MS);
			
			OutputStream output = socket.getOutputStream();
			StringBuilder request =
				new StringBuilder("CONNECT ").append(GAME_TEST_HOST).append(':')
					.append(GAME_TEST_PORT).append(" HTTP/1.1\r\nHost: ")
					.append(GAME_TEST_HOST).append(':').append(GAME_TEST_PORT)
					.append("\r\nProxy-Connection: Keep-Alive\r\n");
			if(proxy.hasCredentials())
			{
				String credentials =
					proxy.getUsername() + ":" + proxy.getPassword();
				String encoded = Base64.getEncoder().encodeToString(
					credentials.getBytes(StandardCharsets.ISO_8859_1));
				request.append("Proxy-Authorization: Basic ").append(encoded)
					.append("\r\n");
			}
			request.append("\r\n");
			output.write(
				request.toString().getBytes(StandardCharsets.ISO_8859_1));
			output.flush();
			
			String statusLine = readHttpLine(socket.getInputStream());
			if(!statusLine.startsWith("HTTP/"))
				throw new IOException(
					"The proxy did not return an HTTP response.");
			String[] parts = statusLine.split(" ", 3);
			if(parts.length < 2 || !"200".equals(parts[1]))
				throw new IOException(
					"HTTP proxy CONNECT returned: " + statusLine);
			
			setProtocol(proxy, ProxyProtocol.HTTP);
			LOGGER.info("[Proxy] HTTP CONNECT test succeeded for {}.",
				endpoint);
			return proxy.hasCredentials()
				? "Valid for Minecraft multiplayer and authenticated."
				: "Valid for Minecraft multiplayer.";
		}catch(IOException e)
		{
			LOGGER.error("[Proxy] HTTP CONNECT test failed for {}: {}",
				endpoint, e.toString(), e);
			throw e;
		}
	}
	
	private void testSocks5Connection(InputStream input, OutputStream output)
		throws IOException
	{
		byte[] host = GAME_TEST_HOST.getBytes(StandardCharsets.US_ASCII);
		output.write(new byte[]{5, 1, 0, 3, (byte)host.length});
		output.write(host);
		output.write(GAME_TEST_PORT >>> 8);
		output.write(GAME_TEST_PORT);
		output.flush();
		
		if(readByte(input) != 5)
			throw new IOException(
				"The proxy did not return a SOCKS5 response.");
		int result = readByte(input);
		readByte(input); // reserved byte
		int addressType = readByte(input);
		switch(addressType)
		{
			case 1 -> skipBytes(input, 4);
			case 3 -> skipBytes(input, readByte(input));
			case 4 -> skipBytes(input, 16);
			default -> throw new IOException(
				"The proxy returned an invalid SOCKS5 address type.");
		}
		skipBytes(input, 2); // destination port
		if(result != 0)
			throw new IOException(
				"SOCKS5 proxy could not connect to a Minecraft server (code "
					+ result + ").");
	}
	
	private void skipBytes(InputStream input, int count) throws IOException
	{
		for(int i = 0; i < count; i++)
			readByte(input);
	}
	
	private static String readHttpLine(InputStream input) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while(bytes.size() < 8192)
		{
			int value = input.read();
			if(value < 0)
				throw new IOException(
					"The proxy closed the connection before sending a response.");
			if(value == '\n')
				return bytes.toString(StandardCharsets.ISO_8859_1).trim();
			if(value != '\r')
				bytes.write(value);
		}
		
		throw new IOException("The HTTP proxy response line was too long.");
	}
	
	private synchronized void setProtocol(SocksProxy proxy,
		ProxyProtocol protocol)
	{
		if(!proxies.contains(proxy))
			return;
		
		proxy.setProtocol(protocol);
		save();
	}
	
	private void authenticate(InputStream input, OutputStream output,
		SocksProxy proxy) throws IOException
	{
		if(!proxy.hasCredentials())
			throw new IOException(
				"The proxy requires a username and password.");
		
		byte[] username = proxy.getUsername().getBytes(StandardCharsets.UTF_8);
		byte[] password = proxy.getPassword().getBytes(StandardCharsets.UTF_8);
		output.write(1);
		output.write(username.length);
		output.write(username);
		output.write(password.length);
		output.write(password);
		output.flush();
		
		if(readByte(input) != 1 || readByte(input) != 0)
			throw new IOException(
				"The proxy rejected the username or password.");
	}
	
	private int readByte(InputStream input) throws IOException
	{
		int value = input.read();
		if(value < 0)
			throw new IOException(
				"The proxy closed the connection unexpectedly.");
		
		return value;
	}
	
	private synchronized void load()
	{
		try
		{
			if(!Files.exists(path))
				return;
			
			Encryption encryption = getEncryption();
			JsonObject root =
				JsonParser.parseString(encryption.loadEncryptedFile(path))
					.getAsJsonObject();
			String selectedId =
				root.has("selected") ? root.get("selected").getAsString() : "";
			JsonArray entries = root.has("proxies")
				? root.getAsJsonArray("proxies") : new JsonArray();
			for(int i = 0; i < entries.size(); i++)
			{
				if(!entries.get(i).isJsonObject())
					continue;
				
				JsonObject json = entries.get(i).getAsJsonObject();
				ProxyProtocol protocol = json.has("protocol")
					? ProxyProtocol.valueOf(json.get("protocol").getAsString())
					: ProxyProtocol.SOCKS5;
				SocksProxy proxy = new SocksProxy(
					json.get("host").getAsString(), json.get("port").getAsInt(),
					json.has("username") ? json.get("username").getAsString()
						: "",
					json.has("password") ? json.get("password").getAsString()
						: "",
					protocol);
				if(proxies.contains(proxy))
					continue;
				
				proxies.add(proxy);
				if(proxy.getStorageId().equals(selectedId))
					selectedProxy = proxy;
			}
			
		}catch(Exception e)
		{
			System.err.println("Couldn't load multiplayer proxies.");
			e.printStackTrace();
			proxies.clear();
			selectedProxy = null;
		}
	}
	
	private synchronized void save()
	{
		try
		{
			JsonObject root = new JsonObject();
			root.addProperty("selected",
				selectedProxy == null ? "" : selectedProxy.getStorageId());
			JsonArray entries = new JsonArray();
			for(SocksProxy proxy : proxies)
			{
				JsonObject json = new JsonObject();
				json.addProperty("host", proxy.getHost());
				json.addProperty("port", proxy.getPort());
				json.addProperty("username", proxy.getUsername());
				json.addProperty("password", proxy.getPassword());
				json.addProperty("protocol", proxy.getProtocol().name());
				entries.add(json);
			}
			root.add("proxies", entries);
			
			getEncryption().toEncryptedJson(root, path);
		}catch(Exception e)
		{
			System.err.println("Couldn't save multiplayer proxies.");
			e.printStackTrace();
		}
	}
	
	private Encryption getEncryption() throws IOException
	{
		if(encryption == null)
			encryption = new Encryption(encryptionFolder);
		
		return encryption;
	}
	
	public record ImportResult(int added, int duplicates, int invalid)
	{}
}
