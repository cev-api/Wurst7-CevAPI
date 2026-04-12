/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.awt.Color;
import java.util.Locale;
import java.util.function.Consumer;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.mapa.map.MapRenderService;
import net.wurstclient.hacks.MapaHack;
import net.wurstclient.util.ChatUtils;

public final class MapaCmd extends Command
{
	public MapaCmd()
	{
		super("mapa", "Controls the integrated Mapa minimap.", ".mapa",
			".mapa open", ".mapa reset", ".mapa toggle",
			".mapa enabled <true|false>", ".mapa size <72-256>",
			".mapa zoom <0.25-10>", ".mapa samples <32-512>",
			".mapa textScale <0.5-4>", ".mapa pos <x> <y>",
			".mapa rotate <true|false>", ".mapa underground <true|false>",
			".mapa invert <true|false>", ".mapa smallplants <true|false>",
			".mapa trees <true|false>", ".mapa palette <true|false>",
			".mapa surfaceLighting <true|false>",
			".mapa undergroundLighting <true|false>", ".mapa sharpness <0-3>",
			".mapa relief <0.1-3>", ".mapa brightness <0.7-1.3>",
			".mapa contrast <0.5-1.8>", ".mapa saturation <0.5-1.5>",
			".mapa contourLimit <0.05-0.6>", ".mapa contourSoftness <0-1>",
			".mapa grassTint <0-2>", ".mapa grassColor <hex|reset>",
			".mapa foliageTint <0-2>", ".mapa foliageColor <hex|reset>",
			".mapa waterTint <0-2>", ".mapa waterColor <hex|reset>",
			".mapa waterDetail <0.4-2.5>", ".mapa waterOpacity <0.4-2>",
			".mapa chunkRefresh <0.5-4>");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		MapaHack mapa = WURST.getHax().mapaHack;
		
		if(args.length == 0 || equalsAny(args[0], "show", "status"))
		{
			ChatUtils.message(statusLine(mapa));
			return;
		}
		
		switch(args[0].toLowerCase(Locale.ROOT))
		{
			case "reset":
			requireLength(args, 1);
			mapa.resetToDefaults();
			ChatUtils.message("Mapa reset to defaults.");
			return;
			
			case "open":
			case "worldmap":
			requireLength(args, 1);
			mapa.openWorldMap();
			ChatUtils.message("Opened Mapa world map.");
			return;
			
			case "toggle":
			requireLength(args, 1);
			mapa.setEnabled(!mapa.isEnabled());
			ChatUtils.message("Mapa enabled set to " + mapa.isEnabled() + ".");
			return;
			
			case "enabled":
			requireLength(args, 2);
			mapa.setEnabled(parseBoolean(args[1]));
			ChatUtils.message("Mapa enabled set to " + mapa.isEnabled() + ".");
			return;
			
			case "size":
			requireLength(args, 2);
			mapa.setMapSize(parseInt(args[1], 72, 256));
			ChatUtils.message(
				"Mapa size set to " + mapa.createConfig().minimapSize + ".");
			return;
			
			case "zoom":
			requireLength(args, 2);
			mapa.setMapZoom(parseDouble(args[1], 0.25, 10.0));
			double blocksPerPixel = MapRenderService
				.zoomToBlocksPerPixel(mapa.createConfig().minimapZoom);
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa zoom level set to %.2f (%.3f blocks/pixel).",
				mapa.createConfig().minimapZoom, blocksPerPixel));
			return;
			
			case "samples":
			requireLength(args, 2);
			mapa.setMapSamples(parseInt(args[1], 32, 512));
			ChatUtils.message("Mapa samples set to "
				+ mapa.createConfig().minimapSamples + ".");
			return;
			
