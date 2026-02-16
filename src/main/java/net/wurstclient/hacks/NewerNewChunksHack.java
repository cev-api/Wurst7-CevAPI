/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;

public final class NewerNewChunksHack extends Hack
	implements UpdateListener, RenderListener
{
	public enum DetectMode
	{
		Normal,
		IgnoreBlockExploit,
		BlockExploitMode
	}
	
	private static final Direction[] SEARCH_DIRS =
		new Direction[]{Direction.EAST, Direction.NORTH, Direction.WEST,
			Direction.SOUTH, Direction.UP};
	
	private final EnumSetting<DetectMode> detectMode = new EnumSetting<>(
		"Chunk Detection Mode", DetectMode.values(), DetectMode.Normal);
	
	private final CheckboxSetting liquidExploit =
		new CheckboxSetting("LiquidExploit",
			"Estimates new chunks based on flowing liquids.", false);
	
	private final CheckboxSetting blockUpdateExploit =
		new CheckboxSetting("BlockUpdateExploit",
			"Uses block updates as additional chunk activity signal.", false);
	
	private final CheckboxSetting removeOnDisable = new CheckboxSetting(
		"RemoveOnModuleDisabled", "Removes cached chunks on disable.", true);
	
	private final CheckboxSetting removeOutsideRenderDistance =
		new CheckboxSetting("RemoveOutsideRenderDistance",
			"Removes cached chunks once they leave render distance.", false);
	
	private final CheckboxSetting saveChunkData = new CheckboxSetting(
		"SaveChunkData", "Saves detected chunks to disk.", true);
	
	private final CheckboxSetting loadChunkData = new CheckboxSetting(
		"LoadChunkData", "Loads saved chunks when enabling this hack.", true);
	
	private final CheckboxSetting autoReloadChunks = new CheckboxSetting(
		"AutoReloadChunks", "Reloads chunk savefiles after a delay.", false);
	
	private final SliderSetting autoReloadDelaySeconds = new SliderSetting(
		"AutoReloadDelayInSeconds", 60, 1, 300, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting alarms =
		new CheckboxSetting("Enable Alarms for NewChunks",
			"Rings a sound when new chunks appear.", false);
	
	private final SliderSetting amountOfRings =
		new SliderSetting("Amount Of Rings", 1, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting ringDelay = new SliderSetting(
		"Delay Between Rings (ticks)", 20, 1, 100, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting volume =
		new SliderSetting("Volume", 1, 0, 1, 0.05, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting pitch =
		new SliderSetting("Pitch", 1, 0.5, 2, 0.05, ValueDisplay.DECIMAL);
	
	private final ButtonSetting deleteChunkData = new ButtonSetting(
		"Delete Chunk Data", "Deletes chunk data for current world/dimension.",
		this::confirmDeleteChunkData);
	
	private final SliderSetting renderDistance = new SliderSetting(
		"Render-Distance(Chunks)", 64, 6, 1024, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting renderHeight = new SliderSetting(
		"Render-Height", 0, -64, 319, 1, ValueDisplay.INTEGER);
	
	private final ColorSetting newChunksColor =
		new ColorSetting("New Chunks Color", new Color(255, 0, 0));
	
	private final ColorSetting blockExploitChunksColor =
		new ColorSetting("BlockExploit Chunks Color", new Color(0, 80, 255));
	
	private final ColorSetting oldChunksColor =
		new ColorSetting("Old Chunks Color", new Color(0, 255, 0));
	
	private final Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();
	private final Set<ChunkPos> oldChunks = ConcurrentHashMap.newKeySet();
	private final Set<ChunkPos> tickExploitChunks =
		ConcurrentHashMap.newKeySet();
	
	private final Set<Path> filePaths = Set.of(Paths.NewChunkData,
		Paths.OldChunkData, Paths.BlockExploitChunkData);
	
	private String serverKey = "unknown";
	private String dimensionKey = "unknown";
	private boolean loadedThisSession;
	private int autoReloadTicks;
	
	private boolean ringPending;
	private int ticksUntilRing;
	private int ringsLeft;
	
	private int deleteWarningTicks = 200;
	private int deleteWarningPresses;
	
	private static final class Paths
	{
		private static final Path NewChunkData = Path.of("NewChunkData.txt");
		private static final Path OldChunkData = Path.of("OldChunkData.txt");
		private static final Path BlockExploitChunkData =
			Path.of("BlockExploitChunkData.txt");
	}
	
	public NewerNewChunksHack()
	{
		super("NewerNewChunks");
		setCategory(Category.RENDER);
		
		addSetting(detectMode);
		addSetting(liquidExploit);
		addSetting(blockUpdateExploit);
		addSetting(removeOnDisable);
		addSetting(removeOutsideRenderDistance);
		addSetting(saveChunkData);
		addSetting(loadChunkData);
		addSetting(autoReloadChunks);
		addSetting(autoReloadDelaySeconds);
		addSetting(alarms);
		addSetting(amountOfRings);
		addSetting(ringDelay);
		addSetting(volume);
		addSetting(pitch);
		addSetting(deleteChunkData);
		addSetting(renderDistance);
		addSetting(renderHeight);
		addSetting(newChunksColor);
		addSetting(blockExploitChunksColor);
		addSetting(oldChunksColor);
	}
	
	@Override
	protected void onEnable()
	{
		resolveWorldKeys();
		ensureDataFiles();
		if(loadChunkData.isChecked())
			loadData();
		
		loadedThisSession = loadChunkData.isChecked();
		autoReloadTicks = 0;
		ringPending = false;
		ticksUntilRing = 0;
		ringsLeft = 0;
		deleteWarningTicks = 200;
		deleteWarningPresses = 0;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		if(removeOnDisable.isChecked())
			clearChunkData();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null)
			return;
		
		resolveWorldKeys();
		ensureDataFiles();
		
		if(deleteWarningTicks <= 100)
			deleteWarningTicks++;
		else
			deleteWarningPresses = 0;
		
		if(!loadedThisSession && loadChunkData.isChecked())
		{
			loadData();
			loadedThisSession = true;
		}
		
		if(autoReloadChunks.isChecked())
		{
			autoReloadTicks++;
			if(autoReloadTicks >= autoReloadDelaySeconds.getValueI() * 20)
			{
				clearChunkData();
				if(loadChunkData.isChecked())
					loadData();
				autoReloadTicks = 0;
			}
		}
		
		if(removeOutsideRenderDistance.isChecked())
			removeChunksOutsideRenderDistance();
		
		if(alarms.isChecked() && ringPending && ringsLeft > 0)
		{
			if(ticksUntilRing <= 0)
			{
				playAlarmSound();
				ringsLeft--;
				ticksUntilRing = ringDelay.getValueI();
				if(ringsLeft <= 0)
					ringPending = false;
			}else
				ticksUntilRing--;
		}
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.player == null)
			return;
		
		double y = renderHeight.getValue();
		double maxDist = renderDistance.getValue() * 16;
		BlockPos playerPos = MC.player.blockPosition();
		
		List<AABB> newBoxes = collectBoxes(newChunks, y, playerPos, maxDist);
		List<AABB> oldBoxes = collectBoxes(oldChunks, y, playerPos, maxDist);
		List<AABB> tickBoxes =
			collectBoxes(tickExploitChunks, y, playerPos, maxDist);
		
		if(!oldBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrices, oldBoxes,
				oldChunksColor.getColorI(70), true);
			RenderUtils.drawOutlinedBoxes(matrices, oldBoxes,
				oldChunksColor.getColorI(210), true);
		}
		
		if(!newBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrices, newBoxes,
				newChunksColor.getColorI(95), true);
			RenderUtils.drawOutlinedBoxes(matrices, newBoxes,
				newChunksColor.getColorI(220), true);
		}
		
		if(!tickBoxes.isEmpty())
		{
			int side;
			int line;
			DetectMode mode = detectMode.getSelected();
			if(!blockUpdateExploit.isChecked()
				|| mode == DetectMode.IgnoreBlockExploit)
			{
				side = oldChunksColor.getColorI(70);
				line = oldChunksColor.getColorI(210);
			}else if(mode == DetectMode.Normal)
			{
				side = newChunksColor.getColorI(95);
				line = newChunksColor.getColorI(220);
			}else
			{
				side = blockExploitChunksColor.getColorI(75);
				line = blockExploitChunksColor.getColorI(220);
			}
			RenderUtils.drawSolidBoxes(matrices, tickBoxes, side, true);
			RenderUtils.drawOutlinedBoxes(matrices, tickBoxes, line, true);
		}
	}
	
	public void afterLoadChunk(int x, int z)
	{
		if(!isEnabled() || MC.level == null)
			return;
		
		ChunkPos chunkPos = new ChunkPos(x, z);
		if(containsAny(chunkPos))
			return;
		
		LevelChunk chunk = MC.level.getChunk(x, z);
		if(chunk == null)
			return;
		
		boolean foundFlowingFluid = false;
		int minY = chunk.getMinY();
		int maxY = MC.level.getMaxY() + 1;
		
		for(int cx = 0; cx < 16; cx++)
			for(int y = minY; y < maxY; y++)
				for(int cz = 0; cz < 16; cz++)
				{
					FluidState fluid =
						chunk.getFluidState(new BlockPos(cx, y, cz));
					if(fluid.isEmpty() || fluid.isSource())
						continue;
					foundFlowingFluid = true;
					break;
				}
			
		if(foundFlowingFluid)
			markOld(chunkPos);
		else
			markNew(chunkPos);
	}
	
	public void afterUpdateBlock(BlockPos pos)
	{
		if(!isEnabled() || MC.level == null)
			return;
		
		ChunkPos chunkPos = new ChunkPos(pos);
		if(blockUpdateExploit.isChecked() && !containsAny(chunkPos))
			markTickExploit(chunkPos);
		
		if(!liquidExploit.isChecked() || containsFinalChunkType(chunkPos))
			return;
		
		BlockState state = MC.level.getBlockState(pos);
		FluidState fluid = state.getFluidState();
		if(fluid.isEmpty() || fluid.isSource())
			return;
		
		for(Direction dir : SEARCH_DIRS)
		{
			BlockPos neighbor = pos.relative(dir);
			FluidState neighborFluid =
				MC.level.getBlockState(neighbor).getFluidState();
			if(!neighborFluid.isEmpty() && neighborFluid.isSource())
			{
				tickExploitChunks.remove(chunkPos);
				markNew(chunkPos);
				return;
			}
		}
	}
	
	private boolean containsAny(ChunkPos chunkPos)
	{
		return newChunks.contains(chunkPos) || oldChunks.contains(chunkPos)
			|| tickExploitChunks.contains(chunkPos);
	}
	
	private boolean containsFinalChunkType(ChunkPos chunkPos)
	{
		return newChunks.contains(chunkPos) || oldChunks.contains(chunkPos);
	}
	
	private void markNew(ChunkPos chunkPos)
	{
		if(!newChunks.add(chunkPos))
			return;
		
		oldChunks.remove(chunkPos);
		if(saveChunkData.isChecked())
			saveChunk(Paths.NewChunkData, chunkPos);
		triggerAlarm();
	}
	
	private void markOld(ChunkPos chunkPos)
	{
		if(!oldChunks.add(chunkPos))
			return;
		
		newChunks.remove(chunkPos);
		tickExploitChunks.remove(chunkPos);
		if(saveChunkData.isChecked())
			saveChunk(Paths.OldChunkData, chunkPos);
	}
	
	private void markTickExploit(ChunkPos chunkPos)
	{
		if(!tickExploitChunks.add(chunkPos))
			return;
		
		if(saveChunkData.isChecked())
			saveChunk(Paths.BlockExploitChunkData, chunkPos);
	}
	
	private void triggerAlarm()
	{
		if(!alarms.isChecked())
			return;
		
		ringPending = true;
		ringsLeft = amountOfRings.getValueI();
		ticksUntilRing = 0;
	}
	
	private void playAlarmSound()
	{
		if(MC.level == null || MC.player == null)
			return;
		
		MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
			MC.player.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP,
			SoundSource.PLAYERS, volume.getValueF(), pitch.getValueF(), false);
	}
	
	private void clearChunkData()
	{
		newChunks.clear();
		oldChunks.clear();
		tickExploitChunks.clear();
	}
	
	private List<AABB> collectBoxes(Set<ChunkPos> chunks, double y,
		BlockPos playerPos, double maxDist)
	{
		var boxes = new java.util.ArrayList<AABB>();
		for(ChunkPos chunk : chunks)
		{
			double centerX = chunk.getMiddleBlockX();
			double centerZ = chunk.getMiddleBlockZ();
			if(playerPos.closerThan(
				new BlockPos((int)centerX, playerPos.getY(), (int)centerZ),
				maxDist))
			{
				double minX = chunk.getMinBlockX();
				double minZ = chunk.getMinBlockZ();
				boxes.add(new AABB(minX, y, minZ, minX + 16, y + 1, minZ + 16));
			}
		}
		return boxes;
	}
	
	private void removeChunksOutsideRenderDistance()
	{
		if(MC.player == null)
			return;
		
		double maxDist = renderDistance.getValue() * 16;
		BlockPos playerPos = MC.player.blockPosition();
		removeOutside(newChunks, playerPos, maxDist);
		removeOutside(oldChunks, playerPos, maxDist);
		removeOutside(tickExploitChunks, playerPos, maxDist);
	}
	
	private void removeOutside(Set<ChunkPos> chunks, BlockPos playerPos,
		double maxDist)
	{
		chunks.removeIf(
			chunk -> !playerPos.closerThan(new BlockPos(chunk.getMiddleBlockX(),
				playerPos.getY(), chunk.getMiddleBlockZ()), maxDist));
	}
	
	private void resolveWorldKeys()
	{
		if(MC.level != null)
			dimensionKey =
				MC.level.dimension().identifier().toString().replace(':', '_');
		
		ServerData server = MC.getCurrentServer();
		if(server != null && server.ip != null && !server.ip.isBlank())
			serverKey = server.ip.replace(':', '_');
		else if(MC.hasSingleplayerServer())
			serverKey = "singleplayer";
		else
			serverKey = "unknown";
	}
	
	private Path getBaseDir()
	{
		return WURST.getWurstFolder().resolve("TrouserStreak")
			.resolve("NewChunks").resolve(serverKey).resolve(dimensionKey);
	}
	
	private void ensureDataFiles()
	{
		if(!saveChunkData.isChecked() && !loadChunkData.isChecked())
			return;
		
		Path baseDir = getBaseDir();
		try
		{
			Files.createDirectories(baseDir);
			for(Path fileName : filePaths)
			{
				Path file = baseDir.resolve(fileName);
				if(Files.notExists(file))
					Files.createFile(file);
			}
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void loadData()
	{
		loadChunkData(Paths.NewChunkData, newChunks);
		loadChunkData(Paths.OldChunkData, oldChunks);
		loadChunkData(Paths.BlockExploitChunkData, tickExploitChunks);
	}
	
	private void loadChunkData(Path fileName, Set<ChunkPos> target)
	{
		Path path = getBaseDir().resolve(fileName);
		if(Files.notExists(path))
			return;
		
		try
		{
			for(String line : Files.readAllLines(path))
			{
				if(line == null || line.isBlank())
					continue;
				String trimmed = line.replace("[", "").replace("]", "");
				String[] parts = trimmed.split(", ");
				if(parts.length != 2)
					continue;
				int x = Integer.parseInt(parts[0]);
				int z = Integer.parseInt(parts[1]);
				target.add(new ChunkPos(x, z));
			}
		}catch(IOException | NumberFormatException e)
		{
			e.printStackTrace();
		}
	}
	
	private void saveChunk(Path fileName, ChunkPos chunkPos)
	{
		Path file = getBaseDir().resolve(fileName);
		String line = chunkPos + System.lineSeparator();
		try
		{
			Files.createDirectories(file.getParent());
			Files.write(file, line.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void confirmDeleteChunkData()
	{
		if(deleteWarningPresses == 0)
			ChatUtils.message(
				"[NewerNewChunks] Press Delete Chunk Data again within 5s to confirm.");
		
		deleteWarningTicks = 0;
		deleteWarningPresses++;
		if(deleteWarningPresses < 2)
			return;
		
		deleteWarningPresses = 0;
		clearChunkData();
		Path baseDir = getBaseDir();
		for(Path file : filePaths)
		{
			try
			{
				Files.deleteIfExists(baseDir.resolve(file));
			}catch(IOException e)
			{
				e.printStackTrace();
			}
		}
		ChatUtils
			.message("[NewerNewChunks] Chunk data deleted for this dimension.");
	}
}
