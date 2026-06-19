/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.wurstclient.other_features.packettools.PacketDecodeCoverage.DecodeLevel;

public final class PacketDumper
{
	private static final DateTimeFormatter ISO_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
		.serializeSpecialFloatingPointValues().create();
	
	private final PacketDecodeCoverage coverage;
	private final PacketFilter filter;
	private final EntityLifecycleTracker lifecycleTracker;
	
	public PacketDumper(PacketDecodeCoverage coverage, PacketFilter filter,
		EntityLifecycleTracker lifecycleTracker)
	{
		this.coverage = coverage;
		this.filter = filter;
		this.lifecycleTracker = lifecycleTracker;
	}
	
	public String dump(Packet<?> packet, String direction, String bundlePath)
	{
		if(!filter.matches(packet))
			return null;
		
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("timestamp", LocalDateTime.now().format(ISO_FORMAT));
		root.put("direction", direction);
		root.put("class", packet.getClass().getName());
		root.put("simpleName", packet.getClass().getSimpleName());
		
		try
		{
			root.put("packetId", PacketCatalog.formatPacketName(packet));
		}catch(Exception ignored)
		{}
		
		if(bundlePath != null && !bundlePath.isEmpty())
			root.put("bundlePath", bundlePath);
		
		Map<String, Object> fields = dumpFields(packet);
		root.put("fields", fields);
		
		String json = GSON.toJson(root);
		
		if(!filter.matchesJsonLine(json))
			return null;
		
		return json;
	}
	
	public List<String> dumpRecursive(Packet<?> packet, String direction,
		String bundlePath)
	{
		List<String> lines = new ArrayList<>();
		
		if(packet instanceof ClientboundBundlePacket bundle)
		{
			String basePath = bundlePath == null || bundlePath.isEmpty()
				? "bundle" : bundlePath;
			int idx = 0;
			for(Packet<?> sub : bundle.subPackets())
			{
				String subPath = basePath + "[" + idx + "]";
				lines.addAll(dumpRecursive(sub, direction, subPath));
				idx++;
			}
			String containerLine = dumpContainer(packet, direction, basePath);
			if(containerLine != null)
				lines.add(containerLine);
			return lines;
		}
		
		String line = dump(packet, direction, bundlePath);
		if(line != null)
			lines.add(line);
		return lines;
	}
	
	private String dumpContainer(Packet<?> packet, String direction,
		String bundlePath)
	{
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("timestamp", LocalDateTime.now().format(ISO_FORMAT));
		root.put("direction", direction);
		root.put("class", packet.getClass().getName());
		root.put("simpleName", packet.getClass().getSimpleName());
		root.put("bundlePath", bundlePath);
		root.put("type", "bundle_container");
		
		if(packet instanceof ClientboundBundlePacket bundle)
		{
			int count = 0;
			for(Packet<?> ignored : bundle.subPackets())
				count++;
			root.put("subPacketCount", count);
			List<String> subNames = new ArrayList<>();
			for(Packet<?> sub : bundle.subPackets())
				subNames.add(sub.getClass().getSimpleName());
			root.put("subPacketTypes", subNames);
		}
		
		return GSON.toJson(root);
	}
	
	// ---- Field dumping ----
	
	private Map<String, Object> dumpFields(Packet<?> packet)
	{
		Map<String, Object> f = new LinkedHashMap<>();
		dumpViaReflection(packet, f);
		
		boolean hasSpecial = false;
		
		if(packet instanceof ClientboundAddEntityPacket p)
		{
			hasSpecial = true;
			augmentAddEntity(p, f);
		}
		if(packet instanceof ClientboundPlayerInfoUpdatePacket p)
		{
			hasSpecial = true;
			augmentPlayerInfo(p, f);
		}
		if(packet instanceof ClientboundSetEquipmentPacket p)
		{
			hasSpecial = true;
			augmentEquipment(p, f);
		}
		if(packet instanceof ClientboundSetEntityDataPacket p)
		{
			hasSpecial = true;
			augmentEntityData(p, f);
		}
		if(packet instanceof ClientboundContainerSetSlotPacket p)
		{
			hasSpecial = true;
			augmentContainerSetSlot(p, f);
		}
		if(packet instanceof ClientboundContainerSetContentPacket p)
		{
			hasSpecial = true;
			augmentContainerSetContent(p, f);
		}
		if(packet instanceof ClientboundMerchantOffersPacket p)
		{
			hasSpecial = true;
			augmentMerchantOffers(p, f);
		}
		if(packet instanceof ClientboundUpdateAttributesPacket p)
		{
			hasSpecial = true;
			augmentUpdateAttributes(p, f);
		}
		
		if(hasSpecial)
			coverage.record(packet, DecodeLevel.FULLY_DECODED);
		else if(!f.isEmpty())
			coverage.record(packet, DecodeLevel.PARTIALLY_DECODED);
		else
			coverage.record(packet, DecodeLevel.FALLBACK_ONLY);
		
		return f;
	}
	
