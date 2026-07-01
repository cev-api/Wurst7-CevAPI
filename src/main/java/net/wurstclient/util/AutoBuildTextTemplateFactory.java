/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.wurstclient.command.CmdSyntaxError;

public final class AutoBuildTextTemplateFactory
{
	private static final int FONT_WIDTH = 5;
	private static final int FONT_HEIGHT = 7;
	private static final Map<Character, String[]> FONT = createFont();
	private static final Set<Character> DIAGONAL_CHARS = Set.of('K', 'M', 'N',
		'Q', 'R', 'S', 'V', 'W', 'X', 'Y', 'Z', '2', '4', '7');
	
	private AutoBuildTextTemplateFactory()
	{}
	
	public static AutoBuildTemplate create(String text, int targetHeight)
		throws CmdSyntaxError
	{
		if(text == null || text.isBlank())
			throw new CmdSyntaxError("Text must not be empty.");
		
		if(targetHeight < 1)
			throw new CmdSyntaxError("Height must be at least 1.");
		
		String normalized = text.toUpperCase(Locale.ROOT);
		int scaledWidth = Math.max(3,
			(int)Math.round(targetHeight * (FONT_WIDTH / (double)FONT_HEIGHT)));
		int strokeThickness = getStrokeThickness(targetHeight);
		int letterSpacing = Math.max(1, strokeThickness);
		LinkedHashSet<int[]> blocks = new LinkedHashSet<>();
		int cursorX = 0;
		
		for(int i = 0; i < normalized.length(); i++)
		{
			char c = normalized.charAt(i);
			String[] glyph = FONT.get(c);
			if(glyph == null)
				throw new CmdSyntaxError("Unsupported character: " + c);
			
			addGlyph(blocks, glyph, cursorX, scaledWidth, targetHeight,
				strokeThickness, DIAGONAL_CHARS.contains(c));
			cursorX += scaledWidth + letterSpacing;
		}
		
		return AutoBuildTemplate
			.createGenerated("TEXT_" + normalized.replace(' ', '_'), blocks);
	}
	
	private static void addGlyph(LinkedHashSet<int[]> blocks, String[] glyph,
		int offsetX, int scaledWidth, int scaledHeight, int strokeThickness,
		boolean allowDiagonals)
	{
		int[][] centersX = new int[FONT_HEIGHT][FONT_WIDTH];
		int[][] centersY = new int[FONT_HEIGHT][FONT_WIDTH];
		
		for(int row = 0; row < FONT_HEIGHT; row++)
		{
			for(int col = 0; col < FONT_WIDTH; col++)
			{
				centersX[row][col] =
					offsetX + scale(col, FONT_WIDTH, scaledWidth);
				centersY[row][col] =
					scale(FONT_HEIGHT - 1 - row, FONT_HEIGHT, scaledHeight);
			}
		}
		
		for(int row = 0; row < FONT_HEIGHT; row++)
		{
			for(int col = 0; col < FONT_WIDTH; col++)
			{
				if(glyph[row].charAt(col) != '1')
					continue;
				
				int x = centersX[row][col];
				int y = centersY[row][col];
				drawPoint(blocks, x, y, strokeThickness);
				
				if(col + 1 < FONT_WIDTH && glyph[row].charAt(col + 1) == '1')
					drawLine(blocks, x, y, centersX[row][col + 1],
						centersY[row][col + 1], strokeThickness);
				
				if(row + 1 < FONT_HEIGHT && glyph[row + 1].charAt(col) == '1')
					drawLine(blocks, x, y, centersX[row + 1][col],
						centersY[row + 1][col], strokeThickness);
				
				if(!allowDiagonals || row + 1 >= FONT_HEIGHT)
					continue;
				
				for(int nextCol : new int[]{col - 1, col + 1})
				{
					if(nextCol < 0 || nextCol >= FONT_WIDTH
						|| glyph[row + 1].charAt(nextCol) != '1')
						continue;
					
					drawLine(blocks, x, y, centersX[row + 1][nextCol],
						centersY[row + 1][nextCol], strokeThickness);
				}
			}
		}
	}
	
	private static int scale(int value, int sourceSize, int targetSize)
	{
		if(sourceSize <= 1 || targetSize <= 1)
			return 0;
		
		return (int)Math.round(value * (targetSize - 1D) / (sourceSize - 1D));
	}
	
	private static int getStrokeThickness(int targetHeight)
	{
		if(targetHeight >= 24)
			return 3;
		
		if(targetHeight >= 10)
			return 2;
		
		return 1;
	}
	
