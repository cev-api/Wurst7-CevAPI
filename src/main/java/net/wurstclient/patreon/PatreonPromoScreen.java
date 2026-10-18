/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.WurstClient;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.patreon.SupporterManager.AuthPhase;
import net.wurstclient.patreon.SupporterManager.AuthSnapshot;
import net.wurstclient.patreon.gif.GifTexturePlayer;

/**
 * Full-screen, once-per-launch Patreon promotion.
 *
 * <p>
 * Dismissing this screen only affects the current Minecraft process. A valid,
 * cryptographically verified entitlement is the only way to suppress it
 * permanently.
 */
public final class PatreonPromoScreen extends Screen
{
	private static final int ACCENT = 0xFFFFB300;
	private static final int BODY_COLOR = 0xFFD7DEEA;
	private static final int MUTED_COLOR = 0xFF98A4B5;
	private static final int SUCCESS_COLOR = 0xFF7DFF9B;
	private static final int ERROR_COLOR = 0xFFFF7B72;
	private static final int PANEL_COLOR = 0xB8101622;
	private static final int PANEL_BORDER = 0x8048FFD8;
	private static final long AUTO_CLOSE_MS = 4_500L;
	private static final long FADE_IN_MS = 220L;
	private static final int BOTTOM_MARGIN = 72;
	private static final int BUTTON_GAP = 26;
	
	private final Screen prevScreen;
	private GifTexturePlayer gifPlayer;
	private Button patreonButton;
	private Button continueButton;
	private List<TextBlock> textBlocks = List.of();
	private int textBlockWidth = -1;
	private int totalTextHeight;
	private int textTop;
	private int textBottom;
	private int scrollOffset;
	private int maxScroll;
	private long successAtMs = -1L;
	private long nextGifRetryAtMs;
	private final long openedAtMs = System.currentTimeMillis();
	private static final int MAX_GIF_RETRIES = 10;
	private static final long GIF_RETRY_INTERVAL_MS = 500L;
	
	public PatreonPromoScreen(Screen prevScreen)
	{
		super(Component.literal("Patreon Supporter"));
		this.prevScreen = prevScreen;
	}
	
	@Override
	protected void init()
	{
		super.init();
		
		if(gifPlayer == null)
		{
			gifPlayer = new GifTexturePlayer();
			Identifier selected = GifTexturePlayer.pickRandomGif(minecraft);
			gifPlayer.loadAsync(selected);
			nextGifRetryAtMs =
				System.currentTimeMillis() + GIF_RETRY_INTERVAL_MS;
		}
		
		rebuildText();
		
		int supportWidth = Math.max(180, Math.min(340, width - 48));
		int continueWidth = Math.max(140, Math.min(220, width - 48));
		int centerX = width / 2;
		int continueY = getContinueButtonY();
		int patreonY = getSupportButtonY();
		
		patreonButton = Button
			.builder(Component.literal("Support / Connect with Patreon"),
				b -> startAuthentication())
			.bounds(centerX - supportWidth / 2, patreonY, supportWidth, 20)
			.build();
		continueButton = Button
			.builder(Component.literal("Continue to Minecraft"),
				b -> continueToGame())
			.bounds(centerX - continueWidth / 2, continueY, continueWidth, 20)
			.build();
		
		addRenderableWidget(patreonButton);
		addRenderableWidget(continueButton);
		updateButtonState();
	}
	
	private SupporterManager getManager()
	{
		return WurstClient.INSTANCE.getSupporterManager();
	}
	
	private int getContinueButtonY()
	{
		return Math.max(44, height - BOTTOM_MARGIN);
	}
	
	private int getSupportButtonY()
	{
		return getContinueButtonY() - BUTTON_GAP;
	}
	
