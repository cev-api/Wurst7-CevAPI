/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altgui;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import net.wurstclient.WurstClient;

public final class AltGuiFontManager
{
	private static final String MINECRAFT_FONT = "Minecraft";
	private static final AltGuiFontManager INSTANCE = new AltGuiFontManager();
	
	private final LinkedHashMap<String, Path> fontFiles = new LinkedHashMap<>();
	private final Path fontsFolder;
	
	private String activeName = MINECRAFT_FONT;
	private RasterFont activeFont;
	private int smoothingFactor = 2;
	
	private AltGuiFontManager()
	{
		fontsFolder = WurstClient.INSTANCE.getWurstFolder().resolve("fonts");
		reloadFonts();
	}
	
	public static AltGuiFontManager getInstance()
	{
		return INSTANCE;
	}
	
	public void reloadFonts()
	{
		fontFiles.clear();
		try
		{
			Files.createDirectories(fontsFolder);
			try(var stream = Files.list(fontsFolder))
			{
				stream.filter(Files::isRegularFile).forEach(path -> {
					String fileName = path.getFileName().toString();
					String lower = fileName.toLowerCase(Locale.ROOT);
					if(!lower.endsWith(".ttf") && !lower.endsWith(".otf"))
						return;
					fontFiles.put(fileName, path);
				});
			}
		}catch(IOException ignored)
		{}
		
		if(!MINECRAFT_FONT.equals(activeName)
			&& !fontFiles.containsKey(activeName))
			setActiveFont(MINECRAFT_FONT);
	}
	
	public List<String> getFontOptions()
	{
		ArrayList<String> options = new ArrayList<>();
		options.add(MINECRAFT_FONT);
		options.addAll(fontFiles.keySet());
		return options;
	}
	
	public void setActiveFont(String name)
	{
		if(name == null || name.isBlank() || MINECRAFT_FONT.equals(name))
		{
			activeName = MINECRAFT_FONT;
			activeFont = null;
			return;
		}
		
		if(name.equals(activeName))
			return;
		
		Path file = fontFiles.get(name);
		if(file == null)
		{
			activeName = MINECRAFT_FONT;
			activeFont = null;
			return;
		}
		
		RasterFont loaded = loadFont(file);
		if(loaded == null)
		{
			activeName = MINECRAFT_FONT;
			activeFont = null;
			return;
		}
		
		activeName = name;
		activeFont = loaded;
	}
	
	public String getActiveName()
	{
		return activeName;
	}
	
	public void setSmoothingFactor(int factor)
	{
		int clamped = Math.max(1, Math.min(3, factor));
		if(clamped == smoothingFactor)
			return;
		
		smoothingFactor = clamped;
		if(MINECRAFT_FONT.equals(activeName))
			return;
		
		Path file = fontFiles.get(activeName);
		if(file == null)
		{
			activeName = MINECRAFT_FONT;
			activeFont = null;
			return;
		}
		
		RasterFont loaded = loadFont(file);
		if(loaded == null)
		{
			activeName = MINECRAFT_FONT;
			activeFont = null;
			return;
		}
		
		activeFont = loaded;
	}
	
	public Path getFontsFolder()
	{
		return fontsFolder;
	}
	
	public void openFontsFolder()
	{
		try
		{
			Files.createDirectories(fontsFolder);
			Util.getPlatform().openFile(fontsFolder.toFile());
		}catch(IOException ignored)
		{}
	}
	
	public int getTextWidth(net.minecraft.client.gui.Font fallback, String text)
	{
		if(text == null || text.isEmpty())
			return 0;
		if(activeFont == null)
			return fallback.width(text);
		return activeFont.width(text);
	}
	
	public int getTextWidthPrefix(net.minecraft.client.gui.Font fallback,
		String text, int length)
	{
		if(text == null || text.isEmpty() || length <= 0)
			return 0;
		int safeLength = Math.min(length, text.length());
		if(activeFont == null)
			return fallback.width(text.substring(0, safeLength));
		return activeFont.widthPrefix(text, safeLength);
	}
	
	public int getLineHeight(net.minecraft.client.gui.Font fallback)
	{
		if(activeFont == null)
			return fallback.lineHeight;
		return activeFont.lineHeight();
	}
	
	public void drawString(GuiGraphicsExtractor context,
		net.minecraft.client.gui.Font fallback, String text, int x, int y,
		int color, boolean shadow, float scale)
	{
		if(text == null || text.isEmpty())
			return;
		
		if(activeFont == null)
		{
			if(Math.abs(scale - 1F) < 0.001F)
			{
				context.text(fallback, text, x, y, color, shadow);
			}else
			{
				context.pose().pushMatrix();
				context.pose().scale(scale, scale);
				context.text(fallback, text, Math.round(x / scale),
					Math.round(y / scale), color, shadow);
				context.pose().popMatrix();
			}
			return;
		}
		
		activeFont.draw(context, text, x, y, color, shadow, scale);
	}
	
