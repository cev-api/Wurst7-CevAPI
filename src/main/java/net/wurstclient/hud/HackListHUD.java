/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.wurstclient.ui.UiScale;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_features.HackListOtf;
import net.wurstclient.other_features.HackListOtf.Mode;
import net.wurstclient.other_features.HackListOtf.Position;
import net.wurstclient.util.RenderUtils;

public final class HackListHUD implements UpdateListener
{
	private final ArrayList<HackListEntry> activeHax = new ArrayList<>();
	private final HackListOtf otf = WurstClient.INSTANCE.getOtfs().hackListOtf;
	private int posY;
	private int textColor;
	private boolean usePerHackColors;
	
	public HackListHUD()
	{
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class, this);
	}
	
	public void render(DrawContext context, float partialTicks)
	{
		if(otf.getMode() == Mode.HIDDEN)
			return;
		
		boolean isBottom = (otf.getPosition() == Position.BOTTOM_LEFT
			|| otf.getPosition() == Position.BOTTOM_RIGHT);
		// Factor both global UI scale and hacklist font size multiplier
		int lineHeight = (int)Math.round(9 * getScale() * otf.getFontSize());
		int spacing = otf.getEntrySpacing();
		int count = activeHax.size();
		int height = count == 0 ? 0
			: count * lineHeight + Math.max(0, count - 1) * spacing;
		
		int baseY = isBottom ? context.getScaledWindowHeight() - height - 2 : 2;
		// Avoid overlapping with Wurst Logo in top-left
		boolean isLeft = (otf.getPosition() == Position.TOP_LEFT
			|| otf.getPosition() == Position.BOTTOM_LEFT);
		if(isLeft && WurstClient.INSTANCE.getOtfs().wurstLogoOtf.isVisible())
			posY = Math.max(baseY, 22);
		else
			posY = baseY;
		
		// color
		boolean rainbowUi =
			WurstClient.INSTANCE.getHax().rainbowUiHack.isEnabled();
		if(rainbowUi)
		{
			float[] acColor = WurstClient.INSTANCE.getGui().getAcColor();
			textColor = 0x04 << 24 | (int)(acColor[0] * 0xFF) << 16
				| (int)(acColor[1] * 0xFF) << 8 | (int)(acColor[2] * 0xFF);
			
		}else
			textColor = otf.getColor(0x04);
		// Enable per-hack colors only when toggle is on and RainbowUI is off
		usePerHackColors = otf.useHackColors() && !rainbowUi;
		
		int totalHeight = height + posY;
		if(otf.getMode() == Mode.COUNT
			|| totalHeight > context.getScaledWindowHeight())
			drawCounter(context);
		else
			drawHackList(context, partialTicks, lineHeight, spacing);
	}
	
	private void drawCounter(DrawContext context)
	{
		long size = activeHax.stream().filter(e -> e.hack.isEnabled()).count();
		String s = size + " hack" + (size != 1 ? "s" : "") + " active";
		drawString(context, s,
			/* lineHeight */(int)Math.round(9 * getScale() * otf.getFontSize()),
			/* spacing */0);
	}
	
	private void drawHackList(DrawContext context, float partialTicks,
		int lineHeight, int spacing)
	{
		if(otf.isAnimations())
			for(HackListEntry e : activeHax)
				drawWithOffset(context, e, partialTicks, lineHeight, spacing);
		else
			for(HackListEntry e : activeHax)
				drawString(context, e.hack, e.hack.getRenderName(), lineHeight,
					spacing);
	}
	
	public void updateState(Hack hack)
	{
		int offset = otf.isAnimations() ? 4 : 0;
		HackListEntry entry = new HackListEntry(hack, offset);
		
		if(hack.isEnabled())
		{
			if(activeHax.contains(entry))
				return;
			
			activeHax.add(entry);
			sort();
			
		}else if(!otf.isAnimations())
			activeHax.remove(entry);
	}
	
	private void sort()
	{
		Comparator<HackListEntry> comparator =
			Comparator.comparing(hle -> hle.hack, otf.getComparator());
		Collections.sort(activeHax, comparator);
	}
	
	@Override
	public void onUpdate()
	{
		if(otf.shouldSort())
			sort();
		
		if(!otf.isAnimations())
			return;
		
		for(Iterator<HackListEntry> itr = activeHax.iterator(); itr.hasNext();)
		{
			HackListEntry e = itr.next();
			boolean enabled = e.hack.isEnabled();
			e.prevOffset = e.offset;
			
			if(enabled && e.offset > 0)
				e.offset--;
			else if(!enabled && e.offset < 4)
				e.offset++;
			else if(!enabled && e.offset >= 4)
				itr.remove();
		}
	}
	
	private void drawString(DrawContext context, String s, int lineHeight,
		int spacing)
	{
		TextRenderer tr = WurstClient.MC.textRenderer;
		int posX;
		int yDraw = posY + otf.getYOffset();
		double scale = getScale() * otf.getFontSize();
		// scaled string width
		int stringWidth = (int)(tr.getWidth(s) * scale);
		boolean isLeft = (otf.getPosition() == Position.TOP_LEFT
			|| otf.getPosition() == Position.BOTTOM_LEFT);
		if(isLeft)
			posX = 2 + otf.getXOffset();
		else
		{
			int screenWidth = context.getScaledWindowWidth();
			posX = screenWidth - stringWidth - 2 + otf.getXOffset();
		}
		int alpha = (int)(otf.getTransparency() * 255) << 24;
		// Quantize to scaled-space integer coordinates to avoid half-pixel
		// blurring
		// Compute base pre-scale coords once and derive shadow/main from them
		double eps = 1e-6;
		int baseSX = (int)Math.round(posX / scale + eps);
		int baseSY = (int)Math.round(yDraw / scale + eps);
		int mainX = (int)Math.round(baseSX * scale);
		int mainY = (int)Math.round(baseSY * scale);
		int shadowX = (int)Math.round((baseSX + 1) * scale); // +1 pre-scale =
																// 1px on screen
		int shadowY = (int)Math.round((baseSY + 1) * scale); // consistent 1px
																// vertical
																// offset
		int lineColor = textColor;
		// No hack reference available in this overload; use default textColor
		if(WurstClient.INSTANCE.getOtfs().hackListOtf.useShadowBox())
		{
			int pad = (int)Math.max(1, Math.round(2 * scale));
			int boxX1 = mainX - pad;
			int boxY1 = mainY; // align to line top to avoid overlap
			int boxX2 = mainX + stringWidth + pad;
			int boxY2 = mainY + lineHeight; // align to line bottom
			int fillAlpha = (int)(WurstClient.INSTANCE.getOtfs().hackListOtf
				.getShadowBoxAlpha() * otf.getTransparency() * 255);
			int boxColor = (fillAlpha << 24);
			context.fill(boxX1, boxY1, boxX2, boxY2, boxColor);
		}else
		{
			RenderUtils.drawScaledText(context, tr, s, shadowX, shadowY,
				0x04000000 | alpha, false, scale);
		}
		context.state.goUpLayer();
		RenderUtils.drawScaledText(context, tr, s, mainX, mainY,
			(lineColor | alpha), false, scale);
		
		posY += lineHeight + spacing;
	}
	
	private void drawString(DrawContext context, Hack hack, String s,
		int lineHeight, int spacing)
	{
		TextRenderer tr = WurstClient.MC.textRenderer;
		int posX;
		int yDraw = posY + otf.getYOffset();
		double scale = getScale() * otf.getFontSize();
		// scaled string width
		int stringWidth = (int)(tr.getWidth(s) * scale);
		boolean isLeft = (otf.getPosition() == Position.TOP_LEFT
			|| otf.getPosition() == Position.BOTTOM_LEFT);
		if(isLeft)
			posX = 2 + otf.getXOffset();
		else
		{
			int screenWidth = context.getScaledWindowWidth();
			posX = screenWidth - stringWidth - 2 + otf.getXOffset();
		}
		int alpha = (int)(otf.getTransparency() * 255) << 24;
		// Quantize to scaled-space integer coordinates to avoid half-pixel
		// blurring
		// Compute base pre-scale coords once and derive shadow/main from them
		double eps = 1e-6;
		int baseSX = (int)Math.round(posX / scale + eps);
		int baseSY = (int)Math.round(yDraw / scale + eps);
		int mainX = (int)Math.round(baseSX * scale);
		int mainY = (int)Math.round(baseSY * scale);
		int shadowX = (int)Math.round((baseSX + 1) * scale); // +1 pre-scale =
																// 1px on screen
		int shadowY = (int)Math.round((baseSY + 1) * scale); // consistent 1px
																// vertical
																// offset
		int lineColor = textColor;
		if(usePerHackColors)
		{
			int c = hack.getHackListColorI(0x04);
			if(c != -1)
				lineColor = c;
		}
		if(WurstClient.INSTANCE.getOtfs().hackListOtf.useShadowBox())
		{
			int pad = (int)Math.max(1, Math.round(2 * scale));
			int boxX1 = mainX - pad;
			int boxY1 = mainY; // align to line top to avoid overlap
			int boxX2 = mainX + stringWidth + pad;
			int boxY2 = mainY + lineHeight; // align to line bottom
			int fillAlpha = (int)(WurstClient.INSTANCE.getOtfs().hackListOtf
				.getShadowBoxAlpha() * otf.getTransparency() * 255);
			int boxColor = (fillAlpha << 24);
			context.fill(boxX1, boxY1, boxX2, boxY2, boxColor);
		}else
		{
			RenderUtils.drawScaledText(context, tr, s, shadowX, shadowY,
				0x04000000 | alpha, false, scale);
		}
		context.state.goUpLayer();
		RenderUtils.drawScaledText(context, tr, s, mainX, mainY,
			(lineColor | alpha), false, scale);
		
		posY += lineHeight + spacing;
	}
	
	private void drawWithOffset(DrawContext context, HackListEntry e,
		float partialTicks, int lineHeight, int spacing)
	{
		TextRenderer tr = WurstClient.MC.textRenderer;
		String s = e.hack.getRenderName();
		
		float offset =
			e.offset * partialTicks + e.prevOffset * (1 - partialTicks);
		
		double scale = getScale() * otf.getFontSize();
		int stringWidth = (int)(tr.getWidth(s) * scale);
		
		float posX;
		boolean isLeft = (otf.getPosition() == Position.TOP_LEFT
			|| otf.getPosition() == Position.BOTTOM_LEFT);
		if(isLeft)
			posX = 2 - (int)(5 * offset * scale) + otf.getXOffset();
		else
		{
			int screenWidth = context.getScaledWindowWidth();
			posX = screenWidth - stringWidth - 2 + (int)(5 * offset * scale)
				+ otf.getXOffset();
		}
		
		int yDraw = posY + otf.getYOffset();
		
		int alpha = (int)(255 * (1 - offset / 4) * otf.getTransparency()) << 24;
		// Quantize positions for consistent pixel alignment
		double eps2 = 1e-6;
		int baseSX2 = (int)Math.round(posX / scale + eps2);
		int baseSY2 = (int)Math.round(yDraw / scale + eps2);
		int mainX2 = (int)Math.round(baseSX2 * scale);
		int mainY2 = (int)Math.round(baseSY2 * scale);
		int shadowX2 = (int)Math.round((baseSX2 + 1) * scale);
		int shadowY2 = (int)Math.round((baseSY2 + 1) * scale);
		int lineColor = textColor;
		if(usePerHackColors)
		{
			int c = e.hack.getHackListColorI(0x04);
			if(c != -1)
				lineColor = c;
		}
		if(WurstClient.INSTANCE.getOtfs().hackListOtf.useShadowBox())
		{
			int pad2 = (int)Math.max(1, Math.round(2 * scale));
			int boxX12 = mainX2 - pad2;
			int boxY12 = mainY2; // align to line top
			int boxX22 = mainX2 + stringWidth + pad2;
			int boxY22 = mainY2 + lineHeight; // align to line bottom
			int fillAlpha2 = (int)(WurstClient.INSTANCE.getOtfs().hackListOtf
				.getShadowBoxAlpha() * otf.getTransparency() * 255);
			int boxColor2 = (fillAlpha2 << 24);
			context.fill(boxX12, boxY12, boxX22, boxY22, boxColor2);
		}else
		{
			RenderUtils.drawScaledText(context, tr, s, shadowX2, shadowY2,
				0x04000000 | alpha, false, scale);
		}
		context.state.goUpLayer();
		RenderUtils.drawScaledText(context, tr, s, mainX2, mainY2,
			(lineColor | alpha), false, scale);
		
		posY += lineHeight + spacing;
	}
	
	private double getScale()
	{
		return UiScale.OVERRIDE_SCALE != 1.0 ? UiScale.OVERRIDE_SCALE
			: UiScale.getScale();
	}
	
	private static final class HackListEntry
	{
		private final Hack hack;
		private int offset;
		private int prevOffset;
		
		public HackListEntry(Hack mod, int offset)
		{
			hack = mod;
			this.offset = offset;
			prevOffset = offset;
		}
		
		@Override
		public boolean equals(Object obj)
		{
			if(!(obj instanceof HackListEntry other))
				return false;
			
			return hack == other.hack;
		}
		
		@Override
		public int hashCode()
		{
			return hack.hashCode();
		}
	}
}
