package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.hypixel.HypixelUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


public class DragonAssistant extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWarping = settings.createGroup("Warping");
    private final SettingGroup sgTeleportingOptions = settings.createGroup("Teleporting Options");
    private final SettingGroup sgSummoningEyes = settings.createGroup("Summoning Eyes");
    private final SettingGroup sgDragonFighting = settings.createGroup("Dragon Fighting");

    // General Settings
    private final Setting<String> partyLeader = sgGeneral.add(new StringSetting.Builder()
        .name("party-leader").description("Party leader name to wait for").defaultValue("").build());

    private final Setting<Integer> portalFrameRange = sgGeneral.add(new IntSetting.Builder()
        .name("end-portal-frame-range").description("Range to place eyes (blocks)")
        .defaultValue(3).min(0).max(4).sliderMax(4).build());

    private final Setting<Boolean> smoothAim = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-aim").description("Smooth aiming toggle").defaultValue(false).build());

    private final Setting<Integer> rotationSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("rotation-speed").description("Rotation speed for smooth aim")
        .defaultValue(0).min(0).max(600).sliderMax(600).visible(() -> smoothAim.get()).build());

    // Warping Settings
    private final Setting<WarpLocation> warpLocation = sgWarping.add(new EnumSetting.Builder<WarpLocation>()
        .name("warping-to").description("Warp destination").defaultValue(WarpLocation.TheEnd).build());

    private final List<TeleportLocationData> teleportLocations = new ArrayList<>();
    private int currentLocationIndex = 0;

    // Teleporting Options
    private final Setting<Integer> teleportingDelay = sgTeleportingOptions.add(new IntSetting.Builder()
        .name("teleporting-delay").description("Delay between teleports (ticks)")
        .defaultValue(20).min(1).max(1200).sliderMax(1200).build());

    // Summoning Eyes Settings
    private final Setting<InventoryOption> inventoryOption = sgSummoningEyes.add(new EnumSetting.Builder<InventoryOption>()
        .name("get-summoning-eyes-from").description("Eye inventory location")
        .defaultValue(InventoryOption.Inventory).build());

    private final Setting<EyesToPlace> eyesToPlace = sgSummoningEyes.add(new EnumSetting.Builder<EyesToPlace>()
        .name("eyes-to-place").description("Number of eyes to place").defaultValue(EyesToPlace.Four).build());

    private final Setting<Integer> placingDelay = sgSummoningEyes.add(new IntSetting.Builder()
        .name("placing-delay").description("Delay between placing eyes (ticks)")
        .defaultValue(20).min(0).max(600).sliderMax(600)
        .visible(() -> eyesToPlace.get() != EyesToPlace.None).build());

    private final Setting<Boolean> throwRemnants = sgSummoningEyes.add(new BoolSetting.Builder()
        .name("throw-remnants-of-the-eye").description("Throw remnants after placing")
        .defaultValue(false).build());

    private final Setting<Integer> actionDelay = sgSummoningEyes.add(new IntSetting.Builder()
        .name("action-delay").description("Delay for throwing remnants (ticks)")
        .defaultValue(10).min(0).max(600).sliderMax(600).visible(() -> throwRemnants.get()).build());

    // Dragon Fighting Settings
    private final Setting<TeleportLocation> teleportTo = sgDragonFighting.add(new EnumSetting.Builder<TeleportLocation>()
        .name("teleport-to").description("Shooting position").defaultValue(TeleportLocation.MiddleLeft).build());

    private final Setting<Integer> dragonSpawningDelay = sgDragonFighting.add(new IntSetting.Builder()
        .name("dragon-spawning-delay").description("Dragon spawn delay (ticks)")
        .defaultValue(100).min(0).max(600).sliderMax(600).build());

    private final Setting<Integer> dragonShootingDelay = sgDragonFighting.add(new IntSetting.Builder()
        .name("dragon-shooting-delay").description("Shooting duration (ticks)")
        .defaultValue(60).min(0).max(600).sliderMax(600).build());

    private final Setting<Boolean> chimeraSwap = sgDragonFighting.add(new BoolSetting.Builder()
        .name("chimera-swap").description("Swap to Chimera weapon").defaultValue(false).build());

    private final Setting<Integer> weaponSlot = sgDragonFighting.add(new IntSetting.Builder()
        .name("weapon-slot").description("Weapon hotbar slot (1-8)")
        .defaultValue(1).min(1).max(8).sliderMax(8).visible(() -> chimeraSwap.get()).build());

    private final Setting<Integer> dragonDeathDelay = sgDragonFighting.add(new IntSetting.Builder()
        .name("dragon-death-delay").description("Timeout before returning (ticks)")
        .defaultValue(600).min(0).max(2400).sliderMax(2400).build());

    // State variables
    private SequenceState state = SequenceState.IDLE;
    private int tickCounter = 0;
    private int eyesPlaced = 0;
    private int remnantsThrown = 0;
    private boolean isUsingBow = false;
    private boolean isHoldingChimera = false;
    private boolean isSneaking = false;
    private int dragonDeathTimer = 0;
    private int eyesToMove = 0;

    // Rotation tracking
    private boolean shouldMaintainRotation = false;
    private float persistentYaw = 0;
    private float persistentPitch = 0;

    // Smooth aim
    private float startYaw = 0;
    private float startPitch = 0;
    private float targetYaw = 0;
    private float targetPitch = 0;
    private int aimingTicks = 0;
    private int totalAimTicks = 0;

    public DragonAssistant() {
        super(Main.HYPIXEL_SKYBLOCK, "dragon-assistant",
            "Automated dragon fight assistant for Hypixel Skyblock.");
        loadTeleportLocationsFromFile();
    }

    // CONTINUES IN PART 2...
    // CONTINUED FROM PART 1...

    @Override
    public WWidget getWidget(GuiTheme theme) {
        if (settings.getGroup("Warping") == sgWarping) {
            WTable table = theme.table();
            WButton addLocationBtn = table.add(theme.button("+ Add Teleporting Location")).expandX().widget();
            addLocationBtn.action = () -> mc.setScreen(new LocationManagerScreen(theme, mc.currentScreen, this));
            return table;
        }
        return null;
    }

    // Location management
    public List<TeleportLocationData> getTeleportLocations() { return teleportLocations; }

    public void addTeleportLocation(String name, int x, int y, int z) {
        teleportLocations.add(new TeleportLocationData(name, x, y, z));
        saveTeleportLocationsToFile();
    }

    public void removeTeleportLocation(int index) {
        if (index >= 0 && index < teleportLocations.size()) {
            teleportLocations.remove(index);
            saveTeleportLocationsToFile();
        }
    }

    public void clearTeleportLocations() {
        teleportLocations.clear();
        saveTeleportLocationsToFile();
    }

    private Path getConfigFilePath() {
        return Paths.get(mc.runDirectory.getPath(), "config", "dragon-assistant-locations.txt");
    }

    private void saveTeleportLocationsToFile() {
        try {
            Path configPath = getConfigFilePath();
            Files.createDirectories(configPath.getParent());
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(configPath.toFile()))) {
                for (TeleportLocationData loc : teleportLocations) {
                    writer.write(loc.name + "," + loc.x + "," + loc.y + "," + loc.z);
                    writer.newLine();
                }
            }
            info("Saved " + teleportLocations.size() + " teleport locations.");
        } catch (IOException e) {
            error("Failed to save locations: " + e.getMessage());
        }
    }

    private void loadTeleportLocationsFromFile() {
        try {
            Path configPath = getConfigFilePath();
            if (!Files.exists(configPath)) return;

            teleportLocations.clear();
            try (BufferedReader reader = new BufferedReader(new FileReader(configPath.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length == 4) {
                        teleportLocations.add(new TeleportLocationData(
                            parts[0], Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                    }
                }
            }
            if (!teleportLocations.isEmpty()) {
                info("Loaded " + teleportLocations.size() + " teleport locations.");
            }
        } catch (IOException | NumberFormatException e) {
            error("Failed to load locations: " + e.getMessage());
        }
    }

    @Override
    public void onActivate() {
        if (partyLeader.get().isEmpty()) {
            info("Dragon Assistant activated!");
            startSequence();
        } else {
            info("Waiting for " + partyLeader.get() + " to place eye...");
            state = SequenceState.WAITING_FOR_LEADER;
        }
    }

    @Override
    public void onDeactivate() {
        info("Dragon Assistant deactivated!");
        resetState();
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String message = event.getMessage().getString();

        if (state == SequenceState.WAITING_FOR_LEADER && !partyLeader.get().isEmpty()) {
            if (message.contains("☬ " + partyLeader.get() + " placed a Summoning Eye!")) {
                info("Party leader placed eye! Starting...");
                startSequence();
            }
        }

        if (state == SequenceState.WAITING_FOR_DRAGON_DOWN || state == SequenceState.HOLDING_CHIMERA) {
            if (message.contains("DRAGON DOWN!")) {
                info("Dragon defeated! Restarting...");
                resetState();
                if (partyLeader.get().isEmpty()) startSequence();
                else state = SequenceState.WAITING_FOR_LEADER;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (shouldMaintainRotation) {
            Rotations.rotate(persistentYaw, persistentPitch);
        }

        switch (state) {
            case WARPING_TO_END: handleWarpingToEnd(); break;
            case VERIFYING_LOCATION: handleVerifyingLocation(); break;
            case TELEPORTING_SEQUENCE: handleTeleportingSequence(); break;
            case OPENING_INVENTORY: handleOpeningInventory(); break;
            case CHECKING_INVENTORY: handleCheckingInventory(); break;
            case MOVING_EYES_TO_HOTBAR: handleMovingEyesToHotbar(); break;
            case GETTING_EYES_STASH: handleGettingEyesStash(); break;
            case GETTING_EYES_BZ_INSTA: handleGettingEyesBZInsta(); break;
            case GETTING_EYES_BZ_ORDER: handleGettingEyesBZOrder(); break;
            case PLACING_EYES: handlePlacingEyes(); break;
            case AIMING_AT_PORTAL: handleAimingAtPortal(); break;
            case PATHING_TO_SHOOTING_POS: handlePathingToShootingPos(); break;
            case AIMING: handleAiming(); break;
            case WAITING_FOR_DRAGON: handleWaitingForDragon(); break;
            case SHOOTING_DRAGON: handleShootingDragon(); break;
            case SWAPPING_TO_CHIMERA: handleSwappingToChimera(); break;
            case HOLDING_CHIMERA: handleHoldingChimera(); break;
            case THROWING_REMNANTS: handleThrowingRemnants(); break;
            case WAITING_FOR_DRAGON_DOWN: handleWaitingForDragonDown(); break;
        }
    }

    private void startSequence() {
        resetState();
        setSneaking(true);

        if (warpLocation.get() == WarpLocation.TheEnd) {
            state = SequenceState.WARPING_TO_END;
            info("Starting with warp to The End...");
        } else {
            state = SequenceState.OPENING_INVENTORY;
            info("Starting dragon sequence...");
        }
    }

    private void resetState() {
        state = SequenceState.IDLE;
        tickCounter = eyesPlaced = remnantsThrown = dragonDeathTimer = eyesToMove = currentLocationIndex = 0;
        isUsingBow = isHoldingChimera = false;
        shouldMaintainRotation = false;
        persistentYaw = persistentPitch = startYaw = startPitch = targetYaw = targetPitch = 0;
        aimingTicks = totalAimTicks = 0;

        setSneaking(false);
        if (mc.options != null && mc.options.useKey != null) mc.options.useKey.setPressed(false);
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
    }

    private void setSneaking(boolean sneak) {
        if (mc.player == null) return;
        if (sneak && !isSneaking) {
            mc.player.setSneaking(true);
            isSneaking = true;
        } else if (!sneak && isSneaking) {
            mc.player.setSneaking(false);
            isSneaking = false;
        }
    }

    // CONTINUES IN PART 3...
    // CONTINUED FROM PART 2...

    private void handleWarpingToEnd() {
        if (tickCounter == 0) {
            info("Warping to The End...");
            ChatUtils.sendPlayerMsg("/warp end");
            tickCounter++;
        } else if (tickCounter >= 40) {
            state = SequenceState.VERIFYING_LOCATION;
            tickCounter = 0;
        } else tickCounter++;
    }

    private void handleVerifyingLocation() {
        if (!HypixelUtils.isInTheEnd()) {
            error("ERROR: Not in The End!");
            toggle();
            return;
        }

        if (!HypixelUtils.isAtSpecificLocation(-503, 101, -275)) {
            error("ERROR: Wrong location!");
            toggle();
            return;
        }

        info("Location verified! Starting teleportation...");
        currentLocationIndex = 0;
        state = SequenceState.TELEPORTING_SEQUENCE;
        tickCounter = 0;
    }

    private void handleTeleportingSequence() {
        if (teleportLocations.isEmpty()) {
            info("No teleport locations. Skipping...");
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
            return;
        }

        if (currentLocationIndex >= teleportLocations.size()) {
            info("Teleports completed!");
            setSneaking(false);
            mc.options.useKey.setPressed(false);
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
            return;
        }

        TeleportLocationData location = teleportLocations.get(currentLocationIndex);

        if (tickCounter == 0) {
            if (!isSneaking) setSneaking(true);

            if (currentLocationIndex == 0) {
                int aotvSlot = HypixelUtils.findAOTVSlot();
                if (aotvSlot == -1) {
                    error("ERROR: No AOTV in hotbar!");
                    toggle();
                    return;
                }
                InvUtils.swap(aotvSlot, false);
            }

            info("Teleporting to " + location.name);

            BlockPos targetPos = new BlockPos(location.x, location.y, location.z);
            float[] angles = new float[2];
            HypixelUtils.calculateAimAngles(targetPos, angles);

            startYaw = mc.player.getYaw();
            startPitch = mc.player.getPitch();
            targetYaw = angles[0];
            targetPitch = angles[1];

            aimingTicks = 0;
            totalAimTicks = smoothAim.get() ? rotationSpeed.get() : 10;

            shouldMaintainRotation = true;
            persistentYaw = startYaw;
            persistentPitch = startPitch;
            tickCounter++;
        } else if (tickCounter <= totalAimTicks) {
            float progress = (float) aimingTicks / (float) totalAimTicks;
            float currentYaw = HypixelUtils.lerpAngle(startYaw, targetYaw, progress);
            float currentPitch = HypixelUtils.lerp(startPitch, targetPitch, progress);

            persistentYaw = currentYaw;
            persistentPitch = currentPitch;
            Rotations.rotate(currentYaw, currentPitch);

            aimingTicks++;
            tickCounter++;
        } else if (tickCounter == totalAimTicks + 1) {
            persistentYaw = targetYaw;
            persistentPitch = targetPitch;
            Rotations.rotate(targetYaw, targetPitch);

            BlockPos targetPos = new BlockPos(location.x, location.y, location.z);
            info("Teleporting to " + location.name + "!");

            if (mc.interactionManager != null) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false));
            }
            tickCounter++;
        } else if (tickCounter <= totalAimTicks + 8) {
            Rotations.rotate(targetYaw, targetPitch);
            tickCounter++;
        } else if (tickCounter >= totalAimTicks + 9 + teleportingDelay.get()) {
            startYaw = targetYaw;
            startPitch = targetPitch;
            currentLocationIndex++;
            tickCounter = aimingTicks = 0;
        } else {
            Rotations.rotate(targetYaw, targetPitch);
            tickCounter++;
        }
    }

    private void handleOpeningInventory() {
        if (tickCounter == 0) {
            mc.setScreen(new InventoryScreen(mc.player));
            tickCounter++;
        } else if (tickCounter > 5 && mc.currentScreen instanceof InventoryScreen) {
            info("Checking for Summoning Eyes...");
            state = SequenceState.CHECKING_INVENTORY;
            tickCounter = 0;
        } else tickCounter++;
    }

    private void handleCheckingInventory() {
        int total = HypixelUtils.countSummoningEyes();
        int hotbar = HypixelUtils.countSummoningEyesInHotbar();
        int needed = eyesToPlace.get().getCount();

        info("Found " + total + " eyes (" + hotbar + " in hotbar, need " + needed + ")");

        if (total >= needed) {
            if (hotbar >= needed) {
                mc.player.closeHandledScreen();
                info("Sufficient eyes in hotbar!");
                state = SequenceState.PLACING_EYES;
                tickCounter = 0;
            } else {
                eyesToMove = needed - hotbar;
                info("Moving " + eyesToMove + " eyes to hotbar...");
                state = SequenceState.MOVING_EYES_TO_HOTBAR;
                tickCounter = 0;
            }
        } else {
            info("Not enough eyes. Getting more...");
            mc.player.closeHandledScreen();

            switch (inventoryOption.get()) {
                case Inventory:
                    error("ERROR: Not enough Summoning Eyes!");
                    toggle();
                    break;
                case Stash:
                    state = SequenceState.GETTING_EYES_STASH;
                    tickCounter = 0;
                    break;
                case BZInstaBuy:
                    state = SequenceState.GETTING_EYES_BZ_INSTA;
                    tickCounter = 0;
                    break;
                case BZOrder:
                    state = SequenceState.GETTING_EYES_BZ_ORDER;
                    tickCounter = 0;
                    break;
            }
        }
    }

    private void handleMovingEyesToHotbar() {
        if (!(mc.currentScreen instanceof InventoryScreen screen)) {
            error("ERROR: Inventory not open!");
            toggle();
            return;
        }

        if (eyesToMove <= 0) {
            if (HypixelUtils.countSummoningEyesInHotbar() >= eyesToPlace.get().getCount()) {
                mc.player.closeHandledScreen();
                info("Eyes moved to hotbar!");
                state = SequenceState.PLACING_EYES;
                tickCounter = 0;
            } else {
                error("ERROR: Failed to move eyes!");
                toggle();
            }
            return;
        }

        int sourceSlot = HypixelUtils.findSummoningEyeInMainInventory();
        if (sourceSlot == -1) {
            error("ERROR: Cannot find eye!");
            toggle();
            return;
        }

        if (tickCounter % 5 == 0) {
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId,
                sourceSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
            eyesToMove--;
            tickCounter = 0;
        }
        tickCounter++;
    }

    private void handleGettingEyesStash() {
        if (tickCounter == 0) {
            ChatUtils.sendPlayerMsg("/pickupstash");
            tickCounter++;
        } else if (tickCounter > 40) {
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
        } else tickCounter++;
    }

    private void handleGettingEyesBZInsta() {
        if (tickCounter == 0) {
            ChatUtils.sendPlayerMsg("/bz summoning eye");
            tickCounter++;
        } else if (tickCounter == 20 && mc.currentScreen instanceof GenericContainerScreen screen) {
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 11, 0, SlotActionType.PICKUP, mc.player);
            tickCounter++;
        } else if (tickCounter == 40 && mc.currentScreen instanceof GenericContainerScreen screen) {
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 9, 0, SlotActionType.PICKUP, mc.player);
            tickCounter++;
        } else if (tickCounter == 60 && mc.currentScreen instanceof GenericContainerScreen screen) {
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 14, 0, SlotActionType.PICKUP, mc.player);
            mc.player.closeHandledScreen();
            tickCounter++;
        } else if (tickCounter == 80) {
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
        } else tickCounter++;
    }

    private void handleGettingEyesBZOrder() {
        if (tickCounter == 0) {
            ChatUtils.sendPlayerMsg("/bz");
            tickCounter++;
        } else if (tickCounter == 20 && mc.currentScreen instanceof GenericContainerScreen screen) {
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 50, 0, SlotActionType.PICKUP, mc.player);
            tickCounter++;
        } else if (tickCounter == 40 && mc.currentScreen instanceof GenericContainerScreen screen) {
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 19, 0, SlotActionType.PICKUP, mc.player);
            mc.player.closeHandledScreen();
            tickCounter++;
        } else if (tickCounter == 60) {
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
        } else tickCounter++;
    }

    // CONTINUES IN PART 4...
    // CONTINUED FROM PART 3...

    private void handlePlacingEyes() {
        if (eyesPlaced >= eyesToPlace.get().getCount()) {
            info("All eyes placed!");
            shouldMaintainRotation = false;
            state = SequenceState.PATHING_TO_SHOOTING_POS;
            tickCounter = 0;
            return;
        }

        if (tickCounter == 0) {
            int slot = HypixelUtils.findSummoningEyeSlot();
            if (slot == -1) {
                error("ERROR: No eye in hotbar!");
                toggle();
                return;
            }
            InvUtils.swap(slot, false);

            BlockPos framePos = HypixelUtils.findVisibleEndPortalFrame(portalFrameRange.get());
            if (framePos != null) {
                targetYaw = (float) Rotations.getYaw(framePos);
                targetPitch = (float) Rotations.getPitch(framePos);

                if (smoothAim.get() && rotationSpeed.get() > 0) {
                    startYaw = mc.player.getYaw();
                    startPitch = mc.player.getPitch();
                    aimingTicks = 0;
                    totalAimTicks = rotationSpeed.get();

                    shouldMaintainRotation = true;
                    persistentYaw = startYaw;
                    persistentPitch = startPitch;

                    state = SequenceState.AIMING_AT_PORTAL;
                    tickCounter = 0;
                } else {
                    shouldMaintainRotation = true;
                    persistentYaw = targetYaw;
                    persistentPitch = targetPitch;

                    Rotations.rotate(targetYaw, targetPitch, () ->
                        HypixelUtils.placeEyeOnPortalFrame(framePos));
                    eyesPlaced++;
                    info("Placed eye " + eyesPlaced + "/" + eyesToPlace.get().getCount());
                    tickCounter = -placingDelay.get();
                }
            } else {
                error("ERROR: No portal frame found!");
                toggle();
                return;
            }
        }
        tickCounter++;
        if (tickCounter > 0) tickCounter = 0;
    }

    private void handleAimingAtPortal() {
        if (aimingTicks >= totalAimTicks) {
            BlockPos framePos = HypixelUtils.findVisibleEndPortalFrame(portalFrameRange.get());
            if (framePos != null) {
                persistentYaw = targetYaw;
                persistentPitch = targetPitch;

                Rotations.rotate(targetYaw, targetPitch, () ->
                    HypixelUtils.placeEyeOnPortalFrame(framePos));
                eyesPlaced++;
                info("Placed eye " + eyesPlaced + "/" + eyesToPlace.get().getCount());
                state = SequenceState.PLACING_EYES;
                tickCounter = -placingDelay.get();
            } else {
                error("ERROR: Portal frame disappeared!");
                toggle();
            }
            return;
        }

        float progress = (float) aimingTicks / (float) totalAimTicks;
        float currentYaw = HypixelUtils.lerpAngle(startYaw, targetYaw, progress);
        float currentPitch = HypixelUtils.lerp(startPitch, targetPitch, progress);

        persistentYaw = currentYaw;
        persistentPitch = currentPitch;
        Rotations.rotate(currentYaw, currentPitch);
        aimingTicks++;
    }

    private void handlePathingToShootingPos() {
        Vec3d targetPos = new Vec3d(-670, 9, -267);
        if (mc.player.getBlockPos().toCenterPos().distanceTo(targetPos) < 2.0) {
            info("Reached shooting position!");
            state = SequenceState.AIMING;
            tickCounter = 0;
        }
    }

    private void handleAiming() {
        if (smoothAim.get() && rotationSpeed.get() > 0) {
            if (tickCounter == 0) {
                startYaw = mc.player.getYaw();
                startPitch = mc.player.getPitch();
                targetYaw = 179;
                targetPitch = -88;
                aimingTicks = 0;
                totalAimTicks = rotationSpeed.get();

                shouldMaintainRotation = true;
                persistentYaw = startYaw;
                persistentPitch = startPitch;
            }

            if (aimingTicks >= totalAimTicks) {
                persistentYaw = 179;
                persistentPitch = -88;
                Rotations.rotate(179, -88);
                info("Waiting for dragon...");
                state = SequenceState.WAITING_FOR_DRAGON;
                tickCounter = 0;
                return;
            }

            float progress = (float) aimingTicks / (float) totalAimTicks;
            persistentYaw = HypixelUtils.lerpAngle(startYaw, targetYaw, progress);
            persistentPitch = HypixelUtils.lerp(startPitch, targetPitch, progress);
            Rotations.rotate(persistentYaw, persistentPitch);
            aimingTicks++;
        } else {
            shouldMaintainRotation = true;
            persistentYaw = 179;
            persistentPitch = -88;
            Rotations.rotate(179, -88);

            if (tickCounter > 10) {
                info("Waiting for dragon...");
                state = SequenceState.WAITING_FOR_DRAGON;
                tickCounter = 0;
            }
        }
        tickCounter++;
    }

    private void handleWaitingForDragon() {
        int bowSlot = HypixelUtils.findBowSlot();
        if (bowSlot != -1) InvUtils.swap(bowSlot, false);

        if (tickCounter >= dragonSpawningDelay.get()) {
            info("Starting to shoot dragon!");
            state = SequenceState.SHOOTING_DRAGON;
            tickCounter = 0;
        }
        tickCounter++;
    }

    private void handleShootingDragon() {
        if (!isUsingBow) {
            mc.options.useKey.setPressed(true);
            isUsingBow = true;
        }

        if (tickCounter >= dragonShootingDelay.get()) {
            mc.options.useKey.setPressed(false);
            isUsingBow = false;
            info("Finished shooting!");

            if (chimeraSwap.get()) state = SequenceState.SWAPPING_TO_CHIMERA;
            else if (throwRemnants.get()) state = SequenceState.THROWING_REMNANTS;
            else {
                state = SequenceState.WAITING_FOR_DRAGON_DOWN;
                dragonDeathTimer = 0;
            }
            tickCounter = 0;
        }
        tickCounter++;
    }

    private void handleSwappingToChimera() {
        InvUtils.swap(weaponSlot.get() - 1, false);
        info("Swapped to weapon slot " + weaponSlot.get());
        state = SequenceState.HOLDING_CHIMERA;
        isHoldingChimera = true;
    }

    private void handleHoldingChimera() {
        // Keep holding until dragon down
    }

    private void handleThrowingRemnants() {
        if (tickCounter % actionDelay.get() == 0) {
            int remnantSlot = HypixelUtils.findRemnantSlot();
            if (remnantSlot != -1) {
                InvUtils.swap(remnantSlot, false);
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId,
                    remnantSlot, 0, SlotActionType.THROW, mc.player);
                remnantsThrown++;
            } else {
                info("All remnants thrown (" + remnantsThrown + ")");
                state = SequenceState.WAITING_FOR_DRAGON_DOWN;
                dragonDeathTimer = tickCounter = 0;
                return;
            }
        }
        tickCounter++;
    }

    private void handleWaitingForDragonDown() {
        dragonDeathTimer++;
        if (dragonDeathTimer >= dragonDeathDelay.get()) {
            info("Timeout reached. Returning to island...");
            ChatUtils.sendPlayerMsg("/is");
            toggle();
        }
    }

    // Enums
    public enum TeleportLocation {
        MiddleLeft("Middle Left"), MiddleRight("Middle Right"),
        RightTower("Right Tower"), Gate("Gate");
        private final String name;
        TeleportLocation(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    public enum WarpLocation {
        TheEnd("The End"), DragonsNest("Dragon's Nest");
        private final String name;
        WarpLocation(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    public enum EyesToPlace {
        None("None", 0), Two("2", 2), Three("3", 3), Four("4", 4);
        private final String name;
        private final int count;
        EyesToPlace(String name, int count) { this.name = name; this.count = count; }
        @Override public String toString() { return name; }
        public int getCount() { return count; }
    }

    public enum InventoryOption {
        Inventory("Inventory"), Stash("Stash"),
        BZInstaBuy("BZ Insta-Buy"), BZOrder("BZ Order");
        private final String name;
        InventoryOption(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    private enum SequenceState {
        IDLE, WAITING_FOR_LEADER, WARPING_TO_END, VERIFYING_LOCATION,
        TELEPORTING_SEQUENCE, OPENING_INVENTORY, CHECKING_INVENTORY,
        MOVING_EYES_TO_HOTBAR, GETTING_EYES_STASH, GETTING_EYES_BZ_INSTA,
        GETTING_EYES_BZ_ORDER, PLACING_EYES, AIMING_AT_PORTAL,
        PATHING_TO_SHOOTING_POS, AIMING, WAITING_FOR_DRAGON,
        SHOOTING_DRAGON, SWAPPING_TO_CHIMERA, HOLDING_CHIMERA,
        THROWING_REMNANTS, WAITING_FOR_DRAGON_DOWN
    }

    public static class TeleportLocationData {
        public String name;
        public int x, y, z;
        public TeleportLocationData(String name, int x, int y, int z) {
            this.name = name; this.x = x; this.y = y; this.z = z;
        }
    }

    // CONTINUES IN PART 5 (LocationManagerScreen)...
    // CONTINUED FROM PART 4...

    // LocationManagerScreen inner class
    private static class LocationManagerScreen extends net.minecraft.client.gui.screen.Screen {
        private final GuiTheme theme;
        private final net.minecraft.client.gui.screen.Screen parent;
        private final DragonAssistant module;
        private final List<LocationEntry> entries = new ArrayList<>();
        private int nextLocationNumber = 1;

        protected LocationManagerScreen(GuiTheme theme, net.minecraft.client.gui.screen.Screen parent,
                                        DragonAssistant module) {
            super(net.minecraft.text.Text.literal("Location Manager"));
            this.theme = theme;
            this.parent = parent;
            this.module = module;

            for (TeleportLocationData loc : module.getTeleportLocations()) {
                entries.add(new LocationEntry(loc.name, String.valueOf(loc.x),
                    String.valueOf(loc.y), String.valueOf(loc.z)));
                updateNextLocationNumber(loc.name);
            }
        }

        private void updateNextLocationNumber(String name) {
            if (name.startsWith("Location ")) {
                try {
                    int num = Integer.parseInt(name.substring(9));
                    if (num >= nextLocationNumber) nextLocationNumber = num + 1;
                } catch (NumberFormatException e) { }
            }
        }

        @Override
        protected void init() {
            super.init();

            int centerX = this.width / 2;
            int startY = 40;
            int buttonWidth = 80;
            int buttonHeight = 20;
            int spacing = 30;

            addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
                net.minecraft.text.Text.literal("Add New"),
                button -> addNewLocation()
            ).dimensions(centerX - buttonWidth - 5, startY, buttonWidth, buttonHeight).build());

            addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
                net.minecraft.text.Text.literal("Save"),
                button -> saveAndClose()
            ).dimensions(centerX + 5, startY, buttonWidth, buttonHeight).build());

            renderLocationEntries(startY + spacing);
        }

        private void addNewLocation() {
            entries.add(new LocationEntry("Location " + nextLocationNumber, "0", "0", "0"));
            nextLocationNumber++;
            clearAndInit();
        }

        private void renderLocationEntries(int startY) {
            int entryHeight = 30;
            int currentY = startY;

            for (int i = 0; i < entries.size(); i++) {
                final int index = i;
                LocationEntry entry = entries.get(i);

                int labelX = 20;
                int fieldWidth = 60;
                int fieldSpacing = 70;

                addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
                    net.minecraft.text.Text.literal("Set Pos"),
                    button -> setCurrentPosition(entry)
                ).dimensions(labelX, currentY, 60, 20).build());

                net.minecraft.client.gui.widget.TextFieldWidget xField =
                    new net.minecraft.client.gui.widget.TextFieldWidget(
                        this.textRenderer, labelX + 180, currentY, fieldWidth, 20,
                        net.minecraft.text.Text.literal("X"));
                xField.setText(entry.x);
                xField.setChangedListener(text -> entry.x = text);
                addDrawableChild(xField);

                net.minecraft.client.gui.widget.TextFieldWidget yField =
                    new net.minecraft.client.gui.widget.TextFieldWidget(
                        this.textRenderer, labelX + 180 + fieldSpacing, currentY, fieldWidth, 20,
                        net.minecraft.text.Text.literal("Y"));
                yField.setText(entry.y);
                yField.setChangedListener(text -> entry.y = text);
                addDrawableChild(yField);

                net.minecraft.client.gui.widget.TextFieldWidget zField =
                    new net.minecraft.client.gui.widget.TextFieldWidget(
                        this.textRenderer, labelX + 180 + fieldSpacing * 2, currentY, fieldWidth, 20,
                        net.minecraft.text.Text.literal("Z"));
                zField.setText(entry.z);
                zField.setChangedListener(text -> entry.z = text);
                addDrawableChild(zField);

                addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
                    net.minecraft.text.Text.literal("-").copy()
                        .styled(style -> style.withColor(net.minecraft.util.Formatting.RED)),
                    button -> removeLocation(index)
                ).dimensions(labelX + 180 + fieldSpacing * 3, currentY, 20, 20).build());

                currentY += entryHeight;
            }
        }

        private void setCurrentPosition(LocationEntry entry) {
            if (this.client != null && this.client.player != null) {
                BlockPos pos = this.client.player.getBlockPos();
                entry.x = String.valueOf(pos.getX());
                entry.y = String.valueOf(pos.getY() - 1);
                entry.z = String.valueOf(pos.getZ());
                clearAndInit();
            }
        }

        @Override
        public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);

            context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 20, 0xFFFFFF);

            int startY = 70;
            int entryHeight = 30;
            int currentY = startY;
            int labelX = 20;

            for (LocationEntry entry : entries) {
                context.drawTextWithShadow(this.textRenderer, entry.name,
                    labelX + 70, currentY + 5, 0xFFFFFF);
                context.drawTextWithShadow(this.textRenderer, "X:",
                    labelX + 165, currentY + 5, 0xAAAAAA);
                context.drawTextWithShadow(this.textRenderer, "Y:",
                    labelX + 235, currentY + 5, 0xAAAAAA);
                context.drawTextWithShadow(this.textRenderer, "Z:",
                    labelX + 305, currentY + 5, 0xAAAAAA);
                currentY += entryHeight;
            }
        }

        private void removeLocation(int index) {
            if (index >= 0 && index < entries.size()) {
                entries.remove(index);
                clearAndInit();
            }
        }

        private void saveAndClose() {
            module.clearTeleportLocations();
            for (LocationEntry entry : entries) {
                try {
                    int x = Integer.parseInt(entry.x);
                    int y = Integer.parseInt(entry.y);
                    int z = Integer.parseInt(entry.z);
                    module.addTeleportLocation(entry.name, x, y, z);
                } catch (NumberFormatException e) { }
            }
            close();
        }

        @Override
        public void close() {
            if (this.client != null) this.client.setScreen(parent);
        }

        @Override
        public boolean shouldCloseOnEsc() { return true; }

        private static class LocationEntry {
            String name, x, y, z;
            LocationEntry(String name, String x, String y, String z) {
                this.name = name; this.x = x; this.y = y; this.z = z;
            }
        }
    }
}





