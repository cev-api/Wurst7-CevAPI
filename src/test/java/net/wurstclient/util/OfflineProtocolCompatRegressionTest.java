/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;

public final class OfflineProtocolCompatRegressionTest
{
	public static void main(String[] args) throws Exception
	{
		// The native client is 26.3 (777); the translated server is 26.2 (776).
		DataInputStream input =
			new DataInputStream(new ByteArrayInputStream(OfflineProtocolCompat
				.loginHandshake("167.179.130.34", 25565, 776)));
		check(readVarInt(input) == 776,
			"Handshake must advertise the server protocol, not 777");
		String host = new String(input.readNBytes(readVarInt(input)),
			StandardCharsets.UTF_8);
		check(host.equals("167.179.130.34"), "Handshake address");
		check(input.readUnsignedShort() == 25565, "Handshake port");
		check(readVarInt(input) == 2 && input.available() == 0,
			"Handshake login state");
		check(OfflineProtocolCompat.protocol(new Version(776, true)) == 776,
			"Translated target");
		check(OfflineProtocolCompat.protocol(new Version(-2, false)) == null,
			"Auto-detect is not a wire protocol");
		check(OfflineProtocolCompat.protocol(null) == null,
			"Optional mod absent");
		for(int protocol : new int[]{773, 774, 776, 777})
			for(boolean feedback : new boolean[]{false, true})
				for(boolean disabled : new boolean[]{false, true})
				{
					String name = protocol < 774
						? (feedback ? "sendCommandFeedback"
							: "logAdminCommands")
						: (feedback ? "minecraft:send_command_feedback"
							: "minecraft:log_admin_commands");
					check(
						OfflineProtocolCompat
							.gameruleCommand(protocol, feedback, disabled)
							.equals("gamerule " + name
								+ (disabled ? " false" : " true")),
						"Gamerule version and toggle direction");
				}
		System.out.println(
			"OfflineSettings protocol and gamerule regression checks passed.");
	}
	
	public record Version(int version, boolean known)
	{
		public int getVersion()
		{
			return version;
		}
		
		public boolean isKnown()
		{
			return known;
		}
	}
	
	private static int readVarInt(DataInputStream input) throws Exception
	{
		int result = 0;
		for(int shift = 0; shift < 35; shift += 7)
		{
			int part = input.readUnsignedByte();
			result |= (part & 127) << shift;
			if((part & 128) == 0)
				return result;
		}
		throw new AssertionError("Invalid VarInt");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
