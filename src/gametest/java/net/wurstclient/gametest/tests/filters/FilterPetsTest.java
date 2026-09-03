/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests.filters;

import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.gametest.tests.EntityFilterTest;
import net.wurstclient.settings.filterlists.EntityFilterList.EntityFilter;
import net.wurstclient.settings.filters.FilterPetsSetting;

public final class FilterPetsTest extends EntityFilterTest
{
	public FilterPetsTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing pet filter");
		Supplier<EntityFilter> filter = () -> new FilterPetsSetting("", true);
		
		// Normal pets - filter out if tamed
		assertFilteredOut("Cat (tamed)", filter,
			() -> spawnTamedAnimal(EntityType.CAT));
		assertAllowed("Cat (untamed)", filter,
			() -> spawnEntity(EntityType.CAT));
		assertFilteredOut("Nautilus (tamed)", filter,
			() -> spawnTamedAnimal(EntityType.NAUTILUS));
		assertAllowed("Nautilus (untamed)", filter,
			() -> spawnEntity(EntityType.NAUTILUS));
		assertFilteredOut("Parrot (tamed)", filter,
			() -> spawnTamedAnimal(EntityType.PARROT));
		assertAllowed("Parrot (untamed)", filter,
			() -> spawnEntity(EntityType.PARROT));
		assertFilteredOut("Wolf (tamed)", filter,
			() -> spawnTamedAnimal(EntityType.WOLF));
		assertAllowed("Wolf (untamed)", filter,
			() -> spawnEntity(EntityType.WOLF));
		assertFilteredOut("Zombie Nautilus (tamed)", filter,
			() -> spawnTamedAnimal(EntityType.ZOMBIE_NAUTILUS));
		assertAllowed("Zombie Nautilus (untamed)", filter,
			() -> spawnEntity(EntityType.ZOMBIE_NAUTILUS));
		
		// Normal horse-likes - filter out if tamed
		assertFilteredOut("Donkey (tamed)", filter,
			() -> spawnTamedEquine(EntityType.DONKEY));
		assertAllowed("Donkey (untamed)", filter,
			() -> spawnEntity(EntityType.DONKEY));
		assertFilteredOut("Horse (tamed)", filter,
			() -> spawnTamedEquine(EntityType.HORSE));
		assertAllowed("Horse (untamed)", filter,
			() -> spawnEntity(EntityType.HORSE));
		assertFilteredOut("Llama (tamed)", filter,
			() -> spawnTamedEquine(EntityType.LLAMA));
		assertAllowed("Llama (untamed)", filter,
			() -> spawnEntity(EntityType.LLAMA));
		assertFilteredOut("Mule (tamed)", filter,
			() -> spawnTamedEquine(EntityType.MULE));
		assertAllowed("Mule (untamed)", filter,
			() -> spawnEntity(EntityType.MULE));
		assertFilteredOut("Trader Llama (tamed)", filter,
			() -> spawnTamedEquine(EntityType.TRADER_LLAMA));
		assertAllowed("Trader Llama (untamed)", filter,
			() -> spawnEntity(EntityType.TRADER_LLAMA));
		assertFilteredOut("Zombie Horse (tamed)", filter,
			() -> spawnTamedEquine(EntityType.ZOMBIE_HORSE));
		assertAllowed("Zombie Horse (untamed)", filter,
			() -> spawnEntity(EntityType.ZOMBIE_HORSE));
		
		// Special case: Skeleton Horses self-tame upon trap activation.
		// Relying on the tamed flag seems fine in this case.
		assertFilteredOut(
			"Skeleton Horse (tamed, as if from an already-activated trap)",
			filter, () -> spawnTamedEquine(EntityType.SKELETON_HORSE));
		assertAllowed(
			"Skeleton Horse (untamed, as if from a not-yet-activated trap)",
			filter, () -> spawnEntity(EntityType.SKELETON_HORSE));
		
		// Special case: Camels (both types) override isTamed() so that they are
		// always tamed. They support normal tamed flag too but ignore it.
		// They should only be considered pets if they have a saddle.
		assertFilteredOut("Camel (saddled)", filter,
			() -> spawnSaddledMob(EntityType.CAMEL));
		assertFilteredOut("Camel Husk (saddled)", filter,
			() -> spawnSaddledMob(EntityType.CAMEL_HUSK));
		assertAllowed("Camel (natural)", filter,
			() -> spawnEntity(EntityType.CAMEL));
		assertAllowed("Camel Husk (natural)", filter,
			() -> spawnEntity(EntityType.CAMEL_HUSK));
		assertAllowed("Camel (with unused tamed flag)", filter,
			() -> spawnTamedEquine(EntityType.CAMEL));
		assertAllowed("Camel Husk (with unused tamed flag)", filter,
			() -> spawnTamedEquine(EntityType.CAMEL_HUSK));
		
		// Special case: Striders don't support the tamed flag but otherwise
		// work in a similar way to Camels. Pet if saddled.
		assertFilteredOut("Strider (saddled)", filter,
			() -> spawnSaddledMob(EntityType.STRIDER));
		assertAllowed("Strider (natural)", filter,
			() -> spawnEntity(EntityType.STRIDER));
		
		// Special case: Pigs behave the same way as Striders so we should treat
		// them the same way. Pet if saddled.
		assertFilteredOut("Pig (saddled)", filter,
			() -> spawnSaddledMob(EntityType.PIG));
		assertAllowed("Pig (natural)", filter,
			() -> spawnEntity(EntityType.PIG));
		
		// Special case: Pet Ghasts (Happy Ghasts) are an entirely separate mob.
		assertFilteredOut("Happy Ghast", filter,
			() -> spawnEntity(EntityType.HAPPY_GHAST));
		assertAllowed("Ghast", filter, () -> spawnEntity(EntityType.GHAST));
		
		// Special case: Ocelots use a "trust" system that isn't tied to any
		// particular player. Pet if trusting.
		assertFilteredOut("Ocelot (trusting)", filter,
			() -> spawnTrustingOcelot());
		assertAllowed("Ocelot (wild)", filter,
			() -> spawnEntity(EntityType.OCELOT));
		
		// Special case: Foxes use an entirely different "trust" system with up
		// to two trusted players. Pet if either trusted player is set.
		assertFilteredOut("Fox (trusting)", filter, () -> spawnTrustingFox());
		assertAllowed("Fox (wild)", filter, () -> spawnEntity(EntityType.FOX));
		
		// Clean up taming particles
		context.waitTick();
		clearParticles();
	}
	
	private <T extends TamableAnimal> T spawnTamedAnimal(EntityType<T> type)
	{
		T entity = spawnEntity(type);
		context.runOnClient(mc -> entity.tame(mc.player));
		return entity;
	}
	
	private <T extends AbstractHorse> T spawnTamedEquine(EntityType<T> type)
	{
		T entity = spawnEntity(type);
		context.runOnClient(mc -> entity.tameWithName(mc.player));
		return entity;
	}
	
	private <T extends Mob> T spawnSaddledMob(EntityType<T> type)
	{
		T entity = spawnEntity(type);
		entity.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
		return entity;
	}
	
	private Ocelot spawnTrustingOcelot()
	{
		Ocelot entity = spawnEntity(EntityType.OCELOT);
		entity.setTrusting(true);
		return entity;
	}
	
	private Fox spawnTrustingFox()
	{
		Fox entity = spawnEntity(EntityType.FOX);
		context.runOnClient(mc -> entity.addTrustedEntity(mc.player));
		return entity;
	}
}
