/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"clutch fall", "fall clutch", "water clutch", "MLG"})
public final class ClutchFallHack extends Hack implements UpdateListener
{
	private static final double VANILLA_PLACE_DISTANCE = 4.25;
	private boolean attemptedThisFall;
	private int dangerousFallTicks;
	private final CheckboxSetting useReach = new CheckboxSetting("Use Reach",
		"description.wurst.setting.clutchfall.use_reach", false);
	private final CheckboxSetting autoAim = new CheckboxSetting("Auto aim",
		"description.wurst.setting.clutchfall.auto_aim", true);
	private final SliderSetting minimumFallDistance =
		new SliderSetting("Minimum fall distance",
			"description.wurst.setting.clutchfall.minimum_fall_distance", 2.5,
			0.5, 20, 0.5, ValueDisplay.DECIMAL);
	private final SliderSetting placementDelay =
		new SliderSetting("Placement delay",
			"description.wurst.setting.clutchfall.placement_delay", 0, 0, 5, 1,
			ValueDisplay.INTEGER);
	private final CheckboxSetting hotbarOnly =
		new CheckboxSetting("Hotbar only",
			"description.wurst.setting.clutchfall.hotbar_only", false);
	private final CheckboxSetting voidOnly = new CheckboxSetting("Void only",
		"description.wurst.setting.clutchfall.void_only", false);
	private final CheckboxSetting skipFluids =
		new CheckboxSetting("Skip water and lava",
			"description.wurst.setting.clutchfall.skip_fluids", true);
	private final CheckboxSetting useWaterBucket =
		new CheckboxSetting("Water buckets",
			"description.wurst.setting.clutchfall.water_bucket", true);
	private final CheckboxSetting usePowderSnowBucket =
		new CheckboxSetting("Powder snow buckets",
			"description.wurst.setting.clutchfall.powder_snow_bucket", true);
	private final CheckboxSetting useSlimeBlocks =
		new CheckboxSetting("Slime blocks",
			"description.wurst.setting.clutchfall.slime_block", true);
	private final CheckboxSetting useCobwebs = new CheckboxSetting("Cobwebs",
		"description.wurst.setting.clutchfall.cobweb", true);
	private final CheckboxSetting useSweetBerries =
		new CheckboxSetting("Sweet berries",
			"description.wurst.setting.clutchfall.sweet_berries", true);
	private final CheckboxSetting useHayBlocks = new CheckboxSetting(
		"Hay blocks", "description.wurst.setting.clutchfall.hay_block", true);
	private final CheckboxSetting useHoneyBlocks =
		new CheckboxSetting("Honey blocks",
			"description.wurst.setting.clutchfall.honey_block", true);
	private final CheckboxSetting useBeds = new CheckboxSetting("Beds",
		"description.wurst.setting.clutchfall.bed", true);
	private final EnumSetting<PreferredItem> preferredItem = new EnumSetting<>(
		"Preferred item", "description.wurst.setting.clutchfall.preferred_item",
		PreferredItem.values(), PreferredItem.AUTO);
	private final List<Candidate> candidates = List.of(
		new Candidate(PreferredItem.WATER_BUCKET, ClutchFallHack::isWaterBucket,
			useWaterBucket),
		new Candidate(PreferredItem.POWDER_SNOW_BUCKET,
			stack -> stack.is(Items.POWDER_SNOW_BUCKET), usePowderSnowBucket),
		new Candidate(PreferredItem.SLIME_BLOCK,
			stack -> stack.is(Items.SLIME_BLOCK), useSlimeBlocks),
		new Candidate(PreferredItem.COBWEB, stack -> stack.is(Items.COBWEB),
			useCobwebs),
		new Candidate(PreferredItem.SWEET_BERRIES,
			stack -> stack.is(Items.SWEET_BERRIES), useSweetBerries),
		new Candidate(PreferredItem.HAY_BLOCK,
			stack -> stack.is(Items.HAY_BLOCK), useHayBlocks),
		new Candidate(PreferredItem.HONEY_BLOCK,
			stack -> stack.is(Items.HONEY_BLOCK), useHoneyBlocks),
		new Candidate(PreferredItem.BED,
			stack -> stack.getItem() instanceof BedItem, useBeds));
	
	public ClutchFallHack()
	{
		super("ClutchFall");
		setCategory(Category.MOVEMENT);
		addSetting(useReach);
		addSetting(autoAim);
		addSetting(minimumFallDistance);
		addSetting(placementDelay);
		addSetting(hotbarOnly);
		addSetting(voidOnly);
		addSetting(skipFluids);
		addSetting(preferredItem);
		addSetting(useWaterBucket);
		addSetting(usePowderSnowBucket);
		addSetting(useSlimeBlocks);
		addSetting(useCobwebs);
		addSetting(useSweetBerries);
		addSetting(useHayBlocks);
		addSetting(useHoneyBlocks);
		addSetting(useBeds);
	}
	
