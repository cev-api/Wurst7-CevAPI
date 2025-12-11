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
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"trident esp", "TridentTracers", "trident tracers"})
public final class TridentEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox.\n"
			+ "\u00a7lFancy\u00a7r mode shows larger boxes that look better.");
	
	// Color controls (like Search): single fixed color or rainbow
	private final CheckboxSetting useFixedColor =
		new CheckboxSetting("Use fixed color",
			"Enable to use a fixed color instead of rainbow.", false);
	private final ColorSetting fixedColor = new ColorSetting("Fixed color",
		"Color used when \"Use fixed color\" is enabled.", Color.CYAN);
	
	// Distinguish by owner type (self, other player, mob)
	private final CheckboxSetting colorByOwner = new CheckboxSetting(
		"Color by owner type",
		"When enabled, uses different colors depending on who threw/holds the trident.",
		false);
	private final ColorSetting selfColor = new ColorSetting("Your tridents",
		"Color for your own thrown/held tridents.", new Color(0x55FF55));
	private final ColorSetting otherPlayerColor =
		new ColorSetting("Other players",
			"Color for tridents from other players.", new Color(0xFF5555));
	private final ColorSetting mobColor = new ColorSetting("Mobs",
		"Color for tridents from mobs.", new Color(0xFFFF55));
	
	// Include tridents currently held by others
	private final CheckboxSetting includeHeld = new CheckboxSetting(
		"Highlight held tridents",
		"Also highlight when a trident is currently held by another player or mob.",
		false);
	
	// Cached per-tick results
	private final ArrayList<ThrownTrident> thrown = new ArrayList<>();
	private final ArrayList<LivingEntity> holders = new ArrayList<>();
	
	public TridentEspHack()
	{
		super("TridentESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(useFixedColor);
		addSetting(fixedColor);
		addSetting(colorByOwner);
		addSetting(selfColor);
		addSetting(otherPlayerColor);
		addSetting(mobColor);
		addSetting(includeHeld);
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
		thrown.clear();
		holders.clear();
	}
	
	@Override
	public void onUpdate()
	{
		thrown.clear();
		holders.clear();
		
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(e instanceof ThrownTrident)
				thrown.add((ThrownTrident)e);
			if(includeHeld.isChecked() && e instanceof LivingEntity)
			{
				LivingEntity le = (LivingEntity)e;
				if(le == MC.player)
					continue; // "another player or a mob" only
				if(isHoldingTrident(le))
					holders.add(le);
			}
		}
	}
	
	private boolean isHoldingTrident(LivingEntity e)
	{
		ItemStack main = e.getMainHandItem();
		ItemStack off = e.getOffhandItem();
		return (main != null && main.getItem() == Items.TRIDENT)
			|| (off != null && off.getItem() == Items.TRIDENT);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(!style.hasBoxes() && !style.hasLines())
			return;
		
		if(colorByOwner.isChecked())
			renderByOwner(matrixStack, partialTicks);
		else
			renderSingleColor(matrixStack, partialTicks);
	}
	
	private void renderSingleColor(PoseStack matrixStack, float partialTicks)
	{
		int lineColor;
		if(useFixedColor.isChecked())
			lineColor = fixedColor.getColorI(0x80);
		else
			lineColor =
				RenderUtils.toIntColor(RenderUtils.getRainbowColor(), 0.5F);
		
		if(style.hasBoxes())
		{
			List<AABB> boxes = new ArrayList<>();
			for(ThrownTrident t : thrown)
				boxes.add(
					applyExtraSize(EntityUtils.getLerpedBox(t, partialTicks)));
			for(LivingEntity h : holders)
			{
				AABB handBox = getHeldTridentBox(h, partialTicks);
				if(handBox != null)
					boxes.add(applyExtraSize(handBox));
			}
			if(!boxes.isEmpty())
				RenderUtils.drawOutlinedBoxes(matrixStack, boxes, lineColor,
					false);
		}
		
		if(style.hasLines())
		{
			List<Vec3> ends = new ArrayList<>();
			for(ThrownTrident t : thrown)
				ends.add(EntityUtils.getLerpedBox(t, partialTicks).getCenter());
			for(LivingEntity h : holders)
			{
				Vec3 hand = getHeldTridentPos(h, partialTicks);
				if(hand != null)
					ends.add(hand);
			}
			if(!ends.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, ends,
					lineColor, false);
		}
	}
	
	private void renderByOwner(PoseStack matrixStack, float partialTicks)
	{
		ArrayList<AABB> selfBoxes = new ArrayList<>();
		ArrayList<AABB> playerBoxes = new ArrayList<>();
		ArrayList<AABB> mobBoxes = new ArrayList<>();
		ArrayList<Vec3> selfEnds = new ArrayList<>();
		ArrayList<Vec3> playerEnds = new ArrayList<>();
		ArrayList<Vec3> mobEnds = new ArrayList<>();
		
		for(ThrownTrident t : thrown)
		{
			Entity owner = t.getOwner();
			AABB box =
				applyExtraSize(EntityUtils.getLerpedBox(t, partialTicks));
			Vec3 end = box.getCenter();
			if(owner instanceof Player)
			{
				if(owner == MC.player)
				{
					selfBoxes.add(box);
					selfEnds.add(end);
				}else
				{
					playerBoxes.add(box);
					playerEnds.add(end);
				}
			}else if(owner instanceof LivingEntity)
			{
				mobBoxes.add(box);
				mobEnds.add(end);
			}else
			{
				// Unknown owner: treat as other player color for visibility
				playerBoxes.add(box);
				playerEnds.add(end);
			}
		}
		
		if(includeHeld.isChecked())
		{
			for(LivingEntity h : holders)
			{
				AABB handBox = getHeldTridentBox(h, partialTicks);
				Vec3 end = getHeldTridentPos(h, partialTicks);
				if(handBox == null || end == null)
					continue;
				if(h instanceof Player)
				{
					playerBoxes.add(applyExtraSize(handBox));
					playerEnds.add(end);
				}else
				{
					mobBoxes.add(applyExtraSize(handBox));
					mobEnds.add(end);
				}
			}
		}
		
		// Draw grouped to allow per-group colors
		int selfCol = selfColor.getColorI(0x80);
		int playerCol = otherPlayerColor.getColorI(0x80);
		int mobCol = mobColor.getColorI(0x80);
		
		if(style.hasBoxes())
		{
			if(!selfBoxes.isEmpty())
				RenderUtils.drawOutlinedBoxes(matrixStack, selfBoxes, selfCol,
					false);
			if(!playerBoxes.isEmpty())
				RenderUtils.drawOutlinedBoxes(matrixStack, playerBoxes,
					playerCol, false);
			if(!mobBoxes.isEmpty())
				RenderUtils.drawOutlinedBoxes(matrixStack, mobBoxes, mobCol,
					false);
		}
		
		if(style.hasLines())
		{
			if(!selfEnds.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, selfEnds,
					selfCol, false);
			if(!playerEnds.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, playerEnds,
					playerCol, false);
			if(!mobEnds.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, mobEnds,
					mobCol, false);
		}
	}
	
	private AABB applyExtraSize(AABB box)
	{
		double extra = boxSize.getExtraSize() / 2.0;
		return box.move(0, extra, 0).inflate(extra);
	}
	
	// New helpers: approximate where the held trident is, and make a small box
	// there
	private Vec3 getHeldTridentPos(LivingEntity e, float partialTicks)
	{
		InteractionHand hand = null;
		if(!e.getMainHandItem().isEmpty()
			&& e.getMainHandItem().is(Items.TRIDENT))
			hand = InteractionHand.MAIN_HAND;
		else if(!e.getOffhandItem().isEmpty()
			&& e.getOffhandItem().is(Items.TRIDENT))
			hand = InteractionHand.OFF_HAND;
		if(hand == null)
			return null;
		
		// Base position at entity feet (lerped), then add eye height - 0.1
		Vec3 base = EntityUtils.getLerpedPos(e, partialTicks);
		double yawRad = Math.toRadians(e.getYRot());
		
		// Determine which side the given hand is on.
		HumanoidArm mainArm = HumanoidArm.RIGHT;
		if(e instanceof Player pe)
			mainArm = pe.getMainArm();
		boolean rightSide =
			(mainArm == HumanoidArm.RIGHT && hand == InteractionHand.MAIN_HAND)
				|| (mainArm == HumanoidArm.LEFT
					&& hand == InteractionHand.OFF_HAND);
		double side = rightSide ? -1 : 1;
		
		double eyeH = e.getEyeHeight(e.getPose());
		double offX = Math.cos(yawRad) * 0.16 * side;
		double offY = eyeH - 0.1;
		double offZ = Math.sin(yawRad) * 0.16 * side;
		return base.add(offX, offY, offZ);
	}
	
	private AABB getHeldTridentBox(LivingEntity e, float partialTicks)
	{
		Vec3 c = getHeldTridentPos(e, partialTicks);
		if(c == null)
			return null;
		// Small cube around hand
		double r = 0.18; // half-size
		return new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
	}
}
