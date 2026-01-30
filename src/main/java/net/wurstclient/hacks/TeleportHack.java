/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyBinding;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.timer.TimerPriority;

@SearchTags({"teleport", "phase", "path"})
public final class TeleportHack extends Hack
	implements UpdateListener, RenderListener, GUIRenderListener
{
	private static final Color DAMAGE_COLOR_SAFE = new Color(255, 255, 255);
	private static final Color DAMAGE_COLOR_WARN = new Color(255, 255, 0);
	private static final Color DAMAGE_COLOR_DEATH = new Color(255, 64, 64);
	private static final int SAFE_TICK_COLOR = 0xFF00FF00;
	private static final double VERTICAL_LOOK_THRESHOLD = 0.99995;
	private static final double TRACE_STEP = 0.25;
	
	private final SliderSetting reach =
		new SliderSetting("Reach", "How far ahead to look for a landing spot.",
			48, 8, 96, 1, ValueDisplay.INTEGER);
	private final SliderSetting timerSpeed = new SliderSetting("Timer Speed",
		"Speed multiplier for the intermediate ticks while teleporting.", 2, 1,
		10, 0.1, ValueDisplay.DECIMAL);
	private final SliderSetting packetSpam = new SliderSetting(
		"Teleport Packets", "Movement packets to send before the teleport.", 4,
		1, 10, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting allowLiquids = new CheckboxSetting(
		"Allow Liquids", "Allow landing targets inside liquids.", true);
	private final CheckboxSetting phaseWalls =
		new CheckboxSetting("Phase Through Walls",
			"Keep scanning past the first solid block to find a landing spot.",
			true);
	private final CheckboxSetting shiftClickActivation = new CheckboxSetting(
		"Shift Click Activation",
		"Require holding shift before teleporting through obstacles.", false);
	private final CheckboxSetting renderTarget =
		new CheckboxSetting("Render Target", "Draw the landing zone.", true);
	private final CheckboxSetting renderPath = new CheckboxSetting(
		"Render Path",
		"Draw a line between your eye position and the landing spot.", true);
	private final CheckboxSetting ignoreSafeTick = new CheckboxSetting(
		"Teleport without Tick",
		"Allow teleporting even when the safe-tick indicator is not showing.",
		false);
	private final ColorSetting fillColor = new ColorSetting("Target Fill",
		"Fill colour for the landing box.", new Color(128, 0, 255, 64));
	private final ColorSetting phasingColor = new ColorSetting("Phasing Fill",
		"Fill colour when phasing through solids.", new Color(0, 255, 255, 45));
	private final ColorSetting outlineColor = new ColorSetting("Outline Color",
		"Outline colour for the landing box.", new Color(255, 255, 255, 255));
	private final ColorSetting pathColor = new ColorSetting("Path Color",
		"Colour of the rendered teleport path.", new Color(0, 255, 255, 255));
	
	private Vec3 teleportTarget;
	private AABB targetBox;
	private BlockPos landingBlock;
	private double damageHearts;
	private int damageColor = DAMAGE_COLOR_SAFE.getRGB();
	private boolean isValidTarget;
	private boolean showSafeTick;
	private boolean safeFromDamage;
	private boolean targetBelow;
	private boolean targetViaPhasing;
	private boolean teleportedThisPress;
	private boolean teleporting;
	private int stepIndex;
	private int totalSteps;
	private Vec3 startPos;
	
	public TeleportHack()
	{
		super("Teleport");
		setCategory(Category.MOVEMENT);
		
		addSetting(reach);
		addSetting(timerSpeed);
		addSetting(packetSpam);
		addSetting(allowLiquids);
		addSetting(phaseWalls);
		addSetting(shiftClickActivation);
		addSetting(renderTarget);
		addSetting(renderPath);
		addSetting(ignoreSafeTick);
		addSetting(fillColor);
		addSetting(phasingColor);
		addSetting(outlineColor);
		addSetting(pathColor);
	}
	
	@Override
	protected void onEnable()
	{
		teleportedThisPress = false;
		teleporting = false;
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
		teleporting = false;
		teleportedThisPress = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null
			|| MC.getCameraEntity() == null)
			return;
		
		updateTarget();
		
		if(teleporting && !isValidTarget)
		{
			teleporting = false;
			stepIndex = 0;
		}
		
		boolean shiftOk = !targetViaPhasing || !shiftClickActivation.isChecked()
			|| MC.options.keyShift.isDown();
		boolean readyToTeleport = isValidTarget
			&& (safeFromDamage || !targetBelow || ignoreSafeTick.isChecked());
		boolean attackDown = MC.options.keyAttack.isDown() && shiftOk;
		
		if(attackDown && readyToTeleport)
		{
			if(!teleportedThisPress)
				beginTeleport();
			teleportedThisPress = true;
		}else
		{
			teleportedThisPress = false;
		}
		
		if(teleporting)
			handleTeleportProgress();
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.player == null)
			return;
		
		if(renderTarget.isChecked() && targetBox != null)
		{
			int fill =
				(targetViaPhasing ? phasingColor : fillColor).getColorI(120);
			int outline = outlineColor.getColorI();
			RenderUtils.drawSolidBox(matrices, targetBox, fill, false);
			RenderUtils.drawOutlinedBox(matrices, targetBox, outline, false);
		}
		
		if(renderPath.isChecked() && teleportTarget != null)
		{
			Vec3 start = MC.player.getEyePosition(partialTicks);
			RenderUtils.drawLine(matrices, start, teleportTarget,
				pathColor.getColorI(), false);
		}
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
	{
		if(!isValidTarget || teleportTarget == null || MC.player == null)
			return;
		
		if(!showSafeTick && damageHearts <= 0 && !ignoreSafeTick.isChecked())
			return;
		
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
				context.drawString(font, "✔", x + textWidth + 6, y,
					SAFE_TICK_COLOR, true);
		}else if(showSafeTick)
		{
			context.drawString(font, "✔", centerX, y, SAFE_TICK_COLOR, true);
		}
	}
	
	private void updateTarget()
	{
		teleportTarget = null;
		targetBox = null;
		landingBlock = null;
		damageHearts = 0;
		isValidTarget = false;
		showSafeTick = false;
		targetViaPhasing = false;
		
		BlockPos landing = null;
		
		Vec3 eye = MC.player.getEyePosition(1.0F);
		Vec3 look = MC.player.getViewVector(1.0F).normalize();
		
		if(phaseWalls.isChecked())
		{
			landing = findPhasingLanding(eye, look, reach.getValue());
			if(landing != null)
				targetViaPhasing = true;
		}
		
		if(landing == null)
			landing = findDirectLanding();
		
		if(landing == null)
			return;
		
		landingBlock = landing;
		teleportTarget = new Vec3(landing.getX() + 0.5, landing.getY() + 1.1,
			landing.getZ() + 0.5);
		targetBox = new AABB(landing.getX() - 0.5, landing.getY(),
			landing.getZ() - 0.5, landing.getX() + 0.5, landing.getY() + 1.9,
			landing.getZ() + 0.5);
		isValidTarget = true;
		
		double dropDistance = MC.player.getY() - teleportTarget.y;
		double dropDamage = Math.max(0, dropDistance - 3.0);
		damageHearts = dropDistance > 0 ? dropDamage / 2.0 : 0;
		float playerHearts = getPlayerHearts();
		damageColor = getDamageTextColor(playerHearts, damageHearts);
		
		safeFromDamage = dropDistance <= 0 || playerHearts >= damageHearts;
		showSafeTick = isValidTarget && safeFromDamage;
		targetBelow = dropDistance > 0;
	}
	
	private BlockPos findDirectLanding()
	{
		if(MC.getCameraEntity() == null)
			return null;
		
		Vec3 eye = MC.getCameraEntity().getEyePosition(1.0F);
		Vec3 look = MC.getCameraEntity().getViewVector(1.0F).normalize();
		double maxDistance = reach.getValue();
		double limit = maxDistance;
		BlockHitResult hit = BlockUtils.raycast(eye,
			eye.add(look.scale(maxDistance)), allowLiquids.isChecked()
				? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE);
		if(hit != null && hit.getType() == HitResult.Type.BLOCK)
			limit = Math.min(limit, eye.distanceTo(hit.getLocation()) + 0.5);
		
		return scanLanding(eye, look, limit, true);
	}
	
	private BlockPos findPhasingLanding(Vec3 start, Vec3 direction,
		double maxDistance)
	{
		if(MC.level == null)
			return null;
		
		return scanLanding(start, direction, maxDistance, false);
	}
	
	private BlockPos scanLanding(Vec3 start, Vec3 direction, double maxDistance,
		boolean stopAtFirst)
	{
		if(MC.level == null)
			return null;
		
		Vec3 cursor = start;
		double traveled = 0;
		BlockPos last = null;
		BlockPos best = null;
		
		while(traveled <= maxDistance)
		{
			BlockPos candidate = BlockPos.containing(cursor);
			if(!candidate.equals(last))
			{
				last = candidate;
				if(isValidLanding(candidate))
				{
					if(stopAtFirst)
						return candidate;
					best = candidate;
				}
			}
			
			cursor = cursor.add(direction.scale(TRACE_STEP));
			traveled += TRACE_STEP;
		}
		
		return best;
	}
	
	private boolean isValidLanding(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		
		BlockState base = MC.level.getBlockState(pos);
		BlockState above = MC.level.getBlockState(pos.above());
		
		if(!allowLiquids.isChecked() && (!base.getFluidState().isEmpty()
			|| !above.getFluidState().isEmpty()))
		{
			return false;
		}
		
		return isAirLike(base) && isAirLike(above);
	}
	
	private boolean isAirLike(BlockState state)
	{
		return state.isAir() || state.canBeReplaced();
	}
	
	private void beginTeleport()
	{
		if(teleporting || teleportTarget == null || MC.player == null)
			return;
		
		teleporting = true;
		stepIndex = 0;
		startPos = MC.player.position();
		double distance = Math.max(0.01, startPos.distanceTo(teleportTarget));
		totalSteps =
			Math.max(2, Math.min(20, (int)Math.ceil(distance / 4.0) + 2));
	}
	
	private void handleTeleportProgress()
	{
		if(MC.player == null || teleportTarget == null)
			return;
		
		stepIndex++;
		int steps = Math.max(1, totalSteps);
		double progress = Math.min(1.0, stepIndex / (double)steps);
		Vec3 delta = teleportTarget.subtract(startPos);
		Vec3 next = startPos.add(delta.scale(progress));
		
		MC.player.setPos(next.x, next.y, next.z);
		MC.player.setDeltaMovement(Vec3.ZERO);
		IKeyBinding.get(MC.options.keyJump).setPressed(false);
		IKeyBinding.get(MC.options.keyShift).setPressed(false);
		IKeyBinding.get(MC.options.keyUp).setPressed(false);
		IKeyBinding.get(MC.options.keyDown).setPressed(false);
		IKeyBinding.get(MC.options.keyLeft).setPressed(false);
		IKeyBinding.get(MC.options.keyRight).setPressed(false);
		
		WURST.getTimerManager().requestTimerSpeed((float)timerSpeed.getValue(),
			TimerPriority.IMPORTANT_FOR_USAGE_1, this, 1);
		
		if(stepIndex >= steps)
			finishTeleport();
	}
	
	private void finishTeleport()
	{
		teleporting = false;
		stepIndex = 0;
		teleportedThisPress = false;
		
		sendTeleportPackets();
		
		if(MC.player != null && teleportTarget != null)
		{
			MC.player.setPos(teleportTarget.x, teleportTarget.y,
				teleportTarget.z);
			MC.player.setDeltaMovement(Vec3.ZERO);
		}
	}
	
	private void sendTeleportPackets()
	{
		if(MC.player == null || MC.player.connection == null
			|| teleportTarget == null)
		{
			return;
		}
		
		for(int i = 0; i < packetSpam.getValueI(); i++)
		{
			MC.player.connection.send(new ServerboundMovePlayerPacket.Pos(
				MC.player.getX(), MC.player.getY(), MC.player.getZ(),
				MC.player.onGround(), MC.player.horizontalCollision));
		}
		
		MC.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
			teleportTarget.x, teleportTarget.y, teleportTarget.z,
			MC.player.getYRot(), MC.player.getXRot(), false, false));
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
			return blendColor(DAMAGE_COLOR_SAFE, DAMAGE_COLOR_WARN,
				ratio / 0.5f);
		
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
