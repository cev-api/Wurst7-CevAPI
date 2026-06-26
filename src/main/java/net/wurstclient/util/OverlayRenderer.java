/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.awt.Color;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.wurstclient.WurstClient;
import net.wurstclient.mixinterface.IMinecraftClient;

public final class OverlayRenderer
{
	protected static final Minecraft MC = WurstClient.MC;
	protected static final IMinecraftClient IMC = WurstClient.IMC;
	
	private float progress;
	private float prevProgress;
	private BlockPos prevPos;
	
	// Rainbow state
	private double rainbowTimer;
	
	// ==================== Config record ====================
	
	public record OverlayConfig(boolean passive, Color colorA, Color colorB,
		boolean solidColor, boolean fill, float opacity, boolean rainbowMode,
		double rainbowSpeed, boolean pulseMode, double pulseSpeed,
		boolean vibrancyMode, float vibrancyIntensity, boolean depthTest)
	{}
	
	// ==================== Progress ====================
	
	public void resetProgress()
	{
		progress = 0;
		prevProgress = 0;
		prevPos = null;
	}
	
	public void updateProgress()
	{
		prevProgress = progress;
		progress = MC.gameMode.destroyProgress;
		
		if(progress < prevProgress)
			prevProgress = progress;
	}
	
	// ==================== Legacy render (used by AutoFarm, Nuker, etc.)
	// ====================
	
