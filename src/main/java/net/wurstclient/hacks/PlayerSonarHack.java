/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"player sonar", "far player activity", "activity radar"})
public final class PlayerSonarHack extends Hack
	implements PacketInputListener, UpdateListener, RenderListener
{
	private static final double PLAYER_ESP_LIMIT_BLOCKS = 128.0;
	private static final double PLAYER_ESP_LIMIT_SQ =
		PLAYER_ESP_LIMIT_BLOCKS * PLAYER_ESP_LIMIT_BLOCKS;
	private static final double CONFIRM_PLAYER_RADIUS_BLOCKS = 12.0;
	private static final double CONFIRM_PLAYER_RADIUS_SQ =
		CONFIRM_PLAYER_RADIUS_BLOCKS * CONFIRM_PLAYER_RADIUS_BLOCKS;
	private static final long RECENT_EVENT_WINDOW_MS = 2500L;
	private static final long STATE_CACHE_TTL_MS = 120000L;
	
	private final CheckboxSetting showBoxes = new CheckboxSetting("Show boxes",
		"Draw boxes around suspicious far activity spots.", true);
	private final CheckboxSetting onlyBeyondPlayerEspRange =
		new CheckboxSetting("Only beyond 128 blocks",
			"When enabled, PlayerSonar only detects activity outside the typical PlayerESP range.",
			true);
	private final CheckboxSetting showTracers = new CheckboxSetting(
		"Show tracers", "Draw tracers to suspicious far activity spots.", true);
	private final SliderSetting tracerThickness =
		new SliderSetting("Tracer thickness", 2, 0.5, 10, 0.1,
			ValueDisplay.DECIMAL.withSuffix("px"));
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final CheckboxSetting detectRedstoneInteraction =
		new CheckboxSetting("Detect redstone interaction",
			"Also score powered/open/triggered/lit toggles as weaker hints.",
			true);
	private final CheckboxSetting chatAlerts =
		new CheckboxSetting("Chat alerts",
			"Show chat alerts when a block-change signal is detected.", true);
	private final SliderSetting markerLifetimeSec = new SliderSetting(
		"Marker lifetime", "How long a sonar marker stays on screen.", 35, 5,
		180, 1, ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting maxMarkers = new SliderSetting("Max markers",
		"Maximum number of simultaneous sonar markers.", 80, 10, 300, 1,
		ValueDisplay.INTEGER);
	private final ColorSetting placeColor =
		new ColorSetting("Place color", new java.awt.Color(255, 80, 80));
	private final ColorSetting breakColor =
		new ColorSetting("Break color", new java.awt.Color(80, 200, 255));
	private final ColorSetting redstoneColor =
		new ColorSetting("Redstone color", new java.awt.Color(255, 200, 80));
	
	private final Map<Long, SonarPing> pings = new ConcurrentHashMap<>();
	private final Map<Long, CachedState> knownStates =
		new ConcurrentHashMap<>();
	private final ConcurrentLinkedDeque<RecentEvent> recentEvents =
		new ConcurrentLinkedDeque<>();
	private long lastDecayAt;
	
	public PlayerSonarHack()
	{
		super("PlayerSonar",
			"Detects likely player activity outside 128 blocks by scoring world-change packet patterns.",
			false);
		setCategory(Category.INTEL);
		addSetting(showBoxes);
		addSetting(onlyBeyondPlayerEspRange);
		addSetting(showTracers);
		addSetting(tracerThickness);
		addSetting(tracerFlash);
		addSetting(detectRedstoneInteraction);
		addSetting(chatAlerts);
		addSetting(markerLifetimeSec);
		addSetting(maxMarkers);
		addSetting(placeColor);
		addSetting(breakColor);
		addSetting(redstoneColor);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		lastDecayAt = System.currentTimeMillis();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		pings.clear();
		knownStates.clear();
		recentEvents.clear();
		lastDecayAt = 0L;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(MC.player == null || MC.level == null)
			return;
			
		// In local singleplayer worlds there are no remote players, so block
		// updates are almost always natural/server-simulated changes.
		if(MC.isLocalServer())
			return;
		
		Packet<?> packet = event.getPacket();
		if(packet instanceof ClientboundBlockUpdatePacket blockUpdate)
		{
			handleBlockChange(blockUpdate.getPos(),
				blockUpdate.getBlockState());
			return;
		}
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket deltaUpdate)
		{
			deltaUpdate.runUpdates(this::handleBlockChange);
			return;
		}
	}
	
	private void handleBlockChange(BlockPos pos, BlockState newState)
	{
		if(pos == null || newState == null)
			return;
		
		if(!isInAllowedRange(pos))
			return;
		
		long now = System.currentTimeMillis();
		long key = pos.asLong();
		BlockState oldState = resolveOldState(pos, key, newState, now);
		DetectionResult detection =
			classifyRelevantTransition(oldState, newState);
		knownStates.put(key, new CachedState(newState, now));
		
		if(detection.score <= 0)
			return;
		
		updatePing(pos, detection.kind, detection.oldId, detection.newId);
		recordRecentEvent(pos);
	}
	
	private DetectionResult classifyRelevantTransition(BlockState oldState,
		BlockState newState)
	{
		if(oldState == null || newState == null || oldState == newState)
			return DetectionResult.NONE;
		
		boolean oldAir = oldState.isAir();
		boolean newAir = newState.isAir();
		boolean oldFluid = !oldState.getFluidState().isEmpty();
		boolean newFluid = !newState.getFluidState().isEmpty();
		String oldId = getBlockId(oldState);
		String newId = getBlockId(newState);
		if(isLikelyNaturalTransition(oldId, newId))
			return DetectionResult.NONE;
		
		if(isFluidOrFireState(oldState) || isFluidOrFireState(newState))
			return DetectionResult.NONE;
		
		if(oldAir && !newAir && !newFluid)
			return new DetectionResult(4.0, "PLACE", oldId, newId);
		
		if(!oldAir && !oldFluid && newAir)
			return new DetectionResult(4.0, "BREAK", oldId, newId);
		
		if(!detectRedstoneInteraction.isChecked())
			return DetectionResult.NONE;
		
		if(oldState.getBlock() == newState.getBlock())
		{
			if(hasInteractiveFlip(oldState, newState))
				return new DetectionResult(1.7, "REDSTONE", oldId, newId);
			return DetectionResult.NONE;
		}
		
		return DetectionResult.NONE;
	}
	
	private boolean isLikelyNaturalTransition(String oldId, String newId)
	{
		return isLikelyNaturalBlock(oldId) || isLikelyNaturalBlock(newId);
	}
	
	private boolean isLikelyNaturalBlock(String blockId)
	{
		if(blockId == null)
			return false;
		
		if("minecraft:air".equals(blockId))
			return false;
		
		return blockId.contains("vine") || blockId.contains("amethyst_bud")
			|| blockId.contains("mushroom") || blockId.contains("short_grass")
			|| blockId.contains("tall_grass") || blockId.contains("fern")
			|| blockId.contains("lichen") || blockId.contains("moss")
			|| blockId.contains("seagrass") || blockId.contains("kelp")
			|| blockId.contains("sugar_cane") || blockId.contains("cactus")
			|| blockId.contains("bamboo") || blockId.contains("dripleaf")
			|| blockId.contains("dripstone") || blockId.contains("cocoa")
			|| blockId.contains("nether_wart") || blockId.contains("crop")
			|| blockId.contains("sweet_berry_bush");
	}
	
	private boolean hasInteractiveFlip(BlockState oldState, BlockState newState)
	{
		try
		{
			for(var property : oldState.getProperties())
			{
				String name = property.getName();
				if(!name.equals("open") && !name.equals("powered")
					&& !name.equals("triggered") && !name.equals("lit"))
					continue;
				
				var oldVal = oldState.getValue(property);
				var newVal = newState.getValue(property);
				if(oldVal != null && newVal != null && !oldVal.equals(newVal))
					return true;
			}
		}catch(Exception ignored)
		{}
		
		return false;
	}
	
	private String getBlockId(BlockState state)
	{
		if(state == null)
			return "minecraft:air";
		
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
	
	private boolean isFireId(String blockId)
	{
		if(blockId == null)
			return false;
		
		return "minecraft:fire".equals(blockId)
			|| "minecraft:soul_fire".equals(blockId)
			|| blockId.contains("fire");
	}
	
	private boolean isFluidOrFireState(BlockState state)
	{
		if(state == null)
			return true;
		
		if(!state.getFluidState().isEmpty())
			return true;
		
		return isFireId(getBlockId(state));
	}
	
	private void recordRecentEvent(BlockPos pos)
	{
		long now = System.currentTimeMillis();
		recentEvents.addLast(new RecentEvent(pos.immutable(), now));
		
		while(!recentEvents.isEmpty())
		{
			RecentEvent first = recentEvents.peekFirst();
			if(first == null || now - first.timeMs <= RECENT_EVENT_WINDOW_MS)
				break;
			recentEvents.pollFirst();
		}
	}
	
	private void updatePing(BlockPos pos, String kind, String oldId,
		String newId)
	{
		long key = pos.asLong();
		long now = System.currentTimeMillis();
		SonarPing ping = pings.get(key);
		if(ping == null)
		{
			ping = new SonarPing(pos.immutable());
			pings.put(key, ping);
		}
		
		ping.lastUpdateMs = now;
		ping.hits++;
		ping.lastKind = kind;
		ping.lastOldId = oldId;
		ping.lastNewId = newId;
		
		if(chatAlerts.isChecked() && now - ping.lastAlertMs >= 300L)
		{
			int distanceBlocks = (int)Math
				.round(Math.sqrt(ping.pos.distToCenterSqr(MC.player.getX(),
					MC.player.getY(), MC.player.getZ())));
			String rangeSuffix = onlyBeyondPlayerEspRange.isChecked()
				? String.format(" (%db away, outside %.0fb).", distanceBlocks,
					PLAYER_ESP_LIMIT_BLOCKS)
				: String.format(" (%db away).", distanceBlocks);
			ChatUtils.message(String.format(
				"PlayerSonar: %s %s -> %s at %d, %d, %d%s", ping.lastKind,
				ping.lastOldId, ping.lastNewId, ping.pos.getX(),
				ping.pos.getY(), ping.pos.getZ(), rangeSuffix));
			ping.lastAlertMs = now;
		}
		
		trimToMarkerLimit();
	}
	
	private void trimToMarkerLimit()
	{
		int max = maxMarkers.getValueI();
		if(pings.size() <= max)
			return;
		
		ArrayList<SonarPing> sorted = new ArrayList<>(pings.values());
		sorted.sort(Comparator.comparingLong(p -> p.lastUpdateMs));
		int toRemove = pings.size() - max;
		for(int i = 0; i < toRemove && i < sorted.size(); i++)
			pings.remove(sorted.get(i).pos.asLong());
	}
	
	private BlockState resolveOldState(BlockPos pos, long key,
		BlockState newState, long now)
	{
		CachedState cached = knownStates.get(key);
		if(cached != null && now - cached.timeMs <= STATE_CACHE_TTL_MS)
		{
			if(cached.state != newState)
				return cached.state;
		}
		
		return MC.level.getBlockState(pos);
	}
	
	@Override
	public void onUpdate()
	{
		long now = System.currentTimeMillis();
		if(lastDecayAt <= 0L)
			lastDecayAt = now;
		
		lastDecayAt = now;
		
		long lifetimeMs = markerLifetimeSec.getValueI() * 1000L;
		pings.entrySet().removeIf(entry -> {
			SonarPing ping = entry.getValue();
			return now - ping.lastUpdateMs > lifetimeMs
				|| shouldRevokePing(ping);
		});
		
		while(!recentEvents.isEmpty())
		{
			RecentEvent first = recentEvents.peekFirst();
			if(first == null || now - first.timeMs <= RECENT_EVENT_WINDOW_MS)
				break;
			recentEvents.pollFirst();
		}
		
		knownStates.entrySet()
			.removeIf(e -> now - e.getValue().timeMs > STATE_CACHE_TTL_MS);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null || pings.isEmpty())
			return;
		
		List<SonarPing> sorted = new ArrayList<>(pings.values()).stream()
			.filter(p -> isInAllowedRange(p.pos)).sorted(Comparator
				.comparingLong((SonarPing p) -> p.lastUpdateMs).reversed())
			.limit(maxMarkers.getValueI()).toList();
		
		ArrayList<ColoredPoint> tracerEnds =
			showTracers.isChecked() ? new ArrayList<>(sorted.size()) : null;
		
		for(SonarPing ping : sorted)
		{
			int color = getColorForKind(ping.lastKind);
			if(tracerFlash.isChecked())
				color = RenderUtils.flashColor(color);
			AABB box = new AABB(ping.pos);
			
			if(showBoxes.isChecked())
				RenderUtils.drawOutlinedBox(matrixStack, box, color, false);
			
			if(tracerEnds != null)
				tracerEnds.add(new ColoredPoint(box.getCenter(), color));
		}
		
		if(tracerEnds != null && !tracerEnds.isEmpty())
			RenderUtils.drawTracers("playersonar", matrixStack, partialTicks,
				tracerEnds, false, tracerThickness.getValue());
	}
	
	private int getColorForKind(String kind)
	{
		return switch(kind)
		{
			case "PLACE" -> placeColor.getColorI(220);
			case "BREAK" -> breakColor.getColorI(220);
			case "REDSTONE" -> redstoneColor.getColorI(220);
			default -> placeColor.getColorI(220);
		};
	}
	
	private boolean isBeyondPlayerEspRange(BlockPos pos)
	{
		if(MC.player == null || pos == null)
			return false;
		
		Vec3 center = Vec3.atCenterOf(pos);
		return MC.player.position().distanceToSqr(center) > PLAYER_ESP_LIMIT_SQ;
	}
	
	private boolean isInAllowedRange(BlockPos pos)
	{
		if(pos == null || MC.player == null)
			return false;
		
		if(!onlyBeyondPlayerEspRange.isChecked())
			return true;
		
		return isBeyondPlayerEspRange(pos);
	}
	
	private boolean shouldRevokePing(SonarPing ping)
	{
		if(ping == null || ping.pos == null || MC.player == null
			|| MC.level == null)
			return false;
		
		if(!onlyBeyondPlayerEspRange.isChecked())
			return false;
		
		if(isBeyondPlayerEspRange(ping.pos))
			return false;
		
		return !hasTrackedPlayerNear(ping.pos);
	}
	
	private boolean hasTrackedPlayerNear(BlockPos pos)
	{
		Vec3 center = Vec3.atCenterOf(pos);
		for(Player player : MC.level.players())
		{
			if(player == null || player == MC.player || player.isRemoved())
				continue;
			
			if(player.position()
				.distanceToSqr(center) <= CONFIRM_PLAYER_RADIUS_SQ)
				return true;
		}
		
		return false;
	}
	
	private static final class SonarPing
	{
		private final BlockPos pos;
		private long lastUpdateMs = System.currentTimeMillis();
		private long lastAlertMs;
		private int hits;
		private String lastKind = "UNKNOWN";
		private String lastOldId = "unknown";
		private String lastNewId = "unknown";
		
		private SonarPing(BlockPos pos)
		{
			this.pos = pos;
		}
	}
	
	private record DetectionResult(double score, String kind, String oldId,
		String newId)
	{
		private static final DetectionResult NONE =
			new DetectionResult(0, "", "", "");
	}
	
	private record CachedState(BlockState state, long timeMs)
	{}
	
	private record RecentEvent(BlockPos pos, long timeMs)
	{}
}
