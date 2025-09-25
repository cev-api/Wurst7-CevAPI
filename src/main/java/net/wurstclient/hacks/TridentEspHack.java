/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
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
	private final ArrayList<TridentEntity> thrown = new ArrayList<>();
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
		
		for(Entity e : MC.world.getEntities())
		{
			if(e instanceof TridentEntity)
				thrown.add((TridentEntity)e);
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
		ItemStack main = e.getMainHandStack();
		ItemStack off = e.getOffHandStack();
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
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(!style.hasBoxes() && !style.hasLines())
			return;
		
		if(colorByOwner.isChecked())
			renderByOwner(matrixStack, partialTicks);
		else
			renderSingleColor(matrixStack, partialTicks);
	}
	
	private void renderSingleColor(MatrixStack matrixStack, float partialTicks)
	{
		int lineColor;
		if(useFixedColor.isChecked())
			lineColor = fixedColor.getColorI(0x80);
		else
			lineColor =
				RenderUtils.toIntColor(RenderUtils.getRainbowColor(), 0.5F);
		
		if(style.hasBoxes())
		{
			List<Box> boxes = new ArrayList<>();
			for(TridentEntity t : thrown)
				boxes.add(
					applyExtraSize(EntityUtils.getLerpedBox(t, partialTicks)));
			for(LivingEntity h : holders)
			{
				Box handBox = getHeldTridentBox(h, partialTicks);
				if(handBox != null)
					boxes.add(applyExtraSize(handBox));
			}
			if(!boxes.isEmpty())
				RenderUtils.drawOutlinedBoxes(matrixStack, boxes, lineColor,
					false);
		}
		
		if(style.hasLines())
		{
			List<Vec3d> ends = new ArrayList<>();
			for(TridentEntity t : thrown)
				ends.add(EntityUtils.getLerpedBox(t, partialTicks).getCenter());
			for(LivingEntity h : holders)
			{
				Vec3d hand = getHeldTridentPos(h, partialTicks);
				if(hand != null)
					ends.add(hand);
			}
			if(!ends.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, ends,
					lineColor, false);
		}
	}
	
	private void renderByOwner(MatrixStack matrixStack, float partialTicks)
	{
		ArrayList<Box> selfBoxes = new ArrayList<>();
		ArrayList<Box> playerBoxes = new ArrayList<>();
		ArrayList<Box> mobBoxes = new ArrayList<>();
		ArrayList<Vec3d> selfEnds = new ArrayList<>();
		ArrayList<Vec3d> playerEnds = new ArrayList<>();
		ArrayList<Vec3d> mobEnds = new ArrayList<>();
		
		for(TridentEntity t : thrown)
		{
			Entity owner = t.getOwner();
			Box box = applyExtraSize(EntityUtils.getLerpedBox(t, partialTicks));
			Vec3d end = box.getCenter();
			if(owner instanceof PlayerEntity)
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
				Box handBox = getHeldTridentBox(h, partialTicks);
				Vec3d end = getHeldTridentPos(h, partialTicks);
				if(handBox == null || end == null)
					continue;
				if(h instanceof PlayerEntity)
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
	
	private Box applyExtraSize(Box box)
	{
		double extra = boxSize.getExtraSize() / 2.0;
		return box.offset(0, extra, 0).expand(extra);
	}
	
	// New helpers: approximate where the held trident is, and make a small box
	// there
	private Vec3d getHeldTridentPos(LivingEntity e, float partialTicks)
	{
		Hand hand = null;
		if(!e.getMainHandStack().isEmpty()
			&& e.getMainHandStack().isOf(Items.TRIDENT))
			hand = Hand.MAIN_HAND;
		else if(!e.getOffHandStack().isEmpty()
			&& e.getOffHandStack().isOf(Items.TRIDENT))
			hand = Hand.OFF_HAND;
		if(hand == null)
			return null;
		
		// Base position at entity feet (lerped), then add eye height - 0.1
		Vec3d base = EntityUtils.getLerpedPos(e, partialTicks);
		double yawRad = Math.toRadians(e.getYaw());
		
		// Determine which side the given hand is on.
		Arm mainArm = Arm.RIGHT;
		if(e instanceof PlayerEntity pe)
			mainArm = pe.getMainArm();
		boolean rightSide = (mainArm == Arm.RIGHT && hand == Hand.MAIN_HAND)
			|| (mainArm == Arm.LEFT && hand == Hand.OFF_HAND);
		double side = rightSide ? -1 : 1;
		
		double eyeH = e.getEyeHeight(e.getPose());
		double offX = Math.cos(yawRad) * 0.16 * side;
		double offY = eyeH - 0.1;
		double offZ = Math.sin(yawRad) * 0.16 * side;
		return base.add(offX, offY, offZ);
	}
	
	private Box getHeldTridentBox(LivingEntity e, float partialTicks)
	{
		Vec3d c = getHeldTridentPos(e, partialTicks);
		if(c == null)
			return null;
		// Small cube around hand
		double r = 0.18; // half-size
		return new Box(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
	}
}