	private void rebuildText()
	{
		int contentWidth = Math.max(120, Math.min(width - 64, 720));
		if(contentWidth == textBlockWidth)
			return;
		
		textBlockWidth = contentWidth;
		Font font = minecraft.font;
		textBlocks = new ArrayList<>();
		
		addBlock(font, contentWidth,
			"Maintaining Wurst and NiceWurst for Minecraft 1.21.11 alongside the latest versions requires additional time and effort.",
			BODY_COLOR, 0, false);
		addBlock(font, contentWidth,
			"New features, improvements and fixes are primarily developed for the latest Minecraft release. Bringing these changes to 1.21.11 requires additional coding, compatibility adjustments and testing.",
			BODY_COLOR, 4, false);
		addBlock(font, contentWidth,
			"Your support helps keep older versions maintained while giving me more time to develop new tools, mods, clients and other projects.",
			BODY_COLOR, 2, false);
		addBlock(font, contentWidth,
			"Support the project on Patreon and help make future development possible!",
			ACCENT, 6, true);
		addBlock(font, contentWidth,
			"As a thank-you, verified Patreon supporters can enjoy Wurst and NiceWurst without these promotional startup screens.",
			MUTED_COLOR, 3, false);
		
		totalTextHeight = 0;
		for(TextBlock block : textBlocks)
			totalTextHeight +=
				block.gapBefore + block.lines.size() * font.lineHeight;
	}
	
