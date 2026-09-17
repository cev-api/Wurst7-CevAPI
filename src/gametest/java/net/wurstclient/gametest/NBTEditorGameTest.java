/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.network.chat.Component;
import net.wurstclient.clickgui.screens.NBTSyntaxEditor;

/**
 * Exercises the real widget, font, render clipping, and text input together.
 */
public final class NBTEditorGameTest implements FabricClientGameTest
{
	@Override
	public void runTest(ClientGameTestContext context)
	{
		context.runOnClient(mc -> {
			Screen previous = mc.gui.screen();
			String clipboard = mc.keyboardHandler.getClipboard();
			try
			{
				EditorScreen screen = new EditorScreen();
				mc.gui.setScreen(screen);
				testScrolling(mc, screen);
				testSelection(mc, screen);
				testWrappingAndFolds(mc, screen);
			}finally
			{
				mc.keyboardHandler.setClipboard(clipboard);
				mc.gui.setScreen(previous);
			}
		});
		WurstTest.LOGGER.info("NBT editor scrolling, clipping, selection, "
			+ "clipboard, wrapping, and folding tests passed");
	}
	
	private void testScrolling(Minecraft mc, EditorScreen screen)
	{
		NBTSyntaxEditor editor = screen.editor;
		String text = IntStream.range(0, 16000).mapToObj(i -> "row_" + i)
			.collect(Collectors.joining("\n"));
		editor.setValue(text);
		for(double fraction : new double[]{0, 0.01, 0.25, 0.5, 0.75, 1})
		{
			editor.setScrollAmount(editor.maxScrollAmount() * fraction);
			assertFullViewport(mc, editor, false);
		}
		editor.setScrollAmount(0);
		editor.mouseScrolled(100, 100, 0, -80);
		assertFullViewport(mc, editor, false);
		
		// Ctrl+A selects all text while the viewport stays where it was.
		key(screen, InputConstants.KEY_A, InputConstants.MOD_CONTROL);
		assertFullViewport(mc, editor, true);
		key(screen, InputConstants.KEY_C, InputConstants.MOD_CONTROL);
		check(mc.keyboardHandler.getClipboard().equals(text),
			"Ctrl+A/C must copy the entire large document");
		
		editor.setScrollAmount(0);
		MouseButtonEvent down =
			mouse(editor.getRight() + 2, editor.getY() + 5, 0);
		screen.mouseClicked(down, false);
		MouseButtonEvent drag =
			mouse(editor.getRight() + 2, editor.getBottom() + 10, 0);
		screen.mouseDragged(drag, 0, drag.y() - down.y());
		screen.mouseReleased(drag);
		check(editor.scrollAmount() == editor.maxScrollAmount(),
			"Dragging the scrollbar must reach the document bottom");
		assertFullViewport(mc, editor, true);
		
		key(screen, InputConstants.KEY_HOME, InputConstants.MOD_CONTROL);
		check(editor.scrollAmount() == 0, "Ctrl+Home must reveal the caret");
		key(screen, InputConstants.KEY_END, InputConstants.MOD_CONTROL);
		check(editor.scrollAmount() == editor.maxScrollAmount(),
			"Ctrl+End must reveal the last line");
		key(screen, InputConstants.KEY_A, InputConstants.MOD_CONTROL);
		key(screen, InputConstants.KEY_BACKSPACE, 0);
		check(editor.getValue().isEmpty() && editor.scrollAmount() == 0,
			"Deleting a selected large document must reset the scroll range");
	}
	
	private void testSelection(Minecraft mc, EditorScreen screen)
	{
		NBTSyntaxEditor editor = screen.editor;
		editor.setValue("alpha beta\ngamma delta\nlast");
		int x = editor.getX() + 48;
		int y = editor.getY() + 4;
		MouseButtonEvent down = mouse(x, y + 1, 0);
		screen.mouseClicked(down, false);
		MouseButtonEvent drag = mouse(x + mc.font.width("gamma"), y + 10, 0);
		screen.mouseDragged(drag, 3, 2);
		screen.mouseReleased(drag);
		key(screen, InputConstants.KEY_C, InputConstants.MOD_CONTROL);
		check(mc.keyboardHandler.getClipboard().equals("alpha beta\ngamma"),
			"Dragging must select using mouse positions, not movement deltas");
		check(highlightCount(render(mc, editor)) == 2,
			"Dragging across two lines must draw both highlights");
		
		key(screen, InputConstants.KEY_X, InputConstants.MOD_CONTROL);
		check(editor.getValue().equals(" delta\nlast"),
			"Cut must delete precisely the highlighted text");
		key(screen, InputConstants.KEY_V, InputConstants.MOD_CONTROL);
		check(editor.getValue().equals("alpha beta\ngamma delta\nlast"),
			"Paste must restore the cut selection");
		
		key(screen, InputConstants.KEY_HOME, InputConstants.MOD_CONTROL);
		key(screen, InputConstants.KEY_RIGHT, InputConstants.MOD_SHIFT);
		key(screen, InputConstants.KEY_C, InputConstants.MOD_CONTROL);
		check(mc.keyboardHandler.getClipboard().equals("a"),
			"Shift+Arrow must extend selection");
		screen.mouseClicked(
			mouse(x + mc.font.width("alpha"), y + 1, InputConstants.MOD_SHIFT),
			false);
		screen.mouseReleased(mouse(x, y, 0));
		key(screen, InputConstants.KEY_C, InputConstants.MOD_CONTROL);
		check(mc.keyboardHandler.getClipboard().equals("alpha"),
			"Shift+Click must extend selection from the original anchor");
		
		screen.mouseClicked(mouse(x + 1, y + 1, 0), true);
		screen.mouseReleased(mouse(x, y, 0));
		key(screen, InputConstants.KEY_C, InputConstants.MOD_CONTROL);
		check(mc.keyboardHandler.getClipboard().equals("alpha"),
			"Double-click must select the word");
	}
	
