/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * A multiplayer proxy, optionally protected by username/password
 * authentication.
 */
public final class SocksProxy
{
	public static final int DEFAULT_PORT = 1080;
	
	private final String host;
	private final int port;
	private final String username;
	private final String password;
	private final boolean protocolExplicit;
	private volatile String resolvedHost;
	private volatile ProxyProtocol protocol;
	
	public SocksProxy(String host, int port, String username, String password)
	{
		this(host, port, username, password, ProxyProtocol.SOCKS5);
	}
	
	public SocksProxy(String host, int port, String username, String password,
		ProxyProtocol protocol)
	{
		this(host, port, username, password, protocol, false);
	}
	
	public SocksProxy(String host, int port, String username, String password,
		ProxyProtocol protocol, boolean protocolExplicit)
	{
		this(host, port, username, password, protocol, protocolExplicit, null);
	}
	
	public SocksProxy(String host, int port, String username, String password,
		ProxyProtocol protocol, boolean protocolExplicit, String resolvedHost)
	{
		this.host = requireNonBlank(host, "Proxy host");
		if(port < 1 || port > 65535)
			throw new IllegalArgumentException("Proxy port must be 1-65535.");
		
		this.port = port;
		this.username = username == null ? "" : username;
		this.password = password == null ? "" : password;
		this.protocol = Objects.requireNonNull(protocol, "Proxy protocol");
		this.protocolExplicit = protocolExplicit;
		this.resolvedHost = resolvedHost == null || resolvedHost.isBlank()
			? null : resolvedHost.trim();
	}
	
	public static SocksProxy parse(String text)
	{
		if(text == null || text.isBlank())
			throw new IllegalArgumentException(
				"Enter host:port, socks5://host:port, or a credential format.");
		
		String trimmed = text.trim();
		ProxyProtocol protocol = ProxyProtocol.SOCKS5;
		int schemeSeparator = trimmed.indexOf("://");
		if(schemeSeparator >= 0)
		{
			String scheme =
				trimmed.substring(0, schemeSeparator).toLowerCase(Locale.ROOT);
			protocol = switch(scheme)
			{
				case "socks5" -> ProxyProtocol.SOCKS5;
				case "http" -> ProxyProtocol.HTTP;
				default -> throw new IllegalArgumentException(
					"Unsupported proxy protocol: " + scheme);
			};
			trimmed = trimmed.substring(schemeSeparator + 3).trim();
		}
		
		int passwordSeparator = trimmed.lastIndexOf(':');
		int usernameSeparator =
			findUsernameSeparator(trimmed, passwordSeparator);
		String endpoint = trimmed;
		String username = "";
		String password = "";
		int atSeparator = trimmed.lastIndexOf('@');
		if(atSeparator > 0)
		{
			String userInfo = trimmed.substring(0, atSeparator);
			endpoint = trimmed.substring(atSeparator + 1).trim();
			int separator = userInfo.indexOf(':');
			if(separator <= 0)
				throw new IllegalArgumentException(
					"Proxy credentials must use username:password.");
			username = decodeProxyPart(userInfo.substring(0, separator));
			password = decodeProxyPart(userInfo.substring(separator + 1));
		}else if(usernameSeparator > 0)
		{
			endpoint = trimmed.substring(0, usernameSeparator).trim();
			username =
				trimmed.substring(usernameSeparator + 1, passwordSeparator);
			password = trimmed.substring(passwordSeparator + 1);
			// Credential-form entries are SOCKS5 unless the protocol was
			// explicitly specified (for example, http://...).
		}
		
		Endpoint parsedEndpoint = parseEndpoint(endpoint);
		return new SocksProxy(parsedEndpoint.host(), parsedEndpoint.port(),
			username, password, protocol, schemeSeparator >= 0);
	}
	
	private static String decodeProxyPart(String value)
	{
		try
		{
			return URLDecoder.decode(value,
				java.nio.charset.StandardCharsets.UTF_8);
		}catch(IllegalArgumentException e)
		{
			throw new IllegalArgumentException(
				"Invalid encoded proxy credentials.", e);
		}
	}
	
	private static int findUsernameSeparator(String value,
		int passwordSeparator)
	{
		if(passwordSeparator <= 0)
			return -1;
		
		int separator = value.lastIndexOf(':', passwordSeparator - 1);
		if(!value.startsWith("["))
			return separator;
		
		int closingBracket = value.indexOf(']');
		return closingBracket >= 0 && separator > closingBracket ? separator
			: -1;
	}
	
