/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;

@SearchTags({"bundle dupe", "timeout dupe", "netty lag dupe"})
public final class BundleDupeHack extends Hack implements PacketOutputListener
{
	private final EnumSetting<DupeMethod> dupeMethod =
		new EnumSetting<>("Dupe method", "Choose how to trigger the dupe.",
			DupeMethod.values(), DupeMethod.KICK);
	
	private final SliderSetting timeoutSeconds =
		new SliderSetting("Timeout seconds",
			"Seconds to wait after the next KeepAlive before firing the dupe.",
			30, 1, 120, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting interactDelayMillis =
		new SliderSetting("Interact delay (ms)",
			"Delay before interacting with the bundle after the timeout.", 650,
			0, 1000, 10, ValueDisplay.INTEGER);
	
	private final EnumSetting<KickMethod> kickMethod =
		new EnumSetting<>("Kick method", "How to force a disconnect.",
			KickMethod.values(), KickMethod.HURT);
	
	private final TextFieldSetting customKickCommand = new TextFieldSetting(
		"Custom kick command",
		"Custom chat command to execute for kicking (e.g., /kill @s, or any plugin command).",
		".kick hurt");
	
	private final EnumSetting<LagMethod> lagMethod =
		new EnumSetting<>("Lag method", "How to create lag during the dupe.",
			LagMethod.values(), LagMethod.CLICKSLOT);
	
	private final TextFieldSetting customLagCommand = new TextFieldSetting(
		"Custom lag command",
		"Custom chat command to execute for lag (client will just send it).",
		".t custom-lag-module");
	
	private final SliderSetting boatNbtPackets = new SliderSetting(
		"Boat NBT packets",
		"Amount of OPEN_INVENTORY client commands to send while riding a chest vehicle.",
		200, 1, 1000, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting entityNbtPackets =
		new SliderSetting("Entity NBT packets",
			"Amount of interact-entity packets to send at the target entity.",
			200, 1, 1000, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting clickslotPackets =
		new SliderSetting("ClickSlot packets (needs NBT book in inv)",
			"Amount of container click packets to send.", 200, 1, 1000, 1,
			ValueDisplay.INTEGER);
	
	private final SliderSetting lagPackets = new SliderSetting("Other Lag packets",
		"Amount of lag packets to spam for ALL methods (except custom).", 300,
		100, 2000, 50, ValueDisplay.INTEGER);
	
	private boolean cancelKeepAlive;
	private boolean dupeActivated;
	private boolean waitingForKeepAlive;
	private final ScheduledExecutorService scheduler =
		Executors.newSingleThreadScheduledExecutor();
	
	public BundleDupeHack()
	{
		super("BundleDupe");
		setCategory(Category.ITEMS);
		
		addSetting(dupeMethod);
		addSetting(timeoutSeconds);
		addSetting(interactDelayMillis);
		addSetting(kickMethod);
		addSetting(customKickCommand);
		addSetting(lagMethod);
		addSetting(customLagCommand);
		addSetting(boatNbtPackets);
		addSetting(entityNbtPackets);
		addSetting(clickslotPackets);
		addSetting(lagPackets);
	}
	
	@Override
	protected void onEnable()
	{
		if(lagMethod.getSelected() == LagMethod.BOAT_NBT)
		{
			if(MC.player == null)
			{
				setEnabled(false);
				return;
			}
			
			Entity vehicle = MC.player.getVehicle();
			boolean valid = vehicle instanceof ChestBoat
				|| vehicle instanceof AbstractMinecartContainer;
			if(!valid)
			{
				ChatUtils.error(
					"You must be sitting in a Chest Boat or Minecart with Chest for this Lag Method!");
				setEnabled(false);
				return;
			}
		}
		
		if(lagMethod.getSelected() == LagMethod.ENTITY_NBT)
		{
			HitResult hr = MC.hitResult;
			if(!(hr instanceof EntityHitResult))
			{
				ChatUtils.error(
					"You must be looking at a Chest Boat or Minecart with Chest for this Lag Method!");
				setEnabled(false);
				return;
			}
			
			Entity target = ((EntityHitResult)hr).getEntity();
			boolean valid = target instanceof ChestBoat
				|| target instanceof AbstractMinecartContainer;
			if(!valid)
			{
				ChatUtils.error(
					"Target is not a Chest Boat or Minecart with Chest!");
				setEnabled(false);
				return;
			}
		}
		
		cancelKeepAlive = false;
		dupeActivated = true;
		waitingForKeepAlive = false;
		
		if(dupeMethod.getSelected() == DupeMethod.TIMEOUT)
			executeTimeoutDupe();
		else
			executeKickDupe();
		
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		cancelKeepAlive = false;
		dupeActivated = false;
		waitingForKeepAlive = false;
		EVENTS.remove(PacketOutputListener.class, this);
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		Packet<?> pkt = event.getPacket();
		
		if(dupeActivated && pkt instanceof ServerboundPlayerActionPacket)
			event.cancel();
		
		if(pkt instanceof ServerboundKeepAlivePacket)
		{
			if(waitingForKeepAlive)
			{
				waitingForKeepAlive = false;
				cancelKeepAlive = true;
				ChatUtils
					.message("KeepAlive sent - Starting timeout countdown for "
						+ timeoutSeconds.getValueI() + " seconds...");
				
				scheduler.schedule(() -> {
					if(!isEnabled())
						return;
					
					scheduler.schedule(() -> {
						if(!isEnabled())
							return;
						sendInteractItem();
						executeLagMethod();
						ChatUtils.message("Timeout Dupe executed!");
						setEnabled(false);
					}, interactDelayMillis.getValueI(), TimeUnit.MILLISECONDS);
				}, timeoutSeconds.getValueI(), TimeUnit.SECONDS);
				
			}else if(cancelKeepAlive)
			{
				event.cancel();
			}
		}
	}
	
	private void executeTimeoutDupe()
	{
		ChatUtils.message(
			"Timeout Dupe activated - Waiting for next KeepAlive packet...");
		waitingForKeepAlive = true;
	}
	
	private void executeKickDupe()
	{
		ChatUtils.message("Executing Kick Dupe...");
		
		executeLagMethod();
		sendInteractItem();
		
		switch(kickMethod.getSelected())
		{
			case QUIT ->
			{
				if(MC.level != null)
					MC.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
			}
			
			case CHARS ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null)
					c.sendChat("\u00a7");
			}
			
			case HURT ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null && MC.player != null)
					c.send(ServerboundInteractPacket.createAttackPacket(
						MC.player, MC.player.isShiftKeyDown()));
			}
			
			case CLIENTSETTINGS ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null)
				{
					ClientInformation info =
						MC.options.buildPlayerInformation();
					c.send(new ServerboundClientInformationPacket(info));
				}
			}
			
			case MOVE_NAN ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null && MC.player != null)
				{
					c.send(new ServerboundMovePlayerPacket.PosRot(Double.NaN,
						Double.NaN, Double.NaN, Float.NaN, Float.NaN,
						MC.player.onGround(), false));
				}
			}
			
			case MOVE_INF ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null && MC.player != null)
				{
					c.send(new ServerboundMovePlayerPacket.PosRot(
						Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
						Double.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
						Float.NEGATIVE_INFINITY, MC.player.onGround(), false));
				}
			}
			
			case MOVE_OOB ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null && MC.player != null)
				{
					double bx = 1.0E308, by = 1.0E308, bz = -1.0E308;
					c.send(new ServerboundMovePlayerPacket.PosRot(bx, by, bz,
						0.0f, 0.0f, MC.player.onGround(), false));
				}
			}
			
			case CLICK_INVALID ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null)
				{
					ServerboundContainerClickPacket p =
						new ServerboundContainerClickPacket(-1,
							Integer.MAX_VALUE, (short)Short.MAX_VALUE,
							(byte)127, ClickType.PICKUP,
							new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
					c.send(p);
				}
			}
			
			case UPDATESLOT ->
			{
				ClientPacketListener c = MC.getConnection();
				if(c != null)
					c.send(new ServerboundSetCarriedItemPacket(-1));
			}
			
			case CUSTOM -> executeCommand(customKickCommand.getValue());
		}
		
		executeLagMethod();
		ChatUtils.message("Kick Dupe executed!");
		
		scheduler.schedule(() -> {
			if(isEnabled())
				setEnabled(false);
		}, 100, TimeUnit.MILLISECONDS);
	}
	
	private void sendInteractItem()
	{
		if(MC.player == null || MC.gameMode == null)
			return;
		
		MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
	}
	
	private void executeLagMethod()
	{
		switch(lagMethod.getSelected())
		{
			case CUSTOM -> executeCommand(customLagCommand.getValue());
			case BOAT_NBT -> sendBoatNbtPackets();
			case CLICKSLOT -> sendClickslotPackets();
			case ENTITY_NBT -> sendEntityNbtPackets();
			case INVENTORY_SPAM -> sendInventorySpam();
			case CHAT_FLOOD -> sendChatFlood();
			case SWING_SPAM -> sendSwingSpam();
			case DIG_SPAM -> sendDigSpam();
			case SLOT_SPAM -> sendSlotSpam();
		}
	}
	
	private void sendBoatNbtPackets()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null || MC.player == null)
			return;
		
		for(int i = 0; i < boatNbtPackets.getValueI(); i++)
			c.send(new ServerboundPlayerCommandPacket(MC.player,
				Action.OPEN_INVENTORY));
		
		ChatUtils.message(
			"Sent " + boatNbtPackets.getValueI() + " Boat NBT packets.");
	}
	
	private void sendEntityNbtPackets()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null)
			return;
		
		HitResult hr = MC.hitResult;
		if(!(hr instanceof EntityHitResult eHit))
		{
			ChatUtils.error(
				"You must be looking at an entity (Chest Boat, Minecart with Chest)!");
			return;
		}
		
		Entity entity = eHit.getEntity();
		for(int i = 0; i < entityNbtPackets.getValueI(); i++)
			c.send(ServerboundInteractPacket.createInteractionPacket(entity,
				MC.player != null && MC.player.isShiftKeyDown(),
				InteractionHand.MAIN_HAND));
		
		ChatUtils.message(
			"Sent " + entityNbtPackets.getValueI() + " Interact packets.");
	}
	
	private void sendClickslotPackets()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null)
			return;
		
		for(int i = 0; i < clickslotPackets.getValueI(); i++)
		{
			ServerboundContainerClickPacket p =
				new ServerboundContainerClickPacket(0, 0, (short)0, (byte)0,
					ClickType.PICKUP, new Int2ObjectOpenHashMap<>(),
					HashedStack.EMPTY);
			c.send(p);
		}
		
		ChatUtils.message(
			"Sent " + clickslotPackets.getValueI() + " Clickslot packets.");
	}
	
	private void sendInventorySpam()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null || MC.player == null)
			return;
		
		int count = lagPackets.getValueI();
		for(int i = 0; i < count; i++)
			c.send(new ServerboundPlayerCommandPacket(MC.player,
				Action.OPEN_INVENTORY));
		
		ChatUtils.message("Sent " + count
			+ " inventory open spam packets. (No boat needed!)");
	}
	
	private void sendChatFlood()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null)
			return;
		
		String junk = "a".repeat(200);
		int count = lagPackets.getValueI();
		
		for(int i = 0; i < count; i++)
		{
			c.send(
				new ServerboundChatPacket(junk, Instant.now(), 0L, null, null));
		}
		
		ChatUtils.message("Sent " + count + " chat flood packets. (Unsigned)");
	}
	
	private void sendSwingSpam()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null || MC.player == null)
			return;
		
		int count = lagPackets.getValueI();
		for(int i = 0; i < count; i++)
			c.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
		
		ChatUtils.message("Sent " + count
			+ " swing arm spam packets. (Harmless Netty flood!)");
	}
	
	private void sendDigSpam()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null || MC.player == null)
			return;
		
		BlockPos pos = MC.player.blockPosition();
		Direction dir = Direction.UP;
		int count = lagPackets.getValueI();
		for(int i = 0; i < count; i++)
			c.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos,
				dir));
		
		ChatUtils.message("Sent " + count
			+ " block dig spam packets. (Air block validation lag!)");
	}
	
	private void sendSlotSpam()
	{
		ClientPacketListener c = MC.getConnection();
		if(c == null)
			return;
		
		java.util.Random rand = new java.util.Random();
		int count = lagPackets.getValueI();
		for(int i = 0; i < count; i++)
			c.send(new ServerboundSetCarriedItemPacket(rand.nextInt(9)));
		
		ChatUtils.message("Sent " + count
			+ " hotbar slot spam packets. (Server inv update lag!)");
	}
	
	private void executeCommand(String command)
	{
		if(command == null)
			return;
		
		String trimmed = command.trim();
		if(trimmed.isEmpty())
			return;
		
		ClientPacketListener c = MC.getConnection();
		if(c != null)
			c.sendChat(trimmed);
	}
	
	public static enum DupeMethod
	{
		TIMEOUT,
		KICK
	}
	
	public static enum KickMethod
	{
		QUIT,
		CHARS,
		HURT,
		CLIENTSETTINGS,
		MOVE_NAN,
		MOVE_INF,
		MOVE_OOB,
		CLICK_INVALID,
		UPDATESLOT,
		CUSTOM
	}
	
	public static enum LagMethod
	{
		CUSTOM,
		BOAT_NBT,
		CLICKSLOT,
		ENTITY_NBT,
		INVENTORY_SPAM,
		CHAT_FLOOD,
		SWING_SPAM,
		DIG_SPAM,
		SLOT_SPAM
	}
}
