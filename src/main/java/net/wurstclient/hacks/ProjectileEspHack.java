/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"projectile esp", "ProjectileESP", "snowball esp", "egg esp",
	"projectile tracers", "ProjectileTracers"})
public final class ProjectileEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final EspBoxSizeSetting boxSize =
		new EspBoxSizeSetting("§lAccurate§r mode shows the exact hitbox.\n"
			+ "§lFancy§r mode shows larger boxes that look better.");
	
	private final CheckboxSetting includeArrows = new CheckboxSetting(
		"Include arrows",
		"Also highlight arrows and tridents. Turn off to cut the clutter and"
			+ " only show thrown items like snowballs, eggs and pearls.",
		true);
	
	private final EnumSetting<ColorMode> colorMode = new EnumSetting<>("Colors",
		"How projectiles are colored.\n\n"
			+ "§lFixed§r - one color for everything.\n"
			+ "§lRainbow§r - cycling rainbow.\n"
			+ "§lBy owner§r - who threw it (you / player / mob).\n"
			+ "§lBy type§r - a separate color per projectile type, set in the"
			+ " §lType colors§r menu.",
		ColorMode.values(), ColorMode.BY_TYPE);
	
	private final ColorSetting fixedColor = new ColorSetting("Fixed color",
		"Color used in §lFixed§r mode.", Color.CYAN);
	
	private final ColorSetting selfColor = new ColorSetting("Your projectiles",
		"§lBy owner§r: projectiles you threw.", new Color(0x55FF55));
	private final ColorSetting otherPlayerColor = new ColorSetting(
		"Other players", "§lBy owner§r: projectiles from other players.",
		new Color(0xFF5555));
	private final ColorSetting mobColor = new ColorSetting("Mobs",
		"§lBy owner§r: projectiles from mobs or with no known owner.",
		new Color(0xFFFF55));
	private final SettingGroup ownerColors = new SettingGroup("Owner colors",
		WText.literal("Colors used by the §lBy owner§r mode."), false, true)
			.addChildren(selfColor, otherPlayerColor, mobColor);
	
	private final ColorSetting snowballColor = new ColorSetting("Snowball",
		"Color for snowballs.", new Color(0xFFFFFF));
	private final ColorSetting eggColor =
		new ColorSetting("Egg", "Color for eggs.", new Color(0xF2D9A0));
	private final ColorSetting pearlColor = new ColorSetting("Ender pearl",
		"Color for ender pearls.", new Color(0xA000E0));
	private final ColorSetting potionColor = new ColorSetting("Potion",
		"Color for splash and lingering potions.", new Color(0xFF55FF));
	private final ColorSetting xpBottleColor = new ColorSetting("XP bottle",
		"Color for bottles o' enchanting.", new Color(0x77FF33));
	private final ColorSetting arrowColor =
		new ColorSetting("Arrow", "Color for arrows.", new Color(0xCCCCCC));
	private final ColorSetting tridentColor = new ColorSetting("Trident",
		"Color for thrown tridents.", new Color(0x33FFFF));
	private final ColorSetting fireballColor = new ColorSetting("Fireball",
		"Color for fireballs, wind charges, wither skulls, etc.",
		new Color(0xFF6000));
	private final ColorSetting fireworkColor = new ColorSetting("Firework",
		"Color for firework rockets.", new Color(0xFFC000));
	private final ColorSetting otherColor = new ColorSetting("Other",
		"Color for any other projectile.", new Color(0x999999));
	private final SettingGroup typeColors = new SettingGroup("Type colors",
		WText.literal("A separate color per projectile type, used by the"
			+ " §lBy type§r mode."),
		false, true).addChildren(snowballColor, eggColor, pearlColor,
			potionColor, xpBottleColor, arrowColor, tridentColor, fireballColor,
			fireworkColor, otherColor);
	
	private final CheckboxSetting showNames = new CheckboxSetting("Show names",
		"Draw a name tag above each projectile showing what it is.", false);
	private final SliderSetting nameScale =
		new SliderSetting("Name scale", "Size of the projectile name tags.", 1,
			0.5, 2, 0.05, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting trajectory = new CheckboxSetting("Trajectory",
		"Draw the predicted flight path of each in-flight projectile, in its"
			+ " color.",
		false);
	private final SliderSetting trajectoryThickness = new SliderSetting(
		"Trajectory thickness", 2, 0.5, 8, 0.1, ValueDisplay.DECIMAL);
	private final CheckboxSetting landingBox =
		new CheckboxSetting("Landing box",
			"Mark where each projectile is predicted to land/hit.", true);
	
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	
	private final ArrayList<Projectile> projectiles = new ArrayList<>();
	
	public ProjectileEspHack()
	{
		super("ProjectileESP");
		setCategory(Category.RENDER);
		for(Setting setting : new Setting[]{style, boxSize, includeArrows,
			colorMode, fixedColor, selfColor, otherPlayerColor, mobColor,
			ownerColors, snowballColor, eggColor, pearlColor, potionColor,
			xpBottleColor, arrowColor, tridentColor, fireballColor,
			fireworkColor, otherColor, typeColors, showNames, nameScale,
			trajectory, trajectoryThickness, landingBox, tracerFlash})
			addSetting(setting);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		projectiles.clear();
	}
	
	@Override
	public void onUpdate()
	{
		projectiles.clear();
		if(MC.level == null)
			return;
		
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!(e instanceof Projectile projectile))
				continue;
			if(!includeArrows.isChecked() && e instanceof AbstractArrow)
				continue;
			projectiles.add(projectile);
		}
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines() || trajectory.isChecked())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(projectiles.isEmpty())
			return;
		boolean boxes = style.hasBoxes();
		boolean lines = style.hasLines();
		boolean names = showNames.isChecked();
		boolean paths = trajectory.isChecked();
		if(!boxes && !lines && !names && !paths)
			return;
		
		Map<Integer, List<AABB>> boxesByColor = new LinkedHashMap<>();
		Map<Integer, List<Vec3>> endsByColor = new LinkedHashMap<>();
		Map<Integer, List<AABB>> landingByColor = new LinkedHashMap<>();
		
		for(Projectile p : projectiles)
		{
			int color = colorFor(p);
			AABB box =
				applyExtraSize(EntityUtils.getLerpedBox(p, partialTicks));
			
			if(boxes)
				boxesByColor.computeIfAbsent(color, k -> new ArrayList<>())
					.add(box);
			if(lines)
				endsByColor.computeIfAbsent(color, k -> new ArrayList<>())
					.add(box.getCenter());
			if(names)
				drawNameTag(matrixStack,
					p.getType().getDescription().getString(), box.getCenter().x,
					box.maxY + 0.3, box.getCenter().z, color);
			
			if(paths)
			{
				List<Vec3> path = buildPath(p);
				if(path.size() >= 2)
				{
					RenderUtils.drawCurvedLine(matrixStack, path, color, false,
						trajectoryThickness.getValue());
					if(landingBox.isChecked())
					{
						Vec3 hit = path.get(path.size() - 1);
						landingByColor
							.computeIfAbsent(color, k -> new ArrayList<>())
							.add(new AABB(hit.x - 0.25, hit.y - 0.25,
								hit.z - 0.25, hit.x + 0.25, hit.y + 0.25,
								hit.z + 0.25));
					}
				}
			}
		}
		
		for(Map.Entry<Integer, List<AABB>> e : boxesByColor.entrySet())
			RenderUtils.drawOutlinedBoxes(matrixStack, e.getValue(), e.getKey(),
				false);
		for(Map.Entry<Integer, List<Vec3>> e : endsByColor.entrySet())
			RenderUtils.drawTracers(matrixStack, partialTicks, e.getValue(),
				e.getKey(), false);
		for(Map.Entry<Integer, List<AABB>> e : landingByColor.entrySet())
		{
			int c = e.getKey();
			RenderUtils.drawSolidBoxes(matrixStack, e.getValue(),
				c & 0x00FFFFFF | 0x40000000, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, e.getValue(), c, false);
		}
	}
	
	private List<Vec3> buildPath(Projectile p)
	{
		ArrayList<Vec3> path = new ArrayList<>();
		Vec3 pos = p.position();
		Vec3 vel = p.getDeltaMovement();
		path.add(pos);
		if(MC.level == null)
			return path;
		
		double gravity =
			p.isNoGravity() ? 0.0 : p instanceof AbstractArrow ? 0.05 : 0.03;
		double drag = p.isInWater() ? 0.8 : 0.99;
		
		for(int i = 0; i < 200; i++)
		{
			Vec3 next = pos.add(vel);
			BlockHitResult hit = BlockUtils.raycast(pos, next);
			if(hit.getType() != HitResult.Type.MISS)
			{
				path.add(hit.getLocation());
				break;
			}
			
			path.add(next);
			pos = next;
			vel = vel.scale(drag).add(0, -gravity, 0);
			
			if(pos.y < MC.level.getMinY() - 4 || vel.lengthSqr() < 1e-4)
				break;
		}
		
		return path;
	}
	
	private int colorFor(Projectile p)
	{
		int color = switch(colorMode.getSelected())
		{
			case FIXED -> fixedColor.getColorI(0x80);
			case RAINBOW -> RenderUtils
				.toIntColor(RenderUtils.getRainbowColor(), 0.5F);
			case BY_OWNER -> ownerColor(p);
			case BY_TYPE -> typeColorSetting(p).getColorI(0x80);
		};
		return tracerFlash.isChecked() ? RenderUtils.flashColor(color) : color;
	}
	
	private int ownerColor(Projectile p)
	{
		Entity owner = p.getOwner();
		if(owner == MC.player)
			return selfColor.getColorI(0x80);
		if(owner instanceof Player)
			return otherPlayerColor.getColorI(0x80);
		return mobColor.getColorI(0x80);
	}
	
	private ColorSetting typeColorSetting(Projectile p)
	{
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()).getPath();
		return switch(id)
		{
			case "snowball" -> snowballColor;
			case "egg" -> eggColor;
			case "ender_pearl" -> pearlColor;
			case "splash_potion", "lingering_potion", "potion" -> potionColor;
			case "experience_bottle" -> xpBottleColor;
			case "arrow", "spectral_arrow" -> arrowColor;
			case "trident" -> tridentColor;
			case "fireball", "small_fireball", "large_fireball", "dragon_fireball", "wither_skull", "wind_charge", "breeze_wind_charge" -> fireballColor;
			case "firework_rocket" -> fireworkColor;
			default -> otherColor;
		};
	}
	
	private AABB applyExtraSize(AABB box)
	{
		double extra = boxSize.getExtraSize() / 2.0;
		return box.move(0, extra, 0).inflate(extra);
	}
	
	private void drawNameTag(PoseStack matrices, String text, double x,
		double y, double z, int argb)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		
		Entity camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		
		matrices.mulPose(Axis.YP.rotationDegrees(180.0F));
		float s = 0.025F * nameScale.getValueF();
		matrices.scale(s, -s, s);
		
		Font font = MC.font;
		float w = font.width(text) / 2F;
		int bgAlpha = (int)(MC.options.getBackgroundOpacity(0.25F) * 255) << 24;
		var matrix = matrices.last().pose();
		RenderUtils.drawTextInBatch(font, text, -w, 0, argb | 0xFF000000, false,
			matrix, null, Font.DisplayMode.SEE_THROUGH, bgAlpha, 0xF000F0);
		matrices.popPose();
	}
	
	private enum ColorMode
	{
		FIXED("Fixed"),
		RAINBOW("Rainbow"),
		BY_OWNER("By owner"),
		BY_TYPE("By type");
		
		private final String name;
		
		ColorMode(String name)
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
