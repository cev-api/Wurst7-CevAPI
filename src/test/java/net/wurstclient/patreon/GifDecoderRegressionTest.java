/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.wurstclient.patreon.gif.GifDecoder;
import net.wurstclient.patreon.gif.GifDecoder.DecodedFrame;
import net.wurstclient.patreon.gif.GifDecoder.DecodedGif;

/**
 * Decodes the bundled Neco GIFs and verifies frame/composition invariants.
 */
public final class GifDecoderRegressionTest
{
	public static void main(String[] args) throws Exception
	{
		Path assets = Path.of("src", "main", "resources", "assets", "wurst");
		check(Files.isDirectory(assets), "asset directory must exist");
		
		int animated = 0;
		int transparentFrames = 0;
		for(int i = 1; i <= 20; i++)
		{
			Path gif = assets.resolve("neco_gif_" + i + ".gif");
			check(Files.isRegularFile(gif), "missing " + gif);
			DecodedGif decoded = GifDecoder.decode(Files.readAllBytes(gif));
			validate(decoded, "neco_gif_" + i + ".gif");
			if(decoded.frames().size() > 1)
				animated++;
			transparentFrames += countTransparentFrames(decoded);
		}
		
		check(animated > 0, "at least one GIF must be animated");
		check(transparentFrames > 0,
			"at least one decoded frame must contain transparency");
		
		expectFailure(new byte[0], "empty GIF");
		expectFailure(new byte[]{1, 2, 3, 4, 5}, "garbage GIF");
		
		testCropAbgr();
		testFullyTransparentGuard();
		
		System.out.println("GIF decoder regression checks passed.");
	}
	
	private static void testCropAbgr()
	{
		// 4x3 frame, each pixel encodes its own coordinates as 0xFFRRGGBB.
		int width = 4;
		int height = 3;
		int[] abgr = new int[width * height];
		for(int y = 0; y < height; y++)
			for(int x = 0; x < width; x++)
				abgr[y * width + x] = 0xFF000000 | (x + 1) << 16 | (y + 1) << 8;
			
		DecodedFrame frame = new DecodedFrame(width, height, abgr, 50);
		int[] cropped = GifDecoder.cropAbgr(frame, 1, 1, 2, 2);
		
		check(cropped.length == 4, "crop must produce width*height pixels");
		check(cropped[0] == abgr[1 * width + 1],
			"crop must start at the requested offset");
		check(cropped[1] == abgr[1 * width + 2],
			"crop must advance along the row");
		check(cropped[2] == abgr[2 * width + 1],
			"crop must advance by canvas width, not crop width");
		check(cropped[3] == abgr[2 * width + 2],
			"crop must preserve the last pixel");
		
		expectIllegalCrop(frame, 3, 0, 2, 1, "crop past right edge");
		expectIllegalCrop(frame, 0, 2, 1, 2, "crop past bottom edge");
		expectIllegalCrop(frame, -1, 0, 1, 1, "negative crop origin");
		expectIllegalCrop(frame, 0, 0, 0, 1, "zero crop width");
	}
	
	private static void expectIllegalCrop(DecodedFrame frame, int x, int y,
		int width, int height, String label)
	{
		try
		{
			GifDecoder.cropAbgr(frame, x, y, width, height);
			throw new AssertionError(label + " must be rejected");
			
		}catch(IllegalArgumentException expected)
		{
			// expected
		}
	}
	
	private static void testFullyTransparentGuard()
	{
		DecodedFrame transparent =
			new DecodedFrame(1, 1, new int[]{0x00000000}, 50);
		DecodedFrame visible =
			new DecodedFrame(1, 1, new int[]{0xFF000000}, 50);
		check(
			GifDecoder.isFullyTransparent(new DecodedGif(
				java.util.List.of(transparent), 1, 1, 0, 0, 1, 1, 50)),
			"a fully transparent GIF must be detected");
		check(
			!GifDecoder.isFullyTransparent(
				new DecodedGif(java.util.List.of(transparent, visible), 1, 1, 0,
					0, 1, 1, 100)),
			"a GIF with any visible pixel must not be flagged");
	}
	
	private static void validate(DecodedGif decoded, String name)
	{
		check(!decoded.frames().isEmpty(), name + " must decode frames");
		check(decoded.canvasWidth() > 0 && decoded.canvasHeight() > 0,
			name + " must have a canvas");
		check(decoded.cropWidth() > 0 && decoded.cropHeight() > 0,
			name + " must have non-empty opaque bounds");
		check(decoded.cropX() >= 0 && decoded.cropY() >= 0,
			name + " must have valid crop offsets");
		check(decoded.cropX() + decoded.cropWidth() <= decoded.canvasWidth(),
			name + " crop must fit horizontally");
		check(decoded.cropY() + decoded.cropHeight() <= decoded.canvasHeight(),
			name + " crop must fit vertically");
		check(decoded.totalDurationMs() > 0, name + " must have a duration");
		
		for(DecodedFrame frame : decoded.frames())
		{
			check(frame.width() == decoded.canvasWidth(),
				name + " frame width must match canvas");
			check(frame.height() == decoded.canvasHeight(),
				name + " frame height must match canvas");
			check(frame.abgr().length == frame.width() * frame.height(),
				name + " frame pixel count must match dimensions");
			check(frame.delayMs() >= 20, name + " frame delay must be usable");
		}
	}
	
	private static int countTransparentFrames(DecodedGif decoded)
	{
		int count = 0;
		for(DecodedFrame frame : decoded.frames())
		{
			for(int pixel : frame.abgr())
				if((pixel >>> 24) == 0)
				{
					count++;
					break;
				}
		}
		return count;
	}
	
	private static void expectFailure(byte[] bytes, String label)
	{
		try
		{
			GifDecoder.decode(bytes);
			throw new AssertionError(label + " must be rejected");
			
		}catch(IOException expected)
		{
			// expected
		}
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
