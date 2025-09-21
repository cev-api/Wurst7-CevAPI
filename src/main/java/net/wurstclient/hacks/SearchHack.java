/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.Collectors;

import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;

import net.minecraft.block.Block;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.BlockVertexCompiler;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.RegionPos;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.chunk.ChunkSearcher;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"BlockESP", "block esp"})
public final class SearchHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final TextFieldSetting query = new TextFieldSetting("Query",
		"Enter text to match block IDs or names. Leave empty to search for the selected block only.",
		"", value -> value.length() <= 64);
	private final BlockSetting block = new BlockSetting("Block",
		"The type of block to search for.", "minecraft:diamond_ore", false);
	// New: style setting for boxes/lines like ChestESP
	private final net.wurstclient.settings.EspStyleSetting style =
		new net.wurstclient.settings.EspStyleSetting();
	private final net.wurstclient.settings.CheckboxSetting stickyArea =
		new net.wurstclient.settings.CheckboxSetting("Sticky area",
			"Off: Re-centers the scan every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	private final net.wurstclient.settings.CheckboxSetting useFixedColor =
		new net.wurstclient.settings.CheckboxSetting("Use fixed color",
			"Enable to use a fixed color instead of rainbow.", false);
	private final net.wurstclient.settings.ColorSetting fixedColor =
		new net.wurstclient.settings.ColorSetting("Fixed color",
			"Color used when \"Use fixed color\" is enabled.", Color.RED);
	private Block lastBlock;
	private String lastQuery = "";
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	
	private final SliderSetting limit = new SliderSetting("Limit",
		"The maximum number of blocks to display.\n"
			+ "Higher values require a faster computer.",
		4, 3, 6, 1, ValueDisplay.LOGARITHMIC);
	private int prevLimit;
	private boolean notify;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(area);
	
	private ForkJoinPool forkJoinPool;
	private ForkJoinTask<HashSet<BlockPos>> getMatchingBlocksTask;
	private ForkJoinTask<ArrayList<int[]>> compileVerticesTask;
	
	// Keep a copy of matching positions for tracers
	private HashSet<BlockPos> lastMatchingBlocks;
	
	private EasyVertexBuffer vertexBuffer;
	private RegionPos bufferRegion;
	private boolean bufferUpToDate;
	// Precomputed tracer endpoints
	private java.util.List<net.minecraft.util.math.Vec3d> tracerEnds;
	private ChunkPos lastPlayerChunk;
	
	public SearchHack()
	{
		super("Search");
		setCategory(Category.RENDER);
		addSetting(query);
		addSetting(block);
		addSetting(style);
		addSetting(stickyArea);
		addSetting(useFixedColor);
		addSetting(fixedColor);
		addSetting(area);
		addSetting(limit);
	}
	
	@Override
	public String getRenderName()
	{
		String rawQuery = query.getValue().trim();
		String colorMode = useFixedColor.isChecked() ? "Fixed" : "Rainbow";
		if(!rawQuery.isEmpty())
			return getName() + " [" + abbreviate(rawQuery) + "] (" + colorMode
				+ ")";
		return getName() + " [" + block.getBlockName().replace("minecraft:", "")
			+ "] (" + colorMode + ")";
	}
	
	@Override
	protected void onEnable()
	{
		prevLimit = limit.getValueI();
		notify = true;
		forkJoinPool = new ForkJoinPool();
		bufferUpToDate = false;
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.getBlockPos());
		applySearchCriteria(block.getBlock(), normalizeQuery(query.getValue()));
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		stopBuildingBuffer();
		coordinator.reset();
		forkJoinPool.shutdownNow();
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = null;
		bufferRegion = null;
		lastMatchingBlocks = null;
		tracerEnds = null;
		lastPlayerChunk = null;
	}
	
	@Override
	public void onUpdate()
	{
		boolean searchersChanged = false;
		// Recenter per chunk when sticky is off
		ChunkPos currentChunk = new ChunkPos(MC.player.getBlockPos());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			stopBuildingBuffer();
			searchersChanged = true;
		}
		
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			searchersChanged = true;
		}
		
		Block currentBlock = block.getBlock();
		String currentQuery = normalizeQuery(query.getValue());
		if(shouldUpdateSearch(currentBlock, currentQuery))
		{
			applySearchCriteria(currentBlock, currentQuery);
			searchersChanged = true;
		}
		
		if(coordinator.update())
			searchersChanged = true;
		
		if(searchersChanged)
			stopBuildingBuffer();
		
		if(!coordinator.isDone())
			return;
		
		if(limit.getValueI() != prevLimit)
		{
			stopBuildingBuffer();
			prevLimit = limit.getValueI();
			notify = true;
		}
		
		if(getMatchingBlocksTask == null)
			startGetMatchingBlocksTask();
		
		if(!getMatchingBlocksTask.isDone())
			return;
		
		if(compileVerticesTask == null)
			startCompileVerticesTask();
		
		if(!compileVerticesTask.isDone())
			return;
		
		if(!bufferUpToDate)
			setBufferFromTask();
	}
	
	// New: cancel view bobbing if drawing lines
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(vertexBuffer == null || bufferRegion == null)
		{
			if(style.hasLines() && tracerEnds != null && !tracerEnds.isEmpty())
			{
				int color = useFixedColor.isChecked()
					? net.wurstclient.util.RenderUtils
						.toIntColor(fixedColor.getColorF(), 0.5F)
					: net.wurstclient.util.RenderUtils.toIntColor(
						net.wurstclient.util.RenderUtils.getRainbowColor(),
						0.5F);
				net.wurstclient.util.RenderUtils.drawTracers(matrixStack,
					partialTicks, tracerEnds, color, false);
			}
			return;
		}
		matrixStack.push();
		RenderUtils.applyRegionalRenderOffset(matrixStack, bufferRegion);
		float[] rgb = useFixedColor.isChecked() ? fixedColor.getColorF()
			: RenderUtils.getRainbowColor();
		vertexBuffer.draw(matrixStack, WurstRenderLayers.ESP_QUADS, rgb, 0.5F);
		matrixStack.pop();
		if(style.hasLines() && tracerEnds != null && !tracerEnds.isEmpty())
		{
			int color = useFixedColor.isChecked()
				? RenderUtils.toIntColor(fixedColor.getColorF(), 0.5F)
				: RenderUtils.toIntColor(RenderUtils.getRainbowColor(), 0.5F);
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				color, false);
		}
	}
	
	private boolean shouldUpdateSearch(Block currentBlock, String currentQuery)
	{
		if(!currentQuery.equals(lastQuery))
			return true;
		
		return currentQuery.isEmpty() && currentBlock != lastBlock;
	}
	
	private String normalizeQuery(String rawQuery)
	{
		if(rawQuery == null)
			return "";
		
		return rawQuery.trim().toLowerCase(Locale.ROOT);
	}
	
	private String abbreviate(String text)
	{
		if(text.length() <= 32)
			return text;
		
		return text.substring(0, 32) + "...";
	}
	
	private void applySearchCriteria(Block currentBlock, String normalizedQuery)
	{
		if(normalizedQuery.isEmpty())
		{
			lastBlock = currentBlock;
			coordinator.setTargetBlock(currentBlock);
		}else
		{
			lastBlock = currentBlock;
			coordinator.setQuery((pos,
				state) -> blockMatchesQuery(state.getBlock(), normalizedQuery));
		}
		
		lastQuery = normalizedQuery;
		notify = true;
	}
	
	private boolean blockMatchesQuery(Block block, String normalizedQuery)
	{
		String id = BlockUtils.getName(block);
		String localId =
			id.contains(":") ? id.substring(id.indexOf(":") + 1) : id;
		
		return containsNormalized(id, normalizedQuery)
			|| containsNormalized(localId, normalizedQuery)
			|| containsNormalized(localId.replace('_', ' '), normalizedQuery)
			|| containsNormalized(block.getTranslationKey(), normalizedQuery)
			|| containsNormalized(block.getName().getString(), normalizedQuery);
	}
	
	private boolean containsNormalized(String haystack, String normalizedQuery)
	{
		return haystack != null
			&& haystack.toLowerCase(Locale.ROOT).contains(normalizedQuery);
	}
	
	private void startGetMatchingBlocksTask()
	{
		BlockPos eyesPos = BlockPos.ofFloored(RotationUtils.getEyesPos());
		Comparator<BlockPos> comparator =
			Comparator.comparingInt(pos -> eyesPos.getManhattanDistance(pos));
		
		getMatchingBlocksTask = forkJoinPool.submit(() -> coordinator
			.getMatches().parallel().map(ChunkSearcher.Result::pos)
			.sorted(comparator).limit(limit.getValueLog())
			.collect(Collectors.toCollection(HashSet::new)));
	}
	
	private void startCompileVerticesTask()
	{
		HashSet<BlockPos> matchingBlocks = getMatchingBlocksTask.join();
		// store for tracers
		lastMatchingBlocks = matchingBlocks;
		
		if(matchingBlocks.size() < limit.getValueLog())
			notify = true;
		else if(notify)
		{
			ChatUtils.warning("Search found \u00a7lA LOT\u00a7r of blocks!"
				+ " To prevent lag, it will only show the closest \u00a76"
				+ limit.getValueString() + "\u00a7r results.");
			notify = false;
		}
		
		compileVerticesTask = forkJoinPool
			.submit(() -> BlockVertexCompiler.compile(matchingBlocks));
	}
	
	private void setBufferFromTask()
	{
		ArrayList<int[]> vertices = compileVerticesTask.join();
		RegionPos region = RenderUtils.getCameraRegion();
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = EasyVertexBuffer.createAndUpload(DrawMode.QUADS,
			VertexFormats.POSITION_COLOR, buffer -> {
				for(int[] vertex : vertices)
					buffer.vertex(vertex[0] - region.x(), vertex[1],
						vertex[2] - region.z()).color(0xFFFFFFFF);
			});
		bufferUpToDate = true;
		bufferRegion = region;
		// build tracer endpoints now that we have matching blocks
		if(lastMatchingBlocks != null)
		{
			tracerEnds = lastMatchingBlocks.stream().map(pos -> {
				if(net.wurstclient.util.BlockUtils.canBeClicked(pos))
					return net.wurstclient.util.BlockUtils.getBoundingBox(pos)
						.getCenter();
				return pos.toCenterPos();
			}).collect(java.util.stream.Collectors.toList());
		}
	}
	
	private void stopBuildingBuffer()
	{
		if(getMatchingBlocksTask != null)
			getMatchingBlocksTask.cancel(true);
		getMatchingBlocksTask = null;
		if(compileVerticesTask != null)
			compileVerticesTask.cancel(true);
		compileVerticesTask = null;
		bufferUpToDate = false;
		tracerEnds = null;
	}
}
