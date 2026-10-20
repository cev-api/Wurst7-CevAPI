/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Collections;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.HandNoClipTargeting;
import net.wurstclient.util.text.WText;

@SearchTags({"hand noclip", "hand no clip"})
public final class HandNoClipHack extends Hack
	implements GUIRenderListener, UpdateListener, RightClickListener
{
	private static final int WARNING_COLOR = 0xFFFF0000;
	private static final int WARNING_SIZE = 5;
	
	private final BlockListSetting blocks = new BlockListSetting("Blocks",
		"The blocks you want to reach through walls.", "minecraft:barrel",
		"minecraft:black_shulker_box", "minecraft:blue_shulker_box",
		"minecraft:brown_shulker_box", "minecraft:chest",
		"minecraft:cyan_shulker_box", "minecraft:dispenser",
		"minecraft:dropper", "minecraft:ender_chest",
		"minecraft:gray_shulker_box", "minecraft:green_shulker_box",
		"minecraft:hopper", "minecraft:light_blue_shulker_box",
		"minecraft:light_gray_shulker_box", "minecraft:lime_shulker_box",
		"minecraft:magenta_shulker_box", "minecraft:orange_shulker_box",
		"minecraft:pink_shulker_box", "minecraft:purple_shulker_box",
		"minecraft:red_shulker_box", "minecraft:shulker_box",
		"minecraft:trapped_chest", "minecraft:white_shulker_box",
		"minecraft:yellow_shulker_box");
	private final CheckboxSetting enableOnTarget =
		new CheckboxSetting("Enable On Target",
			WText.literal("Only noclip while aiming at a listed block."), true);
	
	private final CheckboxSetting villagerThroughWalls = new CheckboxSetting(
		"Villagers Through Walls",
		WText.literal(
			"Allows interacting with villagers through walls when aiming at them."
				+ " Only works with Enable On Target."),
		true);
	
	private final CheckboxSetting boatsThroughWalls =
		new CheckboxSetting("Boats Through Walls",
			WText.literal(
				"Allows entering boats through walls when aiming at them."
					+ " Only works with Enable On Target."),
			true);
	
	private final CheckboxSetting bedrockPassthrough = new CheckboxSetting(
		"Bedrock Passthrough",
		WText.literal("When enabled, aiming through bedrock can target"
			+ " blocks behind it for breaking, interacting, and placement."),
		true);
	private final CheckboxSetting leadsThroughWalls = new CheckboxSetting(
		"Leads Through Walls",
		WText.literal(
			"Allows attaching and removing leads from animals and boats through walls."
				+ " Only works with Enable On Target."),
		true);
	
	private BlockPos targetBlock;
	private boolean targetVillager;
	private Boat targetBoat;
	private Entity targetLeashable;
	private Set<BlockPos> occludingBlocks = Collections.emptySet();
	
	public HandNoClipHack()
	{
		super("HandNoClip");
		
		setCategory(Category.BLOCKS);
		addSetting(blocks);
		addSetting(enableOnTarget);
		addSetting(villagerThroughWalls);
		addSetting(boatsThroughWalls);
		addSetting(leadsThroughWalls);
		addSetting(bedrockPassthrough);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(GUIRenderListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RightClickListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		clearTarget();
	}
	
	public boolean isBlockInList(BlockPos pos)
	{
		return blocks.matchesBlock(BlockUtils.getBlock(pos));
	}
	
	public boolean shouldClearBlock(BlockPos pos)
	{
		if(enableOnTarget.isChecked())
			return occludingBlocks.contains(pos);
		
		return !pos.equals(targetBlock) && !isBlockInList(pos);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
		{
			clearTarget();
			return;
		}
		
		updateTargeting();
	}
	
	private void clearTarget()
	{
		targetBlock = null;
		targetVillager = false;
		targetBoat = null;
		targetLeashable = null;
		occludingBlocks = Collections.emptySet();
	}
	
	private void updateTargeting()
	{
		Entity source = MC.getCameraEntity();
		if(source != null)
			updateTargeting(source.getEyePosition(1), source.getViewVector(1),
				MC.player.blockInteractionRange(),
				MC.player.entityInteractionRange());
	}
	
	public void updateTargeting(Vec3 eyes, Vec3 look, double blockReach,
		double entityReach)
	{
		clearTarget();
		if(!isEnabled()
			|| (!enableOnTarget.isChecked() && !bedrockPassthrough.isChecked())
			|| MC.player == null || MC.level == null)
			return;
		
		Vec3 entityEnd = eyes.add(look.scale(entityReach));
		double villagerDistance =
			enableOnTarget.isChecked() && villagerThroughWalls.isChecked()
				? getClosestVillagerHitDistance(eyes, entityEnd) : Double.NaN;
		EntityRayTarget boat =
			enableOnTarget.isChecked() && boatsThroughWalls.isChecked()
				? getClosestBoatHit(eyes, entityEnd) : null;
		LeashRayTarget leash =
			enableOnTarget.isChecked() && leadsThroughWalls.isChecked()
				? getClosestLeashTargetHit(eyes, entityEnd) : null;
		double entityDistance = Double.POSITIVE_INFINITY;
		if(!Double.isNaN(villagerDistance))
		{
			entityDistance = villagerDistance;
			targetVillager = true;
		}
		if(boat != null && boat.distance() < entityDistance)
		{
			entityDistance = boat.distance();
			targetVillager = false;
			targetBoat = boat.boat();
		}
		if(leash != null && leash.distance() <= entityDistance)
		{
			entityDistance = leash.distance();
			targetVillager = false;
			targetBoat = null;
			targetLeashable = leash.entity();
		}
		
		var result = HandNoClipTargeting.trace(MC.level, eyes, look, blockReach,
			entityDistance, state -> blocks.matchesBlock(state.getBlock()),
			bedrockPassthrough.isChecked());
		targetBlock = result.block();
		occludingBlocks = result.occludingBlocks();
		if(targetBlock != null)
		{
			targetVillager = false;
			targetBoat = null;
			targetLeashable = null;
		}
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(MC.player == null || MC.level == null || MC.gameMode == null)
			return;
		
		if(!enableOnTarget.isChecked())
			return;
		
		Entity leashTarget = getHoveredLeashTarget();
		if(leadsThroughWalls.isChecked() && leashTarget != null
			&& (!(leashTarget instanceof Boat)
				|| boatsThroughWalls.isChecked()))
		{
			if(MC.rightClickDelay > 0 || !MC.options.keyUse.isDown()
				|| MC.options.keyShift.isDown())
				return;
			Vec3 targetVec = leashTarget.getBoundingBox().getCenter();
			EntityHitResult hitResult =
				MC.hitResult instanceof EntityHitResult hit
					&& hit.getEntity() == leashTarget ? hit
						: new EntityHitResult(leashTarget, targetVec);
			WURST.getRotationFaker().faceVectorClient(targetVec);
			MC.rightClickDelay = 4;
			event.cancel();
			for(InteractionHand hand : InteractionHand.values())
			{
				InteractionResult result = MC.gameMode.interact(MC.player,
					leashTarget, hitResult, hand);
				if(result.consumesAction())
					MC.player.swing(hand,
						MC.player.getItemInHand(hand).getInteractAnimation(),
						false);
			}
			return;
		}
		
		if(!boatsThroughWalls.isChecked())
			return;
		
		Boat boat = getHoveredBoat();
		if(MC.rightClickDelay > 0 || !MC.options.keyUse.isDown() || boat == null
			|| MC.options.keyShift.isDown())
			return;
		
		Vec3 targetVec = getBoatTopHitVec(boat);
		EntityHitResult hitResult = MC.hitResult instanceof EntityHitResult hit
			&& hit.getEntity() == boat ? hit
				: new EntityHitResult(boat, targetVec);
		WURST.getRotationFaker().faceVectorClient(targetVec);
		MC.rightClickDelay = 4;
		event.cancel();
		
		for(InteractionHand hand : InteractionHand.values())
		{
			InteractionResult result =
				MC.gameMode.interact(MC.player, boat, hitResult, hand);
			if(result.consumesAction())
			{
				MC.player.swing(hand,
					MC.player.getItemInHand(hand).getInteractAnimation(),
					false);
				event.cancel();
			}
			
			if(MC.player.isPassenger())
			{
				event.cancel();
				return;
			}
		}
	}
	
	private Boat getHoveredBoat()
	{
		if(MC.hitResult instanceof EntityHitResult hit
			&& hit.getEntity() instanceof Boat boat && !boat.isRemoved())
		{
			return boat;
		}
		
		return targetBoat != null && !targetBoat.isRemoved() ? targetBoat
			: null;
	}
	
	private Entity getHoveredLeashTarget()
	{
		if(MC.hitResult instanceof EntityHitResult hit
			&& isLeashTarget(hit.getEntity()) && !hit.getEntity().isRemoved())
			return hit.getEntity();
		return targetLeashable != null && !targetLeashable.isRemoved()
			? targetLeashable : null;
	}
	
	private LeashRayTarget getClosestLeashTargetHit(Vec3 start, Vec3 end)
	{
		if(MC.level == null)
			return null;
		Vec3 direction = end.subtract(start);
		double maxDist = direction.length();
		if(maxDist <= 0)
			return null;
		Vec3 normalized = direction.scale(1.0 / maxDist);
		LeashRayTarget closest = null;
		for(Entity entity : MC.level.getEntities(MC.player,
			new AABB(start, end).inflate(1), this::isLeashTarget))
		{
			var hit = entity.getBoundingBox().clip(start, end);
			if(hit.isEmpty())
				continue;
			double distance = hit.get().subtract(start).dot(normalized);
			if(distance < 0 || distance > maxDist
				|| (closest != null && distance >= closest.distance()))
				continue;
			closest = new LeashRayTarget(entity, distance);
		}
		return closest;
	}
	
	private boolean isLeashTarget(Entity entity)
	{
		return entity != MC.player
			&& (entity instanceof Leashable || entity instanceof Boat);
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(MC.player == null || !shouldDrawWarningCrosshair())
			return;
		
		drawWarningCrosshair(context);
	}
	
	private boolean shouldDrawWarningCrosshair()
	{
		if(!enableOnTarget.isChecked())
			return true;
		
		return targetBlock != null || targetVillager || targetBoat != null
			|| targetLeashable != null;
	}
	
	private void drawWarningCrosshair(GuiGraphicsExtractor context)
	{
		int centerX = context.guiWidth() / 2;
		int centerY = context.guiHeight() / 2;
		
		for(int i = -WARNING_SIZE; i <= WARNING_SIZE; i++)
		{
			int x1 = centerX + i;
			int y1 = centerY + i;
			context.fill(x1, y1, x1 + 1, y1 + 1, WARNING_COLOR);
			
			int x2 = centerX + i;
			int y2 = centerY - i;
			context.fill(x2, y2, x2 + 1, y2 + 1, WARNING_COLOR);
		}
	}
	
	// See handnoclip.BlockStateBaseMixin.onGetShape()
	
	private double getClosestVillagerHitDistance(Vec3 start, Vec3 end)
	{
		if(MC.level == null)
			return Double.NaN;
		
		Vec3 dir = end.subtract(start);
		double maxDist = dir.length();
		if(maxDist <= 0)
			return Double.NaN;
		
		// Normalize for distance computation along the ray
		Vec3 dirNorm = dir.scale(1.0 / maxDist);
		
		AABB searchBox = new AABB(start, end).inflate(1);
		double closest = Double.NaN;
		for(var e : MC.level.getEntities(MC.player, searchBox,
			entity -> entity instanceof Villager))
		{
			if(!(e instanceof Villager vil) || vil.isRemoved()
				|| vil.getHealth() <= 0)
				continue;
			
			AABB box = vil.getBoundingBox();
			var opt = box.clip(start, end);
			if(opt.isEmpty())
				continue;
			
			Vec3 hit = opt.get();
			double dist = hit.subtract(start).dot(dirNorm);
			if(dist < 0 || dist > maxDist)
				continue;
			
			if(Double.isNaN(closest) || dist < closest)
				closest = dist;
		}
		
		return closest;
	}
	
	private EntityRayTarget getClosestBoatHit(Vec3 start, Vec3 end)
	{
		if(MC.level == null)
			return null;
		
		Vec3 dir = end.subtract(start);
		double maxDist = dir.length();
		if(maxDist <= 0)
			return null;
		
		Vec3 dirNorm = dir.scale(1.0 / maxDist);
		AABB searchBox = new AABB(start, end).inflate(1);
		EntityRayTarget closest = null;
		for(var e : MC.level.getEntities(MC.player, searchBox,
			entity -> entity instanceof Boat))
		{
			if(!(e instanceof Boat boat) || boat.isRemoved())
				continue;
			
			AABB box = boat.getBoundingBox();
			var opt = box.clip(start, end);
			if(opt.isEmpty())
				continue;
			
			Vec3 hit = opt.get();
			double dist = hit.subtract(start).dot(dirNorm);
			if(dist < 0 || dist > maxDist)
				continue;
			
			if(closest == null || dist < closest.distance())
				closest = new EntityRayTarget(boat, dist);
		}
		
		return closest;
	}
	
	private Vec3 getBoatTopHitVec(Boat boat)
	{
		AABB box = boat.getBoundingBox();
		return new Vec3((box.minX + box.maxX) * 0.5, box.maxY - 0.05,
			(box.minZ + box.maxZ) * 0.5);
	}
	
	private static record EntityRayTarget(Boat boat, double distance)
	{}
	
	private static record LeashRayTarget(Entity entity, double distance)
	{}
}
