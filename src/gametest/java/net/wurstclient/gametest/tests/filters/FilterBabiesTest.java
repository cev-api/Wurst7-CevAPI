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
		
		// Filtered out
		assertFilteredOut("Baby Armadillo", filter,
			() -> spawnBaby(EntityType.ARMADILLO));
		assertFilteredOut("Baby Axolotl", filter,
			() -> spawnBaby(EntityType.AXOLOTL));
		assertFilteredOut("Baby Bee", filter, () -> spawnBaby(EntityType.BEE));
		assertFilteredOut("Baby Camel", filter,
			() -> spawnBaby(EntityType.CAMEL));
		assertFilteredOut("Kitten (Baby Cat)", filter,
			() -> spawnBaby(EntityType.CAT));
		assertFilteredOut("Chick (Baby Chicken)", filter,
			() -> spawnBaby(EntityType.CHICKEN));
		assertFilteredOut("Baby Cow", filter, () -> spawnBaby(EntityType.COW));
		assertFilteredOut("Baby Dolphin", filter,
			() -> spawnBaby(EntityType.DOLPHIN));
		assertFilteredOut("Baby Donkey", filter,
			() -> spawnBaby(EntityType.DONKEY));
		assertFilteredOut("Baby Fox", filter, () -> spawnBaby(EntityType.FOX));
		assertFilteredOut("Tadpole (Baby Frog)", filter,
			() -> spawnEntity(EntityType.TADPOLE));
		assertFilteredOut("Ghastling (Baby Happy Ghast)", filter,
			() -> spawnBaby(EntityType.HAPPY_GHAST));
		assertFilteredOut("Baby Glow Squid", filter,
			() -> spawnBaby(EntityType.GLOW_SQUID));
		assertFilteredOut("Baby Goat", filter,
			() -> spawnBaby(EntityType.GOAT));
		assertFilteredOut("Foal (Baby Horse)", filter,
			() -> spawnBaby(EntityType.HORSE));
		assertFilteredOut("Baby Llama", filter,
			() -> spawnBaby(EntityType.LLAMA));
		assertFilteredOut("Baby Mooshroom", filter,
			() -> spawnBaby(EntityType.MOOSHROOM));
		assertFilteredOut("Baby Mule", filter,
			() -> spawnBaby(EntityType.MULE));
		assertFilteredOut("Baby Nautilus", filter,
			() -> spawnBaby(EntityType.NAUTILUS));
		assertFilteredOut("Baby Ocelot", filter,
			() -> spawnBaby(EntityType.OCELOT));
		assertFilteredOut("Baby Panda", filter,
			() -> spawnBaby(EntityType.PANDA));
		assertFilteredOut("Baby Pig", filter, () -> spawnBaby(EntityType.PIG));
		assertFilteredOut("Baby Polar Bear", filter,
			() -> spawnBaby(EntityType.POLAR_BEAR));
		assertFilteredOut("Baby Rabbit", filter,
			() -> spawnBaby(EntityType.RABBIT));
		assertFilteredOut("Baby Sheep", filter,
			() -> spawnBaby(EntityType.SHEEP));
		assertFilteredOut("Baby Skeleton Horse", filter,
			() -> spawnBaby(EntityType.SKELETON_HORSE));
		assertFilteredOut("Snifflet (Baby Sniffer)", filter,
			() -> spawnBaby(EntityType.SNIFFER));
		assertFilteredOut("Baby Squid", filter,
			() -> spawnBaby(EntityType.SQUID));
		assertFilteredOut("Baby Strider", filter,
			() -> spawnBaby(EntityType.STRIDER));
		assertFilteredOut("Baby Trader Llama", filter,
			() -> spawnBaby(EntityType.TRADER_LLAMA));
		assertFilteredOut("Baby Turtle", filter,
			() -> spawnBaby(EntityType.TURTLE));
		assertFilteredOut("Puppy (Baby Wolf)", filter,
			() -> spawnBaby(EntityType.WOLF));
		assertFilteredOut("Baby Villager", filter,
			() -> spawnBaby(EntityType.VILLAGER));
		assertFilteredOut("Baby Zombie Horse", filter,
			() -> spawnBaby(EntityType.ZOMBIE_HORSE));
		
		// Allowed because hostile
		assertAllowed("Gurgle (Baby Drowned)", filter,
			() -> spawnBaby(EntityType.DROWNED));
		assertAllowed("Baby Hoglin", filter,
			() -> spawnBaby(EntityType.HOGLIN));
		assertAllowed("Baby Husk", filter, () -> spawnBaby(EntityType.HUSK));
		assertAllowed("Baby Zoglin", filter,
			() -> spawnBaby(EntityType.ZOGLIN));
		assertAllowed("Baby Zombie", filter,
			() -> spawnBaby(EntityType.ZOMBIE));
		assertAllowed("Baby Zombie Villager", filter,
			() -> spawnBaby(EntityType.ZOMBIE_VILLAGER));
		
		// Allowed because neutral
		assertAllowed("Baby Piglin", filter,
			() -> spawnBaby(EntityType.PIGLIN));
		assertAllowed("Baby Zombified Piglin", filter,
			() -> spawnBaby(EntityType.ZOMBIFIED_PIGLIN));
	}
	
	private <T extends Mob> T spawnBaby(EntityType<T> type)
	{
		T entity = spawnEntity(type);
		entity.setBaby(true);
		return entity;
	}
}
