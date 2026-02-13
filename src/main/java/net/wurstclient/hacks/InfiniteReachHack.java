/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.RenderUtils;

@SearchTags({"infinite reach", "teleport reach", "long reach",
	"through blocks"})
public final class InfiniteReachHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final double MACE_VERTICAL_LOCK_RANGE = 50.0;
	private static final double MACE_VERTICAL_LOCK_MIN_DOT = 0.6;
	
	private enum Mode
	{
		VANILLA("Vanilla"),
		PAPER("Paper");
		
		private final String name;
		
		Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final CheckboxSetting swingArm = new CheckboxSetting("Swing Arm",
		"Swing your main hand when Infinite Reach lands a hit.", true);
	
	private final CheckboxSetting homeTeleport = new CheckboxSetting(
		"Home Teleport",
		"Teleport back to where you started so the server never sees you there.",
		true);
	
	private final EnumSetting<Mode> mode = new EnumSetting<>(
		"Compatibility Mode",
		"Vanilla = 22 block reach with fewer packets. Paper = longer reach and more packets.",
		Mode.values(), Mode.VANILLA);
	
	private final CheckboxSetting clipUp = new CheckboxSetting("Clip Up",
		"Teleport a little higher before the attack so Paper servers can bypass obstacles.",
		true);
	
	private final SliderSetting vanillaPackets = new SliderSetting(
		"# Spam Packets (Vanilla)",
		"Amount of movement packets to fire before teleporting on vanilla servers.",
		4, 1, 5, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting vanillaDistance = new SliderSetting(
		"Max Distance (Vanilla)", "Maximum reach on vanilla servers.", 22, 1,
		22, 1, ValueDisplay.DECIMAL);
	
	private final SliderSetting paperPackets = new SliderSetting(
		"# Spam Packets (Paper)",
		"Amount of movement packets to fire before teleporting on paper servers.",
		7, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting paperDistance =
		new SliderSetting("Max Distance (Paper)",
			"Maximum reach on paper servers (requires more packets).", 59, 1,
			99, 1, ValueDisplay.DECIMAL);
	
	private final SliderSetting horizontalOffset = new SliderSetting(
		"Horizontal Offset",
		"Offset applied when returning so players do not stay exactly where they teleported.",
		0.05, 0.01, 0.99, 0.01, ValueDisplay.DECIMAL);
	
	private final SliderSetting verticalOffset = new SliderSetting("Y Offset",
		"Vertical offset applied when returning after each attack.", 0.01, 0.01,
		0.99, 0.01, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting onlyMace = new CheckboxSetting(
		"Only When Mace",
		"Only perform Infinite Reach when you are holding a mace and the player target is not blocking.",
		false);
	
	private final CheckboxSetting maceVerticalLock = new CheckboxSetting(
		"Mace Vertical Lock",
		"While holding right click with a mace, match your vertical movement to the locked player target.",
		false);
	
	private final CheckboxSetting skipCollisionCheck = new CheckboxSetting(
		"Boat Collision Skip",
		"While riding a boat only check for lava so the boat can clip through walls.",
		true);
	
	private final CheckboxSetting throughBlocks = new CheckboxSetting(
		"Through Blocks",
		"Skip the clear-path tests so Infinite Reach can attack through walls.",
		false);
	
	private final CheckboxSetting renderEntity = new CheckboxSetting(
		"Render Entity Box",
		"Render a box around the entity Infinity Reach is targeting.", true);
	
	private final ColorSetting entitySideColor =
		new ColorSetting("Entity Side Color", new Color(255, 0, 0, 40));
	
	private final ColorSetting entityLineColor =
		new ColorSetting("Entity Line Color", new Color(255, 0, 0, 120));
	
	private final CheckboxSetting renderBlock =
		new CheckboxSetting("Render Block Box",
			"Render a box around the block Infinity Reach is targeting.", true);
	
	private final ColorSetting blockSideColor =
		new ColorSetting("Block Side Color", new Color(255, 0, 255, 40));
	
	private final ColorSetting blockLineColor =
		new ColorSetting("Block Line Color", new Color(255, 0, 255, 120));
	
	private final SliderSetting blockAttackDelay =
		new SliderSetting("Mining Packet Delay",
			"Ticks between block attack packets sent by Infinite Reach.", 5, 1,
			20, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting itemUseDelay =
		new SliderSetting("Item Use Delay",
			"Ticks between block/item uses triggered by Infinite Reach.", 5, 1,
			20, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting entityAttackDelay =
		new SliderSetting("Attack Delay",
			"Ticks between entity attacks triggered by Infinite Reach.", 5, 1,
			20, 1, ValueDisplay.INTEGER);
	
	private Entity hoveredTarget;
	private BlockHitResult blockHit;
	private double maxDistance;
	private int blockAttackTicks;
	private boolean canBlockAttack = true;
	private int itemUseTicks;
	private boolean canItemUse = true;
	private int entityAttackTicks;
	private boolean canEntityAttack = true;
	private Vec3 startPos;
	private Vec3 finalPos;
	private Vec3 aboveself;
	private Vec3 abovetarget;
	private Vec3 blockfinalPos;
	private Vec3 blockaboveself;
	private Vec3 blockabovetarget;
	private Mode lastMode;
	
	public InfiniteReachHack()
	{
		super("InfiniteReach");
		setCategory(Category.COMBAT);
		
		addSetting(swingArm);
		addSetting(homeTeleport);
		addSetting(mode);
		addSetting(clipUp);
		addSetting(vanillaPackets);
		addSetting(vanillaDistance);
		addSetting(paperPackets);
		addSetting(paperDistance);
		addSetting(horizontalOffset);
		addSetting(verticalOffset);
		addSetting(onlyMace);
		addSetting(maceVerticalLock);
		addSetting(skipCollisionCheck);
		addSetting(throughBlocks);
		
		addSetting(renderEntity);
		addSetting(entitySideColor);
		addSetting(entityLineColor);
		addSetting(renderBlock);
		addSetting(blockSideColor);
		addSetting(blockLineColor);
		
		addSetting(blockAttackDelay);
		addSetting(itemUseDelay);
		addSetting(entityAttackDelay);
		
		lastMode = null;
	}
	
	@Override
	protected void onEnable()
	{
		updateVisibility();
		hoveredTarget = null;
		blockHit = null;
		blockfinalPos = null;
		finalPos = null;
		blockAttackTicks = itemUseTicks = entityAttackTicks = 0;
		canBlockAttack = canItemUse = canEntityAttack = true;
		maxDistance = mode.getSelected() == Mode.VANILLA
			? vanillaDistance.getValue() : paperDistance.getValue();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		hoveredTarget = null;
		blockHit = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.getConnection() == null)
			return;
		
		if(!isMaceAllowed())
		{
			hoveredTarget = null;
			blockHit = null;
			return;
		}
		
		if(MC.player.getVehicle() != null)
			return;
		
		tickDelays();
		updateVisibility();
		maxDistance = mode.getSelected() == Mode.VANILLA
			? vanillaDistance.getValue() : paperDistance.getValue();
		updateTargets();
		updateMaceVerticalLock();
		
		boolean attackPressed = MC.options.keyAttack.isDown();
		boolean usePressed = MC.options.keyUse.isDown();
		boolean usingItem = MC.player.isUsingItem();
		
		if(!attackPressed && !usePressed)
			return;
		
		BlockHitResult targetBhr =
			MC.hitResult instanceof BlockHitResult bhr ? bhr : null;
		
		if(hoveredTarget != null && attackPressed && canEntityAttack)
		{
			canEntityAttack = false;
			entityAttackTicks = 0;
			hitEntity(hoveredTarget, true);
		}else if(hoveredTarget != null && usePressed && canItemUse)
		{
			canItemUse = false;
			itemUseTicks = 0;
			hitEntity(hoveredTarget, false);
		}else if(blockHit != null && attackPressed && canBlockAttack)
		{
			canBlockAttack = false;
			blockAttackTicks = 0;
			hitBlock(blockHit, true);
		}else if(blockHit != null && usePressed && canItemUse && !usingItem)
		{
			canItemUse = false;
			itemUseTicks = 0;
			if(targetBhr == null
				|| MC.level.getBlockState(targetBhr.getBlockPos()).isAir())
			{
				hitBlock(blockHit, false);
			}
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null || MC.level == null)
			return;
		
		if(!isMaceAllowed())
			return;
		
		if(renderEntity.isChecked() && hoveredTarget != null)
		{
			AABB box = hoveredTarget.getBoundingBox();
			RenderUtils.drawSolidBox(matrixStack, box,
				entitySideColor.getColorI(), false);
			RenderUtils.drawOutlinedBox(matrixStack, box,
				entityLineColor.getColorI(), false);
		}
		
		if(renderBlock.isChecked() && blockHit != null)
		{
			AABB box = new AABB(blockHit.getBlockPos());
			RenderUtils.drawSolidBox(matrixStack, box,
				blockSideColor.getColorI(), false);
			RenderUtils.drawOutlinedBox(matrixStack, box,
				blockLineColor.getColorI(), false);
		}
	}
	
	private void tickDelays()
	{
		if(!canBlockAttack)
		{
			blockAttackTicks++;
			if(blockAttackTicks >= blockAttackDelay.getValueI())
			{
				canBlockAttack = true;
				blockAttackTicks = 0;
			}
		}
		
		if(!canItemUse)
		{
			itemUseTicks++;
			if(itemUseTicks >= itemUseDelay.getValueI())
			{
				canItemUse = true;
				itemUseTicks = 0;
			}
		}
		
		if(!canEntityAttack)
		{
			entityAttackTicks++;
			if(entityAttackTicks >= entityAttackDelay.getValueI())
			{
				canEntityAttack = true;
				entityAttackTicks = 0;
			}
		}
	}
	
	private void updateVisibility()
	{
		Mode selected = mode.getSelected();
		if(selected == lastMode)
			return;
		
		lastMode = selected;
		
		boolean paper = selected == Mode.PAPER;
		clipUp.setVisibleInGui(paper);
		paperPackets.setVisibleInGui(paper);
		paperDistance.setVisibleInGui(paper);
		vanillaPackets.setVisibleInGui(!paper);
		vanillaDistance.setVisibleInGui(!paper);
	}
	
	private void updateTargets()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		startPos = MC.player.position();
		finalPos = null;
		aboveself = null;
		abovetarget = null;
		blockfinalPos = null;
		blockaboveself = null;
		blockabovetarget = null;
		
		Vec3 cameraPos = MC.player.getEyePosition(1.0F);
		Vec3 view = MC.player.getViewVector(1.0F);
		Vec3 endVec = cameraPos.add(view.scale(maxDistance));
		
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
			MC.player, cameraPos, endVec,
			MC.player.getBoundingBox().inflate(maxDistance), e -> e.isAlive()
				&& e.isAttackable() && !e.isInvulnerable() && e != MC.player,
			maxDistance * maxDistance);
		
		BlockHitResult blockTarget =
			MC.level.clip(new ClipContext(cameraPos, endVec,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
		
		if(blockTarget.getType() == HitResult.Type.MISS
			|| MC.level.getBlockState(blockTarget.getBlockPos()).isAir())
		{
			blockTarget = null;
		}
		
		blockHit = blockTarget;
		
		if(entityHit != null)
		{
			hoveredTarget = entityHit.getEntity();
		}else
		{
			hoveredTarget = null;
		}
		
		if(blockTarget != null)
		{
			BlockPos targetActionPos =
				blockTarget.getBlockPos().relative(blockTarget.getDirection());
			AABB playerBox = MC.player.getBoundingBox();
			AABB targetActionBox = new AABB(targetActionPos);
			
			if(!playerBox.intersects(targetActionBox))
			{
				Vec3 blockTargetCenter =
					Vec3.atCenterOf(blockTarget.getBlockPos());
				AABB blockBox = new AABB(blockTarget.getBlockPos());
				Vec3 diff = startPos.subtract(blockTargetCenter);
				double horizontalSq = diff.x * diff.x + diff.z * diff.z;
				double flatUp = Math.sqrt(
					Math.max(0, maxDistance * maxDistance - horizontalSq));
				double targetUp = flatUp + diff.y;
				double yOffset = blockTargetCenter.y;
				
				Vec3 insideBlock =
					new Vec3(blockTargetCenter.x, yOffset, blockTargetCenter.z);
				
				blockfinalPos = findNearestPosBLOCK(insideBlock,
					targetActionPos, blockTarget);
				if(blockfinalPos != null)
				{
					blockaboveself = startPos.add(0, maxDistance, 0);
					blockabovetarget = blockfinalPos.add(0, targetUp, 0);
				}
			}
		}
		
		if(hoveredTarget != null)
		{
			Vec3 targetPos = hoveredTarget.position();
			Vec3 diff = startPos.subtract(targetPos);
			double horizontalSq = diff.x * diff.x + diff.z * diff.z;
			double flatUp = Math
				.sqrt(Math.max(0, maxDistance * maxDistance - horizontalSq));
			double targetUp = flatUp + diff.y;
			double yOffset = hoveredTarget.getBoundingBox().maxY + 0.3;
			
			Vec3 insideTarget = new Vec3(targetPos.x, yOffset, targetPos.z);
			
			finalPos = invalid(insideTarget) ? findNearestPos(insideTarget)
				: insideTarget;
			
			if(finalPos != null)
			{
				aboveself = startPos.add(0, maxDistance, 0);
				abovetarget = finalPos.add(0, targetUp, 0);
			}
		}
	}
	
	private void hitBlock(BlockHitResult hitResult, boolean attackPressed)
	{
		if(MC.player == null || MC.player.connection == null
			|| MC.level == null)
			return;
		
		if(blockfinalPos == null || blockaboveself == null
			|| blockabovetarget == null)
		{
			return;
		}
		
		if(invalid(blockfinalPos) || requiresPathCheck()
			&& (!hasClearPath(blockaboveself, blockabovetarget)
				|| invalid(blockaboveself) || invalid(blockabovetarget)))
		{
			return;
		}
		
		spamMovementPackets();
		
		if(mode.getSelected() == Mode.PAPER && clipUp.isChecked())
		{
			sendMove(blockaboveself);
			sendMove(blockabovetarget);
		}
		
		sendMove(blockfinalPos);
		
		if(!homeTeleport.isChecked())
			MC.player.setPos(blockfinalPos.x, blockfinalPos.y, blockfinalPos.z);
		
		if(attackPressed)
		{
			MC.player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
				hitResult.getBlockPos(), hitResult.getDirection(), 0));
			MC.player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
				hitResult.getBlockPos(), hitResult.getDirection(), 0));
		}else
		{
			InteractionSimulator.rightClickBlock(hitResult);
		}
		
		if(swingArm.isChecked())
			swingMainHand();
		
		if(homeTeleport.isChecked())
			returnHomeAfterBlock();
	}
	
	private void hitEntity(Entity target, boolean attackPressed)
	{
		if(MC.player == null || MC.player.connection == null)
			return;
		
		if(onlyMace.isChecked())
		{
			if(MC.player.getMainHandItem().getItem() != Items.MACE)
				return;
			
			if(target instanceof Player player && player.isBlocking())
				return;
		}
		
		if(startPos == null || finalPos == null || aboveself == null
			|| abovetarget == null)
		{
			return;
		}
		
		double actualDistance = startPos.distanceTo(target.position());
		if(actualDistance > maxDistance - 0.5)
			return;
		
		if(invalid(finalPos)
			|| requiresPathCheck() && (!hasClearPath(aboveself, abovetarget)
				|| invalid(aboveself) || invalid(abovetarget)))
		{
			return;
		}
		
		spamMovementPackets();
		
		if(mode.getSelected() == Mode.PAPER && clipUp.isChecked())
		{
			sendMove(aboveself);
			sendMove(abovetarget);
		}
		
		sendMove(finalPos);
		
		if(!homeTeleport.isChecked())
			MC.player.setPos(finalPos.x, finalPos.y, finalPos.z);
		
		if(attackPressed)
			MC.gameMode.attack(MC.player, target);
		else
			MC.gameMode.interact(MC.player, target, InteractionHand.MAIN_HAND);
		
		if(swingArm.isChecked())
			swingMainHand();
		
		if(homeTeleport.isChecked())
			returnHomeAfterEntity();
	}
	
	private void returnHomeAfterBlock()
	{
		if(mode.getSelected() == Mode.PAPER && clipUp.isChecked())
		{
			sendMove(blockabovetarget.add(0, 0.01, 0));
			sendMove(blockaboveself.add(0, 0.01, 0));
		}
		
		sendMove(startPos);
		Vec3 offset = getOffset(startPos);
		sendMove(offset);
		MC.player.setPos(offset.x, offset.y, offset.z);
	}
	
	private void returnHomeAfterEntity()
	{
		if(mode.getSelected() == Mode.PAPER && clipUp.isChecked())
		{
			sendMove(abovetarget.add(0, 0.01, 0));
			sendMove(aboveself.add(0, 0.01, 0));
		}
		
		sendMove(startPos);
		Vec3 offset = getOffset(startPos);
		sendMove(offset);
		MC.player.setPos(offset.x, offset.y, offset.z);
	}
	
	private boolean requiresPathCheck()
	{
		return !throughBlocks.isChecked() && mode.getSelected() == Mode.PAPER
			&& clipUp.isChecked();
	}
	
	private void spamMovementPackets()
	{
		int amount = mode.getSelected() == Mode.VANILLA
			? vanillaPackets.getValueI() : paperPackets.getValueI();
		
		for(int i = 0; i < amount; i++)
		{
			MC.player.connection.send(new ServerboundMovePlayerPacket.Pos(
				MC.player.getX(), MC.player.getY(), MC.player.getZ(), false,
				MC.player.horizontalCollision));
		}
	}
	
	private void sendMove(Vec3 pos)
	{
		if(MC.player == null || MC.player.connection == null)
			return;
		
		MC.player.connection
			.send(new ServerboundMovePlayerPacket.PosRot(pos.x, pos.y, pos.z,
				MC.player.getYRot(), MC.player.getXRot(), false, false));
	}
	
	private Vec3 getOffset(Vec3 base)
	{
		double dx = horizontalOffset.getValue();
		double dy = verticalOffset.getValue();
		
		List<Vec3> offsets = new ArrayList<>(Arrays.asList(base.add(dx, dy, 0),
			base.add(-dx, dy, 0), base.add(0, dy, dx), base.add(0, dy, -dx),
			base.add(dx, dy, dx), base.add(-dx, dy, -dx), base.add(-dx, dy, dx),
			base.add(dx, dy, -dx)));
		
		Collections.shuffle(offsets);
		
		for(Vec3 pos : offsets)
		{
			if(!invalid(pos))
				return pos;
		}
		
		Vec3 noHorizontal = base.add(0, dy, 0);
		if(!invalid(noHorizontal))
			return noHorizontal;
		
		return base;
	}
	
	private void swingMainHand()
	{
		if(MC.player == null || MC.player.connection == null)
			return;
		
		MC.player.connection
			.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
		MC.player.swing(InteractionHand.MAIN_HAND);
	}
	
	private boolean isHoldingMace()
	{
		if(MC.player == null)
			return false;
		
		return MC.player.getMainHandItem().getItem() == Items.MACE;
	}
	
	private boolean isMaceAllowed()
	{
		if(!onlyMace.isChecked())
			return true;
		
		return isHoldingMace();
	}
	
	private void updateMaceVerticalLock()
	{
		if(MC.player == null || MC.options == null
			|| !maceVerticalLock.isChecked() || !MC.options.keyUse.isDown()
			|| !isHoldingMace())
		{
			return;
		}
		
		Entity target = null;
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist != null && aimAssist.isEnabled()
			&& aimAssist.getCurrentTarget() instanceof Player playerTarget
			&& playerTarget != MC.player && playerTarget.isAlive()
			&& MC.player.distanceToSqr(playerTarget) <= MACE_VERTICAL_LOCK_RANGE
				* MACE_VERTICAL_LOCK_RANGE)
		{
			target = playerTarget;
		}
		
		if(target == null)
			target = findMaceVerticalLockTarget();
		
		if(target == null)
			return;
		
		double maxStep =
			Math.max(0.05, MC.player.getAbilities().getFlyingSpeed());
		double delta = target.getY() - MC.player.getY();
		if(Math.abs(delta) < 0.02)
			return;
		
		double step = Math.max(-maxStep, Math.min(maxStep, delta));
		Vec3 motion = MC.player.getDeltaMovement();
		MC.player.setDeltaMovement(motion.x, motion.y + step, motion.z);
	}
	
	private Player findMaceVerticalLockTarget()
	{
		if(MC.player == null || MC.level == null)
			return null;
		
		Vec3 eyePos = MC.player.getEyePosition(1.0F);
		Vec3 lookVec = MC.player.getLookAngle().normalize();
		double maxRangeSq = MACE_VERTICAL_LOCK_RANGE * MACE_VERTICAL_LOCK_RANGE;
		
		Player best = null;
		double bestDot = -1;
		double bestDistSq = Double.MAX_VALUE;
		Player nearest = null;
		double nearestDistSq = Double.MAX_VALUE;
		
		for(Player player : MC.level.players())
		{
			if(player == MC.player || !player.isAlive() || player.isSpectator())
				continue;
			
			Vec3 targetPos = player.getBoundingBox().getCenter();
			double distSq = eyePos.distanceToSqr(targetPos);
			if(distSq > maxRangeSq)
				continue;
			
			if(distSq < nearestDistSq)
			{
				nearest = player;
				nearestDistSq = distSq;
			}
			
			Vec3 toTarget = targetPos.subtract(eyePos);
			if(toTarget.lengthSqr() < 1.0E-6)
				continue;
			
			double dot = lookVec.dot(toTarget.normalize());
			if(dot < MACE_VERTICAL_LOCK_MIN_DOT)
				continue;
			
			if(dot > bestDot || (dot == bestDot && distSq < bestDistSq))
			{
				best = player;
				bestDot = dot;
				bestDistSq = distSq;
			}
		}
		
		return best != null ? best : nearest;
	}
	
	private Vec3 findNearestPos(Vec3 desired)
	{
		if(!invalid(desired))
			return desired;
		
		Vec3 best = null;
		double bestDist = Double.MAX_VALUE;
		
		for(int dx = -3; dx <= 3; dx++)
		{
			for(int dy = -3; dy <= 3; dy++)
			{
				for(int dz = -3; dz <= 3; dz++)
				{
					Vec3 test = desired.add(dx, dy, dz);
					if(invalid(test))
						continue;
					
					double dist = test.distanceToSqr(desired);
					if(dist < bestDist)
					{
						bestDist = dist;
						best = test;
					}
				}
			}
		}
		
		return best;
	}
	
	private Vec3 findNearestPosBLOCK(Vec3 preferredInsideTarget,
		BlockPos actionBlockPos, BlockHitResult blockHit)
	{
		BlockPos floored = BlockPos.containing(preferredInsideTarget.x,
			preferredInsideTarget.y, preferredInsideTarget.z);
		if(!invalid(preferredInsideTarget) && !floored.equals(actionBlockPos))
			return preferredInsideTarget;
		
		Vec3 best = null;
		double bestDist = Double.MAX_VALUE;
		
		for(int dx = -3; dx <= 3; dx++)
		{
			for(int dy = -3; dy <= 3; dy++)
			{
				for(int dz = -3; dz <= 3; dz++)
				{
					Vec3 test =
						preferredInsideTarget.add(dx * 0.4, dy * 0.4, dz * 0.4);
					BlockPos position =
						BlockPos.containing(test.x, test.y, test.z);
					
					if(position.equals(actionBlockPos))
						continue;
					
					if(invalid(test))
						continue;
					
					double dist = test.distanceToSqr(preferredInsideTarget);
					if(dist < bestDist)
					{
						bestDist = dist;
						best = test;
					}
				}
			}
		}
		
		if(best == null)
		{
			for(Direction dir : Direction.values())
			{
				if(dir == blockHit.getDirection())
					continue;
				
				Vec3 fallback = preferredInsideTarget.add(dir.getStepX() * 0.5,
					dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
				BlockPos fallbackPos =
					BlockPos.containing(fallback.x, fallback.y, fallback.z);
				
				if(fallbackPos.equals(actionBlockPos))
					continue;
				
				if(!invalid(fallback))
					return fallback;
			}
		}
		
		return best;
	}
	
	private boolean invalid(Vec3 pos)
	{
		if(MC.player == null || MC.level == null)
			return true;
		
		Entity entity = MC.player;
		AABB targetBox = entity.getBoundingBox().move(pos.x - entity.getX(),
			pos.y - entity.getY(), pos.z - entity.getZ());
		
		int minX = Mth.floor(targetBox.minX);
		int minY = Mth.floor(targetBox.minY);
		int minZ = Mth.floor(targetBox.minZ);
		int maxX = Mth.floor(targetBox.maxX);
		int maxY = Mth.floor(targetBox.maxY);
		int maxZ = Mth.floor(targetBox.maxZ);
		
		boolean skipShapes =
			skipCollisionCheck.isChecked() && entity instanceof Boat;
		
		for(int x = minX; x <= maxX; x++)
		{
			for(int y = minY; y <= maxY; y++)
			{
				for(int z = minZ; z <= maxZ; z++)
				{
					BlockPos blockPos = new BlockPos(x, y, z);
					BlockState state = MC.level.getBlockState(blockPos);
					if(state.is(Blocks.LAVA))
						return true;
					
					if(skipShapes)
						continue;
					
					if(!state.getCollisionShape(MC.level, blockPos).isEmpty())
						return true;
				}
			}
		}
		
		for(Entity other : MC.level.getEntities(entity, targetBox,
			Entity::isPickable))
		{
			if(other != entity)
				return true;
		}
		
		return false;
	}
	
	private boolean hasClearPath(Vec3 start, Vec3 end)
	{
		if(invalid(start) || invalid(end))
			return false;
		
		double distance = start.distanceTo(end);
		int steps = Math.max(10, (int)(distance * 2.5));
		for(int i = 1; i < steps; i++)
		{
			double t = i / (double)steps;
			Vec3 sample = new Vec3(start.x + (end.x - start.x) * t,
				start.y + (end.y - start.y) * t,
				start.z + (end.z - start.z) * t);
			if(invalid(sample))
				return false;
		}
		
		return true;
	}
}
