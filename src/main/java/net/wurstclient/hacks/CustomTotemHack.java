/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"custom totem", "custom totem of undying", "3d totem",
	"skin totem", "totem skin", "totem of undying skin", "player totem",
	"spunky totem"})
public final class CustomTotemHack extends Hack
	implements UpdateListener, PlayerAttacksEntityListener
{
	private static final Identifier TOTEM_ITEM_ID =
		Identifier.withDefaultNamespace("totem_of_undying");
	private static final Identifier SKIN_TEXTURE =
		Identifier.fromNamespaceAndPath("wurst", "custom_totem_skin");
	
	private static final String DEFAULT_TEXTURE_PATH =
		"/assets/wurst/customtotem/totem_of_undying.png";
	
	private static final int KILL_TRACK_TTL_MS = 5000;
	private static final int KILL_TRACK_MISSING_CONFIRM_MS = 1500;
	
	private static volatile boolean active;
	private static volatile String requestedSkinName = "";
	private static volatile String appliedSkinName;
	
	private final TextFieldSetting playerName = new TextFieldSetting(
		"Player name",
		"The Minecraft player whose skin will be used for the 3D totem model.\n\n"
			+ "Leave this empty to use the built-in default skin "
			+ "(\u00a76Neco-Arc\u00a7r).\n\n"
			+ "Enter any player name (e.g. \u00a76Spunky\u00a7r) and press "
			+ "\u00a76Enter\u00a7r to fetch and apply that player's skin. "
			+ "This works for any online Minecraft account.\n\n"
			+ "Changing the name while the hack is enabled will automatically "
			+ "swap the totem's skin. To also see the change in your "
			+ "inventory, use the \u00a76Refresh resource packs\u00a7r button.",
		"", s -> isValidPlayerName(s));
	
	private final CheckboxSetting autoLastKillSkin = new CheckboxSetting(
		"Auto last kill skin",
		"Automatically switches the totem to the skin of the last player you "
			+ "killed. This overrides the \u00a76Player name\u00a7r setting "
			+ "whenever you get a kill.",
		true);
	
	private final ButtonSetting refreshButton =
		new ButtonSetting("Refresh resource packs",
			WText.literal(
				"Reloads resource packs so the current totem skin is also "
					+ "reflected in your inventory. Totems in the world update "
					+ "instantly either way."),
			this::requestReload);
	
	private final Map<Integer, KillTrack> pendingKills = new HashMap<>();
	private String lastAppliedName;
	private boolean fetchInProgress;
	
	public CustomTotemHack()
	{
		super("CustomTotem",
			"Turns every totem of undying into a 3D miniature player built "
				+ "out of the skin of whichever player you choose. By default "
				+ "it uses \u00a76Neco-Arc's\u00a7r skin, but you can type any "
				+ "player name to use their skin instead.",
			false);
		setCategory(Category.FUN);
		addSetting(playerName);
		addSetting(autoLastKillSkin);
		addSetting(refreshButton);
		
		// Register once: wrap the totem's baked item model so it can swap
		// between the vanilla totem and the 3D skin totem at render time.
		ModelLoadingPlugin.register(context -> context
			.modifyItemModelAfterBake().register((model, ctx) -> {
				if(!ctx.itemId().equals(TOTEM_ITEM_ID))
					return model;
				
				return CustomTotemItemModel.wrap(model);
			}));
		
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		
		active = true;
		lastAppliedName = null;
		fetchInProgress = false;
		
		String name = normalizeName(playerName.getValue());
		requestedSkinName = name;
		if(name.isEmpty())
		{
			// No custom skin configured - show the default skin.
			applyDefaultSkin();
			return;
		}
		
		// A custom skin is configured: don't preload the default skin first.
		// The totem renders vanilla (or the previously applied custom skin)
		// until this skin finishes downloading, then swaps straight to it -
		// no pointless flash of the default skin.
		startFetch(name);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		pendingKills.clear();
		
		active = false;
		fetchInProgress = false;
	}
	
	@Override
	public void onUpdate()
	{
		checkPendingKills();
		
		String name = normalizeName(playerName.getValue());
		requestedSkinName = name;
		if(name.equals(lastAppliedName))
			return;
		
		if(name.isEmpty())
			applyDefaultSkin();
		else
			startFetch(name);
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		if(!isEnabled() || !autoLastKillSkin.isChecked())
			return;
		
		if(!(target instanceof Player player))
			return;
		
		String name = player.getGameProfile().name();
		if(name == null || name.isEmpty())
			return;
		if(MC.player != null && player == MC.player)
			return;
		
		KillTrack track = new KillTrack();
		track.playerName = name;
		track.attackedAtMs = System.currentTimeMillis();
		pendingKills.put(target.getId(), track);
	}
	
	private void checkPendingKills()
	{
		if(!isEnabled() || !autoLastKillSkin.isChecked() || MC.level == null)
		{
			pendingKills.clear();
			return;
		}
		if(pendingKills.isEmpty())
			return;
		
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, KillTrack>> it =
			pendingKills.entrySet().iterator();
		while(it.hasNext())
		{
			Map.Entry<Integer, KillTrack> entry = it.next();
			KillTrack track = entry.getValue();
			Entity entity = MC.level.getEntity(entry.getKey());
			boolean killed = false;
			
			if(entity != null)
			{
				track.sawEntity = true;
				if(!entity.isAlive())
					killed = true;
				else if(entity instanceof LivingEntity living
					&& living.getHealth() <= 0)
					killed = true;
			}else if(track.sawEntity
				&& now - track.attackedAtMs >= KILL_TRACK_MISSING_CONFIRM_MS)
				killed = true;
			
			if(killed)
			{
				applyKillSkin(track.playerName);
				it.remove();
				continue;
			}
			
			if(now - track.attackedAtMs >= KILL_TRACK_TTL_MS)
				it.remove();
		}
	}
	
	private void applyKillSkin(String name)
	{
		if(!active)
			return;
			
		// Updating the Player name field makes the normal onUpdate() logic
		// fetch and apply this skin automatically.
		if(playerName.isValidValue(name))
			playerName.setValue(name);
	}
	
	private void requestReload()
	{
		if(MC == null)
			return;
		
		MC.reloadResourcePacks();
	}
	
	private void applyDefaultSkin()
	{
		byte[] png = loadDefaultSkin();
		if(png == null)
		{
			ChatUtils.error("CustomTotem: failed to load the default skin.");
			setEnabled(false);
			return;
		}
		
		lastAppliedName = "";
		applySkin(png, "");
	}
	
	private void startFetch(String name)
	{
		if(fetchInProgress)
			return;
		
		fetchInProgress = true;
		String nameToFetch = name;
		
		CompletableFuture.runAsync(() -> {
			try
			{
				byte[] png = CustomTotemSkinFetcher.fetchSkin(nameToFetch);
				MC.execute(() -> {
					fetchInProgress = false;
					
					if(!active)
						return;
						
					// The name changed while we were fetching; the next
					// onUpdate() will fetch the new name instead.
					if(!normalizeName(playerName.getValue())
						.equals(nameToFetch))
						return;
					
					lastAppliedName = nameToFetch;
					applySkin(png, nameToFetch);
				});
				
			}catch(Exception e)
			{
				MC.execute(() -> {
					fetchInProgress = false;
					
					if(!active)
						return;
						
					// Remember the failed name so we don't retry it every
					// single tick. The previously applied skin (or the default
					// skin) keeps showing.
					lastAppliedName = nameToFetch;
					
					ChatUtils.error("CustomTotem: couldn't load skin for '"
						+ nameToFetch + "': " + e.getMessage());
				});
			}
		});
	}
	
	/**
	 * Decodes the given PNG and registers it as the totem skin texture, on the
	 * render thread. Swapping the texture is instant - no resource reload.
	 *
	 * <p>
	 * The {@link DynamicTexture} is intentionally not closed here: once
	 * registered, the
	 * {@link net.minecraft.client.renderer.texture.TextureManager}
	 * owns it and closes it when it gets replaced.
	 */
	@SuppressWarnings("resource")
	private static void applySkin(byte[] png, String appliedName)
	{
		MC.execute(() -> {
			if(!active)
				return;
			
			try
			{
				NativeImage image = NativeImage.read(png);
				DynamicTexture texture =
					new DynamicTexture(() -> "Wurst CustomTotem skin", image);
				texture.upload();
				// Registering replaces (and closes) the previous texture at
				// this ID, so there's no leak.
				MC.getTextureManager().register(SKIN_TEXTURE, texture);
				appliedSkinName = appliedName;
				
			}catch(IOException | IllegalArgumentException e)
			{
				e.printStackTrace();
			}
		});
	}
	
	private static byte[] loadDefaultSkin()
	{
		try
		{
			return loadResource(DEFAULT_TEXTURE_PATH);
			
		}catch(IOException e)
		{
			e.printStackTrace();
			return null;
		}
	}
	
	private static byte[] loadResource(String path) throws IOException
	{
		try(InputStream in = CustomTotemHack.class.getResourceAsStream(path))
		{
			if(in == null)
				throw new IOException("Resource not found: " + path);
			
			return in.readAllBytes();
		}
	}
	
	private static String normalizeName(String name)
	{
		return name == null ? "" : name.trim();
	}
	
	private static boolean isValidPlayerName(String name)
	{
		return name.length() <= 16 && name.chars()
			.allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
	}
	
	/**
	 * Whether the hack is currently enabled. Read by the
	 * {@link CustomTotemItemModel} on every rendered frame.
	 */
	public static boolean isActive()
	{
		return active;
	}
	
	/**
	 * Whether the currently registered skin texture is the one the user wants
	 * to see. Used by {@link CustomTotemItemModel} to render the vanilla totem
	 * while a custom skin is still downloading, instead of flashing the
	 * default skin or a missing texture.
	 */
	public static boolean isSkinReady()
	{
		if(!active)
			return false;
		
		String requested = requestedSkinName;
		if(requested == null || requested.isEmpty())
			return appliedSkinName != null;
		
		return requested.equals(appliedSkinName);
	}
	
	/**
	 * The texture ID under which the current totem skin is registered as a
	 * {@link DynamicTexture}.
	 */
	public static Identifier getSkinTextureId()
	{
		return SKIN_TEXTURE;
	}
	
	@Override
	public String getStatusText()
	{
		String name = normalizeName(playerName.getValue());
		return name.isEmpty() ? "Default skin" : "Skin: " + name;
	}
	
	private static final class KillTrack
	{
		private String playerName;
		private long attackedAtMs;
		private boolean sawEntity;
	}
}
