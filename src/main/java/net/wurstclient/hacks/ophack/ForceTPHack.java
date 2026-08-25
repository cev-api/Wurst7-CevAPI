/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class ForceTPHack extends Hack implements UpdateListener
{
	private final TextFieldSetting targetMode = new TextFieldSetting(
		"Target mode", "OnTarget, ToVoid, or ToPlayer.", "OnTarget");
	private final TextFieldSetting targetPlayer = new TextFieldSetting(
		"Target player", "Player name for ToPlayer mode.", "");
	private final SliderSetting range = new SliderSetting("Range",
		"Targeting range.", 512, 1, 512, 1, ValueDisplay.INTEGER);
	private final SliderSetting velocity = new SliderSetting("Velocity",
		"Pearl initial velocity.", 5, 0, 10, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting automatic =
		new CheckboxSetting("FULLAUTO", "Spawn repeatedly.", true);
	private final SliderSetting delay = new SliderSetting("Tick delay",
		"Ticks between pearls.", 3, 3, 20, 1, ValueDisplay.INTEGER);
	private int ticks;
	
	public ForceTPHack()
	{
		super("ForceTP",
			"Creates owner-bound ender pearls through creative item data.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(targetMode);
		addSetting(targetPlayer);
		addSetting(range);
		addSetting(velocity);
		addSetting(automatic);
		addSetting(delay);
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
		ticks = delay.getValueI();
		EVENTS.add(UpdateListener.class, this);
		spawnPearl();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(!automatic.isChecked())
			return;
		if(ticks++ >= delay.getValueI())
		{
			ticks = 0;
			spawnPearl();
		}
	}
	
	private void spawnPearl()
	{
		if(MC.player == null || MC.gameMode == null)
			return;
		UUID owner = targetUuid();
		if(owner == null)
			return;
		ItemStack original = MC.player.getMainHandItem().copy();
		ItemStack egg = new ItemStack(Items.BEE_SPAWN_EGG);
		egg.applyComponentsAndValidate(DataComponentPatch.builder()
			.set(DataComponents.ENTITY_DATA, createPearlData(owner)).build());
		BlockHitResult hit =
			new BlockHitResult(MC.player.getEyePosition(), Direction.DOWN,
				BlockPos.containing(MC.player.getEyePosition()), false);
		int slot = 36 + MC.player.getInventory().getSelectedSlot();
		MC.gameMode.handleCreativeModeItemAdd(egg, slot);
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		MC.gameMode.handleCreativeModeItemAdd(original, slot);
	}
	
	private UUID targetUuid()
	{
		if(MC.hitResult instanceof EntityHitResult hit)
			return hit.getEntity().getUUID();
		String name = targetPlayer.getValue();
		if(!name.isBlank() && MC.getConnection() != null)
			for(var p : MC.getConnection().getOnlinePlayers())
				if(p.getProfile().name().equalsIgnoreCase(name))
					return p.getProfile().id();
		return MC.player.getUUID();
	}
	
	private TypedEntityData<EntityType<?>> createPearlData(UUID owner)
	{
		CompoundTag tag = new CompoundTag();
		ListTag pos = new ListTag();
		Vec3 target = MC.hitResult != null
			&& MC.hitResult.getType() != HitResult.Type.MISS
				? MC.hitResult.getLocation() : MC.player.position();
		if(targetMode.getValue().equalsIgnoreCase("ToVoid"))
			pos.add(DoubleTag.valueOf(MC.player.getX()));
		else
			pos.add(DoubleTag.valueOf(target.x));
		if(targetMode.getValue().equalsIgnoreCase("ToVoid"))
			pos.add(DoubleTag.valueOf(MC.level.getMinY() - 1));
		else
			pos.add(DoubleTag.valueOf(target.y));
		if(targetMode.getValue().equalsIgnoreCase("ToVoid"))
			pos.add(DoubleTag.valueOf(MC.player.getZ()));
		else
			pos.add(DoubleTag.valueOf(target.z));
		tag.put("Pos", pos);
		ListTag motion = new ListTag();
		motion.add(DoubleTag.valueOf(0));
		motion.add(DoubleTag.valueOf(-velocity.getValueI()));
		motion.add(DoubleTag.valueOf(0));
		tag.put("Motion", motion);
		long most = owner.getMostSignificantBits();
		long least = owner.getLeastSignificantBits();
		tag.putIntArray("Owner", new int[]{(int)(most >> 32), (int)most,
			(int)(least >> 32), (int)least});
		tag.putString("id", "minecraft:ender_pearl");
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
			.getValue(Identifier.parse("minecraft:ender_pearl"));
		return TypedEntityData.of(type, tag);
	}
}
