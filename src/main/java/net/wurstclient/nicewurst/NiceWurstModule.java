/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.nicewurst;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.client.font.TextRenderer.TextLayerType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.config.BuildConfig;
import net.wurstclient.hack.Hack;
import net.wurstclient.hack.HackList;
import net.wurstclient.navigator.Navigator;
import net.wurstclient.other_feature.OtfList;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.WurstRenderLayers;

public final class NiceWurstModule
{
	private static final String BRAND_LABEL = "NiceWurst 7";
	private static final String OPTIONS_LABEL = "NiceWurst 7 Options";
	
	private static final EnumSet<Category> CATEGORY_ALLOW_ALL =
		EnumSet.of(Category.FUN);
	
	private static final EnumMap<Category, Set<String>> ALLOWED_HACKS =
		new EnumMap<>(Category.class);
	private static final Set<String> ALLOWED_NAME_ONLY =
		Set.of("ClickGUI", "Navigator");
	
	private static final Set<String> HIDDEN_OTHER_FEATURES =
		Set.of("Anti-Fingerprint");
	
	private static final Set<String> DEPTH_TEST_CALLERS = Set.of(
		"net.wurstclient.hacks.BreadcrumbsHack",
		"net.wurstclient.hacks.ItemEspHack", "net.wurstclient.hacks.MobEspHack",
		"net.wurstclient.hacks.MobSearchHack",
		"net.wurstclient.hacks.PlayerEspHack",
		"net.wurstclient.hacks.PortalEspHack",
		"net.wurstclient.hacks.SearchHack",
		"net.wurstclient.hacks.TrialSpawnerEspHack",
		"net.wurstclient.hacks.TridentEspHack");
	private static final Set<String> ENTITY_OVERLAY_CALLERS =
		Set.of("net.wurstclient.hacks.MobEspHack");
	private static final Set<String> TEXT_DEPTH_TEST_CALLERS =
		Set.of("net.wurstclient.hacks.TrialSpawnerEspHack");
	private static final Set<String> TRACER_VISIBILITY_EXCEPTIONS =
		Set.of("net.wurstclient.hacks.WaypointsHack");
	
	private static boolean applied;
	
	static
	{
		ALLOWED_HACKS.put(Category.BLOCKS,
			Set.of("AutoBuild", "AutoSign", "AutoTool", "BuildRandom",
				"Excavator", "InstantBunker", "ScaffoldWalk", "TemplateTool"));
		
		ALLOWED_HACKS.put(Category.MOVEMENT, Set.of("BunnyHop", "AutoSprint",
			"AutoWalk", "AutoSwim", "Dolphin", "SafeWalk", "Sneak", "InvWalk"));
		
		ALLOWED_HACKS.put(Category.COMBAT,
			Set.of("AutoRespawn", "AutoTotem", "AutoLeave", "WindChargeKey"));
		
		ALLOWED_HACKS.put(Category.RENDER,
			Set.of("Breadcrumbs", "Fullbright", "HealthTags", "ItemESP",
				"LavaWaterESP", "LogoutSpots", "MobESP", "MobSearch",
				"MobSpawnESP", "NewChunks", "NoBackground", "NoFireOverlay",
				"NoVignette", "NoWeather", "Freecam", "OpenWaterESP",
				"PlayerESP", "PortalESP", "Radar", "Search", "TrialSpawnerESP",
				"TridentESP", "Waypoints"));
		
		ALLOWED_HACKS.put(Category.OTHER,
			Set.of("AntiAFK", "Antisocial", "AutoFish", "AutoLibrarian",
				"AutoReconnect", "AutoTrader", "CheatDetector", "ClickGUI",
				"FeedAura", "Navigator", "Panic", "PortalGUI", "SafeTP",
				"TooManyHax"));
		
		ALLOWED_HACKS.put(Category.ITEMS,
			Set.of("AntiDrop", "AutoDisenchant", "AutoDrop", "AutoEat",
				"AutoSteal", "ChestSearch", "EnchantmentHandler",
				"QuickShulker", "SignFramePT", "XCarry"));
	}
	
	private NiceWurstModule()
	{}
	
	public static boolean isActive()
	{
		return BuildConfig.NICE_WURST;
	}
	
	public static void apply(WurstClient wurst)
	{
		if(!isActive() || applied || wurst == null)
			return;
		
		applied = true;
		
		filterHackList(wurst.getHax());
		filterNavigator(wurst.getNavigator());
		filterOtherFeatures(wurst.getOtfs());
		scheduleStateCleanup(wurst);
	}
	
