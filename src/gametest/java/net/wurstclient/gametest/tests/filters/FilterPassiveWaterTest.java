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
import net.wurstclient.gametest.tests.EntityFilterTest;
import net.wurstclient.settings.filterlists.EntityFilterList.EntityFilter;
import net.wurstclient.settings.filters.FilterPassiveWaterSetting;

public final class FilterPassiveWaterTest extends EntityFilterTest
{
	public FilterPassiveWaterTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing passive water mob filter");
		Supplier<EntityFilter> filter =
			() -> new FilterPassiveWaterSetting("", true);
		
		// Filtered out
		assertFilteredOut("Axolotl", filter,
			() -> spawnEntity(EntityType.AXOLOTL));
		assertFilteredOut("Cod", filter, () -> spawnEntity(EntityType.COD));
		assertFilteredOut("Dolphin", filter,
			() -> spawnEntity(EntityType.DOLPHIN));
		assertFilteredOut("Glow Squid", filter,
			() -> spawnEntity(EntityType.GLOW_SQUID));
		assertFilteredOut("Salmon", filter,
			() -> spawnEntity(EntityType.SALMON));
		assertFilteredOut("Squid", filter,
			() -> spawnEntity(EntityType.SQUID));
		assertFilteredOut("Tadpole", filter,
			() -> spawnEntity(EntityType.TADPOLE));
		assertFilteredOut("Tropical Fish", filter,
			() -> spawnEntity(EntityType.TROPICAL_FISH));
		
		// Allowed because hostile
		assertAllowed("Drowned (hostile mob)", filter,
			() -> spawnEntity(EntityType.DROWNED));
		assertAllowed("Elder Guardian (hostile mob)", filter,
			() -> spawnEntity(EntityType.ELDER_GUARDIAN));
		assertAllowed("Guardian (hostile mob)", filter,
			() -> spawnEntity(EntityType.GUARDIAN));
		assertAllowed("Pufferfish (hostile mob)", filter,
			() -> spawnEntity(EntityType.PUFFERFISH));
		
		// Allowed because neutral
		assertAllowed("Nautilus (neutral mob)", filter,
			() -> spawnEntity(EntityType.NAUTILUS));
		assertAllowed("Zombie Nautilus (neutral mob)", filter,
			() -> spawnEntity(EntityType.ZOMBIE_NAUTILUS));
		
		// Allowed because land-based
		assertAllowed("Silverfish (land-based hostile mob)", filter,
			() -> spawnEntity(EntityType.SILVERFISH));
		assertAllowed("Turtle (land-based mob)", filter,
			() -> spawnEntity(EntityType.TURTLE));
	}
}
