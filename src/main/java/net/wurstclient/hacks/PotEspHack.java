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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
import net.wurstclient.util.EspLimitUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.chunk.ChunkUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"pot esp", "PotESP", "decorated pot esp", "trial pot"})
public final class PotEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private static final Set<Item> NATURAL_POT_ITEMS = Set.of(Items.AIR,
		Items.STRING, Items.EMERALD, Items.EMERALD_BLOCK, Items.RAW_IRON_BLOCK,
		Items.IRON_INGOT, Items.TRIAL_KEY, Items.DIAMOND, Items.DIAMOND_BLOCK,
		Items.MUSIC_DISC_CREATOR_MUSIC_BOX);
	
	private final EspStyleSetting style = new EspStyleSetting();
	private final CheckboxSetting stickyArea =
		new CheckboxSetting("Sticky area",
			"Off: Re-centers every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	private final ColorSetting color = new ColorSetting("Color",
		"Suspicious pots will be highlighted in this color.",
		new Color(0xE74C3C));
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final CheckboxSetting chatAlerts =
		new CheckboxSetting("Chat alerts",
			"Show a chat alert when a suspicious decorated pot is detected.",
			false);
	private final CheckboxSetting soundAlerts =
		new CheckboxSetting("Sound alerts",
			"Play a sound alert when a suspicious decorated pot is detected.",
			false);
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of suspicious pots to this hack's entry in the HackList.",
		false);
	private final CheckboxSetting onlyAboveGround = new CheckboxSetting(
		"Above ground only",
		"Only show suspicious pots at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> state.getBlock() == Blocks.DECORATED_POT;
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	
	private final HashSet<BlockPos> alertedPots = new HashSet<>();
	private final HashSet<BlockPos> currentSuspiciousPots = new HashSet<>();
	private final ArrayList<AABB> renderBoxes = new ArrayList<>();
	private List<Vec3> tracerEnds = List.of();
	private boolean renderDataUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	private int lastMatchesVersion;
	private int foundCount;
	
	public PotEspHack()
	{
		super("PotESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(color);
		addSetting(tracerFlash);
		addSetting(chatAlerts);
		addSetting(soundAlerts);
		addSetting(showCountInHackList);
		addSetting(area);
		addSetting(stickyArea);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
	}
	
	@Override
	protected void onEnable()
	{
		renderDataUpToDate = false;
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = ChunkPos.containing(MC.player.blockPosition());
		lastMatchesVersion = coordinator.getMatchesVersion();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(net.wurstclient.events.PacketInputListener.class,
			coordinator);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(net.wurstclient.events.PacketInputListener.class,
			coordinator);
		coordinator.reset();
		renderBoxes.clear();
		currentSuspiciousPots.clear();
		alertedPots.clear();
		tracerEnds = List.of();
		foundCount = 0;
		lastMatchesVersion = coordinator.getMatchesVersion();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			renderDataUpToDate = false;
		}
		
		ChunkPos currentChunk = ChunkPos.containing(MC.player.blockPosition());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			renderDataUpToDate = false;
		}
		
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			renderDataUpToDate = false;
		
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion != lastMatchesVersion)
		{
			lastMatchesVersion = matchesVersion;
			renderDataUpToDate = false;
		}
		
		boolean partialScan =
			WURST.getHax().globalToggleHack.usePartialChunkScan();
		if(!renderDataUpToDate && (partialScan ? coordinator.hasReadyMatches()
			: coordinator.isDone()))
			rebuildRenderData();
	}
	
	private void rebuildRenderData()
	{
		renderBoxes.clear();
		currentSuspiciousPots.clear();
		
		int globalLimit = getEffectiveGlobalEspLimit();
		if(globalLimit > 0)
		{
			for(Result result : getNearestSuspiciousMatches(globalLimit))
				addResult(result);
		}else
			coordinator.getReadyMatches().forEach(this::addResult);
			
		// Fallback scan over loaded block entities to handle hacked/malformed
		// pots where suspicious items can sit in non-zero container slots.
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof DecoratedPotBlockEntity)
			.map(BlockEntity::getBlockPos).forEach(pos -> {
				if(onlyAboveGround.isChecked()
					&& pos.getY() < aboveGroundY.getValue())
					return;
				Item suspiciousItem = getSuspiciousItem(pos);
				if(suspiciousItem == null)
					return;
				addSuspiciousPos(pos);
			});
		
		tracerEnds = renderBoxes.stream().map(AABB::getCenter).toList();
		foundCount = Math.min(renderBoxes.size(), 999);
		renderDataUpToDate = true;
		
		if(chatAlerts.isChecked() || soundAlerts.isChecked())
		{
			for(BlockPos pos : currentSuspiciousPots)
				if(alertedPots.add(pos))
					triggerAlert(pos);
				
			alertedPots.retainAll(currentSuspiciousPots);
		}else
			alertedPots.clear();
	}
	
	private int getEffectiveGlobalEspLimit()
	{
		return WURST.getHax().globalToggleHack
			.getEffectiveGlobalEspRenderLimit();
	}
	
	private List<Result> getNearestSuspiciousMatches(int limit)
	{
		var eyesPos = RotationUtils.getEyesPos();
		return EspLimitUtils.collectNearest(coordinator.getReadyMatches(),
			limit, result -> result.pos().distToCenterSqr(eyesPos),
			this::isSuspiciousResult);
	}
	
	private boolean isSuspiciousResult(Result result)
	{
		BlockPos pos = result.pos();
		if(onlyAboveGround.isChecked() && pos.getY() < aboveGroundY.getValue())
			return false;
		return getSuspiciousItem(pos) != null;
	}
	
	private void addResult(Result result)
	{
		BlockPos pos = result.pos();
		if(onlyAboveGround.isChecked() && pos.getY() < aboveGroundY.getValue())
			return;
		
		Item suspiciousItem = getSuspiciousItem(pos);
		if(suspiciousItem == null)
			return;
		
		addSuspiciousPos(pos);
	}
	
	private void addSuspiciousPos(BlockPos pos)
	{
		BlockPos immutablePos = pos.immutable();
		if(currentSuspiciousPots.add(immutablePos))
			renderBoxes.add(new AABB(immutablePos));
	}
	
	private Item getSuspiciousItem(BlockPos pos)
	{
		if(MC.level == null || pos == null)
			return null;
		
		BlockEntity blockEntity = MC.level.getBlockEntity(pos);
		if(!(blockEntity instanceof DecoratedPotBlockEntity pot))
			return null;
		
		try
		{
			// Read full container components first. This catches hacked pots
			// that contain multiple item types, not just slot 0.
			ItemContainerContents contents =
				pot.collectComponents().getOrDefault(DataComponents.CONTAINER,
					ItemContainerContents.EMPTY);
			boolean hadAnyContainerItems = false;
			for(ItemStack stack : contents.nonEmptyItemCopyStream().toList())
			{
				hadAnyContainerItems = true;
				Item item = stack.getItem();
				if(!NATURAL_POT_ITEMS.contains(item))
					return item;
			}
			if(hadAnyContainerItems)
				return null;
			
			// Fallback for plain single-item pots.
			ItemStack stack = pot.getTheItem();
			Item item = stack.isEmpty() ? Items.AIR : stack.getItem();
			return NATURAL_POT_ITEMS.contains(item) ? null : item;
		}catch(Throwable ignored)
		{
			return null;
		}
	}
	
	private void triggerAlert(BlockPos pos)
	{
		Item suspiciousItem = getSuspiciousItem(pos);
		if(suspiciousItem == null)
			return;
		
		if(chatAlerts.isChecked())
		{
			String itemId =
				BuiltInRegistries.ITEM.getKey(suspiciousItem).toString();
			ChatUtils.message("PotESP: Suspicious pot at " + pos.getX() + ", "
				+ pos.getY() + ", " + pos.getZ() + " contains " + itemId + ".");
		}
		
		if(soundAlerts.isChecked() && MC.level != null && MC.player != null)
			MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
				MC.player.getZ(), SoundEvents.NOTE_BLOCK_CHIME.value(),
				SoundSource.PLAYERS, 1.0F, 1.0F, false);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(renderBoxes.isEmpty())
			return;
		
		if(style.hasBoxes())
		{
			RenderUtils.drawSolidBoxes(matrixStack, renderBoxes,
				color.getColorI(0x40), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, renderBoxes,
				color.getColorI(0x80), false);
		}
		
		if(style.hasLines() && !tracerEnds.isEmpty())
		{
			int tracerColor = color.getColorI(0x80);
			if(tracerFlash.isChecked())
				tracerColor = RenderUtils.flashColor(tracerColor);
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				tracerColor, false);
		}
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public String getRenderName()
	{
		if(showCountInHackList.isChecked() && foundCount > 0)
			return getName() + " [" + foundCount + "]";
		return getName();
	}
}
