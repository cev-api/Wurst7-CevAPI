/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon.gif;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.wurstclient.patreon.gif.GifDecoder.DecodedFrame;
import net.wurstclient.patreon.gif.GifDecoder.DecodedGif;

/**
 * Decodes an animated GIF off-thread and plays it through a single dynamic
 * texture that is updated whenever the visible frame changes.
 */
public final class GifTexturePlayer
{
	private static final String GIF_PREFIX = "neco_gif_";
	private static final int GIF_COUNT = 20;
	private static final AtomicInteger NEXT_TEXTURE_ID = new AtomicInteger();
	private static final ExecutorService DECODE_EXECUTOR =
		Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "Patreon GIF decoder");
			thread.setDaemon(true);
			return thread;
		});
	
	private final List<int[]> frames = new ArrayList<>();
	private final List<Integer> delays = new ArrayList<>();
	private volatile boolean closed;
	private volatile boolean loading;
	private volatile boolean failed;
	private volatile boolean ready;
	private int retries;
	private int imageWidth;
	private int imageHeight;
	private int totalDurationMs;
	private long animationStartMs;
	private int uploadedFrame = -1;
	private Identifier textureId;
	private DynamicTexture texture;
	
	/**
	 * Discovers the bundled Neco GIFs and returns a random available one.
	 */
	public static Identifier pickRandomGif(Minecraft minecraft)
	{
		if(minecraft == null || minecraft.getResourceManager() == null)
			return null;
		
		ResourceManager resources = minecraft.getResourceManager();
		List<Identifier> available = new ArrayList<>(GIF_COUNT);
		for(int i = 1; i <= GIF_COUNT; i++)
		{
			Identifier id = Identifier.fromNamespaceAndPath("wurst",
				GIF_PREFIX + i + ".gif");
			try
			{
				if(!resources.getResourceStack(id).isEmpty())
					available.add(id);
				
			}catch(Exception ignored)
			{}
		}
		
		if(available.isEmpty())
			return null;
		return available.get(new Random().nextInt(available.size()));
	}
	
	public void loadAsync(Identifier id)
	{
		if(closed || loading || ready)
			return;
		
		if(id == null)
		{
			retries++;
			failed = true;
			return;
		}
		
		loading = true;
		failed = false;
		
		CompletableFuture.supplyAsync(() -> readAndDecode(id), DECODE_EXECUTOR)
			.whenComplete((decoded, error) -> {
				Minecraft minecraft = Minecraft.getInstance();
				if(minecraft == null)
				{
					loading = false;
					failed = true;
					return;
				}
				minecraft
					.execute(() -> acceptDecoded(minecraft, decoded, error));
			});
	}
	
	private static DecodedGif readAndDecode(Identifier id)
	{
		Minecraft minecraft = Minecraft.getInstance();
		if(minecraft == null || minecraft.getResourceManager() == null)
			throw new IllegalStateException("No resource manager");
		
		List<Resource> stack =
			minecraft.getResourceManager().getResourceStack(id);
		if(stack.isEmpty())
			throw new IllegalStateException("GIF not found");
		
		Resource resource = stack.get(stack.size() - 1);
		try(InputStream in = resource.open())
		{
			return GifDecoder.decode(in.readAllBytes());
			
		}catch(Exception e)
		{
			throw new IllegalStateException("Couldn't decode GIF", e);
		}
	}
	
	private void acceptDecoded(Minecraft minecraft, DecodedGif decoded,
		Throwable error)
	{
		loading = false;
		if(closed)
			return;
		
		if(error != null || decoded == null || decoded.frames().isEmpty()
			|| GifDecoder.isFullyTransparent(decoded))
		{
			retries++;
			failed = true;
			return;
		}
		
		try
		{
			int cropX = decoded.cropX();
			int cropY = decoded.cropY();
			int width = Math.max(1, decoded.cropWidth());
			int height = Math.max(1, decoded.cropHeight());
			
			for(DecodedFrame frame : decoded.frames())
			{
				frames.add(
					GifDecoder.cropAbgr(frame, cropX, cropY, width, height));
				delays.add(Math.max(20, frame.delayMs()));
			}
			
			imageWidth = width;
			imageHeight = height;
			totalDurationMs = (int)Math.min(Integer.MAX_VALUE,
				Math.max(1L, decoded.totalDurationMs()));
			
			int number = NEXT_TEXTURE_ID.incrementAndGet();
			textureId = Identifier.fromNamespaceAndPath("wurst",
				"dynamic/patreon_gif_" + number);
			texture = new DynamicTexture(() -> "wurst_patreon_gif_" + number,
				width, height, false);
			
			// Upload the first frame right away so the GIF is visible even if
			// the remaining frames take a moment to paint.
			uploadFrame(0);
			minecraft.getTextureManager().register(textureId, texture);
			
			animationStartMs = System.currentTimeMillis();
			ready = true;
			
		}catch(RuntimeException e)
		{
			clearFrames();
			retries++;
			failed = true;
		}
	}
	
	/**
	 * Returns the texture to draw, updating its pixels first if the visible
	 * frame has changed. Must be called on Minecraft's client thread.
	 */
	public Frame getCurrentFrame()
	{
		if(!ready)
			return null;
		
		long elapsed =
			(System.currentTimeMillis() - animationStartMs) % totalDurationMs;
		int index = currentFrameIndex(elapsed);
		if(index != uploadedFrame)
			uploadFrame(index);
		
		return new Frame(textureId, imageWidth, imageHeight);
	}
	
	private void uploadFrame(int index)
	{
		if(index < 0 || index >= frames.size() || texture == null)
			return;
		
		NativeImage pixels = texture.getPixels();
		if(pixels == null)
			return;
		
		int[] source = frames.get(index);
		
		// NativeImage#getPixelsABGR returns a copy of the pixel data, so
		// writing into it would leave the texture fully transparent. The
		// pixels must be written back through setPixelABGR.
		for(int y = 0; y < imageHeight; y++)
		{
			int row = y * imageWidth;
			for(int x = 0; x < imageWidth; x++)
				pixels.setPixelABGR(x, y, source[row + x]);
		}
		
		texture.upload();
		uploadedFrame = index;
	}
	
	private int currentFrameIndex(long elapsed)
	{
		int cursor = 0;
		for(int i = 0; i < delays.size(); i++)
		{
			cursor += delays.get(i);
			if(elapsed < cursor)
				return i;
		}
		return Math.max(0, delays.size() - 1);
	}
	
	public boolean isReady()
	{
		return ready;
	}
	
	public boolean isLoading()
	{
		return loading;
	}
	
	public boolean hasFailed()
	{
		return failed;
	}
	
	public int getRetries()
	{
		return retries;
	}
	
	public int getImageWidth()
	{
		return imageWidth;
	}
	
	public int getImageHeight()
	{
		return imageHeight;
	}
	
	/**
	 * Releases the GPU texture. Safe to call more than once.
	 */
	public void close()
	{
		closed = true;
		clearFrames();
	}
	
	private void clearFrames()
	{
		ready = false;
		if(textureId != null)
		{
			Minecraft minecraft = Minecraft.getInstance();
			if(minecraft != null)
				minecraft.getTextureManager().release(textureId);
			else if(texture != null)
				texture.close();
		}
		
		texture = null;
		textureId = null;
		uploadedFrame = -1;
		frames.clear();
		delays.clear();
		imageWidth = 0;
		imageHeight = 0;
		totalDurationMs = 0;
	}
	
	public record Frame(Identifier id, int width, int height)
	{}
}
