/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.hacks.autolibrarian.BookOffer;
import net.wurstclient.settings.BookOffersSetting;
import net.wurstclient.util.MathUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.WurstColors;

public final class AddBookOfferScreen extends Screen
{
	private final Screen prevScreen;
	private final BookOffersSetting bookOffers;
	
	private ListGui listGui;
	
	private EditBox searchField;
	private EditBox levelField;
	private Button levelPlusButton;
	private Button levelMinusButton;
	
	private EditBox priceField;
	private Button pricePlusButton;
	private Button priceMinusButton;
	
	private Button addButton;
	private Button cancelButton;
	
	private BookOffer offerToAdd;
	private boolean alreadyAdded;
	private boolean suppressSearchListener;
	
	public AddBookOfferScreen(Screen prevScreen, BookOffersSetting bookOffers)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.bookOffers = bookOffers;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(minecraft, this);
		addWidget(listGui);
		
		int searchWidth = 200;
		int searchTop = 44;
		searchField = new EditBox(minecraft.font, width / 2 - searchWidth / 2,
			searchTop, searchWidth, 18, Component.literal(""));
		addWidget(searchField);
		searchField.setMaxLength(256);
		searchField.setResponder(this::onSearchChanged);
		
		levelField = new EditBox(minecraft.font, width / 2 - 32, height - 74,
			28, 12, Component.literal(""));
		addWidget(levelField);
		levelField.setMaxLength(2);
		levelField.setFilter(t -> {
			if(t.isEmpty())
				return true;
			
			if(!MathUtils.isInteger(t))
				return false;
			
			int level = Integer.parseInt(t);
			if(level < 1 || level > 10)
				return false;
			
			if(offerToAdd == null)
				return true;
			
			Enchantment enchantment = offerToAdd.getEnchantment();
			return enchantment == null || level <= enchantment.getMaxLevel();
		});
		levelField.setResponder(t -> {
			if(!MathUtils.isInteger(t))
				return;
			
			int level = Integer.parseInt(t);
			updateLevel(level, false);
		});
		
		priceField = new EditBox(minecraft.font, width / 2 - 32, height - 58,
			28, 12, Component.literal(""));
		addWidget(priceField);
		priceField.setMaxLength(2);
		priceField.setFilter(t -> t.isEmpty() || MathUtils.isInteger(t)
			&& Integer.parseInt(t) >= 1 && Integer.parseInt(t) <= 64);
		priceField.setResponder(t -> {
			if(!MathUtils.isInteger(t))
				return;
			
			int price = Integer.parseInt(t);
			updatePrice(price, false);
		});
		
		addRenderableWidget(levelPlusButton = Button
			.builder(Component.literal("+"), b -> updateLevel(1, true))
			.bounds(width / 2 + 2, height - 74, 20, 12)
			.createNarration(sup -> Component
				.translatable("gui.narrate.button", "increase level")
				.append(", current value: " + levelField.getValue()))
			.build());
		levelPlusButton.active = false;
		
		addRenderableWidget(levelMinusButton = Button
			.builder(Component.literal("-"), b -> updateLevel(-1, true))
			.bounds(width / 2 + 26, height - 74, 20, 12)
			.createNarration(sup -> Component
				.translatable("gui.narrate.button", "decrease level")
				.append(", current value: " + levelField.getValue()))
			.build());
		levelMinusButton.active = false;
		
		addRenderableWidget(pricePlusButton = Button
			.builder(Component.literal("+"), b -> updatePrice(1, true))
			.bounds(width / 2 + 2, height - 58, 20, 12)
			.createNarration(sup -> Component
				.translatable("gui.narrate.button", "increase max price")
				.append(", current value: " + priceField.getValue()))
			.build());
		pricePlusButton.active = false;
		
		addRenderableWidget(priceMinusButton = Button
			.builder(Component.literal("-"), b -> updatePrice(-1, true))
			.bounds(width / 2 + 26, height - 58, 20, 12)
			.createNarration(sup -> Component
				.translatable("gui.narrate.button", "decrease max price")
				.append(", current value: " + priceField.getValue()))
			.build());
		priceMinusButton.active = false;
		
