package com.redlimerl.detailab.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.redlimerl.detailab.DetailArmorBar;
import com.redlimerl.detailab.api.DetailArmorBarAPI;
import com.redlimerl.detailab.api.render.CustomArmorBar;
import com.redlimerl.detailab.config.ConfigEnumType.Animation;
import com.redlimerl.detailab.config.ConfigEnumType.ProtectionEffect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Pair;
import net.minecraft.util.math.MathHelper;

import java.awt.*;
import java.util.List;
import java.util.*;

import static com.redlimerl.detailab.DetailArmorBar.GUI_ARMOR_BAR;
import static com.redlimerl.detailab.DetailArmorBar.getConfig;

public class ArmorBarRenderer {
    static class LevelData {
        int level;
        int count;
        LevelData(int level, int count) {
            this.level = level;
            this.count = count;
        }
    }

    public static final ArmorBarRenderer INSTANCE = new ArmorBarRenderer();
    public static long LAST_THORNS = 0L;
    public static long LAST_MENDING = 0L;


    private static int getAnimationSpeed() {
        return switch (getConfig().getOptions().effectSpeed) {
            case VERY_SLOW -> 45;
            case SLOW -> 37;
            case FAST -> 23;
            case VERY_FAST -> 15;
            default -> 30;
        };
    }

    private static Color getProtectColor(int g, int p, int e, int f, int a) {
        int speed = getAnimationSpeed();
        int alpha;
        if (getConfig().getOptions().effectType == ProtectionEffect.AURA) alpha = 80;
        else if (getConfig().getOptions().effectType == ProtectionEffect.OUTLINE) {
            long time = DetailArmorBar.getTicks();
            if (time % (speed*4L) < (speed*2L)) alpha = 0;
            else if (time % (speed*2L) < speed)
                alpha = Math.round(MathHelper.lerp((time % speed) / (speed - 1f), 0f, 0.65f) * 255);
            else alpha = Math.round(MathHelper.lerp((time % speed) / (speed - 1f), 0.65f, 0f) * 255);
        } else alpha = 0;

        if (g > 0) return new Color(153, 255, 255, alpha);
        if (p > 0) return new Color(112, 51, 173, alpha);
        if (e > 0) return new Color(255, 255, 0, alpha);
        if (f > 0) return new Color(210, 56, 0, alpha);
        if (a > 0) return new Color(255, 255, 255, alpha);
        return Color.WHITE;
    }

    private static Color getProtectColor(int[] s) {
        return getProtectColor(s[0], s[1], s[2], s[3], s[4]);
    }

    private static Color getLowDurabilityColor() {
        int speed = getAnimationSpeed();
        long time = DetailArmorBar.getTicks();
        int alpha;
        if (time % (speed*4L) >= (speed*2L)) alpha = 0;
        else if (time % (speed*2L) < speed)
            alpha = Math.round(MathHelper.lerp((time % speed) / (speed - 1f), 0f, 0.65f) * 255);
        else alpha = Math.round(MathHelper.lerp((time % speed) / (speed - 1f), 0.65f, 0f) * 255);

        return new Color(255, 25, 25, alpha);
    }

    private static Color getThornColor() {
        long time = DetailArmorBar.getTicks() - LAST_THORNS;
        if (getConfig().getOptions().effectThorn == Animation.STATIC) return Color.WHITE;
        if (time > 19) return Color.WHITE;

        int cc = Math.round(MathHelper.lerp((time % 20) / 19f, 0f, 1f)*255);
        return new Color(255, cc, cc);
    }

    private static Map<RegistryKey<Enchantment>, LevelData> getEnchantments(Iterable<ItemStack> equipment) {
        HashMap<RegistryKey<Enchantment>, LevelData> result = new HashMap<>();

        for (ItemStack itemStack : equipment) {
            if (!itemStack.isEmpty()) {
                EnchantmentHelper.getEnchantments(itemStack).getEnchantmentEntries().forEach(enchantment -> {
                    RegistryKey<Enchantment> enchantType = enchantment.getKey().getKey().orElse(null);
                    LevelData enchantData = result.getOrDefault(enchantType, new LevelData(0, 0));
                    enchantData.count++;
                    enchantData.level += enchantment.getIntValue();
                    if (enchantType == Enchantments.THORNS) enchantData.level += enchantment.getIntValue() - 1;
                    result.put(enchantType, enchantData);
                });
            }
        }

        return result;
    }

    private static LevelData getEnchantLevel(Iterable<ItemStack> equipment, RegistryKey<Enchantment> type) {
        return getEnchantments(equipment).getOrDefault(type, new LevelData(0, 0));
    }

