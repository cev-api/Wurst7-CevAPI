/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import com.mojang.blaze3d.platform.InputConstants;

import static net.wurstclient.gametest.WurstClientTestHelper.*;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.extractor.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.extractor.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.extractor.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.extractor.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.wurstclient.gametest.tests.*;
import net.wurstclient.gametest.tests.filters.FilterBabiesTest;
import net.wurstclient.gametest.tests.filters.FilterCalmTest;
import net.wurstclient.gametest.tests.filters.FilterPassiveWaterTest;
import net.wurstclient.gametest.tests.filters.FilterPetsTest;
import net.wurstclient.gametest.tests.filters.MobDispositionTest;

public class WurstTest implements FabricClientGameTest
{
	public static final Logger LOGGER = LoggerFactory.getLogger("Wurst Test");
	
	public static final boolean IS_SODIUM_INSTALLED =
		FabricLoader.getInstance().isModLoaded("sodium");
	public static final boolean IS_LOOTR_INSTALLED =
		FabricLoader.getInstance().isModLoaded("lootr");
	
	@Override
	public void runTest(ClientGameTestContext extractor)
	{
		LOGGER.info("Starting Wurst Client GameTest");
		extractor.runOnClient(mc -> mc.options
			.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
		hideSplashTexts(extractor);
		waitForTitleScreenFade(extractor);
		
		LOGGER.info("Reached title screen");
		AltManagerTest.testAltManagerButton(extractor);
		
		LOGGER.info("Creating test world");
		TestWorldBuilder worldBuilder = extractor.worldBuilder();
		worldBuilder.adjustSettings(creator -> {
			String mcVersion = SharedConstants.getCurrentVersion().name();
			creator.setName("E2E Test " + mcVersion);
			creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			creator.getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, false,
				null);
			applyFlatPresetWithSmoothStone(creator);
		});
		
		try(TestSingleplayerContext spContext = worldBuilder.create())
		{
			testInWorld(extractor, spContext);
			LOGGER.info("Exiting test world");
		}
		
		LOGGER.info("Test complete");
	}
	
	private void testInWorld(ClientGameTestContext extractor,
		TestSingleplayerContext spContext)
	{
		TestInput input = extractor.getInput();
		TestServerConnection connection = spContext.getConnection();
		TestServerContext server = spContext.getServer();
		
		// Disable chunk fade
		extractor
			.runOnClient(mc -> mc.options.chunkSectionFadeInTime().set(0.0));
		new VaultRollModelTest(extractor).run();
		
		runCommand(server, "time set noon");
		runCommand(server, "tp 0 -57 0");
		BlockTestHelper.setBlocksAndWait(extractor, spContext, blocks -> {
			blocks.fill(0, -60, 0, 0, -58, 0, Blocks.SMOOTH_STONE);
			blocks.fill(-12, -60, 10, 12, -48, 10, Blocks.SMOOTH_STONE);
		});
		
		LOGGER.info("Loading chunks");
		extractor.waitTicks(2);
		connection.waitForChunksRender();
		
		assertScreenshotEquals(extractor, "in_game",
			"https://i.imgur.com/EfzN9Cd.png");
		
		LOGGER.info("Recording debug menu");
		input.pressKey(InputConstants.KEY_F3);
		extractor.takeScreenshot("debug_menu");
		input.pressKey(InputConstants.KEY_F3);
		
		LOGGER.info("Checking for broken mixins");
		MixinEnvironment.getCurrentEnvironment().audit();
		
		LOGGER.info("Opening inventory");
		input.pressKey(InputConstants.KEY_E);
		input.setCursorPos(427, 240);
		assertScreenshotEquals(extractor, "inventory",
			"https://i.imgur.com/LyQ5FSD.png");
		input.pressKey(InputConstants.KEY_ESCAPE);
		
		runWurstCommand(extractor,
			"setmode WurstLogo visibility only_when_outdated");
		runWurstCommand(extractor, "setcheckbox HackList animations off");
		
		new PauseScreenTest(extractor, spContext).run();
		
		// Test entity filters
		new FilterBabiesTest(extractor, spContext).run();
		new FilterPassiveWaterTest(extractor, spContext).run();
		new FilterPetsTest(extractor, spContext).run();
		new MobDispositionTest(extractor, spContext).run();
		new FilterCalmTest(extractor, spContext).run();
		
		// TODO: Open ClickGUI and Navigator
		
		// Test Wurst hacks
		new AntiWobbleHackTest(extractor, spContext).run();
		new AutoSprintHackTest(extractor, spContext).run();
		new AutoMineHackTest(extractor, spContext).run();
		new BlinkHackSmokeTest(extractor, spContext).run();
		new ChestEspGroupTest(extractor, spContext).run();
		new ChestEspRenderingTest(extractor, spContext).run();
		new FreecamHackTest(extractor, spContext).run();
		new LsdHackTest(extractor, spContext).run();
		new NoFallHackTest(extractor, spContext).run();
		new NoShieldOverlayHackTest(extractor, spContext).run();
		new NoWeatherHackTest(extractor, spContext).run();
		new XRayHackTest(extractor, spContext).run();
		
		// Test Wurst commands
		new CopyItemCmdTest(extractor, spContext).run();
		new DamageCmdTest(extractor, spContext).run();
		new GiveCmdTest(extractor, spContext).run();
		new ModifyCmdTest(extractor, spContext).run();
		
		// TODO: Test more Wurst features
		
		// Test special cases
		new AttributeSwapMechanicTest(extractor, spContext).run();
		new OcclusionCullingTest(extractor, spContext).run();
		new PistonTest(extractor, spContext).run();
	}
	
	// because the grass texture is randomized and smooth stone isn't
	private void applyFlatPresetWithSmoothStone(WorldCreationUiState creator)
	{
		FlatLevelGeneratorSettings config = ((FlatLevelSource)creator
			.getSettings().selectedDimensions().overworld()).settings();
		
		List<FlatLayerInfo> layers =
			List.of(new FlatLayerInfo(1, Blocks.BEDROCK),
				new FlatLayerInfo(2, Blocks.DIRT),
				new FlatLayerInfo(1, Blocks.SMOOTH_STONE));
		
		creator.updateDimensions(
			(drm, dorHolder) -> dorHolder.replaceOverworldGenerator(drm,
				new FlatLevelSource(config.withBiomeAndLayers(layers,
					config.structureOverrides(), config.getBiome()))));
	}
}
