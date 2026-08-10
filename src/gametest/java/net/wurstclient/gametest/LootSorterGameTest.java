/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.wurstclient.gametest.tests.LootSorterModelTest;

/** Boots a bound registry before exercising the LootSorter model layer. */
public final class LootSorterGameTest implements FabricClientGameTest
{
	@Override
	public void runTest(ClientGameTestContext context)
	{
		WurstTest.LOGGER.info("Testing LootSorter models");
		try(TestSingleplayerContext spContext = context.worldBuilder().create())
		{
			context.waitTicks(2);
			spContext.getConnection().waitForChunksRender();
			new LootSorterModelTest(context).run();
		}
		WurstTest.LOGGER.info("LootSorter model tests passed");
	}
}
