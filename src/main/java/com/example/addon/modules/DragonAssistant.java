package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class DragonAssistant extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGetEyes = settings.createGroup("Get Summoning Eyes");
    private final SettingGroup sgFightDelays = settings.createGroup("Fight Delays");

    // General Category
    private final Setting<String> partyLeader = sgGeneral.add(new StringSetting.Builder()
        .name("party-leader")
        .description("Username of the party leader to watch for.")
        .defaultValue("")
        .build()
    );

    private final Setting<Integer> portalFrameRange = sgGeneral.add(new IntSetting.Builder()
        .name("end-portal-frame-range")
        .description("Maximum range to place eyes on portal frames (in blocks).")
        .defaultValue(3)
        .min(0)
        .max(4)
        .sliderMax(4)
        .build()
    );

    private final Setting<Boolean> aotd = sgGeneral.add(new BoolSetting.Builder()
        .name("aotd")
        .description("Use AOTD teleportation (not yet implemented).")
        .defaultValue(false)
        .build()
    );

    private final Setting<EyesToPlace> eyesToPlace = sgGeneral.add(new EnumSetting.Builder<EyesToPlace>()
        .name("eyes-to-place")
        .description("How many Summoning Eyes to place.")
        .defaultValue(EyesToPlace.Four)
        .build()
    );

    private final Setting<Integer> placingDelay = sgGeneral.add(new IntSetting.Builder()
        .name("placing-delay")
        .description("Delay between placing each Summoning Eye (in ticks).")
        .defaultValue(20)
        .min(0)
        .max(600)
        .sliderMax(600)
        .visible(() -> eyesToPlace.get() != EyesToPlace.None)
        .build()
    );

    private final Setting<Boolean> throwRemnants = sgGeneral.add(new BoolSetting.Builder()
        .name("throw-remnants-of-the-eye")
        .description("Throw away Remnants of the Eye after placing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay for throwing Remnants (in ticks).")
        .defaultValue(10)
        .min(0)
        .max(600)
        .sliderMax(600)
        .visible(() -> throwRemnants.get())
        .build()
    );

    // Get Summoning Eyes Category
    private final Setting<InventoryOption> inventoryOption = sgGetEyes.add(new EnumSetting.Builder<InventoryOption>()
        .name("inventory-options")
        .description("Which inventory are the Summoning Eyes in.")
        .defaultValue(InventoryOption.Inventory)
        .build()
    );

    // Fight Delays Category
    private final Setting<Integer> dragonSpawningDelay = sgFightDelays.add(new IntSetting.Builder()
        .name("dragon-spawning-delay")
        .description("The delay for the Dragon to spawn. The bot won't shoot during this delay (in ticks).")
        .defaultValue(100)
        .min(0)
        .max(600)
        .sliderMax(600)
        .build()
    );

    private final Setting<Integer> dragonShootingDelay = sgFightDelays.add(new IntSetting.Builder()
        .name("dragon-shooting-delay")
        .description("Determines the duration at which the bot will be shooting (in ticks).")
        .defaultValue(60)
        .min(0)
        .max(600)
        .sliderMax(600)
        .build()
    );

    private final Setting<Boolean> chimeraSwap = sgFightDelays.add(new BoolSetting.Builder()
        .name("chimera-swap")
        .description("Should the bot swap to a Chimera weapon after shooting the Dragon.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> weaponSlot = sgFightDelays.add(new IntSetting.Builder()
        .name("weapon-slot")
        .description("Which hotbar slot to swap to (1-8).")
        .defaultValue(1)
        .min(1)
        .max(8)
        .sliderMax(8)
        .visible(() -> chimeraSwap.get())
        .build()
    );

    private final Setting<Integer> dragonDeathDelay = sgFightDelays.add(new IntSetting.Builder()
        .name("dragon-death-delay")
        .description("Timeout after which the script will return to island (in ticks).")
        .defaultValue(600)
        .min(0)
        .max(2400)
        .sliderMax(2400)
        .build()
    );

    // State management
    private SequenceState state = SequenceState.IDLE;
    private int tickCounter = 0;
    private int eyesPlaced = 0;
    private int remnantsThrown = 0;
    private boolean isUsingBow = false;
    private boolean isHoldingChimera = false;
    private int dragonDeathTimer = 0;

    public DragonAssistant() {
        super(AddonTemplate.HYPIXEL_SKYBLOCK, "dragon-assistant", "Automated dragon fight assistant for Hypixel Skyblock.");
    }

    @Override
    public void onActivate() {
        if (partyLeader.get().isEmpty()) {
            info("Dragon Assistant activated! No party leader set - starting immediately.");
            startSequence();
        } else {
            info("Dragon Assistant activated! Waiting for " + partyLeader.get() + " to place a Summoning Eye...");
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

        // Check for party leader placing eye
        if (state == SequenceState.WAITING_FOR_LEADER && !partyLeader.get().isEmpty()) {
            if (message.contains("☬ " + partyLeader.get() + " placed a Summoning Eye!")) {
                info("Party leader placed an eye! Starting sequence...");
                startSequence();
            }
        }

        // Check for dragon down
        if (state == SequenceState.WAITING_FOR_DRAGON_DOWN || state == SequenceState.HOLDING_CHIMERA) {
            if (message.contains("DRAGON DOWN!")) {
                info("Dragon defeated! Restarting sequence...");
                resetState();
                if (partyLeader.get().isEmpty()) {
                    startSequence();
                } else {
                    state = SequenceState.WAITING_FOR_LEADER;
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (state) {
            case OPENING_INVENTORY:
                handleOpeningInventory();
                break;
            case CHECKING_INVENTORY:
                handleCheckingInventory();
                break;
            case GETTING_EYES_STASH:
                handleGettingEyesStash();
                break;
            case GETTING_EYES_BZ_INSTA:
                handleGettingEyesBZInsta();
                break;
            case GETTING_EYES_BZ_ORDER:
                handleGettingEyesBZOrder();
                break;
            case PLACING_EYES:
                handlePlacingEyes();
                break;
            case PATHING_TO_SHOOTING_POS:
                handlePathingToShootingPos();
                break;
            case AIMING:
                handleAiming();
                break;
            case WAITING_FOR_DRAGON:
                handleWaitingForDragon();
                break;
            case SHOOTING_DRAGON:
                handleShootingDragon();
                break;
            case SWAPPING_TO_CHIMERA:
                handleSwappingToChimera();
                break;
            case HOLDING_CHIMERA:
                handleHoldingChimera();
                break;
            case THROWING_REMNANTS:
                handleThrowingRemnants();
                break;
            case WAITING_FOR_DRAGON_DOWN:
                handleWaitingForDragonDown();
                break;
        }
    }

    private void startSequence() {
        resetState();
        state = SequenceState.OPENING_INVENTORY;
        info("Starting dragon sequence...");
    }

    private void resetState() {
        state = SequenceState.IDLE;
        tickCounter = 0;
        eyesPlaced = 0;
        remnantsThrown = 0;
        isUsingBow = false;
        isHoldingChimera = false;
        dragonDeathTimer = 0;

        // Close any open screens
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }

    private void handleOpeningInventory() {
        if (tickCounter == 0) {
            // Open inventory like a real player would
            mc.setScreen(new InventoryScreen(mc.player));
            tickCounter++;
        } else if (tickCounter > 5 && mc.currentScreen instanceof InventoryScreen) {
            info("Inventory opened. Checking for Summoning Eyes...");
            state = SequenceState.CHECKING_INVENTORY;
            tickCounter = 0;
        } else {
            tickCounter++;
        }
    }

    private void handleCheckingInventory() {
        int eyesCount = countSummoningEyes();
        int needed = eyesToPlace.get().getCount();

        info("Found " + eyesCount + " Summoning Eye(s) in inventory (need " + needed + ")");

        // Debug: Log all player heads found
        int totalHeads = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.PLAYER_HEAD) {
                totalHeads++;
                info("Found player head in slot " + i + ": " + stack.getName().getString());
            }
        }
        info("Total player heads found: " + totalHeads);

        if (eyesCount >= needed) {
            mc.player.closeHandledScreen();
            info("Sufficient Summoning Eyes found. Proceeding to place them...");
            state = SequenceState.PLACING_EYES;
            tickCounter = 0;
        } else {
            info("Not enough Summoning Eyes (" + eyesCount + "/" + needed + "). Getting more...");
            mc.player.closeHandledScreen();

            switch (inventoryOption.get()) {
                case Inventory:
                    error("ERROR: Not enough Summoning Eyes in inventory!");
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

    private void handleGettingEyesStash() {
        if (tickCounter == 0) {
            ChatUtils.sendPlayerMsg("/pickupstash");
            tickCounter++;
        } else if (tickCounter > 40) { // Wait 2 seconds
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
        } else {
            tickCounter++;
        }
    }

    private void handleGettingEyesBZInsta() {
        if (tickCounter == 0) {
            ChatUtils.sendPlayerMsg("/bz summoning eye");
            tickCounter++;
        } else if (tickCounter == 20 && mc.currentScreen instanceof GenericContainerScreen screen) {
            // Click slot 12 (Insta-buy)
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 11, 0, SlotActionType.PICKUP, mc.player);
            tickCounter++;
        } else if (tickCounter == 40 && mc.currentScreen instanceof GenericContainerScreen screen) {
            // Click slot 10 (Confirm)
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 9, 0, SlotActionType.PICKUP, mc.player);
            tickCounter++;
        } else if (tickCounter == 60 && mc.currentScreen instanceof GenericContainerScreen screen) {
            // Click slot 15 (Confirm purchase)
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 14, 0, SlotActionType.PICKUP, mc.player);
            mc.player.closeHandledScreen();
            tickCounter++;
        } else if (tickCounter == 80) {
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
        } else {
            tickCounter++;
        }
    }

    private void handleGettingEyesBZOrder() {
        if (tickCounter == 0) {
            ChatUtils.sendPlayerMsg("/bz");
            tickCounter++;
        } else if (tickCounter == 20 && mc.currentScreen instanceof GenericContainerScreen screen) {
            // Click slot 51 (Browse orders)
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 50, 0, SlotActionType.PICKUP, mc.player);
            tickCounter++;
        } else if (tickCounter == 40 && mc.currentScreen instanceof GenericContainerScreen screen) {
            // Click slot 20 (Confirm)
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 19, 0, SlotActionType.PICKUP, mc.player);
            mc.player.closeHandledScreen();
            tickCounter++;
        } else if (tickCounter == 60) {
            state = SequenceState.OPENING_INVENTORY;
            tickCounter = 0;
        } else {
            tickCounter++;
        }
    }

    private void handlePlacingEyes() {
        if (eyesPlaced >= eyesToPlace.get().getCount()) {
            info("All eyes placed! Moving to shooting position...");
            state = SequenceState.PATHING_TO_SHOOTING_POS;
            tickCounter = 0;
            return;
        }

        if (tickCounter == 0) {
            // Find and select Summoning Eye in hotbar
            int slot = findSummoningEyeSlot();
            if (slot == -1) {
                error("ERROR: Cannot find Summoning Eye in hotbar!");
                toggle();
                return;
            }
            InvUtils.swap(slot, false);

            // Find nearby end portal frame that is visible and in range
            BlockPos framePos = findVisibleEndPortalFrame();
            if (framePos != null) {
                // Place eye on frame
                Rotations.rotate(Rotations.getYaw(framePos), Rotations.getPitch(framePos), () -> {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(framePos), Direction.UP, framePos, false));
                });
                eyesPlaced++;
                info("Placed eye " + eyesPlaced + "/" + eyesToPlace.get().getCount());
                tickCounter = -placingDelay.get(); // Wait for delay
            } else {
                error("ERROR: No visible end portal frame found in range!");
                toggle();
                return;
            }
        }
        tickCounter++;
        if (tickCounter > 0) tickCounter = 0; // Reset for next placement
    }

    private void handlePathingToShootingPos() {
        Vec3d targetPos = new Vec3d(-670, 9, -267);

        if (mc.player.getBlockPos().toCenterPos().distanceTo(targetPos) < 2.0) {
            info("Reached shooting position. Aiming...");
            state = SequenceState.AIMING;
            tickCounter = 0;
        }
    }

    private void handleAiming() {
        Rotations.rotate(179, -88);
        if (tickCounter > 10) { // Aim for 10 ticks
            info("Aimed. Waiting for dragon to spawn...");
            state = SequenceState.WAITING_FOR_DRAGON;
            tickCounter = 0;
        }
        tickCounter++;
    }

    private void handleWaitingForDragon() {
        // Select bow
        int bowSlot = findBowSlot();
        if (bowSlot != -1) {
            InvUtils.swap(bowSlot, false);
        }

        if (tickCounter >= dragonSpawningDelay.get()) {
            info("Starting to shoot dragon!");
            state = SequenceState.SHOOTING_DRAGON;
            tickCounter = 0;
        }
        tickCounter++;
    }

    private void handleShootingDragon() {
        // Hold right click (use bow)
        if (!isUsingBow) {
            mc.options.useKey.setPressed(true);
            isUsingBow = true;
        }

        if (tickCounter >= dragonShootingDelay.get()) {
            mc.options.useKey.setPressed(false);
            isUsingBow = false;
            info("Finished shooting!");

            if (chimeraSwap.get()) {
                state = SequenceState.SWAPPING_TO_CHIMERA;
            } else if (throwRemnants.get()) {
                state = SequenceState.THROWING_REMNANTS;
            } else {
                state = SequenceState.WAITING_FOR_DRAGON_DOWN;
                dragonDeathTimer = 0;
            }
            tickCounter = 0;
        }
        tickCounter++;
    }

    private void handleSwappingToChimera() {
        InvUtils.swap(weaponSlot.get() - 1, false); // Convert 1-8 to 0-7
        info("Swapped to weapon slot " + weaponSlot.get());
        state = SequenceState.HOLDING_CHIMERA;
        isHoldingChimera = true;
    }

    private void handleHoldingChimera() {
        // Keep holding the chimera weapon until dragon is down
        // The message handler will catch "DRAGON DOWN!" and reset
    }

    private void handleThrowingRemnants() {
        if (tickCounter % actionDelay.get() == 0) {
            // Find and throw remnant
            int remnantSlot = findRemnantSlot();
            if (remnantSlot != -1) {
                InvUtils.swap(remnantSlot, false);
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId,
                    remnantSlot, 0, SlotActionType.THROW, mc.player);
                remnantsThrown++;
            } else {
                info("All remnants thrown (" + remnantsThrown + ")");
                state = SequenceState.WAITING_FOR_DRAGON_DOWN;
                dragonDeathTimer = 0;
                tickCounter = 0;
                return;
            }
        }
        tickCounter++;
    }

    private void handleWaitingForDragonDown() {
        dragonDeathTimer++;
        if (dragonDeathTimer >= dragonDeathDelay.get()) {
            info("Dragon death timeout reached. Returning to island...");
            ChatUtils.sendPlayerMsg("/is");
            toggle();
        }
    }

    // Helper methods
    private int countSummoningEyes() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            var stack = mc.player.getInventory().getStack(i);
            // Each summoning eye is a separate item (not stackable)
            if (isSummoningEye(stack)) {
                count++;
            }
        }
        return count;
    }

    private boolean isSummoningEye(net.minecraft.item.ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.PLAYER_HEAD) return false;

        try {
            var customData = stack.getComponents().get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
            if (customData == null) return false;

            var nbt = customData.copyNbt();
            if (!nbt.contains("ExtraAttributes")) return false;

            var extraAttributes = nbt.get("ExtraAttributes");
            if (extraAttributes == null) return false;

            if (extraAttributes instanceof net.minecraft.nbt.NbtCompound compound) {
                if (compound.contains("id")) {
                    var idElement = compound.get("id");
                    if (idElement instanceof net.minecraft.nbt.NbtString nbtString) {
                        return "SUMMONING_EYE".equals(nbtString.asString());
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail
        }

        return false;
    }

    private boolean isRemnant(net.minecraft.item.ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.PLAYER_HEAD) return false;

        try {
            var customData = stack.getComponents().get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
            if (customData == null) return false;

            var nbt = customData.copyNbt();
            if (!nbt.contains("ExtraAttributes")) return false;

            var extraAttributes = nbt.get("ExtraAttributes");
            if (extraAttributes == null) return false;

            if (extraAttributes instanceof net.minecraft.nbt.NbtCompound compound) {
                if (compound.contains("id")) {
                    var idElement = compound.get("id");
                    if (idElement instanceof net.minecraft.nbt.NbtString nbtString) {
                        return "REMNANT_OF_THE_EYE".equals(nbtString.asString());
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail
        }

        return false;
    }

    private int findSummoningEyeSlot() {
        for (int i = 0; i < 9; i++) {
            if (isSummoningEye(mc.player.getInventory().getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findRemnantSlot() {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (isRemnant(mc.player.getInventory().getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findBowSlot() {
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.BOW) {
                return i;
            }
        }
        return -1;
    }

    private BlockPos findVisibleEndPortalFrame() {
        BlockPos playerPos = mc.player.getBlockPos();
        int range = portalFrameRange.get();

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, range, range, range)) {
            // Check if it's an end portal frame without an eye
            if (mc.world.getBlockState(pos).getBlock() == Blocks.END_PORTAL_FRAME) {
                if (!mc.world.getBlockState(pos).get(EndPortalFrameBlock.EYE)) {
                    // Check if the block is visible (not behind other blocks)
                    if (isBlockVisible(pos)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private boolean isBlockVisible(BlockPos pos) {
        Vec3d playerEyes = mc.player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(pos);

        // Raycast from player eyes to block center
        HitResult result = mc.world.raycast(new net.minecraft.world.RaycastContext(
            playerEyes,
            blockCenter,
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,
            mc.player
        ));

        // If we hit the exact block we're looking for, it's visible
        if (result.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) result;
            return blockHit.getBlockPos().equals(pos);
        }

        return false;
    }

    // Enums
    public enum EyesToPlace {
        None("None", 0),
        Two("2", 2),
        Three("3", 3),
        Four("4", 4);

        private final String name;
        private final int count;

        EyesToPlace(String name, int count) {
            this.name = name;
            this.count = count;
        }

        @Override
        public String toString() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }

    public enum InventoryOption {
        Inventory("Inventory"),
        Stash("Stash"),
        BZInstaBuy("BZ Insta-Buy"),
        BZOrder("BZ Order");

        private final String name;

        InventoryOption(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private enum SequenceState {
        IDLE,
        WAITING_FOR_LEADER,
        OPENING_INVENTORY,
        CHECKING_INVENTORY,
        GETTING_EYES_STASH,
        GETTING_EYES_BZ_INSTA,
        GETTING_EYES_BZ_ORDER,
        PLACING_EYES,
        PATHING_TO_SHOOTING_POS,
        AIMING,
        WAITING_FOR_DRAGON,
        SHOOTING_DRAGON,
        SWAPPING_TO_CHIMERA,
        HOLDING_CHIMERA,
        THROWING_REMNANTS,
        WAITING_FOR_DRAGON_DOWN
    }
}
