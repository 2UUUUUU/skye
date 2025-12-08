package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoSpawnerSell extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSendProfits = settings.createGroup("Send Profits");
    private final SettingGroup sgSpawnerProtect = settings.createGroup("Spawner Protect");

    // General Settings
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

    // Send Profits Settings
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

    // Spawner Protect Settings - Main Enable
    private final Setting<Boolean> enableSpawnerProtect = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("enable")
        .description("Enable spawner protection when entities are detected")
        .defaultValue(false)
        .build()
    );

    // Spawner Protect Settings Sub-category
    private final Setting<Integer> entitiesRange = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("entities-range")
        .description("Range to detect entities for protection")
        .defaultValue(16)
        .min(1)
        .max(50)
        .sliderMax(50)
        .visible(enableSpawnerProtect::get)
        .build()
    );

    private final Setting<Set<EntityType<?>>> targetEntities = sgSpawnerProtect.add(new EntityTypeListSetting.Builder()
        .name("add-entity")
        .description("Add an Entity for Spawner Protect to trigger on detection")
        .defaultValue(Set.of(EntityType.PLAYER))
        .visible(enableSpawnerProtect::get)
        .build()
    );

    private final Setting<Integer> recheckDelaySeconds = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("recheck-delay-seconds")
        .description("Delay in seconds before rechecking for spawners")
        .defaultValue(1)
        .min(1)
        .sliderMax(10)
        .visible(enableSpawnerProtect::get)
        .build()
    );

    private final Setting<Integer> emergencyDistance = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("emergency-distance")
        .description("Distance in blocks where entity triggers immediate disconnect")
        .defaultValue(7)
        .min(1)
        .max(20)
        .sliderMax(20)
        .visible(enableSpawnerProtect::get)
        .build()
    );

    // Whitelist Sub-category
    private final Setting<Boolean> enableWhitelist = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("enable-whitelist")
        .description("Enable player whitelist (whitelisted players won't trigger protection)")
        .defaultValue(false)
        .visible(enableSpawnerProtect::get)
        .build()
    );

    private final Setting<List<String>> whitelistPlayers = sgSpawnerProtect.add(new StringListSetting.Builder()
        .name("add-players-to-whitelist")
        .description("List of player names to ignore")
        .defaultValue(new ArrayList<>())
        .visible(() -> enableSpawnerProtect.get() && enableWhitelist.get())
        .build()
    );

    // Webhook Sub-category
    private final Setting<Boolean> webhook = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("webhook")
        .description("Enable webhook notifications")
        .defaultValue(false)
        .visible(enableSpawnerProtect::get)
        .build()
    );

    private final Setting<String> webhookUrl = sgSpawnerProtect.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL for notifications")
        .defaultValue("")
        .visible(() -> enableSpawnerProtect.get() && webhook.get())
        .build()
    );

    private final Setting<Boolean> selfPing = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("self-ping")
        .description("Ping yourself in the webhook message")
        .defaultValue(false)
        .visible(() -> enableSpawnerProtect.get() && webhook.get())
        .build()
    );

    private final Setting<String> discordId = sgSpawnerProtect.add(new StringSetting.Builder()
        .name("discord-id")
        .description("Your Discord user ID for pinging")
        .defaultValue("")
        .visible(() -> enableSpawnerProtect.get() && webhook.get() && selfPing.get())
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
        // Spawner Protect States
        PROTECT_DETECTION_WAIT,
        PROTECT_CHECK_LIME_GLASS,
        PROTECT_WAIT_AFTER_LIME_GLASS,
        PROTECT_CLOSE_ALL_INVENTORIES,
        OPENED_CHEST_CHECK,
        PROTECT_INITIAL_WAIT,
        PROTECT_OPEN_PLAYER_INVENTORY,
        PROTECT_CHECK_INVENTORY,
        PROTECT_FIND_CHEST,
        PROTECT_OPEN_CHEST,
        PROTECT_DEPOSIT_BONES_TO_CHEST,
        PROTECT_CLOSE_CHEST,
        PROTECT_RECHECK_WAIT,
        PROTECT_GOING_TO_SPAWNERS,
        PROTECT_GOING_TO_CHEST,
        PROTECT_OPENING_CHEST,
        PROTECT_DEPOSITING_ITEMS,
        PROTECT_DISCONNECTING
    }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private int timeoutCounter = 0;
    private int spawnerTimeoutCounter = 0;
    private BlockPos targetSpawner = null;
    private boolean chatMessageReceived = false;
    private boolean waitingForChatMessage = false;
    private boolean waitingForProfitMessage = false;

    // Spawner Protect variables
    private String detectedPlayer = "";
    private String detectedEntity = "";
    private long detectionTime = 0;
    private boolean spawnersMinedSuccessfully = false;
    private boolean itemsDepositedSuccessfully = false;
    private int protectTickCounter = 0;
    private boolean sneaking = false;
    private BlockPos currentProtectTarget = null;
    private boolean isMiningCycle = true;
    private int miningCycleTimer = 0;
    private final int MINING_DURATION = 80;
    private final int PAUSE_DURATION = 20;
    private BlockPos targetChest = null;
    private int chestOpenAttempts = 0;
    private boolean emergencyDisconnect = false;
    private String emergencyReason = "";
    private World trackedWorld = null;
    private int worldChangeCount = 0;
    private final int PLAYER_COUNT_THRESHOLD = 3;
    private int transferDelayCounter = 0;
    private int lastProcessedSlot = -1;
    private BlockPos tempBoneChest = null;
    private int boneChestOpenAttempts = 0;

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
        chatMessageReceived = false;
        waitingForChatMessage = false;
        waitingForProfitMessage = false;

        // Spawner Protect initialization
        detectedPlayer = "";
        detectedEntity = "";
        detectionTime = 0;
        spawnersMinedSuccessfully = false;
        itemsDepositedSuccessfully = false;
        protectTickCounter = 0;
        sneaking = false;
        currentProtectTarget = null;
        isMiningCycle = true;
        miningCycleTimer = 0;
        targetChest = null;
        chestOpenAttempts = 0;
        emergencyDisconnect = false;
        emergencyReason = "";
        transferDelayCounter = 0;
        lastProcessedSlot = -1;
        tempBoneChest = null;
        boneChestOpenAttempts = 0;

        if (mc.world != null) {
            trackedWorld = mc.world;
            worldChangeCount = 0;
        }

        info("AutoSpawnerSell activated - searching for spawner...");

        if (enableSpawnerProtect.get()) {
            info("Spawner Protection is ENABLED - monitoring for entities...");
            info("Monitoring entities: " + targetEntities.get().size() + " types");
            ChatUtils.warning("Make sure to have a silk touch pickaxe and an ender chest nearby!");
        }
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
                Pattern pattern = Pattern.compile("You delivered .+ Bones and received \\$([0-9.KMB]+)");
                Matcher matcher = pattern.matcher(message);

                if (matcher.find()) {
                    String profitAmount = matcher.group(1);
                    String playerName = profitPlayer.get();

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

            int minPercent = Math.min(minimumPercent.get(), maximumPercent.get());
            int maxPercent = Math.max(minimumPercent.get(), maximumPercent.get());
            double randomPercent = minPercent + (random.nextDouble() * (maxPercent - minPercent));

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
            warning("Failed to parse profit amount: " + amountStr);
            return amountStr;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        protectTickCounter++;

        // Handle world changes for Spawner Protect
        if (enableSpawnerProtect.get() && mc.world != trackedWorld) {
            handleWorldChange();
            return;
        }

        // Check for emergency disconnect first
        if (enableSpawnerProtect.get() && checkEmergencyDisconnect()) {
            return;
        }

        // Check for players (spawner protect)
        if (enableSpawnerProtect.get() && !isProtectMode() && checkForPlayers()) {
            return;
        }

        if (transferDelayCounter > 0) {
            transferDelayCounter--;
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
            // Spawner Protect States
            case PROTECT_DETECTION_WAIT:
                handleProtectDetectionWait(handler);
                break;
            case PROTECT_CHECK_LIME_GLASS:
                handleProtectCheckLimeGlass(handler);
                break;
            case PROTECT_WAIT_AFTER_LIME_GLASS:
                handleProtectWaitAfterLimeGlass();
                break;
            case PROTECT_CLOSE_ALL_INVENTORIES:
                handleProtectCloseAllInventories();
                break;
            case OPENED_CHEST_CHECK:
                handleOpenedChestCheck(handler);
                break;
            case PROTECT_INITIAL_WAIT:
                handleProtectInitialWait();
                break;
            case PROTECT_OPEN_PLAYER_INVENTORY:
                handleProtectOpenPlayerInventory();
                break;
            case PROTECT_CHECK_INVENTORY:
                handleProtectCheckInventory();
                break;
            case PROTECT_FIND_CHEST:
                handleProtectFindChest();
                break;
            case PROTECT_OPEN_CHEST:
                handleProtectOpenChest();
                break;
            case PROTECT_DEPOSIT_BONES_TO_CHEST:
                handleProtectDepositBonesToChest(handler);
                break;
            case PROTECT_CLOSE_CHEST:
                handleProtectCloseChest();
                break;
            case PROTECT_RECHECK_WAIT:
                handleProtectRecheckWait();
                break;
            case PROTECT_GOING_TO_SPAWNERS:
                handleProtectGoingToSpawners();
                break;
            case PROTECT_GOING_TO_CHEST:
                handleProtectGoingToChest();
                break;
            case PROTECT_OPENING_CHEST:
                handleProtectOpeningChest();
                break;
            case PROTECT_DEPOSITING_ITEMS:
                handleProtectDepositingItems();
                break;
            case PROTECT_DISCONNECTING:
                handleProtectDisconnecting();
                break;
        }
    }

    // ==================== SPAWNER PROTECT METHODS ====================

    private boolean isProtectMode() {
        return currentState == State.PROTECT_DETECTION_WAIT ||
            currentState == State.PROTECT_CHECK_LIME_GLASS ||
            currentState == State.PROTECT_WAIT_AFTER_LIME_GLASS ||
            currentState == State.PROTECT_CLOSE_ALL_INVENTORIES ||
            currentState == State.OPENED_CHEST_CHECK ||
            currentState == State.PROTECT_INITIAL_WAIT ||
            currentState == State.PROTECT_OPEN_PLAYER_INVENTORY ||
            currentState == State.PROTECT_CHECK_INVENTORY ||
            currentState == State.PROTECT_FIND_CHEST ||
            currentState == State.PROTECT_OPEN_CHEST ||
            currentState == State.PROTECT_DEPOSIT_BONES_TO_CHEST ||
            currentState == State.PROTECT_CLOSE_CHEST ||
            currentState == State.PROTECT_RECHECK_WAIT ||
            currentState == State.PROTECT_GOING_TO_SPAWNERS ||
            currentState == State.PROTECT_GOING_TO_CHEST ||
            currentState == State.PROTECT_OPENING_CHEST ||
            currentState == State.PROTECT_DEPOSITING_ITEMS ||
            currentState == State.PROTECT_DISCONNECTING;
    }

    private void handleProtectDetectionWait(ScreenHandler handler) {
        info("Detection wait complete, checking for lime glass...");
        currentState = State.PROTECT_CHECK_LIME_GLASS;
        delayCounter = 0;
    }

    private void handleProtectCheckLimeGlass(ScreenHandler handler) {
        // Check if spawner menu is open and has lime glass pane
        if (handler instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
            if (container.getRows() == 6) {
                // Check for lime glass pane in slot 50 (typical spawner menu)
                ItemStack slot50 = container.getSlot(50).getStack();
                if (!slot50.isEmpty() && slot50.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                    info("Lime glass detected in spawner menu - spawner is active");
                    currentState = State.PROTECT_WAIT_AFTER_LIME_GLASS;
                    delayCounter = 10;
                    return;
                }
            }
        }

        // No lime glass or menu not open, proceed to close inventories
        info("No lime glass detected or menu not open, proceeding...");
        currentState = State.PROTECT_CLOSE_ALL_INVENTORIES;
        delayCounter = 5;
    }

    private void handleProtectWaitAfterLimeGlass() {
        info("Wait after lime glass check complete");
        currentState = State.PROTECT_CLOSE_ALL_INVENTORIES;
        delayCounter = 0;
    }

    private void handleProtectCloseAllInventories() {
        info("Closing all inventories before protection sequence");
        mc.player.closeHandledScreen();
        currentState = State.OPENED_CHEST_CHECK;
        delayCounter = 5;
    }

    private void handleOpenedChestCheck(ScreenHandler handler) {
        // Ensure all inventories are closed
        if (handler instanceof GenericContainerScreenHandler) {
            info("Still have an inventory open, closing it...");
            mc.player.closeHandledScreen();
            delayCounter = 5;
            return;
        }

        info("All inventories closed, starting initial wait");
        currentState = State.PROTECT_INITIAL_WAIT;
        delayCounter = 20; // Initial wait before starting protection
    }

    private void handleProtectOpenPlayerInventory() {
        info("Opening player inventory to check contents");
        // In Minecraft, pressing 'E' opens inventory
        // We'll move directly to checking since we can access inventory without opening GUI
        currentState = State.PROTECT_CHECK_INVENTORY;
        delayCounter = 0;
    }

    private void handleProtectInitialWait() {
        info("Initial wait complete, checking inventory...");
        currentState = State.PROTECT_CHECK_INVENTORY;
        delayCounter = 0;
    }

    private void handleProtectCheckInventory() {
        if (isInventoryFull()) {
            info("Inventory is full! Looking for a chest to deposit bones...");
            currentState = State.PROTECT_FIND_CHEST;
            delayCounter = 0;
        } else {
            info("Inventory has space. Proceeding to spawner mining...");
            setSneaking(true);
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            delayCounter = 0;
        }
    }

    private boolean isInventoryFull() {
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) { // Check main inventory (excluding hotbar armor slots)
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
            }
        }
        info("Empty inventory slots: " + emptySlots);
        return emptySlots == 0;
    }

    private void handleProtectFindChest() {
        tempBoneChest = findNearestChestForBones();
        if (tempBoneChest == null) {
            warning("No chest found for bone deposit! Proceeding to spawner mining...");
            setSneaking(true);
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            delayCounter = 0;
            return;
        }

        info("Found chest at " + tempBoneChest + " for bone deposit");
        currentState = State.PROTECT_OPEN_CHEST;
        boneChestOpenAttempts = 0;
        delayCounter = 5;
    }

    private BlockPos findNearestChestForBones() {
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

    private void handleProtectOpenChest() {
        if (tempBoneChest == null) {
            currentState = State.PROTECT_FIND_CHEST;
            return;
        }

        lookAtBlock(tempBoneChest);

        if (boneChestOpenAttempts % 3 == 0) {
            if (mc.interactionManager != null && mc.player != null) {
                mc.interactionManager.interactBlock(
                    mc.player,
                    Hand.MAIN_HAND,
                    new BlockHitResult(
                        Vec3d.ofCenter(tempBoneChest),
                        Direction.UP,
                        tempBoneChest,
                        false
                    )
                );
                info("Opening chest for bone deposit... (attempt " + (boneChestOpenAttempts / 3 + 1) + ")");
            }
        }

        boneChestOpenAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            currentState = State.PROTECT_DEPOSIT_BONES_TO_CHEST;
            lastProcessedSlot = -1;
            info("Chest opened successfully! Depositing bones...");
            delayCounter = 2;
        }

        if (boneChestOpenAttempts > 60) {
            warning("Failed to open chest after multiple attempts! Proceeding to spawner mining...");
            setSneaking(true);
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            delayCounter = 0;
        }
    }

    private void handleProtectDepositBonesToChest(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler)) {
            currentState = State.PROTECT_OPEN_CHEST;
            boneChestOpenAttempts = 0;
            return;
        }

        GenericContainerScreenHandler container = (GenericContainerScreenHandler) handler;
        int containerSlots = container.getRows() * 9;

        // Check if there are any bones left in player inventory
        boolean foundBones = false;
        for (int i = containerSlots; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                foundBones = true;
                info("Depositing bones from slot " + i + " to chest");
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                delayCounter = 2;
                return;
            }
        }

        if (!foundBones) {
            info("All bones deposited to chest!");
            currentState = State.PROTECT_CLOSE_CHEST;
            delayCounter = 2;
        }
    }

    private void handleProtectCloseChest() {
        info("Closing chest after bone deposit");
        mc.player.closeHandledScreen();
        tempBoneChest = null;
        currentState = State.PROTECT_RECHECK_WAIT;
        delayCounter = 10; // Wait 10 ticks before rechecking
    }

    private void handleProtectRecheckWait() {
        info("Recheck wait complete, checking inventory again...");
        currentState = State.PROTECT_CHECK_INVENTORY;
        delayCounter = 0;
    }

    private void handleWorldChange() {
        worldChangeCount++;
        trackedWorld = mc.world;
        info("World changed - count: " + worldChangeCount);
    }

    private boolean checkEmergencyDisconnect() {
        if (targetEntities.get().isEmpty()) return false;

        List<Entity> nearbyEntities = mc.world.getOtherEntities(mc.player,
            new Box(mc.player.getBlockPos()).expand(emergencyDistance.get()));

        for (Entity entity : nearbyEntities) {
            if (entity == mc.player) continue;
            if (!targetEntities.get().contains(entity.getType())) continue;

            // Check whitelist for players
            if (entity instanceof PlayerEntity) {
                String playerName = ((PlayerEntity) entity).getGameProfile().name();
                if (enableWhitelist.get() && isPlayerWhitelisted(playerName)) {
                    continue;
                }
            }

            double distance = mc.player.distanceTo(entity);
            if (distance <= emergencyDistance.get()) {
                String entityName = getEntityName(entity);
                warning("EMERGENCY: " + entityName + " came too close (" + String.format("%.1f", distance) + " blocks)!");

                emergencyDisconnect = true;
                emergencyReason = entityName + " came too close";
                detectedEntity = entityName;
                if (entity instanceof PlayerEntity) {
                    detectedPlayer = ((PlayerEntity) entity).getGameProfile().name();
                } else {
                    detectedPlayer = entityName;
                }
                detectionTime = System.currentTimeMillis();

                disableAutoReconnectIfEnabled();

                mc.player.closeHandledScreen();
                currentState = State.PROTECT_DISCONNECTING;
                return true;
            }
        }
        return false;
    }

    private boolean checkForPlayers() {
        if (targetEntities.get().isEmpty()) return false;

        List<Entity> nearbyEntities = mc.world.getOtherEntities(mc.player,
            new Box(mc.player.getBlockPos()).expand(entitiesRange.get()));

        for (Entity entity : nearbyEntities) {
            if (entity == mc.player) continue;
            if (!targetEntities.get().contains(entity.getType())) continue;

            // Check whitelist for players
            if (entity instanceof PlayerEntity) {
                String playerName = ((PlayerEntity) entity).getGameProfile().name();
                if (enableWhitelist.get() && isPlayerWhitelisted(playerName)) {
                    continue;
                }
                detectedPlayer = playerName;
            } else {
                detectedPlayer = getEntityName(entity);
            }

            detectedEntity = getEntityName(entity);
            detectionTime = System.currentTimeMillis();

            warning("SpawnerProtect: Entity detected - " + detectedEntity);

            disableAutoReconnectIfEnabled();

            mc.player.closeHandledScreen();
            currentState = State.PROTECT_DETECTION_WAIT;
            delayCounter = 20; // Wait 20 ticks
            info("Entity detected! Starting protection sequence...");

            return true;
        }
        return false;
    }

    private String getEntityName(Entity entity) {
        if (entity instanceof PlayerEntity) {
            return "Player: " + ((PlayerEntity) entity).getGameProfile().name();
        }
        return EntityType.getId(entity.getType()).toString();
    }

    private boolean isPlayerWhitelisted(String playerName) {
        if (!enableWhitelist.get() || whitelistPlayers.get().isEmpty()) {
            return false;
        }

        return whitelistPlayers.get().stream()
            .anyMatch(whitelistedName -> whitelistedName.equalsIgnoreCase(playerName));
    }

    private void disableAutoReconnectIfEnabled() {
        Module autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            info("AutoReconnect disabled due to player detection");
        }
    }

    private void handleProtectGoingToSpawners() {
        setSneaking(true);

        // Check if player has diamond pickaxe in hotbar and switch to it
        if (!isHoldingDiamondPickaxe()) {
            int pickaxeSlot = findDiamondPickaxeInHotbar();
            if (pickaxeSlot == -1) {
                warning("No diamond pickaxe found in hotbar! Cannot mine spawners.");
                stopBreaking();
                currentState = State.PROTECT_GOING_TO_CHEST;
                info("Skipping spawner mining, going directly to chest...");
                protectTickCounter = 0;
                return;
            }

// Switch to the pickaxe slot by swapping items
            // Swap pickaxe to hotbar slot 0
            ItemStack pickaxe = mc.player.getInventory().getStack(pickaxeSlot);
            ItemStack slot0 = mc.player.getInventory().getStack(0);
            mc.player.getInventory().setStack(0, pickaxe);
            mc.player.getInventory().setStack(pickaxeSlot, slot0);
            info("Moved diamond pickaxe to slot 1");
            delayCounter = 3;
            return;        }

        if (currentProtectTarget == null) {
            BlockPos found = findNearestSpawnerForProtect();

            if (found == null) {
                stopBreaking();
                spawnersMinedSuccessfully = true;
                currentProtectTarget = null;
                currentState = State.PROTECT_GOING_TO_CHEST;
                info("All spawners mined successfully. Looking for ender chest...");
                protectTickCounter = 0;
                return;
            }

            currentProtectTarget = found;
            isMiningCycle = true;
            miningCycleTimer = 0;
            info("Starting to mine spawner at " + currentProtectTarget);
        }

        if (isMiningCycle) {
            lookAtBlock(currentProtectTarget);
            breakBlock(currentProtectTarget);

            if (mc.world.getBlockState(currentProtectTarget).isAir()) {
                info("Spawner at " + currentProtectTarget + " broken!");
                stopBreaking();
                isMiningCycle = false;
                miningCycleTimer = 0;
                currentProtectTarget = null;
                transferDelayCounter = 5;
            }
        } else {
            miningCycleTimer++;
            if (miningCycleTimer >= PAUSE_DURATION) {
                // Pause complete
            }
        }
    }

    private boolean isHoldingDiamondPickaxe() {
        ItemStack heldItem = mc.player.getMainHandStack();
        return !heldItem.isEmpty() && heldItem.getItem() == Items.DIAMOND_PICKAXE;
    }

    private int findDiamondPickaxeInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.DIAMOND_PICKAXE) {
                return i;
            }
        }
        return -1; // Not found
    }

    private BlockPos findNearestSpawnerForProtect() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearestSpawner = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
            playerPos.add(-entitiesRange.get(), -entitiesRange.get(), -entitiesRange.get()),
            playerPos.add(entitiesRange.get(), entitiesRange.get(), entitiesRange.get()))) {

            if (mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                double distance = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestSpawner = pos.toImmutable();
                }
            }
        }

        if (nearestSpawner != null) {
            info("Found spawner at " + nearestSpawner + " (distance: " + String.format("%.2f", Math.sqrt(nearestDistance)) + ")");
        }

        return nearestSpawner;
    }

    private void lookAtBlock(BlockPos pos) {
        Vec3d targetPos = Vec3d.ofCenter(pos);
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(-Math.asin(direction.y));

        mc.player.setYaw((float) yaw);
        mc.player.setPitch((float) pitch);
    }

    private void breakBlock(BlockPos pos) {
        if (mc.interactionManager != null) {
            mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
        }
    }

    private void stopBreaking() {
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
    }

    private void setSneaking(boolean sneak) {
        if (mc.player == null) return;

        if (sneak && !sneaking) {
            mc.player.setSneaking(true);
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), true);
            sneaking = true;
        } else if (!sneak && sneaking) {
            mc.player.setSneaking(false);
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);
            sneaking = false;
        }
    }

    private void handleProtectGoingToChest() {
        setSneaking(true);

        if (targetChest == null) {
            targetChest = findNearestEnderChest();
            if (targetChest == null) {
                info("No ender chest found nearby!");
                currentState = State.PROTECT_DISCONNECTING;
                return;
            }
            info("Found ender chest at " + targetChest);
        }

        moveTowardsBlock(targetChest);

        if (mc.player.getBlockPos().getSquaredDistance(targetChest) <= 9) {
            currentState = State.PROTECT_OPENING_CHEST;
            chestOpenAttempts = 0;
            info("Reached ender chest. Attempting to open...");
        }

        if (protectTickCounter > 600) {
            ChatUtils.error("Timed out trying to reach ender chest!");
            currentState = State.PROTECT_DISCONNECTING;
        }
    }

    private BlockPos findNearestEnderChest() {
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

    private void moveTowardsBlock(BlockPos target) {
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = Vec3d.ofCenter(target);
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        mc.player.setYaw((float) yaw);

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
    }

    private void handleProtectOpeningChest() {
        if (targetChest == null) {
            currentState = State.PROTECT_GOING_TO_CHEST;
            return;
        }

        setSneaking(false);

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);

        if (chestOpenAttempts < 20) {
            lookAtBlock(targetChest);
        }

        if (chestOpenAttempts % 5 == 0) {
            if (mc.interactionManager != null && mc.player != null) {
                mc.interactionManager.interactBlock(
                    mc.player,
                    Hand.MAIN_HAND,
                    new BlockHitResult(
                        Vec3d.ofCenter(targetChest),
                        Direction.UP,
                        targetChest,
                        false
                    )
                );
                info("Right-clicking ender chest... (attempt " + (chestOpenAttempts / 5 + 1) + ")");
            }
        }

        chestOpenAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
            currentState = State.PROTECT_DEPOSITING_ITEMS;
            lastProcessedSlot = -1;
            protectTickCounter = 0;
            info("Ender chest opened successfully!");
        }

        if (chestOpenAttempts > 200) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
            ChatUtils.error("Failed to open ender chest after multiple attempts!");
            currentState = State.PROTECT_DISCONNECTING;
        }
    }

    private void handleProtectDepositingItems() {
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler handler = (GenericContainerScreenHandler) mc.player.currentScreenHandler;

            if (!hasItemsToDeposit()) {
                itemsDepositedSuccessfully = true;
                info("All items deposited successfully!");
                mc.player.closeHandledScreen();
                transferDelayCounter = 10;
                currentState = State.PROTECT_DISCONNECTING;
                return;
            }

            transferItemsToChest(handler);

        } else {
            currentState = State.PROTECT_OPENING_CHEST;
            chestOpenAttempts = 0;
        }

        if (protectTickCounter > 900) {
            ChatUtils.error("Timed out depositing items!");
            currentState = State.PROTECT_DISCONNECTING;
        }
    }

    private boolean hasItemsToDeposit() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                // Only check for spawners and diamond pickaxes
                if (stack.getItem() == Items.SPAWNER || stack.getItem() == Items.DIAMOND_PICKAXE) {
                    return true;
                }
            }
        }
        return false;
    }

    private void transferItemsToChest(GenericContainerScreenHandler handler) {
        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;
        int playerInventoryStart = chestSlots;
        int startSlot = Math.max(lastProcessedSlot + 1, playerInventoryStart);

        for (int i = 0; i < 36; i++) {
            int slotId = playerInventoryStart + ((startSlot - playerInventoryStart + i) % 36);
            ItemStack stack = handler.getSlot(slotId).getStack();

            if (stack.isEmpty() || stack.getItem() == Items.AIR) {
                continue;
            }

            // Only transfer spawners and diamond pickaxes
            if (stack.getItem() != Items.SPAWNER && stack.getItem() != Items.DIAMOND_PICKAXE) {
                continue;
            }

            info("Transferring item from slot " + slotId + ": " + stack.getItem().toString());

            if (mc.interactionManager != null) {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    slotId,
                    0,
                    SlotActionType.QUICK_MOVE,
                    mc.player
                );
            }

            lastProcessedSlot = slotId;
            transferDelayCounter = 2;
            return;
        }

        if (lastProcessedSlot >= playerInventoryStart) {
            lastProcessedSlot = playerInventoryStart - 1;
            transferDelayCounter = 3;
        }
    }

    private void handleProtectDisconnecting() {
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
        stopBreaking();
        setSneaking(false);

        sendWebhookNotification();

        if (emergencyDisconnect) {
            info("SpawnerProtect: " + emergencyReason + ". Successfully disconnected.");
        } else {
            info("SpawnerProtect: " + detectedEntity + " detected. Successfully disconnected.");
        }

        if (mc.world != null) {
            mc.world.disconnect(Text.literal("Entity detected - SpawnerProtect"));
        }

        info("Disconnected due to entity detection.");
        toggle();
    }

    private void sendWebhookNotification() {
        if (!webhook.get() || webhookUrl.get() == null || webhookUrl.get().trim().isEmpty()) {
            return;
        }

        String webhookUrlValue = webhookUrl.get().trim();
        long discordTimestamp = detectionTime / 1000L;

        String messageContent = "";
        if (selfPing.get() && discordId.get() != null && !discordId.get().trim().isEmpty()) {
            messageContent = String.format("<@%s>", discordId.get().trim());
        }

        String embedJson = createWebhookPayload(messageContent, discordTimestamp);

        new Thread(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrlValue))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(embedJson))
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    info("Webhook notification sent successfully!");
                } else {
                    ChatUtils.error("Failed to send webhook notification. Status: " + response.statusCode());
                }
            } catch (Exception e) {
                ChatUtils.error("Failed to send webhook notification: " + e.getMessage());
            }
        }).start();
    }

    private String createWebhookPayload(String messageContent, long discordTimestamp) {
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

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    // ==================== ORIGINAL AUTOSPAWNERSELL METHODS ====================

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
        int firstFiveRows = 5 * 9;

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
        chatMessageReceived = false;
        waitingForChatMessage = false;
        waitingForProfitMessage = false;

        // Clean up Spawner Protect state
        stopBreaking();
        setSneaking(false);
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);

        detectedPlayer = "";
        detectionTime = 0;
        spawnersMinedSuccessfully = false;
        itemsDepositedSuccessfully = false;
        protectTickCounter = 0;
        currentProtectTarget = null;
        targetChest = null;
        emergencyDisconnect = false;
        emergencyReason = "";
        tempBoneChest = null;
        boneChestOpenAttempts = 0;

        info("AutoSpawnerSell deactivated");
    }
}