		addRenderableWidget(
			addButton = Button.builder(Component.literal("Add"), b -> {
				bookOffers.add(offerToAdd);
				minecraft.setScreen(prevScreen);
			}).bounds(width / 2 - 102, height - 28, 100, 20).build());
		addButton.active = false;
		
		addRenderableWidget(cancelButton = Button
			.builder(Component.literal("Cancel"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(width / 2 + 2, height - 28, 100, 20).build());
	}
	
	private void updateLevel(int i, boolean offset)
	{
		if(offerToAdd == null)
			return;
		
		String id = offerToAdd.id();
		int level = offset ? offerToAdd.level() + i : i;
		level = Math.max(1, Math.min(10, level));
		int price = offerToAdd.price();
		
		Enchantment enchantment = offerToAdd.getEnchantment();
		if(enchantment != null && level > enchantment.getMaxLevel())
			return;
		
		updateSelectedOffer(new BookOffer(id, level, price));
	}
	
	private void updatePrice(int i, boolean offset)
	{
		if(offerToAdd == null)
			return;
		
		String id = offerToAdd.id();
		int level = offerToAdd.level();
		int price = offset ? offerToAdd.price() + i : i;
		
		if(price < 1 || price > 64)
			return;
		
		updateSelectedOffer(new BookOffer(id, level, price));
	}
	
	private int parseLevel(String text, int fallback)
	{
		if(MathUtils.isInteger(text))
		{
			int value = Integer.parseInt(text);
			return Math.max(1, Math.min(10, value));
		}
		
		return Math.max(1, Math.min(10, fallback));
	}
	
	private int parsePrice(String text, int fallback)
	{
		if(MathUtils.isInteger(text))
		{
			int value = Integer.parseInt(text);
			return Math.max(1, Math.min(64, value));
		}
		
		return Math.max(1, Math.min(64, fallback));
	}
	
	private void onSearchChanged(String value)
	{
		if(suppressSearchListener)
			return;
		
		String query = value == null ? "" : value.trim();
		listGui.setFilter(query);
		
		if(query.isEmpty())
		{
			updateSelectedOfferFromList(listGui.getPrimarySelection());
			return;
		}
		
		BookOffer match = listGui.findById(query);
		if(match != null)
		{
			listGui.selectByKey(toKey(match));
			return;
		}
		
		Identifier id = Identifier.tryParse(query);
		if(id == null)
		{
			updateSelectedOffer(null);
			return;
		}
		
		int level = parseLevel(levelField.getValue(),
			offerToAdd != null ? offerToAdd.level() : 1);
		int price = parsePrice(priceField.getValue(),
			offerToAdd != null ? offerToAdd.price() : 64);
		updateSelectedOffer(new BookOffer(query, level, price));
	}
	
	private void updateSelectedOfferFromList(BookOffer offer)
	{
		updateSelectedOffer(offer);
	}
	
	private static String toKey(BookOffer offer)
	{
		return offer.id() + ";" + offer.level();
	}
	
	private void updateSelectedOffer(BookOffer offer)
	{
		offerToAdd = offer;
		alreadyAdded = offer != null && bookOffers.contains(offer);
		if(addButton != null)
			addButton.active =
				offer != null && offer.isMostlyValid() && !alreadyAdded;
		
		if(offer == null)
		{
			if(levelField != null && !levelField.getValue().isEmpty())
				levelField.setValue("");
			
			if(priceField != null && !priceField.getValue().isEmpty())
				priceField.setValue("");
			
		}else
		{
			String level = "" + offer.level();
			if(levelField != null && !levelField.getValue().equals(level))
				levelField.setValue(level);
			
			String price = "" + offer.price();
			if(priceField != null && !priceField.getValue().equals(price))
				priceField.setValue(price);
		}
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(searchField.mouseClicked(context, doubleClick))
		{
			setFocused(searchField);
			return true;
		}
		
		boolean childClicked = super.mouseClicked(context, doubleClick);
		
		levelField.mouseClicked(context, doubleClick);
		priceField.mouseClicked(context, doubleClick);
		
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
			cancelButton.onPress(context);
		
		return childClicked;
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		switch(context.key())
		{
			case GLFW.GLFW_KEY_ENTER:
			if(addButton.active)
				addButton.onPress(context);
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			cancelButton.onPress(context);
			break;
			
			default:
			break;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public void tick()
	{
		Enchantment enchantment =
			offerToAdd != null ? offerToAdd.getEnchantment() : null;
		int maxLevel = enchantment != null ? enchantment.getMaxLevel() : 10;
		levelPlusButton.active =
			offerToAdd != null && offerToAdd.level() < maxLevel;
		levelMinusButton.active = offerToAdd != null && offerToAdd.level() > 1;
		
		pricePlusButton.active = offerToAdd != null && offerToAdd.price() < 64;
		priceMinusButton.active = offerToAdd != null && offerToAdd.price() > 1;
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		Matrix3x2fStack matrixStack = context.pose();
		
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		matrixStack.pushMatrix();
		
		Font tr = minecraft.font;
		String titleText =
			"Available Books (" + listGui.children().size() + ")";
		context.drawCenteredString(tr, titleText, width / 2, 12,
			CommonColors.WHITE);
		
		searchField.render(context, mouseX, mouseY, partialTicks);
		if(searchField.getValue().isEmpty() && !searchField.isFocused())
			context.drawString(tr, "search or custom ID",
				searchField.getX() + 4, searchField.getY() + 5,
				CommonColors.GRAY);
		
		levelField.render(context, mouseX, mouseY, partialTicks);
		priceField.render(context, mouseX, mouseY, partialTicks);
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
		
		matrixStack.translate(width / 2 - 100, 0);
		
		context.drawString(tr, "Level:", 0, height - 72,
			WurstColors.VERY_LIGHT_GRAY);
		context.drawString(tr, "Max price:", 0, height - 56,
			WurstColors.VERY_LIGHT_GRAY);
		
		if(alreadyAdded && offerToAdd != null)
		{
			String errorText = offerToAdd.getEnchantmentNameWithLevel()
				+ " is already on your list!";
			context.drawString(tr, errorText, 0, height - 40,
				WurstColors.LIGHT_RED);
		}
		
		matrixStack.popMatrix();
		
		RenderUtils.drawItem(context, new ItemStack(Items.EMERALD),
			width / 2 - 16, height - 58, false);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private final class Entry
		extends MultiSelectEntryListWidget.Entry<AddBookOfferScreen.Entry>
	{
		private final BookOffer bookOffer;
		private long lastClickTime;
		
		public Entry(ListGui parent, BookOffer bookOffer)
		{
			super(parent);
			this.bookOffer = Objects.requireNonNull(bookOffer);
		}
		
		@Override
		public Component getNarration()
		{
			int maxLevel = bookOffer.getEnchantmentEntry()
				.map(entry -> entry.value().getMaxLevel())
				.orElse(bookOffer.level());
			String levels = maxLevel + (maxLevel == 1 ? " level" : " levels");
			
			return Component.translatable("narrator.select",
				"Enchantment " + bookOffer.getEnchantmentName() + ", ID "
					+ bookOffer.id() + ", " + levels);
		}
		
		@Override
		public boolean mouseClicked(MouseButtonEvent context,
			boolean doubleClick)
		{
			if(!super.mouseClicked(context, doubleClick))
				return false;
			
			long timeSinceLastClick = Util.getMillis() - lastClickTime;
			lastClickTime = Util.getMillis();
			
			if(timeSinceLastClick < 250 && addButton.active)
				addButton.onPress(context);
			
			return true;
		}
		
		@Override
		public void renderContent(GuiGraphics context, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			
			Item item = BuiltInRegistries.ITEM
				.getValue(Identifier.parse("enchanted_book"));
			ItemStack stack = new ItemStack(item);
			RenderUtils.drawItem(context, stack, x + 1, y + 1, true);
			
			Font tr = minecraft.font;
			String name = bookOffer.getEnchantmentName();
			boolean isCurse = bookOffer.getEnchantmentEntry()
				.map(entry -> entry.is(EnchantmentTags.CURSE)).orElse(false);
			int nameColor =
				isCurse ? WurstColors.LIGHT_RED : WurstColors.VERY_LIGHT_GRAY;
			context.drawString(tr, name, x + 28, y, nameColor, false);
			
			context.drawString(tr, bookOffer.id(), x + 28, y + 9,
				CommonColors.LIGHT_GRAY, false);
			
			int maxLevel = bookOffer.getEnchantmentEntry()
				.map(entry -> entry.value().getMaxLevel())
				.orElse(bookOffer.level());
			String levels = maxLevel + (maxLevel == 1 ? " level" : " levels");
			context.drawString(tr, levels, x + 28, y + 18,
				CommonColors.LIGHT_GRAY, false);
		}
		
		@Override
		public String selectionKey()
		{
			return toKey(bookOffer);
		}
	}
	
	private final class ListGui
		extends MultiSelectEntryListWidget<AddBookOfferScreen.Entry>
	{
		private final List<BookOffer> allOffers = new ArrayList<>();
		private String filterText = "";
		
		public ListGui(Minecraft minecraft, AddBookOfferScreen screen)
		{
			super(minecraft, screen.width, screen.height - 152, 68, 30);
			setSelectionListener(
				() -> updateSelectedOfferFromList(getPrimarySelection()));
			
			RegistryAccess drm = minecraft.level.registryAccess();
			Registry<Enchantment> registry =
				drm.lookupOrThrow(Registries.ENCHANTMENT);
			
			registry.stream().map(BookOffer::create)
				.filter(BookOffer::isMostlyValid).sorted()
				.forEach(allOffers::add);
			
			applyFilter();
			ensureSelection();
		}
		
		private void applyFilter()
		{
			SelectionState previousState = captureState();
			clearEntries();
			String query = filterText.toLowerCase(Locale.ROOT);
			allOffers.stream().filter(offer -> matchesFilter(offer, query))
				.map(offer -> new AddBookOfferScreen.Entry(this, offer))
				.forEach(this::addEntry);
			
			if(children().isEmpty())
			{
				ensureSelection();
				return;
			}
			
			if(previousState != null && !previousState.selectedKeys().isEmpty())
			{
				restoreState(previousState);
				return;
			}
			
			ensureSelection();
		}
		
		private boolean matchesFilter(BookOffer offer, String query)
		{
			if(query.isEmpty())
				return true;
			
			String idLower = offer.id().toLowerCase(Locale.ROOT);
			if(idLower.contains(query))
				return true;
			
			return offer.getEnchantmentNameWithLevel().toLowerCase(Locale.ROOT)
				.contains(query);
		}
		
		public void setFilter(String text)
		{
			BookOffer previous = getPrimarySelection();
			filterText = text == null ? "" : text.toLowerCase(Locale.ROOT);
			applyFilter();
			if(previous != null)
				selectByKey(toKey(previous));
		}
		
		public BookOffer findById(String id)
		{
			if(id == null || id.isEmpty())
				return null;
			
			String lower = id.toLowerCase(Locale.ROOT);
			return allOffers.stream()
				.filter(
					offer -> offer.id().toLowerCase(Locale.ROOT).equals(lower))
				.findFirst().orElse(null);
		}
		
		public BookOffer getPrimarySelection()
		{
			List<AddBookOfferScreen.Entry> selected = getSelectedEntries();
			return selected.isEmpty() ? null : selected.get(0).bookOffer;
		}
		
		public void selectByKey(String key)
		{
			if(key == null)
				return;
			
			for(AddBookOfferScreen.Entry entry : children())
			{
				if(toKey(entry.bookOffer).equals(key))
				{
					onEntryClicked(entry, false, false);
					return;
				}
			}
		}
		
		@Override
		protected String getSelectionKey(AddBookOfferScreen.Entry entry)
		{
			return toKey(entry.bookOffer);
		}
	}
}