	// === Reflection cache ===
	
	private static final ConcurrentHashMap<Class<?>, List<FieldAccessor>> FIELD_CACHE =
		new ConcurrentHashMap<>();
	
	private interface FieldAccessor
	{
		String name();
		
		Object get(Object target);
	}
	
	private record FieldRef(Field field) implements FieldAccessor
	{
		@Override
		public String name()
		{
			return field.getName();
		}
		
		@Override
		public Object get(Object target)
		{
			try
			{
				return field.get(target);
			}catch(Exception e)
			{
				return null;
			}
		}
	}
	
	private record MethodRef(Method method) implements FieldAccessor
	{
		@Override
		public String name()
		{
			return method.getName();
		}
		
		@Override
		public Object get(Object target)
		{
			try
			{
				return method.invoke(target);
			}catch(Exception e)
			{
				return null;
			}
		}
	}
	
	private static List<FieldAccessor> getCachedAccessors(Class<?> clazz)
	{
		return FIELD_CACHE.computeIfAbsent(clazz, k -> {
			List<FieldAccessor> list = new ArrayList<>();
			Class<?> c = k;
			while(c != Object.class && c != null && c != Record.class)
			{
				for(Field field : c.getDeclaredFields())
				{
					if(Modifier.isStatic(field.getModifiers()))
						continue;
					if(field.isSynthetic())
						continue;
					field.setAccessible(true);
					list.add(new FieldRef(field));
				}
				for(Method method : c.getDeclaredMethods())
				{
					if(Modifier.isStatic(method.getModifiers()))
						continue;
					if(method.isSynthetic() || method.isBridge())
						continue;
					if(method.getParameterCount() != 0)
						continue;
					String name = method.getName();
					if(name.equals("toString") || name.equals("hashCode")
						|| name.equals("equals") || name.equals("clone")
						|| name.equals("finalize"))
						continue;
					if(name.startsWith("get") && name.length() > 3
						&& Character.isUpperCase(name.charAt(3)))
						continue;
					method.setAccessible(true);
					list.add(new MethodRef(method));
				}
				c = c.getSuperclass();
			}
			return list;
		});
	}
	
	private void dumpViaReflection(Packet<?> packet, Map<String, Object> out)
	{
		for(FieldAccessor a : getCachedAccessors(packet.getClass()))
		{
			Object value = a.get(packet);
			if(value == null)
				continue;
			String key = a.name();
			if(!out.containsKey(key))
				out.put(key, safeToString(value));
		}
	}
	
	// ---- Augmenters ----
	