	private static Endpoint parseEndpoint(String endpoint)
	{
		if(endpoint.isBlank())
			throw new IllegalArgumentException("Proxy IP cannot be empty.");
		
		String host = endpoint;
		int port = DEFAULT_PORT;
		if(endpoint.startsWith("["))
		{
			int closingBracket = endpoint.indexOf(']');
			if(closingBracket < 0)
				throw new IllegalArgumentException(
					"Invalid bracketed IPv6 address.");
			
			host = endpoint.substring(1, closingBracket);
			String portPart = endpoint.substring(closingBracket + 1);
			if(!portPart.isEmpty())
			{
				if(!portPart.startsWith(":"))
					throw new IllegalArgumentException(
						"Invalid proxy endpoint.");
				port = parsePort(portPart.substring(1));
			}
		}else
		{
			int separator = endpoint.lastIndexOf(':');
			if(separator > 0 && isPort(endpoint.substring(separator + 1)))
			{
				host = endpoint.substring(0, separator);
				port = parsePort(endpoint.substring(separator + 1));
			}
		}
		
		return new Endpoint(requireNonBlank(host, "Proxy IP"), port);
	}
	
	private static boolean isPort(String value)
	{
		if(value.isEmpty() || value.length() > 5)
			return false;
		
		for(int i = 0; i < value.length(); i++)
			if(!Character.isDigit(value.charAt(i)))
				return false;
			
		return true;
	}
	
	private static int parsePort(String value)
	{
		if(!isPort(value))
			throw new IllegalArgumentException("Proxy port must be numeric.");
		
		try
		{
			int port = Integer.parseInt(value);
			if(port < 1 || port > 65535)
				throw new IllegalArgumentException(
					"Proxy port must be 1-65535.");
			
			return port;
		}catch(NumberFormatException e)
		{
			throw new IllegalArgumentException("Proxy port must be numeric.",
				e);
		}
	}
	
	private static String requireNonBlank(String value, String name)
	{
		if(value == null || value.isBlank())
			throw new IllegalArgumentException(name + " cannot be empty.");
		
		return value.trim();
	}
	
	public InetSocketAddress getAddress()
	{
		return new InetSocketAddress(resolvedHost == null ? host : resolvedHost,
			port);
	}
	
	public String getResolvedHost()
	{
		return resolvedHost;
	}
	
	public boolean resolveHost()
	{
		try
		{
			String address = InetAddress.getByName(host).getHostAddress();
			if(address == null || address.isBlank())
				return false;
			resolvedHost = address;
			return true;
		}catch(Exception e)
		{
			return false;
		}
	}
	
	/** Converts this entry to the JDK proxy type used by HTTP connections. */
	public Proxy toJavaProxy()
	{
		return new Proxy(
			protocol == ProxyProtocol.HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
			getAddress());
	}
	
	public boolean isProtocolExplicit()
	{
		return protocolExplicit;
	}
	
	public String getHost()
	{
		return host;
	}
	
	public int getPort()
	{
		return port;
	}
	
	public String getUsername()
	{
		return username;
	}
	
	public String getPassword()
	{
		return password;
	}
	
	public ProxyProtocol getProtocol()
	{
		return protocol;
	}
	
	public void setProtocol(ProxyProtocol protocol)
	{
		this.protocol = Objects.requireNonNull(protocol, "Proxy protocol");
	}
	
	public boolean hasCredentials()
	{
		return !username.isEmpty() || !password.isEmpty();
	}
	
	public void validateCredentialsForSocks5()
	{
		if(!hasCredentials())
			return;
		
		if(username.isEmpty() || password.isEmpty())
			throw new IllegalArgumentException(
				"SOCKS5 authentication needs both a username and password.");
		if(username.getBytes(StandardCharsets.UTF_8).length > 255
			|| password.getBytes(StandardCharsets.UTF_8).length > 255)
		{
			throw new IllegalArgumentException(
				"SOCKS5 usernames and passwords must be 255 bytes or fewer.");
		}
	}
	
	public String getDisplayName()
	{
		String endpoint =
			formatEndpoint(resolvedHost == null ? host : resolvedHost);
		if(resolvedHost != null && !resolvedHost.equalsIgnoreCase(host))
			endpoint += " (" + formatEndpoint(host) + ")";
		return endpoint + (hasCredentials() ? " (" + username + ")"
			: " (no authentication)");
	}
	
	public String getStorageId()
	{
		return host + "\u0000" + port + "\u0000" + username + "\u0000"
			+ password;
	}
	
	private String formatEndpoint()
	{
		return formatEndpoint(host);
	}
	
	private String formatEndpoint(String value)
	{
		String formattedHost =
			value.indexOf(':') >= 0 ? "[" + value + "]" : value;
		return formattedHost + ":" + port;
	}
	
	@Override
	public int hashCode()
	{
		return Objects.hash(host, port, username, password);
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if(this == obj)
			return true;
		if(!(obj instanceof SocksProxy other))
			return false;
		
		return port == other.port && host.equals(other.host)
			&& username.equals(other.username)
			&& password.equals(other.password);
	}
	
	private record Endpoint(String host, int port)
	{}
}
