/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.List;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.altgui.AltGuiScreen;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.MouseButtonPressListener;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.chestesp.ChestEspBlockGroup;
import net.wurstclient.hacks.chestesp.ChestEspGroupManager;
import net.wurstclient.hacks.chestesp.groups.BarrelsGroup;
import net.wurstclient.hacks.chestesp.groups.CraftersGroup;
import net.wurstclient.hacks.chestesp.groups.DispensersGroup;
import net.wurstclient.hacks.chestesp.groups.DroppersGroup;
import net.wurstclient.hacks.chestesp.groups.EnderChestsGroup;
import net.wurstclient.hacks.chestesp.groups.FurnacesGroup;
import net.wurstclient.hacks.chestesp.groups.HoppersGroup;
import net.wurstclient.hacks.chestesp.groups.NormalChestsGroup;
import net.wurstclient.hacks.chestesp.groups.PotsGroup;
import net.wurstclient.hacks.chestesp.groups.ShulkerBoxesGroup;
import net.wurstclient.hacks.chestesp.groups.TrapChestsGroup;
import net.wurstclient.hacks.portalesp.PortalEspBlockGroup;
import net.wurstclient.mapa.config.XMapConfig;
import net.wurstclient.mapa.map.MapRenderService;
import net.wurstclient.mapa.screen.WorldMapScreen;
import net.wurstclient.navigator.NavigatorScreen;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.nicewurst.NiceWurstModule;