	public static boolean showAltManager()
	{
		return !isActive();
	}
	
	public static boolean showAntiFingerprintControls()
	{
		return !isActive();
	}
	
	public static boolean showXrayBlocksManager()
	{
		return !isActive();
	}
	
	public static String getBrandLabel(String defaultLabel)
	{
		return isActive() ? BRAND_LABEL : defaultLabel;
	}
	
	public static String getOptionsLabel(String defaultLabel)
	{
		return isActive() ? OPTIONS_LABEL : defaultLabel;
	}
	
	public static boolean enforceDepthTest(boolean originalDepthTest)
	{
		if(originalDepthTest || !isActive())
			return originalDepthTest;
		
		if(isEntityOverlayCall())
			return originalDepthTest;
		
		for(StackTraceElement element : Thread.currentThread().getStackTrace())
		{
			if(DEPTH_TEST_CALLERS.contains(element.getClassName()))
				return true;
		}
		
		return originalDepthTest;
	}
	
	public static RenderLayer.MultiPhase enforceDepthTest(
		RenderLayer.MultiPhase originalLayer)
	{
		if(!isActive() || originalLayer == null)
			return originalLayer;
		
		if(isEntityOverlayCall())
			return originalLayer;
		
		if(originalLayer == WurstRenderLayers.ESP_QUADS)
			return WurstRenderLayers.QUADS;
		
		if(originalLayer == WurstRenderLayers.ESP_QUADS_NO_CULLING)
			return WurstRenderLayers.QUADS_NO_CULLING;
		
		if(originalLayer == WurstRenderLayers.ESP_LINES)
			return WurstRenderLayers.LINES;
		
		if(originalLayer == WurstRenderLayers.ESP_LINE_STRIP)
			return WurstRenderLayers.LINE_STRIP;
		
		return originalLayer;
	}
	
	public static boolean shouldEnforceTracerVisibility()
	{
		if(!isActive())
			return false;
		
		for(StackTraceElement element : Thread.currentThread().getStackTrace())
		{
			if(TRACER_VISIBILITY_EXCEPTIONS.contains(element.getClassName()))
				return false;
		}
		
		return true;
	}
	
	public static boolean shouldRenderTarget(Vec3d target)
	{
		if(!isActive() || target == null)
			return true;
		
		if(WurstClient.MC.world == null || WurstClient.MC.player == null)
			return true;
		
		Camera camera = WurstClient.MC.gameRenderer.getCamera();
		if(camera == null)
			return true;
		
		Vec3d from = camera.getPos();
		if(from == null)
			return true;
		
		if(from.squaredDistanceTo(target) < 1e-6)
			return true;
		
		RaycastContext context =
			new RaycastContext(from, target, RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, WurstClient.MC.player);
		HitResult hit = WurstClient.MC.world.raycast(context);
		if(hit == null || hit.getType() == HitResult.Type.MISS)
			return true;
		
		if(hit.getType() != HitResult.Type.BLOCK)
			return true;
		
		BlockPos hitPos = ((BlockHitResult)hit).getBlockPos();
		BlockPos targetPos = BlockPos.ofFloored(target);
		if(hitPos.equals(targetPos))
			return true;
		
		double targetDistSq = from.squaredDistanceTo(target);
		double hitDistSq = hit.getPos().squaredDistanceTo(from);
		return hitDistSq >= targetDistSq - 1e-3;
	}
	
	public static Integer filterGlowColor(LivingEntity entity, Integer color)
	{
		if(color == null || !isActive())
			return color;
		if(entity == null)
			return color;
		
		Vec3d target = entity.getBoundingBox().getCenter();
		return shouldRenderTarget(target) ? color : null;
	}
	
	public static TextLayerType enforceTextLayer(TextLayerType originalLayer)
	{
		if(originalLayer == null || !isActive())
			return originalLayer;
		
		if(originalLayer != TextLayerType.SEE_THROUGH)
			return originalLayer;
		
		for(StackTraceElement element : Thread.currentThread().getStackTrace())
		{
			if(TEXT_DEPTH_TEST_CALLERS.contains(element.getClassName()))
				return TextLayerType.NORMAL;
		}
		
		return originalLayer;
	}
	
	public static boolean shouldOverlayEntityShapes()
	{
		return isActive() && isEntityOverlayCall();
	}
	
	private static boolean isEntityOverlayCall()
	{
		for(StackTraceElement element : Thread.currentThread().getStackTrace())
		{
			if(ENTITY_OVERLAY_CALLERS.contains(element.getClassName()))
				return true;
		}
		
		return false;
	}
	