	private void addBlock(Font font, int contentWidth, String text, int color,
		int gapBefore, boolean bold)
	{
		MutableComponent component = Component.literal(text);
		if(bold)
			component = component.withStyle(ChatFormatting.BOLD);
		List<FormattedCharSequence> lines = font.split(component, contentWidth);
		textBlocks.add(new TextBlock(lines, color, gapBefore));
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		renderBackground(context, mouseX, mouseY, partialTicks);
		renderContent(context);
		
		for(Renderable renderable : renderables)
			renderable.render(context, mouseX, mouseY, partialTicks);
		
		long elapsed = System.currentTimeMillis() - openedAtMs;
		if(elapsed < FADE_IN_MS)
		{
			float fade = 1F - Math.min(1F, elapsed / (float)FADE_IN_MS);
			int alpha = Math.round(255F * fade);
			context.fill(0, 0, width, height, alpha << 24);
		}
	}
	
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		context.fillGradient(0, 0, width, height, 0xF0060910, 0xF0162233);
		context.fill(0, 0, width, 2, ACCENT);
		context.fill(0, height - 2, width, height, 0x66FFB300);
	}
	
	private void renderContent(GuiGraphics context)
	{
		Font font = minecraft.font;
		int panelWidth = Math.min(width - 20, 920);
		int panelX = (width - panelWidth) / 2;
		int panelY = 10;
		int panelHeight = Math.max(20, height - 20);
		int innerX = panelX + 16;
		int innerWidth = Math.max(80, panelWidth - 32);
		
		context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight,
			PANEL_COLOR);
		context.fill(panelX, panelY, panelX + panelWidth, panelY + 1,
			PANEL_BORDER);
		context.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth,
			panelY + panelHeight, PANEL_BORDER);
		
		boolean compact = height < 320;
		int gifMaxHeight =
			compact ? Math.max(36, Math.min(96, (int)(height * 0.22F)))
				: Math.max(48, Math.min(210, (int)(height * 0.28F)));
		int gifMaxWidth = Math.max(90, Math.min(360, innerWidth / 2));
		int gifY = panelY + 12;
		int gifBottom = gifY;
		
		GifTexturePlayer.Frame frame =
			gifPlayer == null ? null : gifPlayer.getCurrentFrame();
		if(frame != null)
		{
			int imageWidth = Math.max(1, gifPlayer.getImageWidth());
			int imageHeight = Math.max(1, gifPlayer.getImageHeight());
			float scale = Math.min(gifMaxWidth / (float)imageWidth,
				gifMaxHeight / (float)imageHeight);
			int drawWidth = Math.max(1, Math.round(imageWidth * scale));
			int drawHeight = Math.max(1, Math.round(imageHeight * scale));
			int drawX = (width - drawWidth) / 2;
			context.blit(RenderPipelines.GUI_TEXTURED, frame.id(), drawX, gifY,
				0.0F, 0.0F, drawWidth, drawHeight, imageWidth, imageHeight,
				imageWidth, imageHeight, 0xFFFFFFFF);
			gifBottom = gifY + drawHeight;
			
		}else
		{
			int boxWidth = Math.min(gifMaxWidth, 220);
			int boxHeight = Math.min(gifMaxHeight, 120);
			int boxX = (width - boxWidth) / 2;
			context.fill(boxX, gifY, boxX + boxWidth, gifY + boxHeight,
				0x66000000);
			String message = gifPlayer != null && gifPlayer.hasFailed()
				? "Neco GIF unavailable" : "Loading Neco...";
			context.drawCenteredString(font, message, width / 2,
				gifY + boxHeight / 2 - 4, MUTED_COLOR);
			gifBottom = gifY + boxHeight;
		}
		
		String brand = NiceWurstModule.isActive() ? "NiceWurst" : "Wurst";
		String headline = "Help Keep " + brand + " 1.21.11 Alive!";
		int headlineY = gifBottom + (compact ? 4 : 10);
		context.drawCenteredString(font,
			Component.literal(headline).withStyle(ChatFormatting.BOLD),
			width / 2, headlineY, ACCENT);
		if(!compact)
			context.drawCenteredString(font,
				"An annoying message from your favorite developer, CevAPI:",
				width / 2, headlineY + font.lineHeight + 2, MUTED_COLOR);
		
		int supportY = getSupportButtonY();
		textTop = compact ? headlineY + font.lineHeight + 4
			: headlineY + font.lineHeight * 2 + 10;
		textBottom = Math.max(textTop + 8, supportY - (compact ? 16 : 28));
		if(textBottom > supportY - 4)
			textBottom = Math.max(textTop, supportY - 4);
		
		int viewHeight = textBottom - textTop;
		maxScroll = Math.max(0, totalTextHeight - viewHeight);
		if(scrollOffset > maxScroll)
			scrollOffset = maxScroll;
		if(scrollOffset < 0)
			scrollOffset = 0;
		
		context.enableScissor(innerX, textTop, innerX + innerWidth, textBottom);
		int y = textTop - scrollOffset;
		for(TextBlock block : textBlocks)
		{
			y += block.gapBefore;
			for(FormattedCharSequence line : block.lines)
			{
				context.drawCenteredString(font, line, width / 2, y,
					block.color);
				y += font.lineHeight;
			}
		}
		context.disableScissor();
		
		if(maxScroll > 0 && textBottom > textTop + 4)
			renderScrollbar(context, innerX + innerWidth + 2, textTop,
				textBottom);
		
		renderStatus(context, font, supportY);
	}
	
	private void renderScrollbar(GuiGraphics context, int x, int top,
		int bottom)
	{
		int height = Math.max(1, bottom - top);
		int thumbHeight = Math.max(18,
			Math.round(height * (height / (float)(height + maxScroll))));
		int thumbTop = top + Math
			.round((height - thumbHeight) * (scrollOffset / (float)maxScroll));
		context.fill(x, top, x + 4, bottom, 0x55303030);
		context.fill(x, thumbTop, x + 4, thumbTop + thumbHeight, 0xAA808080);
	}
	
	private void renderStatus(GuiGraphics context, Font font, int supportY)
	{
		SupporterManager manager = getManager();
		if(manager == null)
			return;
		
		AuthSnapshot snapshot = manager.getAuthSnapshot();
		String message = snapshot.message();
		if(message == null || message.isBlank())
			message = manager.getStatusMessage();
		
		int color = switch(snapshot.phase())
		{
			case SUCCESS -> SUCCESS_COLOR;
			case ERROR -> ERROR_COLOR;
			case WAITING, DISCONNECTING -> ACCENT;
			case IDLE -> MUTED_COLOR;
		};
		
		if(message == null || message.isBlank())
		{
			context.drawCenteredString(font,
				"Supporting is optional. You can continue to Minecraft without a subscription.",
				width / 2, supportY - 14, MUTED_COLOR);
			return;
		}
		
		int maxWidth = Math.max(120, Math.min(width - 64, 720));
		List<FormattedCharSequence> lines =
			font.split(Component.literal(message), maxWidth);
		int lineCount = Math.min(lines.size(), height < 320 ? 2 : 3);
		int startY = supportY - 12 - lineCount * font.lineHeight;
		for(int i = 0; i < lineCount; i++)
			context.drawCenteredString(font, lines.get(i), width / 2,
				startY + i * font.lineHeight, color);
	}
	
	@Override
	public void tick()
	{
		super.tick();
		retryGifIfNeeded();
		updateButtonState();
		
		SupporterManager manager = getManager();
		if(manager == null)
			return;
		
		AuthSnapshot snapshot = manager.getAuthSnapshot();
		if(snapshot.phase() == AuthPhase.SUCCESS
			|| manager.hasValidSupporterEntitlement())
		{
			if(successAtMs < 0L)
				successAtMs = System.currentTimeMillis();
			
			if(System.currentTimeMillis() - successAtMs >= AUTO_CLOSE_MS)
				continueToGame();
		}
	}
	
	private void retryGifIfNeeded()
	{
		if(gifPlayer == null || minecraft == null || gifPlayer.isLoading()
			|| gifPlayer.isReady() || gifPlayer.getRetries() >= MAX_GIF_RETRIES)
			return;
		
		long now = System.currentTimeMillis();
		if(now < nextGifRetryAtMs)
			return;
		nextGifRetryAtMs = now + GIF_RETRY_INTERVAL_MS;
		gifPlayer.loadAsync(GifTexturePlayer.pickRandomGif(minecraft));
	}
	
	private void updateButtonState()
	{
		if(patreonButton == null || continueButton == null)
			return;
		
		SupporterManager manager = getManager();
		AuthPhase phase = manager == null ? AuthPhase.IDLE
			: manager.getAuthSnapshot().phase();
		
		boolean busy =
			phase == AuthPhase.WAITING || phase == AuthPhase.DISCONNECTING;
		boolean success = phase == AuthPhase.SUCCESS;
		
		patreonButton.active = !busy && !success;
		patreonButton.setMessage(Component.literal(switch(phase)
		{
			case WAITING -> "Connecting to Patreon...";
			case SUCCESS -> "Patreon membership verified!";
			case ERROR -> "Retry Patreon Authentication";
			default -> "Support / Connect with Patreon";
		}));
		continueButton.setMessage(Component.literal(busy
			? "Cancel and Continue to Minecraft" : "Continue to Minecraft"));
	}
	
	private void startAuthentication()
	{
		SupporterManager manager = getManager();
		if(manager == null)
			return;
		manager.beginAuthentication(url -> Util.getPlatform().openUri(url));
		updateButtonState();
	}
	
	private void continueToGame()
	{
		SupporterManager manager = getManager();
		if(manager != null)
			manager.cancelAuthentication();
		
		closeGif();
		if(prevScreen != null)
			minecraft.setScreen(prevScreen);
		else
			minecraft.setScreen(new TitleScreen());
	}
	
	private void closeGif()
	{
		if(gifPlayer != null)
		{
			gifPlayer.close();
			gifPlayer = null;
		}
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
		double scrollY)
	{
		if(maxScroll <= 0)
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		
		if(mouseY < textTop || mouseY > textBottom)
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		
		scrollOffset -= (int)Math.round(scrollY * 18.0);
		if(scrollOffset < 0)
			scrollOffset = 0;
		if(scrollOffset > maxScroll)
			scrollOffset = maxScroll;
		return true;
	}
	
	@Override
	public void onClose()
	{
		continueToGame();
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public void removed()
	{
		closeGif();
		super.removed();
	}
	
	private record TextBlock(List<FormattedCharSequence> lines, int color,
		int gapBefore)
	{}
}
