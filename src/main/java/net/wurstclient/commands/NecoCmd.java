/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
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
	private final Identifier[] necos = buildNecoFrames();
	
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
	public void onRenderGUI(DrawContext context, float partialTicks)
	{
		int color = WURST.getHax().rainbowUiHack.isEnabled()
			? RenderUtils.toIntColor(WURST.getGui().getAcColor(), 1)
			: 0xFFFFFFFF;
		
		int sw = context.getScaledWindowWidth();
		int sh = context.getScaledWindowHeight();
		int hungerBaselineY = sh - HUNGER_ROW_BASELINE;
		
		int x = sw - DRAW_W - RIGHT_MARGIN;
		int y = hungerBaselineY - DRAW_H - ABOVE_HUNGER;
		
		int frameIndex = (ticks / TICKS_PER_FRAME) % 37;
		
		context.drawTexture(RenderPipelines.GUI_TEXTURED, necos[frameIndex], x,
			y, 0, 0, DRAW_W, DRAW_H, NECO_TEX_W, NECO_TEX_H, color);
	}
	
	private static Identifier[] buildNecoFrames()
	{
		Identifier[] frames = new Identifier[37];
		for(int i = 0; i < 37; i++)
			frames[i] = Identifier.of("wurst", "neco" + (i + 1) + ".png");
		return frames;
	}
}
