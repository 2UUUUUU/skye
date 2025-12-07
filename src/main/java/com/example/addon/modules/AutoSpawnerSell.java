package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoSpawnerSell extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSendProfits = settings.createGroup("Send Profits");
    private final SettingGroup sgFailsafe = settings.createGroup("Failsafe");

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay in ticks between actions")
        .defaultValue(10)
        .min(0)
        .max(100)
        .build()
    );

    private final Setting<Integer> inventoryClosingDelay = sgGeneral.add(new IntSetting.Builder()
        .name("inventory-closing-delay")
        .description("Delay in ticks between closing inventories after successful delivery")
        .defaultValue(10)
        .min(0)
        .max(100)
        .build()
    );

    private final Setting<Boolean> orderExpiredCheck = sgGeneral.add(new BoolSetting.Builder()
        .name("order-expired-check")
        .description("Enable checking if order expired after confirmation")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> orderExpiredTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("order-expired-timeout")
        .description("Ticks to wait for chat confirmation message")
        .defaultValue(100)
        .min(20)
        .max(400)
        .visible(orderExpiredCheck::get)
        .build()
    );

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Delay in ticks before restarting after successful delivery")
        .defaultValue(100)
        .min(0)
        .max(72000)
        .build()
    );

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-range")
        .description("Range to search for spawner blocks")
        .defaultValue(5)
        .min(1)
        .max(10)
        .build()
    );

    private final Setting<Integer> spawnerTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-timeout")
        .description("Ticks to wait for spawner menu to open before retrying")
        .defaultValue(40)
        .min(10)
        .max(200)
        .build()
    );

    private final Setting<Integer> pauseSequence = sgGeneral.add(new IntSetting.Builder()
        .name("pause-sequence")
        .description("Delay in ticks to pause when not enough bones in spawner")
        .defaultValue(200)
        .min(20)
        .max(72000)
        .build()
    );

    private final Setting<Boolean> enableSendProfits = sgSendProfits.add(new BoolSetting.Builder()
        .name("enable")
        .description("Enable automatic profit sending")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> profitPlayer = sgSendProfits.add(new StringSetting.Builder()
        .name("player")
        .description("Player name to send profits to")
        .defaultValue("")
        .visible(enableSendProfits::get)
        .build()
    );

    private final Setting<Boolean> randomizeAmount = sgSendProfits.add(new BoolSetting.Builder()
        .name("randomize-amount")
        .description("Randomize the amount sent")
        .defaultValue(false)
        .visible(enableSendProfits::get)
        .build()
    );

    private final Setting<Integer> minimumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("minimum-percent")
        .description("Minimum percentage of profit to send")
        .defaultValue(20)
        .min(0)
        .max(100)
        .visible(() -> enableSendProfits.get() && randomizeAmount.get())
        .build()
    );

    private final Setting<Integer> maximumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("maximum-percent")
        .description("Maximum percentage of profit to send")
        .defaultValue(100)
        .min(0)
        .max(100)
        .visible(() -> enableSendProfits.get() && randomizeAmount.get())
        .build()
    );

    private final Setting<Boolean> enableFailsafe = sgFailsafe.add(new BoolSetting.Builder()
        .name("enable-failsafe")
        .description("Enable player detection failsafe")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> failsafeDistance = sgFailsafe.add(new IntSetting.Builder()
        .name("failsafe-distance")
        .description("Distance to detect players for failsafe")
        .defaultValue(16)
        .min(1)
        .max(50)
        .visible(enableFailsafe::get)
        .build()
    );

    private final Setting<Integer> shulkerRange = sgFailsafe.add(new IntSetting.Builder()
        .name("shulker-range")
        .description("Range to search for shulker boxes")
        .defaultValue(5)
        .min(1)
        .max(10)
        .visible(enableFailsafe::get)
        .build()
    );

    private enum State {
        IDLE,
        FINDING_SPAWNER,
        OPENING_SPAWNER,
        WAITING_SPAWNER_MENU,
        CHECKING_SPAWNER_CONTENTS,
        PAUSED_NOT_ENOUGH_BONES,
        CLICK_SLOT_50,
        SENDING_ORDER_COMMAND,
        WAITING_ORDER_MENU,
        CLICK_BONE_SLOT,
        WAITING_DEPOSIT_MENU,
        DEPOSITING_BONES,
        CLOSING_DEPOSIT_MENU,
        WAITING_CONFIRM_MENU,
        CLICK_CONFIRM_SLOT,
        WAITING_FOR_CHAT_CONFIRMATION,
        CLOSING_FIRST_INVENTORY,
        CLOSING_SECOND_INVENTORY,
        LOOP_DELAY,
        RETRY_SEQUENCE,
        FAILSAFE_FINDING_SHULKER,
        FAILSAFE_OPENING_SHULKER,
        FAILSAFE_DEPOSITING
    }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private int timeoutCounter = 0;
    private int spawnerTimeoutCounter = 0;
    private BlockPos targetSpawner = null;
    private BlockPos targetShulker = null;
    private boolean chatMessageReceived = false;
    private boolean waitingForChatMessage = false;
    private boolean waitingForProfitMessage = false;

    public AutoSpawnerSell() {
        super(AddonTemplate.CATEGORY, "auto-spawner-sell", "Automatically drops bones from spawner and sells them");
    }

    @Override
    public void onActivate() {
        currentState = State.FINDING_SPAWNER;
        delayCounter = 0;
        timeoutCounter = 0;
        spawnerTimeoutCounter = 0;
        targetSpawner = null;
        targetShulker = null;
        chatMessageReceived = false;
        waitingForChatMessage = false;
        waitingForProfitMessage = false;
        info("AutoSpawnerSell activated - searching for spawner...");
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String message = event.getMessage().getString();

        // Check for delivery confirmation message
        if (waitingForChatMessage) {
            if (message.contains("You delivered") && message.contains("Bones") && message.contains("received")) {
                info("Delivery confirmation received!");
                chatMessageReceived = true;

                // If send profits is enabled, flag to wait for profit extraction
                if (enableSendProfits.get() && !profitPlayer.get().isEmpty()) {
                    waitingForProfitMessage = true;
                }
            }
        }

        // Check for profit amount and send payment
        if (waitingForProfitMessage) {
            if (message.contains("You delivered") && message.contains("Bones") && message.contains("received")) {
                // Pattern to match "You delivered X Bones and received $Y"
                Pattern pattern = Pattern.compile("You delivered .+ Bones and received \\$([0-9.KMB]+)");
                Matcher matcher = pattern.matcher(message);

                if (matcher.find()) {
                    String profitAmount = matcher.group(1);
                    String playerName = profitPlayer.get();

                    // Calculate the amount to send (with randomization if enabled)
                    String amountToSend = profitAmount;
                    if (randomizeAmount.get()) {
                        amountToSend = calculateRandomizedAmount(profitAmount);
                    }

                    info("Sending profit of $" + amountToSend + " to " + playerName);
                    mc.getNetworkHandler().sendChatCommand("pay " + playerName + " " + amountToSend);
                    waitingForProfitMessage = false;
                }
            }
        }
    }

    private String calculateRandomizedAmount(String amountStr) {
        // Parse the amount (could be in format like "43K", "2.5M", "123.45", etc.)
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

            // Get random percentage between min and max
            int minPercent = Math.min(minimumPercent.get(), maximumPercent.get());
            int maxPercent = Math.max(minimumPercent.get(), maximumPercent.get());
            double randomPercent = minPercent + (random.nextDouble() * (maxPercent - minPercent));

            // Calculate the randomized amount
            double randomizedValue = baseValue * (randomPercent / 100.0);

            // Format back with suffix
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
            warning("Failed to parse profit amount: " + amountStr);
            return amountStr; // Return original if parsing fails
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Check for players (failsafe)
        if (enableFailsafe.get() && checkForPlayers()) {
            return;
        }

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;

        switch (currentState) {
            case IDLE:
                currentState = State.FINDING_SPAWNER;
                break;
            case FINDING_SPAWNER:
                handleFindingSpawner();
                break;
            case OPENING_SPAWNER:
                handleOpeningSpawner();
                break;
            case WAITING_SPAWNER_MENU:
                handleWaitingSpawnerMenu(handler);
                break;
            case CHECKING_SPAWNER_CONTENTS:
                handleCheckingSpawnerContents(handler);
                break;
            case PAUSED_NOT_ENOUGH_BONES:
                handlePausedNotEnoughBones();
                break;
            case CLICK_SLOT_50:
                handleClickSlot50(handler);
                break;
            case SENDING_ORDER_COMMAND:
                handleSendingOrderCommand();
                break;
            case WAITING_ORDER_MENU:
                handleWaitingOrderMenu(handler);
                break;
            case CLICK_BONE_SLOT:
                handleClickBoneSlot(handler);
                break;
            case WAITING_DEPOSIT_MENU:
                handleWaitingDepositMenu(handler);
                break;
            case DEPOSITING_BONES:
                handleDepositingBones(handler);
                break;
            case CLOSING_DEPOSIT_MENU:
                handleClosingDepositMenu();
                break;
            case WAITING_CONFIRM_MENU:
                handleWaitingConfirmMenu(handler);
                break;
            case CLICK_CONFIRM_SLOT:
                handleClickConfirmSlot(handler);
                break;
            case WAITING_FOR_CHAT_CONFIRMATION:
                handleWaitingForChatConfirmation();
                break;
            case CLOSING_FIRST_INVENTORY:
                handleClosingFirstInventory();
                break;
            case CLOSING_SECOND_INVENTORY:
                handleClosingSecondInventory();
                break;
            case LOOP_DELAY:
                handleLoopDelay();
                break;
            case RETRY_SEQUENCE:
                handleRetrySequence();
                break;
            case FAILSAFE_FINDING_SHULKER:
                handleFailsafeFindingShulker();
                break;
            case FAILSAFE_OPENING_SHULKER:
                handleFailsafeOpeningShulker();
                break;
            case FAILSAFE_DEPOSITING:
                handleFailsafeDepositing(handler);
                break;
        }
    }

    private boolean checkForPlayers() {
        if (currentState == State.FAILSAFE_FINDING_SHULKER ||
            currentState == State.FAILSAFE_OPENING_SHULKER ||
            currentState == State.FAILSAFE_DEPOSITING) {
            return false; // Already in failsafe mode
        }

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!(player instanceof AbstractClientPlayerEntity)) continue;

            double distance = mc.player.distanceTo(player);
            if (distance <= failsafeDistance.get()) {
                warning("Player detected within range! Activating failsafe...");
                mc.player.closeHandledScreen();
                currentState = State.FAILSAFE_FINDING_SHULKER;
                delayCounter = 0;
                return true;
            }
        }
        return false;
    }

    private void handleFailsafeFindingShulker() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearestShulker = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
            playerPos.add(-shulkerRange.get(), -shulkerRange.get(), -shulkerRange.get()),
            playerPos.add(shulkerRange.get(), shulkerRange.get(), shulkerRange.get()))) {

            if (mc.world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock) {
                double distance = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestShulker = pos.toImmutable();
                }
            }
        }

        if (nearestShulker != null) {
            targetShulker = nearestShulker;
            info("Found shulker box at " + targetShulker);
            currentState = State.FAILSAFE_OPENING_SHULKER;
            delayCounter = actionDelay.get();
        } else {
            error("No shulker box found! Disabling module...");
            toggle();
        }
    }

    private void handleFailsafeOpeningShulker() {
        if (targetShulker == null) {
            currentState = State.FAILSAFE_FINDING_SHULKER;
            return;
        }

        Vec3d targetPos = Vec3d.ofCenter(targetShulker);
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(-Math.asin(direction.y));

        mc.player.setYaw((float) yaw);
        mc.player.setPitch((float) pitch);

        if (mc.interactionManager != null) {
            info("Opening shulker box");
            mc.interactionManager.interactBlock(
                mc.player,
                Hand.MAIN_HAND,
                new BlockHitResult(
                    Vec3d.ofCenter(targetShulker),
                    Direction.UP,
                    targetShulker,
                    false
                )
            );
        }

        currentState = State.FAILSAFE_DEPOSITING;
        delayCounter = actionDelay.get() * 2;
    }

    private void handleFailsafeDepositing(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler)) {
            delayCounter = actionDelay.get();
            return;
        }

        GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
        int containerSlots = container.getRows() * 9;

        boolean foundBones = false;
        for (int i = containerSlots; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                info("Depositing bones to shulker from slot " + i);
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                delayCounter = actionDelay.get();
                foundBones = true;
                return;
            }
        }

        if (!foundBones) {
            info("All bones deposited to shulker. Disabling module...");
            mc.player.closeHandledScreen();
            toggle();
        }
    }

    private void handleFindingSpawner() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearestSpawner = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
            playerPos.add(-spawnerRange.get(), -spawnerRange.get(), -spawnerRange.get()),
            playerPos.add(spawnerRange.get(), spawnerRange.get(), spawnerRange.get()))) {

            if (mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                double distance = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestSpawner = pos.toImmutable();
                }
            }
        }

        if (nearestSpawner != null) {
            targetSpawner = nearestSpawner;
            info("Found spawner at " + targetSpawner);
            currentState = State.OPENING_SPAWNER;
            delayCounter = actionDelay.get();
            spawnerTimeoutCounter = 0;
        } else {
            warning("No spawner found in range!");
            delayCounter = 40;
        }
    }

    private void handleOpeningSpawner() {
        if (targetSpawner == null) {
            currentState = State.FINDING_SPAWNER;
            return;
        }

        Vec3d targetPos = Vec3d.ofCenter(targetSpawner);
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(-Math.asin(direction.y));

        mc.player.setYaw((float) yaw);
        mc.player.setPitch((float) pitch);

        if (mc.interactionManager != null) {
            info("Right-clicking spawner at " + targetSpawner);
            mc.interactionManager.interactBlock(
                mc.player,
                Hand.MAIN_HAND,
                new BlockHitResult(
                    Vec3d.ofCenter(targetSpawner),
                    Direction.UP,
                    targetSpawner,
                    false
                )
            );
        }

        currentState = State.WAITING_SPAWNER_MENU;
        delayCounter = actionDelay.get() * 2;
        spawnerTimeoutCounter = 0;
    }

    private void handleWaitingSpawnerMenu(ScreenHandler handler) {
        spawnerTimeoutCounter++;

        if (handler instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
            if (container.getRows() == 6) {
                info("Spawner menu opened (6 rows)");
                currentState = State.CHECKING_SPAWNER_CONTENTS;
                delayCounter = actionDelay.get();
                spawnerTimeoutCounter = 0;
                return;
            }
        }

        // Check if timeout has been reached
        if (spawnerTimeoutCounter >= spawnerTimeout.get()) {
            warning("Spawner menu timeout - retrying to open spawner...");
            currentState = State.OPENING_SPAWNER;
            delayCounter = actionDelay.get();
            spawnerTimeoutCounter = 0;
        }
    }

    private void handleCheckingSpawnerContents(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
        int firstFiveRows = 5 * 9; // First 5 rows = 45 slots

        // Check if all slots in the first 5 rows contain bones
        for (int i = 0; i < firstFiveRows; i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (stack.isEmpty() || stack.getItem() != Items.BONE) {
                warning("Not enough bones in spawner! Pausing sequence...");
                mc.player.closeHandledScreen();
                currentState = State.PAUSED_NOT_ENOUGH_BONES;
                delayCounter = pauseSequence.get();
                return;
            }
        }

        info("Spawner contents verified - all first 5 rows contain bones");
        currentState = State.CLICK_SLOT_50;
        delayCounter = actionDelay.get();
    }

    private void handlePausedNotEnoughBones() {
        info("Pause complete, restarting sequence from spawner");
        currentState = State.FINDING_SPAWNER;
        delayCounter = actionDelay.get() * 2;
    }

    private void handleClickSlot50(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        info("Clicking slot 50 in spawner menu");
        mc.interactionManager.clickSlot(handler.syncId, 50, 0, SlotActionType.PICKUP, mc.player);
        currentState = State.SENDING_ORDER_COMMAND;
        delayCounter = actionDelay.get();
    }

    private void handleSendingOrderCommand() {
        info("Sending /order bones command");
        mc.getNetworkHandler().sendChatCommand("order bones");
        currentState = State.WAITING_ORDER_MENU;
        delayCounter = actionDelay.get() * 2;
    }

    private void handleWaitingOrderMenu(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
            if (container.getRows() == 6) {
                info("Order menu detected (6 rows)");
                currentState = State.CLICK_BONE_SLOT;
                delayCounter = actionDelay.get();
            }
        }
    }

    private void handleClickBoneSlot(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        int[] prioritySlots = {4, 5, 6, 7, 8, 9, 10, 11, 12};
        GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;

        int slotToClick = -1;

        for (int slot : prioritySlots) {
            if (slot < container.getRows() * 9) {
                ItemStack stack = container.getSlot(slot).getStack();
                if (!stack.isEmpty()) {
                    slotToClick = slot;
                    break;
                }
            }
        }

        if (slotToClick == -1) {
            for (int i = 0; i < Math.min(5 * 9, container.getRows() * 9); i++) {
                ItemStack stack = container.getSlot(i).getStack();
                if (!stack.isEmpty()) {
                    slotToClick = i;
                    break;
                }
            }
        }

        if (slotToClick != -1) {
            info("Clicking slot " + slotToClick + " in order menu");
            mc.interactionManager.clickSlot(handler.syncId, slotToClick, 0, SlotActionType.PICKUP, mc.player);
            currentState = State.WAITING_DEPOSIT_MENU;
            delayCounter = actionDelay.get() * 2;
        } else {
            warning("No items found in order menu");
            currentState = State.RETRY_SEQUENCE;
        }
    }

    private void handleWaitingDepositMenu(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
            if (container.getRows() == 4) {
                info("Deposit menu detected (4 rows)");
                currentState = State.DEPOSITING_BONES;
                delayCounter = actionDelay.get();
            }
        }
    }

    private void handleDepositingBones(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
        int containerSlots = container.getRows() * 9;

        boolean chestFull = true;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (stack.isEmpty() || (stack.getItem() == Items.BONE && stack.getCount() < stack.getMaxCount())) {
                chestFull = false;
                break;
            }
        }

        boolean foundBones = false;
        for (int i = containerSlots; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                foundBones = true;
                if (!chestFull) {
                    info("Moving bones from slot " + i);
                    mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                    delayCounter = actionDelay.get();
                    return;
                }
            }
        }

        if (!foundBones || chestFull) {
            if (chestFull) {
                info("Chest is full, proceeding to next step");
            } else {
                info("All bones deposited, proceeding to next step");
            }
            currentState = State.CLOSING_DEPOSIT_MENU;
            delayCounter = actionDelay.get();
        }
    }

    private void handleClosingDepositMenu() {
        info("Closing deposit menu");
        mc.player.closeHandledScreen();
        currentState = State.WAITING_CONFIRM_MENU;
        delayCounter = actionDelay.get() * 2;
    }

    private void handleWaitingConfirmMenu(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
            if (container.getRows() == 3) {
                info("Confirm menu detected (3 rows)");
                currentState = State.CLICK_CONFIRM_SLOT;
                delayCounter = actionDelay.get();
            }
        }
    }

    private void handleClickConfirmSlot(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        info("Clicking slot 15 to confirm");
        mc.interactionManager.clickSlot(handler.syncId, 15, 0, SlotActionType.PICKUP, mc.player);

        chatMessageReceived = false;
        waitingForChatMessage = true;
        waitingForProfitMessage = false;
        currentState = State.WAITING_FOR_CHAT_CONFIRMATION;
        delayCounter = 5;
        timeoutCounter = 0;
    }

    private void handleWaitingForChatConfirmation() {
        timeoutCounter++;

        if (chatMessageReceived) {
            info("Order successfully delivered! Closing inventories...");
            waitingForChatMessage = false;
            currentState = State.CLOSING_FIRST_INVENTORY;
            delayCounter = 0;
            return;
        }

        if (orderExpiredCheck.get() && timeoutCounter >= orderExpiredTimeout.get()) {
            warning("Order expired - no chat confirmation received");
            waitingForChatMessage = false;
            waitingForProfitMessage = false;
            mc.player.closeHandledScreen();

            if (playerHasBones()) {
                info("Player still has bones, retrying /order bones");
                currentState = State.SENDING_ORDER_COMMAND;
                delayCounter = actionDelay.get() * 2;
            } else {
                info("No more bones, restarting from spawner");
                currentState = State.FINDING_SPAWNER;
                delayCounter = actionDelay.get() * 2;
            }
        }
    }

    private void handleClosingFirstInventory() {
        info("Closing first inventory");
        mc.player.closeHandledScreen();
        currentState = State.CLOSING_SECOND_INVENTORY;
        delayCounter = inventoryClosingDelay.get();
    }

    private void handleClosingSecondInventory() {
        info("Closing second inventory");
        mc.player.closeHandledScreen();
        currentState = State.LOOP_DELAY;
        delayCounter = loopDelay.get();
    }

    private void handleLoopDelay() {
        info("Loop delay complete");

        if (playerHasBones()) {
            info("Restarting from /order bones");
            currentState = State.SENDING_ORDER_COMMAND;
        } else {
            info("Restarting from spawner");
            currentState = State.FINDING_SPAWNER;
        }
        delayCounter = actionDelay.get() * 2;
    }

    private void handleRetrySequence() {
        info("Retrying sequence");
        mc.player.closeHandledScreen();

        if (playerHasBones()) {
            currentState = State.SENDING_ORDER_COMMAND;
        } else {
            currentState = State.FINDING_SPAWNER;
        }
        delayCounter = actionDelay.get() * 3;
        timeoutCounter = 0;
        targetSpawner = null;
    }

    private boolean playerHasBones() {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        delayCounter = 0;
        timeoutCounter = 0;
        spawnerTimeoutCounter = 0;
        targetSpawner = null;
        targetShulker = null;
        chatMessageReceived = false;
        waitingForChatMessage = false;
        waitingForProfitMessage = false;
        info("AutoSpawnerSell deactivated");
    }
}
