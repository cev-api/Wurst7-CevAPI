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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.CameraTransformViewBobbingListener.CameraTransformViewBobbingEvent;
import java.lang.reflect.Method;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"Pearl ESP", "ender pearl warning", "PearlESP"})
public class PearlEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private static final double LANDING_BOX_RADIUS = 0.35;
	private static final double HAND_BOX_RADIUS = 0.18;
	
	private final ColorSetting color = new ColorSetting("Color",
		"Highlight color for pearls, landing boxes and trajectory.",
		new Color(0xFF00FF));
	private final CheckboxSetting highlightHeld = new CheckboxSetting(
		"Highlight held pearls",
		"Highlight players who are currently holding an ender pearl.", true);
	private final CheckboxSetting showPlayerPrediction = new CheckboxSetting(
		"Show player prediction",
		"Show predicted trajectory while the local player is holding a pearl.",
		true);
	
	private final ArrayList<TrackedPearl> pearls = new ArrayList<>();
	private final ArrayList<HeldPearl> holders = new ArrayList<>();
	private boolean hasOwnPearl;
	private long ownPearlSuppressUntilMs;
	
	public PearlEspHack()
	{
		super("PearlESP");
		setCategory(Category.RENDER);
		addSetting(color);
		addSetting(highlightHeld);
		addSetting(showPlayerPrediction);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		pearls.clear();
		holders.clear();
		hasOwnPearl = false;
		ownPearlSuppressUntilMs = 0L;
	}
	
	@Override
	public void onUpdate()
	{
		pearls.clear();
		holders.clear();
		hasOwnPearl = false;
		
		if(MC.level == null)
			return;
		
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(entity.getType() == EntityType.ENDER_PEARL)
			{
				Entity pearl = entity;
				PearlPrediction prediction = buildPrediction(pearl);
				pearls.add(new TrackedPearl(pearl, prediction));
				// Detect ownership via reflection and set suppression window
				try
				{
					Method m = pearl.getClass().getMethod("getOwner");
					Object owner = m.invoke(pearl);
					if(owner == MC.player)
					{
						hasOwnPearl = true;
						ownPearlSuppressUntilMs =
							System.currentTimeMillis() + 4000; // suppress
																// prediction up
																// to 4s
					}
				}catch(Exception ignored)
				{}
				continue;
			}
			
			if(entity instanceof Player player)
				handleHolder(player);
		}
	}
	
	private void handleHolder(Player player)
	{
		if(player == null || player == MC.player || player.isSpectator())
			return;
		
		for(InteractionHand hand : InteractionHand.values())
		{
			if(isHoldingPearl(player, hand))
				holders.add(new HeldPearl(player, hand));
		}
	}
	
	private boolean isHoldingPearl(Player player, InteractionHand hand)
	{
		ItemStack stack = hand == InteractionHand.MAIN_HAND
			? player.getMainHandItem() : player.getOffhandItem();
		return !stack.isEmpty() && stack.is(Items.ENDER_PEARL);
	}
	
	private boolean isHoldingPearlAnyHand(Player player)
	{
		return isHoldingPearl(player, InteractionHand.MAIN_HAND)
			|| isHoldingPearl(player, InteractionHand.OFF_HAND);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		for(TrackedPearl t : pearls)
		{
			PearlPrediction p = t.prediction();
			if(p != null && p.path() != null && p.path().size() > 1)
			{
				event.cancel();
				return;
			}
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(pearls.isEmpty() && holders.isEmpty())
		{
			boolean wantPrediction = showPlayerPrediction.isChecked()
				&& MC.player != null && isHoldingPearlAnyHand(MC.player);
			if(!wantPrediction)
				return;
		}
		
		ArrayList<AABB> pearlBoxes = new ArrayList<>();
		ArrayList<AABB> landingBoxes = new ArrayList<>();
		ArrayList<AABB> handBoxes = new ArrayList<>();
		int lineColor = color.getColorI();
		int fillColor = color.getColorI(0x50);
		
		for(TrackedPearl tracked : pearls)
		{
			Entity pearl = tracked.pearl();
			if(pearl == null || pearl.isRemoved())
				continue;
			
			AABB box = EntityUtils.getLerpedBox(pearl, partialTicks);
			pearlBoxes.add(box);
			
			PearlPrediction prediction = tracked.prediction();
			if(prediction != null && prediction.landing() != null)
			{
				landingBoxes.add(
					createLandingBox(prediction.landing(), LANDING_BOX_RADIUS));
				renderTrajectory(matrixStack, prediction.path(), lineColor);
			}
		}
		
		for(HeldPearl held : holders)
		{
			if(!highlightHeld.isChecked())
				continue;
			AABB box = getHandBox(held.player(), held.hand(), partialTicks);
			if(box != null)
				handBoxes.add(box);
		}
		
		// Show predicted trajectory for local player while holding a pearl
		if(MC.player != null)
		{
			for(InteractionHand hand : InteractionHand.values())
			{
				if(!showPlayerPrediction.isChecked())
					break;
				if(isHoldingPearl(MC.player, hand))
				{
					Vec3 start = getHandPos(MC.player, hand, partialTicks);
					// Suppress prediction if our pearl is in-flight or recently
					// thrown
					boolean hasActual = hasOwnPearl
						|| System.currentTimeMillis() < ownPearlSuppressUntilMs;
					if(start != null)
					{
						if(!hasActual)
							for(TrackedPearl t : pearls)
							{
								Entity e = t.pearl();
								if(e == null)
									continue;
								try
								{
									Method m =
										e.getClass().getMethod("getOwner");
									Object owner = m.invoke(e);
									if(owner == MC.player)
									{
										hasActual = true;
										break;
									}
								}catch(Exception ex)
								{
									// Fallback: proximity to hand
									Vec3 ppos = e.position();
									if(ppos.distanceTo(start) < 1.5)
									{
										hasActual = true;
										break;
									}
								}
							}
					}
					
					if(hasActual)
						continue; // a real pearl exists — don't draw prediction
						
					PearlPrediction pred = buildPredictionFromPlayer(MC.player,
						hand, partialTicks);
					if(pred != null && pred.landing() != null)
					{
						landingBoxes.add(createLandingBox(pred.landing(),
							LANDING_BOX_RADIUS));
						RenderUtils.drawCurvedLine(matrixStack, pred.path(),
							lineColor, false);
					}
				}
			}
		}
		
		if(!pearlBoxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrixStack, pearlBoxes, lineColor,
				false);
		
		if(!landingBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, landingBoxes, fillColor,
				false);
			RenderUtils.drawOutlinedBoxes(matrixStack, landingBoxes, lineColor,
				false);
		}
		
		if(!handBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, handBoxes, fillColor,
				false);
			RenderUtils.drawOutlinedBoxes(matrixStack, handBoxes, lineColor,
				false);
		}
	}
	
	private void renderTrajectory(PoseStack matrixStack, List<Vec3> path,
		int lineColor)
	{
		if(path == null || path.size() < 2)
			return;
		
		RenderUtils.drawCurvedLine(matrixStack, path, lineColor, false);
	}
	
	private PearlPrediction buildPrediction(Entity pearl)
	{
		ArrayList<Vec3> path = new ArrayList<>();
		Vec3 position = pearl.position();
		Vec3 velocity = pearl.getDeltaMovement();
		path.add(position);
		Vec3 landing = position;
		
		if(MC.player == null)
			return new PearlPrediction(path, landing);
		
		for(int i = 0; i < 160; i++)
		{
			Vec3 next = position.add(velocity);
			BlockHitResult hit = BlockUtils.raycast(position, next);
			if(hit.getType() != HitResult.Type.MISS)
			{
				Vec3 hitPos = hit.getLocation();
				path.add(hitPos);
				return new PearlPrediction(path, hitPos);
			}
			
			path.add(next);
			position = next;
			landing = next;
			
			velocity = applyDrag(velocity, pearl);
			
			if(position.y < MC.level.getMinY() - 4)
				break;
			if(velocity.lengthSqr() < 1e-4)
				break;
		}
		
		return new PearlPrediction(path, landing);
	}
	
	private PearlPrediction buildPredictionFromPlayer(Player player,
		InteractionHand hand, float partialTicks)
	{
		Vec3 start = getHandPos(player, hand, partialTicks);
		if(start == null)
			return null;
		
		// initial velocity based on player yaw/pitch like Trajectories
		double yaw = Math.toRadians(player.getYRot());
		double pitch = Math.toRadians(player.getXRot());
		double cosPitch = Math.cos(pitch);
		double vx = -Math.sin(yaw) * cosPitch;
		double vy = -Math.sin(pitch);
		double vz = Math.cos(yaw) * cosPitch;
		Vec3 velocity = new Vec3(vx, vy, vz).scale(1.5);
		
		ArrayList<Vec3> path = new ArrayList<>();
		Vec3 position = start;
		Vec3 landing = position;
		
		for(int i = 0; i < 160; i++)
		{
			Vec3 next = position.add(velocity);
			BlockHitResult hit = BlockUtils.raycast(position, next);
			if(hit.getType() != HitResult.Type.MISS)
			{
				Vec3 hitPos = hit.getLocation();
				path.add(hitPos);
				return new PearlPrediction(path, hitPos);
			}
			
			path.add(next);
			position = next;
			landing = next;
			
			velocity = applyDrag(velocity, player);
			
			if(position.y < MC.level.getMinY() - 4)
				break;
			if(velocity.lengthSqr() < 1e-4)
				break;
		}
		
		return new PearlPrediction(path, landing);
	}
	
	private Vec3 applyDrag(Vec3 velocity, Entity pearl)
	{
		double drag = pearl.isInWater() ? 0.8 : 0.99;
		Vec3 slowed = velocity.scale(drag);
		if(!pearl.isNoGravity())
			slowed = slowed.add(0, -0.03, 0);
		return slowed;
	}
	
	private AABB createLandingBox(Vec3 center, double radius)
	{
		return new AABB(center.x - radius, center.y - radius, center.z - radius,
			center.x + radius, center.y + radius, center.z + radius);
	}
	
	private AABB getHandBox(Player player, InteractionHand hand,
		float partialTicks)
	{
		Vec3 center = getHandPos(player, hand, partialTicks);
		if(center == null)
			return null;
		return createLandingBox(center, HAND_BOX_RADIUS);
	}
	
	private Vec3 getHandPos(Player player, InteractionHand hand,
		float partialTicks)
	{
		if(player == null)
			return null;
		
		Vec3 basePos = EntityUtils.getLerpedPos(player, partialTicks);
		double yaw = Math.toRadians(player.getYRot());
		
		HumanoidArm mainArm = player.getMainArm();
		boolean rightSide = mainArm == HumanoidArm.RIGHT
			&& hand == InteractionHand.MAIN_HAND
			|| mainArm == HumanoidArm.LEFT && hand == InteractionHand.OFF_HAND;
		double side = rightSide ? -1 : 1;
		
		double offsetX = Math.cos(yaw) * 0.18 * side;
		double offsetY = player.getEyeHeight(player.getPose()) - 0.1;
		double offsetZ = Math.sin(yaw) * 0.18 * side;
		
		return basePos.add(offsetX, offsetY, offsetZ);
	}
	
	private record TrackedPearl(Entity pearl, PearlPrediction prediction)
	{}
	
	private record PearlPrediction(List<Vec3> path, Vec3 landing)
	{}
	
	private record HeldPearl(Player player, InteractionHand hand)
	{}
}