	@Override
	protected void onEnable()
	{
		attemptedThisFall = false;
		dangerousFallTicks = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		attemptedThisFall = false;
		dangerousFallTicks = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		if(MC.player.onGround() || MC.player.getDeltaMovement().y >= -0.02
			|| MC.player.isFallFlying() || MC.player.isPassenger())
		{
			attemptedThisFall = false;
			dangerousFallTicks = 0;
			return;
		}
		
		if(attemptedThisFall)
			return;
		// A normal jump briefly has negative velocity too. Do not touch the
		// selected item until the fall is long enough to be dangerous.
		if(MC.player.fallDistance < minimumFallDistance.getValueF())
		{
			dangerousFallTicks = 0;
			return;
		}
		dangerousFallTicks++;
		if(dangerousFallTicks <= placementDelay.getValueI())
			return;
		
		BlockPos target = findLandingTarget();
		if(target == null)
			return;
		
		Candidate candidate = findAvailableCandidate(target);
		if(candidate == null)
			return;
		
		int maxSlot = hotbarOnly.isChecked() ? 9 : 36;
		int slot = InventoryUtils.indexOf(candidate.predicate(), maxSlot,
			!hotbarOnly.isChecked());
		if(slot < 0)
			return;
		
		if(!candidate.predicate().test(MC.player.getMainHandItem()))
		{
			// This also swaps an item from the main inventory into the selected
			// hotbar slot. The actual placement waits for the next update.
			InventoryUtils.selectItem(slot);
			return;
		}
		
		boolean waterBucket = isWaterBucket(MC.player.getMainHandItem());
		boolean sweetBerries =
			MC.player.getMainHandItem().is(Items.SWEET_BERRIES);
		BlockHitResult hit;
		if(waterBucket || sweetBerries)
			hit = getWaterPlacement(target.below());
		else
		{
			BlockPlacer.BlockPlacingParams placement =
				BlockPlacer.getBlockPlacingParams(target);
			if(placement == null)
				return;
			hit = placement.toHitResult();
		}
		
		if(RotationUtils.getEyesPos()
			.distanceToSqr(hit.getLocation()) > getMaxPlaceDistanceSq())
			return;
		
		float oldYaw = MC.player.getYRot();
		float oldPitch = MC.player.getXRot();
		try
		{
			if(autoAim.isChecked())
			{
				Rotation needed =
					RotationUtils.getNeededRotations(hit.getLocation());
				// sendPlayerLookPacket() only updates the server's rotation.
				// The
				// local game-mode interaction also needs the temporary client
				// rotation, otherwise buckets work only while looking down.
				needed.applyToClientPlayer();
				needed.sendPlayerLookPacket();
				MC.hitResult = hit;
			}
			
			InteractionResult result = MC.gameMode.useItemOn(MC.player,
				InteractionHand.MAIN_HAND, hit);
			// Some client prediction paths return PASS even though the normal
			// right-click flow continues with an item-use packet.
			if(!result.consumesAction())
				result =
					MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
			if(result.consumesAction())
				MC.player.swing(InteractionHand.MAIN_HAND);
			// Do not spam use packets every tick if the server returns PASS.
			// The next fall gets a fresh attempt.
			attemptedThisFall = true;
		}finally
		{
			if(autoAim.isChecked())
			{
				MC.player.setYRot(oldYaw);
				MC.player.setXRot(oldPitch);
			}
		}
	}
	
	private static BlockHitResult getWaterPlacement(BlockPos support)
	{
		// Water buckets and sweet berries both need the top face of the
		// supporting block. The generic BlockPlacer may choose a side face,
		// which is valid for ordinary blocks but rejected by SweetBerriesItem.
		return new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0),
			Direction.UP, support, false);
	}
	
	private BlockPos findLandingTarget()
	{
		BlockPos feet = BlockPos.containing(MC.player.getX(),
			MC.player.getBoundingBox().minY - 0.01, MC.player.getZ());
		for(int y = feet.getY(); y >= MC.level.getMinY(); y--)
		{
			BlockPos support = new BlockPos(feet.getX(), y, feet.getZ());
			if(MC.level.getBlockState(support).canBeReplaced())
				continue;
			
			BlockPos target = support.above();
			if(!MC.level.getBlockState(target).canBeReplaced())
				return null;
			if(voidOnly.isChecked() && support.getY() > MC.level.getMinY())
				return null;
			if(skipFluids.isChecked())
			{
				var fluid = MC.level.getFluidState(support);
				if(fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA))
					return null;
			}
			return target;
		}
		return null;
	}
	
	private static boolean isWaterBucket(ItemStack stack)
	{
		return stack.getItem() instanceof BucketItem bucket
			&& bucket.getContent() instanceof WaterFluid;
	}
	
	private Candidate findAvailableCandidate(BlockPos target)
	{
		BlockState support = MC.level.getBlockState(target.below());
		for(Candidate candidate : orderedCandidates())
		{
			if(!candidate.setting().isChecked())
				continue;
			int maxSlot = hotbarOnly.isChecked() ? 9 : 36;
			if(InventoryUtils.indexOf(candidate.predicate(), maxSlot,
				!hotbarOnly.isChecked()) >= 0)
				return candidate;
		}
		return null;
	}
	
	private List<Candidate> orderedCandidates()
	{
		if(preferredItem.getSelected() == PreferredItem.AUTO)
			return candidates;
		return candidates.stream()
			.sorted((a, b) -> Boolean.compare(
				b.type() == preferredItem.getSelected(),
				a.type() == preferredItem.getSelected()))
			.toList();
	}
	
	private double getMaxPlaceDistanceSq()
	{
		double reach =
			useReach.isChecked() ? WURST.getHax().reachHack.getReachDistance()
				: VANILLA_PLACE_DISTANCE;
		return reach * reach;
	}
	
	private record Candidate(PreferredItem type, Predicate<ItemStack> predicate,
		CheckboxSetting setting)
	{
		private Candidate(PreferredItem type, Predicate<ItemStack> predicate,
			CheckboxSetting setting)
		{
			this.type = type;
			this.predicate = predicate;
			this.setting = setting;
		}
	}
	
	private enum PreferredItem
	{
		AUTO("Automatic"),
		WATER_BUCKET("Water bucket"),
		POWDER_SNOW_BUCKET("Powder snow bucket"),
		SLIME_BLOCK("Slime block"),
		COBWEB("Cobweb"),
		SWEET_BERRIES("Sweet berries"),
		HAY_BLOCK("Hay block"),
		HONEY_BLOCK("Honey block"),
		BED("Bed");
		
		private final String name;
		
		PreferredItem(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