    private int getLowDurabilityItem(Iterable<Pair<EquipmentSlot, ItemStack>> equipment) {
        var count = 0;
        for (Pair<EquipmentSlot, ItemStack> pair : equipment) {
            ItemStack itemStack = pair.getRight();
            EquipmentSlot slot = pair.getLeft();
            if (!itemStack.isEmpty()) {
                if (itemStack.getMaxDamage() != 0 && ((itemStack.getDamage() * 100f) / (itemStack.getMaxDamage() * 100f)) >= 0.92f) {
                    count += itemStack.getItem() instanceof ArmorItem ? getDefense(itemStack, slot) : 2;
                }
            }
        }
        return count;
    }

    private static List<Pair<ItemStack, CustomArmorBar>> getArmorPoints(PlayerEntity player) {
        ArrayList<Pair<ItemStack, CustomArmorBar>> armorItem = new ArrayList<>();
        Stack<Pair<EquipmentSlot, ItemStack>> equipment = new Stack<>();

        for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
            ItemStack itemStack = player.getEquippedStack(equipmentSlot);
            EquippableComponent equippableComponent = itemStack.get(DataComponentTypes.EQUIPPABLE);
            if (equippableComponent != null && equippableComponent.slot() == equipmentSlot) {
                if (getConfig().getOptions().toggleInverseSlot) {
                    equipment.push(new Pair<>(equipmentSlot, itemStack));
                } else {
                    equipment.addFirst(new Pair<>(equipmentSlot, itemStack));
                }
            }
        }

        EntityAttributeInstance attribute = player.getAttributeInstance(EntityAttributes.ARMOR);
        if (attribute != null) {
        	double d = attribute.getBaseValue();
        	for (int i = 0; i < d; i++) {
        		armorItem.add(new Pair<>(ItemStack.EMPTY, CustomArmorBar.DEFAULT));
        	}
        }

        for (Pair<EquipmentSlot, ItemStack> pair : equipment) {
            ItemStack itemStack = pair.getRight();
            EquipmentSlot slot = pair.getLeft();
            if (!itemStack.isEmpty()) {
                if (itemStack.getItem() instanceof ArmorItem armor) {
                    CustomArmorBar barData;
                    if (getConfig().getOptions().toggleArmorTypes) {
                        barData = DetailArmorBarAPI.getArmorBarList().getOrDefault(armor, CustomArmorBar.DEFAULT);
                    }
//                    else if (getConfig().getOptions().toggleNetherites) {
//                        barData = DetailArmorBarAPI.getArmorBarList().getOrDefault(armor, CustomArmorBar.DEFAULT);
//                    }
                    else {
                        barData = CustomArmorBar.DEFAULT;
                    }

                    for (int i = 0; i < getDefense(itemStack, slot); i++) {
                        armorItem.add(new Pair<>(itemStack, barData));
                    }
                } else if (getConfig().getOptions().toggleItemBar && !getConfig().getOptions().toggleSortSpecialItem
                        && DetailArmorBarAPI.getItemBarList().containsKey(itemStack.getItem())) {
                    if (armorItem.size() % 2 == 1)
                        armorItem.add(new Pair<>(ItemStack.EMPTY, CustomArmorBar.EMPTY));

                    var barData = DetailArmorBarAPI.getItemBarList().get(itemStack.getItem());
                    armorItem.add(new Pair<>(itemStack, barData));
                    armorItem.add(new Pair<>(itemStack, barData));
                }
            }
        }

