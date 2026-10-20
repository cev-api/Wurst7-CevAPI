/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

import java.util.ArrayList;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.util.chunk.ChunkSearcher.Result;

public final class ChunkSearcherCoordinator extends AbstractChunkCoordinator
{
	private BooleanSupplier activityCondition = () -> true;
	private IntSupplier searchVersion = () -> 0;
	private final ScanActivity activity = new ScanActivity();
	
	public void setActivityCondition(BooleanSupplier condition)
	{
		activityCondition = java.util.Objects.requireNonNull(condition);
	}
	
	public void setSearchVersionSupplier(IntSupplier version)
	{
		searchVersion = java.util.Objects.requireNonNull(version);
	}
	
	public boolean isActive()
	{
		return WurstClient.MC.level != null && WurstClient.MC.player != null
			&& activityCondition.getAsBoolean();
	}
	
	public boolean isCurrentWorld()
	{
		return isActive() && activity.isCurrent(WurstClient.MC.level);
	}
	
	public void suspend()
	{
		activity.suspend(this::reset);
	}
	
	@Override
	public boolean update()
	{
		if(!isActive())
			return activity.suspend(this::reset);
		boolean changed = activity.resume(WurstClient.MC.level,
			searchVersion.getAsInt(), this::reset);
		return super.update() || changed;
	}
	
	public ChunkSearcherCoordinator(ChunkAreaSetting area)
	{
		this((pos, state) -> false, area);
	}
	
	public ChunkSearcherCoordinator(BiPredicate<BlockPos, BlockState> query,
		ChunkAreaSetting area)
	{
		super(query, area);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!isActive())
			return;
		
		Packet<?> packet = event.getPacket();
		
		if(packet instanceof ClientboundBlockUpdatePacket blockUpdate)
		{
			BlockPos pos = blockUpdate.getPos();
			enqueueBlockUpdate(ChunkPos.containing(pos), pos,
				blockUpdate.getBlockState());
			return;
		}
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket deltaUpdate)
		{
			ChunkPos chunkPos = deltaUpdate.sectionPos.chunk();
			ArrayList<ChunkSearcher.BlockUpdate> updates = new ArrayList<>();
			deltaUpdate.runUpdates((pos, state) -> updates
				.add(new ChunkSearcher.BlockUpdate(pos.immutable(), state)));
			enqueueBlockUpdates(chunkPos, updates);
			return;
		}
		
		ChunkPos chunkPos = ChunkUtils.getAffectedChunk(packet);
		
		if(chunkPos != null)
			chunksToUpdate.add(chunkPos);
	}
	
	public Stream<Result> getMatches()
	{
		return isCurrentWorld()
			? searchers.values().stream().flatMap(ChunkSearcher::getMatches)
			: Stream.empty();
	}
	
	public Stream<Result> getReadyMatches()
	{
		return isCurrentWorld() ? streamReadyMatches() : Stream.empty();
	}
}