	private void augmentAddEntity(ClientboundAddEntityPacket p,
		Map<String, Object> f)
	{
		try
		{
			EntityType<?> type = p.getType();
			if(type != null)
				f.put("_decodedEntityType",
					BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
		}catch(Exception ignored)
		{}
		try
		{
			if(p.getType() == net.wurstclient.util.RegistryUtils
				.entityType("ender_pearl"))
				f.put("_ownerEntityId", p.getData());
		}catch(Exception ignored)
		{}
		try
		{
			lifecycleTracker.onAddEntity(p);
		}catch(Exception ignored)
		{}
	}
	
	private void augmentPlayerInfo(ClientboundPlayerInfoUpdatePacket p,
		Map<String, Object> f)
	{
		List<Map<String, Object>> entries = new ArrayList<>();
		try
		{
			for(ClientboundPlayerInfoUpdatePacket.Entry e : p.entries())
			{
				Map<String, Object> entry = new LinkedHashMap<>();
				if(e.profileId() != null)
					entry.put("uuid", String.valueOf(e.profileId()));
				if(e.profile() != null)
				{
					entry.put("name", e.profile().name());
					entry.put("profileId", String.valueOf(e.profile().id()));
					if(e.profile().properties() != null)
						for(var prop : e.profile().properties().values())
							if(prop.name().equals("textures"))
								entry.put("_textures",
									decodeTextureProperty(prop));
				}
				try
				{
					entry.put("listed", e.listed());
				}catch(Exception ignored)
				{}
				try
				{
					entry.put("latency", e.latency());
				}catch(Exception ignored)
				{}
				try
				{
					if(e.gameMode() != null)
						entry.put("gameMode", e.gameMode().getName());
				}catch(Exception ignored)
				{}
				try
				{
					var session = e.chatSession();
					if(session != null)
						entry.put("_chatSessionId",
							String.valueOf(session.sessionId()));
				}catch(Exception ignored)
				{}
				entries.add(entry);
			}
		}catch(Exception ignored)
		{}
		if(!entries.isEmpty())
			f.put("_decodedPlayerInfo", entries);
		try
		{
			lifecycleTracker.onPlayerInfoUpdate(p);
		}catch(Exception ignored)
		{}
	}
	
	private void augmentEquipment(ClientboundSetEquipmentPacket p,
		Map<String, Object> f)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		try
		{
			for(Object pairObj : p.getSlots())
			{
				Map<String, Object> eq = new LinkedHashMap<>();
				Method getFirst = pairObj.getClass().getMethod("getFirst");
				Method getSecond = pairObj.getClass().getMethod("getSecond");
				Object first = getFirst.invoke(pairObj);
				Object second = getSecond.invoke(pairObj);
				if(first instanceof EquipmentSlot slot)
					eq.put("slot", slot.name().toLowerCase());
				else
					eq.put("first", safeToString(first));
				if(second instanceof ItemStack stack)
					eq.put("item",
						RegistryDecoder.decodeItemStackDetailed(stack));
				else
					eq.put("item", safeToString(second));
				items.add(eq);
			}
		}catch(Exception ignored)
		{}
		if(!items.isEmpty())
			f.put("_decodedEquipment", items);
	}
	
