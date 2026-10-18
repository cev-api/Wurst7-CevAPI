/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon.gif;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Small, dependency-free animated GIF decoder built on {@link ImageIO}.
 *
 * <p>
 * Decoding is intentionally independent of Minecraft so it can run on a
 * background thread and be unit tested without a game instance.
 */
public final class GifDecoder
{
	private static final int MAX_FRAMES = 2048;
	private static final int MAX_DIMENSION = 4096;
	private static final long MAX_PIXELS = 4096L * 4096L;
	private static final long MAX_TOTAL_PIXELS = 48L * 1024L * 1024L;
	
	private GifDecoder()
	{}
	
	public static DecodedGif decode(byte[] bytes) throws IOException
	{
		if(bytes == null || bytes.length == 0)
			throw new IOException("The GIF is empty.");
		
		try(ImageInputStream input =
			ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
		{
			if(input == null)
				throw new IOException("The GIF could not be read.");
			
			Iterator<ImageReader> readers =
				ImageIO.getImageReadersByFormatName("gif");
			if(!readers.hasNext())
				throw new IOException("No GIF decoder is available.");
			
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(input, false, false);
				return decode(reader);
				
			}finally
			{
				reader.dispose();
			}
		}
	}
	
	private static DecodedGif decode(ImageReader reader) throws IOException
	{
		int width = reader.getWidth(0);
		int height = reader.getHeight(0);
		validateDimensions(width, height);
		
		int frameCount = reader.getNumImages(true);
		if(frameCount <= 0)
			throw new IOException("The GIF contains no frames.");
		frameCount = Math.min(frameCount, MAX_FRAMES);
		
		// Keep the decoded pixel budget bounded so an unusual GIF cannot
		// exhaust memory on the client.
		long pixelsPerFrame = (long)width * height;
		frameCount = (int)Math.min(frameCount,
			Math.max(1L, MAX_TOTAL_PIXELS / pixelsPerFrame));
		
		BufferedImage canvas =
			new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = canvas.createGraphics();
		graphics.setComposite(AlphaComposite.SrcOver);
		
		List<DecodedFrame> frames = new ArrayList<>(frameCount);
		int cropMinX = width;
		int cropMinY = height;
		int cropMaxX = -1;
		int cropMaxY = -1;
		
		BufferedImage restorePoint = null;
		int previousDisposal = 0;
		FrameLayout previousLayout = new FrameLayout(0, 0, 0, 0);
		long totalDuration = 0L;
		
		try
		{
			for(int i = 0; i < frameCount; i++)
			{
				if(i > 0)
					applyPreviousDisposal(graphics, canvas, previousDisposal,
						previousLayout, restorePoint);
				
				IIOMetadata metadata = reader.getImageMetadata(i);
				FrameLayout layout = getFrameLayout(metadata);
				int disposal = getDisposalMethod(metadata);
				int delay = getFrameDelay(metadata);
				
				if(disposal == 3)
					restorePoint = copyImage(canvas);
				
				BufferedImage frame = reader.read(i);
				if(frame == null)
				{
					previousDisposal = disposal;
					previousLayout = layout;
					continue;
				}
				
				graphics.setComposite(AlphaComposite.SrcOver);
				boolean isTile = layout.width > 0 && layout.height > 0
					&& frame.getWidth() == layout.width
					&& frame.getHeight() == layout.height
					&& (layout.width != width || layout.height != height);
				if(isTile)
					graphics.drawImage(frame, layout.x, layout.y, null);
				else
					graphics.drawImage(frame, 0, 0, null);
				
				int[] abgr = copyPixelsAsAbgr(canvas);
				frames.add(new DecodedFrame(width, height, abgr, delay));
				totalDuration += delay;
				
				int[] bounds = opaqueBounds(abgr, width, height);
				if(bounds[2] > 0 && bounds[3] > 0)
				{
					cropMinX = Math.min(cropMinX, bounds[0]);
					cropMinY = Math.min(cropMinY, bounds[1]);
					cropMaxX = Math.max(cropMaxX, bounds[0] + bounds[2]);
					cropMaxY = Math.max(cropMaxY, bounds[1] + bounds[3]);
				}
				
				previousDisposal = disposal;
				previousLayout = layout;
			}
			
		}finally
		{
			graphics.dispose();
		}
		
		if(frames.isEmpty())
			throw new IOException("The GIF contains no decodable frames.");
		
		if(cropMaxX < cropMinX || cropMaxY < cropMinY)
		{
			cropMinX = 0;
			cropMinY = 0;
			cropMaxX = width;
			cropMaxY = height;
		}
		
		return new DecodedGif(Collections.unmodifiableList(frames), width,
			height, cropMinX, cropMinY, cropMaxX - cropMinX,
			cropMaxY - cropMinY, totalDuration);
	}
	
	private static void applyPreviousDisposal(Graphics2D graphics,
		BufferedImage canvas, int disposal, FrameLayout layout,
		BufferedImage restorePoint)
	{
		if(disposal == 2)
		{
			graphics.setComposite(AlphaComposite.Clear);
			graphics.fillRect(layout.x, layout.y,
				layout.width > 0 ? layout.width : canvas.getWidth(),
				layout.height > 0 ? layout.height : canvas.getHeight());
			graphics.setComposite(AlphaComposite.SrcOver);
			
		}else if(disposal == 3 && restorePoint != null)
		{
			graphics.setComposite(AlphaComposite.Src);
			graphics.drawImage(restorePoint, 0, 0, null);
			graphics.setComposite(AlphaComposite.SrcOver);
		}
	}
	
