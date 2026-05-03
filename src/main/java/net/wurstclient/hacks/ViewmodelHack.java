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
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.text.WText;

@SearchTags({"view model", "item position", "hand position", "hand render",
	"custom hands", "item offset", "item rotation", "item tint", "glint"})
public final class ViewmodelHack extends Hack
{
	private final HandSettings rightHand =
		new HandSettings("Right hand", new Color(0x55AAFF));
	private final HandSettings leftHand =
		new HandSettings("Left hand", new Color(0xFF77AA));
	
	public ViewmodelHack()
	{
		super("Viewmodel");
		setCategory(Category.RENDER);
		
		rightHand.addSettings();
		leftHand.addSettings();
	}
	
	public void applyTransform(AbstractClientPlayer player,
		InteractionHand hand, PoseStack matrices)
	{
		if(!isEnabled())
			return;
		
		HandSettings settings = getHandSettings(player, hand);
		if(settings == null || settings.hidden.isChecked())
			return;
		
		matrices.translate(settings.x.getValueF(), settings.y.getValueF(),
			settings.z.getValueF());
		matrices.mulPose(Axis.XP.rotationDegrees(settings.pitch.getValueF()));
		matrices.mulPose(Axis.YP.rotationDegrees(settings.yaw.getValueF()));
		matrices.mulPose(Axis.ZP.rotationDegrees(settings.roll.getValueF()));
	}
	
	public boolean shouldHide(AbstractClientPlayer player, InteractionHand hand)
	{
		if(!isEnabled())
			return false;
		
		HandSettings settings = getHandSettings(player, hand);
		return settings != null && settings.hidden.isChecked();
	}
	
	public boolean shouldForceGlint(AbstractClientPlayer player,
		InteractionHand hand)
	{
		if(!isEnabled())
			return false;
		
		HandSettings settings = getHandSettings(player, hand);
		return settings != null && settings.artificialGlint.isChecked()
			&& !settings.hidden.isChecked();
	}
	
	public boolean shouldForceGlint(HumanoidArm arm)
	{
		if(!isEnabled())
			return false;
		
		HandSettings settings = arm == HumanoidArm.RIGHT ? rightHand : leftHand;
		return settings.artificialGlint.isChecked()
			&& !settings.hidden.isChecked();
	}
	
	public int getOverlayColor(AbstractClientPlayer player,
		InteractionHand hand)
	{
		HandSettings settings = getHandSettings(player, hand);
		return settings != null ? settings.getTintColor() : 0;
	}
	
	public int getOverlayColor(HumanoidArm arm)
	{
		if(!isEnabled())
			return 0;
		
		HandSettings settings = arm == HumanoidArm.RIGHT ? rightHand : leftHand;
		return settings.getTintColor();
	}
	
	public float getOpacity(AbstractClientPlayer player, InteractionHand hand)
	{
		HandSettings settings = getHandSettings(player, hand);
		if(settings == null || settings.hidden.isChecked())
			return 1;
		
		return settings.opacity.getValueF();
	}
	
	public float getOpacity(HumanoidArm arm)
	{
		if(!isEnabled())
			return 1;
		
		HandSettings settings = arm == HumanoidArm.RIGHT ? rightHand : leftHand;
		return settings.hidden.isChecked() ? 0 : settings.opacity.getValueF();
	}
	
	private HandSettings getHandSettings(AbstractClientPlayer player,
		InteractionHand hand)
	{
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND
			? player.getMainArm() : player.getMainArm().getOpposite();
		return arm == HumanoidArm.RIGHT ? rightHand : leftHand;
	}
	
	private final class HandSettings
	{
		private final SliderSetting x;
		private final SliderSetting y;
		private final SliderSetting z;
		private final SliderSetting pitch;
		private final SliderSetting yaw;
		private final SliderSetting roll;
		private final SliderSetting opacity;
		private final CheckboxSetting hidden;
		private final ColorSetting overlayColor;
		private final SliderSetting overlayOpacity;
		private final CheckboxSetting artificialGlint;
		private final SettingGroup group;
		
		private HandSettings(String prefix, Color color)
		{
			x = new SliderSetting(prefix + " X", 0, -2, 2, 0.01,
				ValueDisplay.DECIMAL);
			y = new SliderSetting(prefix + " Y", 0, -2, 2, 0.01,
				ValueDisplay.DECIMAL);
			z = new SliderSetting(prefix + " Z", 0, -2, 2, 0.01,
				ValueDisplay.DECIMAL);
			pitch = new SliderSetting(prefix + " pitch", 0, -180, 180, 1,
				ValueDisplay.DEGREES);
			yaw = new SliderSetting(prefix + " yaw", 0, -180, 180, 1,
				ValueDisplay.DEGREES);
			roll = new SliderSetting(prefix + " roll", 0, -180, 180, 1,
				ValueDisplay.DEGREES);
			opacity = new SliderSetting(prefix + " opacity", 1, 0, 1, 0.01,
				ValueDisplay.PERCENTAGE);
			hidden = new CheckboxSetting(prefix + " hidden", false);
			overlayColor = new ColorSetting(prefix + " overlay color", color);
			overlayOpacity = new SliderSetting(prefix + " overlay opacity", 0,
				0, 1, 0.01, ValueDisplay.PERCENTAGE);
			artificialGlint =
				new CheckboxSetting(prefix + " artificial glint", false);
			
			group = new SettingGroup(prefix,
				WText.literal("Position, rotation, opacity, tint, and glint."),
				false, true).addChildren(x, y, z, pitch, yaw, roll, opacity,
					hidden, overlayColor, overlayOpacity, artificialGlint);
		}
		
		private void addSettings()
		{
			addSetting(x);
			addSetting(y);
			addSetting(z);
			addSetting(pitch);
			addSetting(yaw);
			addSetting(roll);
			addSetting(opacity);
			addSetting(hidden);
			addSetting(overlayColor);
			addSetting(overlayOpacity);
			addSetting(artificialGlint);
			addSetting(group);
		}
		
		private int getTintColor()
		{
			float overlay = overlayOpacity.getValueF();
			float alpha = opacity.getValueF();
			if(hidden.isChecked() || alpha <= 0)
				return 0;
			
			if(overlay <= 0 && alpha >= 1)
				return 0;
			
			int red = blend(255, overlayColor.getRed(), overlay);
			int green = blend(255, overlayColor.getGreen(), overlay);
			int blue = blend(255, overlayColor.getBlue(), overlay);
			int a = (int)(alpha * 255);
			return a << 24 | red << 16 | green << 8 | blue;
		}
		
		private int blend(int base, int overlay, float opacity)
		{
			return (int)(base * (1 - opacity) + overlay * opacity);
		}
	}
}