			case "textscale":
			requireLength(args, 2);
			mapa.setPlayerNameScale(parseDouble(args[1], 0.5, 4.0));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa player_name_scale set to %.2f.",
				mapa.createConfig().playerNameScale));
			return;
			
			case "pos":
			requireLength(args, 3);
			mapa.setMapPosition(parseInt(args[1], 0, 10000),
				parseInt(args[2], 0, 10000));
			ChatUtils.message(
				"Mapa position set to " + mapa.createConfig().minimapPosX + ", "
					+ mapa.createConfig().minimapPosY + ".");
			return;
			
			case "rotate":
			requireLength(args, 2);
			mapa.setRotateWithPlayer(parseBoolean(args[1]));
			ChatUtils.message("Mapa rotate_with_player set to "
				+ mapa.createConfig().rotateWithPlayer + ".");
			return;
			
			case "underground":
			requireLength(args, 2);
			mapa.setUndergroundMode(parseBoolean(args[1]));
			ChatUtils.message("Mapa underground_mode set to "
				+ mapa.createConfig().undergroundMode + ".");
			return;
			
			case "invert":
			requireLength(args, 2);
			mapa.setInvertRotation(parseBoolean(args[1]));
			ChatUtils.message("Mapa invert_rotation set to "
				+ mapa.createConfig().invertRotation + ".");
			return;
			
			case "smallplants":
			requireLength(args, 2);
			mapa.setShowSmallPlants(parseBoolean(args[1]));
			ChatUtils.message("Mapa show_small_plants set to "
				+ mapa.createConfig().showSmallPlants + ".");
			return;
			
			case "trees":
			requireLength(args, 2);
			mapa.setShowTreeCanopies(parseBoolean(args[1]));
			ChatUtils.message("Mapa show_tree_canopies set to "
				+ mapa.createConfig().showTreeCanopies + ".");
			return;
			
			case "palette":
			requireLength(args, 2);
			mapa.setBasicPaletteMode(parseBoolean(args[1]));
			ChatUtils.message("Mapa basic_palette_mode set to "
				+ mapa.createConfig().basicPaletteMode + ".");
			return;
			
			case "surfacelighting":
			requireLength(args, 2);
			mapa.setSurfaceDynamicLighting(parseBoolean(args[1]));
			ChatUtils.message("Mapa surface_dynamic_lighting set to "
				+ mapa.createConfig().surfaceDynamicLighting + ".");
			return;
			
			case "undergroundlighting":
			requireLength(args, 2);
			mapa.setUndergroundDynamicLighting(parseBoolean(args[1]));
			ChatUtils.message("Mapa underground_dynamic_lighting set to "
				+ mapa.createConfig().undergroundDynamicLighting + ".");
			return;
			
			case "sharpness":
			requireLength(args, 2);
			mapa.setTextureSharpness(parseDouble(args[1], 0.0, 3.0));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa texture_sharpness set to %.2f.",
				mapa.createConfig().textureSharpness));
			return;
			
			case "relief":
			requireLength(args, 2);
			mapa.setSurfaceRelief(parseDouble(args[1], 0.1, 3.0));
			ChatUtils.message(
				String.format(Locale.ROOT, "Mapa surface_relief set to %.2f.",
					mapa.createConfig().surfaceRelief));
			return;
			
			case "brightness":
			requireLength(args, 2);
			mapa.setSurfaceBrightness(parseDouble(args[1], 0.7, 1.3));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa surface_brightness set to %.2f.",
				mapa.createConfig().surfaceBrightness));
			return;
			
			case "contrast":
			requireLength(args, 2);
			mapa.setSurfaceContrast(parseDouble(args[1], 0.5, 1.8));
			ChatUtils.message(
				String.format(Locale.ROOT, "Mapa surface_contrast set to %.2f.",
					mapa.createConfig().surfaceContrast));
			return;
			
			case "saturation":
			requireLength(args, 2);
			mapa.setSurfaceSaturation(parseDouble(args[1], 0.5, 1.5));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa surface_saturation set to %.2f.",
				mapa.createConfig().surfaceSaturation));
			return;
			
			case "contourlimit":
			requireLength(args, 2);
			mapa.setSurfaceContourLimit(parseDouble(args[1], 0.05, 0.6));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa surface_contour_limit set to %.2f.",
				mapa.createConfig().surfaceContourLimit));
			return;
			
			case "contoursoftness":
			requireLength(args, 2);
			mapa.setSurfaceContourSoftness(parseDouble(args[1], 0.0, 1.0));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa surface_contour_softness set to %.2f.",
				mapa.createConfig().surfaceContourSoftness));
			return;
			
			case "grasstint":
			requireLength(args, 2);
			mapa.setGrassTintStrength(parseDouble(args[1], 0.0, 2.0));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa grass_tint_strength set to %.2f.",
				mapa.createConfig().grassTintStrength));
			return;
			
			case "grasscolor":
			setColorArg(args, "grass", mapa::setGrassTintColorDefault,
				mapa::setGrassTintColor);
			return;
			
			case "foliagetint":
			requireLength(args, 2);
			mapa.setFoliageTintStrength(parseDouble(args[1], 0.0, 2.0));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa foliage_tint_strength set to %.2f.",
				mapa.createConfig().foliageTintStrength));
			return;
			
			case "foliagecolor":
			setColorArg(args, "foliage", mapa::setFoliageTintColorDefault,
				mapa::setFoliageTintColor);
			return;
			
			case "watertint":
			requireLength(args, 2);
			mapa.setWaterTintStrength(parseDouble(args[1], 0.0, 2.0));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa water_tint_strength set to %.2f.",
				mapa.createConfig().waterTintStrength));
			return;
			
			case "watercolor":
			setColorArg(args, "water", mapa::setWaterTintColorDefault,
				mapa::setWaterTintColor);
			return;
			
			case "waterdetail":
			requireLength(args, 2);
			mapa.setWaterDetail(parseDouble(args[1], 0.4, 2.5));
			ChatUtils.message(
				String.format(Locale.ROOT, "Mapa water_detail set to %.2f.",
					mapa.createConfig().waterDetail));
			return;
			
			case "wateropacity":
			requireLength(args, 2);
			mapa.setWaterOpacity(parseDouble(args[1], 0.4, 2.0));
			ChatUtils.message(
				String.format(Locale.ROOT, "Mapa water_opacity set to %.2f.",
					mapa.createConfig().waterOpacity));
			return;
			
			case "chunkrefresh":
			requireLength(args, 2);
			mapa.setChunkRefreshAggression(parseDouble(args[1], 0.5, 4.0));
			ChatUtils.message(String.format(Locale.ROOT,
				"Mapa chunk_refresh_aggression set to %.2f.",
				mapa.createConfig().chunkRefreshAggression));
			return;
			
			default:
			throw new CmdSyntaxError();
		}
	}
	
	private void setColorArg(String[] args, String label,
		Consumer<Boolean> setDefault, Consumer<Color> setColor)
		throws CmdException
	{
		requireLength(args, 2);
		if(args[1].equalsIgnoreCase("reset"))
		{
			setDefault.accept(true);
			ChatUtils.message(
				"Mapa " + label + "_tint_color reset to biome default.");
			return;
		}
		
		Color color = parseHexColor(args[1]);
		setDefault.accept(false);
		setColor.accept(color);
		ChatUtils.message("Mapa " + label + "_tint_color set to #"
			+ String.format(Locale.ROOT, "%06X", color.getRGB() & 0xFFFFFF)
			+ ".");
	}
	
	private static String statusLine(MapaHack mapa)
	{
		var cfg = mapa.createConfig();
		double blocksPerPixel =
			MapRenderService.zoomToBlocksPerPixel(cfg.minimapZoom);
		return "Mapa minimap | size=" + cfg.minimapSize + " enabled="
			+ cfg.enabled
			+ String.format(Locale.ROOT, " zoom=%.2f(%.3fbpp)", cfg.minimapZoom,
				blocksPerPixel)
			+ " pos=" + cfg.minimapPosX + "," + cfg.minimapPosY + " rotate="
			+ cfg.rotateWithPlayer + " invert=" + cfg.invertRotation
			+ " underground=" + cfg.undergroundMode + " smallplants="
			+ cfg.showSmallPlants + " trees=" + cfg.showTreeCanopies
			+ " palette=" + cfg.basicPaletteMode + " surfaceLighting="
			+ cfg.surfaceDynamicLighting + " undergroundLighting="
			+ cfg.undergroundDynamicLighting
			+ String.format(Locale.ROOT, " sharpness=%.2f",
				cfg.textureSharpness)
			+ String.format(Locale.ROOT, " relief=%.2f", cfg.surfaceRelief)
			+ String.format(Locale.ROOT, " bright=%.2f", cfg.surfaceBrightness)
			+ String.format(Locale.ROOT, " contrast=%.2f", cfg.surfaceContrast)
			+ String.format(Locale.ROOT, " sat=%.2f", cfg.surfaceSaturation)
			+ String.format(Locale.ROOT, " contour=%.2f",
				cfg.surfaceContourLimit)
			+ String.format(Locale.ROOT, " contourSoft=%.2f",
				cfg.surfaceContourSoftness)
			+ String.format(Locale.ROOT, " grassTint=%.2f",
				cfg.grassTintStrength)
			+ " grassColor=" + colorStatus(cfg.grassTintColor)
			+ String.format(Locale.ROOT, " foliageTint=%.2f",
				cfg.foliageTintStrength)
			+ " foliageColor=" + colorStatus(cfg.foliageTintColor)
			+ String.format(Locale.ROOT, " waterTint=%.2f",
				cfg.waterTintStrength)
			+ " waterColor=" + colorStatus(cfg.waterTintColor)
			+ String.format(Locale.ROOT, " water=%.2f", cfg.waterDetail)
			+ String.format(Locale.ROOT, " waterAlpha=%.2f", cfg.waterOpacity)
			+ String.format(Locale.ROOT, " chunk=%.2f",
				cfg.chunkRefreshAggression)
			+ String.format(Locale.ROOT, " textScale=%.2f", cfg.playerNameScale)
			+ " samples=" + cfg.minimapSamples;
	}
	
	private static String colorStatus(int color)
	{
		return color < 0 ? "default"
			: "#" + String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
	}
	
	private static void requireLength(String[] args, int expected)
		throws CmdSyntaxError
	{
		if(args.length != expected)
			throw new CmdSyntaxError();
	}
	
	private static boolean equalsAny(String value, String... options)
	{
		for(String option : options)
			if(option.equalsIgnoreCase(value))
				return true;
		return false;
	}
	
	private static boolean parseBoolean(String value) throws CmdSyntaxError
	{
		if(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")
			|| value.equalsIgnoreCase("yes"))
			return true;
		
		if(value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")
			|| value.equalsIgnoreCase("no"))
			return false;
		
		throw new CmdSyntaxError("Invalid boolean: " + value);
	}
	
	private static int parseInt(String value, int min, int max)
		throws CmdException
	{
		try
		{
			int parsed = Integer.parseInt(value);
			if(parsed < min || parsed > max)
				throw new CmdError("Value out of range: " + parsed
					+ " (expected " + min + "-" + max + ")");
			return parsed;
			
		}catch(NumberFormatException e)
		{
			throw new CmdSyntaxError("Not a number: " + value);
		}
	}
	
	private static double parseDouble(String value, double min, double max)
		throws CmdException
	{
		try
		{
			double parsed = Double.parseDouble(value);
			if(parsed < min || parsed > max)
				throw new CmdError(String.format(Locale.ROOT,
					"Value out of range: %s (expected %.2f-%.2f)", value, min,
					max));
			return parsed;
			
		}catch(NumberFormatException e)
		{
			throw new CmdSyntaxError("Not a number: " + value);
		}
	}
	
	private static Color parseHexColor(String raw) throws CmdException
	{
		String value = raw.trim();
		if(value.startsWith("#"))
			value = value.substring(1);
		else if(value.startsWith("0x") || value.startsWith("0X"))
			value = value.substring(2);
		
		if(value.length() != 6)
			throw new CmdSyntaxError("Expected 6-digit hex color.");
		
		try
		{
			return new Color(Integer.parseInt(value, 16));
		}catch(NumberFormatException e)
		{
			throw new CmdSyntaxError("Invalid hex color: " + raw);
		}
	}
}