@SearchTags({"map", "minimap", "world map", "mapa"})
public final class MapaHack extends Hack
	implements GUIRenderListener, MouseButtonPressListener, MouseUpdateListener
{
	private final MapRenderService renderService = new MapRenderService();
	
	private final SliderSetting minimapSize =
		new SliderSetting("Size", 220, 72, 256, 1, ValueDisplay.INTEGER);
	private final SliderSetting minimapZoom =
		new SliderSetting("Zoom", 2.0, 0.25, 10.0, 0.05, ValueDisplay.DECIMAL);
	private final SliderSetting minimapPosX =
		new SliderSetting("Position X", 10, 0, 10000, 1, ValueDisplay.INTEGER);
	private final SliderSetting minimapPosY =
		new SliderSetting("Position Y", 80, 0, 10000, 1, ValueDisplay.INTEGER);
	private final ButtonSetting openWorldMapButton =
		new ButtonSetting("Open world map", this::openWorldMap);
	private final CheckboxSetting noMap = new CheckboxSetting("No map",
		"Disables terrain rendering and cache writes while keeping ESP icons positioned inside the map box.",
		false);
	private final CheckboxSetting espIconsEnabled =
		new CheckboxSetting("Enable ESP icons", true);
	private final SettingGroup mapEspGroup = new SettingGroup("ESP icons",
		net.wurstclient.util.text.WText.literal(
			"Controls which enabled ESP hacks are mirrored onto the map."),
		false, true);
	private final SliderSetting minimapIconSize = new SliderSetting(
		"Minimap icon size", 8, 4, 24, 1, ValueDisplay.INTEGER);
	private final SliderSetting worldMapIconSize = new SliderSetting(
		"World map icon size", 12, 4, 32, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting iconOutline =
		new CheckboxSetting("ESP icon outlines", true);
	private final CheckboxSetting showCenterCross =
		new CheckboxSetting("Show center cross", true);
	private final CheckboxSetting showFrame =
		new CheckboxSetting("Show frame", true);
	private final CheckboxSetting showPlayerNames =
		new CheckboxSetting("Show player names", false);
	private final SliderSetting minimapSamples =
		new SliderSetting("Samples", 512, 32, 512, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting rotateWithPlayer =
		new CheckboxSetting("Rotate with player", true);
	private final CheckboxSetting undergroundMode =
		new CheckboxSetting("Underground mode", false);
	private final CheckboxSetting invertRotation =
		new CheckboxSetting("Invert rotation", false);
	private final CheckboxSetting showSmallPlants =
		new CheckboxSetting("Show small plants", false);
	private final CheckboxSetting showTreeCanopies =
		new CheckboxSetting("Show tree canopies", true);
	private final CheckboxSetting basicPaletteMode =
		new CheckboxSetting("Basic palette mode", false);
	private final CheckboxSetting surfaceDynamicLighting =
		new CheckboxSetting("Surface dynamic lighting", true);
	private final CheckboxSetting undergroundDynamicLighting =
		new CheckboxSetting("Underground dynamic lighting", true);
	private final SliderSetting textureSharpness = new SliderSetting(
		"Texture sharpness", 1.0, 0.0, 3.0, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting surfaceRelief = new SliderSetting(
		"Surface relief", 3.0, 0.1, 3.0, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting surfaceBrightness = new SliderSetting(
		"Surface brightness", 1.2, 0.7, 1.3, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting surfaceContrast = new SliderSetting(
		"Surface contrast", 1.1, 0.5, 1.8, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting surfaceSaturation = new SliderSetting(
		"Surface saturation", 1.0, 0.5, 1.5, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting surfaceContourLimit = new SliderSetting(
		"Surface contour limit", 0.6, 0.05, 0.6, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting surfaceContourSoftness = new SliderSetting(
		"Surface contour softness", 1.0, 0.0, 1.0, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting grassTintStrength = new SliderSetting(
		"Grass tint strength", 0.7, 0.0, 2.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting useDefaultGrassColor =
		new CheckboxSetting("Use biome grass color", false);
	private final ColorSetting grassTintColor =
		new ColorSetting("Grass tint color", new Color(0x55AA33));
	private final SliderSetting foliageTintStrength = new SliderSetting(
		"Foliage tint strength", 1.0, 0.0, 2.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting useDefaultFoliageColor =
		new CheckboxSetting("Use biome foliage color", true);
	private final ColorSetting foliageTintColor =
		new ColorSetting("Foliage tint color", new Color(0x55AA33));
	private final SliderSetting waterTintStrength = new SliderSetting(
		"Water tint strength", 1.3, 0.0, 2.0, 0.01, ValueDisplay.DECIMAL);
	private final CheckboxSetting useDefaultWaterColor =
		new CheckboxSetting("Use biome water color", true);
	private final ColorSetting waterTintColor =
		new ColorSetting("Water tint color", new Color(0x3F76E4));
	private final SliderSetting waterDetail = new SliderSetting("Water detail",
		2.5, 0.4, 2.5, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting waterOpacity = new SliderSetting(
		"Water opacity", 1.2, 0.4, 2.0, 0.01, ValueDisplay.DECIMAL);
	private final SliderSetting chunkRefreshAggression = new SliderSetting(
		"Chunk refresh aggression", 3.0, 0.5, 4.0, 0.01, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting chestEspOnMap =
		new CheckboxSetting("ChestESP icons", true);
	private final CheckboxSetting workstationEspOnMap =
		new CheckboxSetting("WorkstationESP icons", true);
	private final CheckboxSetting signEspOnMap =
		new CheckboxSetting("SignESP icons", true);
	private final CheckboxSetting portalEspOnMap =
		new CheckboxSetting("PortalESP icons", true);
	private final CheckboxSetting playerEspOnMap =
		new CheckboxSetting("PlayerESP icons", true);
	private final CheckboxSetting logoutSpotsOnMap =
		new CheckboxSetting("LogoutSpots icons", true);
	private final CheckboxSetting bedEspOnMap =
		new CheckboxSetting("BedESP icons", true);
	
	private boolean dragging;
	private int dragOffsetX;
	private int dragOffsetY;
	
	public MapaHack()
	{
		super("Mapa");
		setCategory(Category.RENDER);
		addSetting(openWorldMapButton);
		addSetting(noMap);
		addSetting(espIconsEnabled);
		if(NiceWurstModule.isActive())
		{
			mapEspGroup.addChildren(portalEspOnMap, playerEspOnMap,
				logoutSpotsOnMap);
		}else
		{
			mapEspGroup.addChildren(chestEspOnMap, workstationEspOnMap,
				signEspOnMap, portalEspOnMap, playerEspOnMap, logoutSpotsOnMap,
				bedEspOnMap);
		}
		addSetting(mapEspGroup);
		addSetting(minimapIconSize);
		addSetting(worldMapIconSize);
		addSetting(iconOutline);
		addSetting(showCenterCross);
		addSetting(showFrame);
		addSetting(showPlayerNames);
		addSetting(minimapSize);
		addSetting(minimapZoom);
		addSetting(minimapPosX);
		addSetting(minimapPosY);
		addSetting(minimapSamples);
		addSetting(rotateWithPlayer);
		addSetting(undergroundMode);
		addSetting(invertRotation);
		addSetting(showSmallPlants);
		addSetting(showTreeCanopies);
		addSetting(basicPaletteMode);
		addSetting(surfaceDynamicLighting);
		addSetting(undergroundDynamicLighting);
		addSetting(textureSharpness);
		addSetting(surfaceRelief);
		addSetting(surfaceBrightness);
		addSetting(surfaceContrast);
		addSetting(surfaceSaturation);
		addSetting(surfaceContourLimit);
		addSetting(surfaceContourSoftness);
		addSetting(grassTintStrength);
		addSetting(useDefaultGrassColor);
		addSetting(grassTintColor);
		addSetting(foliageTintStrength);
		addSetting(useDefaultFoliageColor);
		addSetting(foliageTintColor);
		addSetting(waterTintStrength);
		addSetting(useDefaultWaterColor);
		addSetting(waterTintColor);
		addSetting(waterDetail);
		addSetting(waterOpacity);
		addSetting(chunkRefreshAggression);
		updateMapSettingVisibility();
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(GUIRenderListener.class, this);
		EVENTS.add(MouseButtonPressListener.class, this);
		EVENTS.add(MouseUpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
		EVENTS.remove(MouseButtonPressListener.class, this);
		EVENTS.remove(MouseUpdateListener.class, this);
		dragging = false;
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		updateMapSettingVisibility();
		XMapConfig cfg = createConfig();
		if(cfg.enabled)
			renderService.drawMinimap(context, MC, cfg);
		if(MC.player == null || MC.level == null)
			return;
		
		context.enableScissor(cfg.minimapPosX, cfg.minimapPosY,
			cfg.minimapPosX + cfg.minimapSize,
			cfg.minimapPosY + cfg.minimapSize);
		try
		{
			renderMapEsp(context, partialTicks, cfg);
		}finally
		{
			context.disableScissor();
		}
		renderMinimapOverlay(context, cfg);
		
		if(!isEditorScreen(MC.screen))
			return;
		
		int x = cfg.minimapPosX;
		int y = cfg.minimapPosY;
		int size = cfg.minimapSize;
		int border = dragging ? 0xFF55FF55 : 0x80FFFFFF;
		context.fill(x - 2, y - 2, x + size + 2, y - 1, border);
		context.fill(x - 2, y + size + 1, x + size + 2, y + size + 2, border);
		context.fill(x - 2, y - 1, x - 1, y + size + 1, border);
		context.fill(x + size + 1, y - 1, x + size + 2, y + size + 1, border);
	}
	
	private void renderMinimapOverlay(GuiGraphicsExtractor context,
		XMapConfig cfg)
	{
		int x = cfg.minimapPosX;
		int y = cfg.minimapPosY;
		int size = cfg.minimapSize;
		if(showFrame.isChecked())
		{
			context.fill(x - 2, y - 2, x + size + 2, y - 1, 0xCC000000);
			context.fill(x - 2, y + size + 1, x + size + 2, y + size + 2,
				0xCC000000);
			context.fill(x - 2, y - 1, x - 1, y + size + 1, 0xCC000000);
			context.fill(x + size + 1, y - 1, x + size + 2, y + size + 1,
				0xCC000000);
			context.fill(x - 1, y - 1, x + size + 1, y, 0xFF6F6F6F);
			context.fill(x - 1, y + size, x + size + 1, y + size + 1,
				0xFF6F6F6F);
			context.fill(x - 1, y, x, y + size, 0xFF6F6F6F);
			context.fill(x + size, y, x + size + 1, y + size, 0xFF6F6F6F);
		}
		if(cfg.showCenterCross)
		{
			int cx = x + size / 2;
			int cy = y + size / 2;
			context.fill(cx - 1, cy - 4, cx + 1, cy + 4, 0xFFFFFFFF);
			context.fill(cx - 4, cy - 1, cx + 4, cy + 1, 0xFFFFFFFF);
		}
	}
	
	private void renderMapEsp(GuiGraphicsExtractor context, float partialTicks,
		XMapConfig cfg)
	{
		if(!espIconsEnabled.isChecked())
			return;
		
		if(chestEspOnMap.isChecked() && WURST.getHax().chestEspHack.isEnabled())
			renderChestMarkers(context, cfg, partialTicks);
		if(workstationEspOnMap.isChecked()
			&& WURST.getHax().workstationEspHack.isEnabled())
			renderBlockGroupMarkers(context, cfg,
				WURST.getHax().workstationEspHack.getMapaGroups());
		if(signEspOnMap.isChecked() && WURST.getHax().signEspHack.isEnabled())
			renderAabbMarkers(context, cfg,
				WURST.getHax().signEspHack.getMapaSignBoxes(),
				iconForItem(Items.OAK_SIGN),
				WURST.getHax().signEspHack.getMapaSignColor());
		if(signEspOnMap.isChecked() && WURST.getHax().signEspHack.isEnabled())
			renderAabbMarkers(context, cfg,
				WURST.getHax().signEspHack.getMapaFrameBoxes(partialTicks),
				iconForItem(Items.ITEM_FRAME),
				WURST.getHax().signEspHack.getMapaFrameColor());
		if(portalEspOnMap.isChecked()
			&& WURST.getHax().portalEspHack.isEnabled())
			renderBlockGroupMarkers(context, cfg,
				WURST.getHax().portalEspHack.getMapaGroups());
		if(playerEspOnMap.isChecked()
			&& WURST.getHax().playerEspHack.isEnabled())
			renderPlayerMarkers(context, cfg);
		if(logoutSpotsOnMap.isChecked()
			&& WURST.getHax().logoutSpotsHack.isEnabled())
			renderLogoutSpots(context, cfg);
		if(bedEspOnMap.isChecked() && WURST.getHax().bedEspHack.isEnabled())
			renderAabbMarkers(context, cfg,
				WURST.getHax().bedEspHack.getMapaBoxes(),
				iconForItem(Items.RED_BED),
				WURST.getHax().bedEspHack.getMapaColor());
	}
	
	private void renderChestMarkers(GuiGraphicsExtractor context,
		XMapConfig cfg, float partialTicks)
	{
		ChestEspHack chestEsp = WURST.getHax().chestEspHack;
		ChestEspGroupManager manager = chestEsp.getMapaGroupManager();
		chestEsp.updateMapaEntityBoxes(partialTicks);
		for(ChestEspBlockGroup group : manager.blockGroups)
			renderAabbMarkers(context, cfg, group.getBoxes(),
				iconForChestGroup(group), group.getColorI(0xFF));
	}
	
	private void renderBlockGroupMarkers(GuiGraphicsExtractor context,
		XMapConfig cfg, List<PortalEspBlockGroup> groups)
	{
		int markerSize = getMarkerSize(cfg);
		for(PortalEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			for(BlockPos pos : group.getPositions())
				renderItemMarker(context, cfg, Vec3.atCenterOf(pos),
					iconForBlock(group.getBlock()), markerSize,
					group.getColorI(0xFF));
		}
	}
	
	private void renderAabbMarkers(GuiGraphicsExtractor context, XMapConfig cfg,
		List<AABB> boxes, ItemStack icon, int outlineColor)
	{
		int markerSize = getMarkerSize(cfg);
		for(AABB box : boxes)
			renderItemMarker(context, cfg, box.getCenter(), icon, markerSize,
				outlineColor);
	}
	
	private void renderPlayerMarkers(GuiGraphicsExtractor context,
		XMapConfig cfg)
	{
		int markerSize = getMarkerSize(cfg);
		for(Player player : WURST.getHax().playerEspHack.getMapaPlayers())
		{
			if(player == MC.player)
				continue;
			PlayerInfo info = MC.getConnection() == null ? null
				: MC.getConnection().getPlayerInfo(player.getUUID());
			Identifier skin = info != null ? info.getSkin().body().texturePath()
				: player instanceof AbstractClientPlayer clientPlayer
					? clientPlayer.getSkin().body().texturePath()
					: DefaultPlayerSkin.get(player.getUUID()).body()
						.texturePath();
			renderPlayerHeadMarker(context, cfg, player.position(), skin,
				player.getName().getString(), markerSize,
				WURST.getHax().playerEspHack.getMapaPlayerColor(player));
		}
	}
	
	private void renderLogoutSpots(GuiGraphicsExtractor context, XMapConfig cfg)
	{
		String dim = MC.level.dimension().identifier().getPath();
		int outlineColor = WURST.getHax().logoutSpotsHack.getMapaLineColor();
		for(var spot : WURST.getHax().logoutSpotsHack.getMapaSpots())
		{
			if(!dim.equals(spot.dimKey()))
				continue;
			MapPoint point = projectToMinimap(cfg, spot.box().getCenter());
			if(point == null)
				continue;
			int size = getMarkerSize(cfg);
			int x = Math.round(point.x()) - size / 2;
			int y = Math.round(point.y()) - size / 2;
			drawIconOutline(context, x, y, size, outlineColor);
			context.fill(x, y, x + size, y + size, 0xFF4AA3FF);
		}
	}
	
	private void renderItemMarker(GuiGraphicsExtractor context, XMapConfig cfg,
		Vec3 worldPos, ItemStack icon, int size, int outlineColor)
	{
		MapPoint point = projectToMinimap(cfg, worldPos);
		if(point == null)
			return;
		renderItemMarker(context, point, icon, size, outlineColor);
	}
	
	private void renderPlayerHeadMarker(GuiGraphicsExtractor context,
		XMapConfig cfg, Vec3 worldPos, Identifier skin, String name, int size,
		int outlineColor)
	{
		MapPoint point = projectToMinimap(cfg, worldPos);
		if(point == null)
			return;
		renderPlayerHeadMarker(context, point, skin, name, size, outlineColor,
			cfg.showPlayerNames);
	}
	
	private void renderPlayerHeadMarker(GuiGraphicsExtractor context,
		MapPoint point, Identifier skin, String name, int size,
		int outlineColor, boolean drawName)
	{
		int x = Math.round(point.x()) - size / 2;
		int y = Math.round(point.y()) - size / 2;
		drawIconOutline(context, x, y, size, outlineColor);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8, 8, size, size,
			64, 64, 0xFFFFFFFF);
		context.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40, 8, size,
			size, 64, 64, 0xFFFFFFFF);
		if(drawName && !name.isEmpty())
			drawMarkerLabel(context, name, x + size / 2, y + size + 2,
				outlineColor, size);
	}
	
	private void drawMarkerLabel(GuiGraphicsExtractor context, String label,
		int centerX, int y, int color, int iconSize)
	{
		float scale = Mth.clamp(iconSize / 8.0F, 0.5F, 2.0F);
		int width = Math.round(MC.font.width(label) * scale);
		int x = centerX - width / 2;
		int stroke = 0xFF000000;
		context.pose().pushMatrix();
		context.pose().translate(x, y);
		context.pose().scale(scale, scale);
		context.text(MC.font, label, -1, 0, stroke, false);
		context.text(MC.font, label, 1, 0, stroke, false);
		context.text(MC.font, label, 0, -1, stroke, false);
		context.text(MC.font, label, 0, 1, stroke, false);
		context.text(MC.font, label, 0, 0, color, false);
		context.pose().popMatrix();
	}
	
	private MapPoint projectToMinimap(XMapConfig cfg, Vec3 worldPos)
	{
		double zoomBlocks =
			MapRenderService.zoomToBlocksPerPixel(cfg.minimapZoom);
		double dx = worldPos.x - MC.player.getX();
		double dz = worldPos.z - MC.player.getZ();
		double angle = rotationAngleRad(cfg.rotateWithPlayer,
			MC.player.getYRot(), cfg.invertRotation);
		double cos = Math.cos(-angle);
		double sin = Math.sin(-angle);
		double rx = dx * cos - dz * sin;
		double rz = dx * sin + dz * cos;
		double px = cfg.minimapPosX + cfg.minimapSize / 2.0 + rx / zoomBlocks;
		double py = cfg.minimapPosY + cfg.minimapSize / 2.0 + rz / zoomBlocks;
		if(px < cfg.minimapPosX || py < cfg.minimapPosY
			|| px >= cfg.minimapPosX + cfg.minimapSize
			|| py >= cfg.minimapPosY + cfg.minimapSize)
			return null;
		return new MapPoint((float)px, (float)py);
	}
	
	private static double rotationAngleRad(boolean rotateWithPlayer,
		float yawDeg, boolean invertRotation)
	{
		if(!rotateWithPlayer)
			return 0.0;
		double yaw = Math.toRadians(yawDeg + 180.0f);
		return invertRotation ? -yaw : yaw;
	}
	
	private static ItemStack iconForChestGroup(ChestEspBlockGroup group)
	{
		if(group instanceof NormalChestsGroup)
			return iconForBlock(Blocks.CHEST);
		if(group instanceof TrapChestsGroup)
			return iconForBlock(Blocks.TRAPPED_CHEST);
		if(group instanceof EnderChestsGroup)
			return iconForBlock(Blocks.ENDER_CHEST);
		if(group instanceof BarrelsGroup)
			return iconForBlock(Blocks.BARREL);
		if(group instanceof PotsGroup)
			return iconForBlock(Blocks.DECORATED_POT);
		if(group instanceof ShulkerBoxesGroup)
			return iconForBlock(Blocks.SHULKER_BOX);
		if(group instanceof HoppersGroup)
			return iconForBlock(Blocks.HOPPER);
		if(group instanceof DroppersGroup)
			return iconForBlock(Blocks.DROPPER);
		if(group instanceof DispensersGroup)
			return iconForBlock(Blocks.DISPENSER);
		if(group instanceof CraftersGroup)
			return iconForBlock(Blocks.CRAFTER);
		if(group instanceof FurnacesGroup)
			return iconForBlock(Blocks.FURNACE);
		return iconForBlock(Blocks.CHEST);
	}
	
	private static ItemStack iconForBlock(Block block)
	{
		if(block == Blocks.NETHER_PORTAL)
			return iconForBlock(Blocks.OBSIDIAN);
		if(block == Blocks.END_PORTAL || block == Blocks.END_PORTAL_FRAME
			|| block == Blocks.END_GATEWAY)
			return iconForItem(Items.ENDER_EYE);
		if(block == Blocks.RESPAWN_ANCHOR)
			return iconForBlock(Blocks.RESPAWN_ANCHOR);
		return new ItemStack(block);
	}
	
	private static ItemStack iconForItem(Item item)
	{
		return new ItemStack(item);
	}
	
	private void renderItemMarker(GuiGraphicsExtractor context, MapPoint point,
		ItemStack icon, int size, int outlineColor)
	{
		int x = Math.round(point.x()) - size / 2;
		int y = Math.round(point.y()) - size / 2;
		drawIconOutline(context, x, y, size, outlineColor);
		context.pose().pushMatrix();
		float scale = size / 16.0f;
		context.pose().translate(x, y);
		context.pose().scale(scale, scale);
		context.item(icon, 0, 0);
		context.pose().popMatrix();
	}
	
	private void drawIconOutline(GuiGraphicsExtractor context, int x, int y,
		int size, int outlineColor)
	{
		if(!iconOutline.isChecked())
			return;
		int color = 0xFF000000 | (outlineColor & 0x00FFFFFF);
		context.fill(x - 1, y - 1, x + size + 1, y, color);
		context.fill(x - 1, y + size, x + size + 1, y + size + 1, color);
		context.fill(x - 1, y, x, y + size, color);
		context.fill(x + size, y, x + size + 1, y + size, color);
	}
	
	@Override
	public void onMouseButtonPress(MouseButtonPressEvent event)
	{
		if(event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		if(event.getAction() == GLFW.GLFW_RELEASE)
		{
			dragging = false;
			return;
		}
		if(event.getAction() != GLFW.GLFW_PRESS || !isEditorScreen(MC.screen))
			return;
		
		XMapConfig cfg = createConfig();
		int mouseX = getScaledMouseX();
		int mouseY = getScaledMouseY();
		if(mouseX < cfg.minimapPosX
			|| mouseX > cfg.minimapPosX + cfg.minimapSize
			|| mouseY < cfg.minimapPosY
			|| mouseY > cfg.minimapPosY + cfg.minimapSize)
			return;
		
		dragging = true;
		dragOffsetX = mouseX - cfg.minimapPosX;
		dragOffsetY = mouseY - cfg.minimapPosY;
	}
	
	@Override
	public void onMouseUpdate(MouseUpdateEvent event)
	{
		if(!dragging)
			return;
		int size = minimapSize.getValueI();
		int maxX = Math.max(0, MC.getWindow().getGuiScaledWidth() - size);
		int maxY = Math.max(0, MC.getWindow().getGuiScaledHeight() - size);
		minimapPosX
			.setValue(Mth.clamp(getScaledMouseX() - dragOffsetX, 0, maxX));
		minimapPosY
			.setValue(Mth.clamp(getScaledMouseY() - dragOffsetY, 0, maxY));
	}
	
	public void resetToDefaults()
	{
		noMap.setChecked(false);
		espIconsEnabled.setChecked(true);
		minimapSize.setValue(220);
		minimapZoom.setValue(2.0);
		minimapIconSize.setValue(8);
		worldMapIconSize.setValue(12);
		iconOutline.setChecked(true);
		showCenterCross.setChecked(true);
		showFrame.setChecked(true);
		showPlayerNames.setChecked(false);
		minimapPosX.setValue(10);
		minimapPosY.setValue(80);
		minimapSamples.setValue(512);
		rotateWithPlayer.setChecked(true);
		undergroundMode.setChecked(false);
		invertRotation.setChecked(false);
		showSmallPlants.setChecked(false);
		showTreeCanopies.setChecked(true);
		basicPaletteMode.setChecked(false);
		surfaceDynamicLighting.setChecked(true);
		undergroundDynamicLighting.setChecked(true);
		textureSharpness.setValue(1.0);
		surfaceRelief.setValue(3.0);
		surfaceBrightness.setValue(1.2);
		surfaceContrast.setValue(1.1);
		surfaceSaturation.setValue(1.0);
		surfaceContourLimit.setValue(0.6);
		surfaceContourSoftness.setValue(1.0);
		grassTintStrength.setValue(0.7);
		useDefaultGrassColor.setChecked(false);
		grassTintColor.setColor(new Color(0x55AA33));
		foliageTintStrength.setValue(1.0);
		useDefaultFoliageColor.setChecked(true);
		foliageTintColor.setColor(new Color(0x55AA33));
		waterTintStrength.setValue(1.3);
		useDefaultWaterColor.setChecked(true);
		waterTintColor.setColor(new Color(0x3F76E4));
		waterDetail.setValue(2.5);
		waterOpacity.setValue(1.2);
		chunkRefreshAggression.setValue(3.0);
		chestEspOnMap.setChecked(true);
		workstationEspOnMap.setChecked(true);
		signEspOnMap.setChecked(true);
		portalEspOnMap.setChecked(true);
		playerEspOnMap.setChecked(true);
		logoutSpotsOnMap.setChecked(true);
		bedEspOnMap.setChecked(true);
	}
	
	public void setMapSize(int value)
	{
		minimapSize.setValue(value);
	}
	
	public void setMapZoom(double value)
	{
		minimapZoom.setValue(value);
	}
	
	public void setMapSamples(int value)
	{
		minimapSamples.setValue(value);
	}
	
	public void setMapPosition(int x, int y)
	{
		minimapPosX.setValue(x);
		minimapPosY.setValue(y);
	}
	
	public void setRotateWithPlayer(boolean value)
	{
		rotateWithPlayer.setChecked(value);
	}
	
	public void setUndergroundMode(boolean value)
	{
		undergroundMode.setChecked(value);
	}
	
	public void setInvertRotation(boolean value)
	{
		invertRotation.setChecked(value);
	}
	
	public void setShowSmallPlants(boolean value)
	{
		showSmallPlants.setChecked(value);
	}
	
	public void setShowTreeCanopies(boolean value)
	{
		showTreeCanopies.setChecked(value);
	}
	
	public void setBasicPaletteMode(boolean value)
	{
		basicPaletteMode.setChecked(value);
	}
	
	public void setSurfaceDynamicLighting(boolean value)
	{
		surfaceDynamicLighting.setChecked(value);
	}
	
	public void setUndergroundDynamicLighting(boolean value)
	{
		undergroundDynamicLighting.setChecked(value);
	}
	
	public void setTextureSharpness(double value)
	{
		textureSharpness.setValue(value);
	}
	
	public void setSurfaceRelief(double value)
	{
		surfaceRelief.setValue(value);
	}
	
	public void setSurfaceBrightness(double value)
	{
		surfaceBrightness.setValue(value);
	}
	
	public void setSurfaceContrast(double value)
	{
		surfaceContrast.setValue(value);
	}
	
	public void setSurfaceSaturation(double value)
	{
		surfaceSaturation.setValue(value);
	}
	
	public void setSurfaceContourLimit(double value)
	{
		surfaceContourLimit.setValue(value);
	}
	
	public void setSurfaceContourSoftness(double value)
	{
		surfaceContourSoftness.setValue(value);
	}
	
	public void setGrassTintStrength(double value)
	{
		grassTintStrength.setValue(value);
	}
	
	public void setGrassTintColorDefault(boolean value)
	{
		useDefaultGrassColor.setChecked(value);
	}
	
	public void setGrassTintColor(Color value)
	{
		grassTintColor.setColor(value);
	}
	
	public void setFoliageTintStrength(double value)
	{
		foliageTintStrength.setValue(value);
	}
	
	public void setFoliageTintColorDefault(boolean value)
	{
		useDefaultFoliageColor.setChecked(value);
	}
	
	public void setFoliageTintColor(Color value)
	{
		foliageTintColor.setColor(value);
	}
	
	public void setWaterTintStrength(double value)
	{
		waterTintStrength.setValue(value);
	}
	
	public void setWaterTintColorDefault(boolean value)
	{
		useDefaultWaterColor.setChecked(value);
	}
	
	public void setWaterTintColor(Color value)
	{
		waterTintColor.setColor(value);
	}
	
	public void setWaterDetail(double value)
	{
		waterDetail.setValue(value);
	}
	
	public void setWaterOpacity(double value)
	{
		waterOpacity.setValue(value);
	}
	
	public void setChunkRefreshAggression(double value)
	{
		chunkRefreshAggression.setValue(value);
	}
	
	public XMapConfig createConfig()
	{
		XMapConfig cfg = new XMapConfig();
		cfg.enabled = !noMap.isChecked();
		cfg.showCenterCross = showCenterCross.isChecked();
		cfg.showPlayerNames = showPlayerNames.isChecked();
		cfg.minimapSize = minimapSize.getValueI();
		cfg.minimapZoom = minimapZoom.getValue();
		cfg.minimapPosX = minimapPosX.getValueI();
		cfg.minimapPosY = minimapPosY.getValueI();
		cfg.minimapSamples = minimapSamples.getValueI();
		cfg.rotateWithPlayer = rotateWithPlayer.isChecked();
		cfg.undergroundMode = undergroundMode.isChecked();
		cfg.invertRotation = invertRotation.isChecked();
		cfg.showSmallPlants = showSmallPlants.isChecked();
		cfg.showTreeCanopies = showTreeCanopies.isChecked();
		cfg.basicPaletteMode = basicPaletteMode.isChecked();
		cfg.surfaceDynamicLighting = surfaceDynamicLighting.isChecked();
		cfg.undergroundDynamicLighting = undergroundDynamicLighting.isChecked();
		cfg.textureSharpness = textureSharpness.getValue();
		cfg.surfaceRelief = surfaceRelief.getValue();
		cfg.surfaceBrightness = surfaceBrightness.getValue();
		cfg.surfaceContrast = surfaceContrast.getValue();
		cfg.surfaceSaturation = surfaceSaturation.getValue();
		cfg.surfaceContourLimit = surfaceContourLimit.getValue();
		cfg.surfaceContourSoftness = surfaceContourSoftness.getValue();
		cfg.grassTintStrength = grassTintStrength.getValue();
		cfg.grassTintColor = useDefaultGrassColor.isChecked() ? -1
			: grassTintColor.getColor().getRGB() & 0xFFFFFF;
		cfg.foliageTintStrength = foliageTintStrength.getValue();
		cfg.foliageTintColor = useDefaultFoliageColor.isChecked() ? -1
			: foliageTintColor.getColor().getRGB() & 0xFFFFFF;
		cfg.waterTintStrength = waterTintStrength.getValue();
		cfg.waterTintColor = useDefaultWaterColor.isChecked() ? -1
			: waterTintColor.getColor().getRGB() & 0xFFFFFF;
		cfg.waterDetail = waterDetail.getValue();
		cfg.waterOpacity = waterOpacity.getValue();
		cfg.chunkRefreshAggression = chunkRefreshAggression.getValue();
		cfg.sanitize();
		return cfg;
	}
	
	private void updateMapSettingVisibility()
	{
		boolean mapVisible = !noMap.isChecked();
		boolean niceWurst = NiceWurstModule.isActive();
		boolean espVisible = mapVisible && espIconsEnabled.isChecked();
		minimapSamples.setVisibleInGui(mapVisible);
		rotateWithPlayer.setVisibleInGui(mapVisible);
		undergroundMode.setVisibleInGui(mapVisible);
		invertRotation.setVisibleInGui(mapVisible);
		showSmallPlants.setVisibleInGui(mapVisible);
		showTreeCanopies.setVisibleInGui(mapVisible);
		basicPaletteMode.setVisibleInGui(mapVisible);
		surfaceDynamicLighting.setVisibleInGui(mapVisible);
		undergroundDynamicLighting.setVisibleInGui(mapVisible);
		textureSharpness.setVisibleInGui(mapVisible);
		surfaceRelief.setVisibleInGui(mapVisible);
		surfaceBrightness.setVisibleInGui(mapVisible);
		surfaceContrast.setVisibleInGui(mapVisible);
		surfaceSaturation.setVisibleInGui(mapVisible);
		surfaceContourLimit.setVisibleInGui(mapVisible);
		surfaceContourSoftness.setVisibleInGui(mapVisible);
		grassTintStrength.setVisibleInGui(mapVisible);
		useDefaultGrassColor.setVisibleInGui(mapVisible);
		grassTintColor.setVisibleInGui(mapVisible);
		foliageTintStrength.setVisibleInGui(mapVisible);
		useDefaultFoliageColor.setVisibleInGui(mapVisible);
		foliageTintColor.setVisibleInGui(mapVisible);
		waterTintStrength.setVisibleInGui(mapVisible);
		useDefaultWaterColor.setVisibleInGui(mapVisible);
		waterTintColor.setVisibleInGui(mapVisible);
		waterDetail.setVisibleInGui(mapVisible);
		waterOpacity.setVisibleInGui(mapVisible);
		chunkRefreshAggression.setVisibleInGui(mapVisible);
		mapEspGroup.setVisibleInGui(espVisible);
		chestEspOnMap.setVisibleInGui(espVisible && !niceWurst);
		workstationEspOnMap.setVisibleInGui(espVisible && !niceWurst);
		signEspOnMap.setVisibleInGui(espVisible && !niceWurst);
		portalEspOnMap.setVisibleInGui(espVisible);
		playerEspOnMap.setVisibleInGui(espVisible);
		logoutSpotsOnMap.setVisibleInGui(espVisible);
		bedEspOnMap.setVisibleInGui(espVisible && !niceWurst);
	}
	
	private static boolean isEditorScreen(Screen screen)
	{
		return screen instanceof ClickGuiScreen
			|| screen instanceof AltGuiScreen
			|| screen instanceof NavigatorScreen;
	}
	
	private int getMarkerSize(XMapConfig cfg)
	{
		return minimapIconSize.getValueI();
	}
	
	private int getFullscreenMarkerSize(int drawSize)
	{
		return worldMapIconSize.getValueI();
	}
	
	public void openWorldMap()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		MC.setScreen(new WorldMapScreen(this, renderService));
	}
	
	public void renderFullscreenMapEsp(GuiGraphicsExtractor context, int mapX,
		int mapY, int drawWidth, int drawHeight, double centerX, double centerZ,
		double blocksPerPixel)
	{
		if(MC.player == null || MC.level == null)
			return;
		if(!espIconsEnabled.isChecked())
			return;
		
		context.enableScissor(mapX, mapY, mapX + drawWidth, mapY + drawHeight);
		try
		{
			if(chestEspOnMap.isChecked()
				&& WURST.getHax().chestEspHack.isEnabled())
				renderFullscreenChestMarkers(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel);
			if(workstationEspOnMap.isChecked()
				&& WURST.getHax().workstationEspHack.isEnabled())
				renderFullscreenBlockGroups(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel,
					WURST.getHax().workstationEspHack.getMapaGroups());
			if(signEspOnMap.isChecked()
				&& WURST.getHax().signEspHack.isEnabled())
			{
				renderFullscreenAabbs(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel,
					WURST.getHax().signEspHack.getMapaSignBoxes(),
					iconForItem(Items.OAK_SIGN),
					WURST.getHax().signEspHack.getMapaSignColor());
				renderFullscreenAabbs(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel,
					WURST.getHax().signEspHack.getMapaFrameBoxes(1.0f),
					iconForItem(Items.ITEM_FRAME),
					WURST.getHax().signEspHack.getMapaFrameColor());
			}
			if(portalEspOnMap.isChecked()
				&& WURST.getHax().portalEspHack.isEnabled())
				renderFullscreenBlockGroups(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel,
					WURST.getHax().portalEspHack.getMapaGroups());
			if(playerEspOnMap.isChecked()
				&& WURST.getHax().playerEspHack.isEnabled())
				renderFullscreenPlayers(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel);
			if(logoutSpotsOnMap.isChecked()
				&& WURST.getHax().logoutSpotsHack.isEnabled())
				renderFullscreenLogoutSpots(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel);
			if(bedEspOnMap.isChecked() && WURST.getHax().bedEspHack.isEnabled())
				renderFullscreenAabbs(context, mapX, mapY, drawWidth,
					drawHeight, centerX, centerZ, blocksPerPixel,
					WURST.getHax().bedEspHack.getMapaBoxes(),
					iconForItem(Items.RED_BED),
					WURST.getHax().bedEspHack.getMapaColor());
		}finally
		{
			context.disableScissor();
		}
	}
	
	private void renderFullscreenChestMarkers(GuiGraphicsExtractor context,
		int mapX, int mapY, int drawWidth, int drawHeight, double centerX,
		double centerZ, double blocksPerPixel)
	{
		ChestEspHack chestEsp = WURST.getHax().chestEspHack;
		ChestEspGroupManager manager = chestEsp.getMapaGroupManager();
		chestEsp.updateMapaEntityBoxes(1.0f);
		for(ChestEspBlockGroup group : manager.blockGroups)
			renderFullscreenAabbs(context, mapX, mapY, drawWidth, drawHeight,
				centerX, centerZ, blocksPerPixel, group.getBoxes(),
				iconForChestGroup(group), group.getColorI(0xFF));
	}
	
	private void renderFullscreenBlockGroups(GuiGraphicsExtractor context,
		int mapX, int mapY, int drawWidth, int drawHeight, double centerX,
		double centerZ, double blocksPerPixel, List<PortalEspBlockGroup> groups)
	{
		int markerSize =
			getFullscreenMarkerSize(Math.max(drawWidth, drawHeight));
		for(PortalEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			for(BlockPos pos : group.getPositions())
			{
				MapPoint point = projectToFullscreenMap(Vec3.atCenterOf(pos),
					mapX, mapY, drawWidth, drawHeight, centerX, centerZ,
					blocksPerPixel);
				if(point != null)
					renderItemMarker(context, point,
						iconForBlock(group.getBlock()), markerSize,
						group.getColorI(0xFF));
			}
		}
	}
	
	private void renderFullscreenAabbs(GuiGraphicsExtractor context, int mapX,
		int mapY, int drawWidth, int drawHeight, double centerX, double centerZ,
		double blocksPerPixel, List<AABB> boxes, ItemStack icon,
		int outlineColor)
	{
		int markerSize =
			getFullscreenMarkerSize(Math.max(drawWidth, drawHeight));
		for(AABB box : boxes)
		{
			MapPoint point = projectToFullscreenMap(box.getCenter(), mapX, mapY,
				drawWidth, drawHeight, centerX, centerZ, blocksPerPixel);
			if(point != null)
				renderItemMarker(context, point, icon, markerSize,
					outlineColor);
		}
	}
	
	private void renderFullscreenPlayers(GuiGraphicsExtractor context, int mapX,
		int mapY, int drawWidth, int drawHeight, double centerX, double centerZ,
		double blocksPerPixel)
	{
		int markerSize =
			getFullscreenMarkerSize(Math.max(drawWidth, drawHeight));
		for(Player player : WURST.getHax().playerEspHack.getMapaPlayers())
		{
			if(player == MC.player)
				continue;
			PlayerInfo info = MC.getConnection() == null ? null
				: MC.getConnection().getPlayerInfo(player.getUUID());
			Identifier skin = info != null ? info.getSkin().body().texturePath()
				: player instanceof AbstractClientPlayer clientPlayer
					? clientPlayer.getSkin().body().texturePath()
					: DefaultPlayerSkin.get(player.getUUID()).body()
						.texturePath();
			MapPoint point = projectToFullscreenMap(player.position(), mapX,
				mapY, drawWidth, drawHeight, centerX, centerZ, blocksPerPixel);
			if(point != null)
				renderPlayerHeadMarker(context, point, skin,
					player.getName().getString(), markerSize,
					WURST.getHax().playerEspHack.getMapaPlayerColor(player),
					createConfig().showPlayerNames);
		}
	}
	
	private void renderFullscreenLogoutSpots(GuiGraphicsExtractor context,
		int mapX, int mapY, int drawWidth, int drawHeight, double centerX,
		double centerZ, double blocksPerPixel)
	{
		String dim = MC.level.dimension().identifier().getPath();
		int outlineColor = WURST.getHax().logoutSpotsHack.getMapaLineColor();
		int markerSize =
			getFullscreenMarkerSize(Math.max(drawWidth, drawHeight));
		for(var spot : WURST.getHax().logoutSpotsHack.getMapaSpots())
		{
			if(!dim.equals(spot.dimKey()))
				continue;
			MapPoint point =
				projectToFullscreenMap(spot.box().getCenter(), mapX, mapY,
					drawWidth, drawHeight, centerX, centerZ, blocksPerPixel);
			if(point == null)
				continue;
			int x = Math.round(point.x()) - markerSize / 2;
			int y = Math.round(point.y()) - markerSize / 2;
			drawIconOutline(context, x, y, markerSize, outlineColor);
			context.fill(x, y, x + markerSize, y + markerSize, 0xFF4AA3FF);
		}
	}
	
	private MapPoint projectToFullscreenMap(Vec3 worldPos, int mapX, int mapY,
		int drawWidth, int drawHeight, double centerX, double centerZ,
		double blocksPerPixel)
	{
		double px =
			mapX + drawWidth / 2.0 + (worldPos.x - centerX) / blocksPerPixel;
		double py =
			mapY + drawHeight / 2.0 + (worldPos.z - centerZ) / blocksPerPixel;
		if(px < mapX || py < mapY || px >= mapX + drawWidth
			|| py >= mapY + drawHeight)
			return null;
		return new MapPoint((float)px, (float)py);
	}
	
	private int getScaledMouseX()
	{
		return (int)(MC.mouseHandler.xpos() * MC.getWindow().getGuiScaledWidth()
			/ MC.getWindow().getScreenWidth());
	}
	
	private int getScaledMouseY()
	{
		return (int)(MC.mouseHandler.ypos()
			* MC.getWindow().getGuiScaledHeight()
			/ MC.getWindow().getScreenHeight());
	}
	
	private record MapPoint(float x, float y)
	{}
}