	private RasterFont loadFont(Path file)
	{
		try(InputStream in = Files.newInputStream(file))
		{
			Font base = Font.createFont(Font.TRUETYPE_FONT, in);
			return new RasterFont(base.deriveFont(14F), smoothingFactor);
		}catch(FontFormatException | IOException e)
		{
			return null;
		}
	}
	
	private static final class RasterFont
	{
		private static final int MAX_WIDTH_CACHE = 4096;
		
		private final LinkedHashMap<Character, Glyph> glyphs =
			new LinkedHashMap<>();
		private final Glyph[] asciiGlyphs = new Glyph[128];
		private final LinkedHashMap<String, Integer> widthCache =
			new LinkedHashMap<>(1024, 0.75F, true)
			{
				@Override
				protected boolean removeEldestEntry(
					java.util.Map.Entry<String, Integer> eldest)
				{
					return size() > MAX_WIDTH_CACHE;
				}
			};
		private final int lineHeight;
		private final int oversample;
		private final int alphaStep;
		private final int alphaCutoff;
		
		private RasterFont(Font font, int oversample)
		{
			this.oversample = Math.max(1, oversample);
			if(this.oversample <= 1)
			{
				alphaStep = 255; // binary alpha for maximum speed
				alphaCutoff = 96;
			}else if(this.oversample == 2)
			{
				alphaStep = 64; // 4 alpha levels
				alphaCutoff = 12;
			}else
			{
				alphaStep = 48; // 6 alpha levels
				alphaCutoff = 8;
			}
			Font rasterFont =
				font.deriveFont(font.getSize2D() * this.oversample);
			BufferedImage tmp =
				new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = tmp.createGraphics();
			applyQualityHints(g);
			g.setFont(rasterFont);
			int ascent = g.getFontMetrics().getAscent();
			lineHeight = Math.max(9, Math.round(
				g.getFontMetrics().getHeight() / (float)this.oversample));
			g.dispose();
			
			for(char c = 32; c <= 126; c++)
				glyphs.put(c, buildGlyph(rasterFont, c, ascent, lineHeight));
			glyphs.put('★', buildGlyph(rasterFont, '★', ascent, lineHeight));
			glyphs.put('?', buildGlyph(rasterFont, '?', ascent, lineHeight));
			
			for(int i = 0; i < asciiGlyphs.length; i++)
				asciiGlyphs[i] = glyphs.getOrDefault((char)i, glyphs.get('?'));
		}
		
		private Glyph buildGlyph(Font font, char c, int ascent, int height)
		{
			int osHeight = Math.max(2, height * oversample);
			BufferedImage img =
				new BufferedImage(Math.max(6 * oversample, osHeight),
					osHeight + 2 * oversample, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			applyQualityHints(g);
			g.setFont(font);
			var fm = g.getFontMetrics();
			int osAdvance =
				Math.max(3 * oversample, fm.charWidth(c) + oversample);
			int w = Math.max(osAdvance + 2 * oversample, 4 * oversample);
			
			if(img.getWidth() < w)
			{
				g.dispose();
				img = new BufferedImage(w, osHeight + 2 * oversample,
					BufferedImage.TYPE_INT_ARGB);
				g = img.createGraphics();
				applyQualityHints(g);
				g.setFont(font);
			}
			
			g.setColor(java.awt.Color.WHITE);
			g.drawString(String.valueOf(c), oversample, ascent);
			g.dispose();
			
			int[][] alphaGrid = downsampleAlpha(img, oversample);
			ArrayList<Run> runs = new ArrayList<>();
			HashMap<RunKey, MutableRun> activeRuns = new HashMap<>();
			for(int y = 0; y < alphaGrid.length; y++)
			{
				HashMap<RunKey, MutableRun> nextRuns = new HashMap<>();
				int x = 0;
				while(x < alphaGrid[y].length)
				{
					int alpha = quantizeAlpha(alphaGrid[y][x]);
					if(alpha < alphaCutoff)
					{
						x++;
						continue;
					}
					
					int exactAlpha = alpha;
					int x1 = x;
					x++;
					while(x < alphaGrid[y].length)
					{
						int a = quantizeAlpha(alphaGrid[y][x]);
						if(a != exactAlpha || a < alphaCutoff)
							break;
						x++;
					}
					
					RunKey key = new RunKey(x1, x, exactAlpha);
					MutableRun continued = activeRuns.remove(key);
					if(continued != null)
					{
						continued.extendTo(y + 1);
						nextRuns.put(key, continued);
					}else
						nextRuns.put(key,
							new MutableRun(y, y + 1, x1, x, exactAlpha));
				}
				
				for(MutableRun stale : activeRuns.values())
					runs.add(stale.toRun());
				activeRuns = nextRuns;
			}
			
			for(MutableRun leftover : activeRuns.values())
				runs.add(leftover.toRun());
			
			int advance =
				Math.max(3, Math.round(osAdvance / (float)oversample));
			return new Glyph(advance, runs);
		}
		
		private static int[][] downsampleAlpha(BufferedImage img, int factor)
		{
			int outW = Math.max(1, (img.getWidth() + factor - 1) / factor);
			int outH = Math.max(1, (img.getHeight() + factor - 1) / factor);
			int[][] out = new int[outH][outW];
			
			for(int oy = 0; oy < outH; oy++)
				for(int ox = 0; ox < outW; ox++)
				{
					int sum = 0;
					int count = 0;
					int startY = oy * factor;
					int startX = ox * factor;
					for(int sy = 0; sy < factor; sy++)
						for(int sx = 0; sx < factor; sx++)
						{
							int px = startX + sx;
							int py = startY + sy;
							if(px >= img.getWidth() || py >= img.getHeight())
								continue;
							sum += img.getRGB(px, py) >>> 24;
							count++;
						}
					out[oy][ox] = count == 0 ? 0 : sum / count;
				}
			
			return out;
		}
		
		private static void applyQualityHints(Graphics2D g)
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
				RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING,
				RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
				RenderingHints.VALUE_STROKE_PURE);
		}
		