        if (getConfig().getOptions().toggleItemBar && getConfig().getOptions().toggleSortSpecialItem) {
            for (Pair<EquipmentSlot, ItemStack> pair : equipment) {
                ItemStack itemStack = pair.getRight();
                if (!itemStack.isEmpty() && !(itemStack.getItem() instanceof ArmorItem) && DetailArmorBarAPI.getItemBarList().containsKey(itemStack.getItem())) {
                    if (armorItem.size() % 2 == 1)
                        armorItem.add(new Pair<>(ItemStack.EMPTY, CustomArmorBar.EMPTY));

                    var barData = DetailArmorBarAPI.getItemBarList().get(itemStack.getItem());
                    armorItem.add(new Pair<>(itemStack, barData));
                    armorItem.add(new Pair<>(itemStack, barData));
                }
            }
        }
        return armorItem;
    }

    private static int getDefense(ItemStack itemStack, EquipmentSlot slot) {
        AttributeModifiersComponent modifier = itemStack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        for (AttributeModifiersComponent.Entry entry : modifier.modifiers()) {
            if (entry.slot().matches(slot) && entry.attribute().equals(EntityAttributes.ARMOR)) {
                return (int) entry.modifier().value();
            }
        }
        return 0;
    }



    private final MinecraftClient client = MinecraftClient.getInstance();
    private final InGameHud hud = client.inGameHud;

    public void render(DrawContext context, PlayerEntity player) {
        var generic = getEnchantLevel(player.getArmorItems(), Enchantments.PROTECTION);
        var projectile = getEnchantLevel(player.getArmorItems(), Enchantments.PROJECTILE_PROTECTION);
        var explosive = getEnchantLevel(player.getArmorItems(), Enchantments.BLAST_PROTECTION);
        var fire = getEnchantLevel(player.getArmorItems(), Enchantments.FIRE_PROTECTION);
        var protectArr = new int[] { generic.level + generic.count, projectile.level, explosive.level, fire.level, 0 };
        var armorPoints = getArmorPoints(player);
        var thorns = getEnchantLevel(player.getArmorItems(), Enchantments.THORNS);

        var playerHealth = MathHelper.ceil(player.getHealth());
        var totalArmorPoint = armorPoints.size();
        var totalEnchants = Arrays.stream(protectArr).sum();
        var maxHealth = Math.max(player.getAttributeValue(EntityAttributes.MAX_HEALTH), playerHealth);
        var absorptionHealth = MathHelper.ceil(player.getAbsorptionAmount());
        var healthRow = getConfig().getOptions().toggleCompatibleHeartMod ? 1 : MathHelper.ceil((maxHealth + absorptionHealth) / 20.0f);
        var screenWidth = client.getWindow().getScaledWidth() / 2 - 91;
        var screenHeight = client.getWindow().getScaledHeight() - 39;
        var yPos = screenHeight - (healthRow - 1) * Math.max(10 - (healthRow - 2), 3) - 10;

        RenderSystem.enableBlend();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX);

        int stackCount = (totalArmorPoint - 1) / 20;

        //Default
        if (totalArmorPoint > 0) {

            for (int stackIndex = 0; stackIndex < stackCount + 1; stackIndex++) {
                for (int armorIndex = 0; armorIndex < 10; armorIndex++) {
                    int xPos = screenWidth + armorIndex * 8;
                    int yPos2 = yPos - stackIndex * 10;
                    int index = stackIndex * 20 + armorIndex * 2;

                    if (index + 1 < totalArmorPoint) {
                        Pair<ItemStack, CustomArmorBar> am1 = armorPoints.get(index);
                        Pair<ItemStack, CustomArmorBar> am2 = armorPoints.get(index + 1);
                        if (am1.getRight() == am2.getRight()) {
                            am1.getRight().draw(am1.getLeft(), context, xPos, yPos2, false, false);
                        } else {
                            am2.getRight().draw(am2.getLeft(), context, xPos, yPos2, true, true);
                            am1.getRight().draw(am1.getLeft(), context, xPos, yPos2, true, false);
                        }
                    }
                    if (index + 1 == totalArmorPoint) {
                        CustomArmorBar.EMPTY.draw(ItemStack.EMPTY, context, xPos, yPos2, false, false);
                        Pair<ItemStack, CustomArmorBar> am = armorPoints.get(index);
                        am.getRight().draw(am.getLeft(), context, xPos, yPos2, true, false);
                    }
                    if (index + 1 > totalArmorPoint) {
                        CustomArmorBar.EMPTY.draw(ItemStack.EMPTY, context, xPos, yPos2, false, false);
                    }
                }
            }
        }

        //Durability Color
        if (getConfig().getOptions().toggleDurability) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();

            for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
                ItemStack itemStack = player.getEquippedStack(equipmentSlot);
                EquippableComponent equippableComponent = itemStack.get(DataComponentTypes.EQUIPPABLE);
                if (equippableComponent != null && equippableComponent.slot() == equipmentSlot) {
                    equipment.add(new Pair<>(equipmentSlot, itemStack));
                }
            }

            int lowDur = getLowDurabilityItem(equipment);

            if (totalArmorPoint != 0 && lowDur != 0) {
                Color lowDurColor = getLowDurabilityColor();
                if (lowDurColor.getAlpha() != 0) {
                    for (int stackIndex = 0; stackIndex < stackCount + 1; stackIndex++) {
                        for (int armorIndex = 0; armorIndex < 10; armorIndex++) {
                            int xPos = screenWidth + armorIndex * 8;
                            int yPos2 = yPos - stackIndex * 10;
                            int index = stackIndex * 20 + armorIndex * 2;
                            if (lowDur <= 0) break;
                            Pair<ItemStack, CustomArmorBar> am = armorPoints.get(index);
                            if (lowDur == 1) {
                                am.getRight().drawOutLine(am.getLeft(), context, xPos, yPos2, true, false, lowDurColor);
                                lowDur = 0;
                            } else {
                                am.getRight().drawOutLine(am.getLeft(), context, xPos, yPos2, false, false, lowDurColor);
                                lowDur -= 2;
                            }
                        }
                    }
                }
            }
        }

        //Mending Color
        if (getConfig().getOptions().toggleMending && totalArmorPoint != 0) {
            var mendingTime = DetailArmorBar.getTicks() - LAST_MENDING;
            var mendingSpeed = 3;

            if (mendingTime < (mendingSpeed * 4)) {
                for (int stackIndex = 0; stackIndex < stackCount + 1; stackIndex++) {
                    for (int armorIndex = 0; armorIndex < 10; armorIndex++) {
                        int xPos = screenWidth + armorIndex * 8;
                        int yPos2 = yPos - stackIndex * 10;
                        int index = stackIndex * 20 + armorIndex * 2;

                        if (mendingTime % (mendingSpeed * 2) < mendingSpeed) {
                            if (armorPoints.size() <= index) {
                                if (getConfig().getOptions().toggleEmptyBar)
                                    CustomArmorBar.DEFAULT.drawOutLine(ItemStack.EMPTY, context, xPos, yPos2, false, false, Color.WHITE);
                            } else {
                                Pair<ItemStack, CustomArmorBar> am = armorPoints.get(index);
                                am.getRight().drawOutLine(am.getLeft(), context, xPos, yPos2, false, false, Color.WHITE);
                            }
                        }
                    }
                }
            }
        }

        RenderSystem.setShaderTexture(0, GUI_ARMOR_BAR);

        //Armor Enchantments
        if (getConfig().getOptions().toggleEnchants && totalEnchants > 0 && totalArmorPoint > 0) {
            for (int stackIndex = 0; stackIndex < stackCount + 1; stackIndex++) {
                for (int armorIndex = 0; armorIndex < 10; armorIndex++) {
                    int xPos = screenWidth + armorIndex * 8;
                    int yPos2 = yPos - stackIndex * 10;
                    int index = stackIndex * 20 + armorIndex * 2;

                    if (index + 1 > totalEnchants) break;

                    if (index + 1 < totalEnchants) {
                        var min = -1;
                        var max = -1;
                        for (int pw = 0; pw < 5; pw++) {
                            if (min == -1 && protectArr[pw] > 1) {
                                min = pw;
                                break;
                            } else if (min == -1 && protectArr[pw] == 1) {
                                min = pw;
                            } else if (min != -1 && max == -1 && protectArr[pw] >= 1) max = pw;
                        }
                        if (min != -1 && max != -1) {
                            drawEnchantTexture(context, xPos, yPos2, getProtectColor(protectArr), 2);
                            protectArr[min] = 0;
                            drawEnchantTexture(context, xPos, yPos2, getProtectColor(protectArr), 1);
                            protectArr[max] -= 1;
                        } else {
                            drawEnchantTexture(context, xPos, yPos2, getProtectColor(protectArr), 0);
                            protectArr[min] -= 2;
                        }
                    }
                    if (index + 1 == totalEnchants) {
                        drawEnchantTexture(context, xPos, yPos2, getProtectColor(protectArr), 2);
                    }
                }
            }
        }

        //Thorns Check
        if (getConfig().getOptions().toggleThorns && thorns.level > 0 && totalArmorPoint > 0) {
            Color thornsColor = getThornColor();
            for (int stackIndex = 0; stackIndex < stackCount + 1; stackIndex++) {
                for (int armorIndex = 0; armorIndex < 10; armorIndex++) {
                    int xPos = screenWidth + armorIndex * 8;
                    int yPos2 = yPos - stackIndex * 10;
                    int index = stackIndex * 20 + armorIndex * 2;

                    if (index + 1 > thorns.level) break;

                    if (index + 1 < thorns.level) {
                        InGameDrawer.drawTexture(GUI_ARMOR_BAR, context, xPos, yPos2, 36, 18, thornsColor, false);
                    }
                    if (index + 1 == thorns.level) {
                        InGameDrawer.drawTexture(GUI_ARMOR_BAR, context, xPos, yPos2, 27, 18, thornsColor, false);
                    }
                }
            }
        }

        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private void drawEnchantTexture(DrawContext context, int x, int y, Color color, int half) {
        int u = 0;
        int v = 0;
        var t = (hud.getTicks()/3) % 36;

        if (getConfig().getOptions().effectType == ProtectionEffect.AURA) {
            if (t < 12) {
                u = (t % 12) * 9;
                v = 27 + (half * 9);
            }
        } else if (getConfig().getOptions().effectType == ProtectionEffect.OUTLINE) {
            u = 9 + (half * 9);
        } else return;

        InGameDrawer.drawTexture(GUI_ARMOR_BAR, context, x, y, u, v, color, false);
    }
}
