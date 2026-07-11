/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IMultiPlayerGameMode;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"auto totem", "offhand", "off-hand"})
public final class AutoTotemHack extends Hack
	implements UpdateListener, PacketInputListener, ChatInputListener
{
	private static final long ENEMY_POP_EXPIRY_MS = 5 * 60 * 1000L;
	private final CheckboxSetting showCounter = new CheckboxSetting(
		"Show totem counter", "Displays the number of totems you have.", true);
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"Amount of ticks to wait before equipping the next totem.", 0, 0, 20, 1,
		ValueDisplay.INTEGER);
	
	private final SliderSetting health = new SliderSetting("Health",
		"Won't equip a totem until your health reaches this value or falls"
			+ " below it.\n" + "0 = always active",
		0, 0, 10, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts")
			.withLabel(1, "1 heart").withLabel(0, "ignore"));
	private final SliderSetting greenAt = new SliderSetting("Green at",
		"Totem count at which the HackList color reaches green.", 5, 1, 30, 1,
		ValueDisplay.INTEGER.withSuffix(" totems"));
	private final CheckboxSetting countOtherPlayerPops =
		new CheckboxSetting("Count other player pops",
			"Shows a yellow counter above other players for their totem pops.",
			false);
	
	private int nextTickSlot;
	private int totems;
	private int timer;
	private boolean wasTotemInOffhand;
	private boolean shieldSwingWasEnabledBeforeAutoTotem;
	private final java.util.Map<java.util.UUID, Integer> enemyTotemPops =
		new java.util.HashMap<>();
	private final java.util.Map<java.util.UUID, String> enemyTotemNames =
		new java.util.HashMap<>();
	private final java.util.Map<java.util.UUID, Long> enemyLastSeen =
		new java.util.HashMap<>();
	
	public AutoTotemHack()
	{
		super("AutoTotem");
		setCategory(Category.COMBAT);
		addSetting(showCounter);
		addSetting(delay);
		addSetting(health);
		addSetting(greenAt);
		addSetting(countOtherPlayerPops);
	}
	
	@Override
	public String getRenderName()
	{
		if(!showCounter.isChecked())
			return getName();
		
		int linkedTotems = RemoteEnderChestHack.getLinkedEnderChestTotemCount();
		String linkedSuffix = linkedTotems > 0 ? " +" + linkedTotems : "";
		
		if(totems == 1)
			return getName() + " [1 totem" + linkedSuffix + "]";
		
		return getName() + " [" + totems + " totems" + linkedSuffix + "]";
	}
	
	@Override
	protected void onEnable()
	{
		if(WURST.getHax().shieldSwingHack.isEnabled())
		{
			shieldSwingWasEnabledBeforeAutoTotem = true;
			WURST.getHax().shieldSwingHack.setEnabled(false);
			ChatUtils
				.warning("ShieldSwing disabled because AutoTotem is enabled.");
		}else
		{
			shieldSwingWasEnabledBeforeAutoTotem = false;
		}
		
		nextTickSlot = -1;
		totems = 0;
		timer = 0;
		wasTotemInOffhand = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(ChatInputListener.class, this);
		enemyTotemPops.clear();
		enemyTotemNames.clear();
		enemyLastSeen.clear();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(ChatInputListener.class, this);
		enemyTotemPops.clear();
		enemyTotemNames.clear();
		enemyLastSeen.clear();
		
		if(shieldSwingWasEnabledBeforeAutoTotem
			&& !WURST.getHax().shieldSwingHack.isEnabled())
		{
			shieldSwingWasEnabledBeforeAutoTotem = false;
			WURST.getHax().shieldSwingHack.setEnabled(true);
			ChatUtils.message("ShieldSwing restored.");
		}
	}
	
	@Override
	public void onUpdate()
	{
		if(!countOtherPlayerPops.isChecked())
		{
			enemyTotemPops.clear();
			enemyTotemNames.clear();
			enemyLastSeen.clear();
		}else
			cleanupEnemyPopCounts();
		
		finishMovingTotem();
		
		int nextTotemSlot = searchForTotems();
		
		if(isTotem(MC.player.getOffhandItem()))
		{
			wasTotemInOffhand = true;
			return;
		}
		
		if(wasTotemInOffhand)
		{
			timer = delay.getValueI();
			wasTotemInOffhand = false;
		}
		
		if(nextTotemSlot == -1)
			return;
		
		float healthF = health.getValueF();
		if(healthF > 0 && MC.player.getHealth() > healthF * 2F)
			return;
		
		boolean remoteChestActive = isRemoteChestActive();
		// Don't move items while an unrelated container is open. The linked
		// RemoteEChest menu is safe because its clicks use that menu's ID.
		if(MC.gui.screen() instanceof AbstractContainerScreen
			&& !(MC.gui.screen() instanceof InventoryScreen
				|| MC.gui.screen() instanceof CreativeModeInventoryScreen)
			&& !remoteChestActive)
			return;
		
		if(timer > 0)
		{
			timer--;
			return;
		}
		
		moveToOffhand(nextTotemSlot);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(MC.level == null
			|| !(event
				.getPacket() instanceof ClientboundEntityEventPacket packet)
			|| packet.getEventId() != 35)
			return;
		
		if(!(packet.getEntity(MC.level) instanceof Player player))
			return;
		
		if(player == MC.player)
		{
			// With a linked chest menu open, the server may not send the normal
			// player-inventory slot update for a popped offhand totem. The pop
			// packet is authoritative, so clear this client-side ghost first.
			MC.player.getInventory().setItem(40, ItemStack.EMPTY);
			RemoteEnderChestHack.requestAutoTotemRefill();
			return;
		}
		
		if(!countOtherPlayerPops.isChecked() || player.isCreative())
			return;
			
		// Event 35 is the server's authoritative totem-pop notification.
		// Count it immediately instead of waiting for a hand update.
		enemyTotemPops.merge(player.getUUID(), 1, Integer::sum);
		enemyTotemNames.put(player.getUUID(), player.getName().getString());
		enemyLastSeen.put(player.getUUID(), System.currentTimeMillis());
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(!countOtherPlayerPops.isChecked() || event.getComponent() == null)
			return;
		
		String message =
			event.getComponent().getString().toLowerCase(java.util.Locale.ROOT);
		if(!looksLikeDeathMessage(message))
			return;
		
		for(var entry : enemyTotemNames.entrySet())
			if(!entry.getValue().isBlank() && message
				.contains(entry.getValue().toLowerCase(java.util.Locale.ROOT)))
				clearEnemyPopCount(entry.getKey());
	}
	
	private boolean looksLikeDeathMessage(String message)
	{
		String[] deathTerms = {" died", "was slain", "was killed", "was shot",
			"was blown up", "was fireballed", "hit the ground", "fell from",
			"drowned", "tried to swim", "went off with a bang", "burned",
			"starved", "suffocated", "withered", "was pricked", "impaled",
			"froze", "frozen", "lava", "discovered the floor was lava"};
		for(String term : deathTerms)
			if(message.contains(term))
				return true;
		return false;
	}
	
	private void cleanupEnemyPopCounts()
	{
		long now = System.currentTimeMillis();
		for(var entry : new java.util.HashMap<>(enemyTotemPops).entrySet())
		{
			java.util.UUID uuid = entry.getKey();
			Player player =
				MC.level == null ? null : MC.level.getPlayerByUUID(uuid);
			if(player != null)
			{
				if(!player.isAlive())
				{
					clearEnemyPopCount(uuid);
					continue;
				}
				enemyLastSeen.put(uuid, now);
				continue;
			}
			
			long lastSeen = enemyLastSeen.getOrDefault(uuid, now);
			if(now - lastSeen >= ENEMY_POP_EXPIRY_MS)
				clearEnemyPopCount(uuid);
		}
	}
	
	private void clearEnemyPopCount(java.util.UUID uuid)
	{
		enemyTotemPops.remove(uuid);
		enemyTotemNames.remove(uuid);
		enemyLastSeen.remove(uuid);
	}
	
	public static int getEnemyTotemPops(Player player)
	{
		AutoTotemHack self = WurstClient.INSTANCE.getHax().autoTotemHack;
		if(self == null || !self.isEnabled()
			|| !self.countOtherPlayerPops.isChecked() || player == null)
			return 0;
		return self.enemyTotemPops.getOrDefault(player.getUUID(), 0);
	}
	
	@Override
	public int getHackListColorI(int alpha)
	{
		int count =
			totems + RemoteEnderChestHack.getLinkedEnderChestTotemCount();
		float progress = Math.min(1F, count / (float)greenAt.getValueI());
		int red;
		int green;
		if(progress < 0.5F)
		{
			red = 255;
			green = Math.round(progress * 2F * 255F);
		}else
		{
			red = Math.round((1F - progress) * 2F * 255F);
			green = 255;
		}
		return (alpha << 24) | (red << 16) | (green << 8);
	}
	
	private void moveToOffhand(int itemSlot)
	{
		if(isRemoteChestActive())
		{
			// A chest menu remains the server's active container while the
			// RemoteEChest GUI is hidden. Button 40 is the offhand SWAP target.
			int remoteMenuSlot = itemSlot + 18;
			if(remoteMenuSlot >= 27 && remoteMenuSlot < 63)
			{
				((IMultiPlayerGameMode)MC.gameMode).windowClick(
					MC.player.containerMenu.containerId, remoteMenuSlot, 40,
					ContainerInput.SWAP);
				return;
			}
		}
		
		boolean offhandEmpty = MC.player.getOffhandItem().isEmpty();
		
		IMultiPlayerGameMode im = IMC.getInteractionManager();
		im.windowClick_PICKUP(itemSlot);
		im.windowClick_PICKUP(45);
		
		if(!offhandEmpty)
			nextTickSlot = itemSlot;
	}
	
	private boolean isRemoteChestActive()
	{
		return MC.player != null
			&& MC.player.containerMenu != MC.player.inventoryMenu
			&& RemoteEnderChestHack
				.shouldKeepContainerOpen(MC.player.containerMenu.containerId);
	}
	
	private void finishMovingTotem()
	{
		if(nextTickSlot == -1)
			return;
		
		if(isRemoteChestActive())
		{
			int remoteMenuSlot = nextTickSlot + 18;
			if(remoteMenuSlot >= 27 && remoteMenuSlot < 63)
				((IMultiPlayerGameMode)MC.gameMode).windowClick(
					MC.player.containerMenu.containerId, remoteMenuSlot, 0,
					ContainerInput.PICKUP);
			nextTickSlot = -1;
			return;
		}
		
		IMultiPlayerGameMode im = IMC.getInteractionManager();
		im.windowClick_PICKUP(nextTickSlot);
		nextTickSlot = -1;
	}
	
	private int searchForTotems()
	{
		totems = InventoryUtils.count(this::isTotem, 40, true);
		if(totems <= 0)
			return -1;
		
		int totemSlot = InventoryUtils.indexOf(this::isTotem, 40);
		return InventoryUtils.toNetworkSlot(totemSlot);
	}
	
	private boolean isTotem(ItemStack stack)
	{
		return stack.is(Items.TOTEM_OF_UNDYING);
	}
}
