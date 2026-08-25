/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.wurstclient.util.RegistryUtils;
import net.minecraft.world.item.component.TypedEntityData;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;

public final class ForceOPSignHack extends Hack
{
	private final CheckboxSetting cloneSign = new CheckboxSetting(
		"Clone sign mode", "Use the first command to clone a sign.", false);
	private final TextFieldSetting command1 = new TextFieldSetting(
		"Click command 1", "Command on line one.", "kill @e");
	private final TextFieldSetting command2 =
		new TextFieldSetting("Click command 2", "Command on line two.", "");
	private final TextFieldSetting command3 =
		new TextFieldSetting("Click command 3", "Command on line three.", "");
	private final TextFieldSetting command4 =
		new TextFieldSetting("Click command 4", "Command on line four.", "");
	
	public ForceOPSignHack()
	{
		super("ForceOPSign",
			"Creates a creative sign whose lines execute commands when clicked.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(cloneSign);
		addSetting(command1);
		addSetting(command2);
		addSetting(command3);
		addSetting(command4);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null || MC.gameMode == null
			|| !MC.player.getAbilities().instabuild)
		{
			setEnabled(false);
			return;
		}
		ItemStack stack = new ItemStack(Items.OAK_SIGN);
		stack.applyComponentsAndValidate(DataComponentPatch.builder()
			.set(DataComponents.BLOCK_ENTITY_DATA, createEntityData()).build());
		int slot = 36 + MC.player.getInventory().getSelectedSlot();
		MC.gameMode.handleCreativeModeItemAdd(stack, slot);
		MC.gameMode.handleContainerInput(MC.player.containerMenu.containerId,
			slot, 0, ContainerInput.PICKUP, MC.player);
		MC.gameMode.handleContainerInput(MC.player.containerMenu.containerId,
			slot, 0, ContainerInput.PICKUP, MC.player);
		setEnabled(false);
	}
	
	private TypedEntityData<BlockEntityType<?>> createEntityData()
	{
		CompoundTag root = new CompoundTag();
		root.putString("id", "minecraft:sign");
		ListTag messages = new ListTag();
		for(String command : new String[]{command1.getValue(),
			command2.getValue(), command3.getValue(), command4.getValue()})
		{
			CompoundTag line = new CompoundTag();
			line.putString("text", "");
			CompoundTag click = new CompoundTag();
			click.putString("action", "run_command");
			click.putString("command", command);
			line.put("click_event", click);
			messages.add(line);
		}
		CompoundTag front = new CompoundTag();
		front.put("messages", messages);
		CompoundTag back = new CompoundTag();
		back.put("messages", messages.copy());
		root.put("front_text", front);
		root.put("back_text", back);
		return TypedEntityData.of(RegistryUtils.blockEntityType("sign"), root);
	}
}
