/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.minecraft.network.chat.Component;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

/**
 * Navigator-only helper that briefly toggles Towny PvP while you attack:
 * enable on attack, disable when you stop attacking.
 *
 * Implementation notes:
 * - Does not set a Category, so it shows up only in the Navigator/commands.
 * - Uses PlayerAttacksEntity event to detect the start of an attack.
 * - Disables again once the attack key is released (with a 1-tick grace).
 */
public final class TownyHack extends Hack
	implements PlayerAttacksEntityListener, UpdateListener, ChatInputListener
{
	private enum PvPState
	{
		UNKNOWN,
		ENABLED,
		DISABLED
	}
	
	private boolean desiredEnabled;
	private PvPState knownState;
	private boolean awaitingConfirm;
	private int ticksSinceCommand;
	private int ticksSinceAttackReleased;
	private int retryCount;
	private String lastCommandSent;
	
	private enum SendMode
	{
		NONE,
		COMMAND,
		CHAT
	}
	
	private SendMode lastSendMode = SendMode.NONE;
	private boolean didFallback;
	
	private final TextFieldSetting toggleCommand =
		new TextFieldSetting("Command",
			"Command to toggle Towny PvP.\n\n" + "A leading slash is optional.",
			"t toggle pvp", this::isValidCommand);
	
	private final SliderSetting attackEndDelay = new SliderSetting("End delay",
		"Ticks to wait after you stop attacking before sending the command.", 1,
		0, 100, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	// Anti-spam timings (ticks)
	private static final int MIN_INTERVAL_TICKS = 40; // 2s between sends
	private static final int CONFIRM_TIMEOUT_TICKS = 100; // 5s to wait
	
	public TownyHack()
	{
		super("Towny");
		// No category -> Navigator-only
		addSetting(toggleCommand);
		addSetting(attackEndDelay);
	}
	
	@Override
	protected void onEnable()
	{
		desiredEnabled = false;
		knownState = PvPState.UNKNOWN;
		awaitingConfirm = false;
		ticksSinceCommand = 0;
		ticksSinceAttackReleased = 0;
		retryCount = 0;
		lastSendMode = SendMode.NONE;
		didFallback = false;
		lastCommandSent = "";
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(ChatInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(ChatInputListener.class, this);
		desiredEnabled = false;
		knownState = PvPState.UNKNOWN;
		awaitingConfirm = false;
		ticksSinceCommand = 0;
		ticksSinceAttackReleased = 0;
		retryCount = 0;
		lastSendMode = SendMode.NONE;
		didFallback = false;
		lastCommandSent = "";
	}
	
	@Override
	public void onPlayerAttacksEntity(net.minecraft.world.entity.Entity target)
	{
		if(MC == null || MC.player == null)
			return;
		
		// We want PvP enabled while attacking.
		desiredEnabled = true;
		ticksSinceAttackReleased = 0;
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		// Track when the attack key is no longer held.
		KeyMapping attackKey = MC.options.keyAttack;
		boolean attackDown = attackKey != null && attackKey.isDown();
		if(attackDown)
		{
			desiredEnabled = true;
			ticksSinceAttackReleased = 0;
		}else
		{
			if(ticksSinceAttackReleased < Integer.MAX_VALUE)
				ticksSinceAttackReleased++;
			
			if(ticksSinceAttackReleased >= getAttackEndDelayTicks())
				desiredEnabled = false;
		}
		
		// Handle timing for anti-spam
		if(ticksSinceCommand < Integer.MAX_VALUE)
			ticksSinceCommand++;
		
		if(awaitingConfirm)
		{
			if(ticksSinceCommand > CONFIRM_TIMEOUT_TICKS)
			{
				// Give up or retry once after timeout
				awaitingConfirm = false;
				if(retryCount < 1)
				{
					// Only retry if still needed and min interval elapsed
					maybeSendToggle();
					retryCount++;
				}
			}
			return;
		}
		
		// Not awaiting confirmation: check if desired differs from known.
		boolean isKnownEnabled = (knownState == PvPState.ENABLED);
		if(desiredEnabled != isKnownEnabled)
			maybeSendToggle();
	}
	
	private void maybeSendToggle()
	{
		if(ticksSinceCommand < MIN_INTERVAL_TICKS)
			return;
		
		sendTownyToggleCommand();
		awaitingConfirm = true;
		ticksSinceCommand = 0;
	}
	
	private void sendTownyToggleCommand()
	{
		try
		{
			if(MC != null && MC.getConnection() != null)
			{
				// Preferred: command packet without leading slash.
				String command = getCommandForCommandPacket();
				if(command.isEmpty())
					return;
				MC.getConnection().sendCommand(command);
				lastSendMode = SendMode.COMMAND;
				lastCommandSent = command.toLowerCase();
			}
		}catch(Throwable ignored)
		{
			// Best-effort: ignore failures sending command
		}
	}
	
	private void sendTownyToggleChat()
	{
		try
		{
			if(MC != null && MC.getConnection() != null)
			{
				// Fallback: send as literal chat with a slash.
				String chatCommand = getCommandForChat();
				if(chatCommand.isEmpty())
					return;
				MC.getConnection().sendChat(chatCommand);
				lastSendMode = SendMode.CHAT;
				lastCommandSent = normalizeCommandForMatch(chatCommand);
			}
		}catch(Throwable ignored)
		{
			// ignore
		}
	}
	
	@Override
	public void onReceivedMessage(ChatInputListener.ChatInputEvent event)
	{
		Component comp = event.getComponent();
		if(comp == null)
			return;
		
		String msg = comp.getString();
		if(msg == null)
			return;
		String m = msg.trim().toLowerCase();
		// Fallback trigger: unknown command error for our string
		if(m.contains("unknown or incomplete command")
			&& !lastCommandSent.isEmpty() && m.contains(lastCommandSent)
			&& lastSendMode == SendMode.COMMAND && !didFallback)
		{
			// Try one-time fallback via chat with slash
			if(ticksSinceCommand >= MIN_INTERVAL_TICKS)
			{
				sendTownyToggleChat();
				awaitingConfirm = true;
				ticksSinceCommand = 0;
				didFallback = true;
			}
			return;
		}
		
		if(!m.contains("pvp"))
			return;
		
		// Heuristic parse; match common Towny toggle outputs.
		boolean enable = (m.contains("enabled") || m.contains("on"))
			&& !m.contains("disable");
		boolean disable = (m.contains("disabled") || m.contains("off"))
			&& !m.contains("enable ");
		
		if(enable == disable)
			return; // ambiguous
			
		knownState = enable ? PvPState.ENABLED : PvPState.DISABLED;
		awaitingConfirm = false;
		retryCount = 0;
		didFallback = false;
	}
	
	private int getAttackEndDelayTicks()
	{
		return Math.max(0, (int)Math.round(attackEndDelay.getValue()));
	}
	
	private boolean isValidCommand(String value)
	{
		if(value == null)
			return false;
		
		return !value.trim().isEmpty();
	}
	
	private String getCommandForCommandPacket()
	{
		String command = getRawCommand();
		if(command.startsWith("/"))
			command = command.substring(1).trim();
		
		return command;
	}
	
	private String getCommandForChat()
	{
		String command = getRawCommand();
		if(command.startsWith("/"))
			return command;
		
		return "/" + command;
	}
	
	private String getRawCommand()
	{
		String command = toggleCommand.getValue();
		if(command == null)
			return "t toggle pvp";
		
		command = command.trim();
		if(command.isEmpty() || "/".equals(command))
			return "t toggle pvp";
		
		return command;
	}
	
	private String normalizeCommandForMatch(String command)
	{
		if(command == null)
			return "";
		
		String normalized = command.trim().toLowerCase();
		if(normalized.startsWith("/"))
			normalized = normalized.substring(1).trim();
		
		return normalized;
	}
}
