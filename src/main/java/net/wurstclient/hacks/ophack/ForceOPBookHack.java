/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;

public final class ForceOPBookHack extends Hack
{
	private final CheckboxSetting anyCommand = new CheckboxSetting(
		"Any command", "Use the configured command instead of /op.", false);
	private final TextFieldSetting command = new TextFieldSetting("Command",
		"Command run when the page is clicked.", "kill @e");
	private final TextFieldSetting title = new TextFieldSetting("Book title",
		"Written book title.", "Book and Quill");
	private final TextFieldSetting author = new TextFieldSetting("Book author",
		"Written book author.", "Book and Quill");
	private final TextFieldSetting text =
		new TextFieldSetting("Page text", "Visible page text.", "");
	
	public ForceOPBookHack()
	{
		super("ForceOPBook",
			"Creates a creative written book with a click-command page.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(anyCommand);
		addSetting(command);
		addSetting(title);
		addSetting(author);
		addSetting(text);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null || MC.gameMode == null)
		{
			setEnabled(false);
			return;
		}
		if(!MC.player.getAbilities().instabuild)
		{
			setEnabled(false);
			return;
		}
		
		ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
		String run = anyCommand.isChecked() ? command.getValue()
			: "op " + MC.player.getName().getString();
		MutableComponent page = Component.literal(text.getValue());
		page.withStyle(
			style -> style.withClickEvent(new ClickEvent.RunCommand(run)));
		List<Filterable<Component>> pages = new ArrayList<>();
		pages.add(Filterable.passThrough(page));
		WrittenBookContent content =
			new WrittenBookContent(Filterable.passThrough(title.getValue()),
				author.getValue(), 0, pages, true);
		stack.applyComponentsAndValidate(DataComponentPatch.builder()
			.set(DataComponents.WRITTEN_BOOK_CONTENT, content).build());
		
		int slot = 36 + MC.player.getInventory().getSelectedSlot();
		MC.gameMode.handleCreativeModeItemAdd(stack, slot);
		MC.gameMode.handleContainerInput(MC.player.containerMenu.containerId,
			slot, 0, ContainerInput.PICKUP, MC.player);
		MC.gameMode.handleContainerInput(MC.player.containerMenu.containerId,
			slot, 0, ContainerInput.PICKUP, MC.player);
		setEnabled(false);
	}
}