	private static void validateDimensions(int width, int height)
		throws IOException
	{
		if(width <= 0 || height <= 0 || width > MAX_DIMENSION
			|| height > MAX_DIMENSION || (long)width * height > MAX_PIXELS)
			throw new IOException("The GIF dimensions are not supported.");
	}
	
	private static int[] copyPixelsAsAbgr(BufferedImage image)
	{
		int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(),
			null, 0, image.getWidth());
		for(int i = 0; i < pixels.length; i++)
		{
			int argb = pixels[i];
			pixels[i] = argb & 0xFF00FF00 | argb << 16 & 0x00FF0000
				| argb >>> 16 & 0x000000FF;
		}
		return pixels;
	}
	
	/**
	 * Copies a sub-rectangle out of a full-canvas frame. Kept separate from the
	 * GPU upload so the row arithmetic can be tested without Minecraft.
	 */
	public static int[] cropAbgr(DecodedFrame frame, int x, int y, int width,
		int height)
	{
		int canvasWidth = frame.width();
		if(x < 0 || y < 0 || width <= 0 || height <= 0
			|| x + width > canvasWidth || y + height > frame.height())
			throw new IllegalArgumentException("Crop is outside the frame.");
		
		int[] source = frame.abgr();
		int[] cropped = new int[width * height];
		for(int row = 0; row < height; row++)
			System.arraycopy(source, (y + row) * canvasWidth + x, cropped,
				row * width, width);
		return cropped;
	}
	
	/**
	 * True when every frame is fully transparent, which means the GIF would be
	 * invisible if rendered.
	 */
	public static boolean isFullyTransparent(DecodedGif gif)
	{
		for(DecodedFrame frame : gif.frames())
			for(int pixel : frame.abgr())
				if((pixel >>> 24) != 0)
					return false;
		return true;
	}
	
	private static BufferedImage copyImage(BufferedImage source)
	{
		BufferedImage copy = new BufferedImage(source.getWidth(),
			source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = copy.createGraphics();
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return copy;
	}
	
	private static int[] opaqueBounds(int[] abgr, int width, int height)
	{
		int minX = width;
		int minY = height;
		int maxX = -1;
		int maxY = -1;
		for(int y = 0; y < height; y++)
		{
			int row = y * width;
			for(int x = 0; x < width; x++)
			{
				if((abgr[row + x] >>> 24) == 0)
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
		}
		
		if(maxX < minX || maxY < minY)
			return new int[]{0, 0, 0, 0};
		return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
	}
	
	private static FrameLayout getFrameLayout(IIOMetadata metadata)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"ImageDescriptor".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attributes = node.getAttributes();
				if(attributes == null)
					break;
				
				int x = parseInt(attributes, "imageLeftPosition", 0);
				int y = parseInt(attributes, "imageTopPosition", 0);
				int width = parseInt(attributes, "imageWidth", 0);
				int height = parseInt(attributes, "imageHeight", 0);
				return new FrameLayout(x, y, width, height);
			}
			
		}catch(Exception ignored)
		{}
		return new FrameLayout(0, 0, 0, 0);
	}
	
	private static int getDisposalMethod(IIOMetadata metadata)
	{
		String value = getGraphicControlValue(metadata, "disposalMethod");
		if(value == null)
			return 0;
		
		return switch(value)
		{
			case "restoreToBackgroundColor" -> 2;
			case "restoreToPrevious" -> 3;
			default -> 0;
		};
	}
	
	private static int getFrameDelay(IIOMetadata metadata)
	{
		String value = getGraphicControlValue(metadata, "delayTime");
		if(value == null)
			return 100;
		
		try
		{
			int hundredths = Integer.parseInt(value);
			if(hundredths <= 0)
				return 100;
			return Math.max(20, hundredths * 10);
			
		}catch(NumberFormatException e)
		{
			return 100;
		}
	}
	
	private static String getGraphicControlValue(IIOMetadata metadata,
		String attribute)
	{
		try
		{
			Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
			for(Node node = root.getFirstChild(); node != null; node =
				node.getNextSibling())
			{
				if(!"GraphicControlExtension".equals(node.getNodeName()))
					continue;
				
				NamedNodeMap attributes = node.getAttributes();
				if(attributes == null)
					return null;
				Node value = attributes.getNamedItem(attribute);
				return value == null ? null : value.getNodeValue();
			}
			
		}catch(Exception ignored)
		{}
		return null;
	}
	
	private static int parseInt(NamedNodeMap attributes, String name,
		int fallback)
	{
		Node node = attributes.getNamedItem(name);
		if(node == null)
			return fallback;
		try
		{
			return Integer.parseInt(node.getNodeValue());
			
		}catch(NumberFormatException e)
		{
			return fallback;
		}
	}
	
	public record DecodedGif(List<DecodedFrame> frames, int canvasWidth,
		int canvasHeight, int cropX, int cropY, int cropWidth, int cropHeight,
		long totalDurationMs)
	{}
	
	public record DecodedFrame(int width, int height, int[] abgr, int delayMs)
	{}
	
	private record FrameLayout(int x, int y, int width, int height)
	{}
}
