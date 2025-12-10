package com.example.addon.utils.hypixel;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class HypixelUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ==================== ITEM DETECTION ====================

    public static boolean isSummoningEye(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.PLAYER_HEAD) return false;
        try {
            String displayName = stack.getName().getString();
            if (displayName.contains("Summoning Eye")) return true;

            var customData = stack.getComponents().get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
            if (customData == null) return false;
            var nbt = customData.copyNbt();

            if (nbt.contains("ExtraAttributes")) {
                var extraAttributes = nbt.get("ExtraAttributes");
                if (extraAttributes instanceof NbtCompound compound) {
                    if (compound.contains("id") && compound.get("id") instanceof NbtString nbtString) {
                        if ("SUMMONING_EYE".equals(nbtString.asString())) return true;
                    }
                }
            }
            if (nbt.contains("id") && nbt.get("id") instanceof NbtString nbtString) {
                if ("SUMMONING_EYE".equals(nbtString.asString())) return true;
            }
        } catch (Exception e) { }
        return false;
    }

    public static boolean isRemnant(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.PLAYER_HEAD) return false;
        try {
            String displayName = stack.getName().getString();
            if (displayName.contains("Remnant of the Eye")) return true;

            var customData = stack.getComponents().get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
            if (customData == null) return false;
            var nbt = customData.copyNbt();

            if (nbt.contains("ExtraAttributes")) {
                var extraAttributes = nbt.get("ExtraAttributes");
                if (extraAttributes instanceof NbtCompound compound) {
                    if (compound.contains("id") && compound.get("id") instanceof NbtString nbtString) {
                        if ("REMNANT_OF_THE_EYE".equals(nbtString.asString())) return true;
                    }
                }
            }
        } catch (Exception e) { }
        return false;
    }

    public static boolean isAOTV(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.DIAMOND_SHOVEL) return false;
        try {
            String displayName = stack.getName().getString();
            if (displayName.contains("Aspect of the Void")) return true;

            var customData = stack.getComponents().get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
            if (customData == null) return false;
            var nbt = customData.copyNbt();

            if (nbt.contains("ExtraAttributes")) {
                var extraAttributes = nbt.get("ExtraAttributes");
                if (extraAttributes instanceof NbtCompound compound) {
                    if (compound.contains("id") && compound.get("id") instanceof NbtString nbtString) {
                        if ("ASPECT_OF_THE_VOID".equals(nbtString.asString())) return true;
                    }
                }
            }
            if (nbt.contains("id") && nbt.get("id") instanceof NbtString nbtString) {
                if ("ASPECT_OF_THE_VOID".equals(nbtString.asString())) return true;
            }
        } catch (Exception e) { }
        return false;
    }

    // ==================== INVENTORY COUNTING ====================

    public static int countSummoningEyes() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (isSummoningEye(mc.player.getInventory().getStack(i))) count++;
        }
        return count;
    }

    public static int countSummoningEyesInHotbar() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            if (isSummoningEye(mc.player.getInventory().getStack(i))) count++;
        }
        return count;
    }

    // ==================== SLOT FINDING ====================

    public static int findSummoningEyeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isSummoningEye(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    public static int findSummoningEyeInMainInventory() {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            if (isSummoningEye(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    public static int findRemnantSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (isRemnant(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    public static int findBowSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.BOW) return i;
        }
        return -1;
    }

    public static int findAOTVSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isAOTV(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    // ==================== PORTAL FRAME OPERATIONS ====================

    public static BlockPos findVisibleEndPortalFrame(int range) {
        if (mc.player == null || mc.world == null) return null;
        BlockPos playerPos = mc.player.getBlockPos();

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, range, range, range)) {
            if (mc.world.getBlockState(pos).getBlock() == Blocks.END_PORTAL_FRAME) {
                if (!mc.world.getBlockState(pos).get(EndPortalFrameBlock.EYE)) {
                    if (isBlockVisible(pos)) return pos;
                }
            }
        }
        return null;
    }

    public static boolean isBlockVisible(BlockPos pos) {
        if (mc.player == null || mc.world == null) return false;
        Vec3d playerEyes = mc.player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(pos);

        HitResult result = mc.world.raycast(new net.minecraft.world.RaycastContext(
            playerEyes, blockCenter,
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,
            mc.player
        ));

        if (result.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) result).getBlockPos().equals(pos);
        }
        return false;
    }

    public static void placeEyeOnPortalFrame(BlockPos framePos) {
        if (mc.interactionManager == null || mc.player == null) return;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(framePos), Direction.UP, framePos, false));
    }

    // ==================== ROTATION UTILITIES ====================

    public static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    public static float lerpAngle(float start, float end, float progress) {
        start = normalizeAngle(start);
        end = normalizeAngle(end);
        float diff = end - start;
        if (diff > 180) diff -= 360;
        else if (diff < -180) diff += 360;
        return normalizeAngle(start + diff * progress);
    }

    public static float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180) angle -= 360;
        else if (angle < -180) angle += 360;
        return angle;
    }

    public static void calculateAimAngles(BlockPos targetPos, float[] result) {
        if (mc.player == null) return;
        Vec3d targetCenter = Vec3d.ofCenter(targetPos);
        Vec3d playerEyes = mc.player.getEyePos();

        result[0] = (float) Math.toDegrees(Math.atan2(targetCenter.z - playerEyes.z,
            targetCenter.x - playerEyes.x)) - 90.0f;

        double deltaX = targetCenter.x - playerEyes.x;
        double deltaY = targetCenter.y - playerEyes.y;
        double deltaZ = targetCenter.z - playerEyes.z;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        result[1] = (float) -Math.toDegrees(Math.atan2(deltaY, distance));
    }

    // ==================== TELEPORTATION ====================

    public static void teleportToBlock(BlockPos targetPos, int slot) {
        if (mc.player == null || mc.interactionManager == null) return;
        InvUtils.swap(slot, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false));
    }

    // ==================== BIOME CHECKING ====================

    public static boolean isInTheEnd() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos playerPos = mc.player.getBlockPos();
        String biome = mc.world.getBiome(playerPos).getIdAsString();
        return biome.equals("minecraft:the_end");
    }

    public static boolean isAtSpecificLocation(int x, int y, int z) {
        if (mc.player == null) return false;
        BlockPos playerPos = mc.player.getBlockPos();
        return playerPos.getX() == x && playerPos.getY() == y && playerPos.getZ() == z;
    }
}
