/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
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
	
	private TextFieldWidget searchField;
	private TextFieldWidget levelField;
	private ButtonWidget levelPlusButton;
	private ButtonWidget levelMinusButton;
	
	private TextFieldWidget priceField;
	private ButtonWidget pricePlusButton;
	private ButtonWidget priceMinusButton;
	
	private ButtonWidget addButton;
	private ButtonWidget cancelButton;
	
	private BookOffer offerToAdd;
	private boolean alreadyAdded;
	private boolean suppressSearchListener;
	
	public AddBookOfferScreen(Screen prevScreen, BookOffersSetting bookOffers)
	{
		super(Text.literal(""));
		this.prevScreen = prevScreen;
		this.bookOffers = bookOffers;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(client, this);
		addSelectableChild(listGui);
		
		int searchWidth = 200;
		int searchTop = 44;
		searchField = new TextFieldWidget(client.textRenderer,
			width / 2 - searchWidth / 2, searchTop, searchWidth, 18,
			Text.literal(""));
		addSelectableChild(searchField);
		searchField.setMaxLength(256);
		searchField.setChangedListener(this::onSearchChanged);
		
		levelField = new TextFieldWidget(client.textRenderer, width / 2 - 32,
			height - 74, 28, 12, Text.literal(""));
		addSelectableChild(levelField);
		levelField.setMaxLength(2);
		levelField.setTextPredicate(t -> {
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
		levelField.setChangedListener(t -> {
			if(!MathUtils.isInteger(t))
				return;
			
			int level = Integer.parseInt(t);
			updateLevel(level, false);
		});
		
		priceField = new TextFieldWidget(client.textRenderer, width / 2 - 32,
			height - 58, 28, 12, Text.literal(""));
		addSelectableChild(priceField);
		priceField.setMaxLength(2);
		priceField.setTextPredicate(t -> t.isEmpty() || MathUtils.isInteger(t)
			&& Integer.parseInt(t) >= 1 && Integer.parseInt(t) <= 64);
		priceField.setChangedListener(t -> {
			if(!MathUtils.isInteger(t))
				return;
			
			int price = Integer.parseInt(t);
			updatePrice(price, false);
		});
		
		addDrawableChild(levelPlusButton =
			ButtonWidget.builder(Text.literal("+"), b -> updateLevel(1, true))
				.dimensions(width / 2 + 2, height - 74, 20, 12)
				.narrationSupplier(sup -> Text
					.translatable("gui.narrate.button", "increase level")
					.append(", current value: " + levelField.getText()))
				.build());
		levelPlusButton.active = false;
		
		addDrawableChild(levelMinusButton =
			ButtonWidget.builder(Text.literal("-"), b -> updateLevel(-1, true))
				.dimensions(width / 2 + 26, height - 74, 20, 12)
				.narrationSupplier(sup -> Text
					.translatable("gui.narrate.button", "decrease level")
					.append(", current value: " + levelField.getText()))
				.build());
		levelMinusButton.active = false;
		
		addDrawableChild(pricePlusButton = ButtonWidget
			.builder(Text.literal("+"), b -> updatePrice(1, true))
			.dimensions(width / 2 + 2, height - 58, 20, 12)
			.narrationSupplier(sup -> Text
				.translatable("gui.narrate.button", "increase max price")
				.append(", current value: " + priceField.getText()))
			.build());
		pricePlusButton.active = false;
		
		addDrawableChild(priceMinusButton = ButtonWidget
			.builder(Text.literal("-"), b -> updatePrice(-1, true))
			.dimensions(width / 2 + 26, height - 58, 20, 12)
			.narrationSupplier(sup -> Text
				.translatable("gui.narrate.button", "decrease max price")
				.append(", current value: " + priceField.getText()))
			.build());
		priceMinusButton.active = false;
		
		addDrawableChild(
			addButton = ButtonWidget.builder(Text.literal("Add"), b -> {
				bookOffers.add(offerToAdd);
				client.setScreen(prevScreen);
			}).dimensions(width / 2 - 102, height - 28, 100, 20).build());
		addButton.active = false;
		
		addDrawableChild(cancelButton = ButtonWidget
			.builder(Text.literal("Cancel"), b -> client.setScreen(prevScreen))
			.dimensions(width / 2 + 2, height - 28, 100, 20).build());
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
		
		int level = parseLevel(levelField.getText(),
			offerToAdd != null ? offerToAdd.level() : 1);
		int price = parsePrice(priceField.getText(),
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
			if(levelField != null && !levelField.getText().isEmpty())
				levelField.setText("");
			
			if(priceField != null && !priceField.getText().isEmpty())
				priceField.setText("");
			
		}else
		{
			String level = "" + offer.level();
			if(levelField != null && !levelField.getText().equals(level))
				levelField.setText(level);
			
			String price = "" + offer.price();
			if(priceField != null && !priceField.getText().equals(price))
				priceField.setText(price);
		}
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if(searchField.mouseClicked(mouseX, mouseY, button))
		{
			setFocused(searchField);
			return true;
		}
		
		boolean childClicked = super.mouseClicked(mouseX, mouseY, button);
		
		levelField.mouseClicked(mouseX, mouseY, button);
		priceField.mouseClicked(mouseX, mouseY, button);
		
		if(button == GLFW.GLFW_MOUSE_BUTTON_4)
			cancelButton.onPress();
		
		return childClicked;
	}
	
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		switch(keyCode)
		{
			case GLFW.GLFW_KEY_ENTER:
			if(addButton.active)
				addButton.onPress();
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			cancelButton.onPress();
			break;
			
			default:
			break;
		}
		
		return super.keyPressed(keyCode, scanCode, modifiers);
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
	public void render(DrawContext context, int mouseX, int mouseY,
		float partialTicks)
	{
		Matrix3x2fStack matrixStack = context.getMatrices();
		
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		matrixStack.pushMatrix();
		
		TextRenderer tr = client.textRenderer;
		String titleText =
			"Available Books (" + listGui.children().size() + ")";
		context.drawCenteredTextWithShadow(tr, titleText, width / 2, 12,
			Colors.WHITE);
		
		searchField.render(context, mouseX, mouseY, partialTicks);
		if(searchField.getText().isEmpty() && !searchField.isFocused())
			context.drawTextWithShadow(tr, "search or custom ID",
				searchField.getX() + 4, searchField.getY() + 5, Colors.GRAY);
		
		levelField.render(context, mouseX, mouseY, partialTicks);
		priceField.render(context, mouseX, mouseY, partialTicks);
		
		for(Drawable drawable : drawables)
			drawable.render(context, mouseX, mouseY, partialTicks);
		
		matrixStack.translate(width / 2 - 100, 0);
		
		context.drawTextWithShadow(tr, "Level:", 0, height - 72,
			WurstColors.VERY_LIGHT_GRAY);
		context.drawTextWithShadow(tr, "Max price:", 0, height - 56,
			WurstColors.VERY_LIGHT_GRAY);
		
		if(alreadyAdded && offerToAdd != null)
		{
			String errorText = offerToAdd.getEnchantmentNameWithLevel()
				+ " is already on your list!";
			context.drawTextWithShadow(tr, errorText, 0, height - 40,
				WurstColors.LIGHT_RED);
		}
		
		matrixStack.popMatrix();
		
		RenderUtils.drawItem(context, new ItemStack(Items.EMERALD),
			width / 2 - 16, height - 58, false);
	}
	
	@Override
	public boolean shouldPause()
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
		public Text getNarration()
		{
			int maxLevel = bookOffer.getEnchantmentEntry()
				.map(entry -> entry.value().getMaxLevel())
				.orElse(bookOffer.level());
			String levels = maxLevel + (maxLevel == 1 ? " level" : " levels");
			
			return Text.translatable("narrator.select",
				"Enchantment " + bookOffer.getEnchantmentName() + ", ID "
					+ bookOffer.id() + ", " + levels);
		}
		
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button)
		{
			if(!super.mouseClicked(mouseX, mouseY, button))
				return false;
			
			long timeSinceLastClick = Util.getMeasuringTimeMs() - lastClickTime;
			lastClickTime = Util.getMeasuringTimeMs();
			
			if(timeSinceLastClick < 250 && addButton.active)
				addButton.onPress();
			
			return true;
		}
		
		@Override
		public void render(DrawContext context, int index, int y, int x,
			int entryWidth, int entryHeight, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			Item item = Registries.ITEM.get(Identifier.of("enchanted_book"));
			ItemStack stack = new ItemStack(item);
			RenderUtils.drawItem(context, stack, x + 1, y + 1, true);
			
			TextRenderer tr = client.textRenderer;
			String name = bookOffer.getEnchantmentName();
			boolean isCurse = bookOffer.getEnchantmentEntry()
				.map(entry -> entry.isIn(EnchantmentTags.CURSE)).orElse(false);
			int nameColor =
				isCurse ? WurstColors.LIGHT_RED : WurstColors.VERY_LIGHT_GRAY;
			context.drawText(tr, name, x + 28, y, nameColor, false);
			
			context.drawText(tr, bookOffer.id(), x + 28, y + 9,
				Colors.LIGHT_GRAY, false);
			
			int maxLevel = bookOffer.getEnchantmentEntry()
				.map(entry -> entry.value().getMaxLevel())
				.orElse(bookOffer.level());
			String levels = maxLevel + (maxLevel == 1 ? " level" : " levels");
			context.drawText(tr, levels, x + 28, y + 18, Colors.LIGHT_GRAY,
				false);
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
		
		public ListGui(MinecraftClient minecraft, AddBookOfferScreen screen)
		{
			super(minecraft, screen.width, screen.height - 152, 68, 30);
			setSelectionListener(
				() -> updateSelectedOfferFromList(getPrimarySelection()));
			
			DynamicRegistryManager drm = client.world.getRegistryManager();
			Registry<Enchantment> registry =
				drm.getOrThrow(RegistryKeys.ENCHANTMENT);
			
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
