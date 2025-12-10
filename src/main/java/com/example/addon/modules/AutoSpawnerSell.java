package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.smp.SMPUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoSpawnerSell extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Setting Groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSendProfits = settings.createGroup("Send Profits");
    private final SettingGroup sgSpawnerProtect = settings.createGroup("Spawner Protect");

    // General Settings
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay").description("Delay in ticks between actions")
        .defaultValue(10).min(0).max(100).build());

    private final Setting<Integer> inventoryClosingDelay = sgGeneral.add(new IntSetting.Builder()
        .name("inventory-closing-delay").description("Delay in ticks between closing inventories")
        .defaultValue(10).min(0).max(100).build());

    private final Setting<Boolean> orderExpiredCheck = sgGeneral.add(new BoolSetting.Builder()
        .name("order-expired-check").description("Enable checking if order expired")
        .defaultValue(true).build());

    private final Setting<Integer> orderExpiredTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("order-expired-timeout").description("Ticks to wait for confirmation")
        .defaultValue(100).min(20).max(400).visible(orderExpiredCheck::get).build());

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay").description("Delay before restarting after delivery")
        .defaultValue(100).min(0).max(72000).build());

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-range").description("Range to search for spawners")
        .defaultValue(5).min(1).max(10).build());

    private final Setting<Integer> spawnerTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-timeout").description("Ticks to wait for spawner menu")
        .defaultValue(40).min(10).max(200).build());

    private final Setting<Boolean> pauseSequence = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-sequence")
        .description("Pause the sequence if the spawner does not contain enough bones.")
        .defaultValue(false).build());

    private final Setting<Integer> pauseMinimumDelay = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-delay")
        .description("Minimum delay. Minimum: 20 ticks (1 second)")
        .defaultValue(200).min(20).max(432000)
        .visible(pauseSequence::get).build());

    private final Setting<Integer> pauseMaximumDelay = sgGeneral.add(new IntSetting.Builder()
        .name("maximum-delay")
        .description("Maximum delay. Maximum: 432000 ticks (6 hours)")
        .defaultValue(400).min(20).max(432000)
        .visible(pauseSequence::get).build());

    // Send Profits Settings
    private final Setting<Boolean> enableSendProfits = sgSendProfits.add(new BoolSetting.Builder()
        .name("enable").description("Enable automatic profit sending")
        .defaultValue(false).build());

    private final Setting<String> profitPlayer = sgSendProfits.add(new StringSetting.Builder()
        .name("player").description("Player name to send profits to")
        .defaultValue("").visible(enableSendProfits::get).build());

    private final Setting<Boolean> randomizeAmount = sgSendProfits.add(new BoolSetting.Builder()
        .name("randomize-amount").description("Randomize the amount sent")
        .defaultValue(false).visible(enableSendProfits::get).build());

    private final Setting<Integer> minimumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("minimum-percent").description("Minimum percentage of profit")
        .defaultValue(20).min(0).max(100)
        .visible(() -> enableSendProfits.get() && randomizeAmount.get()).build());

    private final Setting<Integer> maximumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("maximum-percent").description("Maximum percentage of profit")
        .defaultValue(100).min(0).max(100)
        .visible(() -> enableSendProfits.get() && randomizeAmount.get()).build());

    // Spawner Protect Settings
    private final Setting<Boolean> enableSpawnerProtect = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("enable").description("Enable spawner protection")
        .defaultValue(false).build());

    private final Setting<Boolean> triggerOnBrokenBlock = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("trigger-on-broken-block")
        .description("Triggers Spawner Protect when a block near the bot gets broken.")
        .defaultValue(false).visible(enableSpawnerProtect::get).build());

    private final Setting<Boolean> triggerOnPlacedBlock = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("trigger-on-placed-block")
        .description("Triggers Spawner Protect when a block near the bot gets placed.")
        .defaultValue(false).visible(enableSpawnerProtect::get).build());

    private final Setting<Integer> entitiesRange = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("entities-range").description("Range to detect entities")
        .defaultValue(16).min(1).max(50).sliderMax(50).visible(enableSpawnerProtect::get).build());

    private final Setting<Set<EntityType<?>>> targetEntities = sgSpawnerProtect.add(new EntityTypeListSetting.Builder()
        .name("add-entity").description("Add entity to monitor")
        .defaultValue(Set.of(EntityType.PLAYER)).visible(enableSpawnerProtect::get).build());

    private final Setting<Integer> recheckDelaySeconds = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("recheck-delay-seconds").description("Delay before rechecking")
        .defaultValue(1).min(1).sliderMax(10).visible(enableSpawnerProtect::get).build());

    private final Setting<Integer> emergencyDistance = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("emergency-distance").description("Distance for immediate disconnect")
        .defaultValue(7).min(1).max(20).sliderMax(20).visible(enableSpawnerProtect::get).build());

    private final Setting<Boolean> enableWhitelist = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("enable-whitelist").description("Enable player whitelist")
        .defaultValue(false).visible(enableSpawnerProtect::get).build());

    private final Setting<List<String>> whitelistPlayers = sgSpawnerProtect.add(new StringListSetting.Builder()
        .name("add-players-to-whitelist").description("Whitelisted players")
        .defaultValue(new ArrayList<>())
        .visible(() -> enableSpawnerProtect.get() && enableWhitelist.get()).build());

    private final Setting<Boolean> webhook = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("webhook").description("Enable webhook notifications")
        .defaultValue(false).visible(enableSpawnerProtect::get).build());

    private final Setting<String> webhookUrl = sgSpawnerProtect.add(new StringSetting.Builder()
        .name("webhook-url").description("Discord webhook URL")
        .defaultValue("").visible(() -> enableSpawnerProtect.get() && webhook.get()).build());

    private final Setting<Boolean> selfPing = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("self-ping").description("Ping yourself in webhook")
        .defaultValue(false).visible(() -> enableSpawnerProtect.get() && webhook.get()).build());

    private final Setting<String> discordId = sgSpawnerProtect.add(new StringSetting.Builder()
        .name("discord-id").description("Your Discord user ID")
        .defaultValue("").visible(() -> enableSpawnerProtect.get() && webhook.get() && selfPing.get()).build());

    // State enum
    private enum State {
        IDLE, FINDING_SPAWNER, OPENING_SPAWNER, WAITING_SPAWNER_MENU, CHECKING_SPAWNER_CONTENTS,
        PAUSED_NOT_ENOUGH_BONES, CLICK_SLOT_50, SENDING_ORDER_COMMAND, WAITING_ORDER_MENU,
        CLICK_BONE_SLOT, WAITING_DEPOSIT_MENU, DEPOSITING_BONES, CLOSING_DEPOSIT_MENU,
        WAITING_CONFIRM_MENU, CLICK_CONFIRM_SLOT, WAITING_FOR_CHAT_CONFIRMATION,
        CLOSING_FIRST_INVENTORY, CLOSING_SECOND_INVENTORY, LOOP_DELAY, RETRY_SEQUENCE,
        PROTECT_DETECTION_WAIT, PROTECT_CHECK_LIME_GLASS, PROTECT_WAIT_AFTER_LIME_GLASS,
        PROTECT_CLOSE_ALL_INVENTORIES, OPENED_CHEST_CHECK, PROTECT_INITIAL_WAIT,
        PROTECT_OPEN_PLAYER_INVENTORY, PROTECT_CHECK_INVENTORY, PROTECT_FIND_CHEST,
        PROTECT_OPEN_CHEST, PROTECT_DEPOSIT_BONES_TO_CHEST, PROTECT_CLOSE_CHEST,
        PROTECT_RECHECK_WAIT, PROTECT_GOING_TO_SPAWNERS, PROTECT_GOING_TO_CHEST,
        PROTECT_OPENING_CHEST, PROTECT_DEPOSITING_ITEMS, PROTECT_DISCONNECTING
    }

    // State variables
    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private int timeoutCounter = 0;
    private int spawnerTimeoutCounter = 0;
    private BlockPos targetSpawner = null;
    private boolean chatMessageReceived = false;
    private boolean waitingForChatMessage = false;
    private boolean waitingForProfitMessage = false;

    // Spawner Protect variables
    private String detectedEntity = "";
    private long detectionTime = 0;
    private boolean spawnersMinedSuccessfully = false;
    private boolean itemsDepositedSuccessfully = false;
    private int protectTickCounter = 0;
    private final boolean[] sneakingState = {false};
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
    private int transferDelayCounter = 0;
    private int lastProcessedSlot = -1;
    private BlockPos tempBoneChest = null;
    private int boneChestOpenAttempts = 0;

    public AutoSpawnerSell() {
        super(Main.CATEGORY, "auto-spawner-sell", "Automatically drops bones from spawner and sells them");
    }

    @Override
    public void onActivate() {
        currentState = State.FINDING_SPAWNER;
        resetCounters();
        resetProtectState();

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

    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        resetCounters();
        resetProtectState();
        cleanupControls();
        info("AutoSpawnerSell deactivated");
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String message = event.getMessage().getString();

        if (waitingForChatMessage && message.contains("You delivered") &&
            message.contains("Bones") && message.contains("received")) {
            info("Delivery confirmation received!");
            chatMessageReceived = true;

            if (enableSendProfits.get() && !profitPlayer.get().isEmpty()) {
                waitingForProfitMessage = true;
            }
        }

        if (waitingForProfitMessage && message.contains("You delivered") &&
            message.contains("Bones") && message.contains("received")) {
            Pattern pattern = Pattern.compile("You delivered .+ Bones and received \\$([0-9.KMB]+)");
            Matcher matcher = pattern.matcher(message);

            if (matcher.find()) {
                String profitAmount = matcher.group(1);
                String playerName = profitPlayer.get();
                String amountToSend = randomizeAmount.get()
                    ? SMPUtils.calculateRandomizedAmount(profitAmount, minimumPercent.get(), maximumPercent.get())
                    : profitAmount;

                info("Sending profit of $" + amountToSend + " to " + playerName);
                mc.getNetworkHandler().sendChatCommand("pay " + playerName + " " + amountToSend);
                waitingForProfitMessage = false;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        protectTickCounter++;

        if (enableSpawnerProtect.get() && mc.world != trackedWorld) {
            handleWorldChange();
            return;
        }

        if (enableSpawnerProtect.get() && checkEmergencyDisconnect()) return;
        if (enableSpawnerProtect.get() && !isProtectMode() && checkForEntities()) return;

        if (transferDelayCounter > 0) {
            transferDelayCounter--;
            return;
        }

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        handleState(handler);
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!enableSpawnerProtect.get() || mc.player == null || mc.world == null) return;
        if (isProtectMode()) return;

        String soundId = event.sound.getId().toString();

        // Check for block break sounds
        if (triggerOnBrokenBlock.get() && SMPUtils.isBlockBreakSound(soundId)) {
            detectedEntity = "Block Break Sound";
            detectionTime = System.currentTimeMillis();
            warning("SpawnerProtect: Block break detected!");
            SMPUtils.disableAutoReconnectIfEnabled(this);
            mc.player.closeHandledScreen();
            currentState = State.PROTECT_DETECTION_WAIT;
            delayCounter = 20;
            info("Block break detected! Starting protection sequence...");
        }

        // Check for block place sounds
        if (triggerOnPlacedBlock.get() && SMPUtils.isBlockPlaceSound(soundId)) {
            detectedEntity = "Block Place Sound";
            detectionTime = System.currentTimeMillis();
            warning("SpawnerProtect: Block placement detected!");
            SMPUtils.disableAutoReconnectIfEnabled(this);
            mc.player.closeHandledScreen();
            currentState = State.PROTECT_DETECTION_WAIT;
            delayCounter = 20;
            info("Block placement detected! Starting protection sequence...");
        }
    }

    private void resetCounters() {
        delayCounter = 0;
        timeoutCounter = 0;
        spawnerTimeoutCounter = 0;
        targetSpawner = null;
        chatMessageReceived = false;
        waitingForChatMessage = false;
        waitingForProfitMessage = false;
    }

    private void resetProtectState() {
        detectedEntity = "";
        detectionTime = 0;
        spawnersMinedSuccessfully = false;
        itemsDepositedSuccessfully = false;
        protectTickCounter = 0;
        sneakingState[0] = false;
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
    }

    private void cleanupControls() {
        SMPUtils.stopBreaking();
        SMPUtils.setSneaking(false, sneakingState);
        SMPUtils.stopMoving();
        SMPUtils.setJumping(false);
    }

    private void handleWorldChange() {
        worldChangeCount++;
        trackedWorld = mc.world;
        info("World changed - count: " + worldChangeCount);
    }

    private boolean isProtectMode() {
        return currentState.name().startsWith("PROTECT_") || currentState == State.OPENED_CHEST_CHECK;
    }

    private boolean checkEmergencyDisconnect() {
        if (targetEntities.get().isEmpty()) return false;

        List<Entity> nearbyEntities = mc.world.getOtherEntities(mc.player,
            new Box(mc.player.getBlockPos()).expand(emergencyDistance.get()));

        for (Entity entity : nearbyEntities) {
            if (entity == mc.player || !targetEntities.get().contains(entity.getType())) continue;

            if (entity instanceof PlayerEntity playerEntity) {
                String playerName = SMPUtils.getPlayerName(playerEntity);
                if (enableWhitelist.get() && SMPUtils.isPlayerWhitelisted(playerName, whitelistPlayers.get())) {
                    continue;
                }
            }

            double distance = mc.player.distanceTo(entity);
            if (distance <= emergencyDistance.get()) {
                String entityName = SMPUtils.getEntityName(entity);
                warning("EMERGENCY: " + entityName + " came too close (" + String.format("%.1f", distance) + " blocks)!");

                emergencyDisconnect = true;
                emergencyReason = entityName + " came too close";
                detectedEntity = entityName;
                detectionTime = System.currentTimeMillis();

                SMPUtils.disableAutoReconnectIfEnabled(this);
                mc.player.closeHandledScreen();
                currentState = State.PROTECT_DISCONNECTING;
                return true;
            }
        }
        return false;
    }

    private boolean checkForEntities() {
        if (targetEntities.get().isEmpty()) return false;

        List<Entity> nearbyEntities = mc.world.getOtherEntities(mc.player,
            new Box(mc.player.getBlockPos()).expand(entitiesRange.get()));

        for (Entity entity : nearbyEntities) {
            if (entity == mc.player || !targetEntities.get().contains(entity.getType())) continue;

            if (entity instanceof PlayerEntity playerEntity) {
                String playerName = SMPUtils.getPlayerName(playerEntity);
                if (enableWhitelist.get() && SMPUtils.isPlayerWhitelisted(playerName, whitelistPlayers.get())) {
                    continue;
                }
            }

            detectedEntity = SMPUtils.getEntityName(entity);
            detectionTime = System.currentTimeMillis();

            warning("SpawnerProtect: Entity detected - " + detectedEntity);
            SMPUtils.disableAutoReconnectIfEnabled(this);

            mc.player.closeHandledScreen();
            currentState = State.PROTECT_DETECTION_WAIT;
            delayCounter = 20;
            info("Entity detected! Starting protection sequence...");

            return true;
        }
        return false;
    }

    private void sendWebhookNotification() {
        if (!webhook.get()) return;

        String messageContent = "";
        if (selfPing.get() && discordId.get() != null && !discordId.get().trim().isEmpty()) {
            messageContent = String.format("<@%s>", discordId.get().trim());
        }

        SMPUtils.sendWebhookNotification(
            webhookUrl.get(),
            messageContent,
            detectedEntity,
            detectionTime,
            emergencyDisconnect,
            emergencyReason,
            spawnersMinedSuccessfully,
            itemsDepositedSuccessfully,
            this
        );
    }

    private void handleState(ScreenHandler handler) {
        switch (currentState) {
            case IDLE -> currentState = State.FINDING_SPAWNER;
            case FINDING_SPAWNER -> handleFindingSpawner();
            case OPENING_SPAWNER -> handleOpeningSpawner();
            case WAITING_SPAWNER_MENU -> handleWaitingSpawnerMenu(handler);
            case CHECKING_SPAWNER_CONTENTS -> handleCheckingSpawnerContents(handler);
            case PAUSED_NOT_ENOUGH_BONES -> handlePausedNotEnoughBones();
            case CLICK_SLOT_50 -> handleClickSlot50(handler);
            case SENDING_ORDER_COMMAND -> handleSendingOrderCommand();
            case WAITING_ORDER_MENU -> handleWaitingOrderMenu(handler);
            case CLICK_BONE_SLOT -> handleClickBoneSlot(handler);
            case WAITING_DEPOSIT_MENU -> handleWaitingDepositMenu(handler);
            case DEPOSITING_BONES -> handleDepositingBones(handler);
            case CLOSING_DEPOSIT_MENU -> handleClosingDepositMenu();
            case WAITING_CONFIRM_MENU -> handleWaitingConfirmMenu(handler);
            case CLICK_CONFIRM_SLOT -> handleClickConfirmSlot(handler);
            case WAITING_FOR_CHAT_CONFIRMATION -> handleWaitingForChatConfirmation();
            case CLOSING_FIRST_INVENTORY -> handleClosingFirstInventory();
            case CLOSING_SECOND_INVENTORY -> handleClosingSecondInventory();
            case LOOP_DELAY -> handleLoopDelay();
            case RETRY_SEQUENCE -> handleRetrySequence();
            case PROTECT_DETECTION_WAIT -> handleProtectDetectionWait();
            case PROTECT_CHECK_LIME_GLASS -> handleProtectCheckLimeGlass(handler);
            case PROTECT_WAIT_AFTER_LIME_GLASS -> handleProtectWaitAfterLimeGlass();
            case PROTECT_CLOSE_ALL_INVENTORIES -> handleProtectCloseAllInventories();
            case OPENED_CHEST_CHECK -> handleOpenedChestCheck(handler);
            case PROTECT_INITIAL_WAIT -> handleProtectInitialWait();
            case PROTECT_OPEN_PLAYER_INVENTORY -> handleProtectOpenPlayerInventory();
            case PROTECT_CHECK_INVENTORY -> handleProtectCheckInventory();
            case PROTECT_FIND_CHEST -> handleProtectFindChest();
            case PROTECT_OPEN_CHEST -> handleProtectOpenChest();
            case PROTECT_DEPOSIT_BONES_TO_CHEST -> handleProtectDepositBonesToChest(handler);
            case PROTECT_CLOSE_CHEST -> handleProtectCloseChest();
            case PROTECT_RECHECK_WAIT -> handleProtectRecheckWait();
            case PROTECT_GOING_TO_SPAWNERS -> handleProtectGoingToSpawners();
            case PROTECT_GOING_TO_CHEST -> handleProtectGoingToChest();
            case PROTECT_OPENING_CHEST -> handleProtectOpeningChest();
            case PROTECT_DEPOSITING_ITEMS -> handleProtectDepositingItems();
            case PROTECT_DISCONNECTING -> handleProtectDisconnecting();
        }
    }

// ADD THESE METHODS INSIDE THE AutoSpawnerSell CLASS (after handleState method)

    private void handleFindingSpawner() {
        BlockPos spawner = SMPUtils.findNearestSpawner(spawnerRange.get());

        if (spawner != null) {
            targetSpawner = spawner;
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

        SMPUtils.lookAtBlock(targetSpawner);
        info("Right-clicking spawner at " + targetSpawner);
        SMPUtils.interactWithBlock(targetSpawner);

        currentState = State.WAITING_SPAWNER_MENU;
        delayCounter = actionDelay.get() * 2;
        spawnerTimeoutCounter = 0;
    }

    private void handleWaitingSpawnerMenu(ScreenHandler handler) {
        spawnerTimeoutCounter++;

        if (handler instanceof GenericContainerScreenHandler container && container.getRows() == 6) {
            info("Spawner menu opened (6 rows)");
            currentState = State.CHECKING_SPAWNER_CONTENTS;
            delayCounter = actionDelay.get();
            spawnerTimeoutCounter = 0;
            return;
        }

        if (spawnerTimeoutCounter >= spawnerTimeout.get()) {
            warning("Spawner menu timeout - retrying to open spawner...");
            currentState = State.OPENING_SPAWNER;
            delayCounter = actionDelay.get();
            spawnerTimeoutCounter = 0;
        }
    }

    private void handleCheckingSpawnerContents(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        int firstFiveRows = 5 * 9;
        for (int i = 0; i < firstFiveRows; i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (stack.isEmpty() || stack.getItem() != Items.BONE) {
                warning("Not enough bones in spawner! Pausing sequence...");
                mc.player.closeHandledScreen();
                currentState = State.PAUSED_NOT_ENOUGH_BONES;

                if (pauseSequence.get()) {
                    int minDelay = pauseMinimumDelay.get();
                    int maxDelay = pauseMaximumDelay.get();
                    int randomDelay = minDelay + (int)(Math.random() * (maxDelay - minDelay + 1));
                    delayCounter = randomDelay;
                    info("Pausing for " + randomDelay + " ticks (between " + minDelay + " and " + maxDelay + ")");
                } else {
                    delayCounter = 200; // Default delay if pause sequence is disabled
                }
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
        if (handler instanceof GenericContainerScreenHandler container && container.getRows() == 6) {
            info("Order menu detected (6 rows)");
            currentState = State.CLICK_BONE_SLOT;
            delayCounter = actionDelay.get();
        }
    }

    private void handleClickBoneSlot(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        int[] prioritySlots = {4, 5, 6, 7, 8, 9, 10, 11, 12};
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
        if (handler instanceof GenericContainerScreenHandler container && container.getRows() == 4) {
            info("Deposit menu detected (4 rows)");
            currentState = State.DEPOSITING_BONES;
            delayCounter = actionDelay.get();
        }
    }

    private void handleDepositingBones(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

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
            info(chestFull ? "Chest is full, proceeding" : "All bones deposited");
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
        if (handler instanceof GenericContainerScreenHandler container && container.getRows() == 3) {
            info("Confirm menu detected (3 rows)");
            currentState = State.CLICK_CONFIRM_SLOT;
            delayCounter = actionDelay.get();
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

            currentState = SMPUtils.playerHasBones() ? State.SENDING_ORDER_COMMAND : State.FINDING_SPAWNER;
            delayCounter = actionDelay.get() * 2;
            info(SMPUtils.playerHasBones() ? "Player still has bones, retrying" : "No bones, restarting");
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
        boolean hasBones = SMPUtils.playerHasBones();
        currentState = hasBones ? State.SENDING_ORDER_COMMAND : State.FINDING_SPAWNER;
        delayCounter = actionDelay.get() * 2;
        info(hasBones ? "Restarting from /order bones" : "Restarting from spawner");
    }

    private void handleRetrySequence() {
        info("Retrying sequence");
        mc.player.closeHandledScreen();

        boolean hasBones = SMPUtils.playerHasBones();
        currentState = hasBones ? State.SENDING_ORDER_COMMAND : State.FINDING_SPAWNER;
        delayCounter = actionDelay.get() * 3;
        timeoutCounter = 0;
        targetSpawner = null;
    }

    private void handleProtectDetectionWait() {
        info("Detection wait complete, checking for lime glass...");
        currentState = State.PROTECT_CHECK_LIME_GLASS;
        delayCounter = 0;
    }

    private void handleProtectCheckLimeGlass(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler container && container.getRows() == 6) {
            ItemStack slot50 = container.getSlot(50).getStack();
            if (!slot50.isEmpty() && slot50.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                info("Lime glass detected - spawner is active");
                currentState = State.PROTECT_WAIT_AFTER_LIME_GLASS;
                delayCounter = 10;
                return;
            }
        }

        info("No lime glass detected, proceeding...");
        currentState = State.PROTECT_CLOSE_ALL_INVENTORIES;
        delayCounter = 5;
    }

    private void handleProtectWaitAfterLimeGlass() {
        info("Wait after lime glass check complete");
        currentState = State.PROTECT_CLOSE_ALL_INVENTORIES;
        delayCounter = 0;
    }

    private void handleProtectCloseAllInventories() {
        info("Closing all inventories");
        mc.player.closeHandledScreen();
        currentState = State.OPENED_CHEST_CHECK;
        delayCounter = 5;
    }

    private void handleOpenedChestCheck(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler) {
            info("Still have an inventory open, closing it...");
            mc.player.closeHandledScreen();
            delayCounter = 5;
            return;
        }

        info("All inventories closed, starting initial wait");
        currentState = State.PROTECT_INITIAL_WAIT;
        delayCounter = 20;
    }

    private void handleProtectInitialWait() {
        info("Initial wait complete, checking inventory...");
        currentState = State.PROTECT_CHECK_INVENTORY;
        delayCounter = 0;
    }

    private void handleProtectOpenPlayerInventory() {
        info("Opening player inventory to check contents");
        currentState = State.PROTECT_CHECK_INVENTORY;
        delayCounter = 0;
    }

    private void handleProtectCheckInventory() {
        if (SMPUtils.isInventoryFull()) {
            info("Inventory is full! Looking for chest to deposit bones...");
            currentState = State.PROTECT_FIND_CHEST;
            delayCounter = 0;
        } else {
            info("Inventory has space. Proceeding to spawner mining...");
            SMPUtils.setSneaking(true, sneakingState);
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            delayCounter = 0;
        }
    }

    private void handleProtectFindChest() {
        tempBoneChest = SMPUtils.findNearestChestForBones();

        if (tempBoneChest == null) {
            warning("No chest found for bone deposit! Proceeding to spawner mining...");
            SMPUtils.setSneaking(true, sneakingState);
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            delayCounter = 0;
            return;
        }

        info("Found chest at " + tempBoneChest + " for bone deposit");
        currentState = State.PROTECT_OPEN_CHEST;
        boneChestOpenAttempts = 0;
        delayCounter = 5;
    }

    private void handleProtectOpenChest() {
        if (tempBoneChest == null) {
            currentState = State.PROTECT_FIND_CHEST;
            return;
        }

        SMPUtils.lookAtBlock(tempBoneChest);

        if (boneChestOpenAttempts % 3 == 0) {
            SMPUtils.interactWithBlock(tempBoneChest);
            info("Opening chest for bone deposit... (attempt " + (boneChestOpenAttempts / 3 + 1) + ")");
        }

        boneChestOpenAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            currentState = State.PROTECT_DEPOSIT_BONES_TO_CHEST;
            lastProcessedSlot = -1;
            info("Chest opened successfully! Depositing bones...");
            delayCounter = 2;
        }

        if (boneChestOpenAttempts > 60) {
            warning("Failed to open chest! Proceeding to spawner mining...");
            SMPUtils.setSneaking(true, sneakingState);
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            delayCounter = 0;
        }
    }

    private void handleProtectDepositBonesToChest(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.PROTECT_OPEN_CHEST;
            boneChestOpenAttempts = 0;
            return;
        }

        int containerSlots = container.getRows() * 9;
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
        delayCounter = 10;
    }

    private void handleProtectRecheckWait() {
        info("Recheck wait complete, checking inventory again...");
        currentState = State.PROTECT_CHECK_INVENTORY;
        delayCounter = 0;
    }

    private void handleProtectGoingToSpawners() {
        SMPUtils.setSneaking(true, sneakingState);

        if (!SMPUtils.isHoldingDiamondPickaxe()) {
            int pickaxeSlot = SMPUtils.findDiamondPickaxeInHotbar();
            if (pickaxeSlot == -1) {
                warning("No diamond pickaxe found in hotbar!");
                SMPUtils.stopBreaking();
                currentState = State.PROTECT_GOING_TO_CHEST;
                info("Skipping spawner mining, going to chest...");
                protectTickCounter = 0;
                return;
            }

            SMPUtils.swapPickaxeToSlot0(pickaxeSlot);
            info("Moved diamond pickaxe to slot 1");
            delayCounter = 3;
            return;
        }

        if (currentProtectTarget == null) {
            BlockPos found = SMPUtils.findNearestSpawner(entitiesRange.get());

            if (found == null) {
                SMPUtils.stopBreaking();
                spawnersMinedSuccessfully = true;
                currentProtectTarget = null;
                currentState = State.PROTECT_GOING_TO_CHEST;
                info("All spawners mined! Looking for ender chest...");
                protectTickCounter = 0;
                return;
            }

            currentProtectTarget = found;
            isMiningCycle = true;
            miningCycleTimer = 0;
            info("Starting to mine spawner at " + currentProtectTarget);
        }

        if (isMiningCycle) {
            SMPUtils.lookAtBlock(currentProtectTarget);
            SMPUtils.breakBlock(currentProtectTarget);

            if (mc.world.getBlockState(currentProtectTarget).isAir()) {
                info("Spawner at " + currentProtectTarget + " broken!");
                SMPUtils.stopBreaking();
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

    private void handleProtectGoingToChest() {
        SMPUtils.setSneaking(true, sneakingState);

        if (targetChest == null) {
            targetChest = SMPUtils.findNearestEnderChest();
            if (targetChest == null) {
                info("No ender chest found nearby!");
                currentState = State.PROTECT_DISCONNECTING;
                return;
            }
            info("Found ender chest at " + targetChest);
        }

        SMPUtils.moveTowardsBlock(targetChest);

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

    private void handleProtectOpeningChest() {
        if (targetChest == null) {
            currentState = State.PROTECT_GOING_TO_CHEST;
            return;
        }

        SMPUtils.setSneaking(false, sneakingState);
        SMPUtils.stopMoving();
        SMPUtils.setJumping(true);

        if (chestOpenAttempts < 20) {
            SMPUtils.lookAtBlock(targetChest);
        }

        if (chestOpenAttempts % 5 == 0) {
            SMPUtils.interactWithBlock(targetChest);
            info("Right-clicking ender chest... (attempt " + (chestOpenAttempts / 5 + 1) + ")");
        }

        chestOpenAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            SMPUtils.setJumping(false);
            currentState = State.PROTECT_DEPOSITING_ITEMS;
            lastProcessedSlot = -1;
            protectTickCounter = 0;
            info("Ender chest opened successfully!");
        }

        if (chestOpenAttempts > 200) {
            SMPUtils.setJumping(false);
            ChatUtils.error("Failed to open ender chest!");
            currentState = State.PROTECT_DISCONNECTING;
        }
    }

    private void handleProtectDepositingItems() {
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            if (!SMPUtils.hasItemsToDepositFiltered()) {
                itemsDepositedSuccessfully = true;
                info("All items deposited successfully!");
                mc.player.closeHandledScreen();
                transferDelayCounter = 10;
                currentState = State.PROTECT_DISCONNECTING;
                return;
            }

            int[] lastSlot = {lastProcessedSlot};
            int[] transferDelay = {transferDelayCounter};
            SMPUtils.transferFilteredItemsToChest(handler, lastSlot, transferDelay, this);
            lastProcessedSlot = lastSlot[0];
            transferDelayCounter = transferDelay[0];

        } else {
            currentState = State.PROTECT_OPENING_CHEST;
            chestOpenAttempts = 0;
        }

        if (protectTickCounter > 900) {
            ChatUtils.error("Timed out depositing items!");
            currentState = State.PROTECT_DISCONNECTING;
        }
    }

    private void handleProtectDisconnecting() {
        cleanupControls();
        sendWebhookNotification();

        String message = emergencyDisconnect
            ? "SpawnerProtect: " + emergencyReason + ". Successfully disconnected."
            : "SpawnerProtect: " + detectedEntity + " detected. Successfully disconnected.";
        info(message);

        if (mc.world != null) {
            mc.world.disconnect(Text.literal("Entity detected - SpawnerProtect"));
        }

        info("Disconnected due to entity detection.");
        toggle();
    }
}