		private int quantizeAlpha(int alpha)
		{
			if(alphaStep >= 255)
				return alpha >= alphaCutoff ? 255 : 0;
			return Math.min(255,
				((alpha + alphaStep / 2) / alphaStep) * alphaStep);
		}
		
		private int width(String text)
		{
			Integer cached = widthCache.get(text);
			if(cached != null)
				return cached;
			
			int w = 0;
			for(int i = 0; i < text.length(); i++)
			{
				char c = text.charAt(i);
				Glyph glyph = c < asciiGlyphs.length ? asciiGlyphs[c]
					: glyphs.getOrDefault(c, glyphs.get('?'));
				w += glyph == null ? lineHeight / 2 : glyph.advance();
			}
			
			widthCache.put(text, w);
			return w;
		}
		
		private int widthPrefix(String text, int length)
		{
			int safeLength = Math.min(length, text.length());
			int w = 0;
			for(int i = 0; i < safeLength; i++)
			{
				char c = text.charAt(i);
				Glyph glyph = c < asciiGlyphs.length ? asciiGlyphs[c]
					: glyphs.getOrDefault(c, glyphs.get('?'));
				w += glyph == null ? lineHeight / 2 : glyph.advance();
			}
			return w;
		}
		
		private int lineHeight()
		{
			return lineHeight;
		}
		
		private void draw(GuiGraphicsExtractor context, String text, int x,
			int y, int color, boolean shadow, float scale)
		{
			if(shadow)
			{
				int shadowColor = (color & 0xFF000000)
					| (((color >> 16) & 0xFF) / 4 << 16)
					| (((color >> 8) & 0xFF) / 4 << 8) | ((color & 0xFF) / 4);
				drawInternal(context, text, x + 1, y + 1, shadowColor, scale);
			}
			
			drawInternal(context, text, x, y, color, scale);
		}
		
		private void drawInternal(GuiGraphicsExtractor context, String text,
			int x, int y, int color, float scale)
		{
			boolean scaled = Math.abs(scale - 1F) >= 0.001F;
			if(scaled)
			{
				context.pose().pushMatrix();
				context.pose().scale(scale, scale);
			}
			int dx = scaled ? Math.round(x / scale) : x;
			int dy = scaled ? Math.round(y / scale) : y;
			int baseAlpha = (color >>> 24) & 0xFF;
			
			for(int i = 0; i < text.length(); i++)
			{
				char c = text.charAt(i);
				Glyph glyph = c < asciiGlyphs.length ? asciiGlyphs[c]
					: glyphs.getOrDefault(c, glyphs.get('?'));
				if(glyph == null)
				{
					dx += lineHeight / 2;
					continue;
				}
				
				for(Run run : glyph.runs())
				{
					int a = baseAlpha * run.alpha() / 255;
					if(a <= 0)
						continue;
					int argb = (color & 0x00FFFFFF) | (a << 24);
					context.fill(dx + run.x1(), dy + run.y1(), dx + run.x2(),
						dy + run.y2(), argb);
				}
				
				dx += glyph.advance();
			}
			
			if(scaled)
				context.pose().popMatrix();
		}
		
		private record RunKey(int x1, int x2, int alpha)
		{}
		
		private static final class MutableRun
		{
			private final int y1;
			private int y2;
			private final int x1;
			private final int x2;
			private final int alpha;
			
			private MutableRun(int y1, int y2, int x1, int x2, int alpha)
			{
				this.y1 = y1;
				this.y2 = y2;
				this.x1 = x1;
				this.x2 = x2;
				this.alpha = alpha;
			}
			
			private void extendTo(int newY2)
			{
				y2 = Math.max(y2, newY2);
			}
			
			private Run toRun()
			{
				return new Run(y1, y2, x1, x2, alpha);
			}
		}
	}
	
	private record Run(int y1, int y2, int x1, int x2, int alpha)
	{}
	
	private record Glyph(int advance, List<Run> runs)
	{}
}
