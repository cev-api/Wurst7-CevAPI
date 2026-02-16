/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.GamePacketTypes;

public enum PacketCatalog
{
	;
	
	private static final Set<String> S2C =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static final Set<String> C2S =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private static volatile boolean initialized;
	
	public static Set<String> getS2CNames()
	{
		ensureInitialized();
		return Collections.unmodifiableSet(S2C);
	}
	
	public static Set<String> getC2SNames()
	{
		ensureInitialized();
		return Collections.unmodifiableSet(C2S);
	}
	
	public static String formatPacketName(Packet<?> packet)
	{
		if(packet == null)
			return "unknown";
		
		try
		{
			return formatTypeName(packet.type());
			
		}catch(RuntimeException e)
		{
			String simple = packet.getClass().getSimpleName();
			if(simple == null || simple.isBlank())
				return "UnknownPacket";
			return simple;
		}
	}
	
	private static void ensureInitialized()
	{
		if(initialized)
			return;
		
		synchronized(PacketCatalog.class)
		{
			if(initialized)
				return;
			
			scanPacketTypes();
			initialized = true;
		}
	}
	
	private static void scanPacketTypes()
	{
		try
		{
			for(Field field : GamePacketTypes.class.getDeclaredFields())
			{
				if(!Modifier.isStatic(field.getModifiers()))
					continue;
				if(!PacketType.class.isAssignableFrom(field.getType()))
					continue;
				
				field.setAccessible(true);
				Object value = field.get(null);
				if(!(value instanceof PacketType<?> type))
					continue;
				
				String name = formatTypeName(type);
				if(type.flow() == PacketFlow.CLIENTBOUND)
					S2C.add(name);
				else if(type.flow() == PacketFlow.SERVERBOUND)
					C2S.add(name);
			}
			
		}catch(ReflectiveOperationException e)
		{
			// Keep sets empty and rely on discovered runtime packets.
		}
	}
	
	private static String formatTypeName(PacketType<?> type)
	{
		String idPath = type.id().getPath();
		String prefix = type.flow() == PacketFlow.CLIENTBOUND ? "Clientbound"
			: type.flow() == PacketFlow.SERVERBOUND ? "Serverbound" : "Packet";
		return prefix + toPascal(idPath) + "Packet";
	}
	
	private static String toPascal(String value)
	{
		StringBuilder builder = new StringBuilder();
		boolean uppercase = true;
		for(int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if(c == '_' || c == '-' || c == '/' || c == '.')
			{
				uppercase = true;
				continue;
			}
			
			if(uppercase)
			{
				builder.append(Character.toUpperCase(c));
				uppercase = false;
			}else
				builder.append(c);
		}
		
		return builder.toString();
	}
}
