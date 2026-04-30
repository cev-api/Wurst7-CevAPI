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
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
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
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EspLimitUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"barrier esp"})
public final class BarrierEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EnumSetting<RenderMode> renderMode = new EnumSetting<>(
		"Render mode", "description.wurst.setting.barrieresp.render_mode",
		RenderMode.values(), RenderMode.ESP_AND_ICONS);
	private final EspStyleSetting style = new EspStyleSetting();
	private final CheckboxSetting stickyArea =
		new CheckboxSetting("Sticky area",
			"description.wurst.setting.barrieresp.sticky_area", false);
	private final ColorSetting color = new ColorSetting("Barrier color",
		"description.wurst.setting.barrieresp.barrier_color",
		new Color(0xFF5555));
	private final CheckboxSetting fillBoxes = new CheckboxSetting("Fill boxes",
		"description.wurst.setting.barrieresp.fill_boxes", true);
	private final CheckboxSetting tracerFlash =
		new CheckboxSetting("Tracer flash",
			"description.wurst.setting.barrieresp.tracer_flash", false);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"description.wurst.setting.barrieresp.area");
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"description.wurst.setting.barrieresp.above_ground_only", false);
	private final SliderSetting aboveGroundY =
		new SliderSetting("Set ESP Y limit",
			"description.wurst.setting.barrieresp.set_esp_y_limit", 62, -65,
			255, 1, SliderSetting.ValueDisplay.INTEGER);
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> state.getBlock() == Blocks.BARRIER;
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	private final ArrayList<AABB> boxes = new ArrayList<>();
	private boolean boxesUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	private int lastMatchesVersion;
	private RenderMode lastRenderMode;
	private boolean espEventsRegistered;
	
	public BarrierEspHack()
	{
		super("BarrierESP");
		setCategory(Category.RENDER);
		addSetting(renderMode);
		addSetting(style);
		addSetting(color);
		addSetting(fillBoxes);
		addSetting(tracerFlash);
		addSetting(area);
		addSetting(stickyArea);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
	}
	
	@Override
	protected void onEnable()
	{
		boxesUpToDate = false;
		lastRenderMode = renderMode.getSelected();
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.blockPosition());
		lastMatchesVersion = coordinator.getMatchesVersion();
		EVENTS.add(UpdateListener.class, this);
		if(lastRenderMode.usesEsp())
			addEspEvents();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		removeEspEvents();
		coordinator.reset();
		lastMatchesVersion = coordinator.getMatchesVersion();
		boxes.clear();
	}
	
	@Override
	public void onUpdate()
	{
		RenderMode currentMode = renderMode.getSelected();
		if(currentMode != lastRenderMode)
		{
			lastRenderMode = currentMode;
			boxes.clear();
			boxesUpToDate = false;
			coordinator.reset();
			lastMatchesVersion = coordinator.getMatchesVersion();
			lastAreaSelection = area.getSelected();
			lastPlayerChunk = new ChunkPos(MC.player.blockPosition());
			
			if(currentMode.usesEsp())
				addEspEvents();
			else
				removeEspEvents();
		}
		
		if(!currentMode.usesEsp())
			return;
		
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			boxesUpToDate = false;
		}
		
		ChunkPos currentChunk = new ChunkPos(MC.player.blockPosition());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			boxesUpToDate = false;
		}
		
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			boxesUpToDate = false;
		
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion != lastMatchesVersion)
		{
			lastMatchesVersion = matchesVersion;
			boxesUpToDate = false;
		}
		
		boolean partialScan =
			WURST.getHax().globalToggleHack.usePartialChunkScan();
		if(!boxesUpToDate && (partialScan ? coordinator.hasReadyMatches()
			: coordinator.isDone()))
			updateBoxes();
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(!renderMode.getSelected().usesEsp())
			return;
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(!renderMode.getSelected().usesEsp())
			return;
		
		if(style.hasBoxes())
			renderBoxes(matrixStack);
		if(style.hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(PoseStack matrixStack)
	{
		int quadsColor = color.getColorI(0x40);
		int linesColor = color.getColorI(0x80);
		if(fillBoxes.isChecked())
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
		RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor, false);
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks)
	{
		List<Vec3> ends = boxes.stream().map(AABB::getCenter).toList();
		int tracerColor = color.getColorI(0x80);
		if(tracerFlash.isChecked())
			tracerColor = RenderUtils.flashColor(tracerColor);
		RenderUtils.drawTracers(matrixStack, partialTicks, ends, tracerColor,
			false);
	}
	
	private void updateBoxes()
	{
		boxes.clear();
		int globalLimit = getEffectiveGlobalEspLimit();
		if(globalLimit > 0)
		{
			for(Result result : getNearestReadyMatches(globalLimit))
				addBarrierBox(result.pos());
		}else
			coordinator.getReadyMatches().forEach(this::addBarrierBox);
		
		boxesUpToDate = true;
	}
	
	private int getEffectiveGlobalEspLimit()
	{
		return WURST.getHax().globalToggleHack
			.getEffectiveGlobalEspRenderLimit();
	}
	
	private List<Result> getNearestReadyMatches(int limit)
	{
		var eyesPos = RotationUtils.getEyesPos();
		return EspLimitUtils.collectNearest(coordinator.getReadyMatches(),
			limit, result -> result.pos().distToCenterSqr(eyesPos),
			this::shouldRenderResult);
	}
	
	private boolean shouldRenderResult(Result result)
	{
		return !onlyAboveGround.isChecked()
			|| result.pos().getY() >= aboveGroundY.getValue();
	}
	
	private void addBarrierBox(Result result)
	{
		addBarrierBox(result.pos());
	}
	
	private void addBarrierBox(BlockPos pos)
	{
		if(onlyAboveGround.isChecked() && pos.getY() < aboveGroundY.getValue())
			return;
		if(!BlockUtils.canBeClicked(pos))
			return;
		AABB box = BlockUtils.getBoundingBox(pos);
		if(box.getSize() == 0)
			return;
		boxes.add(box);
	}
	
	public boolean shouldRenderVanillaIcons()
	{
		return renderMode.getSelected().usesIcons();
	}
	
	private void addEspEvents()
	{
		if(espEventsRegistered)
			return;
		
		espEventsRegistered = true;
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(net.wurstclient.events.PacketInputListener.class,
			coordinator);
	}
	
	private void removeEspEvents()
	{
		if(!espEventsRegistered)
			return;
		
		espEventsRegistered = false;
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(net.wurstclient.events.PacketInputListener.class,
			coordinator);
	}
	
	private enum RenderMode
	{
		ICONS_ONLY("Icons only", false, true),
		ESP_ONLY("ESP only", true, false),
		ESP_AND_ICONS("ESP + icons", true, true);
		
		private final String name;
		private final boolean esp;
		private final boolean icons;
		
		private RenderMode(String name, boolean esp, boolean icons)
		{
			this.name = name;
			this.esp = esp;
			this.icons = icons;
		}
		
		public boolean usesEsp()
		{
			return esp;
		}
		
		public boolean usesIcons()
		{
			return icons;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