	private static void drawLine(LinkedHashSet<int[]> blocks, int x1, int y1,
		int x2, int y2, int strokeThickness)
	{
		int dx = Math.abs(x2 - x1);
		int dy = Math.abs(y2 - y1);
		int sx = x1 < x2 ? 1 : -1;
		int sy = y1 < y2 ? 1 : -1;
		int err = dx - dy;
		int x = x1;
		int y = y1;
		
		while(true)
		{
			drawPoint(blocks, x, y, strokeThickness);
			if(x == x2 && y == y2)
				return;
			
			int e2 = err * 2;
			if(e2 > -dy)
			{
				err -= dy;
				x += sx;
			}
			
			if(e2 < dx)
			{
				err += dx;
				y += sy;
			}
		}
	}
	
	private static void drawPoint(LinkedHashSet<int[]> blocks, int x, int y,
		int strokeThickness)
	{
		int start = -(strokeThickness / 2);
		int end = start + strokeThickness - 1;
		
		for(int dx = start; dx <= end; dx++)
			for(int dy = start; dy <= end; dy++)
				blocks.add(new int[]{x + dx, y + dy, 0});
	}
	
	private static Map<Character, String[]> createFont()
	{
		LinkedHashMap<Character, String[]> font = new LinkedHashMap<>();
		font.put(' ', glyph("00000", "00000", "00000", "00000", "00000",
			"00000", "00000"));
		font.put('A', glyph("01110", "10001", "10001", "11111", "10001",
			"10001", "10001"));
		font.put('B', glyph("11111", "10001", "10001", "11111", "10001",
			"10001", "11111"));
		font.put('C', glyph("01111", "10000", "10000", "10000", "10000",
			"10000", "01111"));
		font.put('D', glyph("11110", "10001", "10001", "10001", "10001",
			"10001", "11110"));
		font.put('E', glyph("11111", "10000", "10000", "11110", "10000",
			"10000", "11111"));
		font.put('F', glyph("11111", "10000", "10000", "11110", "10000",
			"10000", "10000"));
		font.put('G', glyph("01111", "10000", "10000", "10111", "10001",
			"10001", "01111"));
		font.put('H', glyph("10001", "10001", "10001", "11111", "10001",
			"10001", "10001"));
		font.put('I', glyph("11111", "00100", "00100", "00100", "00100",
			"00100", "11111"));
		font.put('J', glyph("00111", "00010", "00010", "00010", "00010",
			"10010", "01100"));
		font.put('K', glyph("10001", "10010", "10100", "11000", "10100",
			"10010", "10001"));
		font.put('L', glyph("10000", "10000", "10000", "10000", "10000",
			"10000", "11111"));
		font.put('M', glyph("10001", "11011", "10101", "10101", "10001",
			"10001", "10001"));
		font.put('N', glyph("10001", "10001", "11001", "10101", "10011",
			"10001", "10001"));
		font.put('O', glyph("01110", "10001", "10001", "10001", "10001",
			"10001", "01110"));
		font.put('P', glyph("11110", "10001", "10001", "11110", "10000",
			"10000", "10000"));
		font.put('Q', glyph("01110", "10001", "10001", "10001", "10101",
			"10010", "01101"));
		font.put('R', glyph("11110", "10001", "10001", "11110", "10100",
			"10010", "10001"));
		font.put('S', glyph("01111", "10000", "10000", "01110", "00001",
			"00001", "11110"));
		font.put('T', glyph("11111", "00100", "00100", "00100", "00100",
			"00100", "00100"));
		font.put('U', glyph("10001", "10001", "10001", "10001", "10001",
			"10001", "01110"));
		font.put('V', glyph("10001", "10001", "10001", "10001", "10001",
			"01010", "00100"));
		font.put('W', glyph("10001", "10001", "10001", "10101", "10101",
			"10101", "01010"));
		font.put('X', glyph("10001", "10001", "01010", "00100", "01010",
			"10001", "10001"));
		font.put('Y', glyph("10001", "10001", "01010", "00100", "00100",
			"00100", "00100"));
		font.put('Z', glyph("11111", "00001", "00010", "00100", "01000",
			"10000", "11111"));
		font.put('0', glyph("01110", "10001", "10011", "10101", "11001",
			"10001", "01110"));
		font.put('1', glyph("00100", "01100", "00100", "00100", "00100",
			"00100", "01110"));
		font.put('2', glyph("01110", "10001", "00001", "00010", "00100",
			"01000", "11111"));
		font.put('3', glyph("11110", "00001", "00001", "01110", "00001",
			"00001", "11110"));
		font.put('4', glyph("00010", "00110", "01010", "10010", "11111",
			"00010", "00010"));
		font.put('5', glyph("11111", "10000", "10000", "11110", "00001",
			"00001", "11110"));
		font.put('6', glyph("01110", "10000", "10000", "11110", "10001",
			"10001", "01110"));
		font.put('7', glyph("11111", "00001", "00010", "00100", "01000",
			"01000", "01000"));
		font.put('8', glyph("01110", "10001", "10001", "01110", "10001",
			"10001", "01110"));
		font.put('9', glyph("01110", "10001", "10001", "01111", "00001",
			"00001", "01110"));
		return font;
	}
	
	private static String[] glyph(String... rows)
	{
		return rows;
	}
}
