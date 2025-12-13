/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.wurstclient.Category;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.util.RenderUtils;

public final class NecoCmd extends Command
	implements GUIRenderListener, UpdateListener
{
	private final ResourceLocation[] necos = buildNecoFrames();
	
	private static final int NECO_TEX_W = 200;
	private static final int NECO_TEX_H = 200;
	private static final int DRAW_W = 200;
	private static final int DRAW_H = 200;
	private static final int RIGHT_MARGIN = 10;
	private static final int ABOVE_HUNGER = 4;
	private static final int HUNGER_ROW_BASELINE = 39;
	
	private boolean enabled;
	private int ticks = 0;
	
	private static final int TICKS_PER_FRAME = 2;
	
	public NecoCmd()
	{
		super("neco", "Spawns a dancing Neco-Arc.\n");
		setCategory(Category.FUN);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length != 0)
			throw new CmdSyntaxError("Neco-arc doesn't want your arguments!");
		
		enabled = !enabled;
		
		if(enabled)
		{
			EVENTS.add(GUIRenderListener.class, this);
			EVENTS.add(UpdateListener.class, this);
		}else
		{
			EVENTS.remove(GUIRenderListener.class, this);
			EVENTS.remove(UpdateListener.class, this);
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
	public void onUpdate()
	{
		int cycle = 37 * TICKS_PER_FRAME;
		if(ticks >= cycle - 1)
			ticks = 0;
		else
			ticks++;
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
	{
		if(WURST.getHax().rainbowUiHack.isEnabled())
			RenderUtils.setShaderColor(WURST.getGui().getAcColor(), 1);
		else
			RenderSystem.setShaderColor(1, 1, 1, 1);
		
		int sw = context.guiWidth();
		int sh = context.guiHeight();
		int hungerBaselineY = sh - HUNGER_ROW_BASELINE;
		
		int x = sw - DRAW_W - RIGHT_MARGIN;
		int y = hungerBaselineY - DRAW_H - ABOVE_HUNGER;
		
		int frameIndex = (ticks / TICKS_PER_FRAME) % 37;
		
		context.blit(RenderPipelines.GUI_TEXTURED, necos[frameIndex], x, y, 0,
			0, DRAW_W, DRAW_H, NECO_TEX_W, NECO_TEX_H, color);
	}
	
	private static ResourceLocation[] buildNecoFrames()
	{
		ResourceLocation[] frames = new ResourceLocation[37];
		for(int i = 0; i < 37; i++)
			frames[i] = ResourceLocation.fromNamespaceAndPath("wurst",
				"neco" + (i + 1) + ".png");
		return frames;
	}
}
