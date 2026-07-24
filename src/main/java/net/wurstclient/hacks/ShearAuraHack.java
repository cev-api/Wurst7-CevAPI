/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.EntityHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TakeItemsFromSetting;
import net.wurstclient.settings.TakeItemsFromSetting.TakeItemsFrom;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"shear aura", "AutoShear", "auto shear", "AutoShearing",
	"auto shearing"})
public final class ShearAuraHack extends Hack
	implements UpdateListener, HandleInputListener
{
	private static final int RETRY_DELAY = 4;
	
	private final SliderSetting range =
		new SliderSetting("Range", "Determines how far ShearAura will reach.",
			5, 1, 10, 0.05, ValueDisplay.DECIMAL);
	
	private final TakeItemsFromSetting takeItemsFrom =
		TakeItemsFromSetting.withHands(this, TakeItemsFrom.HOTBAR);
	
	private final CheckboxSetting sheep = new CheckboxSetting("Sheep",
		"Shears adult sheep that still have wool.", true);
	
	private final CheckboxSetting mooshrooms = new CheckboxSetting("Mooshrooms",
		"Shears adult mooshrooms, turning them into cows.", true);
	
	private final CheckboxSetting snowGolems = new CheckboxSetting(
		"Snow golems", "Removes carved pumpkins from snow golems.", true);
	
	private final CheckboxSetting bogged = new CheckboxSetting("Bogged",
		"Shears mushrooms from bogged that have not been sheared yet.", true);
	
	private final CheckboxSetting copperGolems = new CheckboxSetting(
		"Copper golems", "Shears flowers from copper golem antennas.", true);
	
	private final CheckboxSetting sulfurCubes = new CheckboxSetting(
		"Sulfur cubes", "Shears swallowed blocks from sulfur cubes.", true);
	
	private final CheckboxSetting wolfArmor = new CheckboxSetting("Wolf armor",
		"Removes wolf armor from wolves that you own.", false);
	
	private final CheckboxSetting llamaCarpets =
		new CheckboxSetting("Llama carpets",
			"Removes carpets from llamas and trader llamas.", false);
	
	private final CheckboxSetting happyGhastHarnesses = new CheckboxSetting(
		"Happy Ghast harnesses", "Removes harnesses from Happy Ghasts.", false);
	
	private final CheckboxSetting leashedEntities =
		new CheckboxSetting("Leashed entities",
			"Removes every lead connected to an entity.", false);
	
	private final CheckboxSetting leashKnots = new CheckboxSetting(
		"Leash knots", "Removes leash knots and their connected leads.", false);
	
	private final CheckboxSetting saddles =
		new CheckboxSetting("Saddles", "Removes saddles from entities.", false);
	
	private final CheckboxSetting horseArmor = new CheckboxSetting(
		"Horse armor", "Removes armor from horse-like entities.", false);
	
	private final CheckboxSetting nautilusArmor = new CheckboxSetting(
		"Nautilus armor", "Removes armor from nautiluses.", false);
	
	private final Random random = new Random();
	private final Map<Integer, Long> retryAfter = new HashMap<>();
	private Entity target;
	
	public ShearAuraHack()
	{
		super("ShearAura");
		setCategory(Category.OTHER);
		
		addSetting(range);
		addSetting(takeItemsFrom);
		addSetting(sheep);
		addSetting(mooshrooms);
		addSetting(snowGolems);
		addSetting(bogged);
		addSetting(copperGolems);
		addSetting(sulfurCubes);
		addSetting(wolfArmor);
		addSetting(llamaCarpets);
		addSetting(happyGhastHarnesses);
		addSetting(leashedEntities);
		addSetting(leashKnots);
		addSetting(saddles);
		addSetting(horseArmor);
		addSetting(nautilusArmor);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().feedAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
		
		target = null;
		retryAfter.clear();
	}
	
	@Override
	public void onUpdate()
	{
		target = null;
		if(!hasAccessibleShears())
			return;
		
		long gameTime = MC.level.getGameTime();
		retryAfter.entrySet().removeIf(e -> e.getValue() <= gameTime);
		
		ArrayList<Entity> entities = StreamSupport
			.stream(MC.level.entitiesForRendering().spliterator(), false)
			.filter(e -> e != null && e != MC.player && !e.isRemoved()
				&& e.isAlive())
			.collect(Collectors.toCollection(ArrayList::new));
		
		Set<Entity> connectedLeashes = getConnectedLeashEntities(entities);
		double rangeSq = range.getValueSq();
		ArrayList<Entity> targets = entities.stream()
			.filter(e -> EntityUtils.distanceToHitboxSq(e) <= rangeSq)
			.filter(e -> !retryAfter.containsKey(e.getId())).filter(e -> {
				Action action = getNextAction(e, connectedLeashes);
				return action != null && isEnabled(action);
			}).collect(Collectors.toCollection(ArrayList::new));
		
		if(targets.isEmpty())
			return;
		
		target = targets.get(random.nextInt(targets.size()));
		WURST.getRotationFaker()
			.faceVectorPacket(target.getBoundingBox().getCenter());
	}
	
	@Override
	public void onHandleInput()
	{
		if(target == null)
			return;
		
		MultiPlayerGameMode gameMode = MC.gameMode;
		LocalPlayer player = MC.player;
		if(gameMode.isDestroying() || player.isHandsBusy())
			return;
		
		InteractionHand hand = getShearsHand();
		if(hand == null)
		{
			InventoryUtils.selectItem(Items.SHEARS,
				takeItemsFrom.getMaxInvSlot());
			target = null;
			return;
		}
		
		Entity clicked = target;
		EntityHitResult hitResult = EntityUtils.createHitResult(clicked);
		InteractionResult result =
			gameMode.interact(player, clicked, hitResult, hand);
		
		if(result instanceof InteractionResult.Success success)
		{
			retryAfter.put(clicked.getId(),
				MC.level.getGameTime() + RETRY_DELAY);
			
			if(success.swingSource() == InteractionResult.SwingSource.CLIENT)
				player.swing(hand);
		}
		
		target = null;
	}
	
	private boolean hasAccessibleShears()
	{
		if(getShearsHand() != null)
			return true;
		
		return InventoryUtils.indexOf(Items.SHEARS,
			takeItemsFrom.getMaxInvSlot()) >= 0;
	}
	
	private InteractionHand getShearsHand()
	{
		if(MC.player.getMainHandItem().is(Items.SHEARS))
			return InteractionHand.MAIN_HAND;
		
		if(MC.player.getOffhandItem().is(Items.SHEARS))
			return InteractionHand.OFF_HAND;
		
		return null;
	}
	
	private Set<Entity> getConnectedLeashEntities(Iterable<Entity> entities)
	{
		Set<Entity> connected = new HashSet<>();
		
		for(Entity entity : entities)
		{
			if(!(entity instanceof Leashable leashable)
				|| !leashable.isLeashed())
				continue;
			
			connected.add(entity);
			Entity holder = leashable.getLeashHolder();
			if(holder != null)
				connected.add(holder);
		}
		
		return connected;
	}
	
	private Action getNextAction(Entity entity, Set<Entity> connectedLeashes)
	{
		if(connectedLeashes.contains(entity))
			return entity instanceof LeashFenceKnotEntity ? Action.LEASH_KNOT
				: Action.LEASHED_ENTITY;
		
		Action equipmentAction = getEquipmentAction(entity);
		if(equipmentAction != null)
			return equipmentAction;
		
		if(!(entity instanceof Shearable shearable)
			|| !shearable.readyForShearing())
			return null;
		
		if(entity instanceof Sheep)
			return Action.SHEEP;
		
		if(entity instanceof MushroomCow)
			return Action.MOOSHROOM;
		
		if(entity instanceof SnowGolem)
			return Action.SNOW_GOLEM;
		
		if(entity instanceof Bogged)
			return Action.BOGGED;
		
		if(entity instanceof CopperGolem)
			return Action.COPPER_GOLEM;
		
		if(entity instanceof SulfurCube)
			return Action.SULFUR_CUBE;
		
		return null;
	}
	
	private Action getEquipmentAction(Entity entity)
	{
		if(!(entity instanceof Mob mob) || MC.player.isSecondaryUseActive())
			return null;
		
		if(mob instanceof Wolf wolf)
		{
			if(!wolf.isOwnedBy(MC.player))
				return null;
			
		}else if(mob.isVehicle())
			return null;
		
		for(EquipmentSlot slot : EquipmentSlot.VALUES)
		{
			ItemStack stack = mob.getItemBySlot(slot);
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			if(equippable == null || !equippable.canBeSheared())
				continue;
			
			if(!MC.player.isCreative() && EnchantmentHelper.has(stack,
				EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE))
				continue;
			
			return classifyEquipment(entity, slot, stack);
		}
		
		return null;
	}
	
	private Action classifyEquipment(Entity entity, EquipmentSlot slot,
		ItemStack stack)
	{
		if(entity instanceof Wolf && stack.is(Items.WOLF_ARMOR))
			return Action.WOLF_ARMOR;
		
		if(entity instanceof Llama && stack.is(ItemTags.WOOL_CARPETS))
			return Action.LLAMA_CARPET;
		
		if(entity instanceof HappyGhast && stack.is(ItemTags.HARNESSES))
			return Action.HAPPY_GHAST_HARNESS;
		
		if(slot == EquipmentSlot.SADDLE && stack.is(Items.SADDLE))
			return Action.SADDLE;
		
		if(slot == EquipmentSlot.BODY
			&& entity.typeHolder().is(EntityTypeTags.CAN_WEAR_HORSE_ARMOR))
			return Action.HORSE_ARMOR;
		
		if(slot == EquipmentSlot.BODY
			&& entity.typeHolder().is(EntityTypeTags.CAN_WEAR_NAUTILUS_ARMOR))
			return Action.NAUTILUS_ARMOR;
		
		return Action.UNLISTED_EQUIPMENT;
	}
	
	private boolean isEnabled(Action action)
	{
		return switch(action)
		{
			case SHEEP -> sheep.isChecked();
			case MOOSHROOM -> mooshrooms.isChecked();
			case SNOW_GOLEM -> snowGolems.isChecked();
			case BOGGED -> bogged.isChecked();
			case COPPER_GOLEM -> copperGolems.isChecked();
			case SULFUR_CUBE -> sulfurCubes.isChecked();
			case WOLF_ARMOR -> wolfArmor.isChecked();
			case LLAMA_CARPET -> llamaCarpets.isChecked();
			case HAPPY_GHAST_HARNESS -> happyGhastHarnesses.isChecked();
			case LEASHED_ENTITY -> leashedEntities.isChecked();
			case LEASH_KNOT -> leashKnots.isChecked();
			case SADDLE -> saddles.isChecked();
			case HORSE_ARMOR -> horseArmor.isChecked();
			case NAUTILUS_ARMOR -> nautilusArmor.isChecked();
			case UNLISTED_EQUIPMENT -> false;
		};
	}
	
	private enum Action
	{
		SHEEP,
		MOOSHROOM,
		SNOW_GOLEM,
		BOGGED,
		COPPER_GOLEM,
		SULFUR_CUBE,
		WOLF_ARMOR,
		LLAMA_CARPET,
		HAPPY_GHAST_HARNESS,
		LEASHED_ENTITY,
		LEASH_KNOT,
		SADDLE,
		HORSE_ARMOR,
		NAUTILUS_ARMOR,
		UNLISTED_EQUIPMENT;
	}
}
