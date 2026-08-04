/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import static net.wurstclient.WurstClient.MC;

import java.security.SecureRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_feature.OtfList;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"texture rotator", "texture rotation", "no texture rotation",
	"notexturerotations", "rotation randomizer", "block rotation",
	"anti texture finder", "anti seed cracker", "seed protection"})
public final class TextureRotatorHack extends Hack implements UpdateListener
{
	/**
	 * Constant seed used in "No rotation" mode. Every block gets the same
	 * seed, so texture variants never rotate and offsets are removed.
	 */
	private static final long NO_ROTATION_SEED = 42L;
	
	public static enum Mode
	{
		RANDOM("Random"),
		CUSTOM("Custom seed"),
		NO_ROTATION("No rotation");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"Controls how block texture rotations and position offsets are handled.\n\n"
			+ "\u00a7lRandom\u00a7r generates a fresh, cryptographically secure "
			+ "random seed every time the hack is enabled, so every block in "
			+ "every chunk gets new texture variants. Blocks keep their natural "
			+ "varied look, but the rotations can no longer be used to determine "
			+ "block coordinates. The pattern is different every time you toggle "
			+ "the hack.\n\n"
			+ "\u00a7lCustom seed\u00a7r uses the seed from the \u00a76Seed\u00a7r "
			+ "setting below, giving the same rotation pattern every time. "
			+ "A seed of 0 leaves the vanilla pattern unchanged.\n\n"
			+ "\u00a7lNo rotation\u00a7r removes texture rotations and block "
			+ "position offsets entirely, so every block looks identical.",
		Mode.values(), Mode.RANDOM);
	private final TextFieldSetting customSeed = new TextFieldSetting("Seed",
		"Custom seed used in \u00a76Custom seed\u00a7r mode. Must be a whole "
			+ "number (can be negative). The same seed always produces the same "
			+ "rotation pattern, in every chunk and on every world.",
		"0", s -> {
			try
			{
				Long.parseLong(s.trim());
				return true;
			}catch(NumberFormatException e)
			{
				return false;
			}
		});
	
	private final CheckboxSetting hideFromHackList = new CheckboxSetting(
		"Hide from HackList",
		"Hides TextureRotator from the HackList HUD while the hack is active.\n"
			+ "The hack can still be toggled via the ClickGUI, keybinds or "
			+ "commands, it just won't be shown on the HUD.",
		false)
	{
		@Override
		public void update()
		{
			super.update();
			syncHiddenState();
		}
	};
	
	private final SecureRandom secureRandom = new SecureRandom();
	
	/** The active global seed that all block rotations are derived from. */
	private long seed;
	
	/** Seed-derived position offsets used to randomize flower-style offsets. */
	private int offsetX;
	private int offsetZ;
	
	/** Mode/seed values at the time {@link #seed} was last generated. */
	private Mode lastMode;
	private String lastSeedText;
	
	public TextureRotatorHack()
	{
		super("TextureRotator");
		setCategory(Category.INTEL);
		addSetting(mode);
		addSetting(customSeed);
		addSetting(hideFromHackList);
	}
	
	@Override
	protected void onEnable()
	{
		generateSeed();
		EVENTS.add(UpdateListener.class, this);
		
		// Recompile every chunk section so that all textures change
		// immediately, not just the ones nearby.
		if(MC.levelExtractor != null)
			MC.levelExtractor.allChanged();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		// Recompile every chunk section to restore the vanilla textures.
		if(MC.levelExtractor != null)
			MC.levelExtractor.allChanged();
	}
	
	@Override
	public void onUpdate()
	{
		// Apply mode and custom seed changes immediately.
		if(mode.getSelected() == lastMode
			&& customSeed.getValue().equals(lastSeedText))
			return;
		
		generateSeed();
		
		if(MC.levelExtractor != null)
			MC.levelExtractor.allChanged();
	}
	
	private void generateSeed()
	{
		switch(mode.getSelected())
		{
			case RANDOM:
			seed = secureRandom.nextLong();
			break;
			case CUSTOM:
			seed = parseSeed(customSeed.getValue());
			break;
			case NO_ROTATION:
			seed = NO_ROTATION_SEED;
			break;
		}
		
		lastMode = mode.getSelected();
		lastSeedText = customSeed.getValue();
		
		// Derive position offsets from the seed so that flower-style offsets
		// get randomized too, in a way that is stable and seed-dependent.
		offsetX = (int)((seed >>> 0) & 0xFFFF) - 0x8000;
		offsetZ = (int)((seed >>> 16) & 0xFFFF) - 0x8000;
	}
	
	public boolean isNoRotationMode()
	{
		return mode.getSelected() == Mode.NO_ROTATION;
	}
	
	public long getNoRotationSeed()
	{
		return NO_ROTATION_SEED;
	}
	
	/**
	 * Returns a seed based on the block position, mixed with the active global
	 * seed. Every block in every chunk gets a stable variant that changes
	 * whenever the global seed changes, making the effect look like a global
	 * resource pack swap.
	 */
	public long getRandomizedSeed(BlockPos pos)
	{
		return Mth.getSeed(pos.getX(), pos.getY(), pos.getZ()) ^ seed;
	}
	
	/**
	 * Shifts a position by the seed-derived offset. Used to randomize the
	 * flower-style position offsets while keeping their natural look.
	 */
	public BlockPos getRandomizedOffsetPos(BlockPos pos)
	{
		return pos.offset(offsetX, 0, offsetZ);
	}
	
	private void syncHiddenState()
	{
		try
		{
			OtfList otfs = WURST.getOtfs();
			if(otfs == null || otfs.hackListOtf == null)
				return;
			
			otfs.hackListOtf.setHidden(this, hideFromHackList.isChecked());
		}catch(Throwable ignored)
		{}
	}
	
	private static long parseSeed(String s)
	{
		try
		{
			return Long.parseLong(s.trim());
		}catch(NumberFormatException e)
		{
			return 0L;
		}
	}
}