	private void testWrappingAndFolds(Minecraft mc, EditorScreen screen)
	{
		NBTSyntaxEditor editor = screen.editor;
		String wrapped = "long_value_".repeat(300) + "\ntail";
		editor.setValue(wrapped);
		check(editor.maxScrollAmount() > 0,
			"Wrapped text must contribute to the scrollbar range");
		editor.setScrollAmount(editor.maxScrollAmount() / 2.0);
		key(screen, InputConstants.KEY_A, InputConstants.MOD_CONTROL);
		assertFullViewport(mc, editor, true);
		
		// Seek to a wrapped display row and replace one character.
		editor.setScrollAmount(0);
		int x = editor.getX() + 48;
		int y = editor.getY() + 4;
		int rowLength = mc.font
			.plainSubstrByWidth(wrapped, editor.getWidth() - 52).length();
		screen.mouseClicked(mouse(x, y + 10, 0), false);
		screen.mouseReleased(mouse(x, y + 10, 0));
		key(screen, InputConstants.KEY_DELETE, 0);
		check(
			editor.getValue()
				.equals(wrapped.substring(0, rowLength)
					+ wrapped.substring(rowLength + 1)),
			"Clicking a wrapped row must edit the character drawn there");
		
		String folded = "{\n  \"literal\": \"[\",\n  \"value\": 1\n}\ntarget\n";
		editor.setValue(folded);
		screen.mouseClicked(mouse(editor.getX() + 4, y + 1, 0), false);
		screen.mouseReleased(mouse(editor.getX() + 4, y + 1, 0));
		screen.mouseClicked(mouse(x, y + 10, 0), false);
		screen.mouseReleased(mouse(x, y + 10, 0));
		key(screen, InputConstants.KEY_END, InputConstants.MOD_SHIFT);
		key(screen, InputConstants.KEY_C, InputConstants.MOD_CONTROL);
		check(mc.keyboardHandler.getClipboard().equals("target"),
			"Clicking below a fold must select the visible source line; "
				+ "brackets inside strings must not swallow later lines");
		key(screen, InputConstants.KEY_A, InputConstants.MOD_CONTROL);
		key(screen, InputConstants.KEY_BACKSPACE, 0);
		check(editor.getValue().isEmpty() && editor.maxScrollAmount() == 0,
			"Select-all must include folded text and clear stale folds");
	}
	
	private void assertFullViewport(Minecraft mc, NBTSyntaxEditor editor,
		boolean selected)
	{
		GuiRenderState state = render(mc, editor);
		List<GuiTextRenderState> texts = new ArrayList<>();
		state.forEachText(texts::add);
		check(!texts.isEmpty(), "Scrolling must not leave an empty viewport");
		ScreenRectangle viewport = new ScreenRectangle(editor.getX() + 1,
			editor.getY() + 1, editor.getWidth() - 2, editor.getHeight() - 2);
		for(GuiTextRenderState text : texts)
			check(viewport.equals(text.scissor),
				"Text scissor must remain fixed: expected " + viewport
					+ ", got " + text.scissor + ", scroll="
					+ editor.scrollAmount());
		int lowest = texts.stream().map(GuiTextRenderState::bounds)
			.filter(b -> b != null).mapToInt(ScreenRectangle::bottom).max()
			.orElse(0);
		check(lowest >= editor.getBottom() - 13,
			"Text must fill the viewport down to the bottom");
		if(selected)
			check(highlightCount(state) >= editor.getHeight() / 9 - 2,
				"Ctrl+A must visibly highlight every visible row");
	}
	
	private GuiRenderState render(Minecraft mc, NBTSyntaxEditor editor)
	{
		GuiRenderState state = new GuiRenderState();
		editor.extractRenderState(new GuiGraphicsExtractor(mc, state, 0, 0), 0,
			0, 0);
		return state;
	}
	
	private int highlightCount(GuiRenderState state)
	{
		int[] count = {0};
		state.forEachElement(element -> {
			if(element instanceof ColoredRectangleRenderState rectangle
				&& rectangle.pipeline() == RenderPipelines.GUI_TEXT_HIGHLIGHT)
				count[0]++;
		}, GuiRenderState.TraverseRange.ALL);
		return count[0];
	}
	
	private void key(Screen screen, int key, int modifiers)
	{
		check(screen.keyPressed(new KeyEvent(key, 0, modifiers)),
			"Editor must consume key " + key);
	}
	
	private MouseButtonEvent mouse(double x, double y, int modifiers)
	{
		return new MouseButtonEvent(x, y, new MouseButtonInfo(0, modifiers));
	}
	
	private void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
	
	private static final class EditorScreen extends Screen
	{
		private NBTSyntaxEditor editor;
		
		private EditorScreen()
		{
			super(Component.literal("NBT editor regression"));
		}
		
		@Override
		protected void init()
		{
			editor = addRenderableWidget(new NBTSyntaxEditor(font, 20, 20,
				Math.min(420, width - 48), Math.min(200, height - 40)));
			setInitialFocus(editor);
		}
	}
}
