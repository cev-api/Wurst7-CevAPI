/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.CameraTransformViewBobbingListener.CameraTransformViewBobbingEvent;
import java.lang.reflect.Method;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.DisconnectContext;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"Pearl ESP", "ender pearl warning", "PearlESP"})
public class PearlEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private static final double LANDING_BOX_RADIUS = 0.35;
	private static final double HAND_BOX_RADIUS = 0.18;
	private static final long STASIS_LABEL_STICKY_MS = 1200;
	
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
	private final CheckboxSetting showTracerLine =
		new CheckboxSetting("Show tracer line",
			"Draw trajectory lines for pearl predictions.", true);
	private final CheckboxSetting showStasisOwners = new CheckboxSetting(
		"Show stasis owners",
		"Show the owner name above visible ender pearls sitting in stasis chambers.",
		true);
	private final SliderSetting tracerThickness = new SliderSetting(
		"Tracer thickness", 2, 0.5, 8, 0.1, ValueDisplay.DECIMAL);
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final CheckboxSetting chatAlerts =
		new CheckboxSetting("Chat alerts",
			"Show a chat alert when a new ender pearl is detected.", false);
	private final CheckboxSetting soundAlerts =
		new CheckboxSetting("Sound alerts",
			"Play a sound alert when a new ender pearl is detected.", false);
	private final EnumSetting<AlertSound> alertSound =
		new EnumSetting<>("Alert sound",
			"Sound used for PearlESP alerts (includes all note-block sounds).",
			AlertSound.values(), AlertSound.NOTE_BLOCK_CHIME);
	
	private final ArrayList<TrackedPearl> pearls = new ArrayList<>();
	private final ArrayList<HeldPearl> holders = new ArrayList<>();
	private final HashSet<UUID> alertedPearls = new HashSet<>();
	private final Map<UUID, StasisLabel> stasisLabels = new HashMap<>();
	private boolean hasOwnPearl;
	private long ownPearlSuppressUntilMs;
	
	public PearlEspHack()
	{
		super("PearlESP");
		setCategory(Category.RENDER);
		addSetting(color);
		addSetting(highlightHeld);
		addSetting(showPlayerPrediction);
		addSetting(showTracerLine);
		addSetting(showStasisOwners);
		addSetting(tracerThickness);
		addSetting(tracerFlash);
		addSetting(chatAlerts);
		addSetting(soundAlerts);
		addSetting(alertSound);
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
		alertedPearls.clear();
		stasisLabels.clear();
		hasOwnPearl = false;
		ownPearlSuppressUntilMs = 0L;
	}
	
	@Override
	public void onUpdate()
	{
		pearls.clear();
		holders.clear();
		hasOwnPearl = false;
		HashSet<UUID> seenPearls = new HashSet<>();
		long now = System.currentTimeMillis();
		
		if(MC.level == null)
			return;
		
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(entity.getType() == EntityType.ENDER_PEARL)
			{
				Entity pearl = entity;
				seenPearls.add(pearl.getUUID());
				PearlPrediction prediction = buildPrediction(pearl);
				Entity owner = getPearlOwner(pearl);
				String ownerLabel = getPearlOwnerLabel(owner);
				Vec3 stasisLabelPos = getStasisLabelPos(pearl);
				if(stasisLabelPos != null)
					stasisLabels.put(pearl.getUUID(),
						new StasisLabel(ownerLabel, stasisLabelPos,
							now + STASIS_LABEL_STICKY_MS));
				StasisLabel stasisLabel = stasisLabels.get(pearl.getUUID());
				pearls.add(new TrackedPearl(pearl, prediction, ownerLabel,
					stasisLabel));
				boolean ownPearl = owner == MC.player;
				if(ownPearl)
				{
					hasOwnPearl = true;
					ownPearlSuppressUntilMs = System.currentTimeMillis() + 4000;
				}
				
				if(!ownPearl && alertedPearls.add(pearl.getUUID()))
					triggerPearlAlert(pearl, ownerLabel);
				continue;
			}
			
			if(entity instanceof Player player)
				handleHolder(player);
		}
		
		alertedPearls.retainAll(seenPearls);
		stasisLabels.entrySet().removeIf(e -> !seenPearls.contains(e.getKey())
			|| e.getValue().untilMs() < now);
	}
	
	private void triggerPearlAlert(Entity pearl, String ownerLabel)
	{
		if(!chatAlerts.isChecked() && !soundAlerts.isChecked())
			return;
		
		if(chatAlerts.isChecked())
		{
			Vec3 selfPos = MC.player == null ? null : MC.player.position();
			String detectionDetails = DisconnectContext
				.formatPlayerDetectionDetails(selfPos, pearl.position());
			ChatUtils.message("PearlESP: Ender pearl detected.\nDetected at:\n"
				+ detectionDetails + "\n" + ownerLabel + ".");
		}
		
		if(!soundAlerts.isChecked() || MC.level == null || MC.player == null)
			return;
		
		SoundEvent sound = alertSound.getSelected().resolve();
		if(sound == null)
			return;
		
		MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
			MC.player.getZ(), sound, SoundSource.PLAYERS, 1.0F, 1.0F, false);
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
		if(!showTracerLine.isChecked())
			return;
		
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
			
			if(showStasisOwners.isChecked())
			{
				StasisLabel label = tracked.stasisLabel();
				if(label != null)
					drawWorldLabel(matrixStack, label.text(), label.pos().x,
						label.pos().y, label.pos().z, lineColor, 0.85F, 0F);
			}
			
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
						renderTrajectory(matrixStack, pred.path(), lineColor);
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
		if(!showTracerLine.isChecked() || path == null || path.size() < 2)
			return;
		
		if(tracerFlash.isChecked())
			lineColor = RenderUtils.flashColor(lineColor);
		RenderUtils.drawCurvedLine(matrixStack, path, lineColor, false,
			tracerThickness.getValue());
	}
	
	private Entity getPearlOwner(Entity pearl)
	{
		if(pearl instanceof Projectile projectile)
			return projectile.getOwner();
		
		try
		{
			Method m = pearl.getClass().getMethod("getOwner");
			Object owner = m.invoke(pearl);
			if(owner instanceof Entity entity)
				return entity;
		}catch(Exception ignored)
		{}
		
		return null;
	}
	
	private String getPearlOwnerLabel(Entity owner)
	{
		if(owner == MC.player)
			return "Owner: You";
		if(owner instanceof Player player)
			return "Owner: " + player.getName().getString();
		return "Owner: Unknown";
	}
	
	private Vec3 getStasisLabelPos(Entity pearl)
	{
		if(MC.level == null)
			return null;
		
		BlockPos pearlPos = pearl.blockPosition();
		for(int yOffset = 2; yOffset >= -2; yOffset--)
		{
			BlockPos checkPos = pearlPos.offset(0, yOffset, 0);
			BlockPos base = getStasisBase(checkPos);
			if(base != null)
				return getStaticLabelPos(base);
		}
		
		return null;
	}
	
	private BlockPos getStasisBase(BlockPos pos)
	{
		if(!isBubbleColumn(pos))
			return null;
		
		for(int dy = 1; dy <= 8; dy++)
		{
			BlockPos below = pos.below(dy);
			BlockState state = MC.level.getBlockState(below);
			if(state.getBlock() == Blocks.SOUL_SAND
				|| state.getBlock() == Blocks.SOUL_SOIL)
				return below;
			if(!isBubbleColumn(below) && state.getFluidState().isEmpty())
				return null;
		}
		
		return null;
	}
	
	private Vec3 getStaticLabelPos(BlockPos base)
	{
		int topY = base.getY();
		for(int dy = 1; dy <= 16; dy++)
		{
			BlockPos above = base.above(dy);
			if(!isBubbleColumn(above))
				break;
			topY = above.getY();
		}
		
		return new Vec3(base.getX() + 0.5, topY + 2.35, base.getZ() + 0.5);
	}
	
	private boolean isBubbleColumn(BlockPos pos)
	{
		BlockState state = MC.level.getBlockState(pos);
		return state.getBlock() instanceof BubbleColumnBlock;
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb, float scale, float offsetPx)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		
		matrices.mulPose(Axis.YP.rotationDegrees(180.0F));
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		matrices.translate(0, offsetPx, 0);
		
		Font font = MC.font;
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		float w = font.width(text) / 2F;
		int baseAlpha = (argb >>> 24) & 0xFF;
		int bgAlpha =
			(int)Math.round(MC.options.getBackgroundOpacity(0.25F) * baseAlpha);
		int bg = bgAlpha << 24;
		int strokeColor =
			(Math.max(0, Math.min(255, baseAlpha)) << 24) | 0x000000;
		var matrix = matrices.last().pose();
		
		font.drawInBatch(text, -w - 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w + 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, -1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, 1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, 0, argb, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, bg, 0xF000F0);
		
		vcp.endBatch();
		matrices.popPose();
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
	
	private record TrackedPearl(Entity pearl, PearlPrediction prediction,
		String ownerLabel, StasisLabel stasisLabel)
	{}
	
	private record PearlPrediction(List<Vec3> path, Vec3 landing)
	{}
	
	private record HeldPearl(Player player, InteractionHand hand)
	{}
	
	private record StasisLabel(String text, Vec3 pos, long untilMs)
	{}
	
	private enum AlertSound
	{
		NOTE_BLOCK_HARP("Note Block Harp", "minecraft:block.note_block.harp"),
		NOTE_BLOCK_BASS("Note Block Bass", "minecraft:block.note_block.bass"),
		NOTE_BLOCK_BASEDRUM("Note Block Basedrum",
			"minecraft:block.note_block.basedrum"),
		NOTE_BLOCK_SNARE("Note Block Snare",
			"minecraft:block.note_block.snare"),
		NOTE_BLOCK_HAT("Note Block Hat", "minecraft:block.note_block.hat"),
		NOTE_BLOCK_GUITAR("Note Block Guitar",
			"minecraft:block.note_block.guitar"),
		NOTE_BLOCK_FLUTE("Note Block Flute",
			"minecraft:block.note_block.flute"),
		NOTE_BLOCK_BELL("Note Block Bell", "minecraft:block.note_block.bell"),
		NOTE_BLOCK_CHIME("Note Block Chime",
			"minecraft:block.note_block.chime"),
		NOTE_BLOCK_XYLOPHONE("Note Block Xylophone",
			"minecraft:block.note_block.xylophone"),
		NOTE_BLOCK_IRON_XYLOPHONE("Note Block Iron Xylophone",
			"minecraft:block.note_block.iron_xylophone"),
		NOTE_BLOCK_COW_BELL("Note Block Cow Bell",
			"minecraft:block.note_block.cow_bell"),
		NOTE_BLOCK_DIDGERIDOO("Note Block Didgeridoo",
			"minecraft:block.note_block.didgeridoo"),
		NOTE_BLOCK_BIT("Note Block Bit", "minecraft:block.note_block.bit"),
		NOTE_BLOCK_BANJO("Note Block Banjo",
			"minecraft:block.note_block.banjo"),
		NOTE_BLOCK_PLING("Note Block Pling",
			"minecraft:block.note_block.pling"),
		XP_PICKUP("XP Pickup", "minecraft:entity.experience_orb.pickup"),
		UI_BUTTON_CLICK("UI Button Click", "minecraft:ui.button.click"),
		AMETHYST_CHIME("Amethyst Chime",
			"minecraft:block.amethyst_block.chime"),
		BELL_USE("Bell Use", "minecraft:block.bell.use"),
		ITEM_PICKUP("Item Pickup", "minecraft:entity.item.pickup");
		
		private final String displayName;
		private final String id;
		
		private AlertSound(String displayName, String id)
		{
			this.displayName = displayName;
			this.id = id;
		}
		
		private SoundEvent resolve()
		{
			try
			{
				return BuiltInRegistries.SOUND_EVENT
					.getValue(Identifier.parse(id));
			}catch(Exception e)
			{
				return null;
			}
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