	private void augmentEntityData(ClientboundSetEntityDataPacket p,
		Map<String, Object> f)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		try
		{
			var packed = p.packedItems();
			if(packed != null)
				for(var dv : packed)
				{
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("id", dv.id());
					try
					{
						e.put("serializer",
							dv.serializer().getClass().getSimpleName());
					}catch(Exception ignored)
					{}
					Object val = dv.value();
					if(val instanceof ItemStack stack)
						e.put("value",
							RegistryDecoder.decodeItemStackDetailed(stack));
					else
						e.put("value", safeToString(val));
					e.put("valueClass",
						val != null ? val.getClass().getSimpleName() : "null");
					items.add(e);
				}
		}catch(Exception ignored)
		{}
		if(!items.isEmpty())
			f.put("_decodedEntityData", items);
	}
	
	private void augmentContainerSetSlot(ClientboundContainerSetSlotPacket p,
		Map<String, Object> f)
	{
		try
		{
			f.put("_decodedItem",
				RegistryDecoder.decodeItemStackDetailed(p.getItem()));
		}catch(Exception ignored)
		{}
	}
	
	private void augmentContainerSetContent(
		ClientboundContainerSetContentPacket p, Map<String, Object> f)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		try
		{
			Method m = p.getClass().getMethod("items");
			@SuppressWarnings("unchecked")
			List<ItemStack> stacks = (List<ItemStack>)m.invoke(p);
			int count = 0;
			for(ItemStack stack : stacks)
			{
				if(count++ >= 128)
				{
					items.add(Map.of("_truncated", true, "_remainingCount",
						stacks.size() - 128));
					break;
				}
				items.add(RegistryDecoder.decodeItemStackDetailed(stack));
			}
		}catch(Exception ignored)
		{}
		if(!items.isEmpty())
			f.put("_decodedItems", items);
	}
	
	private void augmentMerchantOffers(ClientboundMerchantOffersPacket p,
		Map<String, Object> f)
	{
		List<Map<String, Object>> offers = new ArrayList<>();
		try
		{
			for(var offer : p.getOffers())
			{
				Map<String, Object> o = new LinkedHashMap<>();
				o.put("baseCostA", RegistryDecoder
					.decodeItemStackDetailed(offer.getBaseCostA()));
				o.put("costB",
					RegistryDecoder.decodeItemStackDetailed(offer.getCostB()));
				o.put("result",
					RegistryDecoder.decodeItemStackDetailed(offer.getResult()));
				o.put("uses", offer.getUses());
				o.put("maxUses", offer.getMaxUses());
				o.put("xp", offer.getXp());
				o.put("demand", offer.getDemand());
				o.put("priceMultiplier", offer.getPriceMultiplier());
				offers.add(o);
			}
		}catch(Exception ignored)
		{}
		if(!offers.isEmpty())
			f.put("_decodedOffers", offers);
	}
	
	private void augmentUpdateAttributes(ClientboundUpdateAttributesPacket p,
		Map<String, Object> f)
	{
		List<Map<String, Object>> attrs = new ArrayList<>();
		try
		{
			Method valuesMethod = p.getClass().getMethod("getValues");
			@SuppressWarnings("unchecked")
			List<?> values = (List<?>)valuesMethod.invoke(p);
			for(Object attr : values)
			{
				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", RegistryDecoder.decode(
					attr.getClass().getMethod("attribute").invoke(attr)));
				a.put("base", attr.getClass().getMethod("base").invoke(attr));
				List<Map<String, Object>> mods = new ArrayList<>();
				for(Object mod : (Iterable<?>)attr.getClass()
					.getMethod("modifiers").invoke(attr))
				{
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", String
						.valueOf(mod.getClass().getMethod("id").invoke(mod)));
					m.put("amount",
						mod.getClass().getMethod("amount").invoke(mod));
					m.put("operation", mod.getClass().getMethod("operation")
						.invoke(mod).toString());
					mods.add(m);
				}
				a.put("modifiers", mods);
				attrs.add(a);
			}
		}catch(Exception ignored)
		{}
		if(!attrs.isEmpty())
			f.put("_decodedAttributes", attrs);
	}
	
	// ---- Recursive value decoder ----
	
	private static final int MAX_DEPTH = 12;
	private static final int MAX_COLLECTION_ITEMS = 64;
	private static final int MAX_MAP_ENTRIES = 64;
	private static final int MAX_ARRAY_ITEMS = 64;
	private static final int MAX_STRING_LENGTH = 4096;
	private static final int MAX_BYTE_ARRAY_INLINE = 256;
	
	private static Object decodeValue(Object value, int depth,
		IdentityHashMap<Object, Boolean> visited)
	{
		if(value == null)
			return null;
		if(depth > MAX_DEPTH)
			return "_MAX_DEPTH";
		
		if(value instanceof String s)
			return s.length() > MAX_STRING_LENGTH
				? s.substring(0, MAX_STRING_LENGTH) + "...(truncated "
					+ (s.length() - MAX_STRING_LENGTH) + " chars)"
				: s;
		if(value instanceof Number || value instanceof Boolean)
			return value;
		
		Class<?> vc = value.getClass();
		boolean immutable = isImmutableType(vc);
		if(!immutable)
		{
			if(visited.containsKey(value))
				return "_CYCLE";
			visited.put(value, Boolean.TRUE);
		}
		
		Object result;
		try
		{
			result = decodeValueInternal(value, depth, visited);
		}finally
		{
			if(!immutable)
				visited.remove(value);
		}
		return result;
	}
	
	private static boolean isImmutableType(Class<?> c)
	{
		return c == String.class || c == Integer.class || c == Long.class
			|| c == Float.class || c == Double.class || c == Boolean.class
			|| c == UUID.class || c == Identifier.class || c == BlockPos.class
			|| c == Vec3.class || c.isPrimitive() || c.isEnum();
	}
	
	@SuppressWarnings("unchecked")
	private static Object decodeValueInternal(Object value, int depth,
		IdentityHashMap<Object, Boolean> visited)
	{
		int nextDepth = depth + 1;
		
		if(value instanceof Enum<?> e)
			return e.name();
		if(value instanceof UUID u)
			return u.toString();
		if(value instanceof BlockPos bp)
			return bp.toShortString();
		if(value instanceof Vec3 v)
			return String.format("%.3f,%.3f,%.3f", v.x, v.y, v.z);
		if(value instanceof Identifier id)
			return id.toString();
		if(value instanceof Component c)
			return c.getString();
		
		if(value instanceof Optional<?> opt)
			return opt.map(v -> decodeValue(v, nextDepth, visited))
				.orElse(null);
		
		// Registry types
		if(value instanceof net.minecraft.core.Holder<?> holder)
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("_type", "Holder");
			Optional<?> ko = holder.unwrapKey();
			if(ko.isPresent() && ko
				.get() instanceof net.minecraft.resources.ResourceKey<?> rk)
			{
				m.put("key", decodeResourceKeyStr(rk));
				return m;
			}
			Object val = holder.value();
			if(val != null)
			{
				Identifier loc = RegistryDecoder.getRegistryNameStatic(val);
				if(loc != null)
					m.put("value", loc.toString());
				else
					m.put("value", val.getClass().getSimpleName());
			}
			return m;
		}
		
		if(value instanceof net.minecraft.resources.ResourceKey<?> rk)
			return decodeResourceKeyStr(rk);
		
		// DataValue
		if(value instanceof SynchedEntityData.DataValue<?> dv)
			return RegistryDecoder.decodeDataValue(dv);
		
		// Pair
		String cn = value.getClass().getName();
		if(cn.equals("com.mojang.datafixers.util.Pair")
			|| cn.startsWith("com.mojang.datafixers.util.Pair"))
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("_type", "Pair");
			try
			{
				Object first =
					value.getClass().getMethod("getFirst").invoke(value);
				Object second =
					value.getClass().getMethod("getSecond").invoke(value);
				if(first instanceof EquipmentSlot slot)
					m.put("slot", slot.name().toLowerCase());
				m.put("first", decodeValue(first, nextDepth, visited));
				if(second instanceof ItemStack stack)
					m.put("item",
						RegistryDecoder.decodeItemStackDetailed(stack));
				m.put("second", decodeValue(second, nextDepth, visited));
			}catch(Exception e)
			{
				m.put("_error", e.getMessage());
			}
			return m;
		}
		
		// Map.Entry
		if(value instanceof Map.Entry<?, ?> me)
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", decodeValue(me.getKey(), nextDepth, visited));
			m.put("value", decodeValue(me.getValue(), nextDepth, visited));
			return m;
		}
		
		// ItemStack
		if(value instanceof ItemStack stack)
			return RegistryDecoder.decodeItemStackDetailed(stack);
		
		// BlockState
		if(value instanceof BlockState state)
			return RegistryDecoder.decodeBlockStateDetailed(state);
		
		// NBT Tag
		if(value instanceof net.minecraft.nbt.Tag tag)
		{
			String s = tag.toString();
			return s.length() > 4096 ? s.substring(0, 4096) + "..." : s;
		}
		
		// GameProfile
		if(value instanceof com.mojang.authlib.GameProfile profile)
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("_type", "GameProfile");
			m.put("id", profile.id().toString());
			m.put("name", profile.name());
			try
			{
				var props = profile.properties();
				if(!props.isEmpty())
				{
					Map<String, Object> pm = new LinkedHashMap<>();
					for(String key : props.keySet())
						pm.put(key,
							decodeValue(props.get(key), nextDepth, visited));
					m.put("properties", pm);
				}
			}catch(Exception ignored)
			{}
			return m;
		}
		
		// Strongly-encapsulated JDK types should not be reflectively opened.
		// Fall back to their string form so packet dumps stay usable on Java
		// 25.
		if(cn.startsWith("java.") || cn.startsWith("javax.")
			|| cn.startsWith("jdk."))
			return value.toString();
		
		// Property
		if(cn.equals("com.mojang.authlib.properties.Property"))
		{
			try
			{
				Method gn = value.getClass().getMethod("getName");
				Method gv = value.getClass().getMethod("getValue");
				Method hs = value.getClass().getMethod("hasSignature");
				String name = (String)gn.invoke(value);
				String val = (String)gv.invoke(value);
				boolean sig = (boolean)hs.invoke(value);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("_type", "Property");
				m.put("name", name);
				m.put("length", val.length());
				m.put("signed", sig);
				if(name.equals("textures"))
					m.put("_decodedTexture", decodeTexturePayload(val));
				return m;
			}catch(Exception e)
			{
				return "Property{...}";
			}
		}
		
		// DataComponentMap
		if(value instanceof DataComponentMap map)
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("_type", "DataComponentMap");
			int count = 0;
			for(var entry : map)
			{
				if(count++ >= MAX_MAP_ENTRIES)
				{
					m.put("_truncated", true);
					break;
				}
				DataComponentType<?> type = entry.type();
				String id = type != null ? BuiltInRegistries.DATA_COMPONENT_TYPE
					.getKey(type).toString() : "?";
				m.put(id, decodeValue(entry.value(), nextDepth, visited));
			}
			return m;
		}
		
		// Collections
		if(value instanceof Collection<?> col)
		{
			List<Object> list = new ArrayList<>();
			int count = 0;
			for(Object item : col)
			{
				if(count++ >= MAX_COLLECTION_ITEMS)
				{
					list.add(Map.of("_truncated", true, "_remainingCount",
						col.size() - MAX_COLLECTION_ITEMS));
					break;
				}
				list.add(decodeValue(item, nextDepth, visited));
			}
			return list;
		}
		
		// Maps
		if(value instanceof Map<?, ?> map)
		{
			Map<String, Object> out = new LinkedHashMap<>();
			int count = 0;
			for(Map.Entry<?, ?> e : map.entrySet())
			{
				if(count++ >= MAX_MAP_ENTRIES)
				{
					out.put("_truncated", true);
					out.put("_remainingCount", map.size() - MAX_MAP_ENTRIES);
					break;
				}
				out.put(String.valueOf(e.getKey()),
					decodeValue(e.getValue(), nextDepth, visited));
			}
			return out;
		}
		
		// Arrays
		if(value instanceof int[] ints)
		{
			if(ints.length > 512)
				return "[int[" + ints.length + "]]";
			List<Integer> list = new ArrayList<>();
			for(int i = 0; i < Math.min(ints.length, MAX_ARRAY_ITEMS); i++)
				list.add(ints[i]);
			if(ints.length > MAX_ARRAY_ITEMS)
				list.add(-1);
			return list;
		}
		if(value instanceof byte[] bytes)
		{
			if(bytes.length > MAX_BYTE_ARRAY_INLINE)
				return "[byte[" + bytes.length + "]]";
			StringBuilder sb = new StringBuilder("[");
			for(int i = 0; i < Math.min(bytes.length, MAX_ARRAY_ITEMS); i++)
			{
				if(i > 0)
					sb.append(",");
				sb.append(String.format("%02x", bytes[i] & 0xFF));
			}
			if(bytes.length > MAX_ARRAY_ITEMS)
				sb.append(
					",...(" + (bytes.length - MAX_ARRAY_ITEMS) + " more)");
			sb.append("]");
			return sb.toString();
		}
		if(value instanceof Object[] objs)
		{
			if(objs.length > 512)
				return "[Object[" + objs.length + "]]";
			List<Object> list = new ArrayList<>();
			for(int i = 0; i < Math.min(objs.length, MAX_ARRAY_ITEMS); i++)
				list.add(decodeValue(objs[i], nextDepth, visited));
			if(objs.length > MAX_ARRAY_ITEMS)
				list.add(Map.of("_truncated", true));
			return list;
		}
		if(value.getClass().isArray())
		{
			int len = java.lang.reflect.Array.getLength(value);
			if(len > 512)
				return "[array[" + len + "]]";
			List<Object> list = new ArrayList<>();
			for(int i = 0; i < Math.min(len, MAX_ARRAY_ITEMS); i++)
				list.add(decodeValue(java.lang.reflect.Array.get(value, i),
					nextDepth, visited));
			if(len > MAX_ARRAY_ITEMS)
				list.add(Map.of("_truncated", true));
			return list;
		}
		
		// Try RegistryDecoder string fallback
		String decoded = RegistryDecoder.decode(value);
		if(decoded != null)
			return decoded;
		
		// Generic record/accessor fallback
		if(depth < MAX_DEPTH - 2)
		{
			Map<String, Object> fb =
				decodeGenericObject(value, nextDepth, visited);
			if(fb != null)
				return fb;
		}
		
		// Last resort
		try
		{
			String s = String.valueOf(value);
			if(s.length() > MAX_STRING_LENGTH)
				return s.substring(0, MAX_STRING_LENGTH) + "...(truncated "
					+ (s.length() - MAX_STRING_LENGTH) + " chars)";
			return s;
		}catch(Exception e)
		{
			return "<error:" + e.getMessage() + ">";
		}
	}
	
	private static String decodeResourceKeyStr(
		net.minecraft.resources.ResourceKey<?> key)
	{
		Identifier loc = key.identifier();
		Identifier registry = key.registry();
		if(registry != null)
			return registry.getPath() + ":" + loc.getNamespace() + ":"
				+ loc.getPath();
		return loc.getNamespace() + ":" + loc.getPath();
	}
	
	private static Map<String, Object> decodeTextureProperty(Object prop)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		try
		{
			Method gn = prop.getClass().getMethod("getName");
			Method gv = prop.getClass().getMethod("getValue");
			String name = (String)gn.invoke(prop);
			String val = (String)gv.invoke(prop);
			m.put("name", name);
			m.put("length", val.length());
			if(name.equals("textures"))
				m.put("_decodedTexture", decodeTexturePayload(val));
		}catch(Exception ignored)
		{}
		return m;
	}
	
	private static Map<String, Object> decodeTexturePayload(String base64)
	{
		Map<String, Object> t = new LinkedHashMap<>();
		try
		{
			byte[] decoded = Base64.getDecoder().decode(base64);
			String json =
				new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
			var root = JsonParser.parseString(json).getAsJsonObject();
			if(root.has("timestamp"))
				t.put("timestamp", root.get("timestamp").getAsLong());
			if(root.has("profileId"))
				t.put("profileId", root.get("profileId").getAsString());
			if(root.has("profileName"))
				t.put("profileName", root.get("profileName").getAsString());
			if(root.has("textures"))
			{
				var textures = root.getAsJsonObject("textures");
				Map<String, Object> tm = new LinkedHashMap<>();
				for(String key : textures.keySet())
				{
					var to = textures.getAsJsonObject(key);
					Map<String, Object> entry = new LinkedHashMap<>();
					if(to.has("url"))
						entry.put("url", to.get("url").getAsString());
					if(to.has("metadata"))
						entry.put("metadata", to.get("metadata").toString());
					tm.put(key, entry);
				}
				t.put("textures", tm);
			}
		}catch(Exception ignored)
		{}
		return t;
	}
	
	private static Map<String, Object> decodeGenericObject(Object obj,
		int depth, IdentityHashMap<Object, Boolean> visited)
	{
		Class<?> c = obj.getClass();
		String cn = c.getName();
		if(cn.startsWith("java.lang.") || cn.startsWith("java.util.")
			|| cn.startsWith("java.io."))
			return null;
		if(cn.startsWith("net.minecraft.client.Minecraft")
			|| cn.startsWith("net.minecraft.client.multiplayer"))
			return null;
		if(c == Class.class || c == ClassLoader.class || c == Thread.class)
			return null;
		
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("_class", c.getSimpleName());
		
		int fc = 0;
		Class<?> cur = c;
		while(cur != Object.class && cur != null && cur != Record.class
			&& fc < 32)
		{
			for(Field field : cur.getDeclaredFields())
			{
				if(fc++ >= 32)
				{
					out.put("_truncated", true);
					return out;
				}
				if(Modifier.isStatic(field.getModifiers()))
					continue;
				if(field.isSynthetic())
					continue;
				field.setAccessible(true);
				try
				{
					out.put(field.getName(),
						decodeValue(field.get(obj), depth, visited));
				}catch(Exception ignored)
				{}
			}
			cur = cur.getSuperclass();
		}
		
		if(c.isRecord() && fc < 32)
		{
			for(Method method : c.getDeclaredMethods())
			{
				if(fc++ >= 32)
				{
					out.put("_truncated", true);
					return out;
				}
				if(Modifier.isStatic(method.getModifiers()))
					continue;
				if(method.getParameterCount() != 0)
					continue;
				String name = method.getName();
				if(name.equals("toString") || name.equals("hashCode")
					|| name.equals("equals"))
					continue;
				try
				{
					out.put(name,
						decodeValue(method.invoke(obj), depth, visited));
				}catch(Exception ignored)
				{}
			}
		}
		
		return fc > 0 ? out : null;
	}
	
	// ---- Entry point ----
	
	private static Object safeToString(Object value)
	{
		return decodeValue(value, 0, new IdentityHashMap<Object, Boolean>());
	}
}
