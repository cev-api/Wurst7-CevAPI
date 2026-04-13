/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;

@SearchTags({"bedrock", "void", "bedrock escape"})
public final class BedrockEscapeHack extends Hack
	implements UpdateListener, RenderListener, GUIRenderListener
{
	private final SliderSetting reach = new SliderSetting("Reach",
		"How far ahead to look for bedrock to tunnel through.", 48, 8, 96, 1,
		ValueDisplay.INTEGER);
	
	private final CheckboxSetting allowLiquids = new CheckboxSetting(
		"Allow Liquids", "Allow teleport targets inside liquids.", true);
	
	private final SliderSetting packetSpam = new SliderSetting(
		"Teleport Packets",
		"Movement packets to send before the teleport to stay under the radar.",
		4, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting render = new CheckboxSetting("Render Target",
		"Render a box around the landing zone.", true);
	
	private final CheckboxSetting renderEscapeShafts = new CheckboxSetting(
		"Render Escape Shafts",
		"Highlights bedrock columns that can be escaped by breaking blocks below.\n"
			+ "Green = no damage, Yellow = low survivable damage.",
		true);
	
	private final CheckboxSetting shaftFillBoxes = new CheckboxSetting(
		"Shaft fill boxes",
		"Render solid fills for shaft ESP. Disable for wireframe-only mode.",
		false);
	private final CheckboxSetting shaftSurfaceOnly = new CheckboxSetting(
		"Shaft surface-only markers",
		"Render a thin marker on the top surface of bedrock instead of full block markers.",
		true);
	
	private final ChunkAreaSetting shaftArea = new ChunkAreaSetting(
		"Shaft area", "Chunk range to scan for bedrock escape shafts.",
		ChunkAreaSetting.ChunkArea.A3);
	
	private final SliderSetting shaftChunksPerTick = new SliderSetting(
		"Shaft chunks per tick",
		"How many chunks are scanned each tick for shaft ESP. Lower = better FPS.",
		1, 1, 8, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting shaftRenderLimit = new SliderSetting(
		"Shaft render limit", "Maximum number of shaft ESP boxes to render.",
		300, 50, 5000, 10, ValueDisplay.INTEGER);
	
	private final SliderSetting shaftDepth = new SliderSetting("Shaft depth",
		"How far below bedrock to scan when looking for escape paths.", 16, 4,
		64, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting lowDamageLimit =
		new SliderSetting("Low damage limit",
			"Maximum estimated damage (in hearts) for yellow escape shafts.",
			3.0, 0.5, 10.0, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts"));
	
	private final ColorSetting safeShaftColor =
		new ColorSetting("Safe shaft color", new Color(60, 255, 100));
	private final ColorSetting lowDamageShaftColor =
		new ColorSetting("Low damage color", new Color(255, 235, 80));
	
	private final CheckboxSetting ignoreSafeTickRequirement =
		new CheckboxSetting("Teleport without Tick",
			"Allow teleporting even when the safe-tick indicator isn't showing.",
			false);
	
	private final CheckboxSetting shiftClickActivation = new CheckboxSetting(
		"Shift Click Activation",
		"Require holding shift to teleport when going down through bedrock.",
		false);
	
	private final CheckboxSetting autoSwitchTool = new CheckboxSetting(
		"Auto Switch Tool",
		"Automatically switches to the best tool (pickaxe) when breaking blocks"
			+ " below bedrock.",
		true);
	
	private final CheckboxSetting shiftSurfaceXray =
		new CheckboxSetting("Shift SurfaceXray",
			"Holding shift (or targeting bedrock above) temporarily enables"
				+ " SurfaceXray for bedrock only with 30% opacity.",
			false);
	
	private final ColorSetting boxColor =
		new ColorSetting("Bedrock Color", new Color(128, 0, 255));
	
	private static final Color DAMAGE_COLOR_SAFE = new Color(255, 255, 255);
	private static final Color DAMAGE_COLOR_WARN = new Color(255, 255, 0);
	private static final Color DAMAGE_COLOR_DEATH = new Color(255, 64, 64);
	private static final int SAFE_TICK_COLOR = 0xFF00FF00;
	private static final double VERTICAL_LOOK_THRESHOLD = 0.99995;
	
	private Vec3 teleportTarget;
	private AABB targetBox;
	private double damageHearts;
	private int damageColor = DAMAGE_COLOR_SAFE.getRGB();
	private boolean isValidTarget;
	private boolean teleportedThisPress;
	private boolean showSafeTick;
	private boolean targetBelow;
	private final List<BlockPos> blocksBelowBedrock = new ArrayList<>();
	private static final int SHIFT_BREAK_COOLDOWN_TICKS = 6;
	private static final String BEDROCK_BLOCK_ID = "minecraft:bedrock";
	private static final double SHIFT_SURFACE_XRAY_OPACITY = 0.3;
	private int shiftBreakCooldown;
	private boolean shiftSurfaceXrayApplied;
	private boolean surfaceXrayWasEnabled;
	private double surfaceXrayPreviousOpacity;
	private List<String> surfaceXrayPreviousBlocks = Collections.emptyList();
	private final ArrayDeque<ChunkPos> shaftScanQueue = new ArrayDeque<>();
	private final HashSet<ChunkPos> queuedShaftChunks = new HashSet<>();
	private final HashMap<ChunkPos, ArrayList<ShaftCandidate>> shaftsByChunk =
		new HashMap<>();
	private final ArrayList<ColoredBox> safeShaftBoxes = new ArrayList<>();
	private final ArrayList<ColoredBox> lowDamageShaftBoxes = new ArrayList<>();
	private ChunkPos lastShaftPlayerChunk;
	private ChunkAreaSetting.ChunkArea lastShaftAreaSelection;
	private int sideBoundaryY = Integer.MIN_VALUE;
	private boolean playerAboveSideBoundary;
	private boolean hasSideBoundary;
	private int foundSafeShafts;
	private int foundLowDamageShafts;
	
	public BedrockEscapeHack()
	{
		super("BedrockEscape");
		setCategory(Category.MOVEMENT);
		
		addSetting(reach);
		addSetting(allowLiquids);
		addSetting(packetSpam);
		addSetting(render);
		addSetting(renderEscapeShafts);
		addSetting(shaftFillBoxes);
		addSetting(shaftSurfaceOnly);
		addSetting(shaftArea);
		addSetting(shaftChunksPerTick);
		addSetting(shaftRenderLimit);
		addSetting(shaftDepth);
		addSetting(lowDamageLimit);
		addSetting(safeShaftColor);
		addSetting(lowDamageShaftColor);
		addSetting(boxColor);
		addSetting(ignoreSafeTickRequirement);
		addSetting(shiftClickActivation);
		addSetting(autoSwitchTool);
		addSetting(shiftSurfaceXray);
	}
	
	@Override
	protected void onEnable()
	{
		teleportedThisPress = false;
		shiftSurfaceXrayApplied = false;
		clearShaftScanState();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		restoreShiftSurfaceXrayOverride();
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		teleportedThisPress = false;
		clearShaftScanState();
	}
	
	@Override
	public void onUpdate()
	{
		
		if(MC.player == null || MC.level == null || MC.getConnection() == null)
		{
			restoreShiftSurfaceXrayOverride();
			return;
		}
		
		updateTarget();
		updateShiftSurfaceXrayOverride();
		updateEscapeShafts();
		
		if(!isValidTarget)
			return;
		
		if(!showSafeTick && !ignoreSafeTickRequirement.isChecked())
			return;
		
		boolean shiftOk = !targetBelow || !shiftClickActivation.isChecked()
			|| MC.options.keyShift.isDown();
		boolean wantsTeleport = MC.options.keyAttack.isDown() && shiftOk;
		if(wantsTeleport)
		{
			if(!teleportedThisPress)
			{
				performTeleport(teleportTarget);
				teleportedThisPress = true;
			}
			return;
		}
		
		teleportedThisPress = false;
		
		boolean breakingBelowBedrock =
			isValidTarget && MC.options.keyShift.isDown();
		if(breakingBelowBedrock)
		{
			if(shiftBreakCooldown <= 0)
			{
				breakBlocksBelowBedrock();
				shiftBreakCooldown = SHIFT_BREAK_COOLDOWN_TICKS;
			}else
			{
				shiftBreakCooldown--;
			}
			
			return;
		}
		
		shiftBreakCooldown = 0;
	}
	
	private void updateShiftSurfaceXrayOverride()
	{
		// Shift SurfaceXray is intended for bedrock roof/floor workflows, not
		// End traversal where bedrock structures are common and can cause
		// noisy auto-triggering.
		if(MC.level != null && MC.level.dimension() == Level.END)
		{
			restoreShiftSurfaceXrayOverride();
			return;
		}
		
		boolean nearVerticalLook = false;
		if(MC.player != null)
		{
			double lookY = MC.player.getViewVector(1.0F).normalize().y;
			nearVerticalLook = Math.abs(lookY) >= VERTICAL_LOOK_THRESHOLD;
		}
		
		boolean shiftInBedrockContext =
			MC.options.keyShift.isDown() && isInBedrockContext();
		boolean autoWhenTargetAbove =
			isValidTarget && !targetBelow && nearVerticalLook;
		boolean shouldApply = shiftSurfaceXray.isChecked()
			&& (shiftInBedrockContext || autoWhenTargetAbove);
		
		if(shouldApply)
			applyShiftSurfaceXrayOverride();
		else
			restoreShiftSurfaceXrayOverride();
	}
	
	private boolean isInBedrockContext()
	{
		if(MC.player == null || MC.level == null)
			return false;
		
		BlockPos playerPos = MC.player.blockPosition();
		if(MC.level.getBlockState(playerPos.below()).is(Blocks.BEDROCK))
			return true;
		
		for(int y = 0; y <= 3; y++)
			if(MC.level.getBlockState(playerPos.above(y)).is(Blocks.BEDROCK))
				return true;
			
		return false;
	}
	
	private void applyShiftSurfaceXrayOverride()
	{
		if(shiftSurfaceXrayApplied)
			return;
		
		SurfaceXrayHack surfaceXray = WURST.getHax().surfaceXrayHack;
		if(surfaceXray == null)
			return;
		
		shiftSurfaceXrayApplied = true;
		surfaceXrayWasEnabled = surfaceXray.isEnabled();
		surfaceXrayPreviousOpacity = surfaceXray.getConfiguredSurfaceOpacity();
		surfaceXrayPreviousBlocks = surfaceXray.getTrackedBlockNamesSnapshot();
		
		surfaceXray.setTrackedBlocksTemporarily(
			Collections.singletonList(BEDROCK_BLOCK_ID));
		surfaceXray.setSurfaceOpacityTemporarily(SHIFT_SURFACE_XRAY_OPACITY);
		
		if(!surfaceXrayWasEnabled)
			surfaceXray.setEnabled(true);
	}
	
	private void restoreShiftSurfaceXrayOverride()
	{
		if(!shiftSurfaceXrayApplied)
			return;
		
		SurfaceXrayHack surfaceXray = WURST.getHax().surfaceXrayHack;
		if(surfaceXray != null)
		{
			surfaceXray.setTrackedBlocksTemporarily(surfaceXrayPreviousBlocks);
			surfaceXray
				.setSurfaceOpacityTemporarily(surfaceXrayPreviousOpacity);
			
			if(!surfaceXrayWasEnabled && surfaceXray.isEnabled())
				surfaceXray.setEnabled(false);
		}
		
		shiftSurfaceXrayApplied = false;
		surfaceXrayPreviousBlocks = Collections.emptyList();
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		renderEscapeShaftHighlights(matrices);
		
		if(!render.isChecked() || !isValidTarget || targetBox == null)
			return;
		
		int color = boxColor.getColorI(80);
		int outline = boxColor.getColorI(255);
		
		RenderUtils.drawSolidBox(matrices, targetBox, color, false);
		RenderUtils.drawOutlinedBox(matrices, targetBox, outline, false);
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!isValidTarget || teleportTarget == null
			|| (!showSafeTick && damageHearts <= 0))
		{
			return;
		}
		
		Font font = MC.font;
		int centerX = context.guiWidth() / 2;
		int y = context.guiHeight() / 2 + 10;
		
		if(damageHearts > 0)
		{
			String text = String.format("≈%.1f♥", damageHearts);
			int textWidth = font.width(text);
			int x = centerX - textWidth / 2;
			context.text(font, text, x, y, damageColor, true);
			
			if(showSafeTick)
			{
				context.text(font, "✔", x + textWidth + 6, y, SAFE_TICK_COLOR,
					true);
			}
		}else if(showSafeTick)
		{
			context.text(font, "✔", centerX, y, SAFE_TICK_COLOR, true);
		}
		
	}
	
	private void updateTarget()
	{
		teleportTarget = null;
		targetBox = null;
		damageHearts = 0;
		isValidTarget = false;
		showSafeTick = false;
		
		if(MC.player == null || MC.level == null)
			return;
		
		Vec3 start = MC.player.getEyePosition(1.0F);
		Vec3 direction = MC.player.getViewVector(1.0F).normalize();
		double maxReach = reach.getValue();
		double step = 0.25;
		Vec3 sample = start.add(direction.scale(step));
		double traveled = step;
		boolean inBedrock = false;
		Vec3 fallback = null;
		BlockPos lastBreakable = null;
		blocksBelowBedrock.clear();
		
		while(traveled <= maxReach)
		{
			BlockPos candidate = BlockPos.containing(sample);
			BlockState state = MC.level.getBlockState(candidate);
			
			if(state.is(Blocks.BEDROCK))
			{
				inBedrock = true;
				blocksBelowBedrock.clear();
				lastBreakable = null;
			}else if(inBedrock)
			{
				if(!state.isAir() && !state.is(Blocks.BEDROCK))
				{
					if(lastBreakable == null
						|| !lastBreakable.equals(candidate))
					{
						blocksBelowBedrock.add(candidate);
						lastBreakable = candidate;
					}
				}
				
				if(isSafeLanding(candidate))
				{
					teleportTarget = getAirTarget(candidate);
					break;
				}
				if(fallback == null && isAirLike(state)
					&& isFluidClear(candidate))
				{
					fallback = getAirTarget(candidate);
				}
			}
			
			sample = sample.add(direction.scale(step));
			traveled += step;
		}
		
		if(teleportTarget == null)
		{
			if(fallback == null || !inBedrock)
				return;
			teleportTarget = fallback;
		}
		
		isValidTarget = inBedrock;
		float playerHearts = getPlayerHearts();
		double dropDistance = MC.player.getY() - teleportTarget.y;
		boolean facingDown = direction.y <= -VERTICAL_LOOK_THRESHOLD;
		boolean facingUp = direction.y >= VERTICAL_LOOK_THRESHOLD;
		boolean isVertical = facingDown || facingUp;
		
		if(dropDistance > 0)
		{
			double raw = Math.max(0, dropDistance - 3.0);
			damageHearts = Math.floor(raw) / 2.0;
			damageColor = getDamageTextColor(playerHearts, damageHearts);
		}else
		{
			damageHearts = 0;
			damageColor = DAMAGE_COLOR_SAFE.getRGB();
		}
		
		boolean safeFromDamage =
			dropDistance <= 0 || playerHearts >= damageHearts;
		boolean correctOrientation = dropDistance > 0 ? facingDown : facingUp;
		showSafeTick =
			isValidTarget && safeFromDamage && isVertical && correctOrientation;
		targetBelow = dropDistance > 0;
		
		targetBox = new AABB(teleportTarget.x - 0.5, teleportTarget.y,
			teleportTarget.z - 0.5, teleportTarget.x + 0.5,
			teleportTarget.y + 1.9, teleportTarget.z + 0.5);
	}
	
	private boolean isSafeLanding(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		
		BlockState first = MC.level.getBlockState(pos);
		BlockState second = MC.level.getBlockState(pos.above());
		
		if(!isFluidClear(pos))
			return false;
		
		return isAirLike(first) && isAirLike(second);
	}
	
	private boolean isAirLike(BlockState state)
	{
		return state.isAir() || state.canBeReplaced();
	}
	
	private boolean isFluidClear(BlockPos pos)
	{
		if(allowLiquids.isChecked())
			return true;
		
		if(MC.level == null)
			return false;
		
		BlockState first = MC.level.getBlockState(pos);
		BlockState second = MC.level.getBlockState(pos.above());
		return first.getFluidState().isEmpty()
			&& second.getFluidState().isEmpty();
	}
	
	private Vec3 getAirTarget(BlockPos pos)
	{
		return new Vec3(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5);
	}
	
	private void breakBlocksBelowBedrock()
	{
		if(blocksBelowBedrock.isEmpty() || MC.player == null
			|| MC.player.connection == null)
		{
			return;
		}
		
		if(autoSwitchTool.isChecked())
		{
			BlockPos target = null;
			for(BlockPos pos : blocksBelowBedrock)
			{
				if(MC.level != null && !MC.level.getBlockState(pos).isAir())
				{
					target = pos;
					break;
				}
			}
			if(target != null)
			{
				net.wurstclient.hacks.AutoToolHack autoTool =
					WURST.getHax().autoToolHack;
				if(autoTool != null)
				{
					if(autoTool.isEnabled())
						autoTool.equipIfEnabled(target);
					else
						autoTool.equipBestTool(target, true, true, 0);
				}
			}
		}
		
		BlockBreaker.breakBlocksWithPacketSpam(blocksBelowBedrock);
		
		if(MC.level == null)
			return;
		
		int total = blocksBelowBedrock.size();
		int air = 0;
		for(BlockPos pos : blocksBelowBedrock)
		{
			if(MC.level.getBlockState(pos).isAir())
				air++;
		}
		
	}
	
	private void performTeleport(Vec3 destination)
	{
		if(MC.player == null || MC.player.connection == null
			|| destination == null)
			return;
		
		int packets = packetSpam.getValueI();
		for(int i = 0; i < packets; i++)
		{
			MC.player.connection.send(new ServerboundMovePlayerPacket.Pos(
				MC.player.getX(), MC.player.getY(), MC.player.getZ(),
				MC.player.onGround(), MC.player.horizontalCollision));
		}
		
		sendMove(destination);
		Player player = MC.player;
		player.setPos(destination.x, destination.y, destination.z);
		player.setDeltaMovement(Vec3.ZERO);
	}
	
	private void sendMove(Vec3 destination)
	{
		if(MC.player == null || MC.player.connection == null)
			return;
		
		MC.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
			destination.x, destination.y, destination.z, MC.player.getYRot(),
			MC.player.getXRot(), false, false));
	}
	
	private float getPlayerHearts()
	{
		if(MC.player == null)
			return 0f;
		
		float totalHealth =
			MC.player.getHealth() + MC.player.getAbsorptionAmount();
		return Math.max(0f, totalHealth) / 2.0f;
	}
	
	private int getDamageTextColor(float playerHearts, double heartsToTake)
	{
		if(heartsToTake <= 0 || playerHearts <= 0)
			return DAMAGE_COLOR_SAFE.getRGB();
		
		float ratio = (float)Math.min(heartsToTake / playerHearts, 1.0);
		if(ratio <= 0)
			return DAMAGE_COLOR_SAFE.getRGB();
		
		if(ratio < 0.5f)
		{
			return blendColor(DAMAGE_COLOR_SAFE, DAMAGE_COLOR_WARN,
				ratio / 0.5f);
		}
		
		return blendColor(DAMAGE_COLOR_WARN, DAMAGE_COLOR_DEATH,
			(ratio - 0.5f) / 0.5f);
	}
	
	private int blendColor(Color from, Color to, float t)
	{
		t = Math.max(0, Math.min(1, t));
		int r = (int)(from.getRed() + (to.getRed() - from.getRed()) * t);
		int g = (int)(from.getGreen() + (to.getGreen() - from.getGreen()) * t);
		int b = (int)(from.getBlue() + (to.getBlue() - from.getBlue()) * t);
		return (255 << 24) | (r << 16) | (g << 8) | b;
	}
	
	private void updateEscapeShafts()
	{
		if(!renderEscapeShafts.isChecked() || MC.player == null
			|| MC.level == null)
		{
			clearShaftScanState();
			return;
		}
		
		ChunkPos currentChunk = MC.player.chunkPosition();
		ChunkAreaSetting.ChunkArea currentArea = shaftArea.getSelected();
		updateSideBoundary();
		if(lastShaftPlayerChunk == null
			|| !lastShaftPlayerChunk.equals(currentChunk)
			|| lastShaftAreaSelection != currentArea)
		{
			lastShaftPlayerChunk = currentChunk;
			lastShaftAreaSelection = currentArea;
			rebuildShaftScanQueue();
		}
		
		if(shaftScanQueue.isEmpty())
			rebuildShaftScanQueue();
		
		for(int i = 0; i < shaftChunksPerTick.getValueI()
			&& !shaftScanQueue.isEmpty(); i++)
			scanShaftChunk(shaftScanQueue.removeFirst());
		
		rebuildShaftRenderCache();
	}
	
	private void renderEscapeShaftHighlights(PoseStack matrices)
	{
		if(!renderEscapeShafts.isChecked())
			return;
		if(safeShaftBoxes.isEmpty() && lowDamageShaftBoxes.isEmpty())
			return;
		
		List<ColoredBox> limitedSafe = limitBoxes(safeShaftBoxes);
		List<ColoredBox> limitedLow = limitBoxes(lowDamageShaftBoxes);
		
		if(!limitedSafe.isEmpty())
		{
			if(shaftFillBoxes.isChecked())
				RenderUtils.drawSolidBoxes(matrices, limitedSafe, false);
			RenderUtils.drawOutlinedBoxes(matrices, limitedSafe, false, 2.0);
		}
		
		if(!limitedLow.isEmpty())
		{
			if(shaftFillBoxes.isChecked())
				RenderUtils.drawSolidBoxes(matrices, limitedLow, false);
			RenderUtils.drawOutlinedBoxes(matrices, limitedLow, false, 2.0);
		}
	}
	
	private void rebuildShaftScanQueue()
	{
		shaftScanQueue.clear();
		queuedShaftChunks.clear();
		
		HashSet<ChunkPos> currentArea = new HashSet<>();
		for(var chunk : shaftArea.getChunksInRange())
		{
			ChunkPos pos = chunk.getPos();
			currentArea.add(pos);
			shaftScanQueue.addLast(pos);
			queuedShaftChunks.add(pos);
		}
		
		shaftsByChunk.keySet().removeIf(pos -> !currentArea.contains(pos));
	}
	
	private void scanShaftChunk(ChunkPos chunkPos)
	{
		if(MC.level == null || MC.player == null)
			return;
		
		ArrayList<ShaftCandidate> candidates = new ArrayList<>();
		int minY = MC.level.getMinY();
		int maxY = minY + MC.level.getHeight() - 1;
		int depthLimit = shaftDepth.getValueI();
		float playerHearts = getPlayerHearts();
		
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for(int x = chunkPos.getMinBlockX(); x <= chunkPos.getMaxBlockX(); x++)
			for(int z = chunkPos.getMinBlockZ(); z <= chunkPos
				.getMaxBlockZ(); z++)
			{
				for(int y = maxY - 1; y >= minY + 1; y--)
				{
					cursor.set(x, y, z);
					if(!MC.level.getBlockState(cursor).is(Blocks.BEDROCK))
						continue;
					tryAddShaftCandidate(candidates, x, y, z, minY, maxY,
						depthLimit, playerHearts, true);
					tryAddShaftCandidate(candidates, x, y, z, minY, maxY,
						depthLimit, playerHearts, false);
				}
			}
		
		shaftsByChunk.put(chunkPos, candidates);
	}
	
	private boolean hasBedrockWithinDepth(int x, int z, int startY, int minY,
		int depthLimit)
	{
		if(MC.level == null)
			return true;
		
		int endY = Math.max(minY, startY - depthLimit);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = startY; y >= endY; y--)
		{
			pos.set(x, y, z);
			if(!MC.level.hasChunkAt(pos))
				return true;
			if(MC.level.getBlockState(pos).is(Blocks.BEDROCK))
				return true;
		}
		
		return false;
	}
	
	private boolean isBreakableEscapeBlock(BlockState state)
	{
		if(state.is(Blocks.BEDROCK))
			return false;
		return state.getFluidState().isEmpty();
	}
	
	private void rebuildShaftRenderCache()
	{
		safeShaftBoxes.clear();
		lowDamageShaftBoxes.clear();
		foundSafeShafts = 0;
		foundLowDamageShafts = 0;
		
		ArrayList<ShaftCandidate> safeCandidates = new ArrayList<>();
		ArrayList<ShaftCandidate> lowCandidates = new ArrayList<>();
		
		for(ArrayList<ShaftCandidate> candidates : shaftsByChunk.values())
			for(ShaftCandidate candidate : candidates)
			{
				if(!isSideAllowed(candidate.fromAbove()))
					continue;
				
				if(candidate.safe())
				{
					safeCandidates.add(candidate);
				}else
				{
					lowCandidates.add(candidate);
				}
			}
		
		Comparator<ShaftCandidate> byDistance =
			Comparator.comparingDouble(this::distanceSqToPlayer)
				.thenComparingInt(c -> c.surfacePos().getX())
				.thenComparingInt(c -> c.surfacePos().getY())
				.thenComparingInt(c -> c.surfacePos().getZ());
		safeCandidates.sort(byDistance);
		lowCandidates.sort(byDistance);
		
		for(ShaftCandidate candidate : safeCandidates)
		{
			AABB markerBox = getShaftMarkerBox(candidate.surfacePos(),
				candidate.fromAbove());
			safeShaftBoxes
				.add(new ColoredBox(markerBox, safeShaftColor.getColorI(0xC0)));
			foundSafeShafts++;
		}
		
		for(ShaftCandidate candidate : lowCandidates)
		{
			AABB markerBox = getShaftMarkerBox(candidate.surfacePos(),
				candidate.fromAbove());
			lowDamageShaftBoxes.add(
				new ColoredBox(markerBox, lowDamageShaftColor.getColorI(0xB8)));
			foundLowDamageShafts++;
		}
	}
	
	private double distanceSqToPlayer(ShaftCandidate candidate)
	{
		if(MC.player == null)
			return Double.MAX_VALUE;
		
		double cx = candidate.surfacePos().getX() + 0.5;
		double cy = candidate.surfacePos().getY() + 0.5;
		double cz = candidate.surfacePos().getZ() + 0.5;
		return MC.player.distanceToSqr(cx, cy, cz);
	}
	
	private AABB getShaftMarkerBox(BlockPos surfacePos, boolean fromAbove)
	{
		double x1 = surfacePos.getX();
		double y1 = surfacePos.getY();
		double z1 = surfacePos.getZ();
		
		if(!shaftSurfaceOnly.isChecked())
			return new AABB(x1, y1, z1, x1 + 1, y1 + 1, z1 + 1);
		
		if(fromAbove)
		{
			// Thin top-face tile marker when escaping downward.
			return new AABB(x1 + 0.05, y1 + 0.98, z1 + 0.05, x1 + 0.95,
				y1 + 1.02, z1 + 0.95);
		}
		
		// Thin bottom-face tile marker when escaping upward from below bedrock.
		return new AABB(x1 + 0.05, y1 - 0.02, z1 + 0.05, x1 + 0.95, y1 + 0.02,
			z1 + 0.95);
	}
	
	private void clearShaftScanState()
	{
		shaftScanQueue.clear();
		queuedShaftChunks.clear();
		shaftsByChunk.clear();
		safeShaftBoxes.clear();
		lowDamageShaftBoxes.clear();
		lastShaftPlayerChunk = null;
		lastShaftAreaSelection = null;
		sideBoundaryY = Integer.MIN_VALUE;
		playerAboveSideBoundary = false;
		hasSideBoundary = false;
		foundSafeShafts = 0;
		foundLowDamageShafts = 0;
	}
	
	private List<ColoredBox> limitBoxes(ArrayList<ColoredBox> boxes)
	{
		int max = shaftRenderLimit.getValueI();
		if(boxes.size() <= max)
			return boxes;
		
		return boxes.subList(0, max);
	}
	
	private void tryAddShaftCandidate(ArrayList<ShaftCandidate> candidates,
		int x, int y, int z, int minY, int maxY, int depthLimit,
		float playerHearts, boolean fromAbove)
	{
		if(MC.level == null || MC.player == null)
			return;
		if(!isSideAllowed(fromAbove))
			return;
		
		BlockPos above = new BlockPos(x, y + 1, z);
		BlockPos below = new BlockPos(x, y - 1, z);
		boolean hasAbove = MC.level.hasChunkAt(above);
		boolean hasBelow = MC.level.hasChunkAt(below);
		if(!hasAbove)
			return;
		if(fromAbove && !hasBelow)
			return;
		
		int landingY;
		if(fromAbove)
		{
			if(!isAirLike(MC.level.getBlockState(above)))
				return;
			if(MC.level.getBlockState(below).is(Blocks.BEDROCK))
				return;
			if(hasBedrockWithinDepth(x, z, y - 1, minY, depthLimit))
				return;
			landingY =
				findBreakableTwoHighLandingYDown(x, y - 1, z, minY, depthLimit);
		}else
		{
			// Allow undersides at the build-limit boundary where "below" may
			// be outside loaded world space.
			if(hasBelow && !isAirLike(MC.level.getBlockState(below)))
				return;
			if(MC.level.getBlockState(above).is(Blocks.BEDROCK))
				return;
			if(hasBedrockWithinDepthUp(x, z, y + 1, maxY, depthLimit))
				return;
			landingY =
				findBreakableTwoHighLandingYUp(x, y + 1, z, maxY, depthLimit);
		}
		
		if(landingY == Integer.MIN_VALUE)
			return;
		
		double targetY = landingY + 0.1;
		double dropDistance = MC.player.getY() - targetY;
		double damage = 0;
		if(dropDistance > 0)
			damage = Math.floor(Math.max(0, dropDistance - 3.0)) / 2.0;
		
		if(playerHearts <= 0 || damage > playerHearts)
			return;
		
		boolean safe = damage <= 0;
		boolean low = !safe && damage <= lowDamageLimit.getValue();
		if(!safe && !low)
			return;
		
		candidates
			.add(new ShaftCandidate(new BlockPos(x, y, z), safe, fromAbove));
	}
	
	private int findBreakableTwoHighLandingYDown(int x, int startY, int z,
		int minY, int depthLimit)
	{
		if(MC.level == null)
			return Integer.MIN_VALUE;
		
		int endY = Math.max(minY, startY - depthLimit);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = startY - 1; y >= endY; y--)
		{
			pos.set(x, y, z);
			BlockState first = MC.level.getBlockState(pos);
			BlockState second = MC.level.getBlockState(pos.above());
			if(isBreakableEscapeBlock(first) && isBreakableEscapeBlock(second))
				return y;
		}
		
		return Integer.MIN_VALUE;
	}
	
	private int findBreakableTwoHighLandingYUp(int x, int startY, int z,
		int maxY, int depthLimit)
	{
		if(MC.level == null)
			return Integer.MIN_VALUE;
		
		int endY = Math.min(maxY - 1, startY + depthLimit);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = startY; y <= endY; y++)
		{
			pos.set(x, y, z);
			BlockState first = MC.level.getBlockState(pos);
			BlockState second = MC.level.getBlockState(pos.above());
			if(isBreakableEscapeBlock(first) && isBreakableEscapeBlock(second))
				return y;
		}
		
		return Integer.MIN_VALUE;
	}
	
	private boolean hasBedrockWithinDepthUp(int x, int z, int startY, int maxY,
		int depthLimit)
	{
		if(MC.level == null)
			return true;
		
		int endY = Math.min(maxY, startY + depthLimit);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = startY; y <= endY; y++)
		{
			pos.set(x, y, z);
			if(!MC.level.hasChunkAt(pos))
				return true;
			if(MC.level.getBlockState(pos).is(Blocks.BEDROCK))
				return true;
		}
		
		return false;
	}
	
	private void updateSideBoundary()
	{
		hasSideBoundary = false;
		if(MC.level == null || MC.player == null)
			return;
		
		int px = MC.player.getBlockX();
		int py = MC.player.getBlockY();
		int pz = MC.player.getBlockZ();
		int minY = MC.level.getMinY();
		int maxY = minY + MC.level.getHeight() - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		
		int aboveY = Integer.MIN_VALUE;
		for(int y = py; y <= maxY; y++)
		{
			pos.set(px, y, pz);
			if(MC.level.getBlockState(pos).is(Blocks.BEDROCK))
			{
				aboveY = y;
				break;
			}
		}
		
		int belowY = Integer.MIN_VALUE;
		for(int y = py; y >= minY; y--)
		{
			pos.set(px, y, pz);
			if(MC.level.getBlockState(pos).is(Blocks.BEDROCK))
			{
				belowY = y;
				break;
			}
		}
		
		boolean hasAbove = aboveY != Integer.MIN_VALUE;
		boolean hasBelow = belowY != Integer.MIN_VALUE;
		if(!hasAbove && !hasBelow)
			return;
		
		if(hasBelow && (!hasAbove || py - belowY <= aboveY - py))
		{
			sideBoundaryY = belowY;
			playerAboveSideBoundary = true;
		}else
		{
			sideBoundaryY = aboveY;
			playerAboveSideBoundary = false;
		}
		
		hasSideBoundary = true;
	}
	
	private boolean isSideAllowed(boolean fromAbove)
	{
		if(MC.level != null && MC.player != null
			&& MC.level.dimension() == Level.NETHER)
		{
			boolean onRoofSide = MC.player.getBlockY() >= 123;
			return fromAbove == onRoofSide;
		}
		
		if(!hasSideBoundary || MC.player == null)
			return true;
		
		if(fromAbove)
			return playerAboveSideBoundary;
		
		return !playerAboveSideBoundary;
	}
	
	private record ShaftCandidate(BlockPos surfacePos, boolean safe,
		boolean fromAbove)
	{}
}
