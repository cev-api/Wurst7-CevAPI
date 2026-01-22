/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Locale;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.PlayerRangeAlertManager;

@SearchTags({"chorus fruit", "chorus", "golden chorus fruit",
	"enchanted golden chorus fruit", "chorus carrot"})
public final class ChorusFruitHack extends Hack
	implements UpdateListener, PlayerRangeAlertManager.Listener
{
	private final CheckboxSetting onPlayerEnter = new CheckboxSetting(
		"On player enter",
		"Consumes a chorus fruit item when a player enters your range.", false);
	
	private final CheckboxSetting onDamageFromPlayer =
		new CheckboxSetting("On damage from player",
			"Consumes a chorus fruit item when you take damage from a player.",
			false);
	
	private final CheckboxSetting onDamageFromAll =
		new CheckboxSetting("On damage from all",
			"Consumes a chorus fruit item when you take any damage.", false);
	
	private final SliderSetting health = new SliderSetting("Health",
		"Only activates when your health reaches this value or falls below it.",
		4, 0.5, 9.5, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts"));
	
	private final CheckboxSetting disableOnActivation =
		new CheckboxSetting("Turn off on activation",
			"Disables this hack after it consumes a chorus fruit item.", false);
	
	private final EnumSetting<ItemTier> itemPreference =
		new EnumSetting<>("Item",
			"Selects which item tier to use first. If it's missing, lower tiers"
				+ " will be tried in order.",
			ItemTier.values(), ItemTier.ENCHANTED_GOLDEN_CHORUS_FRUIT);
	
	private final CheckboxSetting packetSpam =
		new CheckboxSetting("Packet spam",
			"Rapidly re-sends the use action while consuming for faster use on"
				+ " some servers.",
			false);
	
	private final CheckboxSetting silentSwitch = new CheckboxSetting(
		"Silent switching",
		"Swaps the item into your current slot instead of visibly switching.",
		false);
	
	private final PlayerRangeAlertManager alertManager =
		WURST.getPlayerRangeAlertManager();
	
	private float lastHealth = -1F;
	private int oldSlot = -1;
	private int swappedSlot = -1;
	private int useStartTick = -1;
	private boolean consuming;
	private boolean pendingDisable;
	
	public ChorusFruitHack()
	{
		super("ChorusFruit");
		
		addSetting(onPlayerEnter);
		addSetting(onDamageFromPlayer);
		addSetting(onDamageFromAll);
		addSetting(health);
		addSetting(disableOnActivation);
		addSetting(itemPreference);
		addSetting(packetSpam);
		addSetting(silentSwitch);
	}
	
	@Override
	protected void onEnable()
	{
		lastHealth = -1F;
		consuming = false;
		pendingDisable = false;
		oldSlot = -1;
		swappedSlot = -1;
		useStartTick = -1;
		alertManager.addListener(this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		alertManager.removeListener(this);
		EVENTS.remove(UpdateListener.class, this);
		stopConsuming();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		
		if(consuming)
		{
			keepConsuming();
			lastHealth = MC.player.getHealth();
			return;
		}
		
		handleDamageTriggers();
		lastHealth = MC.player.getHealth();
	}
	
	@Override
	public void onPlayerEnter(Player player,
		PlayerRangeAlertManager.PlayerInfo info)
	{
		if(!onPlayerEnter.isChecked() || consuming)
			return;
		
		tryConsume();
	}
	
	@Override
	public void onPlayerExit(PlayerRangeAlertManager.PlayerInfo info)
	{
		// not needed
	}
	
	private void handleDamageTriggers()
	{
		if(!onDamageFromAll.isChecked() && !onDamageFromPlayer.isChecked())
			return;
		
		float currentHealth = MC.player.getHealth();
		if(lastHealth < 0F || currentHealth >= lastHealth)
			return;
		
		DamageSource source = MC.player.getLastDamageSource();
		boolean fromPlayer = isFromPlayer(source);
		
		if(onDamageFromAll.isChecked()
			|| (onDamageFromPlayer.isChecked() && fromPlayer))
			tryConsume();
	}
	
	private boolean isFromPlayer(DamageSource source)
	{
		if(source == null)
			return false;
		
		Entity attacker = source.getEntity();
		if(attacker instanceof Player && attacker != MC.player)
			return true;
		
		Entity direct = source.getDirectEntity();
		return direct instanceof Player && direct != MC.player;
	}
	
	private void tryConsume()
	{
		if(consuming || MC.player == null)
			return;
		
		if(MC.player.getHealth() > health.getValueF() * 2F)
			return;
		
		int slot = findBestItemSlot();
		if(slot == -1)
			return;
		
		startConsuming(slot);
		
		if(disableOnActivation.isChecked())
			pendingDisable = true;
	}
	
	private int findBestItemSlot()
	{
		Inventory inventory = MC.player.getInventory();
		ItemTier[] tiers = ItemTier.values();
		int startIndex = itemPreference.getSelected().ordinal();
		
		for(int i = startIndex; i < tiers.length; i++)
		{
			ItemTier tier = tiers[i];
			
			for(int slot = 0; slot < 9; slot++)
				if(matchesTier(inventory.getItem(slot), tier))
					return slot;
				
			for(int slot = 9; slot < 36; slot++)
				if(matchesTier(inventory.getItem(slot), tier))
					return slot;
		}
		
		return -1;
	}
	
	private boolean matchesTier(ItemStack stack, ItemTier tier)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		if(isOnCooldown(stack))
			return false;
		
		if(tier == ItemTier.CHORUS_FRUIT && stack.is(Items.CHORUS_FRUIT))
			return true;
		
		String name =
			stack.getHoverName().getString().trim().toLowerCase(Locale.ROOT);
		return name.equals(tier.displayName);
	}
	
	private boolean isOnCooldown(ItemStack stack)
	{
		return MC.player != null
			&& MC.player.getCooldowns().isOnCooldown(stack);
	}
	
	private void startConsuming(int slot)
	{
		Inventory inventory = MC.player.getInventory();
		oldSlot = inventory.getSelectedSlot();
		swappedSlot = -1;
		
		if(silentSwitch.isChecked())
		{
			if(slot != oldSlot)
			{
				swappedSlot = slot;
				IMC.getInteractionManager().windowClick_SWAP(
					InventoryUtils.toNetworkSlot(slot), oldSlot);
			}
			
		}else if(slot < 9)
		{
			inventory.setSelectedSlot(slot);
			
		}else
		{
			swappedSlot = slot;
			IMC.getInteractionManager()
				.windowClick_SWAP(InventoryUtils.toNetworkSlot(slot), oldSlot);
		}
		
		consuming = true;
		useStartTick = MC.player.tickCount;
		MC.options.keyUse.setDown(true);
		IMC.getInteractionManager().rightClickItem();
	}
	
	private void keepConsuming()
	{
		if(packetSpam.isChecked())
		{
			for(int i = 0; i < 3; i++)
				IMC.getInteractionManager().rightClickItem();
			
			MC.rightClickDelay = 0;
			
			if(MC.gameMode != null)
				MC.gameMode.useItem(MC.player,
					net.minecraft.world.InteractionHand.MAIN_HAND);
		}
		
		if(MC.player.isUsingItem() || MC.player.tickCount <= useStartTick + 1)
		{
			MC.options.keyUse.setDown(true);
			return;
		}
		
		stopConsuming();
	}
	
	private void stopConsuming()
	{
		if(!consuming)
			return;
		
		consuming = false;
		MC.options.keyUse.setDown(false);
		
		if(oldSlot != -1)
		{
			if(swappedSlot != -1)
			{
				IMC.getInteractionManager().windowClick_SWAP(
					InventoryUtils.toNetworkSlot(swappedSlot), oldSlot);
				swappedSlot = -1;
			}
			
			MC.player.getInventory().setSelectedSlot(oldSlot);
			oldSlot = -1;
		}
		
		if(pendingDisable)
		{
			pendingDisable = false;
			setEnabled(false);
		}
	}
	
	private enum ItemTier
	{
		ENCHANTED_GOLDEN_CHORUS_FRUIT("enchanted golden chorus fruit"),
		GOLDEN_CHORUS_FRUIT("golden chorus fruit"),
		CHORUS_CARROT("chorus carrot"),
		CHORUS_FRUIT("chorus fruit");
		
		private final String displayName;
		
		private ItemTier(String displayName)
		{
			this.displayName = displayName;
		}
		
		@Override
		public String toString()
		{
			StringBuilder builder = new StringBuilder();
			String[] parts = displayName.split(" ");
			for(int i = 0; i < parts.length; i++)
			{
				if(i > 0)
					builder.append(" ");
				builder.append(Character.toUpperCase(parts[i].charAt(0)));
				builder.append(parts[i].substring(1));
			}
			return builder.toString();
		}
	}
}
