/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.state.property.Properties;

import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestConfig;
import net.wurstclient.chestsearch.ChestRecorder;
import net.wurstclient.clickgui.screens.ChestSearchScreen;
import net.wurstclient.hacks.AutoStealHack;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;

@Mixin(GenericContainerScreen.class)
public abstract class GenericContainerScreenMixin
	extends HandledScreen<GenericContainerScreenHandler>
{
	@Shadow
	@Final
	private int rows;
	
	@Unique
	private final AutoStealHack autoSteal =
		WurstClient.INSTANCE.getHax().autoStealHack;
	
	@Unique
	private ChestRecorder chestRecorder;
	@Unique
	private volatile boolean manualScanActive = false;
	@Unique
	private volatile long manualScanUntil = 0L;
	@Unique
	private volatile int lastRecordedNonEmpty = 0;
	@Unique
	private int chestX = 0, chestY = 0, chestZ = 0;
	@Unique
	private int clickedX = 0, clickedY = 0, clickedZ = 0;
	@Unique
	private java.util.List<Integer> chestSlotOrder =
		java.util.Collections.emptyList();
	@Unique
	private BlockPos[] chestDoubleBounds = null;
	@Unique
	private String chestFacing = null;
	@Unique
	private boolean manualScanQuiet = false;
	@Unique
	private String lastRecordMessage = null;
	@Unique
	private long lastRecordUntilMs = 0L;
	
	public GenericContainerScreenMixin(WurstClient wurst,
		GenericContainerScreenHandler container,
		PlayerInventory playerInventory, Text name)
	{
		super(container, playerInventory, name);
	}
	
	@Override
	public void init()
	{
		super.init();
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(autoSteal.areButtonsVisible())
		{
			addDrawableChild(ButtonWidget
				.builder(Text.literal("Steal"),
					b -> autoSteal.steal(this, rows))
				.dimensions(x + backgroundWidth - 108, y + 4, 50, 12).build());
			addDrawableChild(ButtonWidget
				.builder(Text.literal("Store"),
					b -> autoSteal.store(this, rows))
				.dimensions(x + backgroundWidth - 56, y + 4, 50, 12).build());
		}
		if(autoSteal.isEnabled())
			autoSteal.steal(this, rows);
			
		// Add a manual scan button to help on plugin-protected servers when
		// chest search is in manual mode. The toggle is provided in the
		// ChestSearch hack settings.
		try
		{
			net.wurstclient.hacks.ChestSearchHack csh =
				WurstClient.INSTANCE.getHax().chestSearchHack;
			boolean showScan = true;
			try
			{
				if(csh != null)
					showScan = !csh.isAutomaticMode();
			}catch(Throwable ignored)
			{}
			if(showScan)
				addDrawableChild(ButtonWidget
					.builder(Text.literal("Scan"), b -> startManualScan())
					.dimensions(x + 6, y + 4, 50, 12).build());
		}catch(Throwable ignored)
		{}
		
		// Attempt to record the chest contents when the container screen opens.
		try
		{
			ChestConfig cfg = new ChestConfig();
			if(!cfg.enabled)
				return;
			
			this.chestSlotOrder = java.util.Collections.emptyList();
			
			// ensure recorder uses same config (so enabled/dbPath match)
			this.chestRecorder =
				new ChestRecorder(new java.io.File(cfg.dbPath), cfg);
			
			String serverIp = null;
			try
			{
				if(WurstClient.MC != null
					&& WurstClient.MC.getCurrentServerEntry() != null)
					serverIp = WurstClient.MC.getCurrentServerEntry().address;
			}catch(Throwable ignored)
			{}
			String dimension = null;
			try
			{
				if(WurstClient.MC != null && WurstClient.MC.world != null)
					dimension = WurstClient.MC.world.getRegistryKey().getValue()
						.toString();
			}catch(Throwable ignored)
			{}
			
			BlockPos resolvedPos = null;
			// debug removed: resolvedFrom
			// Try block entity on handler
			try
			{
				java.lang.reflect.Method gb =
					this.handler.getClass().getMethod("getBlockEntity");
				Object be = gb.invoke(this.handler);
				if(be instanceof BlockEntity)
				{
					resolvedPos = ((BlockEntity)be).getPos();
					if(resolvedPos != null)
					{
						// debug removed
					}
				}
			}catch(Throwable ignored)
			{}
			
			// If handler gave a pos, prefer it and skip other positional
			// fallbacks
			if(resolvedPos == null)
			{
				// crosshair fallback
				try
				{
					HitResult hr = WurstClient.MC.crosshairTarget;
					if(hr instanceof BlockHitResult bhr)
					{
						BlockPos bp = bhr.getBlockPos();
						if(bp != null)
						{
							resolvedPos = bp;
							// debug removed
						}
					}
				}catch(Throwable ignored)
				{}
			}
			
			// handler context fallback
			if(resolvedPos == null)
			{
				try
				{
					Object ctxObj = null;
					try
					{
						java.lang.reflect.Method m =
							this.handler.getClass().getMethod("getContext");
						ctxObj = m.invoke(this.handler);
					}catch(Throwable ignored)
					{}
					if(ctxObj == null)
					{
						try
						{
							java.lang.reflect.Field f = this.handler.getClass()
								.getDeclaredField("context");
							f.setAccessible(true);
							ctxObj = f.get(this.handler);
						}catch(Throwable ignored)
						{}
					}
					if(ctxObj instanceof ScreenHandlerContext ctx)
					{
						BlockPos[] holder = new BlockPos[1];
						ctx.run((world, pos) -> holder[0] = pos);
						if(holder[0] != null)
						{
							resolvedPos = holder[0];
							// debug removed
						}
					}
				}catch(Throwable ignored)
				{}
			}
			
			// inventory pos fallback
			if(resolvedPos == null)
			{
				try
				{
					Object inv = this.handler.getClass()
						.getMethod("getInventory").invoke(this.handler);
					if(inv != null)
					{
						try
						{
							Object pos =
								inv.getClass().getMethod("getPos").invoke(inv);
							if(pos != null)
							{
								int ix = (int)pos.getClass().getMethod("getX")
									.invoke(pos);
								int iy = (int)pos.getClass().getMethod("getY")
									.invoke(pos);
								int iz = (int)pos.getClass().getMethod("getZ")
									.invoke(pos);
								resolvedPos = new BlockPos(ix, iy, iz);
								// debug removed
							}
						}catch(Exception e2)
						{
							try
							{
								java.lang.reflect.Field f =
									inv.getClass().getDeclaredField("pos");
								f.setAccessible(true);
								Object pos = f.get(inv);
								if(pos != null)
								{
									int ix = (int)pos.getClass()
										.getMethod("getX").invoke(pos);
									int iy = (int)pos.getClass()
										.getMethod("getY").invoke(pos);
									int iz = (int)pos.getClass()
										.getMethod("getZ").invoke(pos);
									resolvedPos = new BlockPos(ix, iy, iz);
									// debug removed
								}
							}catch(Exception ignored)
							{}
						}
					}
				}catch(Throwable ignored)
				{}
			}
			
			// If still unknown, scan nearby around player for chest block
			// entities (radius 2)
			if(resolvedPos == null)
			{
				// First, try a precise raycast along the player's look vector
				resolvedPos = wurst$raycastContainer();
			}
			if(resolvedPos == null)
			{
				// Fallback: scan nearby but bias toward look direction and
				// distance
				try
				{
					if(WurstClient.MC != null && WurstClient.MC.player != null
						&& WurstClient.MC.world != null)
					{
						var player = WurstClient.MC.player;
						net.minecraft.util.math.Vec3d eye =
							player.getCameraPosVec(1.0f);
						net.minecraft.util.math.Vec3d look =
							player.getRotationVec(1.0f).normalize();
						BlockPos pcenter = player.getBlockPos();
						BlockPos best = null;
						double bestScore = -1e9;
						for(int dx = -2; dx <= 2; dx++)
							for(int dy = -1; dy <= 2; dy++)
								for(int dz = -2; dz <= 2; dz++)
								{
									BlockPos c = pcenter.add(dx, dy, dz);
									BlockState s =
										WurstClient.MC.world.getBlockState(c);
									if(!(s.getBlock() instanceof ChestBlock))
										continue;
									net.minecraft.util.math.Vec3d center =
										new net.minecraft.util.math.Vec3d(
											c.getX() + 0.5, c.getY() + 0.5,
											c.getZ() + 0.5);
									double dist = center.squaredDistanceTo(eye);
									double align = look.dotProduct(
										center.subtract(eye).normalize());
									double score = 2.0 - dist * 0.01
										+ Math.max(0, align) * 0.5;
									if(score > bestScore)
									{
										bestScore = score;
										best = c;
									}
								}
						if(best != null)
						{
							resolvedPos = best;
							// debug removed
						}
					}
				}catch(Throwable ignored)
				{}
			}
			
			if(resolvedPos != null)
			{
				this.clickedX = resolvedPos.getX();
				this.clickedY = resolvedPos.getY();
				this.clickedZ = resolvedPos.getZ();
				resolvedPos = wurst$normalizeContainerPos(resolvedPos);
			}
			
			final String fServerIp = serverIp;
			final String fDimension = dimension;
			final int fx = resolvedPos == null ? 0 : resolvedPos.getX();
			final int fy = resolvedPos == null ? 0 : resolvedPos.getY();
			final int fz = resolvedPos == null ? 0 : resolvedPos.getZ();
			this.chestX = fx;
			this.chestY = fy;
			this.chestZ = fz;
			if(resolvedPos != null)
			{
				// debug removed
				try
				{
					// Clear any ESP/waypoint pinned at the exact clicked block
					// so overlays are removed when the user opens the chest.
					BlockPos clickedPos = new BlockPos(this.clickedX,
						this.clickedY, this.clickedZ);
					boolean cleared = ChestSearchScreen
						.clearDecorations(fDimension, clickedPos);
					if(!cleared)
					{
						// Also try canonical normalized position as a fallback.
						ChestSearchScreen.clearDecorations(fDimension,
							resolvedPos);
					}
				}catch(Throwable ignored)
				{}
			}
			final int chestSlots = Math.max(0, rows * 9);
			// prepare slot order in outer scope so fallback branches can reuse
			java.util.List<Integer> chestSlotIndices =
				new java.util.ArrayList<>();
			// If we found a block entity, try to read client-side inventory
			// immediately
			try
			{
				if(fx != 0 || fy != 0 || fz != 0)
				{
					// Prefer reading from handler.slots (client-side view)
					try
					{
						java.util.List<net.minecraft.item.ItemStack> all =
							new java.util.ArrayList<>();
						for(int i = 0; i < this.handler.slots.size(); i++)
						{
							try
							{
								all.add(this.handler.slots.get(i).getStack());
							}catch(Throwable ignored)
							{
								all.add(net.minecraft.item.ItemStack.EMPTY);
							}
						}
						// determine candidate slots that belong to chest (not
						// player inventory)
						java.util.List<Integer> chestSlotIndicesTemp =
							new java.util.ArrayList<>();
						Object playerInvObj = null;
						try
						{
							if(WurstClient.MC != null
								&& WurstClient.MC.player != null)
							{
								try
								{
									java.lang.reflect.Method m =
										WurstClient.MC.player.getClass()
											.getMethod("getInventory");
									playerInvObj =
										m.invoke(WurstClient.MC.player);
								}catch(Throwable e1)
								{
									try
									{
										java.lang.reflect.Field f =
											WurstClient.MC.player.getClass()
												.getDeclaredField("inventory");
										f.setAccessible(true);
										playerInvObj =
											f.get(WurstClient.MC.player);
									}catch(Throwable ignored)
									{}
								}
							}
						}catch(Throwable ignored)
						{}
						for(int i = 0; i < this.handler.slots.size(); i++)
						{
							try
							{
								Object slot = this.handler.slots.get(i);
								Class<?> sc = slot.getClass();
								Object inv = null;
								// strategy 1: field named "inventory"
								try
								{
									java.lang.reflect.Field f =
										sc.getDeclaredField("inventory");
									f.setAccessible(true);
									inv = f.get(slot);
								}catch(Throwable ignoredField)
								{}
								// strategy 2: method getInventory()
								if(inv == null)
								{
									try
									{
										java.lang.reflect.Method m =
											sc.getMethod("getInventory");
										inv = m.invoke(slot);
									}catch(Throwable ignoredMethod)
									{}
								}
								// strategy 3: field named "inv" or "handler"
								if(inv == null)
								{
									try
									{
										java.lang.reflect.Field f2 =
											sc.getDeclaredField("inv");
										f2.setAccessible(true);
										inv = f2.get(slot);
									}catch(Throwable ignored2)
									{}
								}
								// strategy 4: try to obtain via
								// slot.getInventory() (common in mappings)
								if(inv == null)
								{
									try
									{
										java.lang.reflect.Method m2 =
											sc.getMethod("getInventory");
										inv = m2.invoke(slot);
									}catch(Throwable ignored3)
									{}
								}
								boolean isPlayerInv = false;
								try
								{
									if(inv != null && playerInvObj != null
										&& inv == playerInvObj)
										isPlayerInv = true;
									else if(inv != null
										&& inv.getClass().getName()
											.toLowerCase().contains("player"))
										isPlayerInv = true;
								}catch(Throwable ignored)
								{}
								// as a safety, if index is within last 36 slots
								// (typical player inv range), mark as player
								int totalSlots = this.handler.slots.size();
								if(!isPlayerInv && totalSlots >= 36
									&& i >= totalSlots - 36)
									isPlayerInv = true;
								// System.out.println("[ChestRecorder] slotIdx="
								// + i + " slotClass=" + sc.getName()
								// + " invClass="
								// + (inv == null ? "null"
								// : inv.getClass().getName())
								// + " isPlayerInv=" + isPlayerInv);
								if(!isPlayerInv)
									chestSlotIndicesTemp.add(i);
							}catch(Throwable t)
							{
								t.printStackTrace();
							}
						}
						// find largest contiguous range within chestSlotIndices
						int bestStartIdx = -1, bestLen = 0;
						if(!chestSlotIndicesTemp.isEmpty())
						{
							int runStart = chestSlotIndicesTemp.get(0);
							int runPrev = runStart;
							int runLen = 1;
							for(int k = 1; k < chestSlotIndicesTemp.size(); k++)
							{
								int v = chestSlotIndicesTemp.get(k);
								if(v == runPrev + 1)
								{
									runLen++;
									runPrev = v;
								}else
								{
									if(runLen > bestLen)
									{
										bestLen = runLen;
										bestStartIdx = runStart;
									}
									runStart = v;
									runPrev = v;
									runLen = 1;
								}
							}
							if(runLen > bestLen)
							{
								bestLen = runLen;
								bestStartIdx = runStart;
							}
						}
						// if we found a chest slot region, read from that;
						// otherwise fallback to old heuristic
						if(bestStartIdx >= 0 && bestLen > 0)
						{
							int window =
								Math.min(bestLen, Math.max(0, rows * 9));
							chestSlotIndices.clear();
							for(int ii = 0; ii < window && bestStartIdx
								+ ii < this.handler.slots.size(); ii++)
								chestSlotIndices.add(bestStartIdx + ii);
							System.out.println(
								"[ChestRecorder] detected chest region start="
									+ bestStartIdx + " len=" + window);
						}else
						{
							int window = Math.min(Math.max(0, rows * 9),
								this.handler.slots.size());
							System.out.println(
								"[ChestRecorder] fallback chest region start="
									+ 0 + " len=" + window);
							chestSlotIndices.clear();
							for(int ii = 0; ii < window
								&& ii < this.handler.slots.size(); ii++)
								chestSlotIndices.add(ii);
						}
						java.util.List<Integer> slotOrder =
							new java.util.ArrayList<>(chestSlotIndices);
						if(slotOrder.isEmpty())
						{
							int fallbackWindow = Math.min(Math.max(0, rows * 9),
								this.handler.slots.size());
							for(int ii = 0; ii < fallbackWindow; ii++)
								slotOrder.add(ii);
						}
						this.chestSlotOrder =
							new java.util.ArrayList<>(slotOrder);
						java.util.List<net.minecraft.item.ItemStack> region =
							new java.util.ArrayList<>(slotOrder.size());
						int nonEmpty = 0;
						for(int idx : slotOrder)
						{
							net.minecraft.item.ItemStack s =
								(idx >= 0 && idx < this.handler.slots.size())
									? this.handler.slots.get(idx).getStack()
									: net.minecraft.item.ItemStack.EMPTY;
							region.add(s);
							if(s != null && !s.isEmpty())
								nonEmpty++;
						}
						int slotCount = slotOrder.size();
						if(nonEmpty > 0)
						{
							try
							{
								net.wurstclient.hacks.ChestSearchHack csh =
									WurstClient.INSTANCE
										.getHax().chestSearchHack;
								boolean doAuto =
									csh == null || csh.isAutomaticMode();
								if(doAuto)
								{
									int px =
										GenericContainerScreenMixin.this.clickedX;
									int py =
										GenericContainerScreenMixin.this.clickedY;
									int pz =
										GenericContainerScreenMixin.this.clickedZ;
									try
									{
										var hr = WurstClient.MC.crosshairTarget;
										if(hr instanceof net.minecraft.util.hit.BlockHitResult bhr)
										{
											var bpos = bhr.getBlockPos();
											if(bpos != null)
											{
												px = bpos.getX();
												py = bpos.getY();
												pz = bpos.getZ();
											}
										}
									}catch(Throwable ignored)
									{}
									wurst$recordForBounds(fServerIp, fDimension,
										px, py, pz, region, slotOrder,
										wurst$currentBounds());
									// debug removed
									chestRecorder.onChestOpened(fServerIp,
										fDimension, px, py, pz, handler,
										slotCount, slotOrder,
										wurst$currentBounds());
								}
							}catch(Throwable ignored)
							{}
							return;
						}
					}catch(Throwable ignored)
					{
						// ignore and fallback to block entity
					}
					// fallback to block entity read
					BlockPos bp = new BlockPos(fx, fy, fz);
					if(WurstClient.MC != null && WurstClient.MC.world != null)
					{
						BlockEntity be =
							WurstClient.MC.world.getBlockEntity(bp);
						if(be != null)
						{
							try
							{
								java.util.List<net.minecraft.item.ItemStack> stacks =
									new java.util.ArrayList<>();
								java.util.List<Integer> slotOrderEntity =
									new java.util.ArrayList<>();
								for(int i = 0; i < chestSlots; i++)
								{
									try
									{
										stacks
											.add(
												(net.minecraft.item.ItemStack)be
													.getClass()
													.getMethod("getStack",
														int.class)
													.invoke(be, i));
									}catch(Throwable ignored)
									{
										stacks.add(
											net.minecraft.item.ItemStack.EMPTY);
									}
									slotOrderEntity.add(i);
								}
								try
								{
									net.wurstclient.hacks.ChestSearchHack csh =
										WurstClient.INSTANCE
											.getHax().chestSearchHack;
									boolean doAuto =
										csh == null || csh.isAutomaticMode();
									if(doAuto)
									{
										int px =
											GenericContainerScreenMixin.this.clickedX;
										int py =
											GenericContainerScreenMixin.this.clickedY;
										int pz =
											GenericContainerScreenMixin.this.clickedZ;
										try
										{
											var hr =
												WurstClient.MC.crosshairTarget;
											if(hr instanceof net.minecraft.util.hit.BlockHitResult bhr)
											{
												var bpos = bhr.getBlockPos();
												if(bpos != null)
												{
													px = bpos.getX();
													py = bpos.getY();
													pz = bpos.getZ();
												}
											}
										}catch(Throwable ignored)
										{}
										chestRecorder
											.recordFromStacksWithSlotOrder(
												fServerIp, fDimension, px, py,
												pz, stacks, slotOrderEntity,
												wurst$currentBounds());
										wurst$recordForBounds(fServerIp,
											fDimension, px, py, pz, stacks,
											slotOrderEntity,
											wurst$currentBounds());
										// debug removed
									}
								}catch(Throwable ignored)
								{}
								// ensure chestSlotIndices default
								if(chestSlotIndices.isEmpty())
								{
									for(int ii = 0; ii < Math.min(chestSlots,
										this.handler.slots.size()); ii++)
										chestSlotIndices.add(ii);
								}
								this.chestSlotOrder =
									new java.util.ArrayList<>(chestSlotIndices);
								// continue with listener as fallback (only if
								// automatic mode)
								try
								{
									net.wurstclient.hacks.ChestSearchHack csh =
										WurstClient.INSTANCE
											.getHax().chestSearchHack;
									if(csh == null || csh.isAutomaticMode())
										chestRecorder.onChestOpened(fServerIp,
											fDimension,
											GenericContainerScreenMixin.this.clickedX,
											GenericContainerScreenMixin.this.clickedY,
											GenericContainerScreenMixin.this.clickedZ,
											handler, chestSlotIndices.size(),
											chestSlotIndices,
											wurst$currentBounds());
								}catch(Throwable ignored)
								{}
								return;
							}catch(Throwable ignored)
							{
								// ignore
							}
						}
					}
				}
			}catch(Throwable ignored)
			{
				// ignore
			}
			// Start packet/listener-based recording (reliable)
			try
			{
				if(chestSlotIndices.isEmpty())
				{
					for(int ii = 0; ii < Math.min(chestSlots,
						this.handler.slots.size()); ii++)
						chestSlotIndices.add(ii);
				}
				java.util.List<Integer> slotOrder =
					new java.util.ArrayList<>(chestSlotIndices);
				this.chestSlotOrder = new java.util.ArrayList<>(slotOrder);
				try
				{
					net.wurstclient.hacks.ChestSearchHack csh =
						WurstClient.INSTANCE.getHax().chestSearchHack;
					if(csh == null || csh.isAutomaticMode())
						chestRecorder.onChestOpened(fServerIp, fDimension,
							GenericContainerScreenMixin.this.clickedX,
							GenericContainerScreenMixin.this.clickedY,
							GenericContainerScreenMixin.this.clickedZ, handler,
							slotOrder.size(), slotOrder, wurst$currentBounds());
				}catch(Throwable ignored)
				{}
			}catch(Throwable ignored)
			{}
			// As an additional fallback, schedule a delayed task (200ms) to
			// capture the handler slots again after the client has definitely
			// received the server's WindowItems packet.
			new java.util.Timer(true).schedule(new java.util.TimerTask()
			{
				@Override
				public void run()
				{
					try
					{
						java.util.List<net.minecraft.item.ItemStack> all =
							new java.util.ArrayList<>();
						for(int i = 0; i < handler.slots.size(); i++)
						{
							all.add(handler.slots.get(i).getStack());
						}
						int total = all.size();
						java.util.List<Integer> slotOrder =
							new java.util.ArrayList<>(
								GenericContainerScreenMixin.this.chestSlotOrder);
						if(slotOrder.isEmpty())
						{
							int fallbackWindow =
								Math.min(chestSlots, Math.min(total, rows * 9));
							for(int i = 0; i < fallbackWindow; i++)
								slotOrder.add(i);
						}
						java.util.List<net.minecraft.item.ItemStack> region =
							new java.util.ArrayList<>(slotOrder.size());
						int nonEmpty = 0;
						for(int idx : slotOrder)
						{
							net.minecraft.item.ItemStack stack =
								(idx >= 0 && idx < total) ? all.get(idx)
									: net.minecraft.item.ItemStack.EMPTY;
							region.add(stack);
							if(stack != null && !stack.isEmpty())
								nonEmpty++;
						}
						boolean any = nonEmpty > 0;
						lastRecordedNonEmpty =
							Math.max(lastRecordedNonEmpty, nonEmpty);
						if(any)
						{
							try
							{
								net.wurstclient.hacks.ChestSearchHack csh =
									WurstClient.INSTANCE
										.getHax().chestSearchHack;
								boolean doAuto =
									csh == null || csh.isAutomaticMode();
								if(doAuto)
								{
									int px =
										GenericContainerScreenMixin.this.clickedX;
									int py =
										GenericContainerScreenMixin.this.clickedY;
									int pz =
										GenericContainerScreenMixin.this.clickedZ;
									try
									{
										var hr = WurstClient.MC.crosshairTarget;
										if(hr instanceof net.minecraft.util.hit.BlockHitResult bhr)
										{
											var bpos = bhr.getBlockPos();
											if(bpos != null)
											{
												px = bpos.getX();
												py = bpos.getY();
												pz = bpos.getZ();
											}
										}
									}catch(Throwable ignored)
									{}
									wurst$recordForBounds(fServerIp, fDimension,
										px, py, pz, region, slotOrder,
										wurst$currentBounds());
									// debug removed
								}
							}catch(Throwable ignored)
							{}
						}
					}catch(Throwable ignored)
					{}
				}
			}, 1000);
			// Auto-start quiet scan removed per user request
			// (servers requiring interaction can use the Scan button)
		}catch(Throwable e)
		{
			e.printStackTrace();
		}
	}
	
	@Unique
	private void showRecordedMessage(int x, int y, int z)
	{
		java.time.LocalTime t = java.time.LocalTime.now();
		String ts =
			t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
		this.lastRecordMessage =
			"Chest recorded, position " + x + "," + y + "," + z + " at " + ts;
		this.lastRecordUntilMs = System.currentTimeMillis() + 4000; // show 4s
	}
	
	@Unique
	private void startManualScan()
	{
		try
		{
			final String serverIp = (WurstClient.MC != null
				&& WurstClient.MC.getCurrentServerEntry() != null)
					? WurstClient.MC.getCurrentServerEntry().address : null;
			final String dimension =
				(WurstClient.MC != null && WurstClient.MC.world != null)
					? WurstClient.MC.world.getRegistryKey().getValue()
						.toString()
					: null;
			
			java.util.List<Integer> slotOrder =
				new java.util.ArrayList<>(this.chestSlotOrder);
			if(slotOrder.isEmpty())
			{
				int limit =
					Math.min(Math.max(0, rows * 9), this.handler.slots.size());
				for(int i = 0; i < limit; i++)
					slotOrder.add(i);
			}
			java.util.List<net.minecraft.item.ItemStack> region =
				new java.util.ArrayList<>();
			for(int idx : slotOrder)
			{
				net.minecraft.item.ItemStack s =
					(idx >= 0 && idx < this.handler.slots.size())
						? this.handler.slots.get(idx).getStack()
						: net.minecraft.item.ItemStack.EMPTY;
				region.add(s == null ? net.minecraft.item.ItemStack.EMPTY : s);
			}
			// choose primary position: prefer crosshair block the player was
			// pointing at
			int px = chestX, py = chestY, pz = chestZ;
			try
			{
				var hr = WurstClient.MC.crosshairTarget;
				if(hr instanceof net.minecraft.util.hit.BlockHitResult bhr)
				{
					var bpos = bhr.getBlockPos();
					if(bpos != null)
					{
						px = bpos.getX();
						py = bpos.getY();
						pz = bpos.getZ();
					}
				}
			}catch(Throwable ignored)
			{}
			
			boolean any = false;
			for(net.minecraft.item.ItemStack s : region)
				if(s != null && !s.isEmpty())
				{
					any = true;
					break;
				}
			final int fpx = px, fpy = py, fpz = pz;
			final java.util.List<Integer> fSlotOrder =
				new java.util.ArrayList<>(slotOrder);
			final String fServer = serverIp;
			final String fDim = dimension;
			final ChestRecorder.Bounds fBounds = wurst$currentBounds();
			Runnable doRecord = () -> {
				try
				{
					java.util.List<net.minecraft.item.ItemStack> reg =
						new java.util.ArrayList<>();
					for(int idx2 : fSlotOrder)
					{
						net.minecraft.item.ItemStack s2 =
							(idx2 >= 0 && idx2 < this.handler.slots.size())
								? this.handler.slots.get(idx2).getStack()
								: net.minecraft.item.ItemStack.EMPTY;
						reg.add(s2 == null ? net.minecraft.item.ItemStack.EMPTY
							: s2);
					}
					chestRecorder.recordFromStacksWithSlotOrder(fServer, fDim,
						fpx, fpy, fpz, reg, fSlotOrder, fBounds);
					wurst$recordForBounds(fServer, fDim, fpx, fpy, fpz, reg,
						fSlotOrder, fBounds);
				}catch(Throwable ignored)
				{}
			};
			if(any)
			{
				doRecord.run();
			}else
			{
				new java.util.Timer(true).schedule(new java.util.TimerTask()
				{
					@Override
					public void run()
					{
						doRecord.run();
					}
				}, 250);
			}
		}catch(Throwable t)
		{
			t.printStackTrace();
		}
	}
	
	@Unique
	private void startManualScanWith(int durationMs, boolean quiet)
	{
		manualScanActive = true;
		manualScanQuiet = quiet;
		manualScanUntil =
			System.currentTimeMillis() + Math.max(500, durationMs);
		System.out.println(
			"[ChestRecorder] Manual scan started. Hover/click through chest slots.");
		final int chestSlots = Math.max(0, rows * 9);
		final String serverIp = (WurstClient.MC != null
			&& WurstClient.MC.getCurrentServerEntry() != null)
				? WurstClient.MC.getCurrentServerEntry().address : null;
		final String dimension =
			(WurstClient.MC != null && WurstClient.MC.world != null)
				? WurstClient.MC.world.getRegistryKey().getValue().toString()
				: null;
		// choose chest position determined on open; fallback to crosshair, then
		// player
		BlockPos chosenPos = null;
		if(chestX != 0 || chestY != 0 || chestZ != 0)
			chosenPos = new BlockPos(chestX, chestY, chestZ);
		else
		{
			try
			{
				HitResult hr = WurstClient.MC.crosshairTarget;
				if(hr instanceof BlockHitResult bhr)
				{
					BlockPos bp = bhr.getBlockPos();
					if(bp != null)
						chosenPos = bp;
				}
			}catch(Throwable ignored)
			{}
			if(chosenPos == null && WurstClient.MC != null
				&& WurstClient.MC.player != null)
				chosenPos = WurstClient.MC.player.getBlockPos();
		}
		final BlockPos bp =
			chosenPos != null ? chosenPos : new BlockPos(0, 0, 0);
		// poll handler slots periodically, record best-known region
		new java.util.Timer(true).scheduleAtFixedRate(new java.util.TimerTask()
		{
			@Override
			public void run()
			{
				try
				{
					if(WurstClient.MC == null
						|| WurstClient.MC.currentScreen != GenericContainerScreenMixin.this
						|| !manualScanActive
						|| System.currentTimeMillis() > manualScanUntil)
					{
						manualScanActive = false;
						this.cancel();
						System.out
							.println("[ChestRecorder] Manual scan finished.");
						return;
					}
					java.util.List<net.minecraft.item.ItemStack> all =
						new java.util.ArrayList<>();
					for(int i = 0; i < handler.slots.size(); i++)
					{
						all.add(handler.slots.get(i).getStack());
					}
					int total = all.size();
					int window = Math.min(chestSlots, total);
					int bestStart = 0;
					int bestCount = -1;
					for(int start = 0; start + window <= total; start++)
					{
						int count = 0;
						for(int i = 0; i < window; i++)
						{
							net.minecraft.item.ItemStack s = all.get(start + i);
							if(s != null && !s.isEmpty())
								count++;
						}
						if(count > bestCount)
						{
							bestCount = count;
							bestStart = start;
						}
					}
					java.util.List<net.minecraft.item.ItemStack> region =
						new java.util.ArrayList<>(window);
					java.util.List<Integer> slotOrder =
						new java.util.ArrayList<>(window);
					for(int i = 0; i < window; i++)
					{
						int idx = bestStart + i;
						region.add(all.get(idx));
						slotOrder.add(idx);
					}
					if(bestCount > 0)
					{
						lastRecordedNonEmpty =
							Math.max(lastRecordedNonEmpty, bestCount);
						chestRecorder.recordFromStacksWithSlotOrder(serverIp,
							dimension, bp.getX(), bp.getY(), bp.getZ(), region,
							slotOrder, wurst$currentBounds());
						showRecordedMessage(bp.getX(), bp.getY(), bp.getZ());
					}
				}catch(Throwable ignored)
				{}
			}
		}, 0L, 200L);
	}
	
	// Replace hard override with a safe inject at TAIL to render overlay
	@Inject(method = "render", at = @At("TAIL"))
	private void wurst$renderOverlay(DrawContext context, int mouseX,
		int mouseY, float delta, CallbackInfo ci)
	{
		long now = System.currentTimeMillis();
		if(lastRecordMessage != null && now <= lastRecordUntilMs)
		{
			int textX = this.width / 2 - 120;
			int textY = this.height - 18; // near bottom
			context.drawText(this.textRenderer, Text.literal(lastRecordMessage),
				textX, textY, 0xFFFFFF00, false);
		}else if(manualScanActive && !manualScanQuiet)
		{
			String hint = "Scanning... hover/click slots to reveal items";
			int textX = this.width / 2 - 120;
			int textY = this.height - 18;
			context.drawText(this.textRenderer, Text.literal(hint), textX,
				textY, 0xFFFFFF00, false);
		}
	}
	
	@Override
	public void removed()
	{
		wurst$finalizeChestSnapshot();
		super.removed();
	}
	
	// Helper to snapshot/remove chest DB when the screen is closing
	@Unique
	private void wurst$finalizeChestSnapshot()
	{
		try
		{
			if(this.chestRecorder == null || this.handler == null)
				return;
			// Only finalize snapshot automatically if automatic mode is on
			try
			{
				net.wurstclient.hacks.ChestSearchHack csh =
					WurstClient.INSTANCE.getHax().chestSearchHack;
				if(csh != null && !csh.isAutomaticMode())
					return;
			}catch(Throwable ignored)
			{}
			String serverIp = null;
			String dimension = null;
			int fx = this.chestX, fy = this.chestY, fz = this.chestZ;
			try
			{
				if(net.wurstclient.WurstClient.MC != null
					&& net.wurstclient.WurstClient.MC
						.getCurrentServerEntry() != null)
					serverIp = net.wurstclient.WurstClient.MC
						.getCurrentServerEntry().address;
			}catch(Throwable ignored)
			{}
			try
			{
				if(net.wurstclient.WurstClient.MC != null
					&& net.wurstclient.WurstClient.MC.world != null)
					dimension = net.wurstclient.WurstClient.MC.world
						.getRegistryKey().getValue().toString();
			}catch(Throwable ignored)
			{}
			int chestSlots = Math.max(0, this.rows * 9);
			int total = this.handler.slots.size();
			java.util.List<Integer> slotOrder =
				new java.util.ArrayList<>(this.chestSlotOrder);
			if(slotOrder.isEmpty())
			{
				int fallbackWindow = Math.min(chestSlots, total);
				for(int i = 0; i < fallbackWindow; i++)
					slotOrder.add(i);
			}
			java.util.List<net.minecraft.item.ItemStack> region =
				new java.util.ArrayList<>(slotOrder.size());
			boolean any = false;
			for(int idx : slotOrder)
			{
				var st = (idx >= 0 && idx < total)
					? this.handler.slots.get(idx).getStack()
					: net.minecraft.item.ItemStack.EMPTY;
				region.add(st);
				if(st != null && !st.isEmpty())
					any = true;
			}
			this.chestSlotOrder = java.util.Collections.emptyList();
			if(any && !region.isEmpty())
			{
				chestRecorder.recordFromStacksWithSlotOrder(serverIp, dimension,
					fx, fy, fz, region, slotOrder, wurst$currentBounds());
			}
		}catch(Throwable ignored)
		{}
	}
	
	@Unique
	private BlockPos wurst$normalizeContainerPos(BlockPos pos)
	{
		if(pos == null || WurstClient.MC == null
			|| WurstClient.MC.world == null)
			return pos;
		BlockPos current = pos;
		BlockState state = WurstClient.MC.world.getBlockState(current);
		if(!wurst$isTrackableContainer(state))
		{
			BlockPos below = current.down();
			BlockState belowState = WurstClient.MC.world.getBlockState(below);
			if(wurst$isTrackableContainer(belowState))
			{
				current = below;
				state = belowState;
			}
		}
		if(!wurst$isTrackableContainer(state))
			return current;
		// reset stored bounds/facing
		this.chestDoubleBounds = null;
		this.chestFacing = null;
		
		// get facing robustly
		Direction facingDir = null;
		try
		{
			String fName = wurst$getFacingName(state);
			if(fName != null)
			{
				for(Direction d : Direction.values())
				{
					if(d.asString().equalsIgnoreCase(fName))
					{
						facingDir = d;
						break;
					}
				}
			}
			if(facingDir == null)
			{
				try
				{
					facingDir = state.get(ChestBlock.FACING);
				}catch(Throwable ignored)
				{}
			}
		}catch(Throwable ignored)
		{}
		if(facingDir != null)
			this.chestFacing = facingDir.asString();
		
		// If chest block, try to find its connected pair
		if(state.getBlock() instanceof ChestBlock)
		{
			try
			{
				BlockPos other =
					wurst$findConnectedChest(current, state, facingDir);
				if(other != null)
				{
					// ensure facing known: prefer reading from other if missing
					if(this.chestFacing == null)
					{
						try
						{
							String of = wurst$getFacingName(
								WurstClient.MC.world.getBlockState(other));
							if(of != null)
							{
								for(Direction d : Direction.values())
								{
									if(d.asString().equalsIgnoreCase(of))
									{
										facingDir = d;
										break;
									}
								}
								this.chestFacing = of;
							}
						}catch(Throwable ignored)
						{}
					}
					// compute deterministic bounds based on facing
					int x1 = current.getX(), z1 = current.getZ();
					int x2 = other.getX(), z2 = other.getZ();
					BlockPos minPos, maxPos;
					if(facingDir == Direction.NORTH
						|| facingDir == Direction.SOUTH)
					{
						// For chests, the double extends PERPENDICULAR to
						// facing.
						// If facing is NORTH/SOUTH, the pair extends along X
						// (east-west).
						int minX = Math.min(x1, x2);
						int maxX = Math.max(x1, x2);
						minPos =
							new BlockPos(minX, current.getY(), current.getZ());
						maxPos =
							new BlockPos(maxX, current.getY(), current.getZ());
					}else if(facingDir == Direction.EAST
						|| facingDir == Direction.WEST)
					{
						// If facing is EAST/WEST, the pair extends along Z
						// (north-south).
						int minZ = Math.min(z1, z2);
						int maxZ = Math.max(z1, z2);
						minPos =
							new BlockPos(current.getX(), current.getY(), minZ);
						maxPos =
							new BlockPos(current.getX(), current.getY(), maxZ);
					}else
					{
						// unknown facing - fall back to min/max by coords
						minPos = wurst$minPos(current, other);
						maxPos =
							new BlockPos(Math.max(current.getX(), other.getX()),
								Math.max(current.getY(), other.getY()),
								Math.max(current.getZ(), other.getZ()));
					}
					this.chestDoubleBounds = new BlockPos[]{minPos, maxPos};
					
					// choose base open position: prefer half with items,
					// otherwise minPos
					boolean leftHasItems = false;
					boolean rightHasItems = false;
					try
					{
						BlockEntity beL =
							WurstClient.MC.world.getBlockEntity(minPos);
						if(beL instanceof net.minecraft.block.entity.LockableContainerBlockEntity)
						{
							var inv = (net.minecraft.inventory.Inventory)beL;
							for(int i = 0; i < inv.size(); i++)
								if(inv.getStack(i) != null
									&& !inv.getStack(i).isEmpty())
								{
									leftHasItems = true;
									break;
								}
						}
					}catch(Throwable ignored)
					{}
					try
					{
						BlockEntity beR =
							WurstClient.MC.world.getBlockEntity(maxPos);
						if(beR instanceof net.minecraft.block.entity.LockableContainerBlockEntity)
						{
							var inv = (net.minecraft.inventory.Inventory)beR;
							for(int i = 0; i < inv.size(); i++)
								if(inv.getStack(i) != null
									&& !inv.getStack(i).isEmpty())
								{
									rightHasItems = true;
									break;
								}
						}
					}catch(Throwable ignored)
					{}
					BlockPos chosen = minPos;
					if(rightHasItems && !leftHasItems)
						chosen = maxPos;
					
					// store canonical chestX/Y/Z as chosen
					this.chestX = chosen.getX();
					this.chestY = chosen.getY();
					this.chestZ = chosen.getZ();
					
					System.out.println(
						"[ChestPos] normalized double chest: min=" + minPos
							+ " max=" + maxPos + " facing=" + this.chestFacing
							+ " chosen=" + chosen + " leftHasItems="
							+ leftHasItems + " rightHasItems=" + rightHasItems);
					return chosen;
				}
			}catch(Throwable ignored)
			{}
		}
		
		// single chest or other container
		this.chestDoubleBounds = new BlockPos[]{current, current};
		if(this.chestFacing == null)
		{
			try
			{
				this.chestFacing = wurst$getFacingName(state);
			}catch(Throwable ignored)
			{
				this.chestFacing = null;
			}
		}
		this.chestX = current.getX();
		this.chestY = current.getY();
		this.chestZ = current.getZ();
		System.out.println("[ChestPos] normalized single chest at " + current
			+ " facing=" + this.chestFacing);
		return current;
	}
	
	@Unique
	private boolean wurst$isTrackableContainer(BlockState state)
	{
		if(state == null)
			return false;
		Block block = state.getBlock();
		return block instanceof ChestBlock || block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock
			|| block instanceof EnderChestBlock
			|| block instanceof DecoratedPotBlock;
	}
	
	@Unique
	private BlockPos wurst$minPos(BlockPos a, BlockPos b)
	{
		if(a == null)
			return b;
		if(b == null)
			return a;
		int cmpX = Integer.compare(a.getX(), b.getX());
		if(cmpX != 0)
			return cmpX <= 0 ? a : b;
		int cmpY = Integer.compare(a.getY(), b.getY());
		if(cmpY != 0)
			return cmpY <= 0 ? a : b;
		int cmpZ = Integer.compare(a.getZ(), b.getZ());
		return cmpZ <= 0 ? a : b;
	}
	
	@Unique
	private ChestRecorder.Bounds wurst$currentBounds()
	{
		if(this.chestDoubleBounds == null || this.chestDoubleBounds.length == 0)
			return null;
		BlockPos min = this.chestDoubleBounds[0] != null
			? this.chestDoubleBounds[0] : new BlockPos(chestX, chestY, chestZ);
		BlockPos max = this.chestDoubleBounds.length > 1
			&& this.chestDoubleBounds[1] != null ? this.chestDoubleBounds[1]
				: min;
		return ChestRecorder.Bounds.of(min, max, this.chestFacing);
	}
	
	@Unique
	private BlockPos wurst$raycastContainer()
	{
		try
		{
			if(WurstClient.MC == null || WurstClient.MC.player == null
				|| WurstClient.MC.world == null)
				return null;
			var player = WurstClient.MC.player;
			net.minecraft.util.math.Vec3d eye = player.getCameraPosVec(1.0f);
			net.minecraft.util.math.Vec3d look = player.getRotationVec(1.0f);
			double reach = 5.0;
			net.minecraft.util.math.Vec3d end =
				eye.add(look.x * reach, look.y * reach, look.z * reach);
			int steps = 40;
			for(int i = 0; i <= steps; i++)
			{
				double t = (double)i / (double)steps;
				net.minecraft.util.math.Vec3d p =
					eye.multiply(1.0 - t).add(end.multiply(t));
				BlockPos bp = BlockPos.ofFloored(p);
				BlockState s = WurstClient.MC.world.getBlockState(bp);
				if(s.getBlock() instanceof ChestBlock)
					return bp;
			}
		}catch(Throwable ignored)
		{}
		return null;
	}
	
	@Unique
	private String wurst$getFacingName(BlockState state)
	{
		if(state == null)
			return null;
		try
		{
			if(state.contains(Properties.HORIZONTAL_FACING))
				return state.get(Properties.HORIZONTAL_FACING).asString();
			if(state.contains(Properties.FACING))
				return state.get(Properties.FACING).asString();
			try
			{
				Direction d = state.get(ChestBlock.FACING);
				if(d != null)
					return d.asString();
			}catch(Throwable ignored)
			{}
		}catch(Throwable ignored)
		{}
		return null;
	}
	
	@Unique
	private BlockPos wurst$findConnectedChest(BlockPos current,
		BlockState state, Direction facing)
	{
		if(WurstClient.MC == null || WurstClient.MC.world == null)
			return null;
		try
		{
			for(Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH,
				Direction.WEST, Direction.EAST})
			{
				BlockPos candidate = current.offset(d);
				BlockState other =
					WurstClient.MC.world.getBlockState(candidate);
				if(!(other.getBlock() instanceof ChestBlock))
					continue;
				try
				{
					Direction of = null;
					try
					{
						of = other.get(ChestBlock.FACING);
					}catch(Throwable ignored)
					{}
					ChestType ot = null;
					try
					{
						ot = other.get(ChestBlock.CHEST_TYPE);
					}catch(Throwable ignored)
					{}
					if(ot != ChestType.SINGLE)
					{
						if(facing == null || of == facing)
							return candidate;
					}
				}catch(Throwable ignored)
				{}
			}
		}catch(Throwable ignored)
		{}
		return null;
	}
	
	@Unique
	private void wurst$recordForBounds(String serverIp, String dimension, int x,
		int y, int z, java.util.List<net.minecraft.item.ItemStack> stacks,
		java.util.List<Integer> slotOrder, ChestRecorder.Bounds bounds)
	{
		try
		{
			// primary record at provided coords
			chestRecorder.recordFromStacksWithSlotOrder(serverIp, dimension, x,
				y, z, stacks, slotOrder, bounds);
			// if bounds spans multiple blocks (double chest), also record for
			// the other half
			if(bounds != null
				&& (bounds.minX != bounds.maxX || bounds.minZ != bounds.maxZ))
			{
				int otherX = x, otherZ = z;
				// choose the other block pos within bounds that's not (x,z)
				if(bounds.minX != bounds.maxX)
				{
					// varies on X
					otherX = (x == bounds.minX) ? bounds.maxX : bounds.minX;
				}
				if(bounds.minZ != bounds.maxZ)
				{
					otherZ = (z == bounds.minZ) ? bounds.maxZ : bounds.minZ;
				}
				// ensure we don't double record same pos
				if(otherX != x || otherZ != z)
				{
					chestRecorder.recordFromStacksWithSlotOrder(serverIp,
						dimension, otherX, y, otherZ, stacks, slotOrder,
						bounds);
					System.out.println("[ChestPos] also recorded other half at "
						+ otherX + "," + y + "," + otherZ);
				}
			}
		}catch(Throwable t)
		{
			t.printStackTrace();
		}
	}
}
