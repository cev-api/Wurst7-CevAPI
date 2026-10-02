/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.List;
import java.util.Objects;
import org.joml.Matrix3x2f;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;

/** Keeps overlap order while avoiding repeated GUI overlap searches. */
public final class EspItemTagBatcher
{
	private record Tag(ColoredRectangleRenderState background,
		GuiItemRenderState icon, GuiTextRenderState text,
		ScreenRectangle bounds)
	{}
	
	private GuiGraphicsExtractor context;
	private ScreenRectangle lastScissor;
	private final NonOverlappingScreenBatch<Tag> batch =
		new NonOverlappingScreenBatch<>(4096, this::submit);
	
	public void begin(GuiGraphicsExtractor context)
	{
		batch.clear();
		this.context = context;
		lastScissor = null;
	}
	
	public void add(EspItemIconRenderer icons, Font font, ItemStack stack,
		String text, boolean showIcon, boolean reuseModels, boolean shadow,
		float x, float y, float width, float height, float scale)
	{
		int guiScale = WurstClient.MC.getWindow().getGuiScale();
		int x1 = (int)((x - 2) * guiScale);
		int y1 = (int)((y - 2) * guiScale);
		int x2 = (int)((x + width + 2) * guiScale);
		int y2 = (int)((y + height + 2) * guiScale);
		var scissor = context.scissorStack.peek();
		if(!Objects.equals(lastScissor, scissor))
			batch.flush();
		lastScissor = scissor;
		var background = new ColoredRectangleRenderState(RenderPipelines.GUI,
			TextureSetup.noTexture(),
			new Matrix3x2f(context.pose()).scale(1F / guiScale),
			Math.max(x1, x2), Math.max(y1, y2), Math.min(x1, x2),
			Math.min(y1, y2), 0x90000000, 0x90000000, scissor);
		GuiItemRenderState icon = null;
		GuiTextRenderState label = null;
		context.pose().pushMatrix();
		try
		{
			context.pose().translate(x + scale, y + scale);
			context.pose().scale(scale, scale);
			if(showIcon)
				icon = icons.prepare(context, stack, 0, 0, reuseModels);
			if(!text.isEmpty())
				label = new GuiTextRenderState(font,
					Language.getInstance()
						.getVisualOrder(FormattedText.of(text)),
					new Matrix3x2f(context.pose()), showIcon ? 19 : 0,
					showIcon ? 5 : 0, 0xFFFFFFFF, 0, shadow, false, scissor);
		}finally
		{
			context.pose().popMatrix();
		}
		ScreenRectangle area = union(background.bounds(),
			union(icon == null ? null : icon.bounds(),
				label == null ? null : label.bounds()));
		if(icon != null && icon.bounds() == null)
			icon = null;
		if(label != null && label.bounds() == null)
			label = null;
		// Leave a pixel around native bounds to keep edge coverage
		// conservative.
		if(area != null)
			area = new ScreenRectangle(area.left() - 1, area.top() - 1,
				area.width() + 2, area.height() + 2);
		batch.add(new Tag(background, icon, label, area), area);
	}
	
	static ScreenRectangle union(ScreenRectangle first, ScreenRectangle second)
	{
		if(first == null)
			return second;
		if(second == null)
			return first;
		int x = Math.min(first.left(), second.left());
		int y = Math.min(first.top(), second.top());
		return new ScreenRectangle(x, y,
			Math.max(first.right(), second.right()) - x,
			Math.max(first.bottom(), second.bottom()) - y);
	}
	
	private void submit(List<Tag> tags)
	{
		GuiRenderState state = context.guiRenderState;
		if(tags.size() == 1
			|| SharedConstants.DEBUG_RENDER_UI_LAYERING_RECTANGLES)
		{
			for(Tag tag : tags)
			{
				state.addGuiElement(tag.background);
				if(tag.icon != null)
					state.addItem(tag.icon);
				if(tag.text != null)
					state.addText(tag.text);
			}
			return;
		}
		ScreenRectangle area = null;
		for(Tag tag : tags)
			area = union(area, tag.bounds);
		state.addGuiElement(new GuiRectangleBatch(
			tags.stream().map(Tag::background).toList(), area));
		state.up();
		// The batch bounds placed us above all earlier overlapping HUD.
		// Its content is pairwise disjoint, so further layer searches cannot
		// change its order. Keep native atlas identity registration intact.
		for(Tag tag : tags)
			if(tag.icon != null)
			{
				state.itemModelIdentities
					.add(tag.icon.itemStackRenderState().getModelIdentity());
				state.current.addItem(tag.icon);
			}
		boolean separateText =
			tags.stream().anyMatch(tag -> tag.icon != null && tag.text != null
				&& tag.icon.bounds().intersects(tag.text.bounds()));
		if(separateText)
			state.up();
		for(Tag tag : tags)
			if(tag.text != null)
				state.current.addText(tag.text);
	}
	
	public void end()
	{
		try
		{
			batch.flush();
		}finally
		{
			context = null;
		}
	}
}
