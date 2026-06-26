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
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.*;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.Vec3;

@SearchTags({"mob esp", "MobTracers", "mob tracers"})
public final class MobEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final MobEspStyleSetting style = new MobEspStyleSetting();
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting("Box size",
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each mob.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.",
		EspBoxSizeSetting.BoxSize.ACCURATE);
	
	private final CheckboxSetting fillShapes = new CheckboxSetting(
		"Fill shapes", "Render filled versions of the ESP shapes.", true);
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final CheckboxSetting lastAttackerTracer =
		new CheckboxSetting("Last attacker tracer",
			"Draw a tracer to the mob that last damaged you.", true);
	private final CheckboxSetting chargingCreeperTracer = new CheckboxSetting(
		"Charging creeper tracer",
		"Draw a flashing tracer to creepers that are about to explode.", true);
	
	// New color options to match MobSearch
	private final CheckboxSetting useRainbow =
		new CheckboxSetting("Rainbow colors",
			"Use a rainbow color instead of the fixed color.", false);
	private final ColorSetting color = new ColorSetting("Color",
		"Fixed color used when Rainbow colors is disabled.", Color.RED);
	
	private final EntityFilterList entityFilters =
		new EntityFilterList(FilterHostileSetting.genericVision(false),
			FilterNeutralSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterPassiveSetting.genericVision(false),
			FilterPassiveWaterSetting.genericVision(false),
			FilterBatsSetting.genericVision(false),
			FilterSlimesSetting.genericVision(false),
			FilterPetsSetting.genericVision(false),
			FilterVillagersSetting.genericVision(false),
			FilterZombieVillagersSetting.genericVision(false),
			FilterGolemsSetting.genericVision(false),
			FilterPiglinsSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterZombiePiglinsSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterEndermenSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterShulkersSetting.genericVision(false),
			FilterAllaysSetting.genericVision(false),
			FilterInvisibleSetting.genericVision(false),
			FilterNamedSetting.genericVision(false),
			FilterArmorStandsSetting.genericVision(true));
	
	private final ArrayList<LivingEntity> mobs = new ArrayList<>();
	private final ArrayList<ShulkerBullet> shulkerBullets = new ArrayList<>();
	private final ArrayList<WitherSkull> witherSkulls = new ArrayList<>();
	
	// New: optionally show detected count in HackList
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of detected mobs to this hack's entry in the HackList.",
		false);
	
	// Villager professions label
	private final CheckboxSetting showVillagerProfessions =
		new CheckboxSetting("Show villager professions",
			"Displays the profession of villagers above their heads.", false);
	
	// Range limiter
	private final CheckboxSetting rangeLimit = new CheckboxSetting(
		"Range limit", "Only show mobs within the configured range.", false);
	
	private final SliderSetting espRange = new SliderSetting("ESP range", 64, 1,
		150, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	// Above-ground filter
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show mobs at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	
	private final CheckboxSetting highlightShulkerProjectiles =
		new CheckboxSetting("Highlight shulker projectiles",
			"Also outline the tracking projectiles fired by shulkers.", false);
	
	private final CheckboxSetting highlightWitherProjectiles =
		new CheckboxSetting("Highlight wither projectiles",
			"Also outline the projectiles fired by withers.", false);
	
	private static final long LAST_ATTACKER_TIMEOUT_MS = 5000;
	private static final long CREEPER_FLASH_PERIOD_MS = 450L;
	private UUID lastAttackerUuid;
	private long lastAttackerExpiresAt;
	private int lastPlayerHurtTimeSeen;
	
	private int foundCount;
	
	public MobEspHack()
	{
		super("MobESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(fillShapes);
		addSetting(tracerFlash);
		addSetting(lastAttackerTracer);
		addSetting(chargingCreeperTracer);
		addSetting(useRainbow);
		addSetting(color);
		entityFilters.forEach(this::addSetting);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(highlightShulkerProjectiles);
		addSetting(highlightWitherProjectiles);
		addSetting(showCountInHackList);
		addSetting(showVillagerProfessions);
		addSetting(rangeLimit);
		addSetting(espRange);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		lastAttackerUuid = null;
		lastAttackerExpiresAt = 0;
		lastPlayerHurtTimeSeen = MC.player == null ? 0 : MC.player.hurtTime;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		foundCount = 0;
		shulkerBullets.clear();
		witherSkulls.clear();
		lastAttackerUuid = null;
		lastAttackerExpiresAt = 0;
		lastPlayerHurtTimeSeen = 0;
	}
	
	@Override
	public void onUpdate()
	{
		mobs.clear();
		shulkerBullets.clear();
		witherSkulls.clear();
		updateLastAttackerTracking();
		
		Stream<LivingEntity> stream = StreamSupport
			.stream(MC.level.entitiesForRendering().spliterator(), false)
			.filter(LivingEntity.class::isInstance).map(e -> (LivingEntity)e)
			.filter(e -> !(e instanceof Player))
			.filter(e -> !e.isRemoved() && e.getHealth() > 0);
		// optionally filter out mobs below the configured Y level
		if(onlyAboveGround.isChecked())
			stream = stream.filter(e -> e.getY() >= aboveGroundY.getValue());
		
		// optionally limit range
		if(rangeLimit.isChecked())
			stream = stream
				.filter(e -> MC.player.distanceTo(e) <= espRange.getValue());
		
		stream = entityFilters.applyTo(stream);
		
		mobs.addAll(stream.collect(Collectors.toList()));
		
		if(highlightShulkerProjectiles.isChecked())
		{
			for(Entity entity : MC.level.entitiesForRendering())
				if(entity instanceof ShulkerBullet bullet
					&& !bullet.isRemoved())
					shulkerBullets.add(bullet);
		}
		
		if(highlightWitherProjectiles.isChecked())
		{
			for(Entity entity : MC.level.entitiesForRendering())
				if(entity instanceof WitherSkull skull && !skull.isRemoved())
					witherSkulls.add(skull);
		}
		
		// update count for HUD (clamped to 999)
		int highlighted = mobs.size();
		if(highlightShulkerProjectiles.isChecked())
			highlighted += shulkerBullets.size();
		if(highlightWitherProjectiles.isChecked())
			highlighted += witherSkulls.size();
		foundCount = Math.min(highlighted, 999);
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines() || lastAttackerTracer.isChecked()
			|| chargingCreeperTracer.isChecked())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		MobEspStyleSetting.Shape shape = style.getShape();
		boolean glowMode = shape == MobEspStyleSetting.Shape.GLOW;
		boolean drawShape = !glowMode && shape != MobEspStyleSetting.Shape.NONE;
		boolean drawLines = style.hasLines();
		boolean drawSpecialTracers =
			lastAttackerTracer.isChecked() || chargingCreeperTracer.isChecked();
		boolean drawFill = drawShape && fillShapes.isChecked();
		float projectileOutlineAlpha = 0.85F;
		float projectileFillAlpha = 0.35F;
		float projectileGlowFillAlpha = 0.6F;
		
		int anticipatedSize = mobs.size();
		if(highlightShulkerProjectiles.isChecked())
			anticipatedSize += shulkerBullets.size();
		if(highlightWitherProjectiles.isChecked())
			anticipatedSize += witherSkulls.size();
		
		ArrayList<ColoredBox> outlineShapes =
			drawShape ? new ArrayList<>(anticipatedSize) : null;
		ArrayList<ColoredBox> filledShapes =
			drawFill ? new ArrayList<>(anticipatedSize) : null;
		ArrayList<ColoredPoint> ends = (drawLines || drawSpecialTracers)
			? new ArrayList<>(anticipatedSize) : null;
		
		if(drawShape || drawLines || drawSpecialTracers)
		{
			double extraSize = drawShape ? boxSize.getExtraSize() / 2D : 0;
			
			for(LivingEntity e : mobs)
			{
				AABB lerpedBox = EntityUtils.getLerpedBox(e, partialTicks);
				float[] rgb = getColorRgb();
				int outlineColor = RenderUtils.toIntColor(rgb, 0.5F);
				if(drawShape)
				{
					AABB box =
						lerpedBox.move(0, extraSize, 0).inflate(extraSize);
					outlineShapes.add(new ColoredBox(box, outlineColor));
					
					if(filledShapes != null)
					{
						int fillColor = RenderUtils.toIntColor(rgb, 0.15F);
						filledShapes.add(new ColoredBox(box, fillColor));
					}
				}
				
				if(drawLines && ends != null)
					ends.add(
						new ColoredPoint(lerpedBox.getCenter(), outlineColor));
			}
			
			if(drawSpecialTracers && ends != null)
				addSpecialTracers(ends, partialTicks, drawLines);
			
			if(highlightShulkerProjectiles.isChecked())
			{
				for(ShulkerBullet bullet : shulkerBullets)
				{
					AABB lerpedBox =
						EntityUtils.getLerpedBox(bullet, partialTicks);
					float[] rgb = getColorRgb();
					int outlineColor =
						RenderUtils.toIntColor(rgb, projectileOutlineAlpha);
					
					if(drawShape)
					{
						AABB box =
							lerpedBox.move(0, extraSize, 0).inflate(extraSize);
						outlineShapes.add(new ColoredBox(box, outlineColor));
						
						if(filledShapes != null)
						{
							int fillColor = RenderUtils.toIntColor(rgb,
								projectileFillAlpha);
							filledShapes.add(new ColoredBox(box, fillColor));
						}
					}
					
					if(drawLines && ends != null)
					{
						int tracerColor = outlineColor;
						if(tracerFlash.isChecked())
							tracerColor = RenderUtils.flashColor(tracerColor);
						ends.add(new ColoredPoint(lerpedBox.getCenter(),
							tracerColor));
					}
				}
			}
			
			if(highlightWitherProjectiles.isChecked())
			{
				for(WitherSkull skull : witherSkulls)
				{
					AABB lerpedBox =
						EntityUtils.getLerpedBox(skull, partialTicks);
					float[] rgb = getColorRgb();
					int outlineColor =
						RenderUtils.toIntColor(rgb, projectileOutlineAlpha);
					
					if(drawShape)
					{
						AABB box =
							lerpedBox.move(0, extraSize, 0).inflate(extraSize);
						outlineShapes.add(new ColoredBox(box, outlineColor));
						
						if(filledShapes != null)
						{
							int fillColor = RenderUtils.toIntColor(rgb,
								projectileFillAlpha);
							filledShapes.add(new ColoredBox(box, fillColor));
						}
					}
					
					if(drawLines && ends != null)
						ends.add(new ColoredPoint(lerpedBox.getCenter(),
							outlineColor));
				}
			}
		}
		
		if(!glowMode)
		{
			if(filledShapes != null && !filledShapes.isEmpty())
			{
				switch(shape)
				{
					case BOX -> RenderUtils.drawSolidBoxes(matrixStack,
						filledShapes, false);
					case OCTAHEDRON -> RenderUtils
						.drawSolidOctahedrons(matrixStack, filledShapes, false);
					default ->
						{
						}
				}
			}
			
			if(outlineShapes != null && !outlineShapes.isEmpty())
			{
				switch(shape)
				{
					case BOX -> RenderUtils.drawOutlinedBoxes(matrixStack,
						outlineShapes, false);
					case OCTAHEDRON -> RenderUtils.drawOutlinedOctahedrons(
						matrixStack, outlineShapes, false);
					default ->
						{
						}
				}
			}
		}
		
		if(ends != null && !ends.isEmpty())
		{
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
		}
		
		if(glowMode && highlightShulkerProjectiles.isChecked()
			&& !shulkerBullets.isEmpty())
			renderShulkerProjectileFallback(matrixStack, partialTicks);
		
		if(glowMode && highlightWitherProjectiles.isChecked()
			&& !witherSkulls.isEmpty())
			renderWitherProjectileFallback(matrixStack, partialTicks);
		
		// render villager professions
		if(showVillagerProfessions.isChecked())
			renderVillagerProfessions(matrixStack, partialTicks);
	}
	
	private static final double VANILLA_NAMETAG_DIST = 5.0;
	
	private void renderVillagerProfessions(PoseStack matrices,
		float partialTicks)
	{
		Vec3 cam = RenderUtils.getCameraPos();
		var camEntity = MC.getCameraEntity();
		
		for(LivingEntity e : mobs)
		{
			if(!(e instanceof Villager villager))
				continue;
				
			// If vanilla is already showing the profession name tag
			// up close, skip the MobESP label to avoid duplicates.
			if(MC.player.distanceTo(villager) < VANILLA_NAMETAG_DIST)
				continue;
			
			String profession = getVillagerProfessionName(villager);
			if(profession.isEmpty())
				continue;
			
			// position above head
			AABB box = EntityUtils.getLerpedBox(e, partialTicks);
			double lx = box.getCenter().x;
			double ly = box.maxY + 0.6;
			double lz = box.getCenter().z;
			
			float[] rgb = getColorRgb();
			int labelColor = RenderUtils.toIntColor(rgb, 0.9F);
			
			matrices.pushPose();
			matrices.translate(lx - cam.x, ly - cam.y, lz - cam.z);
			if(camEntity != null)
			{
				matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
				matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
			}
			matrices.mulPose(Axis.YP.rotationDegrees(180.0F));
			
			double dist = MC.player.distanceTo(e);
			float scale = 0.025F * (float)Math.max(1.0, dist * 0.1);
			matrices.scale(scale, -scale, scale);
			
			float w = MC.font.width(profession) / 2F;
			int baseAlpha = (labelColor >>> 24) & 0xFF;
			int bgAlpha = (int)Math
				.round(MC.options.getBackgroundOpacity(0.25F) * baseAlpha);
			int bg = bgAlpha << 24;
			int strokeColor =
				(Math.max(0, Math.min(255, baseAlpha)) << 24) | 0x000000;
			var matrix = matrices.last().pose();
			RenderUtils.drawOutlinedTextInBatch(MC.font, profession, -w, 0,
				labelColor, strokeColor, matrix, Font.DisplayMode.SEE_THROUGH,
				bg, 0xF000F0);
			matrices.popPose();
		}
	}
	
	private String getVillagerProfessionName(Villager villager)
	{
		ResourceKey<VillagerProfession> key =
			villager.getVillagerData().profession().unwrapKey().orElse(null);
		
		if(key == null)
			return "";
		
		if(key == VillagerProfession.NONE)
			return "Unemployed";
		if(key == VillagerProfession.NITWIT)
			return "Nitwit";
		if(key == VillagerProfession.FARMER)
			return "Farmer";
		if(key == VillagerProfession.FISHERMAN)
			return "Fisherman";
		if(key == VillagerProfession.SHEPHERD)
			return "Shepherd";
		if(key == VillagerProfession.FLETCHER)
			return "Fletcher";
		if(key == VillagerProfession.LIBRARIAN)
			return "Librarian";
		if(key == VillagerProfession.CARTOGRAPHER)
			return "Cartographer";
		if(key == VillagerProfession.CLERIC)
			return "Cleric";
		if(key == VillagerProfession.ARMORER)
			return "Armorer";
		if(key == VillagerProfession.WEAPONSMITH)
			return "Weaponsmith";
		if(key == VillagerProfession.TOOLSMITH)
			return "Toolsmith";
		if(key == VillagerProfession.BUTCHER)
			return "Butcher";
		if(key == VillagerProfession.LEATHERWORKER)
			return "Leatherworker";
		if(key == VillagerProfession.MASON)
			return "Mason";
		
		return "";
	}
	
	private void renderShulkerProjectileFallback(PoseStack matrixStack,
		float partialTicks)
	{
		ArrayList<ColoredBox> filledShapes =
			new ArrayList<>(shulkerBullets.size());
		double extraSize = boxSize.getExtraSize() / 2D;
		int fillColor = RenderUtils.toIntColor(getColorRgb(), 0.6F);
		
		for(ShulkerBullet bullet : shulkerBullets)
		{
			AABB box = EntityUtils.getLerpedBox(bullet, partialTicks)
				.move(0, extraSize, 0).inflate(extraSize);
			filledShapes.add(new ColoredBox(box, fillColor));
		}
		
		RenderUtils.drawSolidBoxes(matrixStack, filledShapes, false);
	}
	
	private void renderWitherProjectileFallback(PoseStack matrixStack,
		float partialTicks)
	{
		ArrayList<ColoredBox> filledShapes =
			new ArrayList<>(witherSkulls.size());
		double extraSize = boxSize.getExtraSize() / 2D;
		int fillColor = RenderUtils.toIntColor(getColorRgb(), 0.6F);
		
		for(WitherSkull skull : witherSkulls)
		{
			AABB box = EntityUtils.getLerpedBox(skull, partialTicks)
				.move(0, extraSize, 0).inflate(extraSize);
			filledShapes.add(new ColoredBox(box, fillColor));
		}
		
		RenderUtils.drawSolidBoxes(matrixStack, filledShapes, false);
	}
	
	public RenderStyleInfo getRenderStyleInfo()
	{
		MobEspStyleSetting.Shape shape = style.getShape();
		RenderShape renderShape = switch(shape)
		{
			case BOX -> RenderShape.BOX;
			case OCTAHEDRON -> RenderShape.OCTAHEDRON;
			case GLOW -> RenderShape.GLOW;
			default -> RenderShape.NONE;
		};
		
		boolean drawShape = renderShape == RenderShape.BOX
			|| renderShape == RenderShape.OCTAHEDRON;
		
		double extra = drawShape ? boxSize.getExtraSize() / 2D : 0;
		boolean fill = drawShape && fillShapes.isChecked();
		
		return new RenderStyleInfo(renderShape,
			style.hasLines() || lastAttackerTracer.isChecked()
				|| chargingCreeperTracer.isChecked(),
			fill, extra);
	}
	
	private float[] getColorRgb()
	{
		if(useRainbow.isChecked())
			return RenderUtils.getRainbowColor();
		return color.getColorF();
	}
	
	public boolean shouldRenderEntity(LivingEntity entity)
	{
		if(entity == null || entity instanceof Player)
			return false;
		if(entity.isRemoved() || entity.getHealth() <= 0)
			return false;
		if(onlyAboveGround.isChecked()
			&& entity.getY() < aboveGroundY.getValue())
			return false;
		
		return entityFilters.testOne(entity);
	}
	
	private boolean isChargingCreeperTracer(LivingEntity entity)
	{
		if(!chargingCreeperTracer.isChecked() || !(entity instanceof Creeper))
			return false;
		
		Creeper creeper = (Creeper)entity;
		return creeper.getSwellDir() > 0 || creeper.isIgnited()
			|| creeper.isPowered();
	}
	
	private void addSpecialTracers(ArrayList<ColoredPoint> ends,
		float partialTicks, boolean normalMobTracersEnabled)
	{
		float[] rgb = getColorRgb();
		int baseTracerColor = RenderUtils.toIntColor(rgb, 0.5F);
		boolean drawCreeperFlashNow = isCreeperFlashVisibleNow();
		
		if(lastAttackerTracer.isChecked() && MC.level != null
			&& lastAttackerUuid != null)
		{
			Entity tracked = MC.level.getEntity(lastAttackerUuid);
			if(tracked instanceof LivingEntity living
				&& !(living instanceof Player) && !living.isRemoved()
				&& living.getHealth() > 0
				&& (!normalMobTracersEnabled || !mobs.contains(living)))
			{
				AABB attackerBox =
					EntityUtils.getLerpedBox(living, partialTicks);
				ends.add(
					new ColoredPoint(attackerBox.getCenter(), baseTracerColor));
			}
		}
		
		if(chargingCreeperTracer.isChecked())
		{
			for(Entity entity : MC.level.entitiesForRendering())
			{
				if(!(entity instanceof Creeper creeper) || entity.isRemoved()
					|| creeper.getHealth() <= 0
					|| (normalMobTracersEnabled && mobs.contains(creeper)))
					continue;
				if(!isChargingCreeperTracer(creeper))
					continue;
				if(!drawCreeperFlashNow)
					continue;
				
				AABB creeperBox =
					EntityUtils.getLerpedBox(creeper, partialTicks);
				ends.add(
					new ColoredPoint(creeperBox.getCenter(), baseTracerColor));
			}
		}
	}
	
	private boolean isCreeperFlashVisibleNow()
	{
		long phase = System.currentTimeMillis() % CREEPER_FLASH_PERIOD_MS;
		return phase < (CREEPER_FLASH_PERIOD_MS / 2L);
	}
	
	private void updateLastAttackerTracking()
	{
		if(MC.player == null)
		{
			lastPlayerHurtTimeSeen = 0;
			return;
		}
		
		int hurtTime = MC.player.hurtTime;
		if(hurtTime > lastPlayerHurtTimeSeen)
		{
			Entity attacker = null;
			if(MC.player.getLastDamageSource() != null)
			{
				attacker = MC.player.getLastDamageSource().getEntity();
				if(attacker == null)
					attacker =
						MC.player.getLastDamageSource().getDirectEntity();
			}
			
			if(attacker instanceof LivingEntity living
				&& !(living instanceof Player))
			{
				lastAttackerUuid = living.getUUID();
				lastAttackerExpiresAt =
					System.currentTimeMillis() + LAST_ATTACKER_TIMEOUT_MS;
			}
		}
		
		lastPlayerHurtTimeSeen = hurtTime;
		
		if(lastAttackerUuid != null
			&& System.currentTimeMillis() > lastAttackerExpiresAt)
			lastAttackerUuid = null;
	}
	
	public static enum RenderShape
	{
		NONE,
		BOX,
		OCTAHEDRON,
		GLOW;
	}
	
	public static final class RenderStyleInfo
	{
		public final RenderShape shape;
		public final boolean drawLines;
		public final boolean fillShapes;
		public final double extraSize;
		
		public RenderStyleInfo(RenderShape shape, boolean drawLines,
			boolean fillShapes, double extraSize)
		{
			this.shape = shape;
			this.drawLines = drawLines;
			this.fillShapes = fillShapes;
			this.extraSize = extraSize;
		}
	}
	
	public Integer getGlowColor(LivingEntity entity)
	{
		if(!isEnabled())
			return null;
		if(style.getShape() != MobEspStyleSetting.Shape.GLOW)
			return null;
		try
		{
			if(!mobs.contains(entity))
				return null;
		}catch(IllegalStateException e)
		{
			// Entity doesn't have an ID yet (e.g. spawner display entity).
			// mobs.contains() calls Entity.equals() which calls getId().
			return null;
		}
		return RenderUtils.toIntColor(getColorRgb(), 1F);
	}
	
	private static final class MobEspStyleSetting
		extends EnumSetting<MobEspStyleSetting.Style>
	{
		private MobEspStyleSetting()
		{
			super("Style", Style.values(), Style.GLOW);
		}
		
		public Shape getShape()
		{
			return getSelected().shape;
		}
		
		public boolean hasLines()
		{
			return getSelected().lines;
		}
		
		enum Shape
		{
			NONE,
			BOX,
			OCTAHEDRON,
			GLOW;
		}
		
		private enum Style
		{
			BOXES("Boxes only", Shape.BOX, false),
			OCTAHEDRONS("Octahedrons only", Shape.OCTAHEDRON, false),
			LINES("Lines only", Shape.NONE, true),
			LINES_AND_BOXES("Lines and boxes", Shape.BOX, true),
			LINES_AND_OCTAHEDRONS("Lines and octahedrons", Shape.OCTAHEDRON,
				true),
			GLOW("Glow only", Shape.GLOW, false),
			LINES_AND_GLOW("Lines and glow", Shape.GLOW, true);
			
			private final String name;
			private final Shape shape;
			private final boolean lines;
			
			private Style(String name, Shape shape, boolean lines)
			{
				this.name = name;
				this.shape = shape;
				this.lines = lines;
			}
			
			@Override
			public String toString()
			{
				return name;
			}
		}
	}
}
