/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mapa.screen;

import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.wurstclient.hacks.MapaHack;
import net.wurstclient.mapa.map.MapRenderService;

public final class WorldMapScreen extends Screen
{
	private static final Identifier WORLD_MAP_TEX_ID =
		Identifier.fromNamespaceAndPath("wurst", "dynamic/world_map");
	private final MapaHack hack;
	private final MapRenderService mapRenderService;
	private double centerX;
	private double centerZ;
	private double blocksPerPixel = 1.0;
	private DynamicTexture worldMapTexture;
	private int textureWidth;
	private int textureHeight;
	private double lastCenterX = Double.NaN;
	private double lastCenterZ = Double.NaN;
	private double lastBlocksPerPixel = Double.NaN;
	private String lastCacheKey = "";
	private int lastKeyCount = -1;
	
	public WorldMapScreen(MapaHack hack, MapRenderService mapRenderService)
	{
		super(Component.literal("Mapa World Map"));
		this.hack = hack;
		this.mapRenderService = mapRenderService;
	}
	
	@Override
	protected void init()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player != null)
		{
			centerX = mc.player.getX();
			centerZ = mc.player.getZ();
		}
		blocksPerPixel = MapRenderService
			.zoomToBlocksPerPixel(hack.createConfig().minimapZoom);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		extractTransparentBackground(context);
		
		int frame = 12;
		int drawW = width - frame * 2;
		int drawH = height - frame * 2;
		int mapX = frame;
		int mapY = frame;
		
		context.fill(mapX - 2, mapY - 2, mapX + drawW + 2, mapY + drawH + 2,
			0xCC000000);
		context.fill(mapX - 1, mapY - 1, mapX + drawW + 1, mapY + drawH + 1,
			0xFF6F6F6F);
		
		var cfg = hack.createConfig();
		if(cfg.enabled)
		{
			MapRenderService.WorldMapSnapshot snapshot =
				mapRenderService.snapshotWorldMap(Minecraft.getInstance());
			ensureTexture(drawW, drawH);
			if(shouldRebuild(snapshot, drawW, drawH))
				rebuildTexture(snapshot, drawW, drawH);
			if(worldMapTexture != null)
				context.blit(RenderPipelines.GUI_TEXTURED, WORLD_MAP_TEX_ID,
					mapX, mapY, 0, 0, drawW, drawH, drawW, drawH, 0xFFFFFFFF);
		}
		
		hack.renderFullscreenMapEsp(context, mapX, mapY, drawW, drawH, centerX,
			centerZ, blocksPerPixel);
		
		if(cfg.showCenterCross)
		{
			int cx = mapX + drawW / 2;
			int cy = mapY + drawH / 2;
			context.fill(cx - 1, cy - 6, cx + 1, cy + 6, 0xFFFFFFFF);
			context.fill(cx - 6, cy - 1, cx + 6, cy + 1, 0xFFFFFFFF);
		}
		
		int cachedColumns = cfg.enabled ? mapRenderService
			.snapshotWorldMap(Minecraft.getInstance()).keys().length : 0;
		String info = String.format(java.util.Locale.ROOT,
			"Zoom %.2f blocks/pixel | Cached columns %d | Drag to pan | Scroll to zoom | Space to recenter",
			blocksPerPixel, cachedColumns);
		context.text(font, info, mapX, Math.max(4, mapY - 10), 0xFFFFFFFF,
			true);
		super.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
		double scrollY)
	{
		if(scrollY == 0.0)
			return false;
		
		double factor = scrollY > 0.0 ? 0.85 : 1.15;
		blocksPerPixel = Mth.clamp(blocksPerPixel * factor, 0.25, 10.0);
		return true;
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX,
		double dragY)
	{
		if(event.button() != 0)
			return false;
		
		centerX -= dragX * blocksPerPixel;
		centerZ -= dragY * blocksPerPixel;
		setDragging(true);
		return true;
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent event)
	{
		setDragging(false);
		return super.mouseReleased(event);
	}
	
	@Override
	public void removed()
	{
		if(worldMapTexture != null)
		{
			worldMapTexture.close();
			worldMapTexture = null;
		}
		super.removed();
	}
	
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(event.key() == GLFW.GLFW_KEY_SPACE)
		{
			MapRenderService.WorldMapSnapshot snapshot =
				mapRenderService.snapshotWorldMap(Minecraft.getInstance());
			centerX = snapshot.playerX();
			centerZ = snapshot.playerZ();
			return true;
		}
		
		return super.keyPressed(event);
	}
	
	private void ensureTexture(int width, int height)
	{
		if(worldMapTexture != null && textureWidth == width
			&& textureHeight == height)
			return;
		if(worldMapTexture != null)
			worldMapTexture.close();
		textureWidth = width;
		textureHeight = height;
		worldMapTexture =
			new DynamicTexture("wurst_world_map", width, height, false);
		Minecraft.getInstance().getTextureManager().register(WORLD_MAP_TEX_ID,
			worldMapTexture);
		lastCenterX = Double.NaN;
		lastCenterZ = Double.NaN;
		lastBlocksPerPixel = Double.NaN;
		lastCacheKey = "";
		lastKeyCount = -1;
	}
	
	private boolean shouldRebuild(MapRenderService.WorldMapSnapshot snapshot,
		int width, int height)
	{
		return worldMapTexture != null && (Double.isNaN(lastCenterX)
			|| Math.abs(centerX - lastCenterX) > 0.001
			|| Math.abs(centerZ - lastCenterZ) > 0.001
			|| Math.abs(blocksPerPixel - lastBlocksPerPixel) > 0.0001
			|| !snapshot.cacheKey().equals(lastCacheKey)
			|| snapshot.keys().length != lastKeyCount || textureWidth != width
			|| textureHeight != height);
	}
	
	private void rebuildTexture(MapRenderService.WorldMapSnapshot snapshot,
		int width, int height)
	{
		NativeImage image = worldMapTexture.getPixels();
		if(image == null)
			return;
		
		for(int x = 0; x < width; x++)
			for(int y = 0; y < height; y++)
				image.setPixel(x, y, 0xFF101010);
			
		double halfWidth = width * blocksPerPixel * 0.5;
		double halfHeight = height * blocksPerPixel * 0.5;
		double minX = centerX - halfWidth;
		double minZ = centerZ - halfHeight;
		double maxX = centerX + halfWidth;
		double maxZ = centerZ + halfHeight;
		double pixelsPerBlock = 1.0 / Math.max(0.0001, blocksPerPixel);
		
		long[] keys = snapshot.keys();
		int[] colors = snapshot.colors();
		for(int i = 0; i < keys.length; i++)
		{
			long key = keys[i];
			if(MapRenderService.unpackColumnUnderground(key))
				continue;
			int wx = MapRenderService.unpackColumnX(key);
			int wz = MapRenderService.unpackColumnZ(key);
			if(wx < minX - 1 || wz < minZ - 1 || wx > maxX + 1 || wz > maxZ + 1)
				continue;
			
			int px0 = (int)Math.floor((wx - minX) * pixelsPerBlock);
			int py0 = (int)Math.floor((wz - minZ) * pixelsPerBlock);
			int px1 = Math.max(px0 + 1,
				(int)Math.ceil((wx + 1 - minX) * pixelsPerBlock));
			int py1 = Math.max(py0 + 1,
				(int)Math.ceil((wz + 1 - minZ) * pixelsPerBlock));
			
			if(px1 <= 0 || py1 <= 0 || px0 >= width || py0 >= height)
				continue;
			int argb = 0xFF000000 | (colors[i] & 0x00FFFFFF);
			for(int px = Math.max(0, px0); px < Math.min(width, px1); px++)
				for(int py = Math.max(0, py0); py < Math.min(height, py1); py++)
					image.setPixel(px, py, argb);
		}
		
		worldMapTexture.upload();
		lastCenterX = centerX;
		lastCenterZ = centerZ;
		lastBlocksPerPixel = blocksPerPixel;
		lastCacheKey = snapshot.cacheKey();
		lastKeyCount = snapshot.keys().length;
	}
}
