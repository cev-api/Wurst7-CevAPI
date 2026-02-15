/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.Locale;
import java.util.StringJoiner;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

public final class UiUtilsCommandSystem
{
	private static final String PREFIX = "[UI-Utils] ";
	public static final String ROOT_COMMAND = ".uiutils";
	
	private UiUtilsCommandSystem()
	{}
	
	public static String execute(String input)
	{
		if(input == null || input.isBlank())
			return PREFIX + "No command.";
		
		String[] parts = input.trim().split("\\s+", 2);
		String command = parts[0].toLowerCase(Locale.ROOT);
		String args = parts.length > 1 ? parts[1] : "";
		
		return switch(command)
		{
			case "help" -> help();
			case "enable" -> setEnabled(true);
			case "disable" -> setEnabled(false);
			case "close" -> close();
			case "desync" -> desync();
			case "chat" -> chat(args);
			case "screen" -> screen(args);
			case "plugins" -> UiUtilsPluginScanner.startScan();
			case "queue" -> queue(args);
			default -> PREFIX + "Unknown command: " + command;
		};
	}
	
	public static boolean isUiUtilsCommand(String text)
	{
		if(text == null)
			return false;
		
		return text.equalsIgnoreCase(ROOT_COMMAND)
			|| text.toLowerCase(Locale.ROOT).startsWith(ROOT_COMMAND + " ");
	}
	
	public static String extractCommandBody(String text)
	{
		if(text == null || text.isBlank())
			return "";
		if(text.equalsIgnoreCase(ROOT_COMMAND))
			return "help";
		
		if(text.length() <= ROOT_COMMAND.length())
			return "";
		
		return text.substring(ROOT_COMMAND.length()).trim();
	}
	
	private static String help()
	{
		return PREFIX + "Usage: .uiutils <command>\n" + PREFIX
			+ "Commands: help, enable, disable, close, desync,"
			+ " chat, screen, plugins, queue";
	}
	
	private static String setEnabled(boolean enabled)
	{
		UiUtilsState.enabled = enabled;
		return PREFIX + "UI-Utils is now "
			+ (enabled ? "enabled." : "disabled.");
	}
	
	private static String close()
	{
		Minecraft.getInstance().setScreen(null);
		return PREFIX + "Closed current screen.";
	}
	
	private static String desync()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.getConnection() == null || mc.player == null)
			return PREFIX + "Not connected.";
		
		int syncId = mc.player.containerMenu.containerId;
		mc.getConnection().send(new ServerboundContainerClosePacket(syncId));
		return PREFIX + "Sent close packet for syncId " + syncId + ".";
	}
	
	private static String chat(String args)
	{
		Minecraft mc = Minecraft.getInstance();
		if(args.isBlank())
			return PREFIX + "Usage: chat <message>";
		if(mc.player == null || mc.getConnection() == null)
			return PREFIX + "Not connected.";
		
		if(args.startsWith("/"))
			mc.player.connection.sendCommand(args.substring(1));
		else
			mc.player.connection.sendChat(args);
		
		return PREFIX + "Sent.";
	}
	
	private static String screen(String args)
	{
		Minecraft mc = Minecraft.getInstance();
		String[] parts = args.split("\\s+", 3);
		if(parts.length == 0 || parts[0].isBlank())
			return PREFIX + "Usage: screen <save|load|list|info> [slot]";
		
		String action = parts[0].toLowerCase(Locale.ROOT);
		String slot = parts.length > 1 ? parts[1] : "";
		return switch(action)
		{
			case "save" ->
			{
				if(slot.isBlank())
					yield PREFIX + "Usage: screen save <slot>";
				boolean ok = UiUtils.saveCurrentGuiToSlot(mc, slot);
				yield ok ? PREFIX + "Saved GUI to slot \"" + slot + "\"."
					: PREFIX + "No GUI to save.";
			}
			case "load" ->
			{
				if(slot.isBlank())
					yield PREFIX + "Usage: screen load <slot>";
				boolean ok = UiUtils.loadGuiFromSlot(mc, slot);
				yield ok ? PREFIX + "Loaded GUI from slot \"" + slot + "\"."
					: PREFIX + "No GUI in slot \"" + slot + "\".";
			}
			case "list" ->
			{
				if(UiUtilsState.savedScreens.isEmpty())
					yield PREFIX + "No saved slots.";
				StringJoiner joiner = new StringJoiner(", ");
				UiUtilsState.savedScreens.keySet().forEach(joiner::add);
				yield PREFIX + "Slots: " + joiner;
			}
			case "info" ->
			{
				if(slot.isBlank())
					yield PREFIX + "Usage: screen info <slot>";
				String key = slot.toLowerCase(Locale.ROOT);
				var screen = UiUtilsState.savedScreens.get(key);
				if(screen == null)
					yield PREFIX + "No GUI in slot \"" + slot + "\".";
				yield PREFIX + "Slot \"" + slot + "\" -> "
					+ screen.getClass().getSimpleName();
			}
			default -> PREFIX + "Usage: screen <save|load|list|info> [slot]";
		};
	}
	
	private static String queue(String args)
	{
		Minecraft mc = Minecraft.getInstance();
		String[] parts = args.split("\\s+", 2);
		if(parts.length == 0 || parts[0].isBlank())
			return PREFIX + "Queue size: "
				+ UiUtilsState.delayedUiPackets.size();
		
		String action = parts[0].toLowerCase(Locale.ROOT);
		return switch(action)
		{
			case "clear" -> PREFIX + "Cleared " + UiUtils.clearQueuedPackets()
				+ " queued packet(s).";
			case "sendone" -> UiUtils.sendOneQueuedPacket(mc)
				? PREFIX + "Sent one queued packet."
				: PREFIX + "No queued packets.";
			case "poplast" -> UiUtils.popLastQueuedPacket()
				? PREFIX + "Removed last queued packet."
				: PREFIX + "No queued packets.";
			case "spam" ->
			{
				int times = 1;
				if(parts.length > 1 && UiUtils.isInteger(parts[1]))
					times = Math.max(1, Integer.parseInt(parts[1]));
				int sent = UiUtils.sendQueuedPackets(mc, times);
				yield PREFIX + "Sent " + sent + " packet(s).";
			}
			case "list" -> PREFIX + "Queue size: "
				+ UiUtilsState.delayedUiPackets.size();
			default -> PREFIX
				+ "Usage: queue <list|clear|sendone|poplast|spam [times]>";
		};
	}
}
