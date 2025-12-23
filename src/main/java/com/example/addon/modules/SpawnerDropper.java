package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.player.SmoothAimUtils;
import com.example.addon.utils.smp.SMPUtils;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class SpawnerDropper extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Setting Groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpawnerProtect = settings.createGroup("Spawner Protect");

    // General Settings
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay").description("Delay in ticks between actions")
        .defaultValue(10).min(0).max(100).build());

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

    private final Setting<Boolean> smoothAim = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-aim")
        .description("Enable smooth aiming for spawner interactions")
        .defaultValue(false).build());

    private final Setting<Integer> rotationSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("rotation-speed")
        .description("Rotation speed in ticks for smooth aim")
        .defaultValue(10).min(0).max(600).sliderMax(600)
        .visible(smoothAim::get).build());

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

    private final Setting<Integer> miningCycleDuration = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("mining-cycle-duration").description("How long to hold attack button (in ticks)")
        .defaultValue(60).min(20).max(200).sliderMax(200).visible(enableSpawnerProtect::get).build());

    private final Setting<Integer> pauseBetweenMining = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("pause-between-mining").description("How long to pause between mining cycles (in ticks)")
        .defaultValue(40).min(10).max(200).sliderMax(200).visible(enableSpawnerProtect::get).build());

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
        PAUSED_NOT_ENOUGH_BONES, CLICKING_DROPPER, CLOSING_SPAWNER, REOPENING_SPAWNER,
        PROTECT_DETECTION_WAIT, PROTECT_CHECK_LIME_GLASS, PROTECT_WAIT_AFTER_LIME_GLASS,
        PROTECT_CLOSE_ALL_INVENTORIES, OPENED_CHEST_CHECK, PROTECT_INITIAL_WAIT,
        PROTECT_CHECK_INVENTORY, PROTECT_FIND_CHEST, PROTECT_OPEN_CHEST,
        PROTECT_DEPOSIT_BONES_TO_CHEST, PROTECT_CLOSE_CHEST, PROTECT_RECHECK_WAIT,
        PROTECT_GOING_TO_SPAWNERS, PROTECT_MINING_SPAWNER, PROTECT_PAUSE_BETWEEN_MINES,
        PROTECT_CHECK_IF_SPAWNER_EXISTS, PROTECT_GOING_TO_CHEST, PROTECT_OPENING_CHEST,
        PROTECT_DEPOSITING_ITEMS, PROTECT_DISCONNECTING
    }

    // State variables
    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private int spawnerTimeoutCounter = 0;
    private BlockPos targetSpawner = null;

    // Spawner Protect variables
    private String detectedEntity = "";
    private long detectionTime = 0;
    private boolean spawnersMinedSuccessfully = false;
    private boolean itemsDepositedSuccessfully = false;
    private int protectTickCounter = 0;
    private final boolean[] sneakingState = {false};
    private BlockPos currentProtectTarget = null;
    private int miningTimer = 0;
    private BlockPos targetChest = null;
    private int chestOpenAttempts = 0;
    private String emergencyReason = "";
    private int transferDelayCounter = 0;
    private int lastProcessedSlot = -1;
    private BlockPos tempBoneChest = null;
    private int boneChestOpenAttempts = 0;
    private int spawnerCheckAttempts = 0;
    private static final int MAX_SPAWNER_CHECK_ATTEMPTS = 8;

    public SpawnerDropper() {
        super(Main.CATEGORY, "spawner-dropper", "Automatically drops bones from spawner");
    }

    @Override
    public void onActivate() {
        currentState = State.FINDING_SPAWNER;
        resetCounters();
        resetProtectState();

        info("SpawnerDropper activated - searching for spawner...");

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
        SmoothAimUtils.cancelRotation();
        info("SpawnerDropper deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        protectTickCounter++;

        // Handle smooth aim rotation if active
        if (smoothAim.get() && SmoothAimUtils.isRotating()) {
            SmoothAimUtils.tickRotation();
        }

        // Spawner Protect checks
        if (enableSpawnerProtect.get() && !isProtectMode() && checkForEntities()) {
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
        handleState(handler);
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!enableSpawnerProtect.get() || mc.player == null || mc.world == null) return;

        // Skip sound detection if in protect mode
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
        spawnerTimeoutCounter = 0;
        targetSpawner = null;
    }

    private void resetProtectState() {
        detectedEntity = "";
        detectionTime = 0;
        spawnersMinedSuccessfully = false;
        itemsDepositedSuccessfully = false;
        protectTickCounter = 0;
        sneakingState[0] = false;
        currentProtectTarget = null;
        miningTimer = 0;
        targetChest = null;
        chestOpenAttempts = 0;
        emergencyReason = "";
        transferDelayCounter = 0;
        lastProcessedSlot = -1;
        tempBoneChest = null;
        boneChestOpenAttempts = 0;
        spawnerCheckAttempts = 0;
    }

    private void cleanupControls() {
        SMPUtils.stopBreaking();
        SMPUtils.setSneaking(false, sneakingState);
        SMPUtils.stopMoving();
        SMPUtils.setJumping(false);
    }

    private boolean isProtectMode() {
        return currentState.name().startsWith("PROTECT_") || currentState == State.OPENED_CHEST_CHECK;
    }

    private boolean checkForEntities() {
        if (isProtectMode()) return false;
        if (targetEntities.get().isEmpty()) return false;

        List<Entity> nearbyEntities = mc.world.getOtherEntities(mc.player,
            new net.minecraft.util.math.Box(mc.player.getBlockPos()).expand(entitiesRange.get()));

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
            false,
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
            case CLICKING_DROPPER -> handleClickingDropper(handler);
            case CLOSING_SPAWNER -> handleClosingSpawner();
            case REOPENING_SPAWNER -> handleReopeningSpawner();
            case PROTECT_DETECTION_WAIT -> handleProtectDetectionWait();
            case PROTECT_CHECK_LIME_GLASS -> handleProtectCheckLimeGlass(handler);
            case PROTECT_WAIT_AFTER_LIME_GLASS -> handleProtectWaitAfterLimeGlass();
            case PROTECT_CLOSE_ALL_INVENTORIES -> handleProtectCloseAllInventories();
            case OPENED_CHEST_CHECK -> handleOpenedChestCheck(handler);
            case PROTECT_INITIAL_WAIT -> handleProtectInitialWait();
            case PROTECT_CHECK_INVENTORY -> handleProtectCheckInventory();
            case PROTECT_FIND_CHEST -> handleProtectFindChest();
            case PROTECT_OPEN_CHEST -> handleProtectOpenChest();
            case PROTECT_DEPOSIT_BONES_TO_CHEST -> handleProtectDepositBonesToChest(handler);
            case PROTECT_CLOSE_CHEST -> handleProtectCloseChest();
            case PROTECT_RECHECK_WAIT -> handleProtectRecheckWait();
            case PROTECT_GOING_TO_SPAWNERS -> handleProtectGoingToSpawners();
            case PROTECT_MINING_SPAWNER -> handleProtectMiningSpawner();
            case PROTECT_PAUSE_BETWEEN_MINES -> handleProtectPauseBetweenMines();
            case PROTECT_CHECK_IF_SPAWNER_EXISTS -> handleProtectCheckIfSpawnerExists();
            case PROTECT_GOING_TO_CHEST -> handleProtectGoingToChest();
            case PROTECT_OPENING_CHEST -> handleProtectOpeningChest();
            case PROTECT_DEPOSITING_ITEMS -> handleProtectDepositingItems();
            case PROTECT_DISCONNECTING -> handleProtectDisconnecting();
        }
    }

    // ==================== MAIN SEQUENCE HANDLERS ====================

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

        if (smoothAim.get() && !SmoothAimUtils.isRotating()) {
            SmoothAimUtils.startSmoothRotation(targetSpawner, rotationSpeed.get(), () -> {
                info("Right-clicking spawner at " + targetSpawner + " (raytraced)");
                SMPUtils.interactWithBlockRaytraced(targetSpawner);
            });
        } else if (!smoothAim.get()) {
            SMPUtils.lookAtBlock(targetSpawner);
            info("Right-clicking spawner at " + targetSpawner + " (raytraced)");
            SMPUtils.interactWithBlockRaytraced(targetSpawner);
        } else {
            return;
        }

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
            currentState = State.FINDING_SPAWNER;
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
                    delayCounter = 200;
                }
                return;
            }
        }

        info("Spawner contents verified - all first 5 rows contain bones");
        currentState = State.CLICKING_DROPPER;
        delayCounter = actionDelay.get();
    }

    private void handlePausedNotEnoughBones() {
        info("Pause complete, restarting sequence from spawner");
        currentState = State.FINDING_SPAWNER;
        delayCounter = actionDelay.get() * 2;
    }

    private void handleClickingDropper(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.FINDING_SPAWNER;
            return;
        }

        boolean dropperFound = SMPUtils.clickDropperInSpawner(container, this);

        if (dropperFound) {
            info("Clicked dropper item, closing spawner...");
            currentState = State.CLOSING_SPAWNER;
            delayCounter = actionDelay.get();
        } else {
            warning("Dropper not found in spawner GUI! Restarting...");
            mc.player.closeHandledScreen();
            currentState = State.FINDING_SPAWNER;
            delayCounter = actionDelay.get() * 2;
        }
    }

    private void handleClosingSpawner() {
        info("Closing spawner interface");
        mc.player.closeHandledScreen();
        currentState = State.REOPENING_SPAWNER;
        delayCounter = 2;
        spawnerTimeoutCounter = 0;
    }

    private void handleReopeningSpawner() {
        if (targetSpawner == null) {
            warning("Lost spawner position, restarting search...");
            currentState = State.FINDING_SPAWNER;
            delayCounter = actionDelay.get();
            return;
        }

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            info("GUI still open, closing again...");
            mc.player.closeHandledScreen();
            delayCounter = 2;
            return;
        }

        info("Reopening spawner to check for more bones at " + targetSpawner);

        if (smoothAim.get() && !SmoothAimUtils.isRotating()) {
            SmoothAimUtils.startSmoothRotation(targetSpawner, rotationSpeed.get(), () -> {
                info("Right-clicking spawner at " + targetSpawner + " (raytraced)");
                SMPUtils.interactWithBlockRaytraced(targetSpawner);
            });
        } else if (!smoothAim.get()) {
            SMPUtils.lookAtBlock(targetSpawner);
            info("Right-clicking spawner at " + targetSpawner + " (raytraced)");
            SMPUtils.interactWithBlockRaytraced(targetSpawner);
        } else {
            return;
        }

        currentState = State.WAITING_SPAWNER_MENU;
        delayCounter = 5;
        spawnerTimeoutCounter = 0;
    }

    // ==================== SPAWNER PROTECT HANDLERS ====================

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

        if (smoothAim.get() && !SmoothAimUtils.isRotating()) {
            if (boneChestOpenAttempts % 3 == 0) {
                SmoothAimUtils.startSmoothRotation(tempBoneChest, rotationSpeed.get(), () -> {
                    SMPUtils.interactWithBlock(tempBoneChest);
                    info("Opening chest for bone deposit... (attempt " + (boneChestOpenAttempts / 3 + 1) + ")");
                });
            }
        } else if (!smoothAim.get()) {
            SMPUtils.lookAtBlock(tempBoneChest);
            if (boneChestOpenAttempts % 3 == 0) {
                SMPUtils.interactWithBlock(tempBoneChest);
                info("Opening chest for bone deposit... (attempt " + (boneChestOpenAttempts / 3 + 1) + ")");
            }
        } else {
            return;
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
                mc.interactionManager.clickSlot(handler.syncId, i, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
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
            int spawnerCount = SMPUtils.countSpawnersInRange(entitiesRange.get());
            info("Searching for spawners... Found " + spawnerCount + " in range " + entitiesRange.get());

            BlockPos found = SMPUtils.findNearestSpawner(entitiesRange.get());

            if (found == null) {
                info("No more spawners found in range " + entitiesRange.get());
                SMPUtils.stopBreaking();
                spawnersMinedSuccessfully = true;
                currentProtectTarget = null;
                currentState = State.PROTECT_GOING_TO_CHEST;
                info("All spawners mined! Looking for ender chest...");
                protectTickCounter = 0;
                return;
            }

            currentProtectTarget = found;
            miningTimer = 0;
            spawnerCheckAttempts = 0;
            info("Found spawner at " + currentProtectTarget + " (distance: " +
                String.format("%.1f", Math.sqrt(mc.player.getBlockPos().getSquaredDistance(currentProtectTarget))) + " blocks)");
            currentState = State.PROTECT_MINING_SPAWNER;
            delayCounter = 0;
        }
    }

    private void handleProtectMiningSpawner() {
        SMPUtils.setSneaking(true, sneakingState);

        if (currentProtectTarget == null) {
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            return;
        }

        // Apply rotation and start breaking
        if (smoothAim.get() && !SmoothAimUtils.isRotating() && miningTimer == 0) {
            SmoothAimUtils.startSmoothRotation(currentProtectTarget, rotationSpeed.get(), () -> {
                SMPUtils.breakBlock(currentProtectTarget);
                info("Started mining spawner at " + currentProtectTarget);
            });
        } else if (!smoothAim.get() && miningTimer == 0) {
            SMPUtils.lookAtBlock(currentProtectTarget);
            SMPUtils.breakBlock(currentProtectTarget);
            info("Started mining spawner at " + currentProtectTarget);
        }

        miningTimer++;

        // Check if we've reached the mining duration
        if (miningTimer >= miningCycleDuration.get()) {
            info("Mining cycle complete (" + miningTimer + " ticks), stopping attack...");
            SMPUtils.stopBreaking();
            currentState = State.PROTECT_PAUSE_BETWEEN_MINES;
            miningTimer = 0;
            delayCounter = 0;
        }
    }

    private void handleProtectPauseBetweenMines() {
        SMPUtils.setSneaking(true, sneakingState);
        SMPUtils.stopBreaking();

        miningTimer++;

        // Wait for the pause duration
        if (miningTimer >= pauseBetweenMining.get()) {
            info("Pause complete, checking if spawner still exists...");
            miningTimer = 0;
            currentState = State.PROTECT_CHECK_IF_SPAWNER_EXISTS;
            delayCounter = 0;
        }
    }

    private void handleProtectCheckIfSpawnerExists() {
        SMPUtils.setSneaking(true, sneakingState);
        SMPUtils.stopBreaking();

        if (currentProtectTarget == null || mc.world == null) {
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            currentProtectTarget = null;
            return;
        }

        net.minecraft.block.BlockState blockState = mc.world.getBlockState(currentProtectTarget);
        boolean spawnerExists = !blockState.isAir() && blockState.getBlock() == net.minecraft.block.Blocks.SPAWNER;

        if (spawnerExists) {
            spawnerCheckAttempts++;
            info("Spawner still exists at " + currentProtectTarget + " (check " + spawnerCheckAttempts + "/" + MAX_SPAWNER_CHECK_ATTEMPTS + ")");

            if (spawnerCheckAttempts >= MAX_SPAWNER_CHECK_ATTEMPTS) {
                info("Spawner persists after " + MAX_SPAWNER_CHECK_ATTEMPTS + " checks, continuing to mine...");
                spawnerCheckAttempts = 0;
                currentState = State.PROTECT_MINING_SPAWNER;
                delayCounter = 0;
            } else {
                // Wait a bit more before checking again
                currentState = State.PROTECT_PAUSE_BETWEEN_MINES;
                miningTimer = pauseBetweenMining.get() - 10;
                delayCounter = 0;
            }
        } else {
            info("Spawner broken successfully at " + currentProtectTarget + "!");
            currentProtectTarget = null;
            spawnerCheckAttempts = 0;
            currentState = State.PROTECT_GOING_TO_SPAWNERS;
            delayCounter = 5;
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

        if (smoothAim.get() && !SmoothAimUtils.isRotating()) {
            if (chestOpenAttempts < 20) {
                if (chestOpenAttempts % 5 == 0) {
                    SmoothAimUtils.startSmoothRotation(targetChest, rotationSpeed.get(), () -> {
                        SMPUtils.interactWithBlock(targetChest);
                        info("Right-clicking ender chest... (attempt " + (chestOpenAttempts / 5 + 1) + ")");
                    });
                }
            }
        } else if (!smoothAim.get()) {
            if (chestOpenAttempts < 20) {
                SMPUtils.lookAtBlock(targetChest);
            }
            if (chestOpenAttempts % 5 == 0) {
                SMPUtils.interactWithBlock(targetChest);
                info("Right-clicking ender chest... (attempt " + (chestOpenAttempts / 5 + 1) + ")");
            }
        } else {
            chestOpenAttempts++;
            return;
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

        String message = "SpawnerProtect: " + detectedEntity + " detected. Successfully disconnected.";
        info(message);

        if (mc.world != null) {
            mc.world.disconnect(Text.literal("Entity detected - SpawnerProtect"));
        }

        info("Disconnected due to entity detection.");
        toggle();
    }
}
