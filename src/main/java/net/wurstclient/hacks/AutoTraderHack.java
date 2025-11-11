/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;

import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"autotrader", "auto trader", "villager trader", "trade"})
public final class AutoTraderHack extends Hack implements UpdateListener
{
	private final ItemListSetting items = new ItemListSetting("Items",
		"Items to sell to the villager.", "minecraft:paper");
	
	public AutoTraderHack()
	{
		super("AutoTrader");
		setCategory(Category.OTHER);
		addSetting(items);
	}
	
	@Override
	protected void onEnable()
	{
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		WurstClient.INSTANCE.getEventManager().remove(UpdateListener.class,
			this);
	}
	
	public void triggerFromGui()
	{
		if(!isEnabled())
		{
			ChatUtils.warning("AutoTrader needs to be enabled.");
			return;
		}
		
		ChatUtils.message("AutoTrader triggered from GUI.");
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.currentScreen == null)
			return;
		
		if(!(MC.currentScreen instanceof MerchantScreen tradeScreen))
			return;
		
		TradeOfferList recipes = tradeScreen.getScreenHandler().getRecipes();
		if(recipes == null || recipes.isEmpty())
			return;
		
		List<String> wanted = items.getItemNames();
		if(wanted.isEmpty())
			return;
		
		ClientPlayerEntity player = MC.player;
		
		// do one purchase per update tick to avoid spamming
		if(MC.itemUseCooldown > 0)
			return;
		
		for(int i = 0; i < recipes.size(); i++)
		{
			TradeOffer offer = recipes.get(i);
			if(offer == null)
				continue;
			
			ItemStack sell = offer.getSellItem();
			// we want trades where the villager gives emeralds (we sell items)
			if(sell == null || sell.isEmpty()
				|| sell.getItem() != Items.EMERALD)
				continue;
			
			ItemStack firstBuy = offer.getDisplayedFirstBuyItem();
			if(firstBuy == null || firstBuy.isEmpty())
				continue;
			
			String req = net.minecraft.registry.Registries.ITEM
				.getId(firstBuy.getItem()).toString();
			boolean matches =
				wanted.stream().anyMatch(s -> s.equalsIgnoreCase(req));
			if(!matches)
				continue;
			
			// check inventory for required amount
			if(InventoryUtils.count(firstBuy.getItem()) < firstBuy.getCount())
				continue;
			
			// select trade
			tradeScreen.getScreenHandler().setRecipeIndex(i);
			tradeScreen.getScreenHandler().switchTo(i);
			MC.getNetworkHandler()
				.sendPacket(new SelectMerchantTradeC2SPacket(i));
			
			// click the result slot to perform the trade. Use HandledScreen
			// mouse clicks so the client's cursor stack is updated and we can
			// move the result into the inventory if it ends up on the cursor.
			var handler = tradeScreen.getScreenHandler();
			if(handler.slots.size() > 2)
			{
				var outputSlot = handler.slots.get(2);
				// pickup result
				tradeScreen.onMouseClick(outputSlot, outputSlot.id, 0,
					SlotActionType.PICKUP);
				
				// if result ended up on the cursor, quick-move it into the
				// inventory to avoid stalling.
				if(!handler.getCursorStack().isEmpty())
					tradeScreen.onMouseClick(outputSlot, outputSlot.id, 0,
						SlotActionType.QUICK_MOVE);
			}
			
			// set a small cooldown
			MC.itemUseCooldown = 4;
			return;
		}
	}
}
