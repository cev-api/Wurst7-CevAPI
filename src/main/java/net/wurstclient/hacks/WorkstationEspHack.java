/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
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
import net.wurstclient.hacks.portalesp.PortalEspBlockGroup;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"workstation esp", "WorkstationESP"})
public final class WorkstationEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final CheckboxSetting stickyArea =
		new CheckboxSetting("Sticky area",
			"Off: Re-centers every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	private final Color defaultColor = new Color(0x7FC97F);
	// Per-block groups with individual color & toggle (default enabled)
	private final PortalEspBlockGroup craftingTable = new PortalEspBlockGroup(
		Blocks.CRAFTING_TABLE,
		new ColorSetting("Crafting table color",
			"Crafting tables will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include crafting tables", true));
	private final PortalEspBlockGroup smithingTable = new PortalEspBlockGroup(
		Blocks.SMITHING_TABLE,
		new ColorSetting("Smithing table color",
			"Smithing tables will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include smithing tables", true));
	private final PortalEspBlockGroup anvils =
		new PortalEspBlockGroup(Blocks.ANVIL,
			new ColorSetting("Anvil color",
				"Anvils (all states) will be highlighted in this color.",
				defaultColor),
			new CheckboxSetting("Include anvils", true));
	private final PortalEspBlockGroup chippedAnvils = new PortalEspBlockGroup(
		Blocks.CHIPPED_ANVIL,
		new ColorSetting("Chipped anvil color",
			"Chipped anvils will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include chipped anvils", true));
	private final PortalEspBlockGroup damagedAnvils = new PortalEspBlockGroup(
		Blocks.DAMAGED_ANVIL,
		new ColorSetting("Damaged anvil color",
			"Damaged anvils will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include damaged anvils", true));
	private final PortalEspBlockGroup grindstone =
		new PortalEspBlockGroup(Blocks.GRINDSTONE,
			new ColorSetting("Grindstone color",
				"Grindstones will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include grindstones", true));
	private final PortalEspBlockGroup enchanting =
		new PortalEspBlockGroup(Blocks.ENCHANTING_TABLE,
			new ColorSetting("Enchanting table color",
				"Enchanting tables will be highlighted in this color.",
				defaultColor),
			new CheckboxSetting("Include enchanting tables", true));
	private final PortalEspBlockGroup cartography =
		new PortalEspBlockGroup(Blocks.CARTOGRAPHY_TABLE,
			new ColorSetting("Cartography table color",
				"Cartography tables will be highlighted in this color.",
				defaultColor),
			new CheckboxSetting("Include cartography tables", true));
	private final PortalEspBlockGroup stonecutter = new PortalEspBlockGroup(
		Blocks.STONECUTTER,
		new ColorSetting("Stonecutter color",
			"Stonecutters will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include stonecutters", true));
	private final PortalEspBlockGroup loom =
		new PortalEspBlockGroup(Blocks.LOOM,
			new ColorSetting("Loom color",
				"Looms will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include looms", true));
	private final PortalEspBlockGroup furnace =
		new PortalEspBlockGroup(Blocks.FURNACE,
			new ColorSetting("Furnace color",
				"Furnaces will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include furnaces", true));
	private final PortalEspBlockGroup blastFurnace = new PortalEspBlockGroup(
		Blocks.BLAST_FURNACE,
		new ColorSetting("Blast furnace color",
			"Blast furnaces will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include blast furnaces", true));
	private final PortalEspBlockGroup smoker =
		new PortalEspBlockGroup(Blocks.SMOKER,
			new ColorSetting("Smoker color",
				"Smokers will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include smokers", true));
	private final PortalEspBlockGroup campfire =
		new PortalEspBlockGroup(Blocks.CAMPFIRE,
			new ColorSetting("Campfire color",
				"Campfires will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include campfires", true));
	private final PortalEspBlockGroup soulCampfire = new PortalEspBlockGroup(
		Blocks.SOUL_CAMPFIRE,
		new ColorSetting("Soul campfire color",
			"Soul campfires will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include soul campfires", true));
	private final PortalEspBlockGroup brewingStand = new PortalEspBlockGroup(
		Blocks.BREWING_STAND,
		new ColorSetting("Brewing stand color",
			"Brewing stands will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include brewing stands", true));
	private final PortalEspBlockGroup cauldron =
		new PortalEspBlockGroup(Blocks.CAULDRON,
			new ColorSetting("Cauldron color",
				"Cauldrons will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include cauldrons", true));
	private final PortalEspBlockGroup barrel =
		new PortalEspBlockGroup(Blocks.BARREL,
			new ColorSetting("Barrel color",
				"Barrels will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include barrels", true));
	private final PortalEspBlockGroup composter =
		new PortalEspBlockGroup(Blocks.COMPOSTER,
			new ColorSetting("Composter color",
				"Composters will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include composters", true));
	private final PortalEspBlockGroup lectern =
		new PortalEspBlockGroup(Blocks.LECTERN,
			new ColorSetting("Lectern color",
				"Lecterns will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include lecterns", true));
	private final PortalEspBlockGroup fletchingTable =
		new PortalEspBlockGroup(Blocks.FLETCHING_TABLE,
			new ColorSetting("Fletching table color",
				"Fletching tables will be highlighted in this color.",
				defaultColor),
			new CheckboxSetting("Include fletching tables", true));
	private final PortalEspBlockGroup beacon =
		new PortalEspBlockGroup(Blocks.BEACON,
			new ColorSetting("Beacon color",
				"Beacons will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include beacons", true));
	private final List<PortalEspBlockGroup> groups = Arrays.asList(
		craftingTable, smithingTable, anvils, chippedAnvils, damagedAnvils,
		grindstone, enchanting, cartography, stonecutter, loom, furnace,
		blastFurnace, smoker, campfire, soulCampfire, brewingStand, cauldron,
		barrel, composter, lectern, fletchingTable, beacon);
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> isTargetBlock(state.getBlock());
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	private boolean groupsUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	private int foundCount;
	private int lastMatchesVersion;
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of found workstation blocks to this hack's entry in the HackList.",
		false);
	
	// Above-ground filter
	private final CheckboxSetting onlyAboveGround = new CheckboxSetting(
		"Above ground only",
		"Only show workstations at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	
	// Global color override
	private final CheckboxSetting useFixedColor = new CheckboxSetting(
		"Use fixed color",
		"When enabled, all workstation ESP boxes use the fixed color below, overriding per-block colors.",
		false);
	private final ColorSetting fixedColor = new ColorSetting("Fixed color",
		"Global override color for workstation ESP.", defaultColor);
	
	public WorkstationEspHack()
	{
		super("WorkstationESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(showCountInHackList);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(useFixedColor);
		addSetting(fixedColor);
		addSetting(area);
		addSetting(stickyArea);
		groups.stream().flatMap(PortalEspBlockGroup::getSettings)
			.forEach(this::addSetting);
	}
	
	private boolean isTargetBlock(Block b)
	{
		for(PortalEspBlockGroup g : groups)
			if(g.getBlock() == b)
				return true;
		return false;
	}
	
	@Override
	protected void onEnable()
	{
		groupsUpToDate = false;
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.blockPosition());
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
		lastMatchesVersion = coordinator.getMatchesVersion();
		groups.forEach(PortalEspBlockGroup::clear);
		foundCount = 0;
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
		ChunkPos currentChunk = new ChunkPos(MC.player.blockPosition());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			groupsUpToDate = false;
		}
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			groupsUpToDate = false;
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion != lastMatchesVersion)
		{
			lastMatchesVersion = matchesVersion;
			groupsUpToDate = false;
		}
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
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(style.getSelected().hasBoxes())
			renderBoxes(matrixStack);
		if(style.getSelected().hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(PoseStack matrixStack)
	{
		for(PortalEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			List<AABB> boxes = group.getBoxes();
			int quadsColor = useFixedColor.isChecked()
				? fixedColor.getColorI(0x40) : group.getColorI(0x40);
			int linesColor = useFixedColor.isChecked()
				? fixedColor.getColorI(0x80) : group.getColorI(0x80);
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
		}
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks)
	{
		for(PortalEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			List<AABB> boxes = group.getBoxes();
			List<Vec3> ends = boxes.stream().map(AABB::getCenter).toList();
			int color = useFixedColor.isChecked() ? fixedColor.getColorI(0x80)
				: group.getColorI(0x80);
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
	}
	
	private void updateGroupBoxes()
	{
		groups.forEach(PortalEspBlockGroup::clear);
		coordinator.getMatches().forEach(this::addToGroupBoxes);
		groupsUpToDate = true;
		int total = groups.stream().mapToInt(g -> g.getBoxes().size()).sum();
		foundCount = Math.min(total, 999);
	}
	
	private void addToGroupBoxes(Result result)
	{
		if(onlyAboveGround.isChecked()
			&& result.pos().getY() < aboveGroundY.getValue())
			return;
		for(PortalEspBlockGroup group : groups)
			if(result.state().getBlock() == group.getBlock())
			{
				group.add(result.pos());
				break;
			}
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
}
