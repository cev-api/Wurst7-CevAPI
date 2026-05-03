/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import org.joml.Vector4f;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.text.WText;

@SearchTags({"render adjust", "render tweaks", "visual tweaks", "hide npc",
	"hide hologram", "hologram remover", "leaderboard remover", "sky color",
	"fog color", "bossbar remover", "scoreboard remover"})
public final class RenderAdjustHack extends Hack
{
	private final CheckboxSetting adjustFog =
		new CheckboxSetting("Adjust fog", true);
	private final CheckboxSetting disableFog =
		new CheckboxSetting("Disable fog", false);
	private final ColorSetting fogColor =
		new ColorSetting("Fog color", new Color(0xC0D8FF));
	private final SliderSetting fogOpacity = new SliderSetting("Fog opacity", 1,
		0, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting adjustSky =
		new CheckboxSetting("Adjust sky", false);
	private final CheckboxSetting disableSky =
		new CheckboxSetting("Disable sky", false);
	private final ColorSetting skyColor =
		new ColorSetting("Sky color", new Color(0x75B7FF));
	private final SliderSetting skyOpacity = new SliderSetting("Sky opacity", 1,
		0, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting hideScoreboard =
		new CheckboxSetting("Hide scoreboard sidebar", false);
	private final CheckboxSetting hideBossBars =
		new CheckboxSetting("Hide boss bars", false);
	
	private final CheckboxSetting hideHolograms =
		new CheckboxSetting("Hide holograms", false);
	private final CheckboxSetting hideTextDisplays =
		new CheckboxSetting("Hide text displays", false);
	private final CheckboxSetting hideBlockDisplays =
		new CheckboxSetting("Hide block displays", false);
	private final CheckboxSetting hideItemDisplays =
		new CheckboxSetting("Hide item displays", false);
	private final CheckboxSetting hideMarkerArmorStands =
		new CheckboxSetting("Hide marker armor stands", false);
	private final CheckboxSetting hideNamedArmorStands =
		new CheckboxSetting("Hide named armor stands", false);
	private final CheckboxSetting hideItemFrames =
		new CheckboxSetting("Hide item frames", false);
	private final CheckboxSetting hidePaintings =
		new CheckboxSetting("Hide paintings", false);
	private final CheckboxSetting hideXpOrbs =
		new CheckboxSetting("Hide XP orbs", false);
	private final CheckboxSetting hideLikelyNpcLabels =
		new CheckboxSetting("Hide NPC name labels", false);
	
	private final SettingGroup fogGroup = new SettingGroup("Fog",
		WText.literal("Recolor, fade, or remove fog."), false, true)
			.addChildren(adjustFog, disableFog, fogColor, fogOpacity);
	private final SettingGroup skyGroup = new SettingGroup("Sky",
		WText.literal("Recolor, fade, or remove the sky color pass."), false,
		true).addChildren(adjustSky, disableSky, skyColor, skyOpacity);
	private final SettingGroup serverRenderGroup =
		new SettingGroup("Server renders",
			WText.literal("Remove common server-side decorative render spam."),
			false, true).addChildren(hideScoreboard, hideBossBars,
				hideHolograms, hideTextDisplays, hideBlockDisplays,
				hideItemDisplays, hideMarkerArmorStands, hideNamedArmorStands,
				hideItemFrames, hidePaintings, hideXpOrbs, hideLikelyNpcLabels);
	
	public RenderAdjustHack()
	{
		super("RenderAdjust");
		setCategory(Category.RENDER);
		
		addAllSettings(adjustFog, disableFog, fogColor, fogOpacity, fogGroup,
			adjustSky, disableSky, skyColor, skyOpacity, skyGroup,
			hideScoreboard, hideBossBars, hideHolograms, hideTextDisplays,
			hideBlockDisplays, hideItemDisplays, hideMarkerArmorStands,
			hideNamedArmorStands, hideItemFrames, hidePaintings, hideXpOrbs,
			hideLikelyNpcLabels, serverRenderGroup);
	}
	
	private void addAllSettings(Setting... settings)
	{
		for(Setting setting : settings)
			addSetting(setting);
	}
	
	public boolean shouldDisableFog()
	{
		return isEnabled() && disableFog.isChecked();
	}
	
	public boolean shouldAdjustFogColor()
	{
		return isEnabled() && adjustFog.isChecked() && !disableFog.isChecked();
	}
	
	public Vector4f getFogColor(Vector4f original)
	{
		return blendColor(original, fogColor, fogOpacity.getValueF());
	}
	
	public boolean shouldDisableSky()
	{
		return isEnabled() && disableSky.isChecked();
	}
	
	public boolean shouldAdjustSkyColor()
	{
		return isEnabled() && adjustSky.isChecked() && !disableSky.isChecked();
	}
	
	public void applySkyColor(Vector4f color)
	{
		Vector4f adjusted = blendColor(color, skyColor, skyOpacity.getValueF());
		color.set(adjusted.x, adjusted.y, adjusted.z, adjusted.w);
	}
	
	public boolean shouldHideScoreboard()
	{
		return isEnabled() && hideScoreboard.isChecked();
	}
	
	public boolean shouldHideBossBars()
	{
		return isEnabled() && hideBossBars.isChecked();
	}
	
	public boolean shouldHideEntity(Entity entity)
	{
		if(!isEnabled() || entity == null)
			return false;
		
		if(entity instanceof Display.TextDisplay)
			return hideTextDisplays.isChecked() || hideHolograms.isChecked();
		if(entity instanceof Display.BlockDisplay)
			return hideBlockDisplays.isChecked();
		if(entity instanceof Display.ItemDisplay)
			return hideItemDisplays.isChecked();
		if(entity instanceof ArmorStand armorStand)
			return shouldHideArmorStand(armorStand);
		if(entity instanceof GlowItemFrame || entity instanceof ItemFrame)
			return hideItemFrames.isChecked();
		if(entity instanceof Painting)
			return hidePaintings.isChecked();
		if(entity instanceof ExperienceOrb)
			return hideXpOrbs.isChecked();
		
		return hideLikelyNpcLabels.isChecked() && looksLikeNpcLabel(entity);
	}
	
	public boolean shouldRemoveNameTag(Entity entity, Component nameTag)
	{
		if(!isEnabled() || entity == null || nameTag == null)
			return false;
		
		if(hideLikelyNpcLabels.isChecked() && looksLikeNpcText(nameTag))
			return true;
		
		return hideHolograms.isChecked() && entity instanceof ArmorStand;
	}
	
	private boolean shouldHideArmorStand(ArmorStand armorStand)
	{
		if(hideMarkerArmorStands.isChecked() && armorStand.isMarker())
			return true;
		
		if(hideNamedArmorStands.isChecked() && armorStand.hasCustomName())
			return true;
		
		return hideHolograms.isChecked() && armorStand.hasCustomName()
			&& (armorStand.isInvisible() || armorStand.isMarker());
	}
	
	private boolean looksLikeNpcLabel(Entity entity)
	{
		Component name = entity.getCustomName();
		return name != null && looksLikeNpcText(name);
	}
	
	private boolean looksLikeNpcText(Component text)
	{
		String s = text.getString().toLowerCase();
		return s.contains("leaderboard") || s.contains("npc")
			|| s.contains("click") || s.contains("right click")
			|| s.contains("shop") || s.contains("daily") || s.contains("reward")
			|| s.contains("vote") || s.contains("discord")
			|| s.contains("store") || s.contains("playtime")
			|| s.contains("top ");
	}
	
	private Vector4f blendColor(Vector4f original, ColorSetting color,
		float opacity)
	{
		float inverse = 1 - opacity;
		return new Vector4f(
			original.x * inverse + color.getRed() / 255F * opacity,
			original.y * inverse + color.getGreen() / 255F * opacity,
			original.z * inverse + color.getBlue() / 255F * opacity,
			original.w);
	}
}
