/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mapa.config;

import net.minecraft.util.Mth;

public final class XMapConfig
{
	public int minimapSize = 220;
	public double minimapZoom = 2.0;
	public int minimapPosX = 10;
	public int minimapPosY = 80;
	public boolean enabled = true;
	public boolean showCenterCross = true;
	public boolean showPlayerNames = false;
	public double playerNameScale = 1.0;
	public boolean rotateWithPlayer = true;
	public int minimapSamples = 512;
	public boolean undergroundMode = false;
	public boolean invertRotation = false;
	public boolean showSmallPlants = false;
	public boolean showTreeCanopies = true;
	public boolean basicPaletteMode = false;
	public boolean surfaceDynamicLighting = true;
	public boolean undergroundDynamicLighting = true;
	public double textureSharpness = 1.0;
	public double surfaceRelief = 3.0;
	public double surfaceBrightness = 1.2;
	public double surfaceContrast = 1.1;
	public double surfaceSaturation = 1.0;
	public double surfaceContourLimit = 0.6;
	public double surfaceContourSoftness = 1.0;
	public double grassTintStrength = 0.7;
	public int grassTintColor = 0x55AA33;
	public double foliageTintStrength = 1.0;
	public int foliageTintColor = -1;
	public double waterTintStrength = 1.3;
	public int waterTintColor = -1;
	public double waterDetail = 2.5;
	public double waterOpacity = 1.2;
	public double chunkRefreshAggression = 3.0;
	
	public void sanitize()
	{
		minimapSize = Math.max(72, Math.min(256, minimapSize));
		minimapZoom = Math.max(0.25, Math.min(10.0, minimapZoom));
		minimapPosX = Math.max(0, minimapPosX);
		minimapPosY = Math.max(0, minimapPosY);
		playerNameScale = Math.max(0.5, Math.min(4.0, playerNameScale));
		minimapSamples = Math.max(32, Math.min(512, minimapSamples));
		textureSharpness = Math.max(0.0, Math.min(3.0, textureSharpness));
		surfaceRelief = Math.max(0.1, Math.min(3.0, surfaceRelief));
		surfaceBrightness = Math.max(0.7, Math.min(1.3, surfaceBrightness));
		surfaceContrast = Math.max(0.5, Math.min(1.8, surfaceContrast));
		surfaceSaturation = Math.max(0.5, Math.min(1.5, surfaceSaturation));
		surfaceContourLimit =
			Math.max(0.05, Math.min(0.6, surfaceContourLimit));
		surfaceContourSoftness =
			Math.max(0.0, Math.min(1.0, surfaceContourSoftness));
		grassTintStrength = Math.max(0.0, Math.min(2.0, grassTintStrength));
		grassTintColor = Mth.clamp(grassTintColor, -1, 0xFFFFFF);
		foliageTintStrength = Math.max(0.0, Math.min(2.0, foliageTintStrength));
		foliageTintColor = Mth.clamp(foliageTintColor, -1, 0xFFFFFF);
		waterTintStrength = Math.max(0.0, Math.min(2.0, waterTintStrength));
		waterTintColor = Mth.clamp(waterTintColor, -1, 0xFFFFFF);
		waterDetail = Math.max(0.4, Math.min(2.5, waterDetail));
		waterOpacity = Math.max(0.4, Math.min(2.0, waterOpacity));
		chunkRefreshAggression =
			Math.max(0.5, Math.min(4.0, chunkRefreshAggression));
	}
}
