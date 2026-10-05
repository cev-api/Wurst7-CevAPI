/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;

/** Optional ViaFabricPlus integration without a required mod dependency. */
public final class OfflineProtocolCompat
{
	private OfflineProtocolCompat()
	{}
	
	public static Object targetVersion(Connection connection, ServerData server)
	{
		try
		{
			Class<?> entry =
				Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
			Class<?> api = Class
				.forName("com.viaversion.viafabricplus.api.ViaFabricPlusAPI");
			Object translation = api.getMethod("protocolTranslation")
				.invoke(entry.getMethod("api").invoke(null));
			Class<?> type = Class.forName(
				"com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation");
			Object target = connection == null ? null
				: type.getMethod("targetVersion", Connection.class)
					.invoke(translation, connection);
			if(protocol(target) != null)
				return target;
			target = server == null ? null
				: type.getMethod("serverVersion", ServerData.class)
					.invoke(translation, server);
			if(protocol(target) != null)
				return target;
			target = type.getMethod("targetVersion").invoke(translation);
			if(protocol(target) != null)
				return target;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{}
		try
		{
			Class<?> entry =
				Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
			Object impl = entry.getMethod("getImpl").invoke(null);
			Class<?> api = Class
				.forName("com.viaversion.viafabricplus.api.ViaFabricPlusBase");
			Object target = connection == null
				? api.getMethod("getTargetVersion").invoke(impl)
				: api.getMethod("getTargetVersion", Connection.class)
					.invoke(impl, connection);
			return protocol(target) != null ? target : null;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{
			return null;
		}
	}
	
	public static Integer protocol(Object target)
	{
		if(target == null)
			return null;
		try
		{
			try
			{
				if(Boolean.FALSE.equals(
					target.getClass().getMethod("isKnown").invoke(target)))
					return null;
			}catch(NoSuchMethodException e)
			{}
			Object value =
				target.getClass().getMethod("getVersion").invoke(target);
			return value instanceof Number number && number.intValue() >= 0
				? number.intValue() : null;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{
			return null;
		}
	}
	
	public static boolean preserveServerVersion(ServerData server,
		Object target)
	{
		if(target == null)
			return true;
		try
		{
			Class<?> entry =
				Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
			Class<?> api = Class
				.forName("com.viaversion.viafabricplus.api.ViaFabricPlusAPI");
			Object translation = api.getMethod("protocolTranslation")
				.invoke(entry.getMethod("api").invoke(null));
			Class<?> type = Class.forName(
				"com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation");
			Class<?> version = Class.forName(
				"com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
			type.getMethod("setServerVersion", ServerData.class, version)
				.invoke(translation, server, target);
			return true;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{}
		try
		{
			Class<?> entry =
				Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
			Object impl = entry.getMethod("getImpl").invoke(null);
			Class<?> api = Class
				.forName("com.viaversion.viafabricplus.api.ViaFabricPlusBase");
			Class<?> version = Class.forName(
				"com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
			api.getMethod("setTargetVersion", version, boolean.class)
				.invoke(impl, target, true);
			return true;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{
			return false;
		}
	}
	
	public static byte[] loginHandshake(String host, int port, int protocol)
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		writeVarInt(payload, protocol);
		byte[] address = host.getBytes(StandardCharsets.UTF_8);
		writeVarInt(payload, address.length);
		payload.writeBytes(address);
		payload.write((port >> 8) & 0xFF);
		payload.write(port & 0xFF);
		writeVarInt(payload, 2);
		return payload.toByteArray();
	}
	
	private static void writeVarInt(ByteArrayOutputStream out, int value)
	{
		do
		{
			int part = value & 0x7F;
			value >>>= 7;
			out.write(value == 0 ? part : part | 0x80);
		}while(value != 0);
	}
	
	public static String gameruleCommand(int protocol, boolean feedback,
		boolean disabled)
	{
		String rule = protocol >= 774
			? (feedback ? "minecraft:send_command_feedback"
				: "minecraft:log_admin_commands")
			: (feedback ? "sendCommandFeedback" : "logAdminCommands");
		return "gamerule " + rule + " " + !disabled;
	}
}
