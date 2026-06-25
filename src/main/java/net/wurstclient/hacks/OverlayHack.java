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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.OverlayRenderer;
import net.wurstclient.util.OverlayRenderer.OverlayConfig;

@SearchTags({"overlay", "block outline", "selection box", "block highlight",
	"mining progress", "block color"})
public final class OverlayHack extends Hack
	implements UpdateListener, RenderListener
{
	private final OverlayRenderer renderer = new OverlayRenderer();
	
	// ==================== Selection settings ====================
	private final CheckboxSetting selectionMode = new CheckboxSetting(
		"Selection overlay",
		"Show the overlay just by looking at a block, without mining.", false);
	private final ColorSetting selectionColor =
		new ColorSetting("Selection color",
			"Color of the selection block outline.", new Color(0x00FF00));
	private final CheckboxSetting selectionFill =
		new CheckboxSetting("Selection fill",
			"Fill the box. Disable for wireframe-only outline.", false);
	private final SliderSetting selectionOpacity = new SliderSetting(
		"Selection opacity", 0.5, 0.05, 1.0, 0.05, ValueDisplay.PERCENTAGE);
	private final CheckboxSetting selectionRainbow =
		new CheckboxSetting("Selection rainbow", "Rainbow cycling.", false);
	private final SliderSetting selectionRainbowSpeed = new SliderSetting(
		"Selection rainbow speed", 1.0, 0.01, 2.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting selectionPulse =
		new CheckboxSetting("Selection pulse", "Fade in and out.", false);
	private final SliderSetting selectionPulseSpeed = new SliderSetting(
		"Selection pulse speed", 1.0, 0.01, 10.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting selectionGlow = new CheckboxSetting(
		"Selection glow", "Glowing shells + corner rays.", false);
	private final SliderSetting selectionGlowIntensity =
		new SliderSetting("Selection glow intensity", 0.5, 0.1, 1.0, 0.1,
			ValueDisplay.PERCENTAGE);
	private final CheckboxSetting selectionThroughWalls = new CheckboxSetting(
		"Selection through walls",
		"Show the selection overlay through walls instead of visible faces only.",
		false);
	
	// ==================== Break progress settings ====================
	private final ColorSetting breakStartColor = new ColorSetting(
		"Break start color", "Start color for the break progress transition.",
		new Color(0xFFFF00));
	private final ColorSetting breakEndColor = new ColorSetting(
		"Break end color", "End color for the break progress transition.",
		new Color(0xFF0000));
	private final CheckboxSetting breakSolidColor =
		new CheckboxSetting("Break solid color",
			"Use a single solid color instead of start\u2192end transition.",
			false);
	private final CheckboxSetting breakFill = new CheckboxSetting("Break fill",
		"Fill the box. Disable for wireframe-only outline.", true);
	private final SliderSetting breakOpacity = new SliderSetting(
		"Break opacity", 0.5, 0.05, 1.0, 0.05, ValueDisplay.PERCENTAGE);
	private final CheckboxSetting breakRainbow =
		new CheckboxSetting("Break rainbow", "Rainbow cycling.", false);
	private final SliderSetting breakRainbowSpeed = new SliderSetting(
		"Break rainbow speed", 1.0, 0.01, 2.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting breakPulse =
		new CheckboxSetting("Break pulse", "Fade in and out.", false);
	private final SliderSetting breakPulseSpeed = new SliderSetting(
		"Break pulse speed", 1.0, 0.01, 10.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting breakGlow = new CheckboxSetting("Break glow",
		"Glowing shells + corner rays.", false);
	private final SliderSetting breakGlowIntensity = new SliderSetting(
		"Break glow intensity", 0.5, 0.1, 1.0, 0.1, ValueDisplay.PERCENTAGE);
	
	public OverlayHack()
	{
		super("BlockOverlay");
		setCategory(Category.RENDER);
		
		// Selection settings
		addSetting(selectionMode);
		addSetting(selectionColor);
		addSetting(selectionFill);
		addSetting(selectionOpacity);
		addSetting(selectionRainbow);
		addSetting(selectionRainbowSpeed);
		addSetting(selectionPulse);
		addSetting(selectionPulseSpeed);
		addSetting(selectionGlow);
		addSetting(selectionGlowIntensity);
		addSetting(selectionThroughWalls);
		
		// Break progress settings
		addSetting(breakStartColor);
		addSetting(breakEndColor);
		addSetting(breakSolidColor);
		addSetting(breakFill);
		addSetting(breakOpacity);
		addSetting(breakRainbow);
		addSetting(breakRainbowSpeed);
		addSetting(breakPulse);
		addSetting(breakPulseSpeed);
		addSetting(breakGlow);
		addSetting(breakGlowIntensity);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		renderer.resetProgress();
	}
	
	@Override
	public void onUpdate()
	{
		if(isBreaking())
			renderer.updateProgress();
		else
			renderer.resetProgress();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.gameMode == null || MC.player == null)
			return;
		
		if(!(MC.hitResult instanceof BlockHitResult blockHitResult)
			|| blockHitResult.getType() != HitResult.Type.BLOCK)
			return;
		
		boolean selection = selectionMode.isChecked() && !isBreaking();
		boolean breaking = isBreaking();
		
		if(selection)
			renderer.render(matrixStack, partialTicks,
				blockHitResult.getBlockPos(), buildSelectionConfig());
		
		if(breaking)
			renderer.render(matrixStack, partialTicks,
				blockHitResult.getBlockPos(), buildBreakConfig());
	}
	
	private boolean isBreaking()
	{
		if(MC.gameMode == null)
			return false;
		
		if(MC.gameMode.isDestroying())
			return true;
		
		return MC.options.keyAttack.isDown()
			&& MC.hitResult instanceof BlockHitResult blockHitResult
			&& blockHitResult.getType() == HitResult.Type.BLOCK
			&& MC.gui.screen() == null;
	}
	
	// --- Config builders ---
	
	private OverlayConfig buildBreakConfig()
	{
		return new OverlayConfig(false, breakStartColor.getColor(),
			breakEndColor.getColor(), breakSolidColor.isChecked(),
			breakFill.isChecked(), breakOpacity.getValueF(),
			breakRainbow.isChecked(), breakRainbowSpeed.getValue(),
			breakPulse.isChecked(), breakPulseSpeed.getValue(),
			breakGlow.isChecked(), breakGlowIntensity.getValueF(), false);
	}
	
	private OverlayConfig buildSelectionConfig()
	{
		return new OverlayConfig(true, selectionColor.getColor(),
			selectionColor.getColor(), true, selectionFill.isChecked(),
			selectionOpacity.getValueF(), selectionRainbow.isChecked(),
			selectionRainbowSpeed.getValue(), selectionPulse.isChecked(),
			selectionPulseSpeed.getValue(), selectionGlow.isChecked(),
			selectionGlowIntensity.getValueF(),
			!selectionThroughWalls.isChecked());
	}
	
	/**
	 * Checked by the mixin to cancel vanilla's dark-grey block outline
	 * when the custom selection overlay is active.
	 */
	public static boolean shouldCancelVanillaBlockOutline()
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getHax() == null)
			return false;
		OverlayHack hack = WurstClient.INSTANCE.getHax().overlayHack;
		return hack != null && hack.isEnabled()
			&& hack.selectionMode.isChecked();
	}
}
