/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.WurstClient;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;

public final class UiUtilsPluginScanner
{
	private static final Set<String> ANTICHEAT_WORDS = Set.of("nocheatplus",
		"negativity", "vulcan", "spartan", "matrix", "grim", "themis", "kauri",
		"godseye", "anticheat", "exploit", "illegal");
	
	private static final Random RANDOM = new Random();
	
	private static boolean initialized;
	private static boolean scanning;
	private static int ticksWaiting;
	private static int requestId = -1;
	private static final List<String> foundPlugins = new ArrayList<>();
	private static final Set<String> dedupe = new HashSet<>();
	
	private UiUtilsPluginScanner()
	{}
	
	public static void init()
	{
		if(initialized)
			return;
		
		ScannerListener listener = new ScannerListener();
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class,
			listener);
		WurstClient.INSTANCE.getEventManager().add(PacketInputListener.class,
			listener);
		initialized = true;
	}
	
	public static String startScan()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.getConnection() == null || mc.player == null)
			return "[UI-Utils] Not connected.";
		if(scanning)
			return "[UI-Utils] Plugin scan already in progress.";
		
		try
		{
			requestId = RANDOM.nextInt(100000);
			Packet<?> packet = createSuggestionPacket(requestId, "ver ");
			mc.getConnection().send(packet);
		}catch(Exception e)
		{
			UiUtils.LOGGER.error("Failed to start plugin scan.", e);
			return "[UI-Utils] Failed to start plugin scan.";
		}
		
		foundPlugins.clear();
		dedupe.clear();
		scanning = true;
		ticksWaiting = 0;
		return "[UI-Utils] Scanning plugins...";
	}
	
	private static Packet<?> createSuggestionPacket(int id, String text)
		throws ReflectiveOperationException
	{
		Class<?> clazz = Class.forName(
			"net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket");
		for(Constructor<?> constructor : clazz.getDeclaredConstructors())
		{
			Class<?>[] params = constructor.getParameterTypes();
			if(params.length == 2 && params[0] == int.class
				&& params[1] == String.class)
			{
				constructor.setAccessible(true);
				return (Packet<?>)constructor.newInstance(id, text);
			}
		}
		throw new NoSuchMethodException(
			"ServerboundCommandSuggestionPacket(int, String) not found.");
	}
	
	private static void onTick()
	{
		if(!scanning)
			return;
		
		ticksWaiting++;
		if(ticksWaiting >= 60)
			finishAndPrint();
	}
	
	private static void onPacket(PacketInputEvent event)
	{
		if(!scanning)
			return;
		
		Packet<?> packet = event.getPacket();
		if(packet == null || !packet.getClass().getSimpleName()
			.equals("ClientboundCommandSuggestionsPacket"))
			return;
		
		try
		{
			int responseId = extractPacketId(packet);
			if(responseId != requestId)
				return;
			
			Suggestions suggestions = extractSuggestions(packet);
			if(suggestions != null)
				for(Suggestion suggestion : suggestions.getList())
					addPluginName(suggestion.getText());
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Failed to parse plugin scan response.", e);
		}
		
		finishAndPrint();
	}
	
	private static void addPluginName(String rawText)
	{
		if(rawText == null)
			return;
		String text = rawText.trim();
		if(text.isEmpty())
			return;
		if(text.startsWith("/"))
			text = text.substring(1);
		
		String normalized = text.toLowerCase(Locale.ROOT);
		if(!dedupe.add(normalized))
			return;
		
		foundPlugins.add(text);
	}
	
	private static int extractPacketId(Packet<?> packet)
		throws ReflectiveOperationException
	{
		for(String name : new String[]{"id", "getId", "completionId",
			"transactionId"})
			try
			{
				Method method = packet.getClass().getMethod(name);
				Object value = method.invoke(packet);
				if(value instanceof Integer i)
					return i;
			}catch(NoSuchMethodException ignored)
			{}
		
		throw new NoSuchMethodException(
			"No id method found on suggestions packet.");
	}
	
	private static Suggestions extractSuggestions(Packet<?> packet)
		throws ReflectiveOperationException
	{
		for(String name : new String[]{"suggestions", "getSuggestions"})
			try
			{
				Method method = packet.getClass().getMethod(name);
				Object value = method.invoke(packet);
				if(value instanceof Suggestions suggestions)
					return suggestions;
			}catch(NoSuchMethodException ignored)
			{}
		
		return null;
	}
	
	private static void finishAndPrint()
	{
		scanning = false;
		ticksWaiting = 0;
		requestId = -1;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null)
		{
			foundPlugins.clear();
			dedupe.clear();
			return;
		}
		
		if(foundPlugins.isEmpty())
		{
			mc.player.displayClientMessage(
				Component.literal("[UI-Utils] No plugins found or blocked."),
				false);
			return;
		}
		
		foundPlugins.sort(String.CASE_INSENSITIVE_ORDER);
		StringBuilder line = new StringBuilder("[UI-Utils] Plugins (")
			.append(foundPlugins.size()).append("): ");
		for(int i = 0; i < foundPlugins.size(); i++)
		{
			String plugin = foundPlugins.get(i);
			String lower = plugin.toLowerCase(Locale.ROOT);
			boolean flagged =
				ANTICHEAT_WORDS.stream().anyMatch(lower::contains);
			line.append(flagged ? "!" : "").append(plugin);
			if(i < foundPlugins.size() - 1)
				line.append(", ");
		}
		
		mc.player.displayClientMessage(Component.literal(line.toString()),
			false);
		foundPlugins.clear();
		dedupe.clear();
	}
	
	private static final class ScannerListener
		implements UpdateListener, PacketInputListener
	{
		@Override
		public void onUpdate()
		{
			onTick();
		}
		
		@Override
		public void onReceivedPacket(PacketInputEvent event)
		{
			onPacket(event);
		}
	}
}
