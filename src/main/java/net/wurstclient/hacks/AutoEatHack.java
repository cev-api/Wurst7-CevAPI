/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyBinding;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TakeItemsFromSetting;
import net.wurstclient.settings.TakeItemsFromSetting.TakeItemsFrom;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"auto eat", "AutoFood", "auto food", "AutoFeeder", "auto feeder",
	"AutoFeeding", "auto feeding", "AutoSoup", "auto soup"})
public final class AutoEatHack extends Hack implements UpdateListener
{
	private final SliderSetting targetHunger = new SliderSetting(
		"Target hunger", "description.wurst.setting.autoeat.target_hunger", 10,
		0, 10, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting minHunger = new SliderSetting("Min hunger",
		"description.wurst.setting.autoeat.min_hunger", 6.5, 0, 10, 0.5,
		ValueDisplay.DECIMAL);
	
	private final SliderSetting injuredHunger = new SliderSetting(
		"Injured hunger", "description.wurst.setting.autoeat.injured_hunger",
		10, 0, 10, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting injuryThreshold =
		new SliderSetting("Injury threshold",
			"description.wurst.setting.autoeat.injury_threshold", 1.5, 0.5, 10,
			0.5, ValueDisplay.DECIMAL);
	
	private final TakeItemsFromSetting takeItemsFrom =
		TakeItemsFromSetting.withHands(this, TakeItemsFrom.HOTBAR);
	
	private final CheckboxSetting allowOffhand =
		new CheckboxSetting("Allow offhand", true);
	
	private final CheckboxSetting eatWhileWalking =
		new CheckboxSetting("Eat while walking",
			"description.wurst.setting.autoeat.eat_while_walking", false);
	
	private final CheckboxSetting eatWhileFlying = new CheckboxSetting(
		"Eat while flying", "Allows AutoEat to continue while flying.", true);
	
	private final CheckboxSetting eatWhileLookingAtMobs = new CheckboxSetting(
		"Eat while looking at mobs",
		"Allows AutoEat to continue while the crosshair is on a mob.", true);
	
	private final CheckboxSetting eatWhileLookingAtPlayers =
		new CheckboxSetting("Eat while looking at players",
			"Allows AutoEat to continue while the crosshair is on a player.",
			false);
	
	private final CheckboxSetting eatThroughWalls = new CheckboxSetting(
		"Eat through walls",
		"Allows AutoEat to continue while the crosshair is on a wall.", true);
	
	private final CheckboxSetting allowHunger =
		new CheckboxSetting("Allow hunger effect",
			"description.wurst.setting.autoeat.allow_hunger", true);
	
	private final CheckboxSetting allowPoison =
		new CheckboxSetting("Allow poison effect",
			"description.wurst.setting.autoeat.allow_poison", false);
	
	private final CheckboxSetting allowChorus =
		new CheckboxSetting("Allow chorus fruit",
			"description.wurst.setting.autoeat.allow_chorus", false);
	
	private int oldSlot = -1;
	private int selectedFoodSlot = -1;
	private boolean startedUsingFood;
	private InteractionHand autoEatUseHand;
	private ItemStack autoEatUseStack = ItemStack.EMPTY;
	
	public AutoEatHack()
	{
		super("AutoEat");
		setCategory(Category.ITEMS);
		
		addSetting(targetHunger);
		addSetting(minHunger);
		addSetting(injuredHunger);
		addSetting(injuryThreshold);
		
		addSetting(takeItemsFrom);
		addSetting(allowOffhand);
		
		addSetting(eatWhileWalking);
		addSetting(eatWhileFlying);
		addSetting(eatWhileLookingAtMobs);
		addSetting(eatWhileLookingAtPlayers);
		addSetting(eatThroughWalls);
		addSetting(allowHunger);
		addSetting(allowPoison);
		addSetting(allowChorus);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().autoSoupHack.setEnabled(false);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		if(isEating())
			stopEating();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		
		if(!shouldEat())
		{
			if(isEating())
				stopEating();
			
			return;
		}
		
		FoodData hungerManager = player.getFoodData();
		int foodLevel = hungerManager.getFoodLevel();
		int targetHungerI = (int)(targetHunger.getValue() * 2);
		int minHungerI = (int)(minHunger.getValue() * 2);
		int injuredHungerI = (int)(injuredHunger.getValue() * 2);
		
		if(isInjured(player) && foodLevel < injuredHungerI)
		{
			eat(-1);
			return;
		}
		
		if(foodLevel < minHungerI)
		{
			eat(-1);
			return;
		}
		
		if(foodLevel < targetHungerI)
		{
			int maxPoints = targetHungerI - foodLevel;
			eat(maxPoints);
		}else if(isEating())
		{
			// Release the pause even when the target is below full hunger.
			stopEating();
		}
	}
	
	/**
	 * Used by combat hacks to yield before they perform an action that would
	 * interfere with eating. This includes the inventory-transfer tick before
	 * AutoEat has started using the item.
	 */
	public boolean shouldPauseOtherActions()
	{
		if(manualAttackHasPriority(
			IKeyBinding.get(MC.options.keyAttack).isActuallyDown()))
			return false;
		
		if(isEating())
			return true;
		
		if(MC.player == null || MC.player.isUsingItem())
			return false;
		
		if(!shouldEat())
			return false;
		
		LocalPlayer player = MC.player;
		FoodData food = player.getFoodData();
		int foodLevel = food.getFoodLevel();
		int target = (int)(targetHunger.getValue() * 2);
		int min = (int)(minHunger.getValue() * 2);
		int injured = (int)(injuredHunger.getValue() * 2);
		
		int maxPoints = -1;
		if(isInjured(player) && foodLevel < injured)
			maxPoints = -1;
		else if(foodLevel < min)
			maxPoints = -1;
		else if(foodLevel < target)
			maxPoints = target - foodLevel;
		else
			return false;
		
		return findBestFoodSlot(maxPoints) != -1;
	}
	
	private void eat(int maxPoints)
	{
		Inventory inventory = MC.player.getInventory();
		if(MC.player.isUsingItem())
		{
			if(!startedUsingFood || !isUsingAutoEatFood())
				stopEating();
			
			return;
		}
		if(startedUsingFood && !MC.player.isUsingItem())
			startedUsingFood = false;
		
		int foodSlot = findBestFoodSlot(maxPoints);
		
		if(foodSlot == -1)
		{
			if(isEating())
				stopEating();
			
			return;
		}
		
		// select food
		if(foodSlot < 9)
		{
			if(oldSlot == -1)
				oldSlot = inventory.getSelectedSlot();
			
			selectedFoodSlot = foodSlot;
			inventory.setSelectedSlot(foodSlot);
			
		}else if(foodSlot == 40)
		{
			if(oldSlot == -1)
				oldSlot = inventory.getSelectedSlot();
			selectedFoodSlot = -1;
			
			// off-hand slot, no need to select anything
			
		}else
		{
			if(oldSlot == -1)
				oldSlot = inventory.getSelectedSlot();
			selectedFoodSlot = -1;
			InventoryUtils.selectItem(foodSlot);
			return;
		}
		
		// Keep the use binding held, but submit the use action only once.
		MC.options.keyUse.setDown(true);
		if(shouldStartFoodUse(startedUsingFood, MC.player.isUsingItem()))
		{
			autoEatUseHand = foodSlot == 40 ? InteractionHand.OFF_HAND
				: InteractionHand.MAIN_HAND;
			autoEatUseStack = MC.player.getItemInHand(autoEatUseHand).copy();
			if(autoEatUseHand == InteractionHand.OFF_HAND)
				MC.gameMode.useItem(MC.player, autoEatUseHand);
			else
				IMC.getGameMode().rightClickItem();
			startedUsingFood = true;
		}
	}
	
	private int findBestFoodSlot(int maxPoints)
	{
		Inventory inventory = MC.player.getInventory();
		FoodProperties bestFood = null;
		int bestSlot = -1;
		
		int maxInvSlot = takeItemsFrom.getMaxInvSlot();
		
		ArrayList<Integer> slots = new ArrayList<>();
		if(maxInvSlot == 0)
			slots.add(inventory.getSelectedSlot());
		if(allowOffhand.isChecked())
			slots.add(40);
		Stream.iterate(0, i -> i < maxInvSlot, i -> i + 1)
			.forEach(i -> slots.add(i));
		
		Comparator<FoodProperties> comparator =
			Comparator.comparingDouble(FoodProperties::saturation);
		
		for(int slot : slots)
		{
			ItemStack stack = inventory.getItem(slot);
			
			// filter out non-food items
			if(!stack.has(DataComponents.FOOD))
				continue;
			
			if(!isAllowedFood(stack.get(DataComponents.CONSUMABLE)))
				continue;
			
			FoodProperties food = stack.get(DataComponents.FOOD);
			if(maxPoints >= 0 && food.nutrition() > maxPoints)
				continue;
			
			// compare to previously found food
			if(bestFood == null || comparator.compare(food, bestFood) > 0)
			{
				bestFood = food;
				bestSlot = slot;
			}
		}
		
		return bestSlot;
	}
	
	private boolean shouldEat()
	{
		if(MC.player == null || MC.level == null || manualAttackHasPriority(
			IKeyBinding.get(MC.options.keyAttack).isActuallyDown()))
			return false;
		
		if(MC.player.getAbilities().instabuild)
			return false;
		
		if(!MC.player.canEat(false))
			return false;
		
		boolean autoFlyActive = WURST.getHax().autoFlyHack.isEnabled();
		boolean flying = MC.player.getAbilities().flying
			|| WURST.getHax().flightHack.isEnabled() || autoFlyActive;
		if(flying && !eatWhileFlying.isChecked())
			return false;
		
		if(!eatWhileWalking.isChecked() && !autoFlyActive
			&& (MC.player.zza != 0 || MC.player.xxa != 0))
			return false;
		
		boolean mobCombat = WURST.getHax().multiAuraHack.isFightingMobsOnly();
		if(!autoFlyActive && isClickable(MC.hitResult)
			&& !(mobCombat && eatWhileLookingAtMobs.isChecked()))
			return false;
		
		return true;
	}
	
	private void stopEating()
	{
		if(MC.player != null)
		{
			if(startedUsingFood && isUsingAutoEatFood() && MC.gameMode != null)
				MC.gameMode.releaseUsingItem(MC.player);
			
			IKeyBinding.get(MC.options.keyUse).resetPressedState();
			if(selectedFoodSlot >= 0 && MC.player.getInventory()
				.getSelectedSlot() == selectedFoodSlot)
				MC.player.getInventory().setSelectedSlot(oldSlot);
		}
		selectedFoodSlot = -1;
		startedUsingFood = false;
		autoEatUseHand = null;
		autoEatUseStack = ItemStack.EMPTY;
		oldSlot = -1;
	}
	
	private boolean isUsingAutoEatFood()
	{
		return MC.player != null && MC.player.isUsingItem()
			&& autoEatUseHand != null
			&& MC.player.getUsedItemHand() == autoEatUseHand
			&& ItemStack.isSameItemSameComponents(MC.player.getUseItem(),
				autoEatUseStack);
	}
	
	static boolean shouldStartFoodUse(boolean alreadyStarted,
		boolean playerUsingItem)
	{
		return !alreadyStarted && !playerUsingItem;
	}
	
	static boolean manualAttackHasPriority(boolean attackKeyDown)
	{
		return attackKeyDown;
	}
	
	private boolean isAllowedFood(Consumable consumable)
	{
		for(ConsumeEffect consumeEffect : consumable.onConsumeEffects())
		{
			if(!allowChorus.isChecked()
				&& consumeEffect instanceof TeleportRandomlyConsumeEffect)
				return false;
			
			if(!(consumeEffect instanceof ApplyStatusEffectsConsumeEffect applyEffectsConsumeEffect))
				continue;
			
			for(MobEffectInstance effect : applyEffectsConsumeEffect.effects())
			{
				Holder<MobEffect> entry = effect.getEffect();
				
				if(!allowHunger.isChecked() && entry == MobEffects.HUNGER)
					return false;
				
				if(!allowPoison.isChecked() && entry == MobEffects.POISON)
					return false;
			}
		}
		
		return true;
	}
	
	public boolean isEating()
	{
		return oldSlot != -1;
	}
	
	private boolean isClickable(HitResult hitResult)
	{
		if(hitResult == null)
			return false;
		
		if(hitResult instanceof EntityHitResult)
		{
			Entity entity = ((EntityHitResult)hitResult).getEntity();
			if(entity instanceof Player)
				return !eatWhileLookingAtPlayers.isChecked();
			
			return !eatWhileLookingAtMobs.isChecked();
		}
		
		if(hitResult instanceof BlockHitResult)
		{
			BlockPos pos = ((BlockHitResult)hitResult).getBlockPos();
			if(pos == null)
				return false;
			
			Block block = MC.level.getBlockState(pos).getBlock();
			if(block instanceof BaseEntityBlock
				|| block instanceof CraftingTableBlock)
				return true;
			
			return !eatThroughWalls.isChecked();
		}
		
		return false;
	}
	
	private boolean isInjured(LocalPlayer player)
	{
		int injuryThresholdI = (int)(injuryThreshold.getValue() * 2);
		return player.getHealth() < player.getMaxHealth() - injuryThresholdI;
	}
}