	/**
	 * Standard render for mining progress overlay.
	 * Yellow->red transition based on mining progress.
	 */
	public void render(PoseStack matrixStack, float partialTicks, BlockPos pos)
	{
		if(pos == null)
			return;
		
		if(prevPos != null && !pos.equals(prevPos))
			resetProgress();
		
		prevPos = pos;
		
		boolean breaksInstantly = MC.player.getAbilities().instabuild
			|| BlockUtils.getHardness(pos) >= 1;
		float p = breaksInstantly ? 1
			: Mth.lerp(partialTicks, prevProgress, progress);
		
		float red = p * 2F;
		float green = 2 - red;
		float[] rgb = {red, green, 0};
		int quadColor = RenderUtils.toIntColor(rgb, 0.25F);
		int lineColor = RenderUtils.toIntColor(rgb, 0.5F);
		
		AABB box = new AABB(pos);
		if(p < 1)
			box = box.deflate((1 - p) * 0.5);
		
		RenderUtils.drawSolidBox(matrixStack, box, quadColor, false);
		RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, false);
	}
	
	// ==================== Enhanced render with OverlayConfig
	// ====================
	
	/**
	 * Enhanced render with all customization options from OverlayConfig.
	 */
	public void render(PoseStack matrixStack, float partialTicks, BlockPos pos,
		OverlayConfig cfg)
	{
		if(pos == null || cfg == null)
			return;
		
		if(prevPos != null && !pos.equals(prevPos) && !cfg.passive())
			resetProgress();
		
		prevPos = pos;
		
		// Determine progress value
		float p;
		if(cfg.passive())
		{
			p = 1.0F;
		}else
		{
			boolean breaksInstantly = MC.player.getAbilities().instabuild
				|| BlockUtils.getHardness(pos) >= 1;
			p = breaksInstantly ? 1
				: Mth.lerp(partialTicks, prevProgress, progress);
		}
		
		float opacity = cfg.opacity();
		
		// Apply pulse mode
		if(cfg.pulseMode())
		{
			double pulseFactor =
				(System.currentTimeMillis() / 1000.0) * cfg.pulseSpeed();
			double pulse = (Math.sin(pulseFactor * Math.PI * 2) + 1.0) / 2.0;
			opacity *= (float)(0.3 + pulse * 0.7);
		}
		
		float[] rgb = computeColor(cfg, p);
		
		// Set size / shape
		if(cfg.passive())
		{
			// Use the block''s actual selection shape
			renderShape(matrixStack, pos, rgb, cfg, opacity);
		}else
		{
			// Mining: full block box, shrinking with progress
			AABB box = new AABB(pos);
			if(p < 1)
				box = box.deflate((1 - p) * 0.5);
			
			if(cfg.fill())
			{
				float quadAlpha = opacity * 0.5F;
				int quadColor = RenderUtils.toIntColor(rgb, quadAlpha);
				RenderUtils.drawSolidBox(matrixStack, box, quadColor,
					cfg.depthTest());
			}
			
			float lineAlpha = Math.min(opacity * 1.5F, 1.0F);
			int lineColor = RenderUtils.toIntColor(rgb, lineAlpha);
			RenderUtils.drawOutlinedBox(matrixStack, box, lineColor,
				cfg.depthTest());
			
			if(cfg.vibrancyMode())
				renderVibrancyFullBlock(matrixStack, pos, rgb,
					cfg.vibrancyIntensity(), opacity, cfg.fill(),
					cfg.depthTest());
		}
	}
	
	/**
	 * Renders the block''s actual selection/hitbox shape (passive mode).
	 */
	private void renderShape(PoseStack matrixStack, BlockPos pos, float[] rgb,
		OverlayConfig cfg, float opacity)
	{
		if(MC.level == null)
			return;
		
		BlockState state = MC.level.getBlockState(pos);
		VoxelShape shape = state.getShape(MC.level, pos);
		
		List<AABB> boxes = shape.toAabbs();
		if(boxes.isEmpty())
		{
			// Fallback to full block
			boxes = List.of(new AABB(pos));
		}
		
		// Offset each box by the block position
		double bx = pos.getX();
		double by = pos.getY();
		double bz = pos.getZ();
		
		float quadAlpha = opacity * 0.5F;
		float lineAlpha = Math.min(opacity * 1.5F, 1.0F);
		
		for(AABB shapeBox : boxes)
		{
			// Slightly inflate to prevent z-fighting with block faces
			AABB box = shapeBox.move(bx, by, bz).inflate(0.002);
			
			if(cfg.fill())
			{
				int quadColor = RenderUtils.toIntColor(rgb, quadAlpha);
				RenderUtils.drawSolidBox(matrixStack, box, quadColor,
					cfg.depthTest());
			}
			
			int lineColor = RenderUtils.toIntColor(rgb, lineAlpha);
			RenderUtils.drawOutlinedBox(matrixStack, box, lineColor,
				cfg.depthTest());
		}
		
		// Vibrancy uses full-block bounds (surrounding glow)
		if(cfg.vibrancyMode())
			renderVibrancyFullBlock(matrixStack, pos, rgb,
				cfg.vibrancyIntensity(), opacity, cfg.fill(), cfg.depthTest());
	}
	
	// ==================== Color computation ====================
	
	private float[] computeColor(OverlayConfig cfg, float progress)
	{
		if(cfg.rainbowMode())
		{
			rainbowTimer += 0.05 * cfg.rainbowSpeed();
			return getRainbowColor(rainbowTimer);
		}
		
		if(cfg.solidColor())
			return new float[]{cfg.colorA().getRed() / 255F,
				cfg.colorA().getGreen() / 255F, cfg.colorA().getBlue() / 255F};
		
		// Interpolate between colorA and colorB
		float t = Mth.clamp(progress, 0F, 1F);
		t = t < 0.5F ? t * 2F : 1F;
		
		return new float[]{
			Mth.lerp(t, cfg.colorA().getRed() / 255F,
				cfg.colorB().getRed() / 255F),
			Mth.lerp(t, cfg.colorA().getGreen() / 255F,
				cfg.colorB().getGreen() / 255F),
			Mth.lerp(t, cfg.colorA().getBlue() / 255F,
				cfg.colorB().getBlue() / 255F)};
	}
	
	// ==================== Vibrancy ====================
	
	private void renderVibrancyFullBlock(PoseStack matrixStack, BlockPos pos,
		float[] baseRgb, float intensity, float opacity, boolean fill,
		boolean depthTest)
	{
		// Pulsating glow: multiple concentric shells with time-varying opacity
		double time = System.currentTimeMillis() / 1000.0;
		double beat1 = (Math.sin(time * 3.0) + 1.0) / 2.0; // 0..1 slow wave
		double beat2 = (Math.sin(time * 5.0 + 1.2) + 1.0) / 2.0; // offset phase
		double beat3 = (Math.sin(time * 7.0 + 2.5) + 1.0) / 2.0; // faster wave
		
		// 4 concentric glow shells with different expansion/opacity
		for(int layer = 0; layer < 4; layer++)
		{
			double expand = (0.03 + layer * 0.045) * intensity;
			double wave = layer % 2 == 0 ? beat1 : beat2;
			double shellAlpha = opacity * (0.04 + 0.06 * layer) * intensity
				* (0.5 + wave * 0.5);
			
			AABB shellBox = new AABB(pos).inflate(expand);
			int shellColor = RenderUtils.toIntColor(baseRgb, (float)shellAlpha);
			RenderUtils.drawOutlinedBox(matrixStack, shellBox, shellColor,
				depthTest);
			
			// Secondary shimmer at opposite phase
			double shimmerAlpha =
				opacity * 0.02 * intensity * (0.5 + beat3 * 0.5);
			AABB shimmerBox = new AABB(pos).inflate(expand * 1.3);
			int shimmerColor =
				RenderUtils.toIntColor(baseRgb, (float)shimmerAlpha);
			RenderUtils.drawOutlinedBox(matrixStack, shimmerBox, shimmerColor,
				depthTest);
		}
		
		// Faint fill at the innermost glow layer
		if(fill)
		{
			double fillAlpha = opacity * 0.03 * intensity * (0.5 + beat1 * 0.5);
			AABB fillGlow = new AABB(pos).inflate(0.03 * intensity);
			int fillColor = RenderUtils.toIntColor(baseRgb, (float)fillAlpha);
			RenderUtils.drawSolidBox(matrixStack, fillGlow, fillColor,
				depthTest);
		}
		
		// Pulsating corner rays
		float rayAlpha = opacity * 0.12F * intensity;
		float[] rayRgb =
			{baseRgb[0] * 0.7F, baseRgb[1] * 0.7F, baseRgb[2] * 0.7F};
		int rayColor = RenderUtils.toIntColor(rayRgb, rayAlpha);
		drawCornerRays(matrixStack, pos, rayColor, depthTest);
		
		// Secondary rays at opposite angle offset, flickering
		float flickerAlpha =
			opacity * 0.06F * intensity * (float)(0.5 + beat3 * 0.5);
		float[] flickerRgb =
			{baseRgb[0] * 0.9F, baseRgb[1] * 0.9F, baseRgb[2] * 0.9F};
		int flickerColor = RenderUtils.toIntColor(flickerRgb, flickerAlpha);
		drawCornerRaysOffset(matrixStack, pos, flickerColor, depthTest, 0.5);
	}
	
	private void drawCornerRays(PoseStack matrixStack, BlockPos pos, int color,
		boolean depthTest)
	{
		drawCornerRaysOffset(matrixStack, pos, color, depthTest, 1.2);
	}
	
	private void drawCornerRaysOffset(PoseStack matrixStack, BlockPos pos,
		int color, boolean depthTest, double rayLength)
	{
		Vec3 center =
			new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		
		double[][] dirs = {{1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
			{1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}};
		
		for(double[] dir : dirs)
		{
			Vec3 start = center.add(dir[0] * 0.3, dir[1] * 0.3, dir[2] * 0.3);
			Vec3 end = center.add(dir[0] * rayLength, dir[1] * rayLength,
				dir[2] * rayLength);
			RenderUtils.drawLine(matrixStack, start, end, color, depthTest);
		}
	}
	
	// ==================== Rainbow ====================
	
	private static float[] getRainbowColor(double timer)
	{
		float x = (float)(timer % 1.0);
		float pi2 = (float)(Math.PI * 2);
		
		return new float[]{0.5F + 0.5F * Mth.sin(x * pi2),
			0.5F + 0.5F * Mth.sin((x + 1F / 3F) * pi2),
			0.5F + 0.5F * Mth.sin((x + 2F / 3F) * pi2)};
	}
}
