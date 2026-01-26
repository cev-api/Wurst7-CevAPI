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
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.player.Player;
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
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.RenderUtils;

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
	private final List<BlockPos> blocksBelowBedrock = new ArrayList<>();
	private static final int SHIFT_BREAK_COOLDOWN_TICKS = 6;
	private int shiftBreakCooldown;
	
	public BedrockEscapeHack()
	{
		super("BedrockEscape");
		setCategory(Category.MOVEMENT);
		
		addSetting(reach);
		addSetting(allowLiquids);
		addSetting(packetSpam);
		addSetting(render);
		addSetting(boxColor);
	}
	
	@Override
	protected void onEnable()
	{
		teleportedThisPress = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		teleportedThisPress = false;
	}
	
	@Override
	public void onUpdate()
	{
		
		if(MC.player == null || MC.level == null || MC.getConnection() == null)
			return;
		
		updateTarget();
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
		
		if(!isValidTarget)
			return;
		
		if(!showSafeTick)
			return;
		
		if(MC.options.keyAttack.isDown())
		{
			if(!teleportedThisPress)
			{
				performTeleport(teleportTarget);
				teleportedThisPress = true;
			}
		}else
		{
			teleportedThisPress = false;
		}
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(!render.isChecked() || !isValidTarget || targetBox == null)
			return;
		
		int color = boxColor.getColorI(80);
		int outline = boxColor.getColorI(255);
		
		RenderUtils.drawSolidBox(matrices, targetBox, color, false);
		RenderUtils.drawOutlinedBox(matrices, targetBox, outline, false);
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
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
			context.drawString(font, text, x, y, damageColor, true);
			
			if(showSafeTick)
			{
				context.drawString(font, "✔", x + textWidth + 6, y,
					SAFE_TICK_COLOR, true);
			}
		}else if(showSafeTick)
		{
			context.drawString(font, "✔", centerX, y, SAFE_TICK_COLOR, true);
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
}