	private static void filterHackList(HackList hackList)
	{
		TreeMap<String, Hack> hackMap = getHackMap(hackList);
		Iterator<Map.Entry<String, Hack>> iterator =
			hackMap.entrySet().iterator();
		while(iterator.hasNext())
		{
			Hack hack = iterator.next().getValue();
			if(isHackAllowed(hack))
				continue;
			
			hack.setEnabled(false);
			if(hack.isFavorite())
				hack.setFavorite(false);
			iterator.remove();
		}
	}
	
	private static void filterNavigator(Navigator navigator)
	{
		ArrayList<Feature> list = getNavigatorList(navigator);
		list.removeIf(feature -> feature instanceof Hack
			&& !isHackAllowed((Hack)feature));
		list.removeIf(feature -> feature instanceof OtherFeature
			&& HIDDEN_OTHER_FEATURES.contains(feature.getName()));
	}
	
	private static void filterOtherFeatures(OtfList otfList)
	{
		TreeMap<String, OtherFeature> featureMap = getOtherFeatureMap(otfList);
		Iterator<Map.Entry<String, OtherFeature>> iterator =
			featureMap.entrySet().iterator();
		while(iterator.hasNext())
		{
			OtherFeature feature = iterator.next().getValue();
			if(!HIDDEN_OTHER_FEATURES.contains(feature.getName()))
				continue;
			
			iterator.remove();
		}
	}
	
	private static void scheduleStateCleanup(WurstClient wurst)
	{
		wurst.getEventManager().add(UpdateListener.class, new UpdateListener()
		{
			private int ticks;
			
			@Override
			public void onUpdate()
			{
				if(++ticks < 2)
					return;
				
				wurst.getHax().saveEnabledHax();
				wurst.getHax().saveFavoriteHax();
				wurst.getEventManager().remove(UpdateListener.class, this);
			}
		});
	}
	
	private static boolean isHackAllowed(Hack hack)
	{
		Category category = hack.getCategory();
		String name = hack.getName();
		if(ALLOWED_NAME_ONLY.contains(name))
			return true;
		
		if(category != null && CATEGORY_ALLOW_ALL.contains(category))
			return true;
		
		Set<String> allowed = category == null ? Set.of()
			: ALLOWED_HACKS.getOrDefault(category, Set.of());
		
		return allowed.contains(name);
	}
	
	@SuppressWarnings("unchecked")
	private static TreeMap<String, Hack> getHackMap(HackList hackList)
	{
		try
		{
			Field field = HackList.class.getDeclaredField("hax");
			field.setAccessible(true);
			return (TreeMap<String, Hack>)field.get(hackList);
			
		}catch(ReflectiveOperationException e)
		{
			throw new IllegalStateException("Unable to access HackList map.",
				e);
		}
	}
	
	@SuppressWarnings("unchecked")
	private static ArrayList<Feature> getNavigatorList(Navigator navigator)
	{
		try
		{
			Field field = Navigator.class.getDeclaredField("navigatorList");
			field.setAccessible(true);
			return (ArrayList<Feature>)field.get(navigator);
			
		}catch(ReflectiveOperationException e)
		{
			throw new IllegalStateException("Unable to access Navigator list.",
				e);
		}
	}
	
	@SuppressWarnings("unchecked")
	private static TreeMap<String, OtherFeature> getOtherFeatureMap(
		OtfList otfList)
	{
		try
		{
			Field field = OtfList.class.getDeclaredField("otfs");
			field.setAccessible(true);
			return (TreeMap<String, OtherFeature>)field.get(otfList);
			
		}catch(ReflectiveOperationException e)
		{
			throw new IllegalStateException(
				"Unable to access OtherFeature map.", e);
		}
	}
	
	public static boolean isHackEnabled(String name, Category category)
	{
		if(!isActive())
			return true;
		
		name = name == null ? "" : name;
		Category cat = category;
		
		if(cat != null && CATEGORY_ALLOW_ALL.contains(cat))
			return true;
		
		Set<String> allowed =
			cat == null ? Set.of() : ALLOWED_HACKS.getOrDefault(cat, Set.of());
		return allowed.contains(name);
	}
	
	public static List<String> getAllowedHackNames(Category category)
	{
		if(!isActive())
			return List.of();
		
		if(category != null && CATEGORY_ALLOW_ALL.contains(category))
			return List.of(); // All allowed, caller does not need filter list.
			
		Set<String> allowed = category == null ? Set.of()
			: ALLOWED_HACKS.getOrDefault(category, Set.of());
		return allowed.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
	}
}
