/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.component.Consumable;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"use item spam", "useitemspam", "crossbow machine gun",
	"crossbow spam"})
public final class UseItemSpamHack extends Hack implements UpdateListener
{
	private final CheckboxSetting crossbow =
		new CheckboxSetting("Crossbow", true);
	private final CheckboxSetting bow = new CheckboxSetting("Bow", false);
	private final CheckboxSetting throwables =
		new CheckboxSetting("Throwables", false);
	private final CheckboxSetting consumables =
		new CheckboxSetting("Consumables", false);
	private final CheckboxSetting utilityItems =
		new CheckboxSetting("Utility Items", false);
	private final CheckboxSetting all = new CheckboxSetting("All", false);
	
	private final SliderSetting delay =
		new SliderSetting("Delay", "Ticks between packets.", 0, 0, 20, 1,
			ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final CheckboxSetting correctSequence = new CheckboxSetting(
		"Correct sequence", "Use current prediction sequence.", true);
	
	private final CheckboxSetting requireRightClick = new CheckboxSetting(
		"Require right click", "Only repeat while holding use.", true);
	
	private final CheckboxSetting onlyWhenUsingItem =
		new CheckboxSetting("Only when using item",
			"Only repeat if Minecraft already thinks you are using the item.",
			false);
	
	private int timer;
	
	public UseItemSpamHack()
	{
		super("UseItemSpam");
		
		setCategory(Category.ITEMS);
		addSetting(crossbow);
		addSetting(bow);
		addSetting(throwables);
		addSetting(consumables);
		addSetting(utilityItems);
		addSetting(all);
		addSetting(delay);
		addSetting(correctSequence);
		addSetting(requireRightClick);
		addSetting(onlyWhenUsingItem);
	}
	
	@Override
	protected void onEnable()
	{
		timer = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.options == null
			|| MC.getConnection() == null)
			return;
		
		if(requireRightClick.isChecked() && !MC.options.keyUse.isDown())
		{
			timer = 0;
			return;
		}
		
		if(delay.getValueI() > 0)
		{
			if(timer < delay.getValueI())
			{
				timer++;
				return;
			}
			
			timer = 0;
		}
		
		InteractionHand specialHand = findSpecialHand();
		if(specialHand != null)
		{
			spamSpecialUse(specialHand);
			return;
		}
		
		InteractionHand genericHand = findGenericMatchingHand();
		if(genericHand == null)
			return;
		
		if(onlyWhenUsingItem.isChecked() && !isAlreadyUsing(genericHand))
			return;
		
		MC.startUseItem();
	}
	
	private void spamSpecialUse(InteractionHand hand)
	{
		ItemStack stack = MC.player.getItemInHand(hand);
		if(stack.isEmpty())
			return;
		
		if(onlyWhenUsingItem.isChecked() && !isAlreadyUsing(hand))
			return;
		
		if(stack.getItem() instanceof CrossbowItem)
		{
			boolean charged = CrossbowItem.isCharged(stack);
			
			// Help normal crossbows finish their charge/load cycle while the
			// key stays held, then fall back to packet spam for the firing
			// step.
			if(!charged)
			{
				if(!MC.player.isUsingItem())
				{
					MC.startUseItem();
					return;
				}
				
				if(MC.player.getUsedItemHand() == hand && MC.player
					.getTicksUsingItem() >= getCrossbowChargeTicks(stack))
				{
					if(MC.gameMode != null)
						MC.gameMode.releaseUsingItem(MC.player);
				}
				
				return;
			}
		}
		
		if(stack.getItem() instanceof BowItem)
		{
			if(!MC.player.isUsingItem())
			{
				MC.startUseItem();
				return;
			}
			
			if(MC.player.getUsedItemHand() == hand && MC.gameMode != null)
			{
				MC.gameMode.releaseUsingItem(MC.player);
				return;
			}
		}
		
		MC.getConnection().send(new ServerboundUseItemPacket(hand,
			getPredictionSequence(), MC.player.getYRot(), MC.player.getXRot()));
	}
	
