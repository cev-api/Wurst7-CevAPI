/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"pearl drop", "ender pearl drop", "stasis pearl",
	"bubble column pearl", "honey pearl", "pearl slow throw"})
public final class PearlDropHack extends Hack
	implements RightClickListener, UpdateListener, RenderListener
{
	private static final int RANGE = 8;
	private static final int MAX_SETUPS_TO_CHECK = 6;
	private static final int MAX_SIM_TICKS = 80;
	private static final long SOLVE_INTERVAL_MS = 45;
	
	private static final double PEARL_WIDTH = 0.25;
	private static final double PEARL_HEIGHT = 0.25;
	private static final double PEARL_RADIUS = PEARL_WIDTH / 2.0;
	private static final double PEARL_SPEED = 1.5;
	private static final double PEARL_DRAG = 0.99;
	private static final double PEARL_GRAVITY = 0.03;
	private static final double SEARCH_RANGE = 7.0;
	private static final double COARSE_STEP = 0.5;
	private static final double REFINE_RANGE = 0.8;
	private static final double REFINE_STEP = 0.1;
	private static final double COLUMN_CAPTURE_RADIUS = 0.28;
	private static final double HONEY_SHAPE_INSET = 0.0625;
	private static final double HONEY_SHAPE_TOP = 0.9375;
	
	private static final Direction[] HORIZONTAL_DIRECTIONS = {
		Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	
	private BlockPos nearestColumn;
	private BlockPos nearestHoney;
	private BlockPos readyColumn;
	private BlockPos readyHoney;
	private TrajectorySolution bestSolution;
	private TrajectorySolution activeSolution;
	private long lastSolveTime;
	
	public PearlDropHack()
	{
		super("PearlDrop");
		setCategory(Category.MOVEMENT);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		nearestColumn = null;
		nearestHoney = null;
		readyColumn = null;
		readyHoney = null;
		bestSolution = null;
		activeSolution = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		updateSolution(false);
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(MC.player == null || MC.level == null || MC.gameMode == null)
			return;
		if(MC.rightClickDelay > 0 || MC.screen != null)
			return;
		if(getPearlHand() == null)
			return;
		if(getStandingHoneyBlocks().isEmpty())
			return;
		
		updateSolution(true);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null || MC.level == null)
			return;
		if(getStandingHoneyBlocks().isEmpty())
			return;
		
		BlockPos column = readyColumn != null ? readyColumn : nearestColumn;
		BlockPos honey = readyHoney != null ? readyHoney : nearestHoney;
		if(column == null)
			return;
		
		boolean holdingPearl = getPearlHand() != null;
		boolean ready = activeSolution != null && holdingPearl;
		boolean hasSolution = bestSolution != null;
		
		int columnFill =
			ready ? 0x5022FF66 : hasSolution ? 0x50FFE066 : 0x50FF5555;
		int columnOutline =
			ready ? 0xE022FF66 : hasSolution ? 0xE0FFE066 : 0xE0FF5555;
		RenderUtils.drawSolidBox(matrixStack, new AABB(column), columnFill,
			false);
		RenderUtils.drawOutlinedBox(matrixStack, new AABB(column), columnOutline,
			false);
		
		if(honey != null)
		{
			int honeyFill =
				ready ? 0x5022FF66 : hasSolution ? 0x50FFAA22 : 0x50FF5555;
			int honeyOutline =
				ready ? 0xE022FF66 : hasSolution ? 0xE0FFAA22 : 0xE0FF5555;
			RenderUtils.drawSolidBox(matrixStack, new AABB(honey), honeyFill,
				false);
			RenderUtils.drawOutlinedBox(matrixStack, new AABB(honey),
				honeyOutline, false);
		}
		
		Vec3 labelPos = Vec3.atCenterOf(column).add(0, 0.95, 0);
		String text;
		int color;
		if(!holdingPearl)
		{
			text = "HOLD PEARL";
			color = 0xFFFF5555;
		}else if(ready)
		{
			text = "GOOD THROW";
			color = 0xFF55FF55;
		}else if(hasSolution)
		{
			text = "AIM HONEY EDGE";
			color = 0xFFFFFF55;
		}else if(honey == null)
		{
			text = "STAND ON HONEY";
			color = 0xFFFFFF55;
		}else
		{
			text = "NO VALID SLIDE";
			color = 0xFFFF5555;
		}
		
		drawWorldLabel(matrixStack, text, labelPos, color);
	}
	
	private void updateSolution(boolean force)
	{
		long now = System.currentTimeMillis();
		if(!force && now - lastSolveTime < SOLVE_INTERVAL_MS)
			return;
		
		lastSolveTime = now;
		nearestColumn = null;
		nearestHoney = null;
		readyColumn = null;
		readyHoney = null;
		bestSolution = null;
		activeSolution = null;
		
		ArrayList<BlockPos> standingHoneyBlocks = getStandingHoneyBlocks();
		if(standingHoneyBlocks.isEmpty())
			return;
		
		Vec3 eyes = RotationUtils.getEyesPos();
		BlockPos center = BlockPos.containing(MC.player.position());
		ArrayList<SetupCandidate> setups = new ArrayList<>();
		
		for(BlockPos pos : BlockUtils.getAllInBox(center, RANGE))
		{
			if(!isOneHighBubbleColumn(pos))
				continue;
			if(eyes.distanceTo(Vec3.atCenterOf(pos)) > RANGE)
				continue;
			
			for(Direction direction : HORIZONTAL_DIRECTIONS)
			{
				addSetupIfValid(setups, eyes, pos.immutable(),
					pos.relative(direction).immutable(), standingHoneyBlocks);
				addSetupIfValid(setups, eyes, pos.immutable(),
					pos.relative(direction).above().immutable(),
					standingHoneyBlocks);
			}
		}
		
		if(setups.isEmpty())
			return;
		
		setups.sort(Comparator.comparingDouble(setup -> setup.distanceSqr));
		nearestColumn = setups.get(0).column;
		nearestHoney = setups.get(0).honey;
		
		float currentYaw = MC.player.getYRot();
		float currentPitch = MC.player.getXRot();
		int checks = Math.min(MAX_SETUPS_TO_CHECK, setups.size());
		
		for(int i = 0; i < checks; i++)
		{
			SetupCandidate setup = setups.get(i);
			SimulationResult currentResult =
				simulateTrajectory(currentYaw, currentPitch, setup.column,
					setup.honey);
			
			if(currentResult != null)
			{
				TrajectorySolution solution = new TrajectorySolution(setup.column,
					setup.honey, currentYaw, currentPitch, currentResult.score,
					0);
				
				if(activeSolution == null || solution.score < activeSolution.score)
					activeSolution = solution;
			}
			
			TrajectorySolution nearbySolution = solveNearCurrentAim(setup.column,
				setup.honey, currentYaw, currentPitch);
			
			if(nearbySolution == null)
				continue;
			if(bestSolution == null || nearbySolution.score < bestSolution.score)
				bestSolution = nearbySolution;
		}
		
		if(activeSolution != null)
		{
			readyColumn = activeSolution.column;
			readyHoney = activeSolution.honey;
			return;
		}
		
		if(bestSolution != null)
		{
			readyColumn = bestSolution.column;
			readyHoney = bestSolution.honey;
		}
	}
	
	private void addSetupIfValid(ArrayList<SetupCandidate> setups, Vec3 eyes,
		BlockPos column, BlockPos honey, ArrayList<BlockPos> standingHoneyBlocks)
	{
		if(!isHoney(honey))
			return;
		if(!containsBlockPos(standingHoneyBlocks, honey))
			return;
		
		double distanceSqr = eyes.distanceToSqr(Vec3.atCenterOf(column));
		setups.add(new SetupCandidate(column, honey, distanceSqr));
	}
	
	private ArrayList<BlockPos> getStandingHoneyBlocks()
	{
		ArrayList<BlockPos> honeyBlocks = new ArrayList<>();
		if(MC.player == null || MC.level == null)
			return honeyBlocks;
		if(!MC.player.onGround())
			return honeyBlocks;
		
		AABB box = MC.player.getBoundingBox();
		double y = box.minY - 0.08;
		double inset = 0.001;
		double centerX = (box.minX + box.maxX) / 2.0;
		double centerZ = (box.minZ + box.maxZ) / 2.0;
		
		addHoneyBlockAt(honeyBlocks, centerX, y, centerZ);
		addHoneyBlockAt(honeyBlocks, box.minX + inset, y, box.minZ + inset);
		addHoneyBlockAt(honeyBlocks, box.minX + inset, y, box.maxZ - inset);
		addHoneyBlockAt(honeyBlocks, box.maxX - inset, y, box.minZ + inset);
		addHoneyBlockAt(honeyBlocks, box.maxX - inset, y, box.maxZ - inset);
		
		return honeyBlocks;
	}
	
	private void addHoneyBlockAt(ArrayList<BlockPos> honeyBlocks, double x,
		double y, double z)
	{
		BlockPos pos = BlockPos.containing(x, y, z);
		if(!isHoney(pos))
			return;
		if(containsBlockPos(honeyBlocks, pos))
			return;
		
		honeyBlocks.add(pos.immutable());
	}
	
	private boolean containsBlockPos(ArrayList<BlockPos> positions, BlockPos pos)
	{
		for(BlockPos current : positions)
		{
			if(current.equals(pos))
				return true;
		}
		
		return false;
	}
	
	private TrajectorySolution solveNearCurrentAim(BlockPos column, BlockPos honey,
		float currentYaw, float currentPitch)
	{
		SearchResult best = null;
		
		best = searchRotations(column, honey, currentYaw, currentPitch,
			currentYaw, currentPitch, SEARCH_RANGE, COARSE_STEP, best);
		
		if(best == null)
			return null;
		
		best = searchRotations(column, honey, currentYaw, currentPitch, best.yaw,
			best.pitch, REFINE_RANGE, REFINE_STEP, best);
		
		SimulationResult result =
			simulateTrajectory(best.yaw, best.pitch, column, honey);
		if(result == null)
			return null;
		
		double aimError =
			getAngleDifference(currentYaw, currentPitch, best.yaw, best.pitch);
		double score = result.score + aimError * 0.08;
		return new TrajectorySolution(column, honey, best.yaw, best.pitch, score,
			aimError);
	}
	
	private SearchResult searchRotations(BlockPos column, BlockPos honey,
		float currentYaw, float currentPitch, float centerYaw, float centerPitch,
		double range, double step, SearchResult best)
	{
		for(double yawOffset = -range; yawOffset <= range + 1.0E-7;
			yawOffset += step)
		{
			for(double pitchOffset = -range; pitchOffset <= range + 1.0E-7;
				pitchOffset += step)
			{
				float yaw = wrapDegrees((float)(centerYaw + yawOffset));
				float pitch = (float)(centerPitch + pitchOffset);
				
				if(pitch < -89.0F || pitch > 89.0F)
					continue;
				
				SimulationResult result =
					simulateTrajectory(yaw, pitch, column, honey);
				if(result == null)
					continue;
				
				double aimError =
					getAngleDifference(currentYaw, currentPitch, yaw, pitch);
				double score = result.score + aimError * 0.08;
				
				if(best == null || score < best.score)
					best = new SearchResult(yaw, pitch, score);
			}
		}
		
		return best;
	}
	
	private SimulationResult simulateTrajectory(float yaw, float pitch,
		BlockPos column, BlockPos honey)
	{
		Vec3 pos = RotationUtils.getEyesPos().subtract(0, 0.1, 0);
		Vec3 velocity =
			new Rotation(yaw, pitch).toLookVec().normalize().scale(PEARL_SPEED);
		
		AABB honeyCollision = getHoneyCollisionBox(honey);
		AABB soulSandBox = new AABB(column.below());
		boolean honeySlid = false;
		
		for(int tick = 0; tick < MAX_SIM_TICKS; tick++)
		{
			Vec3 next = pos.add(velocity);
			
			if(getSegmentBoxIntersection(pos, next, honeyCollision) != null)
				return null;
			if(getSegmentBoxIntersection(pos, next, soulSandBox) != null)
				return null;
			
			if(honeySlid)
			{
				CaptureResult capture =
					getColumnCapture(pos, next, velocity, column, tick);
				if(capture != null)
					return new SimulationResult(capture.score);
			}
			
			pos = next;
			
			if(applyHoneySlide(pos, velocity, honey, honeyCollision))
			{
				velocity = getHoneySlideVelocity(velocity);
				honeySlid = true;
			}
			
			velocity = velocity.scale(PEARL_DRAG).add(0, -PEARL_GRAVITY, 0);
		}
		
		return null;
	}
	
	private CaptureResult getColumnCapture(Vec3 start, Vec3 end, Vec3 velocity,
		BlockPos column, int tick)
	{
		AABB captureBox = new AABB(column.getX() + 0.5 - COLUMN_CAPTURE_RADIUS,
			column.getY() + 0.03,
			column.getZ() + 0.5 - COLUMN_CAPTURE_RADIUS,
			column.getX() + 0.5 + COLUMN_CAPTURE_RADIUS,
			column.getY() + 0.97,
			column.getZ() + 0.5 + COLUMN_CAPTURE_RADIUS);
		
		Double hit = getSegmentBoxIntersection(start, end, captureBox);
		if(hit == null)
			return null;
		
		Vec3 point = start.add(end.subtract(start).scale(hit));
		double centerX = column.getX() + 0.5;
		double centerZ = column.getZ() + 0.5;
		double dx = point.x - centerX;
		double dz = point.z - centerZ;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		double speedPenalty = Math.max(0, velocity.length() - 0.36) * 0.18;
		double verticalPenalty = Math.abs(point.y - (column.getY() + 0.45))
			* 0.08;
		double tickPenalty = tick * 0.0015;
		double score =
			horizontalDistance + speedPenalty + verticalPenalty + tickPenalty;
		
		return new CaptureResult(point, score);
	}
	
	private boolean applyHoneySlide(Vec3 pos, Vec3 velocity, BlockPos honey,
		AABB honeyCollision)
	{
		AABB pearlBox = getPearlBox(pos);
		AABB honeyTouchBox = new AABB(honey.getX(), honey.getY(),
			honey.getZ(), honey.getX() + 1, honey.getY() + HONEY_SHAPE_TOP,
			honey.getZ() + 1);
		
		if(!pearlBox.intersects(honeyTouchBox))
			return false;
		if(isPointInsideBox(pos, honeyCollision))
			return false;
		if(pos.y > honey.getY() + HONEY_SHAPE_TOP - 1.0E-7)
			return false;
		if(velocity.y >= -0.08)
			return false;
		
		double d = Math.abs(honey.getX() + 0.5 - pos.x);
		double e = Math.abs(honey.getZ() + 0.5 - pos.z);
		double f = 0.4375 + PEARL_WIDTH / 2.0;
		
		return d + 1.0E-7 > f || e + 1.0E-7 > f;
	}
	
	private Vec3 getHoneySlideVelocity(Vec3 velocity)
	{
		if(velocity.y < -0.13)
		{
			double scale = -0.05 / velocity.y;
			return new Vec3(velocity.x * scale, -0.05, velocity.z * scale);
		}
		
		return new Vec3(velocity.x, -0.05, velocity.z);
	}
	
	private boolean isOneHighBubbleColumn(BlockPos pos)
	{
		BlockState state = MC.level.getBlockState(pos);
		if(!(state.getBlock() instanceof BubbleColumnBlock))
			return false;
		
		BlockState below = MC.level.getBlockState(pos.below());
		if(!below.is(Blocks.SOUL_SAND) && !below.is(Blocks.SOUL_SOIL))
			return false;
		
		return !(MC.level.getBlockState(pos.above())
			.getBlock() instanceof BubbleColumnBlock);
	}
	
	private boolean isHoney(BlockPos pos)
	{
		return MC.level.getBlockState(pos).is(Blocks.HONEY_BLOCK);
	}
	
	private InteractionHand getPearlHand()
	{
		if(MC.player.getMainHandItem().is(Items.ENDER_PEARL))
			return InteractionHand.MAIN_HAND;
		if(MC.player.getOffhandItem().is(Items.ENDER_PEARL))
			return InteractionHand.OFF_HAND;
		
		return null;
	}
	
	private AABB getPearlBox(Vec3 pos)
	{
		return new AABB(pos.x - PEARL_RADIUS, pos.y,
			pos.z - PEARL_RADIUS, pos.x + PEARL_RADIUS,
			pos.y + PEARL_HEIGHT, pos.z + PEARL_RADIUS);
	}
	
	private AABB getHoneyCollisionBox(BlockPos honey)
	{
		return new AABB(honey.getX() + HONEY_SHAPE_INSET, honey.getY(),
			honey.getZ() + HONEY_SHAPE_INSET,
			honey.getX() + 1 - HONEY_SHAPE_INSET,
			honey.getY() + HONEY_SHAPE_TOP,
			honey.getZ() + 1 - HONEY_SHAPE_INSET);
	}
	
	private boolean isPointInsideBox(Vec3 point, AABB box)
	{
		return point.x > box.minX && point.x < box.maxX && point.y > box.minY
			&& point.y < box.maxY && point.z > box.minZ && point.z < box.maxZ;
	}
	
	private Double getSegmentBoxIntersection(Vec3 start, Vec3 end, AABB box)
	{
		Vec3 direction = end.subtract(start);
		double tMin = 0.0;
		double tMax = 1.0;
		
		double[] startValues = {start.x, start.y, start.z};
		double[] directionValues = {direction.x, direction.y, direction.z};
		double[] minValues = {box.minX, box.minY, box.minZ};
		double[] maxValues = {box.maxX, box.maxY, box.maxZ};
		
		for(int i = 0; i < 3; i++)
		{
			double axisStart = startValues[i];
			double axisDirection = directionValues[i];
			double axisMin = minValues[i];
			double axisMax = maxValues[i];
			
			if(Math.abs(axisDirection) < 1.0E-9)
			{
				if(axisStart < axisMin || axisStart > axisMax)
					return null;
				
				continue;
			}
			
			double inverse = 1.0 / axisDirection;
			double t1 = (axisMin - axisStart) * inverse;
			double t2 = (axisMax - axisStart) * inverse;
			
			if(t1 > t2)
			{
				double swap = t1;
				t1 = t2;
				t2 = swap;
			}
			
			tMin = Math.max(tMin, t1);
			tMax = Math.min(tMax, t2);
			
			if(tMin > tMax)
				return null;
		}
		
		return tMin;
	}
	
	private double getAngleDifference(float yawA, float pitchA, float yawB,
		float pitchB)
	{
		Vec3 lookA = new Rotation(yawA, pitchA).toLookVec().normalize();
		Vec3 lookB = new Rotation(yawB, pitchB).toLookVec().normalize();
		return Math.toDegrees(Math.acos(clamp(lookA.dot(lookB), -1, 1)));
	}
	
	private float wrapDegrees(float angle)
	{
		angle %= 360.0F;
		
		if(angle >= 180.0F)
			angle -= 360.0F;
		if(angle < -180.0F)
			angle += 360.0F;
		
		return angle;
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, Vec3 pos,
		int argb)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		
		matrices.mulPose(Axis.YP.rotationDegrees(180.0F));
		matrices.scale(0.025F, -0.025F, 0.025F);
		
		Font font = MC.font;
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		float w = font.width(text) / 2F;
		var matrix = matrices.last().pose();
		font.drawInBatch(text, -w, 0, argb, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0x80000000, 0xF000F0);
		vcp.endBatch();
		matrices.popPose();
	}
	
	private double clamp(double value, double min, double max)
	{
		return Math.max(min, Math.min(max, value));
	}
	
	private static final class SetupCandidate
	{
		private final BlockPos column;
		private final BlockPos honey;
		private final double distanceSqr;
		
		private SetupCandidate(BlockPos column, BlockPos honey,
			double distanceSqr)
		{
			this.column = column;
			this.honey = honey;
			this.distanceSqr = distanceSqr;
		}
	}
	
	private static final class TrajectorySolution
	{
		private final BlockPos column;
		private final BlockPos honey;
		private final float yaw;
		private final float pitch;
		private final double score;
		private final double aimError;
		
		private TrajectorySolution(BlockPos column, BlockPos honey, float yaw,
			float pitch, double score, double aimError)
		{
			this.column = column;
			this.honey = honey;
			this.yaw = yaw;
			this.pitch = pitch;
			this.score = score;
			this.aimError = aimError;
		}
	}
	
	private static final class SearchResult
	{
		private final float yaw;
		private final float pitch;
		private final double score;
		
		private SearchResult(float yaw, float pitch, double score)
		{
			this.yaw = yaw;
			this.pitch = pitch;
			this.score = score;
		}
	}
	
	private static final class SimulationResult
	{
		private final double score;
		
		private SimulationResult(double score)
		{
			this.score = score;
		}
	}
	
	private static final class CaptureResult
	{
		private final Vec3 point;
		private final double score;
		
		private CaptureResult(Vec3 point, double score)
		{
			this.point = point;
			this.score = score;
		}
	}
}