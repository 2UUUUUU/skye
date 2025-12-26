package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.player.SmoothAimUtils;
import com.example.addon.utils.smp.SMPUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class AutoCrafting extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Setting Groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgItem = settings.createGroup("Item");
    private final SettingGroup sgResources = settings.createGroup("Resources");
    private final SettingGroup sgSendProfits = settings.createGroup("Send Profits");
    private final SettingGroup sgWebhook = settings.createGroup("Discord Webhook");

    // Item Settings (declared early)
    private final List<Item> DEFAULT_ITEM = List.of(Items.ANVIL);
    public static final List<Item> AVAILABLE_ITEMS = List.of(
        Items.ANVIL,
        Items.CHEST,
        Items.PISTON,
        Items.STICKY_PISTON
    );

    // Crafting Method Enum
    public enum CraftingMethod {
        INVENTORY("Inventory"),
        CRAFTING_TABLE("Crafting Table");

        private final String name;

        CraftingMethod(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Resource Getting Method Enum
    public enum ResourceMethod {
        INVENTORY("Inventory"),
        AUCTION_HOUSE("Auction House"),
        BUY_ORDER("Buy Order");

        private final String name;

        ResourceMethod(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // General Settings
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay in ticks between actions")
        .defaultValue(10)
        .min(1)
        .max(20)
        .sliderMax(20)
        .build());

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Delay in ticks between the sequence loop")
        .defaultValue(100)
        .min(0)
        .max(72000)
        .sliderMax(1200)
        .build());

    private final Setting<Boolean> smoothAim = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-aim")
        .description("Enable smooth aiming for interactions")
        .defaultValue(false)
        .build());

    private final Setting<Integer> rotationSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("rotation-speed")
        .description("Rotation speed in ticks for smooth aim")
        .defaultValue(10)
        .min(0)
        .max(600)
        .sliderMax(600)
        .visible(smoothAim::get)
        .build());

    private final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-mode")
        .description("Hide info logs and chat feedback")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> hideErrors = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-errors")
        .description("Also hide error messages")
        .defaultValue(false)
        .visible(silentMode::get)
        .build());

    // Item Settings
    private final Setting<List<Item>> itemToCraft = sgItem.add(new ItemListSetting.Builder()
        .name("item-to-craft")
        .description("Select the item to craft")
        .defaultValue(DEFAULT_ITEM)
        .filter((item) -> AVAILABLE_ITEMS.contains(item))
        .onChanged(items -> {
            if (items.size() > 1) {
                Item lastItem = items.get(items.size() - 1);
                items.clear();
                items.add(lastItem);
            }
        })
        .build());

    private final Setting<CraftingMethod> craftingMethod = sgItem.add(new EnumSetting.Builder<CraftingMethod>()
        .name("crafting-method")
        .description("Choose how to craft items")
        .defaultValue(CraftingMethod.INVENTORY)
        .onModuleActivated(setting -> {
            if (setting.get() == null) setting.set(CraftingMethod.INVENTORY);
        })
        .build());

    private final Setting<Integer> craftingTableRange = sgItem.add(new IntSetting.Builder()
        .name("crafting-table-range")
        .description("Range to search for crafting table")
        .defaultValue(4)
        .min(0)
        .max(4)
        .sliderMax(4)
        .visible(() -> craftingMethod.get() == CraftingMethod.CRAFTING_TABLE)
        .build());

    private final Setting<String> itemPrice = sgItem.add(new StringSetting.Builder()
        .name("price")
        .description("The price to sell the item for")
        .defaultValue("1000")
        .build());

    private final Setting<String> craftingAmount = sgItem.add(new StringSetting.Builder()
        .name("crafting-amount")
        .description("Amount of selected item to make")
        .defaultValue("64")
        .build());

    private final Setting<Integer> sellingAmount = sgItem.add(new IntSetting.Builder()
        .name("selling-amount")
        .description("Amount of items to sell per transaction")
        .defaultValue(64)
        .min(1)
        .max(64)
        .sliderMax(64)
        .build());

    private final Setting<Integer> craftingDelay = sgItem.add(new IntSetting.Builder()
        .name("crafting-delay")
        .description("How fast should the items be placed in the crafting grid (in ticks)")
        .defaultValue(3)
        .min(1)
        .max(20)
        .sliderMax(20)
        .build());

    // Resources Settings
    private final Setting<ResourceMethod> getResources = sgResources.add(new EnumSetting.Builder<ResourceMethod>()
        .name("get-resources")
        .description("How to obtain crafting resources")
        .defaultValue(ResourceMethod.INVENTORY)
        .onModuleActivated(setting -> {
            if (setting.get() == null) setting.set(ResourceMethod.INVENTORY);
        })
        .build());

    private final Setting<Boolean> createNew = sgResources.add(new BoolSetting.Builder()
        .name("create-new")
        .description("Create new buy orders if none exist")
        .defaultValue(false)
        .visible(() -> getResources.get() == ResourceMethod.BUY_ORDER)
        .build());

    // Send Profits Settings
    private final Setting<Boolean> enableSendProfits = sgSendProfits.add(new BoolSetting.Builder()
        .name("enable")
        .description("Enable automatic profit sending")
        .defaultValue(false)
        .build());

    private final Setting<String> profitPlayer = sgSendProfits.add(new StringSetting.Builder()
        .name("player")
        .description("Player name to send profits to")
        .defaultValue("")
        .visible(enableSendProfits::get)
        .build());

    private final Setting<Boolean> randomizeAmount = sgSendProfits.add(new BoolSetting.Builder()
        .name("randomize-amount")
        .description("Randomize the amount sent")
        .defaultValue(false)
        .visible(enableSendProfits::get)
        .build());

    private final Setting<Integer> minimumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("minimum-percent")
        .description("Minimum percentage of profit")
        .defaultValue(20)
        .min(0)
        .max(100)
        .visible(() -> enableSendProfits.get() && randomizeAmount.get())
        .build());

    private final Setting<Integer> maximumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("maximum-percent")
        .description("Maximum percentage of profit")
        .defaultValue(100)
        .min(0)
        .max(100)
        .visible(() -> enableSendProfits.get() && randomizeAmount.get())
        .build());

    // Discord Webhook Settings
    private final Setting<Boolean> webhook = sgWebhook.add(new BoolSetting.Builder()
        .name("webhook")
        .description("Enable webhook notifications")
        .defaultValue(false)
        .build());

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL")
        .defaultValue("")
        .visible(webhook::get)
        .build());

    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
        .name("self-ping")
        .description("Ping yourself in webhook")
        .defaultValue(false)
        .visible(webhook::get)
        .build());

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
        .name("discord-id")
        .description("Your Discord user ID")
        .defaultValue("")
        .visible(() -> webhook.get() && selfPing.get())
        .build());

    // State variables
    private enum State {
        IDLE,
        FIND_CRAFTING_TABLE,
        OPEN_CRAFTING_TABLE,
        CRAFT_ITEMS,
        PLACE_CRAFTING_ITEMS,
        WAIT_FOR_RECIPE,
        TAKE_CRAFTED_ITEM,
        WAIT_FOR_CRAFTING,
        SELECT_ITEM_HOTBAR,
        OPEN_INVENTORY,
        SPLIT_STACK,
        CLOSE_INVENTORY,
        EXECUTE_SELL_COMMAND,
        WAIT_FOR_CONFIRM_GUI,
        CONFIRM_LISTING,
        WAIT_AFTER_CONFIRM,
        LOOP_DELAY,
        // Buy Order states
        SEND_ORDERS_COMMAND,
        WAIT_FOR_ORDERS_GUI,
        VERIFY_ORDERS_GUI
    }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private BlockPos craftingTablePos = null;
    private int itemsCrafted = 0;
    private int targetCraftAmount = 0;
    private int splitAttempts = 0;
    private static final int MAX_SPLIT_ATTEMPTS = 3;
    private List<CraftingStep> currentRecipe = new ArrayList<>();
    private int recipeStep = 0;

    private static class CraftingStep {
        Item item;
        int slot;

        CraftingStep(Item item, int slot) {
            this.item = item;
            this.slot = slot;
        }
    }

    public AutoCrafting() {
        super(Main.CATEGORY, "auto-crafting", "Automatically crafts items and sells them");
    }

    public Item getSelectedItem() {
        List<Item> items = itemToCraft.get();
        return items.isEmpty() ? Items.ANVIL : items.get(0);
    }

    private String getItemDisplayName(Item item) {
        return Names.get(item);
    }

    @Override
    public void onActivate() {
        currentState = State.IDLE;
        resetCounters();
        Item selected = getSelectedItem();
        info("AutoCrafting activated - Item: " + getItemDisplayName(selected));
    }

    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        resetCounters();
        cleanupControls();
        SmoothAimUtils.cancelRotation();
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
        info("AutoCrafting deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (smoothAim.get() && SmoothAimUtils.isRotating()) {
            SmoothAimUtils.tickRotation();
        }

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        handleState();
    }

    private void resetCounters() {
        delayCounter = 0;
        itemsCrafted = 0;
        craftingTablePos = null;
        splitAttempts = 0;
        currentRecipe.clear();
        recipeStep = 0;
    }

    private void cleanupControls() {
        SMPUtils.stopBreaking();
        SMPUtils.stopMoving();
        SMPUtils.setJumping(false);
    }

    private void handleState() {
        switch (currentState) {
            case IDLE -> {
                try {
                    targetCraftAmount = Integer.parseInt(craftingAmount.get());
                } catch (NumberFormatException e) {
                    error("Invalid crafting amount! Using default: 64");
                    targetCraftAmount = 64;
                }

                Item item = getSelectedItem();
                if (craftingMethod.get() == CraftingMethod.INVENTORY) {
                    if (!canCraftInInventory(item)) {
                        error("ERROR: Can't craft " + getItemDisplayName(item) + " in inventory, use \"Crafting Table\" method!");
                        toggle();
                        return;
                    }
                }

                // Check resources based on selected method
                if (getResources.get() == ResourceMethod.INVENTORY) {
                    if (!hasRequiredResources(item)) {
                        // Error message already displayed in hasRequiredResources()
                        toggle();
                        return;
                    }
                    info("All required resources found in inventory");

                    // Proceed to crafting
                    if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                        currentState = State.FIND_CRAFTING_TABLE;
                    } else {
                        currentState = State.CRAFT_ITEMS;
                    }
                } else if (getResources.get() == ResourceMethod.AUCTION_HOUSE) {
                    info("Resource method: Auction House (not yet implemented)");
                    toggle();
                } else if (getResources.get() == ResourceMethod.BUY_ORDER) {
                    info("Resource method: Buy Order - Opening orders menu");
                    currentState = State.SEND_ORDERS_COMMAND;
                    delayCounter = actionDelay.get();
                }
            }

            case FIND_CRAFTING_TABLE -> {
                craftingTablePos = findNearestCraftingTable();
                if (craftingTablePos == null) {
                    error("ERROR: Couldn't find the Crafting Table!");
                    toggle();
                    return;
                }
                currentState = State.OPEN_CRAFTING_TABLE;
                delayCounter = actionDelay.get();
            }

            case OPEN_CRAFTING_TABLE -> {
                if (openCraftingTable(craftingTablePos)) {
                    currentState = State.CRAFT_ITEMS;
                    delayCounter = actionDelay.get();
                }
            }

            case CRAFT_ITEMS -> {
                if (itemsCrafted >= targetCraftAmount) {
                    if (mc.currentScreen != null) {
                        mc.player.closeHandledScreen();
                    }
                    currentState = State.SELECT_ITEM_HOTBAR;
                    delayCounter = actionDelay.get();
                    return;
                }

                // Prepare the recipe for the selected item
                Item item = getSelectedItem();
                currentRecipe = getRecipeSteps(item);
                recipeStep = 0;

                if (currentRecipe.isEmpty()) {
                    error("ERROR: No recipe found for the selected item!");
                    toggle();
                    return;
                }

                currentState = State.PLACE_CRAFTING_ITEMS;
                delayCounter = actionDelay.get();
            }

            case PLACE_CRAFTING_ITEMS -> {
                if (recipeStep >= currentRecipe.size()) {
                    // All items placed, wait for recipe to form
                    currentState = State.WAIT_FOR_RECIPE;
                    delayCounter = craftingDelay.get();
                    return;
                }

                // Place one item at a time
                CraftingStep step = currentRecipe.get(recipeStep);
                if (placeItemInSlot(step.item, step.slot)) {
                    recipeStep++;
                    delayCounter = craftingDelay.get();
                } else {
                    error("ERROR: Not enough resources to craft the selected item!");
                    toggle();
                }
            }

            case WAIT_FOR_RECIPE -> {
                // Give the server time to recognize the recipe
                info("Waiting for recipe to be recognized...");
                currentState = State.TAKE_CRAFTED_ITEM;
                delayCounter = craftingDelay.get() * 2; // Longer delay to ensure recipe is recognized
            }

            case TAKE_CRAFTED_ITEM -> {
                info("Taking crafted item from result slot...");

                // Make sure we're in the crafting screen
                if (mc.currentScreen instanceof CraftingScreen) {
                    // Shift-click the result slot (slot 0) to take the crafted item
                    mc.interactionManager.clickSlot(
                        mc.player.currentScreenHandler.syncId,
                        0, // Result slot
                        0, // Left click
                        SlotActionType.QUICK_MOVE, // Shift-click
                        mc.player
                    );
                    info("Shift-clicked result slot 0");
                } else {
                    info("Not in crafting screen, attempting inventory craft...");
                    // Inventory crafting
                    mc.interactionManager.clickSlot(
                        mc.player.currentScreenHandler.syncId,
                        0, // Result slot
                        0, // Left click
                        SlotActionType.QUICK_MOVE, // Shift-click
                        mc.player
                    );
                }

                currentState = State.WAIT_FOR_CRAFTING;
                delayCounter = craftingDelay.get();
            }

            case WAIT_FOR_CRAFTING -> {
                itemsCrafted++;

                // Make sure cursor is empty before continuing
                if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                    info("Cursor still has items, returning them to inventory...");
                    // Find an empty or matching slot in inventory to return items
                    ItemStack cursorStack = mc.player.currentScreenHandler.getCursorStack();
                    for (int i = 10; i < mc.player.currentScreenHandler.slots.size(); i++) {
                        ItemStack slotStack = mc.player.currentScreenHandler.getSlot(i).getStack();
                        if (slotStack.isEmpty() || (slotStack.getItem() == cursorStack.getItem() && slotStack.getCount() < slotStack.getMaxCount())) {
                            mc.interactionManager.clickSlot(
                                mc.player.currentScreenHandler.syncId,
                                i,
                                0,
                                SlotActionType.PICKUP,
                                mc.player
                            );
                            info("Returned cursor items to inventory slot " + i);
                            break;
                        }
                    }
                }

                // Clear the recipe for next iteration
                currentRecipe.clear();
                recipeStep = 0;

                if (itemsCrafted < targetCraftAmount) {
                    currentState = State.CRAFT_ITEMS;
                } else {
                    if (mc.currentScreen != null) {
                        mc.player.closeHandledScreen();
                    }
                    currentState = State.SELECT_ITEM_HOTBAR;
                }
                delayCounter = actionDelay.get();
            }

            case SELECT_ITEM_HOTBAR -> {
                if (selectItemInHotbar(getSelectedItem())) {
                    currentState = State.OPEN_INVENTORY;
                    delayCounter = actionDelay.get();
                } else {
                    error("Failed to select item in hotbar!");
                    toggle();
                }
            }

            case OPEN_INVENTORY -> {
                if (mc.currentScreen == null) {
                    mc.setScreen(new InventoryScreen(mc.player));
                    delayCounter = actionDelay.get();
                } else {
                    currentState = State.SPLIT_STACK;
                    delayCounter = actionDelay.get();
                }
            }

            case SPLIT_STACK -> {
                if (splitStackToAmount(getSelectedItem(), sellingAmount.get())) {
                    currentState = State.CLOSE_INVENTORY;
                    delayCounter = actionDelay.get();
                    splitAttempts = 0;
                } else {
                    splitAttempts++;
                    if (splitAttempts >= MAX_SPLIT_ATTEMPTS) {
                        info("Stack splitting failed after max attempts, proceeding anyway...");
                        currentState = State.EXECUTE_SELL_COMMAND;
                        mc.player.closeHandledScreen();
                        delayCounter = actionDelay.get();
                        splitAttempts = 0;
                    } else {
                        delayCounter = actionDelay.get();
                    }
                }
            }

            case CLOSE_INVENTORY -> {
                mc.player.closeHandledScreen();
                currentState = State.EXECUTE_SELL_COMMAND;
                delayCounter = actionDelay.get();
            }

            case EXECUTE_SELL_COMMAND -> {
                String command = "ah sell " + itemPrice.get();
                mc.player.networkHandler.sendChatCommand(command);
                currentState = State.WAIT_FOR_CONFIRM_GUI;
                delayCounter = actionDelay.get() * 2;
            }

            case WAIT_FOR_CONFIRM_GUI -> {
                if (isConfirmListingGuiOpen()) {
                    currentState = State.CONFIRM_LISTING;
                    delayCounter = actionDelay.get();
                }
            }

            case CONFIRM_LISTING -> {
                if (clickConfirmButton()) {
                    currentState = State.WAIT_AFTER_CONFIRM;
                    delayCounter = actionDelay.get();
                }
            }

            case WAIT_AFTER_CONFIRM -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }

                if (hasMoreItemsToSell(getSelectedItem())) {
                    currentState = State.OPEN_INVENTORY;
                    delayCounter = actionDelay.get();
                } else {
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                }
            }

            case LOOP_DELAY -> {
                resetCounters();
                currentState = State.IDLE;
            }

            case SEND_ORDERS_COMMAND -> {
                mc.player.networkHandler.sendChatCommand("orders");
                info("Sent /orders command");
                currentState = State.WAIT_FOR_ORDERS_GUI;
                delayCounter = actionDelay.get() * 2;
            }

            case WAIT_FOR_ORDERS_GUI -> {
                if (isOrdersGuiOpen()) {
                    info("Orders GUI detected, verifying contents...");
                    currentState = State.VERIFY_ORDERS_GUI;
                    delayCounter = actionDelay.get();
                } else {
                    info("Still waiting for orders GUI to open...");
                }
            }

            case VERIFY_ORDERS_GUI -> {
                boolean itemsCorrect = verifyOrdersGuiItems();
                boolean nameCorrect = verifyOrdersGuiName();

                // Send verification results to chat
                mc.player.networkHandler.sendChatMessage("Item check menu = " + itemsCorrect);
                mc.player.networkHandler.sendChatMessage("name check menu = " + nameCorrect);

                info("Verification complete - Items: " + itemsCorrect + ", Name: " + nameCorrect);

                // For now, stop here (you'll add more functionality later)
                toggle();
            }
        }
    }

    private BlockPos findNearestCraftingTable() {
        if (mc.world == null || mc.player == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        int range = craftingTableRange.get();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
                        double dist = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }

        return nearest;
    }

    private boolean openCraftingTable(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return false;

        Vec3d hitVec = Vec3d.ofCenter(pos);
        Direction side = Direction.UP;

        if (smoothAim.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), rotationSpeed.get(), () -> {
                BlockHitResult hitResult = new BlockHitResult(hitVec, side, pos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            });
        } else {
            BlockHitResult hitResult = new BlockHitResult(hitVec, side, pos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        }

        return true;
    }

    private boolean canCraftInInventory(Item item) {
        // Items that require 3x3 grid
        if (item == Items.PISTON || item == Items.STICKY_PISTON) {
            return false;
        }
        return true;
    }

    private boolean hasRequiredResources(Item item) {
        if (mc.player == null) return false;

        // Only check inventory if "Inventory" method is selected
        if (getResources.get() != ResourceMethod.INVENTORY) {
            // For Auction House and Buy Order, we'll handle resource acquisition differently
            return true; // Skip check for now, will be implemented later
        }

        Map<Item, Integer> required = getRequiredResources(item);
        Map<Item, Integer> missing = new HashMap<>();

        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            int count = InvUtils.find(entry.getKey()).count();
            if (count < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - count);
            }
        }

        if (!missing.isEmpty()) {
            // Build detailed error message
            StringBuilder errorMsg = new StringBuilder("ERROR: Missing resources in inventory:");
            for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
                errorMsg.append("\n  - ").append(getItemDisplayName(entry.getKey()))
                    .append(" x").append(entry.getValue());
            }
            error(errorMsg.toString());
            return false;
        }

        return true;
    }

    private Map<Item, Integer> getRequiredResources(Item item) {
        Map<Item, Integer> resources = new HashMap<>();

        if (item == Items.ANVIL) {
            resources.put(Items.IRON_BLOCK, 3);
            resources.put(Items.IRON_INGOT, 4);
        } else if (item == Items.CHEST) {
            resources.put(Items.OAK_PLANKS, 8);
        } else if (item == Items.PISTON) {
            resources.put(Items.OAK_PLANKS, 3);
            resources.put(Items.COBBLESTONE, 4);
            resources.put(Items.IRON_INGOT, 1);
            resources.put(Items.REDSTONE, 1);
        } else if (item == Items.STICKY_PISTON) {
            resources.put(Items.PISTON, 1);
            resources.put(Items.SLIME_BALL, 1);
        }

        return resources;
    }

    private List<CraftingStep> getRecipeSteps(Item item) {
        List<CraftingStep> steps = new ArrayList<>();

        // Crafting table screen handler slots:
        // Crafting grid: slots 1-9 (top-left to bottom-right, row by row)
        // Result: slot 0
        // Inventory: slots 10+

        if (item == Items.ANVIL) {
            // Anvil recipe: 3 iron blocks on top, 1 iron ingot in center, 3 iron ingots on bottom
            // Top row: slots 1, 2, 3
            steps.add(new CraftingStep(Items.IRON_BLOCK, 1));
            steps.add(new CraftingStep(Items.IRON_BLOCK, 2));
            steps.add(new CraftingStep(Items.IRON_BLOCK, 3));
            // Middle row: slot 5 (center)
            steps.add(new CraftingStep(Items.IRON_INGOT, 5));
            // Bottom row: slots 7, 8, 9
            steps.add(new CraftingStep(Items.IRON_INGOT, 7));
            steps.add(new CraftingStep(Items.IRON_INGOT, 8));
            steps.add(new CraftingStep(Items.IRON_INGOT, 9));
        } else if (item == Items.CHEST) {
            // Chest recipe: 8 planks surrounding empty center
            steps.add(new CraftingStep(Items.OAK_PLANKS, 1));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 2));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 3));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 4));
            // Skip slot 5 (center)
            steps.add(new CraftingStep(Items.OAK_PLANKS, 6));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 7));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 8));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 9));
        } else if (item == Items.PISTON) {
            // Piston recipe
            steps.add(new CraftingStep(Items.OAK_PLANKS, 1));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 2));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 3));
            steps.add(new CraftingStep(Items.COBBLESTONE, 4));
            steps.add(new CraftingStep(Items.IRON_INGOT, 5));
            steps.add(new CraftingStep(Items.COBBLESTONE, 6));
            steps.add(new CraftingStep(Items.COBBLESTONE, 7));
            steps.add(new CraftingStep(Items.REDSTONE, 8));
            steps.add(new CraftingStep(Items.COBBLESTONE, 9));
        } else if (item == Items.STICKY_PISTON) {
            // Sticky piston: slime ball on top of piston
            if (mc.currentScreen instanceof CraftingScreen) {
                steps.add(new CraftingStep(Items.SLIME_BALL, 2));
                steps.add(new CraftingStep(Items.PISTON, 5));
            } else {
                // Inventory crafting (2x2 grid in player inventory)
                steps.add(new CraftingStep(Items.SLIME_BALL, 1));
                steps.add(new CraftingStep(Items.PISTON, 3));
            }
        }

        return steps;
    }

    private boolean placeItemInSlot(Item item, int slot) {
        if (mc.player == null || mc.interactionManager == null) return false;

        // Debug info
        info("Placing " + getItemDisplayName(item) + " in slot " + slot + " (step " + (recipeStep + 1) + "/" + currentRecipe.size() + ")");

        // Find the item in inventory or cursor
        ItemStack cursorStack = mc.player.currentScreenHandler.getCursorStack();

        // Check if cursor already has the correct item
        if (!cursorStack.isEmpty() && cursorStack.getItem() == item) {
            // Place one item from cursor into the slot
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                slot,
                1, // Right click to place one
                SlotActionType.PICKUP,
                mc.player
            );

            info("Placed item from cursor into slot " + slot);
            return true;
        }

        // Cursor doesn't have the item, need to pick it up
        // First, if cursor has something else, return it
        if (!cursorStack.isEmpty()) {
            // Find where this item came from and return it
            for (int i = 10; i < mc.player.currentScreenHandler.slots.size(); i++) {
                ItemStack slotStack = mc.player.currentScreenHandler.getSlot(i).getStack();
                if (slotStack.isEmpty() || slotStack.getItem() == cursorStack.getItem()) {
                    mc.interactionManager.clickSlot(
                        mc.player.currentScreenHandler.syncId,
                        i,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );
                    info("Returned previous item to slot " + i);
                    break;
                }
            }
        }

        // Find the item in inventory (slots 10+)
        int itemSlot = -1;
        for (int i = 10; i < mc.player.currentScreenHandler.slots.size(); i++) {
            ItemStack slotStack = mc.player.currentScreenHandler.getSlot(i).getStack();
            if (slotStack.getItem() == item && slotStack.getCount() > 0) {
                itemSlot = i;
                break;
            }
        }

        if (itemSlot == -1) {
            error("Could not find " + getItemDisplayName(item) + " in inventory!");
            return false;
        }

        info("Found " + getItemDisplayName(item) + " in inventory slot " + itemSlot);

        // Pick up the stack
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            itemSlot,
            0, // Left click
            SlotActionType.PICKUP,
            mc.player
        );

        info("Picked up stack from slot " + itemSlot);

        // Place one item in the crafting slot
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            slot,
            1, // Right click to place one
            SlotActionType.PICKUP,
            mc.player
        );

        info("Placed one item into crafting slot " + slot);

        // Determine if we should return remaining items
        boolean shouldReturn = false;

        // Check if next step uses a different item
        if (recipeStep + 1 < currentRecipe.size()) {
            CraftingStep nextStep = currentRecipe.get(recipeStep + 1);
            if (nextStep.item != item) {
                shouldReturn = true;
                info("Next step needs different item, will return remaining");
            }
        } else {
            // This was the last step
            shouldReturn = true;
            info("Last crafting step, will return remaining");
        }

        // Return remaining items if needed
        if (shouldReturn) {
            ItemStack newCursorStack = mc.player.currentScreenHandler.getCursorStack();
            if (!newCursorStack.isEmpty()) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    itemSlot,
                    0, // Left click
                    SlotActionType.PICKUP,
                    mc.player
                );
                info("Returned remaining items to slot " + itemSlot);
            }
        }

        return true;
    }

    private boolean selectItemInHotbar(Item item) {
        if (mc.player == null) return false;

        FindItemResult result = InvUtils.findInHotbar(item);
        if (!result.found()) {
            // Move from inventory to hotbar
            int invSlot = InvUtils.find(item).slot();
            if (invSlot == -1) return false;

            InvUtils.move().from(invSlot).toHotbar(0);
            delayCounter = actionDelay.get();
        }

        // Select in hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) {
                InvUtils.swap(i, false);
                return true;
            }
        }

        return false;
    }

    private boolean splitStackToAmount(Item item, int targetAmount) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (!(mc.currentScreen instanceof InventoryScreen)) return false;

        info("Attempting to split stack to amount: " + targetAmount);

        // Find the item stack in player inventory (hotbar and main inventory)
        int sourceSlot = -1;
        ItemStack largestStack = ItemStack.EMPTY;
        int largestCount = 0;

        // Search through player inventory slots
        for (int i = 9; i < 45; i++) { // Slots 9-44 are player inventory (hotbar + main)
            ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
            if (stack.getItem() == item && stack.getCount() > targetAmount && stack.getCount() > largestCount) {
                sourceSlot = i;
                largestStack = stack;
                largestCount = stack.getCount();
            }
        }

        if (sourceSlot == -1) {
            info("No stack large enough to split found");
            return false;
        }

        info("Found stack of " + largestCount + " in slot " + sourceSlot);

        // Find an empty slot for the split
        int emptySlot = -1;
        for (int i = 9; i < 45; i++) {
            if (mc.player.currentScreenHandler.getSlot(i).getStack().isEmpty()) {
                emptySlot = i;
                break;
            }
        }

        if (emptySlot == -1) {
            info("No empty slot found for splitting");
            return false;
        }

        info("Found empty slot at " + emptySlot);

        // Pick up the entire stack with left click
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            sourceSlot,
            0, // Left click
            SlotActionType.PICKUP,
            mc.player
        );

        info("Picked up stack from slot " + sourceSlot);

        // Calculate how many items to place in the new slot
        int itemsToPlace = targetAmount;

        // Right-click to place items one at a time
        for (int i = 0; i < itemsToPlace; i++) {
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                emptySlot,
                1, // Right click to place one
                SlotActionType.PICKUP,
                mc.player
            );
        }

        info("Placed " + itemsToPlace + " items in empty slot " + emptySlot);

        // Place the remaining items back in the original slot
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            sourceSlot,
            0, // Left click
            SlotActionType.PICKUP,
            mc.player
        );

        info("Returned remaining items to slot " + sourceSlot);

        // Make sure the split stack is now in the hotbar
        // If emptySlot is not in hotbar (0-8), move it there
        if (emptySlot >= 9) {
            // Find empty hotbar slot or swap
            for (int hotbarSlot = 36; hotbarSlot < 45; hotbarSlot++) { // Hotbar is slots 36-44 in screen handler
                if (mc.player.currentScreenHandler.getSlot(hotbarSlot).getStack().isEmpty() ||
                    mc.player.currentScreenHandler.getSlot(hotbarSlot).getStack().getItem() != item) {
                    // Swap the split stack to hotbar
                    InvUtils.move().from(emptySlot).to(hotbarSlot);
                    info("Moved split stack from slot " + emptySlot + " to hotbar slot " + hotbarSlot);
                    break;
                }
            }
        }

        return true;
    }

    private boolean isConfirmListingGuiOpen() {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            var handler = screen.getScreenHandler();

            boolean hasLimePane = false;
            boolean hasRedPane = false;

            // Check all slots for both lime and red glass panes
            for (int i = 0; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                Item item = stack.getItem();

                if (item == Items.LIME_STAINED_GLASS_PANE) {
                    hasLimePane = true;
                } else if (item == Items.RED_STAINED_GLASS_PANE) {
                    hasRedPane = true;
                }

                // Early exit if both found
                if (hasLimePane && hasRedPane) {
                    return true;
                }
            }

            return false;
        }
        return false;
    }

    private boolean clickConfirmButton() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return false;

        var handler = screen.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                // Use direct interaction manager click
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    i,
                    0, // Left click
                    SlotActionType.PICKUP,
                    mc.player
                );
                info("Clicked confirm button in slot " + i);
                return true;
            }
        }

        info("Could not find lime glass pane to click");
        return false;
    }

    private boolean hasMoreItemsToSell(Item item) {
        if (mc.player == null) return false;

        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }

        return count >= sellingAmount.get();
    }

    private boolean isOrdersGuiOpen() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        var handler = screen.getScreenHandler();
        // 6-row chest has 54 slots (6 rows * 9 columns) + 36 player inventory
        return handler.slots.size() == 54 + 36;
    }

    private boolean verifyOrdersGuiItems() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        var handler = screen.getScreenHandler();

        // Last row: slots 45-53
        // Pattern: empty - empty - cauldron - hopper - map - oak_sign - chest - empty - arrow
        Item[] expectedPattern = {
            null, // empty slot (slot 45)
            null, // empty slot (slot 46)
            Items.CAULDRON, // slot 47
            Items.HOPPER, // slot 48
            Items.MAP, // slot 49
            Items.OAK_SIGN, // slot 50
            Items.CHEST, // slot 51
            null, // empty slot (slot 52)
            Items.ARROW // slot 53
        };

        for (int i = 0; i < expectedPattern.length; i++) {
            int slotIndex = 45 + i;
            ItemStack stack = handler.getSlot(slotIndex).getStack();

            if (expectedPattern[i] == null) {
                // Expecting empty slot
                if (!stack.isEmpty()) {
                    return false;
                }
            } else {
                // Expecting specific item
                if (stack.isEmpty() || stack.getItem() != expectedPattern[i]) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean verifyOrdersGuiName() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        // Use SMPUtils method for checking if this is the Orders menu
        boolean isOrders = SMPUtils.isOrdersMenu(screen);

        info("Checking chest title: '" + screen.getTitle().getString() + "'");
        info("Is Orders Menu: " + isOrders);

        return isOrders;
    }

    // Helper methods to respect silent mode
    private void info(String message) {
        if (!silentMode.get()) {
            super.info(message);
        }
    }

    private void error(String message) {
        // Show errors unless both silent mode AND hide errors are enabled
        if (!silentMode.get() || !hideErrors.get()) {
            super.error(message);
        }
    }
}
