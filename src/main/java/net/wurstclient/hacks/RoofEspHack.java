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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"roof esp", "nether roof esp", "roofesp"})
public final class RoofEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private static final int ROOF_Y = 128;
	
	private final EspStyleSetting style = new EspStyleSetting();
	private final ColorSetting color = new ColorSetting("Color",
		"Targets at or above Y=128 in the Nether will be highlighted in this color.",
		new Color(255, 80, 80));
	private final CheckboxSetting highlightFill = new CheckboxSetting(
		"Fill boxes", "Draw filled boxes in addition to outlines.", true);
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final CheckboxSetting chatAlerts =
		new CheckboxSetting("Chat alerts",
			"Sends a chat alert when the number of RoofESP detections changes.",
			false);
	private final CheckboxSetting soundAlerts = new CheckboxSetting(
		"Sound alerts",
		"Plays a sound when the number of RoofESP detections changes.", false);
	private final SliderSetting alertCooldown =
		new SliderSetting("Alert cooldown", "Minimum time between chat alerts.",
			5, 0, 60, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix("s"));
	private final CheckboxSetting ignoreOwnDrops =
		new CheckboxSetting("Ignore own drops",
			"Best-effort filter to ignore item entities dropped by you.", true);
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of detected roof targets to this hack's entry in the HackList.",
		true);
	private final CheckboxSetting stickyArea =
		new CheckboxSetting("Sticky area",
			"Off: Re-centers the scan every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around you to scan for roof targets.",
		ChunkAreaSetting.ChunkArea.A11);
	private final SliderSetting blockScanInterval = new SliderSetting(
		"Block scan interval",
		"How often RoofESP rescans roof blocks. Higher values reduce lag.", 10,
		1, 60, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final SliderSetting blockRenderLimit = new SliderSetting(
		"Block render limit",
		"Maximum amount of roof blocks to process each scan. 0 = unlimited.",
		500, 0, 5000, 1, SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting detectLowerLadders =
		new CheckboxSetting("Detect ladders 118-128",
			"Also detect ladder blocks between Y=118 and Y=127 in the Nether.",
			true);
	private final CheckboxSetting ignoreNaturalRoofBlocks =
		new CheckboxSetting("Ignore natural roof blocks",
			"Hides natural roof noise blocks (red/brown mushrooms).", true);
	
	private final ArrayList<AABB> boxes = new ArrayList<>();
	private final ArrayList<Vec3> tracerEnds = new ArrayList<>();
	private final ArrayList<Target> cachedBlockTargets = new ArrayList<>();
	private int foundCount;
	private int lastAlertCount = -1;
	private long lastAlertMs;
	private int scanTimer;
	private ChunkPos anchorChunk;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	private Method cachedOwnerMethod;
	private boolean ownerMethodResolved;
	
	public RoofEspHack()
	{
		super("RoofESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(color);
		addSetting(highlightFill);
		addSetting(tracerFlash);
		addSetting(chatAlerts);
		addSetting(soundAlerts);
		addSetting(alertCooldown);
		addSetting(ignoreOwnDrops);
		addSetting(showCountInHackList);
		addSetting(stickyArea);
		addSetting(area);
		addSetting(blockScanInterval);
		addSetting(blockRenderLimit);
		addSetting(detectLowerLadders);
		addSetting(ignoreNaturalRoofBlocks);
	}
	
	@Override
	public String getRenderName()
	{
		if(showCountInHackList.isChecked() && foundCount > 0)
			return getName() + " [" + Math.min(foundCount, 999) + "]";
		
		return getName();
	}
	
	public int getDetectionCount()
	{
		return foundCount;
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		anchorChunk = MC.player == null ? null : MC.player.chunkPosition();
		lastAreaSelection = area.getSelected();
		scanTimer = 0;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		boxes.clear();
		tracerEnds.clear();
		foundCount = 0;
		lastAlertCount = -1;
		cachedBlockTargets.clear();
		scanTimer = 0;
		anchorChunk = null;
	}
	
	@Override
	public void onUpdate()
	{
		boxes.clear();
		tracerEnds.clear();
		
		if(!isInNether())
		{
			foundCount = 0;
			cachedBlockTargets.clear();
			anchorChunk = null;
			return;
		}
		
		ArrayList<Target> targets = new ArrayList<>();
		updateBlockCache();
		targets.addAll(cachedBlockTargets);
		collectRoofItems(targets);
		
		targets.sort(Comparator.comparingDouble(this::distanceSqToPlayer));
		int limit = getEffectiveGlobalEspLimit();
		if(limit > 0 && targets.size() > limit)
			targets.subList(limit, targets.size()).clear();
		
		for(Target t : targets)
		{
			boxes.add(t.box);
			tracerEnds.add(t.end);
		}
		
		foundCount = Math.min(targets.size(), 999);
		sendAlertIfNeeded(targets.size());
	}
	
	private void updateBlockCache()
	{
		updateAnchorChunk();
		if(scanTimer > 0)
		{
			scanTimer--;
			return;
		}
		scanTimer = Math.max(1, blockScanInterval.getValueI());
		cachedBlockTargets.clear();
		collectRoofBlocks(cachedBlockTargets);
	}
	
	private void collectRoofBlocks(ArrayList<Target> targets)
	{
		if(MC.level == null)
			return;
		
		int worldMinY = MC.level.getMinY();
		int minY = Math.max(ROOF_Y, worldMinY);
		int ladderMinY = Math.max(ROOF_Y - 10, MC.level.getMinY());
		boolean includeLowerLadders = detectLowerLadders.isChecked();
		int maxYExclusive = MC.level.getMaxY();
		if(minY >= maxYExclusive && !includeLowerLadders)
			return;
		int cap = blockRenderLimit.getValueI();
		HashSet<Long> dedupe = new HashSet<>();
		
		for(LevelChunk chunk : getChunksInScanAreaSortedByDistance())
		{
			if(cap > 0 && targets.size() >= cap)
				break;
			
			LevelChunkSection[] sections = chunk.getSections();
			if(sections == null || sections.length == 0)
				continue;
			
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int scanMinY = includeLowerLadders ? ladderMinY : minY;
			int firstSectionY = chunk.getMinY() >> 4;
			int minSectionIndex = Math.max(0, (scanMinY >> 4) - firstSectionY);
			int maxSectionIndex = Math.min(sections.length - 1,
				((maxYExclusive - 1) >> 4) - firstSectionY);
			for(int sectionIndex =
				minSectionIndex; sectionIndex <= maxSectionIndex; sectionIndex++)
			{
				LevelChunkSection section = sections[sectionIndex];
				if(section == null || section.hasOnlyAir())
					continue;
				
				if(cap > 0 && targets.size() >= cap)
					break;
				
				int baseY = (firstSectionY + sectionIndex) << 4;
				for(int ly = 0; ly < 16; ly++)
				{
					int y = baseY + ly;
					if(y < scanMinY || y >= maxYExclusive)
						continue;
					
					if(cap > 0 && targets.size() >= cap)
						break;
					for(int lx = 0; lx < 16; lx++)
					{
						if(cap > 0 && targets.size() >= cap)
							break;
						int x = chunk.getPos().getMinBlockX() + lx;
						for(int lz = 0; lz < 16; lz++)
						{
							pos.set(x, y, chunk.getPos().getMinBlockZ() + lz);
							Block block =
								section.getBlockState(lx, ly, lz).getBlock();
							if(y < ROOF_Y)
							{
								if(!includeLowerLadders
									|| block != Blocks.LADDER)
									continue;
							}else
							{
								if(block == Blocks.AIR
									|| block == Blocks.CAVE_AIR
									|| block == Blocks.VOID_AIR)
									continue;
								if(ignoreNaturalRoofBlocks.isChecked()
									&& isNaturalRoofBlock(block))
									continue;
							}
							long key = pos.asLong();
							if(!dedupe.add(key))
								continue;
							AABB box = new AABB(pos);
							targets.add(new Target(box, box.getCenter()));
						}
					}
				}
			}
		}
	}
	
	private Iterable<LevelChunk> getChunksInScanArea()
	{
		ArrayList<LevelChunk> chunks = new ArrayList<>();
		if(MC.level == null || anchorChunk == null)
			return chunks;
		
		int radius = getChunkRange(area.getSelected());
		for(int x = anchorChunk.x() - radius; x <= anchorChunk.x()
			+ radius; x++)
			for(int z = anchorChunk.z() - radius; z <= anchorChunk.z()
				+ radius; z++)
			{
				if(!MC.level.hasChunk(x, z))
					continue;
				LevelChunk chunk = MC.level.getChunk(x, z);
				if(chunk != null)
					chunks.add(chunk);
			}
		
		return chunks;
	}
	
	private List<LevelChunk> getChunksInScanAreaSortedByDistance()
	{
		ArrayList<LevelChunk> chunks = new ArrayList<>();
		for(LevelChunk chunk : getChunksInScanArea())
			chunks.add(chunk);
		if(anchorChunk == null)
			return chunks;
		chunks.sort(Comparator.comparingInt(chunk -> {
			int dx = chunk.getPos().x() - anchorChunk.x();
			int dz = chunk.getPos().z() - anchorChunk.z();
			return dx * dx + dz * dz;
		}));
		return chunks;
	}
	
	private boolean isNaturalRoofBlock(Block block)
	{
		return block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM;
	}
	
	private int getChunkRange(ChunkAreaSetting.ChunkArea selectedArea)
	{
		String enumName = selectedArea.name(); // A11 -> 11x11
		if(enumName.length() < 2 || enumName.charAt(0) != 'A')
			return 5;
		try
		{
			int diameter = Integer.parseInt(enumName.substring(1));
			return Math.max(0, (diameter - 1) / 2);
		}catch(NumberFormatException e)
		{
			return 5;
		}
	}
	
	private void updateAnchorChunk()
	{
		if(MC.player == null)
			return;
		
		ChunkAreaSetting.ChunkArea selectedArea = area.getSelected();
		if(lastAreaSelection != selectedArea)
		{
			lastAreaSelection = selectedArea;
			scanTimer = 0;
		}
		
		ChunkPos playerChunk = MC.player.chunkPosition();
		if(!stickyArea.isChecked())
		{
			if(anchorChunk == null || !anchorChunk.equals(playerChunk))
			{
				anchorChunk = playerChunk;
				scanTimer = 0;
			}
			return;
		}
		
		if(anchorChunk == null)
		{
			anchorChunk = playerChunk;
			scanTimer = 0;
			return;
		}
		
		// Sticky mode keeps a stable scan center while you move within the
		// selected area, but should re-anchor once you leave that area.
		int radius = getChunkRange(selectedArea);
		if(Math.abs(playerChunk.x() - anchorChunk.x()) > radius
			|| Math.abs(playerChunk.z() - anchorChunk.z()) > radius)
		{
			anchorChunk = playerChunk;
			scanTimer = 0;
		}
	}
	
	private void collectRoofItems(ArrayList<Target> targets)
	{
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof ItemEntity item))
				continue;
			if(item.getY() < ROOF_Y)
				continue;
			if(ignoreOwnDrops.isChecked() && isDroppedByLocalPlayer(item))
				continue;
			ItemStack stack = item.getItem();
			if(stack == null || stack.isEmpty())
				continue;
			if(stack.is(Blocks.RED_MUSHROOM.asItem())
				|| stack.is(Blocks.BROWN_MUSHROOM.asItem()))
				continue;
			AABB box = EntityUtils.getLerpedBox(item,
				MC.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			targets.add(new Target(box, box.getCenter()));
		}
	}
	
	private boolean isDroppedByLocalPlayer(ItemEntity item)
	{
		if(MC.player == null)
			return false;
		UUID owner = getItemOwnerUuid(item);
		return owner != null && owner.equals(MC.player.getUUID());
	}
	
	private UUID getItemOwnerUuid(ItemEntity item)
	{
		Method method = resolveOwnerMethod(item);
		if(method == null)
			return null;
		try
		{
			Object value = method.invoke(item);
			if(value instanceof UUID uuid)
				return uuid;
		}catch(ReflectiveOperationException ignored)
		{}
		return null;
	}
	
	private Method resolveOwnerMethod(ItemEntity item)
	{
		if(ownerMethodResolved)
			return cachedOwnerMethod;
		ownerMethodResolved = true;
		for(String methodName : new String[]{"getOwner", "getThrower"})
			try
			{
				Method method = item.getClass().getMethod(methodName);
				if(method.getReturnType() == UUID.class)
				{
					cachedOwnerMethod = method;
					break;
				}
			}catch(NoSuchMethodException ignored)
			{}
		return cachedOwnerMethod;
	}
	
	private boolean isInNether()
	{
		return MC.level != null && MC.level.dimension() == Level.NETHER;
	}
	
	private int getEffectiveGlobalEspLimit()
	{
		return WURST.getHax().globalToggleHack
			.getEffectiveGlobalEspRenderLimit();
	}
	
	private double distanceSqToPlayer(Target target)
	{
		return MC.player == null ? Double.MAX_VALUE
			: MC.player.distanceToSqr(target.end());
	}
	
	private void sendAlertIfNeeded(int currentCount)
	{
		boolean chatEnabled = chatAlerts.isChecked();
		boolean soundEnabled = soundAlerts.isChecked();
		if(!chatEnabled && !soundEnabled)
		{
			lastAlertCount = currentCount;
			return;
		}
		
		if(currentCount == lastAlertCount)
			return;
		
		if(currentCount <= 0)
		{
			lastAlertCount = currentCount;
			return;
		}
		
		long now = System.currentTimeMillis();
		long cooldownMs = alertCooldown.getValueI() * 1000L;
		if(now - lastAlertMs < cooldownMs)
			return;
		
		lastAlertMs = now;
		lastAlertCount = currentCount;
		
		if(chatEnabled)
			ChatUtils.component(Component.literal("[RoofESP] ")
				.withStyle(ChatFormatting.DARK_RED)
				.append(Component.literal("Detected ")
					.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(Integer.toString(currentCount))
					.withStyle(ChatFormatting.RED))
				.append(Component.literal(" roof targets at/above Y=128.")
					.withStyle(ChatFormatting.GRAY)));
		
		if(soundEnabled && MC.player != null)
			MC.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.35F);
	}
	
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
		if(boxes.isEmpty())
			return;
		int linesColor = color.getColorI(0x80);
		int fillColor = color.getColorI(0x40);
		if(style.hasBoxes())
		{
			if(highlightFill.isChecked())
				RenderUtils.drawSolidBoxes(matrixStack, boxes, fillColor,
					false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
		}
		if(style.hasLines() && !tracerEnds.isEmpty())
		{
			int tracerColor = tracerFlash.isChecked()
				? RenderUtils.flashColor(linesColor) : linesColor;
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				tracerColor, false);
		}
	}
	
	private record Target(AABB box, Vec3 end)
	{
		// compact immutable render target
	}
}
