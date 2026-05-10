/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.lwjgl.glfw.GLFW;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.wurstclient.Category;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.MouseButtonPressListener;
import net.wurstclient.events.MouseButtonPressListener.MouseButtonPressEvent;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.events.MouseUpdateListener.MouseUpdateEvent;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;

public final class NecoCmd extends Command
	implements GUIRenderListener, MouseButtonPressListener, MouseUpdateListener
{
	private static final String GIF_PREFIX = "neco_gif_";
	
	private static final int DEFAULT_DRAW_W = 200;
	private static final int DEFAULT_DRAW_H = 200;
	private static final int MAX_DRAW_SIZE = 320;
	private static final int RIGHT_MARGIN = 10;
	private static final int ABOVE_HUNGER = 4;
	private static final int HEALTHBAR_BASELINE = 52;
	
	private final SliderSetting scaleSetting =
		new SliderSetting("Scale", "Scale of the rendered Neco GIF.", 0.25,
			0.25, 2.0, 0.05, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private boolean enabled;
	private boolean dragging;
	private int dragOffsetX;
	private int dragOffsetY;
	private int necoX = Integer.MIN_VALUE;
	private int necoY = Integer.MIN_VALUE;
	private int drawW = DEFAULT_DRAW_W;
	private int drawH = DEFAULT_DRAW_H;
	private long animationStartMs;
	private boolean gifLoadFailed;
	private int cropX;
	private int cropY;
	private int cropW;
	private int cropH;
	private final ArrayList<GifFrame> gifFrames = new ArrayList<>();
	
	public NecoCmd()
	{
		super("neco", "Spawns a dancing Neco-Arc.\n");
		setCategory(Category.FUN);
		addSetting(scaleSetting);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length != 0)
			throw new CmdSyntaxError(
				"Neco-arc doesn't want your arguments! Use the Scale setting.");
		
		enabled = !enabled;
		
		if(enabled)
		{
			loadRandomGifByPrefix();
			EVENTS.add(GUIRenderListener.class, this);
			EVENTS.add(MouseButtonPressListener.class, this);
			EVENTS.add(MouseUpdateListener.class, this);
		}else
		{
			EVENTS.remove(GUIRenderListener.class, this);
			EVENTS.remove(MouseButtonPressListener.class, this);
			EVENTS.remove(MouseUpdateListener.class, this);
			dragging = false;
			clearGifFrames();
		}
	}
	
	@Override
	public String getPrimaryAction()
	{
		return "Summon Neco-Arc!";
	}
	
	@Override
	public void doPrimaryAction()
	{
		WURST.getCmdProcessor().process("neco");
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!enabled || gifLoadFailed || gifFrames.isEmpty())
			return;
		
		int color = WURST.getHax().rainbowUiHack.isEnabled()
			? RenderUtils.toIntColor(WURST.getGui().getAcColor(), 1)
			: 0xFFFFFFFF;
		
		int sw = context.guiWidth();
		int sh = context.guiHeight();
		int healthbarBaselineY = sh - HEALTHBAR_BASELINE;
		
		// Apply slider changes live while Neco is visible.
		applyDrawSize();
		
		if(necoX == Integer.MIN_VALUE || necoY == Integer.MIN_VALUE)
		{
			necoX = sw - drawW - RIGHT_MARGIN;
			necoY = healthbarBaselineY - drawH - ABOVE_HUNGER;
		}
		
		int maxX = Math.max(0, sw - drawW);
		int maxY = Math.max(0, sh - drawH);
		necoX = Mth.clamp(necoX, 0, maxX);
		necoY = Mth.clamp(necoY, 0, maxY);
		
		GifFrame frame = getCurrentFrame();
		context.blit(RenderPipelines.GUI_TEXTURED, frame.textureId, necoX,
			necoY, cropX, cropY, drawW, drawH, cropW, cropH, frame.width,
			frame.height, color);
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
		
		if(event.getAction() != GLFW.GLFW_PRESS || !enabled)
			return;
		
		int mouseX = getScaledMouseX();
		int mouseY = getScaledMouseY();
		if(mouseX < necoX || mouseX > necoX + drawW || mouseY < necoY
			|| mouseY > necoY + drawH)
			return;
		
		dragging = true;
		dragOffsetX = mouseX - necoX;
		dragOffsetY = mouseY - necoY;
	}
	
	@Override
	public void onMouseUpdate(MouseUpdateEvent event)
	{
		if(!dragging || !enabled)
			return;
		
		int maxX = Math.max(0, MC.getWindow().getGuiScaledWidth() - drawW);
		int maxY = Math.max(0, MC.getWindow().getGuiScaledHeight() - drawH);
		necoX = Mth.clamp(getScaledMouseX() - dragOffsetX, 0, maxX);
		necoY = Mth.clamp(getScaledMouseY() - dragOffsetY, 0, maxY);
	}
	
	private int getScaledMouseX()
	{
		return (int)Math.round(MC.mouseHandler.xpos()
			* MC.getWindow().getGuiScaledWidth() / MC.getWindow().getWidth());
	}
	
	private int getScaledMouseY()
	{
		return (int)Math.round(MC.mouseHandler.ypos()
			* MC.getWindow().getGuiScaledHeight() / MC.getWindow().getHeight());
	}
	
	private void loadRandomGifByPrefix()
	{
		clearGifFrames();
		gifLoadFailed = false;
		if(MC == null || MC.getResourceManager() == null)
		{
			gifLoadFailed = true;
			return;
		}
		
		List<Identifier> candidates = new ArrayList<>(MC.getResourceManager()
			.listResources("",
				id -> id.getNamespace().equals("wurst")
					&& id.getPath().endsWith(".gif")
					&& id.getPath().startsWith(GIF_PREFIX))
			.keySet());
		if(candidates.isEmpty())
		{
			gifLoadFailed = true;
			return;
		}
		
		Collections.shuffle(candidates, new Random());
		for(Identifier id : candidates)
			if(loadGifFrames(id))
			{
				animationStartMs = System.currentTimeMillis();
				return;
			}
		
		gifLoadFailed = true;
	}
	
	private boolean loadGifFrames(Identifier id)
	{
		try
		{
			List<Resource> stack = MC.getResourceManager().getResourceStack(id);
			if(stack.isEmpty())
				return false;
			
			Resource chosen = stack.get(stack.size() - 1);
			try(InputStream in = chosen.open())
			{
				byte[] bytes = in.readAllBytes();
				decodeGifFrames(bytes);
				applyDrawSize();
				return !gifFrames.isEmpty();
			}
		}catch(Exception e)
		{
			clearGifFrames();
			return false;
		}
	}
	
	private void decodeGifFrames(byte[] bytes) throws IOException
	{
		try(ImageInputStream iis =
			ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
		{
			java.util.Iterator<ImageReader> readers =
				ImageIO.getImageReadersByFormatName("gif");
			if(!readers.hasNext())
				return;
			
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(iis, false, false);
				BufferedImage canvas = new BufferedImage(reader.getWidth(0),
					reader.getHeight(0), BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = canvas.createGraphics();
				graphics.setComposite(AlphaComposite.SrcOver);
				int frameCount = reader.getNumImages(true);
				for(int i = 0; i < frameCount; i++)
				{
					BufferedImage frameImage = reader.read(i);
					if(frameImage == null)
						continue;
					
					GifFrameLayout layout =
						getGifFrameLayout(reader.getImageMetadata(i));
					String disposal =
						getGifDisposalMethod(reader.getImageMetadata(i));
					if("restoreToBackgroundColor".equals(disposal))
					{
						graphics.setComposite(AlphaComposite.Clear);
						graphics.fillRect(layout.x, layout.y, layout.w,
							layout.h);
						graphics.setComposite(AlphaComposite.SrcOver);
					}
					
					boolean isTileFrame = layout.w > 0 && layout.h > 0
						&& frameImage.getWidth() == layout.w
						&& frameImage.getHeight() == layout.h
						&& (layout.w != canvas.getWidth()
							|| layout.h != canvas.getHeight());
					if(isTileFrame)
						graphics.drawImage(frameImage, layout.x, layout.y,
							null);
					else
						graphics.drawImage(frameImage, 0, 0, null);
					
					int delayMs =
						getGifFrameDelayMs(reader.getImageMetadata(i));
					BufferedImage copy = deepCopyBufferedImage(canvas);
					NativeImage nativeFrame = bufferedImageToNative(copy);
					try
					{
						registerGifFrame(i, nativeFrame, delayMs);
					}finally
					{
						nativeFrame.close();
					}
				}
				graphics.dispose();
			}finally
			{
				reader.dispose();
			}
		}
	}
	
	private void registerGifFrame(int frameIndex, NativeImage image,
		int delayMs)
	{
		int width = image.getWidth();
		int height = image.getHeight();
		int[] bounds = getOpaqueBounds(image);
		Identifier frameId = Identifier.fromNamespaceAndPath("wurst",
			"dynamic/neco_gif_" + frameIndex + "_" + System.nanoTime());
		DynamicTexture texture =
			new DynamicTexture("neco_gif_" + frameIndex, width, height, false);
		NativeImage pixels = texture.getPixels();
		if(pixels == null)
			return;
		
		for(int px = 0; px < width; px++)
			for(int py = 0; py < height; py++)
				pixels.setPixel(px, py, image.getPixel(px, py));
			
		texture.upload();
		MC.getTextureManager().register(frameId, texture);
		gifFrames.add(new GifFrame(frameId, texture, Math.max(20, delayMs),
			width, height));
		updateGlobalCrop(bounds);
	}
	
	private GifFrame getCurrentFrame()
	{
		long totalMs = 0;
		for(GifFrame frame : gifFrames)
			totalMs += frame.delayMs;
		
		if(totalMs <= 0)
			return gifFrames.get(0);
		
		long elapsed =
			(System.currentTimeMillis() - animationStartMs) % totalMs;
		long cursor = 0;
		for(GifFrame frame : gifFrames)
		{
			cursor += frame.delayMs;
			if(elapsed < cursor)
				return frame;
		}
		
		return gifFrames.get(gifFrames.size() - 1);
	}
	
	private void clearGifFrames()
	{
		for(GifFrame frame : gifFrames)
			frame.texture.close();
		gifFrames.clear();
		drawW = DEFAULT_DRAW_W;
		drawH = DEFAULT_DRAW_H;
		cropX = 0;
		cropY = 0;
		cropW = 0;
		cropH = 0;
	}
	
	private int[] getOpaqueBounds(NativeImage image)
	{
		int minX = image.getWidth();
		int minY = image.getHeight();
		int maxX = -1;
		int maxY = -1;
		for(int y = 0; y < image.getHeight(); y++)
			for(int x = 0; x < image.getWidth(); x++)
			{
				int alpha = (image.getPixel(x, y) >>> 24) & 0xFF;
				if(alpha == 0)
					continue;
				if(x < minX)
					minX = x;
				if(y < minY)
					minY = y;
				if(x > maxX)
					maxX = x;
				if(y > maxY)
					maxY = y;
			}
		
		if(maxX < minX || maxY < minY)
			return new int[]{0, 0, image.getWidth(), image.getHeight()};
		
		return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
	}
	
	private void updateGlobalCrop(int[] bounds)
	{
		int bx = bounds[0];
		int by = bounds[1];
		int bw = bounds[2];
		int bh = bounds[3];
		if(bw <= 0 || bh <= 0)
			return;
		
		if(cropW <= 0 || cropH <= 0)
		{
			cropX = bx;
			cropY = by;
			cropW = bw;
			cropH = bh;
			return;
		}
		
		int minX = Math.min(cropX, bx);
		int minY = Math.min(cropY, by);
		int maxX = Math.max(cropX + cropW, bx + bw);
		int maxY = Math.max(cropY + cropH, by + bh);
		cropX = minX;
		cropY = minY;
		cropW = maxX - minX;
		cropH = maxY - minY;
	}
	
	private void applyDrawSize()
	{
		if(cropW <= 0 || cropH <= 0)
		{
			drawW = DEFAULT_DRAW_W;
			drawH = DEFAULT_DRAW_H;
			return;
		}
		
		float baseScale =
			Math.min(1.0f, MAX_DRAW_SIZE / (float)Math.max(cropW, cropH));
		float finalScale = baseScale * (float)scaleSetting.getValue();
		drawW = Math.max(24, Math.round(cropW * finalScale));
		drawH = Math.max(24, Math.round(cropH * finalScale));
	}
	
	private GifFrameLayout getGifFrameLayout(IIOMetadata metadata)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"ImageDescriptor".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attrs = node.getAttributes();
				if(attrs == null)
					break;
				
				int x = Integer.parseInt(
					attrs.getNamedItem("imageLeftPosition").getNodeValue());
				int y = Integer.parseInt(
					attrs.getNamedItem("imageTopPosition").getNodeValue());
				int w = Integer
					.parseInt(attrs.getNamedItem("imageWidth").getNodeValue());
				int h = Integer
					.parseInt(attrs.getNamedItem("imageHeight").getNodeValue());
				return new GifFrameLayout(x, y, w, h);
			}
		}catch(Exception ignored)
		{}
		return new GifFrameLayout(0, 0, 0, 0);
	}
	
	private String getGifDisposalMethod(IIOMetadata metadata)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"GraphicControlExtension".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attrs = node.getAttributes();
				if(attrs == null)
					break;
				
				Node disposal = attrs.getNamedItem("disposalMethod");
				if(disposal != null)
					return disposal.getNodeValue();
			}
		}catch(Exception ignored)
		{}
		return "doNotDispose";
	}
	
	private int getGifFrameDelayMs(IIOMetadata metadata)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"GraphicControlExtension".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attrs = node.getAttributes();
				if(attrs == null)
					continue;
				
				Node delay = attrs.getNamedItem("delayTime");
				if(delay == null)
					continue;
				
				int hundredths = Integer.parseInt(delay.getNodeValue());
				return Math.max(20, hundredths * 10);
			}
		}catch(Exception ignored)
		{}
		return 100;
	}
	
	private NativeImage bufferedImageToNative(BufferedImage buffered)
		throws IOException
	{
		try(ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ImageIO.write(buffered, "png", out);
			try(InputStream in = new ByteArrayInputStream(out.toByteArray()))
			{
				NativeImage image = NativeImage.read(in);
				if(image == null)
					throw new IOException("Failed to decode GIF frame.");
				return image;
			}
		}
	}
	
	private BufferedImage deepCopyBufferedImage(BufferedImage source)
	{
		BufferedImage copy = new BufferedImage(source.getWidth(),
			source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = copy.createGraphics();
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return copy;
	}
	
	private static final class GifFrame
	{
		private final Identifier textureId;
		private final DynamicTexture texture;
		private final int delayMs;
		private final int width;
		private final int height;
		
		private GifFrame(Identifier textureId, DynamicTexture texture,
			int delayMs, int width, int height)
		{
			this.textureId = textureId;
			this.texture = texture;
			this.delayMs = delayMs;
			this.width = width;
			this.height = height;
		}
	}
	
	private static final class GifFrameLayout
	{
		private final int x;
		private final int y;
		private final int w;
		private final int h;
		
		private GifFrameLayout(int x, int y, int w, int h)
		{
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}
	}
}
