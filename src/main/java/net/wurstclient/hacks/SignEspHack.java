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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.GlowItemFrameEntity;
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
import net.wurstclient.settings.Setting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"sign esp", "SignESP"})
public final class SignEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final net.wurstclient.settings.CheckboxSetting stickyArea =
		new net.wurstclient.settings.CheckboxSetting("Sticky area",
			"Off: Re-centers every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	// Signs are always included; frame inclusion is controlled by a single
	// checkbox that used to be named "Include signs" in the UI per request.
	private final SignEspGroup signs =
		new SignEspGroup(
			new ColorSetting("Sign color",
				"Signs will be highlighted in this color.", Color.ORANGE),
			null);
	private final List<SignEspGroup> groups = Arrays.asList(signs);
	// Frames (incl. glow) toggle/group - controlled by a single checkbox
	// labeled "Include frames".
	private final CheckboxSetting framesEnabled =
		new CheckboxSetting("Include frames", false);
	private final FrameEspEntityGroup frames =
		new FrameEspEntityGroup(new ColorSetting("Frame color",
			"Item frames (including glow frames) will be highlighted in this color.",
			Color.YELLOW), framesEnabled);
	private final List<FrameEspEntityGroup> entityGroups =
		Arrays.asList(frames);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> state.getBlock() instanceof AbstractSignBlock;
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	private boolean groupsUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	
	public SignEspHack()
	{
		super("SignESP");
		setCategory(Category.RENDER);
		addSetting(style);
		groups.stream().flatMap(SignEspGroup::getSettings)
			.forEach(this::addSetting);
		entityGroups.stream().flatMap(FrameEspEntityGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(area);
		addSetting(stickyArea);
	}
	
	@Override
	protected void onEnable()
	{
		groupsUpToDate = false;
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.getBlockPos());
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
		groups.forEach(SignEspGroup::clear);
	}
	
	@Override
	public void onUpdate()
	{
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			groupsUpToDate = false;
		}
		// Recenter per chunk when sticky is off
		ChunkPos currentChunk = new ChunkPos(MC.player.getBlockPos());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			groupsUpToDate = false;
		}
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			groupsUpToDate = false;
		if(!groupsUpToDate && coordinator.isDone())
			updateGroupBoxes();
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.getSelected().hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		// Update entity-group boxes each frame for smooth rendering
		entityGroups.stream().filter(FrameEspEntityGroup::isEnabled)
			.forEach(g -> g.updateBoxes(partialTicks));
		if(style.getSelected().hasBoxes())
			renderBoxes(matrixStack);
		if(style.getSelected().hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(MatrixStack matrixStack)
	{
		for(SignEspGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			int quadsColor = group.getColorI(0x40);
			int linesColor = group.getColorI(0x80);
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
		}
		// frames
		for(FrameEspEntityGroup group : entityGroups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			int quadsColor = group.getColorI(0x40);
			int linesColor = group.getColorI(0x80);
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
		}
	}
	
	private void renderTracers(MatrixStack matrixStack, float partialTicks)
	{
		for(SignEspGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			List<Vec3d> ends = boxes.stream().map(Box::getCenter).toList();
			int color = group.getColorI(0x80);
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
		// frames
		for(FrameEspEntityGroup group : entityGroups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			List<Vec3d> ends = boxes.stream().map(Box::getCenter).toList();
			int color = group.getColorI(0x80);
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
	}
	
	private void updateGroupBoxes()
	{
		groups.forEach(SignEspGroup::clear);
		coordinator.getMatches().forEach(this::addToGroupBoxes);
		groupsUpToDate = true;
	}
	
	private void addToGroupBoxes(Result result)
	{
		for(SignEspGroup group : groups)
		{
			group.add(result.pos());
			break;
		}
	}
	
	private static final class SignEspGroup
	{
		private final ArrayList<Box> boxes = new ArrayList<>();
		private final ColorSetting color;
		private final CheckboxSetting enabled;
		
		private SignEspGroup(ColorSetting color, CheckboxSetting enabled)
		{
			this.color = Objects.requireNonNull(color);
			this.enabled = enabled;
		}
		
		public void add(BlockPos pos)
		{
			if(!isEnabled())
				return;
			if(!BlockUtils.canBeClicked(pos))
				return;
			Box box = BlockUtils.getBoundingBox(pos);
			if(box.getAverageSideLength() == 0)
				return;
			boxes.add(box);
		}
		
		public void clear()
		{
			boxes.clear();
		}
		
		public boolean isEnabled()
		{
			return enabled == null || enabled.isChecked();
		}
		
		public Stream<Setting> getSettings()
		{
			return Stream.of(enabled, color).filter(Objects::nonNull);
		}
		
		public int getColorI(int alpha)
		{
			return color.getColorI(alpha);
		}
		
		public List<Box> getBoxes()
		{
			return java.util.Collections.unmodifiableList(boxes);
		}
	}
	
	private static final class FrameEspEntityGroup
	{
		private final ArrayList<Box> boxes = new ArrayList<>();
		private final ColorSetting color;
		private final CheckboxSetting enabled;
		
		private FrameEspEntityGroup(ColorSetting color, CheckboxSetting enabled)
		{
			this.color = Objects.requireNonNull(color);
			this.enabled = enabled;
		}
		
		public void updateBoxes(float partialTicks)
		{
			boxes.clear();
			if(!isEnabled())
				return;
			for(var e : net.wurstclient.WurstClient.MC.world.getEntities())
			{
				if(e instanceof ItemFrameEntity
					|| e instanceof GlowItemFrameEntity)
				{
					Box b = EntityUtils.getLerpedBox(e, partialTicks);
					boxes.add(b);
				}
			}
		}
		
		public void clear()
		{
			boxes.clear();
		}
		
		public boolean isEnabled()
		{
			return enabled == null || enabled.isChecked();
		}
		
		public Stream<Setting> getSettings()
		{
			return Stream.of(enabled, color).filter(Objects::nonNull);
		}
		
		public int getColorI(int alpha)
		{
			return color.getColorI(alpha);
		}
		
		public List<Box> getBoxes()
		{
			return java.util.Collections.unmodifiableList(boxes);
		}
	}
}
