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

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;
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
	private static volatile List<MobOption> mobOptionsCache;
	
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
		return getOrCreateMobOptions();
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
	
	public int findPreferredHotbarSlot(ClientPlayerEntity player)
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
		for(MobOption option : getOrCreateMobOptions())
			if(option.id().equals(id))
				return option;
			
		return ANY_OPTION;
	}
	
	private static List<MobOption> getOrCreateMobOptions()
	{
		List<MobOption> cached = mobOptionsCache;
		int cachedSize = cached == null ? 0 : cached.size();
		if(cached == null || cachedSize <= 1)
		{
			List<MobOption> rebuilt = buildMobOptions();
			if(rebuilt.size() > 1)
			{
				mobOptionsCache = rebuilt;
				return rebuilt;
			}
			
			return rebuilt;
		}
		
		return cached;
	}
	
	private static List<MobOption> buildMobOptions()
	{
		List<MobOption> options = new ArrayList<>();
		options.add(ANY_OPTION);
		
		Registry<EntityType<?>> registry = resolveEntityRegistry();
		for(Identifier id : registry.getIds())
		{
			EntityType<?> type = registry.get(id);
			if(type == null || type.getSpawnGroup() == SpawnGroup.MISC)
				continue;
			
			String name = type.getName().getString();
			options.add(new MobOption(id.toString(), name, type));
		}
		
		options.sort(Comparator.comparing(MobOption::displayName,
			String.CASE_INSENSITIVE_ORDER));
		return Collections.unmodifiableList(options);
	}
	
	private static Registry<EntityType<?>> resolveEntityRegistry()
	{
		MinecraftClient mc = WurstClient.MC;
		if(mc != null)
		{
			if(mc.world != null)
			{
				Registry<EntityType<?>> worldRegistry =
					getRegistryFromManager(mc.world.getRegistryManager());
				if(worldRegistry != null)
					return worldRegistry;
			}
			
			ClientPlayNetworkHandler handler = mc.getNetworkHandler();
			if(handler != null)
			{
				Registry<EntityType<?>> networkRegistry =
					getRegistryFromManager(handler.getRegistryManager());
				if(networkRegistry != null)
					return networkRegistry;
			}
		}
		
		return Registries.ENTITY_TYPE;
	}
	
	private static Registry<EntityType<?>> getRegistryFromManager(
		DynamicRegistryManager manager)
	{
		if(manager == null)
			return null;
		
		return manager.getOptional(RegistryKeys.ENTITY_TYPE).orElse(null);
	}
	
	public record MobOption(String id, String displayName,
		net.minecraft.entity.EntityType<?> type)
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
				return stack.isIn(ItemTags.SWORDS);
			}
		},
		
		AXE("Any axe")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.isIn(ItemTags.AXES);
			}
		},
		
		HOE("Any hoe")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.isIn(ItemTags.HOES);
			}
		},
		
		PICKAXE("Any pickaxe")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.isIn(ItemTags.PICKAXES);
			}
		},
		
		SHOVEL("Any shovel")
		{
			@Override
			protected boolean matches(ItemStack stack)
			{
				return stack.isIn(ItemTags.SHOVELS);
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
		
		public int findBestSlot(PlayerInventory inventory)
		{
			int bestSlot = -1;
			float bestScore = Float.NEGATIVE_INFINITY;
			
			for(int i = 0; i < 9; i++)
			{
				ItemStack stack = inventory.getStack(i);
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
			return (float)ItemUtils
				.getAttribute(item, EntityAttributes.ATTACK_DAMAGE)
				.orElse(0.0D);
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
