/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Arrays;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
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
	private enum SearchMode
	{
		LIST,
		BLOCK_ID,
		QUERY
	}
	
	private static final int MAX_QUERY_LENGTH = 256;
	private final net.wurstclient.settings.EnumSetting<SearchMode> mode =
		new net.wurstclient.settings.EnumSetting<>("Mode", SearchMode.values(),
			SearchMode.BLOCK_ID);
	private final net.wurstclient.settings.BlockListSetting blockList =
		new net.wurstclient.settings.BlockListSetting("Block List",
			"Blocks to search when Mode is set to List.");
	private final TextFieldSetting query = new TextFieldSetting("Query",
		"Enter text to match block IDs or names by keyword. Separate multiple terms with commas.",
		"", value -> value.length() <= MAX_QUERY_LENGTH);
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
	// New: optionally show detected count in HackList
	private final net.wurstclient.settings.CheckboxSetting showCountInHackList =
		new net.wurstclient.settings.CheckboxSetting("HackList count",
			"Appends the number of found blocks to this hack's entry in the HackList.",
			false);
	
	// Above-ground filter
	private final net.wurstclient.settings.CheckboxSetting onlyAboveGround =
		new net.wurstclient.settings.CheckboxSetting("Above ground only",
			"Only show blocks at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private Block lastBlock;
	private String lastQuery = "";
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.",
		ChunkAreaSetting.ChunkArea.A33);
	
	private final SliderSetting limit = new SliderSetting("Limit",
		"The maximum number of blocks to display.\n"
			+ "Higher values require a faster computer.",
		4, 2, 6, 1, ValueDisplay.LOGARITHMIC);
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
	private java.util.List<net.minecraft.world.phys.Vec3> tracerEnds;
	private ChunkPos lastPlayerChunk;
	private int lastMatchesVersion;
	
	private SearchMode lastMode;
	private int lastListHash;
	// Cache for LIST mode: exact IDs and keyword terms
	private java.util.Set<String> listExactIds;
	private String[] listKeywords;
	
	private int foundCount; // number of currently displayed matches (clamped)
	
	public SearchHack()
	{
		super("Search");
		setCategory(Category.RENDER);
		addSetting(mode);
		addSetting(blockList);
		addSetting(query);
		addSetting(block);
		addSetting(style);
		addSetting(stickyArea);
		addSetting(useFixedColor);
		addSetting(fixedColor);
		addSetting(area);
		addSetting(limit);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		// new setting
		addSetting(showCountInHackList);
	}
	
	@Override
	public String getRenderName()
	{
		String colorMode = useFixedColor.isChecked() ? "Fixed" : "Rainbow";
		String base;
		switch(mode.getSelected())
		{
			case LIST:
			base = getName() + " [List:" + blockList.size() + "] (" + colorMode
				+ ")";
			break;
			case QUERY:
			String rawQuery = query.getValue().trim();
			if(!rawQuery.isEmpty())
				base = getName() + " [" + abbreviate(rawQuery) + "] ("
					+ colorMode + ")";
			else
				base = getName() + " [query] (" + colorMode + ")";
			break;
			case BLOCK_ID:
			default:
			base = getName() + " ["
				+ block.getBlockName().replace("minecraft:", "") + "] ("
				+ colorMode + ")";
		}
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + Math.min(foundCount, 999) + "]";
		return base;
	}
	
	@Override
	protected void onEnable()
	{
		prevLimit = limit.getValueI();
		notify = true;
		forkJoinPool = new ForkJoinPool();
		bufferUpToDate = false;
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.blockPosition());
		lastMode = mode.getSelected();
		lastListHash = blockList.getBlockNames().hashCode();
		applySearchCriteria(block.getBlock(), "");
		lastMatchesVersion = coordinator.getMatchesVersion();
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
		stopBuildingBuffer(true);
		coordinator.reset();
		forkJoinPool.shutdownNow();
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = null;
		bufferRegion = null;
		lastMatchingBlocks = null;
		tracerEnds = null;
		lastPlayerChunk = null;
		foundCount = 0; // reset count
	}
	
	@Override
	public void onUpdate()
	{
		SearchMode currentMode = mode.getSelected();
		
		// Mode/list changes
		if(currentMode != lastMode)
		{
			lastMode = currentMode;
			applySearchCriteria(block.getBlock(), "");
		}
		if(currentMode == SearchMode.LIST)
		{
			int listHash = blockList.getBlockNames().hashCode();
			if(listHash != lastListHash)
			{
				lastListHash = listHash;
				applySearchCriteria(block.getBlock(), "");
			}
		}
		
		// Recenter per chunk when sticky is off
		ChunkPos currentChunk = new ChunkPos(MC.player.blockPosition());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			stopBuildingBuffer(false);
		}
		
		// Area changes
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			stopBuildingBuffer(true);
			notify = true;
		}
		
		// Criteria changes only for modes that use them
		if(currentMode == SearchMode.BLOCK_ID)
		{
			Block currentBlock = block.getBlock();
			if(currentBlock != lastBlock)
				applySearchCriteria(currentBlock, "");
		}else if(currentMode == SearchMode.QUERY)
		{
			String currentQuery = normalizeQuery(query.getValue());
			if(!currentQuery.equals(lastQuery))
				applySearchCriteria(block.getBlock(), currentQuery);
		}
		
		// Update coordinator (adds/removes searchers, applies packet updates)
		coordinator.update();
		
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion != lastMatchesVersion)
		{
			lastMatchesVersion = matchesVersion;
			stopBuildingBuffer(false);
		}
		
		if(limit.getValueI() != prevLimit)
		{
			stopBuildingBuffer(false);
			prevLimit = limit.getValueI();
			notify = true;
		}
		
		if(!coordinator.hasReadyMatches())
			return;
		
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
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		boolean drawBoxes =
			style.hasBoxes() && vertexBuffer != null && bufferRegion != null;
		boolean drawTracers =
			style.hasLines() && tracerEnds != null && !tracerEnds.isEmpty();
		
		if(!drawBoxes && !drawTracers)
			return;
		
		float[] rgb = useFixedColor.isChecked() ? fixedColor.getColorF()
			: RenderUtils.getRainbowColor();
		
		if(drawBoxes)
		{
			matrixStack.pushPose();
			RenderUtils.applyRegionalRenderOffset(matrixStack, bufferRegion);
			vertexBuffer.draw(matrixStack, WurstRenderLayers.ESP_QUADS, rgb,
				0.5F);
			matrixStack.popPose();
		}
		
		if(drawTracers)
		{
			int tracerColor = RenderUtils.toIntColor(rgb, 0.5F);
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				tracerColor, false);
		}
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
		stopBuildingBuffer(true);
		
		switch(mode.getSelected())
		{
			case LIST:
			lastBlock = currentBlock;
			// Build caches: exact IDs vs keyword terms
			java.util.List<String> names = blockList.getBlockNames();
			java.util.ArrayList<String> kw = new java.util.ArrayList<>();
			java.util.HashSet<String> exact = new java.util.HashSet<>();
			for(String s : names)
			{
				if(s == null)
					continue;
				String raw = s.trim();
				if(raw.isEmpty())
					continue;
				ResourceLocation id = ResourceLocation.tryParse(raw);
				if(id != null && BuiltInRegistries.BLOCK.containsKey(id))
					exact.add(id.toString());
				else
					kw.add(raw.toLowerCase(Locale.ROOT));
			}
			listExactIds = exact;
			listKeywords = kw.toArray(new String[0]);
			coordinator.setQuery((pos, state) -> {
				if(onlyAboveGround.isChecked()
					&& pos.getY() < aboveGroundY.getValue())
					return false;
				String idFull = BlockUtils.getName(state.getBlock());
				if(listExactIds.contains(idFull))
					return true;
				String localId = idFull.contains(":")
					? idFull.substring(idFull.indexOf(":") + 1) : idFull;
				String localSpaced = localId.replace('_', ' ');
				String transKey = state.getBlock().getDescriptionId();
				String display = state.getBlock().getName().getString();
				for(String term : listKeywords)
				{
					if(containsNormalized(idFull, term)
						|| containsNormalized(localId, term)
						|| containsNormalized(localSpaced, term)
						|| containsNormalized(transKey, term)
						|| containsNormalized(display, term))
						return true;
				}
				return false;
			});
			lastQuery = "";
			break;
			case QUERY:
			lastBlock = currentBlock;
			coordinator.setQuery((pos, state) -> {
				if(onlyAboveGround.isChecked()
					&& pos.getY() < aboveGroundY.getValue())
					return false;
				return blockMatchesQuery(state.getBlock(), normalizedQuery);
			});
			lastQuery = normalizedQuery;
			break;
			case BLOCK_ID:
			default:
			lastBlock = currentBlock;
			coordinator.setQuery((pos, state) -> {
				if(onlyAboveGround.isChecked()
					&& pos.getY() < aboveGroundY.getValue())
					return false;
				return state.getBlock() == currentBlock;
			});
			lastQuery = "";
		}
		notify = true;
		lastMatchesVersion = coordinator.getMatchesVersion();
	}
	
	private boolean blockMatchesQuery(Block block, String normalizedQuery)
	{
		String id = BlockUtils.getName(block);
		String localId =
			id.contains(":") ? id.substring(id.indexOf(":") + 1) : id;
		// Support multiple comma-separated terms; match if ANY term matches
		String[] terms = Arrays.stream(normalizedQuery.split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
		if(terms.length == 0)
			terms = new String[]{normalizedQuery};
		String localSpaced = localId.replace('_', ' ');
		String transKey = block.getDescriptionId();
		String display = block.getName().getString();
		for(String term : terms)
			if(containsNormalized(id, term) || containsNormalized(localId, term)
				|| containsNormalized(localSpaced, term)
				|| containsNormalized(transKey, term)
				|| containsNormalized(display, term))
				return true;
		return false;
	}
	
	private boolean containsNormalized(String haystack, String normalizedQuery)
	{
		return haystack != null
			&& haystack.toLowerCase(Locale.ROOT).contains(normalizedQuery);
	}
	
	private void startGetMatchingBlocksTask()
	{
		// Use a bounded max-heap to keep only the closest N matches without
		// sorting the entire result set. This avoids long pauses when scanning
		// very large areas.
		BlockPos eyesPos = BlockPos.containing(RotationUtils.getEyesPos());
		final int limitCount = limit.getValueLog();
		getMatchingBlocksTask = forkJoinPool.submit(() -> {
			PriorityQueue<BlockPos> heap = new PriorityQueue<>((limitCount + 1),
				(a, b) -> Integer.compare(b.distManhattan(eyesPos),
					a.distManhattan(eyesPos)));
			java.util.Iterator<ChunkSearcher.Result> it =
				coordinator.getReadyMatches().iterator();
			while(it.hasNext())
			{
				ChunkSearcher.Result r = it.next();
				BlockPos pos = r.pos();
				if(heap.size() < limitCount)
					heap.offer(pos);
				else if(pos.distManhattan(eyesPos) < heap.peek()
					.distManhattan(eyesPos))
				{
					heap.poll();
					heap.offer(pos);
				}
			}
			return new HashSet<>(heap);
		});
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
		vertexBuffer = EasyVertexBuffer.createAndUpload(Mode.QUADS,
			DefaultVertexFormat.POSITION_COLOR, buffer -> {
				for(int[] vertex : vertices)
					buffer.addVertex(vertex[0] - region.x(), vertex[1],
						vertex[2] - region.z()).setColor(0xFFFFFFFF);
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
				return pos.getCenter();
			}).collect(java.util.stream.Collectors.toList());
			// update count for HUD (clamped to 999)
			foundCount = Math.min(lastMatchingBlocks.size(), 999);
		}else
		{
			foundCount = 0;
		}
	}
	
	private void stopBuildingBuffer(boolean discardCurrent)
	{
		if(getMatchingBlocksTask != null)
			getMatchingBlocksTask.cancel(true);
		getMatchingBlocksTask = null;
		if(compileVerticesTask != null)
			compileVerticesTask.cancel(true);
		compileVerticesTask = null;
		bufferUpToDate = false;
		if(discardCurrent)
		{
			tracerEnds = null;
			lastMatchingBlocks = null;
			foundCount = 0;
			if(vertexBuffer != null)
			{
				vertexBuffer.close();
				vertexBuffer = null;
			}
			bufferRegion = null;
		}
	}
}
