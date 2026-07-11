/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.settings.PlayerMuteListSetting;

public final class EditPlayerMuteScreen extends Screen
{
	private final Screen previous;
	private final PlayerMuteListSetting setting;
	private final Set<String> pendingMutedIds = new LinkedHashSet<>();
	private PlayerList playerList;
	private Button toggleButton;
	
	public EditPlayerMuteScreen(Screen previous, PlayerMuteListSetting setting)
	{
		super(Component.literal(""));
		this.previous = previous;
		this.setting = setting;
	}
	
	@Override
	public void init()
	{
		List<PlayerInfo> players = setting.getHack().getOnlinePlayers();
		pendingMutedIds.clear();
		setting.getHack().getMutedOnlineIds()
			.forEach(id -> pendingMutedIds.add(id.toString()));
		playerList = new PlayerList(minecraft, this, players);
		addWidget(playerList);
		playerList.setSelectionListener(() -> {
			if(toggleButton != null)
				toggleButton.setMessage(Component.literal(toggleButtonText()));
		});
		if(!players.isEmpty())
			playerList.setSelection(
				List.of(players.get(0).getProfile().id().toString()), 0);
		
		int x = width / 2 - 155;
		addRenderableWidget(toggleButton = Button
			.builder(Component.literal(toggleButtonText()),
				button -> toggleSelected())
			.bounds(x, height - 28, 100, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Apply"), button -> apply())
				.bounds(x + 105, height - 28, 65, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Done"), button -> {
				apply();
				minecraft.gui.setScreen(previous);
			}).bounds(x + 175, height - 28, 65, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Cancel"),
				button -> minecraft.gui.setScreen(previous))
			.bounds(x + 245, height - 28, 65, 20).build());
	}
	
	private void apply()
	{
		setting.getHack()
			.applyOnlineMuteSelection(List.copyOf(pendingMutedIds));
	}
	
	private void toggleSelected()
	{
		Entry selected = playerList.getSelected();
		if(selected == null)
			return;
		
		String id = selected.selectionKey();
		if(!pendingMutedIds.add(id))
			pendingMutedIds.remove(id);
		toggleButton.setMessage(Component.literal(toggleButtonText()));
	}
	
	private String toggleButtonText()
	{
		Entry selected = playerList == null ? null : playerList.getSelected();
		return selected != null
			&& pendingMutedIds.contains(selected.selectionKey())
				? "Unmute player" : "Mute player";
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		playerList.extractRenderState(context, mouseX, mouseY, partialTicks);
		context.centeredText(minecraft.font,
			"Select a player, then Mute/Unmute and Apply", width / 2, 12,
			CommonColors.WHITE);
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			minecraft.gui.setScreen(previous);
			return true;
		}
		return super.keyPressed(context);
	}
	
	private final class Entry
		extends MultiSelectEntryListWidget.Entry<EditPlayerMuteScreen.Entry>
	{
		private final PlayerInfo info;
		
		private Entry(PlayerList parent, PlayerInfo info)
		{
			super(parent);
			this.info = Objects.requireNonNull(info);
		}
		
		@Override
		public Component getNarration()
		{
			return Component.translatable("narrator.select",
				info.getProfile().name());
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			boolean muted = playerList.isMuted(this);
			context.text(minecraft.font, muted ? "[x]" : "[ ]", x + 4, y + 4,
				muted ? CommonColors.GREEN : CommonColors.LIGHT_GRAY, false);
			context.text(minecraft.font, info.getProfile().name(), x + 28,
				y + 4, CommonColors.WHITE, false);
			context.text(minecraft.font, muted ? "Muted" : "Not muted", x + 28,
				y + 16, CommonColors.LIGHT_GRAY, false);
		}
		
		@Override
		public String selectionKey()
		{
			return info.getProfile().id().toString();
		}
	}
	
	private final class PlayerList
		extends MultiSelectEntryListWidget<EditPlayerMuteScreen.Entry>
	{
		private PlayerList(Minecraft minecraft, EditPlayerMuteScreen screen,
			List<PlayerInfo> players)
		{
			super(minecraft, screen.width, screen.height - 72, 30, 28);
			for(PlayerInfo info : players)
				addEntry(EditPlayerMuteScreen.this.new Entry(this, info));
		}
		
		private boolean isMuted(Entry entry)
		{
			return pendingMutedIds.contains(entry.selectionKey());
		}
		
		@Override
		protected String getSelectionKey(EditPlayerMuteScreen.Entry entry)
		{
			return entry.selectionKey();
		}
	}
}
