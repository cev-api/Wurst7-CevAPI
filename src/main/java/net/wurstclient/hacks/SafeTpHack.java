/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Locale;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.text.WText;
import net.wurstclient.util.ChatUtils;

@SearchTags({"safe tp", "safe teleport"})
public final class SafeTpHack extends Hack
	implements UpdateListener, ChatInputListener
{
	private static final String[][] TPA_ACCEPT_KEYWORDS =
		{{"accepted", "teleport"}, {"accepted", "request"}, {"accepted", "tpa"},
			{"accepted", "tpahere"}, {"akzeptiert", "anfrage"}};
	
	private final TextFieldSetting command = new TextFieldSetting("Command",
		"description.wurst.setting.safetp.command", "/t spawn",
		value -> value != null && !value.trim().isEmpty());
	private final SliderSetting waitTime = new SliderSetting("Wait time",
		"description.wurst.setting.safetp.wait_time", 5.5, 1, 15, 0.25,
		ValueDisplay.DECIMAL.withSuffix("s"));
	
	private int ticksRemaining;
	private boolean weActivatedBlink;
	
	private final CheckboxSetting saferTpaHere = new CheckboxSetting(
		"Safer TPA Here",
		WText.literal(
			"When enabled, automatically enable Blink when your TPA is accepted and release it when the player arrives. Useful to safely accept TPAs without exposing your position."),
		false);
	
	private final SliderSetting tpaTimeout = new SliderSetting("TPA timeout",
		"How long to wait for the arriving player before releasing Blink.", 6.0,
		1, 30, 0.25, ValueDisplay.DECIMAL.withSuffix("s"));
	
	private BlockPos trapPos = null;
	private boolean weActivatedBlinkForTpa = false;
	private int tpaTicksRemaining = 0;
	private boolean tpaUpdateRegistered = false;
	
	public SafeTpHack()
	{
		super("SafeTP");
		setCategory(Category.OTHER);
		addSetting(command);
		addSetting(waitTime);
		addSetting(saferTpaHere);
		addSetting(tpaTimeout);
		
		// Always listen for TPA accept messages so the Safer TPA Here toggle
		// works independently of SafeTP being enabled.
		EVENTS.add(ChatInputListener.class, this);
	}
	
	@Override
	public String getRenderName()
	{
		if(isEnabled())
		{
			int secondsLeft =
				Math.max((int)Math.ceil(ticksRemaining / 20.0), 0);
			return getName() + " [" + secondsLeft + "s]";
		}
		
		return super.getRenderName();
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null)
		{
			setEnabled(false);
			return;
		}
		
		ClientPacketListener netHandler = MC.getConnection();
		if(netHandler == null)
		{
			setEnabled(false);
			return;
		}
		
		String value = command.getValue().trim();
		if(value.isEmpty())
		{
			ChatUtils.error("SafeTP command cannot be empty.");
			setEnabled(false);
			return;
		}
		
		BlinkHack blinkHack = WURST.getHax().blinkHack;
		boolean blinkAlreadyEnabled = blinkHack.isEnabled();
		weActivatedBlink = false;
		
		if(!blinkAlreadyEnabled)
		{
			blinkHack.setEnabled(true);
			if(!blinkHack.isEnabled())
			{
				ChatUtils.error("SafeTP could not enable Blink.");
				setEnabled(false);
				return;
			}
			
			weActivatedBlink = true;
		}
		
		ticksRemaining = Math.max((int)Math.ceil(waitTime.getValue() * 20), 1);
		
		String commandToSend =
			value.startsWith("/") ? value.substring(1) : value;
		commandToSend = commandToSend.trim();
		if(commandToSend.isEmpty())
		{
			ChatUtils.error("SafeTP command cannot be empty.");
			setEnabled(false);
			return;
		}
		
		netHandler.sendCommand(commandToSend);
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		ticksRemaining = 0;
		
		BlinkHack blinkHack = WURST.getHax().blinkHack;
		if(weActivatedBlink && blinkHack.isEnabled())
			blinkHack.cancel();
		
		if(weActivatedBlinkForTpa && blinkHack.isEnabled())
			blinkHack.setEnabled(false);
		
		weActivatedBlink = false;
		weActivatedBlinkForTpa = false;
		trapPos = null;
		tpaTicksRemaining = 0;
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(!saferTpaHere.isChecked())
			return;
		
		String message =
			event.getComponent().getString().toLowerCase(Locale.ROOT);
		if(message.startsWith("\u00a7c[\u00a76wurst\u00a7c]"))
			return;
		
		if(isTpaAcceptanceMessage(message))
		{
			ChatUtils.message("SaferTPAHere: TPA acceptance detected.");
			if(MC.player == null || MC.getConnection() == null)
				return;
			
			trapPos = BlockPos.containing(MC.player.position());
			
			BlinkHack blinkHack = WURST.getHax().blinkHack;
			boolean blinkAlready = blinkHack.isEnabled();
			if(!blinkAlready)
			{
				blinkHack.setEnabled(true);
				if(!blinkHack.isEnabled())
				{
					ChatUtils.error("SaferTPAHere could not enable Blink.");
					trapPos = null;
					return;
				}
				
				weActivatedBlinkForTpa = true;
				ChatUtils.message("SaferTPAHere: Blink enabled for TPA.");
			}
			
			ChatUtils.message(
				"SaferTPAHere: Blink enabled. Waiting for arriving player at "
					+ trapPos.toShortString());
			
			// start timeout for arrival
			tpaTicksRemaining =
				Math.max((int)Math.ceil(tpaTimeout.getValue() * 20), 1);
			
			// ensure we receive onUpdate even when SafeTP hack itself is
			// disabled
			if(!isEnabled() && !tpaUpdateRegistered)
			{
				EVENTS.add(UpdateListener.class, this);
				tpaUpdateRegistered = true;
			}
		}
	}
	
	private boolean isTpaAcceptanceMessage(String message)
	{
		for(String[] keywords : TPA_ACCEPT_KEYWORDS)
		{
			boolean matches = true;
			for(String keyword : keywords)
				if(!message.contains(keyword))
				{
					matches = false;
					break;
				}
			
			if(matches)
				return true;
		}
		
		return false;
	}
	
	@Override
	public void onUpdate()
	{
		if(trapPos != null)
		{
			for(net.minecraft.world.entity.player.Player p : MC.level.players())
			{
				if(p == MC.player)
					continue;
				if(p instanceof net.wurstclient.util.FakePlayerEntity)
					continue;
				
				BlockPos pPos = p.blockPosition();
				if(pPos.equals(trapPos) || p.distanceToSqr(trapPos.getX() + 0.5,
					trapPos.getY(), trapPos.getZ() + 0.5) < 1.5)
				{
					BlinkHack blinkHack = WURST.getHax().blinkHack;
					if(weActivatedBlinkForTpa && blinkHack.isEnabled())
					{
						blinkHack.setEnabled(false);
						ChatUtils.message("SaferTPAHere: Detected arrival of "
							+ p.getName().getString() + ". Blink released.");
					}
					
					trapPos = null;
					weActivatedBlinkForTpa = false;
					if(tpaUpdateRegistered)
					{
						EVENTS.remove(UpdateListener.class, this);
						tpaUpdateRegistered = false;
					}
					return;
				}
			}
			
			// decrement TPA timeout
			tpaTicksRemaining--;
			if(tpaTicksRemaining <= 0)
			{
				BlinkHack blinkHack = WURST.getHax().blinkHack;
				if(weActivatedBlinkForTpa && blinkHack.isEnabled())
				{
					blinkHack.setEnabled(false);
					ChatUtils.message(
						"SaferTPAHere: Timeout waiting for arrival. Blink released.");
				}
				trapPos = null;
				weActivatedBlinkForTpa = false;
				if(tpaUpdateRegistered)
				{
					EVENTS.remove(UpdateListener.class, this);
					tpaUpdateRegistered = false;
				}
				return;
			}
		}
		
		if(MC.player == null || MC.getConnection() == null)
		{
			setEnabled(false);
			return;
		}
		
		BlinkHack blinkHack = WURST.getHax().blinkHack;
		if(!blinkHack.isEnabled())
		{
			setEnabled(false);
			return;
		}
		
		if(ticksRemaining <= 0)
		{
			setEnabled(false);
			return;
		}
		
		ticksRemaining--;
		
		if(ticksRemaining <= 0)
			setEnabled(false);
	}
}
