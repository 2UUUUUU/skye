package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.player.SmoothAimUtils;
import com.example.addon.utils.smp.SMPUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoSpawnerSell extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Setting Groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFailsafes = settings.createGroup("Failsafes");
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

    private final Setting<Boolean> smoothAim = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-aim")
        .description("Enable smooth aiming for spawner interactions")
        .defaultValue(false).build());

    private final Setting<Integer> rotationSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("rotation-speed")
        .description("Rotation speed in ticks for smooth aim")
        .defaultValue(10).min(0).max(600).sliderMax(600)
        .visible(smoothAim::get).build());

    private final Setting<SilentMode> silentMode = sgGeneral.add(new EnumSetting.Builder<SilentMode>()
        .name("silent-mode")
        .description("Hide logs in chat")
        .defaultValue(SilentMode.Disabled)
        .build());

    // Failsafes Settings
    private final Setting<TeleportAction> onTeleport = sgFailsafes.add(new EnumSetting.Builder<TeleportAction>()
        .name("on-teleport")
        .description("What to do when a teleport is detected")
        .defaultValue(TeleportAction.PauseUntilReturn).build());

    private final Setting<Integer> teleportDistance = sgFailsafes.add(new IntSetting.Builder()
        .name("distance")
        .description("Distance in blocks to detect as teleport")
        .defaultValue(100).min(10).max(1000).sliderMax(500).build());

    private final Setting<Integer> teleportTimeWindow = sgFailsafes.add(new IntSetting.Builder()
        .name("time-window")
        .description("Time window in ticks to detect teleport")
        .defaultValue(20).min(1).max(100).sliderMax(100).build());

    private final Setting<String> homePosition = sgFailsafes.add(new StringSetting.Builder()
        .name("home-position")
        .description("Home position in format: X Y Z (e.g., 100 64 200)")
        .defaultValue("")
        .visible(() -> onTeleport.get() == TeleportAction.PauseUntilReturn).build());

    private final Setting<Integer> pauseTime = sgFailsafes.add(new IntSetting.Builder()
        .name("pause-time")
        .description("Time in ticks to pause when teleport detected")
        .defaultValue(200).min(1).max(72000).sliderMax(1200)
        .visible(() -> onTeleport.get() == TeleportAction.PauseFor).build());

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

    private final Setting<Integer> maxBoneCount = sgSpawnerProtect.add(new IntSetting.Builder()
        .name("max-bone-count")
        .description("Sell bones once this limit is reached")
        .defaultValue(1152)
        .min(0)
        .max(2176)
        .sliderMax(2176)
        .visible(enableSpawnerProtect::get)
        .build());

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

    private final Setting<Boolean> showDetectionBox = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("show-detection-box")
        .description("Show a box around the player indicating detection range")
        .defaultValue(true)
        .visible(enableSpawnerProtect::get)
        .build());

    private final Setting<SettingColor> detectionBoxColor = sgSpawnerProtect.add(new ColorSetting.Builder()
        .name("detection-box-color")
        .description("Color of the detection range box")
        .defaultValue(new SettingColor(255, 255, 0, 50))
        .visible(() -> enableSpawnerProtect.get() && showDetectionBox.get())
        .build());

    private final Setting<Boolean> showEntityBox = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("show-entity-box")
        .description("Show a box around detected entities")
        .defaultValue(true)
        .visible(enableSpawnerProtect::get)
        .build());

    private final Setting<SettingColor> entityBoxColor = sgSpawnerProtect.add(new ColorSetting.Builder()
        .name("entity-box-color")
        .description("Color of the entity box")
        .defaultValue(new SettingColor(255, 0, 0, 75))
        .visible(() -> enableSpawnerProtect.get() && showEntityBox.get())
        .build());

    private final Setting<Boolean> showTracerLine = sgSpawnerProtect.add(new BoolSetting.Builder()
        .name("show-tracer-line")
        .description("Draw a line to detected entities")
        .defaultValue(true)
        .visible(enableSpawnerProtect::get)
        .build());

    private final Setting<SettingColor> tracerLineColor = sgSpawnerProtect.add(new ColorSetting.Builder()
        .name("tracer-line-color")
        .description("Color of the tracer line")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(() -> enableSpawnerProtect.get() && showTracerLine.get())
        .build());

    private final Setting<ShapeMode> shapeMode = sgSpawnerProtect.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered")
        .defaultValue(ShapeMode.Both)
        .visible(enableSpawnerProtect::get)
        .build());

    // State enum
    private enum State {
        IDLE, FINDING_SPAWNER, OPENING_SPAWNER, WAITING_SPAWNER_MENU, CHECKING_SPAWNER_CONTENTS,
        PAUSED_NOT_ENOUGH_BONES, CLICK_SLOT_50, CLOSING_SPAWNER_MENU, SENDING_ORDER_COMMAND, WAITING_ORDER_MENU,
        CLICK_BONE_SLOT, WAITING_DEPOSIT_MENU, DEPOSITING_BONES, CLOSING_DEPOSIT_MENU,
        WAITING_CONFIRM_MENU, CLICK_CONFIRM_SLOT, WAITING_FOR_CHAT_CONFIRMATION,
        CLOSING_FIRST_INVENTORY, CLOSING_SECOND_INVENTORY, LOOP_DELAY, RETRY_SEQUENCE,
        PROTECT_DETECTION_WAIT, PROTECT_CHECK_LIME_GLASS, PROTECT_WAIT_AFTER_LIME_GLASS,
        PROTECT_CLOSE_ALL_INVENTORIES, OPENED_CHEST_CHECK, PROTECT_INITIAL_WAIT,
        PROTECT_OPEN_PLAYER_INVENTORY, PROTECT_CHECK_INVENTORY, PROTECT_FIND_CHEST,
        PROTECT_OPEN_CHEST, PROTECT_DEPOSIT_BONES_TO_CHEST, PROTECT_CLOSE_CHEST,
        PROTECT_RECHECK_WAIT, PROTECT_GOING_TO_SPAWNERS, PROTECT_GOING_TO_CHEST,
        PROTECT_OPENING_CHEST, PROTECT_DEPOSITING_ITEMS, PROTECT_DISCONNECTING,
        TELEPORT_PAUSED, TELEPORT_PAUSE_FOR_TIME, WAITING_CONFIRM_SELL_MENU, CLICK_CONFIRM_SELL,
        CLICK_CONFIRM_SELL_SECOND, WAIT_AFTER_SELL_CONFIRM,
        PROTECT_SELLING_BONES, PROTECT_WAITING_SELL_MENU, PROTECT_DEPOSITING_TO_SELL, PROTECT_CLOSING_SELL_MENU

    }

    // Teleport Action Enum
    public enum TeleportAction {
        DisableModule("Disable Module"),
        Disconnect("Disconnect"),
        PauseUntilReturn("Pause Until Return"),
        PauseFor("Pause For");

        private final String name;
        TeleportAction(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }
    // Silent Mode Enum
    public enum SilentMode {
        Disabled("Disabled"),
        HideDebug("Hide Debug"),
        HideAll("Hide All");

        private final String name;
        SilentMode(String name) { this.name = name; }
        @Override public String toString() { return name; }
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
    private boolean protectSelling = false;

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
    private String emergencyReason = "";
    private int transferDelayCounter = 0;
    private int lastProcessedSlot = -1;

    // Teleport detection variables
    private Vec3d lastPosition = null;
    private Vec3d positionBeforeTeleport = null;
    private State stateBeforeTeleport = State.IDLE;
    private int teleportWaitCounter = 0;
    private boolean teleportDetected = false;

    // Entity detection tracking - COMPLETELY INDEPENDENT
    private final Set<UUID> detectedEntities = new HashSet<>();
    private final Map<UUID, Entity> currentlyDetectedEntities = new HashMap<>();
    private Entity mostRecentDetectedEntity = null;
    private int continuousDetectionTicks = 0;
    private static final int DETECTION_THRESHOLD = 3; // Require 3 consecutive ticks to trigger
    private boolean entityProtectionTriggered = false; // Track if we've already triggered protection

    public AutoSpawnerSell() {
        super(Main.CATEGORY, "auto-spawner-sell", "Automatically drops bones from spawner and sells them");
    }

    @Override
    public void onActivate() {
        currentState = State.FINDING_SPAWNER;
        resetCounters();
        resetProtectState();

        if (mc.player != null) {
            lastPosition = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            positionBeforeTeleport = null;
        }

        info("AutoSpawnerSell activated - searching for spawner...");

        if (enableSpawnerProtect.get()) {
            info("Spawner Protection is ENABLED - monitoring for entities...");
            info("Monitoring entities: " + targetEntities.get().size() + " types");
            info("Detection range: " + entitiesRange.get() + " blocks");
            info("Detection uses EXACT positions (not block-aligned)");
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

        // Clear ALL detection tracking
        detectedEntities.clear();
        currentlyDetectedEntities.clear();
        mostRecentDetectedEntity = null;
        continuousDetectionTicks = 0;
        entityProtectionTriggered = false;

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

        performEntityDetection();

        // === SCREEN DEBUG ===
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            if (protectTickCounter % 20 == 0) {
                String title = screen.getTitle().getString();
                var handler = screen.getScreenHandler();
                info("=== SCREEN DEBUG ===");
                info("Current State: " + currentState);
                info("Screen Title: '" + title + "'");
                info("Handler Type: " + handler.getClass().getSimpleName());
                if (handler instanceof GenericContainerScreenHandler container) {
                    info("Container Rows: " + container.getRows());
                    info("Total Slots: " + container.slots.size());
                }
                info("Currently Detected Entities: " + currentlyDetectedEntities.size());
                info("Continuous Detection Ticks: " + continuousDetectionTicks);
                info("===================");
            }
        }

        protectTickCounter++;

        // === SMOOTH AIM ===
        if (smoothAim.get() && SmoothAimUtils.isRotating()) {
            SmoothAimUtils.tickRotation();
        }

        // === TELEPORT STATES ===
        if (currentState == State.TELEPORT_PAUSED || currentState == State.TELEPORT_PAUSE_FOR_TIME) {
            handleTeleportStates();
            return;
        }

        // === TELEPORT DETECTION ===
        if (checkTeleportDetection()) {
            return;
        }

        // === TRANSFER DELAY ===
        if (transferDelayCounter > 0) {
            transferDelayCounter--;
            return;
        }

        // === ACTION DELAY ===
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        // === HANDLE CURRENT STATE ===
        ScreenHandler handler = mc.player.currentScreenHandler;
        handleState(handler);
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!enableSpawnerProtect.get() || mc.player == null || mc.world == null) return;

        if (isTeleportMode() || isProtectMode()) return;

        String soundId = event.sound.getId().toString();

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

    private boolean checkTeleportDetection() {
        if (mc.player == null) return false;

        if (lastPosition == null) {
            lastPosition = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            return false;
        }

        Vec3d currentPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double distance = currentPos.distanceTo(lastPosition);

        if (distance >= teleportDistance.get()) {
            warning("Teleport detected! Distance: " + String.format("%.0f", distance) + " blocks");

            State previousState = currentState;
            handleTeleportDetected();

            lastPosition = currentPos;

            info("Current state after teleport: " + currentState);
            info("Is in teleport mode: " + isTeleportMode());

            return true;
        }

        lastPosition = currentPos;
        return false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!enableSpawnerProtect.get() || mc.player == null) return;

        if (showDetectionBox.get()) {
            double range = entitiesRange.get();
            Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

            event.renderer.box(
                playerPos.x - range, playerPos.y - range, playerPos.z - range,
                playerPos.x + range, playerPos.y + range, playerPos.z + range,
                detectionBoxColor.get(),
                detectionBoxColor.get(),
                shapeMode.get(),
                0
            );
        }

        if (!currentlyDetectedEntities.isEmpty()) {
            Vec3d playerEyePos = mc.player.getEyePos();

            for (Entity entity : currentlyDetectedEntities.values()) {
                if (entity == null || !entity.isAlive()) continue;

                net.minecraft.util.math.Box entityBox = entity.getBoundingBox();

                if (showEntityBox.get()) {
                    event.renderer.box(
                        entityBox.minX, entityBox.minY, entityBox.minZ,
                        entityBox.maxX, entityBox.maxY, entityBox.maxZ,
                        entityBoxColor.get(),
                        entityBoxColor.get(),
                        shapeMode.get(),
                        0
                    );
                }

                if (showTracerLine.get()) {
                    Vec3d entityCenter = new Vec3d(entity.getX(), entity.getY() + entity.getHeight() / 2, entity.getZ());

                    event.renderer.line(
                        playerEyePos.x, playerEyePos.y, playerEyePos.z,
                        entityCenter.x, entityCenter.y, entityCenter.z,
                        tracerLineColor.get()
                    );
                }
            }
        }
    }

    private void handleTeleportDetected() {
        teleportDetected = true;
        positionBeforeTeleport = lastPosition;

        switch (onTeleport.get()) {
            case DisableModule:
                info("Teleport detected - Disabling module");
                toggle();
                break;

            case Disconnect:
                info("Teleport detected - Disconnecting");
                if (mc.world != null) {
                    mc.world.disconnect(Text.literal("Disconnected: Teleport Detected"));
                }
                toggle();
                break;

            case PauseUntilReturn:
                Vec3d homePos = parseHomePosition();
                if (homePos == null) {
                    error("Invalid home position format! Expected format: X Y Z (e.g., 100 64 200)");
                    error("Please set a valid home position in the settings.");
                    toggle();
                    return;
                }

                info("Teleport detected - Pausing until return to home position");
                info("Home position: " + String.format("X:%.1f Y:%.1f Z:%.1f",
                    homePos.x, homePos.y, homePos.z));
                if (currentState != State.TELEPORT_PAUSED) {
                    stateBeforeTeleport = currentState;
                    currentState = State.TELEPORT_PAUSED;
                    cleanupControls();
                    SmoothAimUtils.cancelRotation();
                }
                break;

            case PauseFor:
                int pauseDuration = pauseTime.get();
                info("Teleport detected - Pausing for " + pauseDuration + " ticks (" +
                    String.format("%.1f", pauseDuration / 20.0) + " seconds)");
                if (currentState != State.TELEPORT_PAUSE_FOR_TIME) {
                    stateBeforeTeleport = currentState;
                    currentState = State.TELEPORT_PAUSE_FOR_TIME;
                    teleportWaitCounter = pauseDuration;
                    cleanupControls();
                    SmoothAimUtils.cancelRotation();
                }
                break;
        }
    }

    private void handleTeleportStates() {
        if (currentState == State.TELEPORT_PAUSE_FOR_TIME) {
            if (teleportWaitCounter > 0) {
                teleportWaitCounter--;
                if (teleportWaitCounter % 100 == 0 && teleportWaitCounter > 0) {
                    info("Resuming in " + teleportWaitCounter + " ticks (" +
                        String.format("%.1f", teleportWaitCounter / 20.0) + " seconds)");
                }
            } else {
                info("Pause duration complete - resuming from previous state");
                currentState = stateBeforeTeleport;
                stateBeforeTeleport = State.IDLE;
                positionBeforeTeleport = null;
                teleportDetected = false;
            }
            return;
        }

        if (mc.player == null) return;

        if (currentState == State.TELEPORT_PAUSED) {
            Vec3d homePos = parseHomePosition();
            if (homePos == null) {
                error("Invalid home position! Disabling module.");
                toggle();
                return;
            }

            Vec3d currentPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            double distanceFromHome = currentPos.distanceTo(homePos);

            if (distanceFromHome < teleportDistance.get()) {
                info("Return detected - Back at home position (distance: " + String.format("%.1f", distanceFromHome) + " blocks)");
                info("Resuming sequence...");
                currentState = stateBeforeTeleport;
                stateBeforeTeleport = State.IDLE;
                positionBeforeTeleport = null;
                teleportDetected = false;
                lastPosition = currentPos;
            }
        }
    }

    private void performEntityDetection() {
        if (!enableSpawnerProtect.get() || mc.player == null || mc.world == null) {
            if (!currentlyDetectedEntities.isEmpty()) {
                currentlyDetectedEntities.clear();
                mostRecentDetectedEntity = null;
                continuousDetectionTicks = 0;
            }
            return;
        }

        if (targetEntities.get().isEmpty()) return;

        currentlyDetectedEntities.clear();
        mostRecentDetectedEntity = null;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double range = entitiesRange.get();

        net.minecraft.util.math.Box detectionBox = new net.minecraft.util.math.Box(
            playerPos.x - range, playerPos.y - range, playerPos.z - range,
            playerPos.x + range, playerPos.y + range, playerPos.z + range
        );

        final boolean[] foundEntityThisTick = {false};

        EntityUtils.intersectsWithEntity(detectionBox, entity -> {
            if (entity == mc.player) return false;
            if (!targetEntities.get().contains(entity.getType())) return false;

            Vec3d entityPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            double distanceToEntity = playerPos.distanceTo(entityPos);
            if (distanceToEntity > range) return false;

            if (entity instanceof PlayerEntity playerEntity) {
                String playerName = SMPUtils.getPlayerName(playerEntity);
                if (enableWhitelist.get() && SMPUtils.isPlayerWhitelisted(playerName, whitelistPlayers.get())) {
                    return false;
                }
            }

            currentlyDetectedEntities.put(entity.getUuid(), entity);

            if (mostRecentDetectedEntity == null) {
                mostRecentDetectedEntity = entity;
            } else {
                Vec3d currentEntityPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
                Vec3d mostRecentPos = new Vec3d(mostRecentDetectedEntity.getX(), mostRecentDetectedEntity.getY(), mostRecentDetectedEntity.getZ());
                if (playerPos.distanceTo(currentEntityPos) < playerPos.distanceTo(mostRecentPos)) {
                    mostRecentDetectedEntity = entity;
                }
            }

            foundEntityThisTick[0] = true;
            return false;
        });

        if (foundEntityThisTick[0]) {
            continuousDetectionTicks++;

            if (continuousDetectionTicks >= DETECTION_THRESHOLD &&
                mostRecentDetectedEntity != null &&
                !entityProtectionTriggered) {

                entityProtectionTriggered = true;
                detectedEntities.add(mostRecentDetectedEntity.getUuid());
                triggerProtectionSequence(mostRecentDetectedEntity);
                continuousDetectionTicks = 0;
            }
        } else {
            continuousDetectionTicks = 0;
            if (!isProtectMode() && !protectSelling) {
                entityProtectionTriggered = false;
            }
        }
    }

    private void triggerProtectionSequence(Entity detectedEntity) {
        this.detectedEntity = SMPUtils.getEntityName(detectedEntity);
        this.detectionTime = System.currentTimeMillis();

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d entityPos = new Vec3d(detectedEntity.getX(), detectedEntity.getY(), detectedEntity.getZ());
        double exactDistance = playerPos.distanceTo(entityPos);

        warning("SpawnerProtect: Entity detected - " + this.detectedEntity);
        info("Exact Distance: " + String.format("%.2f", exactDistance) + " blocks");
        info("Entity Position: X=" + String.format("%.2f", detectedEntity.getX()) +
            " Y=" + String.format("%.2f", detectedEntity.getY()) +
            " Z=" + String.format("%.2f", detectedEntity.getZ()));
        info("Player Position: X=" + String.format("%.2f", mc.player.getX()) +
            " Y=" + String.format("%.2f", mc.player.getY()) +
            " Z=" + String.format("%.2f", mc.player.getZ()));
        info("Detected for " + continuousDetectionTicks + " consecutive ticks");
        info("Current state when entity detected: " + currentState);
        info("In menu: " + (mc.currentScreen != null));
        info("In delay: " + (delayCounter > 0));
        info("Protect selling: " + protectSelling);

        SMPUtils.disableAutoReconnectIfEnabled(this);

        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            info("Closed open screen/menu");
        }

        currentState = State.PROTECT_DETECTION_WAIT;
        delayCounter = 3 + (int)(Math.random() * 8);
        info("Entity detected! Starting protection sequence...");
    }


    private Vec3d parseHomePosition() {
        String posStr = homePosition.get().trim();
        if (posStr.isEmpty()) {
            return null;
        }

        try {
            String[] parts = posStr.split("\\s+");
            if (parts.length != 3) {
                return null;
            }

            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);

            return new Vec3d(x, y, z);
        } catch (NumberFormatException e) {
            return null;
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
        emergencyReason = "";
        transferDelayCounter = 0;
        lastProcessedSlot = -1;
        teleportWaitCounter = 0;
        stateBeforeTeleport = State.IDLE;
        teleportDetected = false;
        positionBeforeTeleport = null;
        protectSelling = false;

        // Clear entity detection tracking
        detectedEntities.clear();
        currentlyDetectedEntities.clear();
        mostRecentDetectedEntity = null;
        continuousDetectionTicks = 0;
        entityProtectionTriggered = false;
    }

    private void cleanupControls() {
        SMPUtils.stopBreaking();
        SMPUtils.setSneaking(false, sneakingState);
        SMPUtils.stopMoving();
        SMPUtils.setJumping(false);
    }

    private boolean isTeleportMode() {
        return currentState == State.TELEPORT_PAUSED ||
            currentState == State.TELEPORT_PAUSE_FOR_TIME;
    }

    private boolean isProtectMode() {
        return currentState.name().startsWith("PROTECT_") || currentState == State.OPENED_CHEST_CHECK;
    }

    private boolean checkForEntities() {
        if (isTeleportMode()) {
            if (protectTickCounter % 20 == 0) {
                info("Skipping entity check - in teleport mode (state: " + currentState + ")");
            }
            return false;
        }

        if (isProtectMode()) return false;

        // Skip entity check if we're selling during protect sequence
        if (protectSelling) {
            return false;
        }

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
            info("Current state when entity detected: " + currentState);
            info("Is in teleport mode: " + isTeleportMode());
            SMPUtils.disableAutoReconnectIfEnabled(this);

            mc.player.closeHandledScreen();
            currentState = State.PROTECT_DETECTION_WAIT;
            delayCounter = 3 + (int)(Math.random() * 8); // Random 3-10 ticks
            info("Entity detected! Starting protection sequence...");

            return true;
        }
        return false;
    }

    private boolean isInDeliverySequence(ScreenHandler handler) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        if (!(handler instanceof GenericContainerScreenHandler)) {
            return false;
        }

        String title = screen.getTitle().getString().toLowerCase();

        // Check for either "deliver items" or "confirm delivery" menus
        boolean isDeliverItems = title.contains("deliver") && title.contains("items");
        boolean isConfirmDelivery = title.contains("confirm") && title.contains("delivery");

        return isDeliverItems || isConfirmDelivery;
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

    private boolean isSpawnerMenu(ScreenHandler handler) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        if (!(handler instanceof GenericContainerScreenHandler)) {
            return false;
        }

        String title = screen.getTitle().getString();
        info("Checking spawner menu - Title: '" + title + "'");

        // Very lenient check - just look for key letter sequences
        String lower = title.toLowerCase();

        // Check for "skelet" or the small caps version
        boolean hasSkeleton = lower.contains("skelet") || title.contains("ᴋᴇʟᴇᴛ");

        // Check for "spawn" or "pawner" or the small caps version
        boolean hasSpawner = lower.contains("spawn") || lower.contains("pawner") ||
            title.contains("ᴘᴀᴡɴᴇʀ") || title.contains("ᴘᴀᴡɴ");

        info("Has skeleton: " + hasSkeleton + ", Has spawner: " + hasSpawner);

        return hasSkeleton && hasSpawner;
    }

    private boolean isOrdersMenu(ScreenHandler handler) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        if (!(handler instanceof GenericContainerScreenHandler)) {
            return false;
        }

        return SMPUtils.isOrdersMenu(screen);
    }

    private boolean isDepositMenu(ScreenHandler handler) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        if (!(handler instanceof GenericContainerScreenHandler)) {
            return false;
        }

        String title = screen.getTitle().getString();
        info("Checking deposit menu - Title: '" + title + "'"); // DEBUG

        String lowerTitle = title.toLowerCase();
        boolean result = lowerTitle.contains("deliver") && lowerTitle.contains("items");
        info("Deposit menu check result: " + result); // DEBUG

        return result;
    }

    private boolean isConfirmMenu(ScreenHandler handler) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        if (!(handler instanceof GenericContainerScreenHandler)) {
            return false;
        }

        String title = screen.getTitle().getString().toLowerCase();

        // Check for key words that identify the confirm menu
        return title.contains("confirm") && title.contains("delivery");
    }

    private boolean isConfirmSellMenu(ScreenHandler handler) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        if (!(handler instanceof GenericContainerScreenHandler)) {
            return false;
        }

        String title = screen.getTitle().getString();
        info("Checking confirm sell menu - Title: '" + title + "'");

        // Use the EXACT characters from your log: ᴄᴏɴꜰɪʀᴍ ѕᴇʟʟ
        boolean hasConfirm = title.contains("ᴄᴏɴꜰɪʀᴍ") ||
            title.toUpperCase().contains("CONFIRM") ||
            title.toLowerCase().contains("confirm");

        boolean hasSell = title.contains("ѕᴇʟʟ") ||
            title.toUpperCase().contains("SELL") ||
            title.toLowerCase().contains("sell");

        info("Has confirm: " + hasConfirm + ", Has sell: " + hasSell);

        return hasConfirm && hasSell;
    }

    private void handleState(ScreenHandler handler) {
        switch (currentState) {
            case IDLE -> currentState = State.FINDING_SPAWNER;
            case FINDING_SPAWNER -> handleFindingSpawner();
            case OPENING_SPAWNER -> handleOpeningSpawner();
            case WAITING_SPAWNER_MENU -> handleWaitingSpawnerMenu(handler);
            case CHECKING_SPAWNER_CONTENTS -> handleCheckingSpawnerContents(handler);
            case PAUSED_NOT_ENOUGH_BONES -> handlePausedNotEnoughBones();
            case WAITING_CONFIRM_SELL_MENU -> handleWaitingConfirmSellMenu(handler);
            case CLICK_CONFIRM_SELL -> handleClickConfirmSell(handler);
            case CLICK_CONFIRM_SELL_SECOND -> handleClickConfirmSellSecond(handler);
            case WAIT_AFTER_SELL_CONFIRM -> handleWaitAfterSellConfirm();
            case CLICK_SLOT_50 -> handleClickSlot50(handler);
            case CLOSING_SPAWNER_MENU -> handleClosingSpawnerMenu();
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
            case PROTECT_GOING_TO_SPAWNERS -> handleProtectGoingToSpawners();
            case PROTECT_GOING_TO_CHEST -> handleProtectGoingToChest();
            case PROTECT_OPENING_CHEST -> handleProtectOpeningChest();
            case PROTECT_DEPOSITING_ITEMS -> handleProtectDepositingItems();
            case PROTECT_DISCONNECTING -> handleProtectDisconnecting();
            case PROTECT_SELLING_BONES -> handleProtectSellingBones();
            case PROTECT_WAITING_SELL_MENU -> handleProtectWaitingSellMenu(handler);
            case PROTECT_DEPOSITING_TO_SELL -> handleProtectDepositingToSell(handler);
            case PROTECT_CLOSING_SELL_MENU -> handleProtectClosingSellMenu();
        }
    }

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
                info("Right-clicking spawner at " + targetSpawner);
                SMPUtils.interactWithBlock(targetSpawner);
            });
        } else if (!smoothAim.get()) {
            SMPUtils.lookAtBlock(targetSpawner);
            info("Right-clicking spawner at " + targetSpawner);
            SMPUtils.interactWithBlock(targetSpawner);
        } else {
            return;
        }

        currentState = State.WAITING_SPAWNER_MENU;
        delayCounter = actionDelay.get() * 2;
        spawnerTimeoutCounter = 0;
    }

    private void handleWaitingSpawnerMenu(ScreenHandler handler) {
        spawnerTimeoutCounter++;

        if (isSpawnerMenu(handler)) {
            info("Spawner menu opened (Skeleton Spawner)");
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
                warning("Not enough bones in spawner! Clicking gold ingot to sell...");

                // Find and click gold ingot
                for (int j = 0; j < container.slots.size(); j++) {
                    ItemStack slotStack = container.getSlot(j).getStack();
                    if (slotStack.getItem() == Items.GOLD_INGOT) {
                        info("Clicking gold ingot in slot " + j);
                        mc.interactionManager.clickSlot(handler.syncId, j, 0, SlotActionType.PICKUP, mc.player);
                        currentState = State.WAITING_CONFIRM_SELL_MENU;
                        delayCounter = actionDelay.get() * 2;
                        return;
                    }
                }

                warning("Gold ingot not found! Pausing sequence...");
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
        currentState = State.CLICK_SLOT_50;
        delayCounter = actionDelay.get();
    }

    private void handleWaitingConfirmSellMenu(ScreenHandler handler) {
        if (isConfirmSellMenu(handler)) {
            info("Confirm sell menu detected");
            currentState = State.CLICK_CONFIRM_SELL;
            delayCounter = actionDelay.get();
        }
    }

    private void handleClickConfirmSell(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        // Check if we're still in the correct screen
        if (!(mc.currentScreen instanceof GenericContainerScreen)) {
            warning("Screen closed before clicking!");
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        // Find and click lime glass pane (first click)
        for (int i = 0; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (stack.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                info("Clicking lime glass pane to confirm sell (1st click) in slot " + i);
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);

                currentState = State.CLICK_CONFIRM_SELL_SECOND;
                delayCounter = 5; // Increase delay to 5 ticks
                return;
            }
        }

        warning("Lime glass pane not found in confirm sell menu!");
        currentState = State.RETRY_SEQUENCE;
    }

    private void handleClickConfirmSellSecond(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            warning("Not in container screen for second click!");
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        // Check if we're still in the correct screen
        if (!(mc.currentScreen instanceof GenericContainerScreen)) {
            warning("Screen closed before second click!");
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        // Find and click lime glass pane (second click)
        for (int i = 0; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (stack.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                info("Clicking lime glass pane to confirm sell (2nd click) in slot " + i);
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);

                // Wait a bit for the clicks to register, then go to pause state
                currentState = State.WAIT_AFTER_SELL_CONFIRM;
                delayCounter = 10; // Just wait for clicks to register
                return;
            }
        }

        warning("Lime glass pane not found in confirm sell menu (second click)!");
        currentState = State.RETRY_SEQUENCE;
    }

    private void handleWaitAfterSellConfirm() {
        info("Clicks registered, now applying pause settings...");

        // Close any open screens
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }

        currentState = State.PAUSED_NOT_ENOUGH_BONES;

        // Now apply the actual pause sequence delay
        if (pauseSequence.get()) {
            int minDelay = pauseMinimumDelay.get();
            int maxDelay = pauseMaximumDelay.get();
            int randomDelay = minDelay + (int)(Math.random() * (maxDelay - minDelay + 1));
            delayCounter = randomDelay;

            // Format the pause message with colors
            logPauseMessage(randomDelay, minDelay, maxDelay);
        } else {
            delayCounter = 200;
            info("Pause sequence disabled, using default 200 tick delay");
        }
    }

    private void logPauseMessage(int ticks, int minTicks, int maxTicks) {
        // Convert ticks to minutes (20 ticks = 1 second, 1200 ticks = 1 minute)
        double minutes = ticks / 1200.0;
        String formattedMinutes = String.format("%.2f", minutes).replace('.', ',');

        // Build colored message using Minecraft's formatting codes
        String message = "§6Pausing for §d" + formattedMinutes + " minutes §8(between §7"
            + minTicks + " §8and §7" + maxTicks + " ticks§8)";

        // Always show pause messages, even in HideAll mode
        ChatUtils.info(message);
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
        currentState = State.CLOSING_SPAWNER_MENU; // CHANGED
        delayCounter = actionDelay.get();
    }

    private void handleClosingSpawnerMenu() {
        info("Closing spawner menu before sending command");
        mc.player.closeHandledScreen();
        currentState = State.SENDING_ORDER_COMMAND;
        delayCounter = actionDelay.get() * 2; // Give it time to close
    }

    private void handleSendingOrderCommand() {
        info("=== SENDING ORDER COMMAND ===");
        info("Sending /order bones command");
        mc.getNetworkHandler().sendChatCommand("order bones");
        currentState = State.WAITING_ORDER_MENU;
        delayCounter = actionDelay.get() * 2;
        info("Now waiting for order menu... delay: " + delayCounter);
    }

    private void handleWaitingOrderMenu(ScreenHandler handler) {
        info("=== WAITING FOR ORDER MENU ===");
        info("Current screen: " + (mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "null"));

        if (isOrdersMenu(handler)) {
            info("Order menu detected (Orders - Page 1)");
            currentState = State.CLICK_BONE_SLOT;
            delayCounter = actionDelay.get();
        } else {
            info("Not order menu yet...");
        }
    }

    private void handleClickBoneSlot(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        // Priority slots 4-12, but select randomly
        int[] prioritySlots = {4, 5, 6, 7, 8, 9, 10, 11, 12};
        List<Integer> validPrioritySlots = new ArrayList<>();

        // Find all valid priority slots with items
        for (int slot : prioritySlots) {
            if (slot < container.getRows() * 9) {
                ItemStack stack = container.getSlot(slot).getStack();
                if (!stack.isEmpty()) {
                    validPrioritySlots.add(slot);
                }
            }
        }

        int slotToClick = -1;

        // If we have valid priority slots, pick one randomly
        if (!validPrioritySlots.isEmpty()) {
            int randomIndex = (int)(Math.random() * validPrioritySlots.size());
            slotToClick = validPrioritySlots.get(randomIndex);
        } else {
            // Fallback: search all slots
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
        if (isDepositMenu(handler)) {
            info("Deposit menu detected (Orders -> Deliver Items)");
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

        // Transfer all bones at once using a single pass
        info("Depositing all bones to chest at once...");

        for (int i = containerSlots; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
        }

        info("All bones deposited!");
        currentState = State.CLOSING_DEPOSIT_MENU;
        delayCounter = actionDelay.get();
    }

    private void handleClosingDepositMenu() {
        info("Closing deposit menu (Orders -> Deliver Items)");
        mc.player.closeHandledScreen();
        currentState = State.WAITING_CONFIRM_MENU;
        delayCounter = actionDelay.get() * 2;
    }

    private void handleWaitingConfirmMenu(ScreenHandler handler) {
        if (isConfirmMenu(handler)) {
            info("Confirm menu detected (Orders -> Confirm Delivery)");
            currentState = State.CLICK_CONFIRM_SLOT;
            delayCounter = actionDelay.get();
        }
    }

    private void handleClickConfirmSlot(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            currentState = State.RETRY_SEQUENCE;
            return;
        }

        // Find and click lime glass pane
        for (int i = 0; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (stack.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                info("Clicking lime glass pane to confirm in slot " + i);
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);

                chatMessageReceived = false;
                waitingForChatMessage = true;
                waitingForProfitMessage = false;
                currentState = State.WAITING_FOR_CHAT_CONFIRMATION;
                delayCounter = 5;
                timeoutCounter = 0;
                return;
            }
        }

        warning("Lime glass pane not found in confirm menu!");
        currentState = State.RETRY_SEQUENCE;
    }

    private void handleWaitingForChatConfirmation() {
        timeoutCounter++;

        if (chatMessageReceived) {
            info("Order successfully delivered!");
            waitingForChatMessage = false;

            // If we were selling during protect sequence, go back to protect flow
            if (protectSelling) {
                info("Bones sold during protect sequence - closing inventories and returning to protection");
                currentState = State.CLOSING_FIRST_INVENTORY;
                delayCounter = 0;
            } else {
                info("Closing inventories...");
                currentState = State.CLOSING_FIRST_INVENTORY;
                delayCounter = 0;
            }
            return;
        }

        if (orderExpiredCheck.get() && timeoutCounter >= orderExpiredTimeout.get()) {
            warning("Order expired - no chat confirmation received");
            waitingForChatMessage = false;
            waitingForProfitMessage = false;
            mc.player.closeHandledScreen();

            // If in protect selling mode, retry or continue protect sequence
            if (protectSelling) {
                info("Order expired during protect sequence - checking bones and continuing");
                protectSelling = false;
                currentState = State.PROTECT_CHECK_LIME_GLASS;
                delayCounter = 0;
            } else {
                currentState = SMPUtils.playerHasBones() ? State.SENDING_ORDER_COMMAND : State.FINDING_SPAWNER;
                delayCounter = actionDelay.get() * 2;
                info(SMPUtils.playerHasBones() ? "Player still has bones, retrying" : "No bones, restarting");
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

        // If we were selling during protect sequence, go back to protect flow
        if (protectSelling) {
            info("Bones sold during protect sequence - returning to protection");
            protectSelling = false;
            currentState = State.PROTECT_CHECK_LIME_GLASS;
            delayCounter = 0;
        } else {
            currentState = State.LOOP_DELAY;
            delayCounter = loopDelay.get();
        }
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
        info("Detection wait complete, checking current state...");

        // Check if we're in a menu
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            // Check if we're in the delivery sequence (Deliver Items or Confirm Delivery)
            if (isInDeliverySequence(handler)) {
                if (isConfirmMenu(handler)) {
                    info("Confirm Delivery menu detected - clicking lime glass pane to complete order");

                    // Find and click lime glass pane
                    for (int i = 0; i < handler.slots.size(); i++) {
                        ItemStack stack = handler.getSlot(i).getStack();
                        if (stack.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                            info("Clicking lime glass pane in slot " + i + " to confirm delivery");
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);

                            // Set flag to continue protect sequence after selling
                            protectSelling = true;
                            chatMessageReceived = false;
                            waitingForChatMessage = true;
                            waitingForProfitMessage = false;
                            currentState = State.WAITING_FOR_CHAT_CONFIRMATION;
                            delayCounter = 5;
                            timeoutCounter = 0;
                            return;
                        }
                    }

                    warning("Lime glass pane not found in confirm menu during protection!");
                } else if (isDepositMenu(handler)) {
                    info("Deliver Items menu detected - depositing all bones to complete order");

                    // Deposit all bones
                    int containerSlots = handler.getRows() * 9;
                    for (int i = containerSlots; i < handler.slots.size(); i++) {
                        ItemStack stack = handler.getSlot(i).getStack();
                        if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                        }
                    }

                    info("Bones deposited, waiting for confirm menu");
                    protectSelling = true;
                    currentState = State.CLOSING_DEPOSIT_MENU;
                    delayCounter = actionDelay.get();
                    return;
                }
            }

            // Close any other open screen
            mc.player.closeHandledScreen();
            info("Screens closed, checking for lime glass...");
            currentState = State.PROTECT_CHECK_LIME_GLASS;
            delayCounter = 0;
        } else {
            // No menu open - check bone count and decide next action
            int boneCount = 0;
            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack.getItem() == Items.BONE) {
                    boneCount += stack.getCount();
                }
            }

            info("No menu open. Bone count: " + boneCount);

            if (boneCount > maxBoneCount.get()) {
                info("Too many bones - need to sell them first via /sell");
                protectSelling = true;
                currentState = State.PROTECT_SELLING_BONES;
                delayCounter = actionDelay.get() * 2;
            } else {
                info("Bone count acceptable - proceeding to spawner mining");
                currentState = State.PROTECT_CHECK_LIME_GLASS;
                delayCounter = 0;
            }
        }
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
        // Count bones in inventory
        int boneCount = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.BONE) {
                boneCount += stack.getCount();
            }
        }

        info("Total bones in inventory: " + boneCount);

        // Check against configured max bone count
        if (boneCount > maxBoneCount.get()) {
            info("Too many bones (" + boneCount + ") - selling via /sell before mining spawners...");
            currentState = State.PROTECT_SELLING_BONES;
            delayCounter = actionDelay.get() * 2;
            return;
        }

        // Proceed directly to spawner mining
        info("Inventory has space (" + boneCount + " bones). Proceeding to spawner mining...");
        SMPUtils.setSneaking(true, sneakingState);
        currentState = State.PROTECT_GOING_TO_SPAWNERS;
        delayCounter = 0;
    }

    private void handleProtectSellingBones() {
        info("Opening /sell menu to sell bones");
        mc.getNetworkHandler().sendChatCommand("sell");
        currentState = State.PROTECT_WAITING_SELL_MENU;
        delayCounter = actionDelay.get() * 2;
    }

    private void handleProtectWaitingSellMenu(ScreenHandler handler) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            info("Waiting for sell menu to open...");
            return;
        }

        if (!(handler instanceof GenericContainerScreenHandler)) {
            info("Not a container screen yet...");
            return;
        }

        // Use the utility method to detect the sell menu
        if (SMPUtils.isSellMenu(screen)) {
            info("Sell menu detected! Title: " + screen.getTitle().getString());
            currentState = State.PROTECT_DEPOSITING_TO_SELL;
            delayCounter = actionDelay.get();
        } else {
            // Debug: show what menu we're seeing
            String title = screen.getTitle().getString();
            if (protectTickCounter % 10 == 0) {
                info("Waiting for sell menu... Current title: '" + title + "'");
            }
        }
    }

    private void handleProtectDepositingToSell(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            warning("Not in sell menu container!");
            currentState = State.PROTECT_SELLING_BONES;
            delayCounter = actionDelay.get() * 2;
            return;
        }

        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            warning("Screen closed unexpectedly!");
            currentState = State.PROTECT_SELLING_BONES;
            delayCounter = actionDelay.get() * 2;
            return;
        }

        int containerSlots = container.getRows() * 9;
        int boneCount = 0;

        info("=== DEPOSITING TO SELL MENU ===");
        info("Container rows: " + container.getRows());
        info("Total slots: " + container.slots.size());
        info("Player inventory starts at slot: " + containerSlots);

        // Count bones first
        for (int i = containerSlots; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                boneCount += stack.getCount();
            }
        }

        info("Total bones to deposit: " + boneCount);

        if (boneCount == 0) {
            warning("No bones found in inventory!");
            currentState = State.PROTECT_CLOSING_SELL_MENU;
            delayCounter = actionDelay.get();
            return;
        }

        info("Depositing all bones to /sell menu at once...");

        // Transfer all bones at once using QUICK_MOVE
        for (int i = containerSlots; i < container.slots.size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.BONE) {
                info("Moving bones from slot " + i + " (count: " + stack.getCount() + ")");
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
        }

        info("All bones deposited to /sell menu!");
        currentState = State.PROTECT_CLOSING_SELL_MENU;
        delayCounter = actionDelay.get() * 2; // Give it more time to register
    }

    private void handleProtectClosingSellMenu() {
        info("Closing /sell menu (bones will be sold automatically)");
        mc.player.closeHandledScreen();

        // Continue with spawner mining
        info("Proceeding to spawner mining after selling bones");
        SMPUtils.setSneaking(true, sneakingState);
        currentState = State.PROTECT_GOING_TO_SPAWNERS;
        delayCounter = actionDelay.get();
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
            if (smoothAim.get() && !SmoothAimUtils.isRotating()) {
                SmoothAimUtils.startSmoothRotation(currentProtectTarget, rotationSpeed.get(), () -> {
                    SMPUtils.breakBlock(currentProtectTarget);
                });
            } else if (!smoothAim.get()) {
                SMPUtils.lookAtBlock(currentProtectTarget);
                SMPUtils.breakBlock(currentProtectTarget);
            }

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

    // Helper methods to respect silent mode
    private void info(String message) {
        // Always show pause messages regardless of silent mode
        if (message.contains("Pause complete, restarting sequence from spawner")) {
            super.info(message);
            return;
        }

        if (silentMode.get() == SilentMode.HideAll) return;

        if (silentMode.get() == SilentMode.HideDebug) {

            if (message.contains("=== SCREEN DEBUG ===") ||
                message.contains("Screen Title:") ||
                message.contains("Handler Type:") ||
                message.contains("Container Rows:") ||
                message.contains("Total Slots:") ||
                message.contains("===") ||
                message.contains("Checking") ||
                message.contains("Has skeleton:") ||
                message.contains("Has spawner:") ||
                message.contains("Has confirm:") ||
                message.contains("Has sell:") ||
                message.contains("Current State:") ||
                message.contains("Clicking") ||
                message.contains("DEBUG")) {
                return;
            }
        }

        super.info(message);
    }

    private void warning(String message) {
        if (silentMode.get() == SilentMode.HideAll) return;
        super.warning(message);
    }

    private void error(String message) {
        if (silentMode.get() == SilentMode.HideAll) return;
        super.error(message);
    }
}
