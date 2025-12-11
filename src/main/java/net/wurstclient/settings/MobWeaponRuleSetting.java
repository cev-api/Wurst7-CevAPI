/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.MobWeaponRuleComponent;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonObject;
import net.wurstclient.util.text.WText;

public final class MobWeaponRuleSetting extends Setting
{
	private static final MobOption ANY_OPTION =
		new MobOption("any", "Any mob", null);
	private static final List<MobOption> MOB_OPTIONS = buildMobOptions();
	
	private MobOption selectedMob = ANY_OPTION;
	private WeaponCategory selectedWeapon = WeaponCategory.NONE;
	
	private final MobOption defaultMob = selectedMob;
	private final WeaponCategory defaultWeapon = selectedWeapon;
	
	public MobWeaponRuleSetting(String name)
	{
		super(name, WText.empty());
	}
	
	@Override
	public Component getComponent()
	{
		return new MobWeaponRuleComponent(this);
	}
	
	public List<MobOption> getMobOptions()
	{
		return MOB_OPTIONS;
	}
	
	public MobOption getSelectedMob()
	{
		return selectedMob;
	}
	
	public void setSelectedMob(MobOption option)
	{
		selectedMob = Objects.requireNonNull(option);
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void resetMob()
	{
		setSelectedMob(defaultMob);
	}
	
	public WeaponCategory getSelectedWeapon()
	{
		return selectedWeapon;
	}
	
	public void setSelectedWeapon(WeaponCategory category)
	{
		selectedWeapon = Objects.requireNonNull(category);
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void resetWeapon()
	{
		setSelectedWeapon(defaultWeapon);
	}
	
	public boolean isActiveFor(Entity entity)
	{
		if(entity == null || selectedWeapon == WeaponCategory.NONE)
			return false;
		
		if(selectedMob == ANY_OPTION)
			return true;
		
		return entity.getType() == selectedMob.type();
	}
	
	public int findPreferredHotbarSlot(LocalPlayer player)
	{
		if(player == null)
			return -1;
		
		return selectedWeapon.findBestSlot(player.getInventory());
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		if(json == null || !json.isJsonObject())
			return;
		
		try
		{
			WsonObject obj = JsonUtils.getAsObject(json);
			String mobId = obj.getString("mob", ANY_OPTION.id());
			String weaponName =
				obj.getString("weapon", WeaponCategory.NONE.name());
			
			setSelectedMob(findMobOption(mobId));
			setSelectedWeapon(WeaponCategory.valueOf(weaponName));
		}catch(IllegalArgumentException | JsonException e)
		{
			resetMob();
			resetWeapon();
		}
	}
	
	@Override
	public JsonElement toJson()
	{
		JsonObject json = new JsonObject();
		json.addProperty("mob", selectedMob.id());
		json.addProperty("weapon", selectedWeapon.name());
		return json;
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "MobWeaponRule");
		json.add("defaultMob", new JsonPrimitive(defaultMob.id()));
		json.add("defaultWeapon",
			new JsonPrimitive(defaultWeapon.name().toLowerCase()));
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		return Collections.emptySet();
	}
	
	private static MobOption findMobOption(String id)
	{
		for(MobOption option : MOB_OPTIONS)
			if(option.id().equals(id))
				return option;
			
		return ANY_OPTION;
	}
	
	private static List<MobOption> buildMobOptions()
	{
		List<MobOption> options = new ArrayList<>();
		options.add(ANY_OPTION);
		BuiltInRegistries.ENTITY_TYPE.keySet().stream()
			.map(BuiltInRegistries.ENTITY_TYPE::getValue)
			.filter(type -> type.getCategory() != MobCategory.MISC)
			.map(type -> {
				Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
				String name = type.getDescription().getString();
				return new MobOption(id.toString(), name, type);
			}).sorted(Comparator.comparing(MobOption::displayName,
				String.CASE_INSENSITIVE_ORDER))
			.forEach(options::add);
		return Collections.unmodifiableList(options);
	}
	
	public record MobOption(String id, String displayName,
		net.minecraft.world.entity.EntityType<?> type)
	{
		public boolean isAny()
		{
			return this == ANY_OPTION;
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
	
	public enum WeaponCategory
	{
		NONE("Do nothing")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return false;
			}
		},
		
		SWORD("Any sword")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.is(ItemTags.SWORDS);
			}
		},
		
		AXE("Any axe")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.is(ItemTags.AXES);
			}
		},
		
		HOE("Any hoe")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.is(ItemTags.HOES);
			}
		},
		
		PICKAXE("Any pickaxe")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.is(ItemTags.PICKAXES);
			}
		},
		
		SHOVEL("Any shovel")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.is(ItemTags.SHOVELS);
			}
		},
		
		MACE("Any mace")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.getItem() instanceof MaceItem;
			}
		},
		
		TRIDENT("Any trident")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.getItem() instanceof TridentItem;
			}
		};
		
		private final String displayName;
		
		WeaponCategory(String displayName)
		{
			this.displayName = displayName;
		}
		
		public int findBestSlot(Inventory inventory)
		{
			int bestSlot = -1;
			float bestScore = Float.NEGATIVE_INFINITY;
			
			for(int i = 0; i < 9; i++)
			{
				ItemStack stack = inventory.getItem(i);
				if(stack.isEmpty() || !matches(stack))
					continue;
				
				float score = getDamageScore(stack.getItem());
				if(score > bestScore)
				{
					bestScore = score;
					bestSlot = i;
				}
			}
			
			return bestSlot;
		}
		
		protected boolean matches(ItemStack stack)
		{
			return false;
		}
		
		protected float getDamageScore(Item item)
		{
			return (float)ItemUtils.getAttribute(item, Attributes.ATTACK_DAMAGE)
				.orElse(0.0D);
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
