/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.nukers;

import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.WurstClient;
import net.wurstclient.events.LeftClickListener;
import net.wurstclient.hacks.nukers.NukerModeSetting.NukerMode;
import net.wurstclient.hacks.nukers.NukerShapeSetting.NukerShape;
import net.wurstclient.settings.BlockSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.BlockUtils;

public final class CommonNukerSettings implements LeftClickListener
{
	private static final Minecraft MC = WurstClient.MC;
	
	private final NukerShapeSetting shape = new NukerShapeSetting();
	
	private final CheckboxSetting flat = new CheckboxSetting("Flat mode",
		"Won't break any blocks below your feet.", false);
	
	private final NukerModeSetting mode = new NukerModeSetting();
	
	private final BlockSetting id =
		new BlockSetting("ID", "The type of block to break in ID mode.\n"
			+ "air = won't break anything", "minecraft:air", true);
	
	private final CheckboxSetting lockId = new CheckboxSetting("Lock ID",
		"Prevents changing the ID by clicking on blocks or restarting the hack.",
		false);
	
	private final NukerMultiIdListSetting multiIdList =
		new NukerMultiIdListSetting();
	
	private final CheckboxSetting avoidSuspicious = new CheckboxSetting(
		"Skip blocks covered by suspicious dust",
		"Stops breaking any block that has suspicious sand or gravel at or above it (blocks beneath the dust).",
		false);
	
	public Stream<Setting> getSettings()
	{
		return Stream.of(shape, flat, mode, id, lockId, multiIdList,
			avoidSuspicious);
	}
	
	public void reset()
	{
		if(!lockId.isChecked())
			id.setBlock(Blocks.AIR);
	}
	
	public String getRenderNameSuffix()
	{
		return switch(mode.getSelected())
		{
			case ID -> " [ID:" + id.getShortBlockName() + "]";
			case MULTI_ID -> " [MultiID:" + multiIdList.size() + "]";
			case SMASH -> " [Smash]";
			default -> "";
		};
	}
	
	public boolean isIdModeWithAir()
	{
		return mode.getSelected() == NukerMode.ID
			&& id.getBlock() == Blocks.AIR;
	}
	
	public boolean isSphereShape()
	{
		return shape.getSelected() == NukerShape.SPHERE;
	}
	
	public boolean shouldBreakBlock(BlockPos pos)
	{
		if(flat.isChecked() && pos.getY() < MC.player.getY())
			return false;
		
		if(avoidSuspicious.isChecked() && hasSuspiciousAbove(pos))
			return false;
		
		switch(mode.getSelected())
		{
			default:
			case NORMAL:
			return true;
			
			case ID:
			return BlockUtils.getName(pos).equals(id.getBlockName());
			
			case MULTI_ID:
			return multiIdList.matchesBlock(BlockUtils.getBlock(pos));
			
			case SMASH:
			return BlockUtils.getHardness(pos) >= 1;
		}
	}
	
	public boolean shouldAttackEntity(Entity entity)
	{
		if(entity == null || mode.getSelected() != NukerMode.MULTI_ID)
			return false;
		
		if(entity instanceof GlowItemFrame)
			return hasEntityId("glow_item_frame");
		
		if(entity instanceof ItemFrame)
			return hasEntityId("item_frame");
		
		if(entity.getType() == EntityType.PAINTING)
			return hasEntityId("painting");
		
		return false;
	}
	
	private boolean hasEntityId(String idPath)
	{
		String canonical = "minecraft:" + idPath;
		String plural = idPath + "s";
		String spaced = idPath.replace('_', ' ');
		String spacedPlural = spaced + "s";
		
		return multiIdList.getBlockNames().stream()
			.map(CommonNukerSettings::normalizeEntityToken)
			.anyMatch(token -> token.equals(idPath) || token.equals(canonical)
				|| token.equals(plural) || token.equals(spaced)
				|| token.equals(spacedPlural));
	}
	
	private static String normalizeEntityToken(String token)
	{
		if(token == null)
			return "";
		
		return token.trim().toLowerCase(java.util.Locale.ROOT).replace('-',
			'_');
	}
	
	private boolean hasSuspiciousAbove(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		
		BlockPos check = pos;
		for(int i = 0; i < 32; i++)
		{
			BlockState state = MC.level.getBlockState(check);
			if(state.is(Blocks.SUSPICIOUS_SAND)
				|| state.is(Blocks.SUSPICIOUS_GRAVEL))
			{
				return true;
			}
			
			check = check.above();
		}
		
		return false;
	}
	
	@Override
	public void onLeftClick(LeftClickEvent event)
	{
		if(lockId.isChecked() || mode.getSelected() != NukerMode.ID)
			return;
		
		if(!(MC.hitResult instanceof BlockHitResult bHitResult)
			|| bHitResult.getType() != HitResult.Type.BLOCK)
			return;
		
		id.setBlockName(BlockUtils.getName(bHitResult.getBlockPos()));
	}
}