	private int getCrossbowChargeTicks(ItemStack stack)
	{
		try
		{
			for(Method method : CrossbowItem.class.getDeclaredMethods())
			{
				if(!method.getName().equals("getChargeDuration")
					|| !Modifier.isStatic(method.getModifiers())
					|| method.getReturnType() != int.class)
					continue;
				
				method.setAccessible(true);
				Class<?>[] params = method.getParameterTypes();
				if(params.length == 2 && params[0] == ItemStack.class)
				{
					Object value = method.invoke(null, stack, MC.player);
					if(value instanceof Integer i)
						return i;
				}
				
				if(params.length == 1 && params[0] == ItemStack.class)
				{
					Object value = method.invoke(null, stack);
					if(value instanceof Integer i)
						return i;
				}
			}
			
		}catch(ReflectiveOperationException ignored)
		{}
		
		return 25;
	}
	
	private InteractionHand findSpecialHand()
	{
		ItemStack mainHand = MC.player.getMainHandItem();
		if(matchesSpecialType(mainHand))
			return InteractionHand.MAIN_HAND;
		
		ItemStack offHand = MC.player.getOffhandItem();
		if(matchesSpecialType(offHand))
			return InteractionHand.OFF_HAND;
		
		return null;
	}
	
	private boolean matchesSpecialType(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		Item item = stack.getItem();
		return crossbow.isChecked() && item instanceof CrossbowItem
			|| bow.isChecked() && item instanceof BowItem;
	}
	
	private InteractionHand findGenericMatchingHand()
	{
		ItemStack mainHand = MC.player.getMainHandItem();
		if(matchesGenericType(mainHand))
			return InteractionHand.MAIN_HAND;
		
		ItemStack offHand = MC.player.getOffhandItem();
		if(matchesGenericType(offHand))
			return InteractionHand.OFF_HAND;
		
		return null;
	}
	
	private boolean matchesGenericType(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		if(all.isChecked())
			return true;
		
		Item item = stack.getItem();
		return throwables.isChecked() && isThrowable(item)
			|| consumables.isChecked() && isConsumable(stack)
			|| utilityItems.isChecked() && isUtilityItem(item);
	}
	
	private boolean isAlreadyUsing(InteractionHand hand)
	{
		return MC.player.isUsingItem() && MC.player.getUsedItemHand() == hand;
	}
	
	private boolean isThrowable(Item item)
	{
		return item instanceof SnowballItem || item instanceof EggItem
			|| item instanceof EnderpearlItem
			|| item instanceof ThrowablePotionItem;
	}
	
	private boolean isConsumable(ItemStack stack)
	{
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		return consumable != null;
	}
	
	private boolean isUtilityItem(Item item)
	{
		if(item instanceof ShieldItem || item instanceof ProjectileWeaponItem
			|| item instanceof SnowballItem || item instanceof EggItem
			|| item instanceof EnderpearlItem
			|| item instanceof ThrowablePotionItem)
			return false;
		
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		if(id == null)
			return false;
		
		String path = id.getPath().toLowerCase(Locale.ROOT);
		return item instanceof ShieldItem || path.contains("bucket")
			|| path.contains("spyglass") || path.contains("goat_horn")
			|| path.contains("brush") || path.contains("flint_and_steel")
			|| path.contains("fire_charge") || path.contains("bundle")
			|| path.contains("writable_book") || path.contains("written_book");
	}
	
	private int getPredictionSequence()
	{
		if(!correctSequence.isChecked())
			return 0;
		
		try
		{
			Field handlerField = MC.level.getClass()
				.getDeclaredField("blockStatePredictionHandler");
			handlerField.setAccessible(true);
			Object handler = handlerField.get(MC.level);
			if(handler == null)
				return 0;
			
			Method currentSequence =
				handler.getClass().getMethod("currentSequence");
			Object value = currentSequence.invoke(handler);
			return value instanceof Integer i ? i : 0;
			
		}catch(ReflectiveOperationException e)
		{
			return 0;
		}
	}
}
