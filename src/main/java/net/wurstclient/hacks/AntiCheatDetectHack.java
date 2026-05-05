/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.Category;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.ServerObserver;

public final class AntiCheatDetectHack extends Hack
	implements PacketInputListener, UpdateListener
{
	private static final long GLOBAL_BLOCK_BURST_WINDOW_MS = 2000L;
	private static final long GLOBAL_BLOCK_FLIP_WINDOW_MS = 1500L;
	private static final long GLOBAL_BLOCK_HISTORY_TTL_MS = 10000L;
	private static final long GLOBAL_BURST_REARM_MS = 30000L;
	private static final long GLOBAL_BE_BURST_WINDOW_MS = 2000L;
	private static final int GLOBAL_BE_BURST_THRESHOLD = 40;
	private static final long GLOBAL_BLOCK_UPDATE_GRACE_MS = 1200L;
	private String lastAnnounced;
	private long lastAnnouncedMs;
	private static final long ANNOUNCE_COOLDOWN_MS = 3000L;
	private final CheckboxSetting setbackDetection = new CheckboxSetting(
		"SetbackDetection",
		"Disables packet-based hacks when the server sends a setback.", true);
	private final CheckboxSetting suppressUnknown =
		new CheckboxSetting("Suppress unknown alerts",
			"description.wurst.setting.anticheatdetect.suppress_unknown", true);
	private final CheckboxSetting detectGlobalAntiEsp = new CheckboxSetting(
		"Detect global anti-esp",
		"Detect suspicious anti-ESP style block-update patterns across all block changes.",
		false);
	private final CheckboxSetting globalAntiEspAlerts = new CheckboxSetting(
		"Global anti-esp alerts",
		"Show chat warnings when global anti-ESP patterns are detected.", true);
	private final SliderSetting globalBlockBurstThreshold =
		new SliderSetting("Global block burst threshold",
			"Warn if this many block changes arrive within 2 seconds.", 400, 50,
			5000, 10, ValueDisplay.INTEGER);
	private final ArrayDeque<BlockChangeEvent> globalBlockEvents =
		new ArrayDeque<>();
	private final HashMap<Long, Integer> globalWindowBlockCounts =
		new HashMap<>();
	private final ArrayDeque<Long> globalBePacketTimes = new ArrayDeque<>();
	private final HashMap<Long, BlockChangeSnapshot> globalLastBlockChanges =
		new HashMap<>();
	private final HashMap<Long, Long> globalRecentBlockUpdates =
		new HashMap<>();
	private final HashMap<String, Long> globalAntiEspCooldowns =
		new HashMap<>();
	private final Object globalAntiEspLock = new Object();
	private boolean globalAntiEspSuspicious;
	private int globalAntiEspSignals;
	private long lastGlobalBurstAlertMs;
	private int lastGlobalBurstAlertCount;
	private int lastGlobalBurstAlertUnique;
	
	public AntiCheatDetectHack()
	{
		super("AntiCheatDetect");
		setCategory(Category.TOOLS);
		addSetting(setbackDetection);
		addSetting(suppressUnknown);
		addSetting(detectGlobalAntiEsp);
		addSetting(globalAntiEspAlerts);
		addSetting(globalBlockBurstThreshold);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getServerObserver().requestCaptureIfNeeded();
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		resetGlobalAntiEspState();
		alertAboutAntiCheat();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		resetGlobalAntiEspState();
	}
	
	@Override
	public void onUpdate()
	{
		if(detectGlobalAntiEsp.isChecked())
			pruneGlobalAntiEspState();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!detectGlobalAntiEsp.isChecked())
			return;
		
		Packet<?> packet = event.getPacket();
		if(packet instanceof ClientboundBlockUpdatePacket blockUpdate)
		{
			recordGlobalBlockChange(blockUpdate.getPos(),
				blockUpdate.getBlockState());
			return;
		}
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket deltaUpdate)
		{
			deltaUpdate.runUpdates(this::recordGlobalBlockChange);
			return;
		}
		
		if(packet instanceof ClientboundBlockEntityDataPacket bePacket)
			recordGlobalBlockEntityPacket(bePacket);
	}
	
	public void completed()
	{
		if(isEnabled())
			alertAboutAntiCheat();
	}
	
	private void alertAboutAntiCheat()
	{
		ServerObserver observer = WURST.getServerObserver();
		String antiCheat = observer.guessAntiCheat(observer.getServerAddress());
		
		if(antiCheat == null)
			return;
		
		if("Unknown".equalsIgnoreCase(antiCheat) && suppressUnknown.isChecked())
			return;
		
		long now = System.currentTimeMillis();
		if(antiCheat.equalsIgnoreCase(lastAnnounced)
			&& now - lastAnnouncedMs < ANNOUNCE_COOLDOWN_MS)
			return;
		
		lastAnnounced = antiCheat;
		lastAnnouncedMs = now;
		MC.execute(() -> ChatUtils.message(WURST
			.translate("message.wurst.anticheatdetect.detected", antiCheat)));
	}
	
	public boolean isSetbackDetectionEnabled()
	{
		return setbackDetection.isChecked();
	}
	
	@Override
	public String getRenderName()
	{
		return super.getRenderName();
	}
	
	private void recordGlobalBlockChange(BlockPos pos, BlockState state)
	{
		if(pos == null || state == null)
			return;
		
		long now = System.currentTimeMillis();
		String blockId =
			BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		boolean currentRelevant = isRelevantBlockState(state, blockId);
		
		synchronized(globalAntiEspLock)
		{
			long key = pos.asLong();
			globalRecentBlockUpdates.put(key, now);
			BlockChangeSnapshot previous = globalLastBlockChanges.put(key,
				new BlockChangeSnapshot(blockId, now, currentRelevant));
			
			boolean idChanged =
				previous != null && !previous.blockId().equals(blockId);
			boolean significantTransition = idChanged && currentRelevant
				&& !isSuppressedNaturalTransition(previous.blockId(), blockId);
			
			if(significantTransition)
			{
				globalBlockEvents.addLast(new BlockChangeEvent(key, now));
				globalWindowBlockCounts.merge(key, 1, Integer::sum);
				pruneBlockWindowLocked(now);
				
				int burstCount = globalBlockEvents.size();
				int uniqueBlocks = globalWindowBlockCounts.size();
				int threshold = globalBlockBurstThreshold.getValueI();
				int uniqueMin = Math.max(24, (int)Math.round(threshold * 0.35));
				if(burstCount >= threshold && uniqueBlocks >= uniqueMin
					&& shouldEmitGlobalBurstLocked(now, burstCount,
						uniqueBlocks))
					flagGlobalAntiEspLocked("global-burst",
						"Observed " + burstCount + " block changes across "
							+ uniqueBlocks + " unique blocks in 2s.");
			}
			
			if(previous != null && previous.relevant() && significantTransition
				&& now - previous.timestamp() <= GLOBAL_BLOCK_FLIP_WINDOW_MS)
				flagGlobalAntiEspLocked("flip-flop",
					"Rapid block swap at " + pos.getX() + ", " + pos.getY()
						+ ", " + pos.getZ() + " (" + previous.blockId() + " -> "
						+ blockId + ")");
		}
	}
	
	private void flagGlobalAntiEspLocked(String key, String message)
	{
		long now = System.currentTimeMillis();
		Long cooldown = globalAntiEspCooldowns.get(key);
		if(cooldown != null && now - cooldown < 8000L)
			return;
		
		globalAntiEspCooldowns.put(key, now);
		globalAntiEspSuspicious = true;
		globalAntiEspSignals = Math.min(globalAntiEspSignals + 1, 999);
		if(globalAntiEspAlerts.isChecked())
			ChatUtils.warning("AntiCheatDetect Global Anti-ESP: " + message);
	}
	
	private void pruneGlobalAntiEspState()
	{
		long now = System.currentTimeMillis();
		synchronized(globalAntiEspLock)
		{
			pruneBlockWindowLocked(now);
			while(!globalBePacketTimes.isEmpty() && now
				- globalBePacketTimes.peekFirst() > GLOBAL_BE_BURST_WINDOW_MS)
				globalBePacketTimes.removeFirst();
			globalRecentBlockUpdates.entrySet().removeIf(
				e -> now - e.getValue() > GLOBAL_BLOCK_HISTORY_TTL_MS);
			globalLastBlockChanges.entrySet().removeIf(e -> now
				- e.getValue().timestamp() > GLOBAL_BLOCK_HISTORY_TTL_MS);
		}
	}
	
	private void resetGlobalAntiEspState()
	{
		synchronized(globalAntiEspLock)
		{
			globalBlockEvents.clear();
			globalWindowBlockCounts.clear();
			globalBePacketTimes.clear();
			globalLastBlockChanges.clear();
			globalRecentBlockUpdates.clear();
			globalAntiEspCooldowns.clear();
			globalAntiEspSuspicious = false;
			globalAntiEspSignals = 0;
			lastGlobalBurstAlertMs = 0L;
			lastGlobalBurstAlertCount = 0;
			lastGlobalBurstAlertUnique = 0;
		}
	}
	
	private void recordGlobalBlockEntityPacket(
		ClientboundBlockEntityDataPacket packet)
	{
		BlockPos pos = packet.getPos();
		if(pos == null)
			return;
		
		long now = System.currentTimeMillis();
		synchronized(globalAntiEspLock)
		{
			globalBePacketTimes.addLast(now);
			while(!globalBePacketTimes.isEmpty() && now
				- globalBePacketTimes.peekFirst() > GLOBAL_BE_BURST_WINDOW_MS)
				globalBePacketTimes.removeFirst();
			
			if(globalBePacketTimes.size() >= GLOBAL_BE_BURST_THRESHOLD)
				flagGlobalAntiEspLocked("be-burst",
					"Received " + globalBePacketTimes.size()
						+ " block-entity packets in 2s.");
			
			Long lastUpdate = globalRecentBlockUpdates.get(pos.asLong());
			if(lastUpdate == null
				|| now - lastUpdate > GLOBAL_BLOCK_UPDATE_GRACE_MS)
				flagGlobalAntiEspLocked("late-be",
					"Block entity at " + pos.getX() + ", " + pos.getY() + ", "
						+ pos.getZ()
						+ " arrived without a recent block update.");
		}
	}
	
	private boolean isRelevantBlockState(BlockState state, String blockId)
	{
		if(state == null || blockId == null)
			return false;
		
		if(state.isAir())
			return false;
		
		if(!state.getFluidState().isEmpty())
			return false;
		
		return !"minecraft:water".equals(blockId)
			&& !"minecraft:lava".equals(blockId);
	}
	
	private boolean isSuppressedNaturalTransition(String oldId, String newId)
	{
		if(oldId == null || newId == null || oldId.equals(newId))
			return true;
		
		// Fire spread and burning are common natural updates.
		if(isFireId(oldId) || isFireId(newId))
			return true;
		
		if(isAirOrFluidId(oldId) || isAirOrFluidId(newId))
			return true;
		
		if(isSoilSurfaceId(oldId) && isSoilSurfaceId(newId))
			return true;
		
		if(isNyliumFamilyId(oldId) && isNyliumFamilyId(newId))
			return true;
		
		// Common growth transitions (plant vs plant-body blocks)
		if((oldId.endsWith("_vines") && newId.endsWith("_vines_plant"))
			|| (oldId.endsWith("_vines_plant") && newId.endsWith("_vines")))
			return true;
		
		if((oldId.endsWith("kelp") && newId.endsWith("kelp_plant"))
			|| (oldId.endsWith("kelp_plant") && newId.endsWith("kelp")))
			return true;
		
		return false;
	}
	
	private boolean isFireId(String id)
	{
		return "minecraft:fire".equals(id) || "minecraft:soul_fire".equals(id);
	}
	
	private boolean isAirOrFluidId(String id)
	{
		return "minecraft:air".equals(id) || "minecraft:cave_air".equals(id)
			|| "minecraft:void_air".equals(id) || "minecraft:water".equals(id)
			|| "minecraft:lava".equals(id);
	}
	
	private boolean isSoilSurfaceId(String id)
	{
		return "minecraft:dirt".equals(id) || "minecraft:grass_block".equals(id)
			|| "minecraft:coarse_dirt".equals(id)
			|| "minecraft:podzol".equals(id) || "minecraft:mycelium".equals(id)
			|| "minecraft:rooted_dirt".equals(id)
			|| "minecraft:farmland".equals(id)
			|| "minecraft:dirt_path".equals(id) || "minecraft:mud".equals(id);
	}
	
	private boolean isNyliumFamilyId(String id)
	{
		return "minecraft:netherrack".equals(id)
			|| "minecraft:crimson_nylium".equals(id)
			|| "minecraft:warped_nylium".equals(id);
	}
	
	private void pruneBlockWindowLocked(long now)
	{
		while(!globalBlockEvents.isEmpty() && now - globalBlockEvents
			.peekFirst().timestamp() > GLOBAL_BLOCK_BURST_WINDOW_MS)
		{
			BlockChangeEvent ev = globalBlockEvents.removeFirst();
			globalWindowBlockCounts.computeIfPresent(ev.posKey(), (k, v) -> {
				int nv = v - 1;
				return nv <= 0 ? null : nv;
			});
		}
	}
	
	private boolean shouldEmitGlobalBurstLocked(long now, int burstCount,
		int uniqueBlocks)
	{
		if(lastGlobalBurstAlertMs == 0L
			|| now - lastGlobalBurstAlertMs >= GLOBAL_BURST_REARM_MS)
		{
			lastGlobalBurstAlertMs = now;
			lastGlobalBurstAlertCount = burstCount;
			lastGlobalBurstAlertUnique = uniqueBlocks;
			return true;
		}
		
		int deltaCount = Math.abs(burstCount - lastGlobalBurstAlertCount);
		int deltaUnique = Math.abs(uniqueBlocks - lastGlobalBurstAlertUnique);
		boolean significantDelta = deltaCount >= 50 || deltaUnique >= 25;
		if(significantDelta)
		{
			lastGlobalBurstAlertMs = now;
			lastGlobalBurstAlertCount = burstCount;
			lastGlobalBurstAlertUnique = uniqueBlocks;
			return true;
		}
		
		return false;
	}
	
	private record BlockChangeSnapshot(String blockId, long timestamp,
		boolean relevant)
	{}
	
	private record BlockChangeEvent(long posKey, long timestamp)
	{}
}
