/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests.filters;

import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.wurstclient.gametest.tests.EntityFilterTest;
import net.wurstclient.settings.filterlists.EntityFilterList.EntityFilter;
import net.wurstclient.settings.filters.FilterBabiesSetting;

public final class FilterBabiesTest extends EntityFilterTest
{
	public FilterBabiesTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing baby mob filter");
		Supplier<EntityFilter> filter = () -> new FilterBabiesSetting("", true);
		
		// Passive mobs (filter out if baby)
		for(EntityType<? extends Mob> type : List.of(EntityType.ARMADILLO,
			EntityType.AXOLOTL, EntityType.BEE, EntityType.CAMEL,
			EntityType.CAT, EntityType.CHICKEN, EntityType.COW,
			EntityType.DOLPHIN, EntityType.DONKEY, EntityType.FOX,
			EntityType.HAPPY_GHAST, EntityType.GLOW_SQUID, EntityType.GOAT,
			EntityType.HORSE, EntityType.LLAMA, EntityType.MOOSHROOM,
			EntityType.MULE, EntityType.NAUTILUS, EntityType.OCELOT,
			EntityType.PANDA, EntityType.PIG, EntityType.POLAR_BEAR,
			EntityType.RABBIT, EntityType.SHEEP, EntityType.SKELETON_HORSE,
			EntityType.SNIFFER, EntityType.SQUID, EntityType.STRIDER,
			EntityType.TRADER_LLAMA, EntityType.TURTLE, EntityType.WOLF,
			EntityType.VILLAGER, EntityType.ZOMBIE_HORSE))
		{
			assertFilteredOut(type.toShortString() + " (baby)", filter,
				() -> spawnBaby(type));
			assertAllowed(type.toShortString() + " (adult)", filter,
				() -> spawnEntity(type));
		}
		
		// Special case: Tadpoles (baby frogs) are a separate entity type
		assertFilteredOut(EntityType.TADPOLE.toShortString(), filter,
			() -> spawnEntity(EntityType.TADPOLE));
		assertAllowed(EntityType.FROG.toShortString(), filter,
			() -> spawnEntity(EntityType.FROG));
		
		// Hostile mobs (always allow)
		for(EntityType<? extends Mob> type : List.of(EntityType.DROWNED,
			EntityType.HOGLIN, EntityType.HUSK, EntityType.ZOGLIN,
			EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER))
		{
			assertAllowed(type.toShortString() + " (baby)", filter,
				() -> spawnBaby(type));
			assertAllowed(type.toShortString() + " (adult)", filter,
				() -> spawnEntity(type));
		}
		
		// Neutral mobs (always allow)
		for(EntityType<? extends Mob> type : List.of(EntityType.PIGLIN,
			EntityType.ZOMBIFIED_PIGLIN))
		{
			assertAllowed(type.toShortString() + " (baby)", filter,
				() -> spawnBaby(type));
			assertAllowed(type.toShortString() + " (adult)", filter,
				() -> spawnEntity(type));
		}
	}
	
	private <T extends Mob> T spawnBaby(EntityType<T> type)
	{
		T entity = spawnEntity(type);
		entity.setBaby(true);
		return entity;
	}
}
