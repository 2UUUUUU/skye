package com.example.addon.utils.smp;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

public class SMPUtils {

    // ==================== SOUND DETECTION ====================

    public static boolean isBlockBreakSound(String soundId) {
        return soundId.contains("block") &&
            (soundId.contains("break") ||
                soundId.contains("destroy") ||
                soundId.contains("hit"));
    }

    public static boolean isBlockPlaceSound(String soundId) {
        return soundId.contains("block") &&
            (soundId.contains("place") ||
                soundId.contains("step"));
    }

    // ==================== PLAYER DETECTION ====================

    public static void disableAutoReconnectIfEnabled(Module module) {
        Module autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            module.info("AutoReconnect disabled due to player detection");
        }
    }

    public static String getEntityName(Entity entity) {
        if (entity instanceof PlayerEntity playerEntity) {
            return "Player: " + playerEntity.getGameProfile().name();
        }
        return entity.getType().toString();
    }

    public static String getPlayerName(PlayerEntity playerEntity) {
        return playerEntity.getGameProfile().name();
    }

    public static boolean isPlayerWhitelisted(String playerName, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) return false;
        return whitelist.stream().anyMatch(name -> name.equalsIgnoreCase(playerName));
    }

    // ==================== SPAWNER OPERATIONS ====================

    public static BlockPos findNearestSpawner(int range) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearestSpawner = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
            playerPos.add(-range, -range, -range),
            playerPos.add(range, range, range))) {

            if (mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                double distance = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestSpawner = pos.toImmutable();
                }
            }
        }

        return nearestSpawner;
    }

    // ==================== CHEST OPERATIONS ====================

    public static BlockPos findNearestEnderChest() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearestChest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
            playerPos.add(-16, -8, -16),
            playerPos.add(16, 8, 16))) {

            if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                double distance = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestChest = pos.toImmutable();
                }
            }
        }

        return nearestChest;
    }

    public static BlockPos findNearestChestForBones() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearestChest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
            playerPos.add(-16, -8, -16),
            playerPos.add(16, 8, 16))) {

            if (mc.world.getBlockState(pos).getBlock() == Blocks.CHEST) {
                double distance = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestChest = pos.toImmutable();
                }
            }
        }

        return nearestChest;
    }

    // ==================== MOVEMENT & ROTATION ====================

    public static void lookAtBlock(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        Vec3d targetPos = Vec3d.ofCenter(pos);
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(-Math.asin(direction.y));

        mc.player.setYaw((float) yaw);
        mc.player.setPitch((float) pitch);
    }

    public static void moveTowardsBlock(BlockPos target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = Vec3d.ofCenter(target);
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        mc.player.setYaw((float) yaw);

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
    }

    public static void stopMoving() {
        MinecraftClient mc = MinecraftClient.getInstance();
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
    }

    public static void setJumping(boolean jumping) {
        MinecraftClient mc = MinecraftClient.getInstance();
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), jumping);
    }

    // ==================== BLOCK INTERACTION ====================

    public static void breakBlock(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager != null) {
            mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
        }
    }

    public static void stopBreaking() {
        MinecraftClient mc = MinecraftClient.getInstance();
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
    }

    public static void interactWithBlock(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null || mc.player == null) return;

        mc.interactionManager.interactBlock(
            mc.player,
            Hand.MAIN_HAND,
            new BlockHitResult(
                Vec3d.ofCenter(pos),
                Direction.UP,
                pos,
                false
            )
        );
    }

    // ==================== SNEAKING CONTROL ====================

    public static void setSneaking(boolean sneak, boolean[] sneakingState) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        if (sneak && !sneakingState[0]) {
            mc.player.setSneaking(true);
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), true);
            sneakingState[0] = true;
        } else if (!sneak && sneakingState[0]) {
            mc.player.setSneaking(false);
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);
            sneakingState[0] = false;
        }
    }

    // ==================== INVENTORY OPERATIONS ====================

    public static boolean hasItemsToDeposit() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasItemsToDepositFiltered() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && (stack.getItem() == Items.SPAWNER || stack.getItem() == Items.DIAMOND_PICKAXE)) {
                return true;
            }
        }
        return false;
    }

    public static void transferItemsToChest(GenericContainerScreenHandler handler, int[] lastProcessedSlot, int[] transferDelayCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;
        int playerInventoryStart = chestSlots;
        int startSlot = Math.max(lastProcessedSlot[0] + 1, playerInventoryStart);

        for (int i = 0; i < 36; i++) {
            int slotId = playerInventoryStart + ((startSlot - playerInventoryStart + i) % 36);
            ItemStack stack = handler.getSlot(slotId).getStack();

            if (stack.isEmpty() || stack.getItem() == Items.AIR) continue;

            if (mc.interactionManager != null && mc.player != null) {
                mc.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
            }

            lastProcessedSlot[0] = slotId;
            transferDelayCounter[0] = 2;
            return;
        }

        if (lastProcessedSlot[0] >= playerInventoryStart) {
            lastProcessedSlot[0] = playerInventoryStart - 1;
            transferDelayCounter[0] = 3;
        }
    }

    public static void transferFilteredItemsToChest(GenericContainerScreenHandler handler, int[] lastProcessedSlot, int[] transferDelayCounter, Module module) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;
        int playerInventoryStart = chestSlots;
        int startSlot = Math.max(lastProcessedSlot[0] + 1, playerInventoryStart);

        for (int i = 0; i < 36; i++) {
            int slotId = playerInventoryStart + ((startSlot - playerInventoryStart + i) % 36);
            ItemStack stack = handler.getSlot(slotId).getStack();

            if (stack.isEmpty() || stack.getItem() == Items.AIR) continue;
            if (stack.getItem() != Items.SPAWNER && stack.getItem() != Items.DIAMOND_PICKAXE) continue;

            module.info("Transferring item from slot " + slotId + ": " + stack.getItem().toString());

            if (mc.interactionManager != null && mc.player != null) {
                mc.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
            }

            lastProcessedSlot[0] = slotId;
            transferDelayCounter[0] = 2;
            return;
        }

        if (lastProcessedSlot[0] >= playerInventoryStart) {
            lastProcessedSlot[0] = playerInventoryStart - 1;
            transferDelayCounter[0] = 3;
        }
    }

    public static void depositBonesToChest(GenericContainerScreenHandler handler) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int containerSlots = handler.getRows() * 9;

        for (int i = containerSlots; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                if (mc.interactionManager != null && mc.player != null) {
                    mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                }
                return;
            }
        }
    }

    public static boolean playerHasBones() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                return true;
            }
        }
        return false;
    }

    public static int findDiamondPickaxeInHotbar() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.DIAMOND_PICKAXE) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isHoldingDiamondPickaxe() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        ItemStack heldItem = mc.player.getMainHandStack();
        return !heldItem.isEmpty() && heldItem.getItem() == Items.DIAMOND_PICKAXE;
    }

    public static boolean isInventoryFull() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) emptySlots++;
        }
        return emptySlots == 0;
    }

    public static void swapPickaxeToSlot0(int pickaxeSlot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || pickaxeSlot == -1) return;

        ItemStack pickaxe = mc.player.getInventory().getStack(pickaxeSlot);
        ItemStack slot0 = mc.player.getInventory().getStack(0);
        mc.player.getInventory().setStack(0, pickaxe);
        mc.player.getInventory().setStack(pickaxeSlot, slot0);
    }

    // ==================== WEBHOOK UTILITIES ====================

    public static void sendWebhookNotification(
        String webhookUrl,
        String messageContent,
        String detectedEntity,
        long detectionTime,
        boolean emergencyDisconnect,
        String emergencyReason,
        boolean spawnersMinedSuccessfully,
        boolean itemsDepositedSuccessfully,
        Module module
    ) {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) return;

        long discordTimestamp = detectionTime / 1000L;
        String embedJson = createWebhookPayload(
            messageContent,
            detectedEntity,
            discordTimestamp,
            emergencyDisconnect,
            emergencyReason,
            spawnersMinedSuccessfully,
            itemsDepositedSuccessfully
        );

        new Thread(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl.trim()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(embedJson))
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    module.info("Webhook notification sent successfully!");
                } else {
                    ChatUtils.error("Failed to send webhook notification. Status: " + response.statusCode());
                }
            } catch (Exception e) {
                ChatUtils.error("Failed to send webhook notification: " + e.getMessage());
            }
        }).start();
    }

    private static String createWebhookPayload(
        String messageContent,
        String detectedEntity,
        long discordTimestamp,
        boolean emergencyDisconnect,
        String emergencyReason,
        boolean spawnersMinedSuccessfully,
        boolean itemsDepositedSuccessfully
    ) {
        String title = emergencyDisconnect ? "SpawnerProtect Emergency Alert" : "SpawnerProtect Alert";
        String description;

        if (emergencyDisconnect) {
            description = String.format("**Entity Detected:** %s\\n**Detection Time:** <t:%d:R>\\n**Reason:** %s\\n**Disconnected:** Yes",
                escapeJson(detectedEntity), discordTimestamp, escapeJson(emergencyReason));
        } else {
            description = String.format("**Entity Detected:** %s\\n**Detection Time:** <t:%d:R>\\n**Spawners Mined:** %s\\n**Items Deposited:** %s\\n**Disconnected:** Yes",
                escapeJson(detectedEntity), discordTimestamp,
                spawnersMinedSuccessfully ? "✅ Success" : "❌ Failed",
                itemsDepositedSuccessfully ? "✅ Success" : "❌ Failed");
        }

        int color = emergencyDisconnect ? 16711680 : 16766720;

        return String.format("""
            {
                "username": "Skye",
                "avatar_url": "https://imgur.com/a/Sph1HYr",
                "content": "%s",
                "embeds": [{
                    "title": "%s",
                    "description": "%s",
                    "color": %d,
                    "timestamp": "%s",
                    "footer": {
                        "text": "Sent by Skye"
                    }
                }]
            }""",
            escapeJson(messageContent),
            title,
            description,
            color,
            Instant.now().toString()
        );
    }

    public static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    // ==================== PROFIT CALCULATION ====================

    public static String calculateRandomizedAmount(String amountStr, int minPercent, int maxPercent) {
        double multiplier = 1.0;
        String numericPart = amountStr;
        String suffix = "";

        if (amountStr.endsWith("K")) {
            multiplier = 1000.0;
            numericPart = amountStr.substring(0, amountStr.length() - 1);
            suffix = "K";
        } else if (amountStr.endsWith("M")) {
            multiplier = 1000000.0;
            numericPart = amountStr.substring(0, amountStr.length() - 1);
            suffix = "M";
        } else if (amountStr.endsWith("B")) {
            multiplier = 1000000000.0;
            numericPart = amountStr.substring(0, amountStr.length() - 1);
            suffix = "B";
        }

        try {
            double baseValue = Double.parseDouble(numericPart) * multiplier;

            int actualMin = Math.min(minPercent, maxPercent);
            int actualMax = Math.max(minPercent, maxPercent);
            double randomPercent = actualMin + (Math.random() * (actualMax - actualMin));

            double randomizedValue = baseValue * (randomPercent / 100.0);

            if (suffix.equals("K")) {
                return String.format("%.2fK", randomizedValue / 1000.0);
            } else if (suffix.equals("M")) {
                return String.format("%.2fM", randomizedValue / 1000000.0);
            } else if (suffix.equals("B")) {
                return String.format("%.2fB", randomizedValue / 1000000000.0);
            } else {
                return String.format("%.2f", randomizedValue);
            }
        } catch (NumberFormatException e) {
            return amountStr;
        }
    }
}
