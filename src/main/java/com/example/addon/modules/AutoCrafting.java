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
        Items.STICKY_PISTON,
        Items.OAK_SIGN
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

    // Price Mode Enum
    public enum PriceMode {
        FIXED("Fixed"),
        RANDOM("Random"),
        ADAPTIVE("Adaptive");

        private final String name;

        PriceMode(String name) {
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

    private final Setting<PriceMode> priceMode = sgItem.add(new EnumSetting.Builder<PriceMode>()
        .name("price")
        .description("How to determine the selling price")
        .defaultValue(PriceMode.FIXED)
        .onModuleActivated(setting -> {
            if (setting.get() == null) setting.set(PriceMode.FIXED);
        })
        .build());

    // Fixed price settings
    private final Setting<String> fixedPrice = sgItem.add(new StringSetting.Builder()
        .name("fixed-price")
        .description("Fixed price to sell items for (supports K, M, B)")
        .defaultValue("1000")
        .visible(() -> priceMode.get() == PriceMode.FIXED)
        .build());

    // Random price settings
    private final Setting<String> minRandomPrice = sgItem.add(new StringSetting.Builder()
        .name("min-price")
        .description("Minimum random price")
        .defaultValue("100")
        .visible(() -> priceMode.get() == PriceMode.RANDOM)
        .build());

    private final Setting<String> maxRandomPrice = sgItem.add(new StringSetting.Builder()
        .name("max-price")
        .description("Maximum random price")
        .defaultValue("1000")
        .visible(() -> priceMode.get() == PriceMode.RANDOM)
        .build());

    private final Setting<Boolean> decimalRandom = sgItem.add(new BoolSetting.Builder()
        .name("decimal")
        .description("Allow decimal values for random prices")
        .defaultValue(false)
        .visible(() -> priceMode.get() == PriceMode.RANDOM)
        .build());

    // Adaptive price mode settings
    private final Setting<Boolean> adaptiveFixed = sgItem.add(new BoolSetting.Builder()
        .name("adaptive-fixed")
        .description("Use the lowest price from auction house")
        .defaultValue(true)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Boolean> adaptiveRandom = sgItem.add(new BoolSetting.Builder()
        .name("adaptive-random")
        .description("Use a random price from auction house range")
        .defaultValue(false)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Boolean> adaptiveDecimal = sgItem.add(new BoolSetting.Builder()
        .name("adaptive-decimal")
        .description("Add random decimal values to the price")
        .defaultValue(false)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE && adaptiveRandom.get())
        .build());

    private final Setting<String> adaptiveSubtract = sgItem.add(new StringSetting.Builder()
        .name("adaptive-subtract")
        .description("Amount to subtract from the fetched price (supports K, M, B)")
        .defaultValue("0")
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Boolean> antiBait = sgItem.add(new BoolSetting.Builder()
        .name("anti-bait")
        .description("Ignore the first 3 items in the auction house")
        .defaultValue(false)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
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

    private final Setting<Integer> splittingDelay = sgItem.add(new IntSetting.Builder()
        .name("splitting-delay")
        .description("How fast should the items get splitted into different stacks after being crafted (in ticks)")
        .defaultValue(3)
        .min(1)
        .max(300)
        .sliderMax(300)
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
        CHECK_REMAINING_ITEMS,
        SEND_AH_SEARCH_COMMAND,
        WAIT_FOR_AH_GUI,
        PARSE_AH_PRICES,
        CLOSE_AH_GUI,
        MOVE_ITEMS_TO_HOTBAR,
        LOOP_DELAY,
        // Buy Order states
        SEND_ORDERS_COMMAND,
        WAIT_FOR_ORDERS_GUI,
        VERIFY_ORDERS_GUI,
        CLICK_CHEST_IN_ORDERS,
        WAIT_FOR_YOUR_ORDERS_GUI,
        CLICK_OAK_LOG_ORDER,
        WAIT_FOR_EDIT_ORDER_GUI,
        CLICK_COLLECT_CHEST,
        COLLECT_OAK_LOGS,
        CLOSE_BUY_ORDER_GUI,
        // Oak Sign specific states
        CHECK_OAK_SIGN_RESOURCES,
        CONVERT_LOGS_TO_PLANKS,
        WAIT_FOR_PLANKS_CRAFT,
        TAKE_PLANKS,
        CONVERT_PLANKS_TO_STICKS,
        WAIT_FOR_STICKS_CRAFT,
        TAKE_STICKS
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
    private String adaptivePrice = null;
    private boolean waitingForAuctionGui = false;
    private int sequenceCycleCount = 0;
    private static final int PRICE_REFRESH_CYCLES = 3;

    // Oak Sign specific variables
    private boolean needsOakPlanks = false;
    private boolean needsSticks = false;
    private int oakSignCheckAttempts = 0;
    private static final int MAX_OAK_SIGN_CHECK_ATTEMPTS = 3;

    // Splitting progress variables
    private int totalStacksToCreate = 0;
    private int stacksSplitSoFar = 0;
    private List<Integer> sourceItemSlots = new ArrayList<>();
    private List<Integer> targetEmptySlots = new ArrayList<>();
    private int currentSourceSlot = -1;
    private int itemsRemainingInCurrentSource = 0;

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

    private String calculatePrice() {
        switch (priceMode.get()) {
            case FIXED -> {
                return fixedPrice.get();
            }
            case RANDOM -> {
                try {
                    double min = Double.parseDouble(minRandomPrice.get());
                    double max = Double.parseDouble(maxRandomPrice.get());

                    if (min > max) {
                        double temp = min;
                        min = max;
                        max = temp;
                    }

                    double randomValue = min + (Math.random() * (max - min));

                    if (decimalRandom.get()) {
                        return String.format("%.2f", randomValue);
                    } else {
                        return String.valueOf((int) Math.round(randomValue));
                    }
                } catch (NumberFormatException e) {
                    error("Invalid random price values, using default 1000");
                    return "1000";
                }
            }
            case ADAPTIVE -> {
                if (adaptivePrice == null) {
                    error("Adaptive price not yet calculated, using default 1000");
                    return "1000";
                }
                return adaptivePrice;
            }
            default -> {
                return "1000";
            }
        }
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
        adaptivePrice = null;
        waitingForAuctionGui = false;
        sequenceCycleCount = 0; // Reset cycle counter
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
        needsOakPlanks = false;
        needsSticks = false;
        oakSignCheckAttempts = 0;

        // Reset splitting variables
        totalStacksToCreate = 0;
        stacksSplitSoFar = 0;
        sourceItemSlots.clear();
        targetEmptySlots.clear();
        currentSourceSlot = -1;
        itemsRemainingInCurrentSource = 0;

        // NOTE: Do NOT reset sequenceCycleCount here - it persists across sequences
        // NOTE: Do NOT reset adaptivePrice here - it persists until refresh is needed
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

                // Check if we need to fetch adaptive pricing
                if (priceMode.get() == PriceMode.ADAPTIVE) {
                    // Refresh price every 3 cycles or if not yet fetched
                    if (adaptivePrice == null || sequenceCycleCount >= PRICE_REFRESH_CYCLES) {
                        info("Adaptive pricing enabled, fetching auction house prices... (Cycle: " + sequenceCycleCount + ")");
                        sequenceCycleCount = 0; // Reset counter
                        adaptivePrice = null; // Clear old price
                        currentState = State.SEND_AH_SEARCH_COMMAND;
                        delayCounter = actionDelay.get();
                        return;
                    } else {
                        info("Using cached adaptive price: " + adaptivePrice + " (Cycle: " + sequenceCycleCount + "/" + PRICE_REFRESH_CYCLES + ")");
                    }
                }

                // Special handling for Oak Sign
                if (item == Items.OAK_SIGN) {
                    currentState = State.CHECK_OAK_SIGN_RESOURCES;
                    delayCounter = actionDelay.get();
                    return;
                }

                // Check resources based on selected method
                if (getResources.get() == ResourceMethod.INVENTORY) {
                    if (!hasRequiredResources(item)) {
                        toggle();
                        return;
                    }
                    info("All required resources found in inventory");

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

            case CHECK_OAK_SIGN_RESOURCES -> {
                int oakLogs = InvUtils.find(Items.OAK_LOG).count();
                int oakPlanks = InvUtils.find(Items.OAK_PLANKS).count();
                int sticks = InvUtils.find(Items.STICK).count();

                info("Oak Sign resource check (attempt " + (oakSignCheckAttempts + 1) + "/" + MAX_OAK_SIGN_CHECK_ATTEMPTS + ") - Logs: " + oakLogs + ", Planks: " + oakPlanks + ", Sticks: " + sticks);

                // Check if we need to get resources via Buy Order
                if (getResources.get() == ResourceMethod.BUY_ORDER && oakLogs < 2) {
                    oakSignCheckAttempts++;
                    if (oakSignCheckAttempts > MAX_OAK_SIGN_CHECK_ATTEMPTS) {
                        error("ERROR: Failed to prepare Oak Sign resources after " + MAX_OAK_SIGN_CHECK_ATTEMPTS + " attempts!");
                        toggle();
                        return;
                    }
                    info("Not enough Oak Logs, using Buy Order to obtain resources");
                    currentState = State.SEND_ORDERS_COMMAND;
                    delayCounter = actionDelay.get();
                    return;
                }

                // For inventory method, check if we have enough oak logs
                if (getResources.get() == ResourceMethod.INVENTORY && oakLogs < 2) {
                    error("ERROR: Not enough items in inventory! Oak Logs x" + (2 - oakLogs) + " required!");
                    toggle();
                    return;
                }

                // Check if we need to make planks
                if (oakPlanks < 6) {
                    info("Need to convert Oak Logs to Oak Planks");
                    needsOakPlanks = true;
                    needsSticks = false; // Make sure stick flag is off
                    oakSignCheckAttempts = 0;
                    if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                        currentState = State.FIND_CRAFTING_TABLE;
                    } else {
                        currentState = State.CONVERT_LOGS_TO_PLANKS;
                    }
                    delayCounter = actionDelay.get();
                    return;
                }

                // Check if we need to make sticks
                if (sticks < 1) {
                    info("Need to convert Oak Planks to Sticks");
                    needsOakPlanks = false; // Make sure plank flag is off
                    needsSticks = true;
                    oakSignCheckAttempts = 0;
                    if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                        currentState = State.FIND_CRAFTING_TABLE;
                    } else {
                        currentState = State.CONVERT_PLANKS_TO_STICKS;
                    }
                    delayCounter = actionDelay.get();
                    return;
                }

                // All resources ready, proceed to crafting Oak Signs
                info("All Oak Sign resources ready (Planks: " + oakPlanks + ", Sticks: " + sticks + "), proceeding to craft Oak Signs");
                oakSignCheckAttempts = 0;
                needsOakPlanks = false;
                needsSticks = false;

                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                    info("Using crafting table method for Oak Signs");
                    currentState = State.FIND_CRAFTING_TABLE;
                } else {
                    info("Using inventory method for Oak Signs");
                    currentState = State.CRAFT_ITEMS;
                }
                delayCounter = actionDelay.get();
            }

            case CONVERT_LOGS_TO_PLANKS -> {
                info("Converting Oak Logs to Oak Planks");

                // Check if we're in the right screen for crafting table
                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                    if (!(mc.currentScreen instanceof CraftingScreen)) {
                        error("ERROR: Not in crafting table screen for plank conversion!");
                        currentState = State.FIND_CRAFTING_TABLE;
                        delayCounter = actionDelay.get();
                        return;
                    }
                }

                // Place oak log in any slot of the crafting grid
                if (placeItemInSlot(Items.OAK_LOG, 1)) {
                    currentState = State.WAIT_FOR_PLANKS_CRAFT;
                    delayCounter = craftingDelay.get() * 2;
                } else {
                    error("ERROR: Failed to place Oak Log for crafting!");
                    toggle();
                }
            }

            case WAIT_FOR_PLANKS_CRAFT -> {
                info("Waiting for planks recipe to be recognized");
                currentState = State.TAKE_PLANKS;
                delayCounter = craftingDelay.get();
            }

            case TAKE_PLANKS -> {
                info("Taking crafted planks");

                // Shift-click the result slot to take all planks at once
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    0, // Result slot
                    0,
                    SlotActionType.QUICK_MOVE,
                    mc.player
                );

                // Check if we still have oak logs and need more planks
                int oakLogs = InvUtils.find(Items.OAK_LOG).count();
                int oakPlanks = InvUtils.find(Items.OAK_PLANKS).count();

                info("After taking planks - Logs: " + oakLogs + ", Planks: " + oakPlanks);

                if (oakPlanks < 6 && oakLogs > 0) {
                    // Continue converting logs to planks
                    info("Need more planks, converting another log");
                    currentState = State.CONVERT_LOGS_TO_PLANKS;
                    delayCounter = craftingDelay.get();
                } else {
                    // Done with planks, close crafting table and recheck resources
                    info("Finished converting logs to planks, closing GUI");
                    if (mc.currentScreen != null) {
                        mc.player.closeHandledScreen();
                    }
                    needsOakPlanks = false;
                    currentState = State.CHECK_OAK_SIGN_RESOURCES;
                    delayCounter = actionDelay.get() * 3; // Longer delay after closing GUI
                }
            }

            case CONVERT_PLANKS_TO_STICKS -> {
                info("Converting Oak Planks to Sticks");

                // Check if we're in the right screen for crafting table
                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                    if (!(mc.currentScreen instanceof CraftingScreen)) {
                        error("ERROR: Not in crafting table screen for stick conversion!");
                        currentState = State.FIND_CRAFTING_TABLE;
                        delayCounter = actionDelay.get();
                        return;
                    }
                }

                // We need to place 2 planks vertically
                // For crafting table: slot 1 (top) and slot 4 (middle left)
                // For inventory: slot 1 (top) and slot 3 (bottom)

                if (recipeStep == 0) {
                    // Place first plank
                    int firstSlot = (mc.currentScreen instanceof CraftingScreen) ? 1 : 1;
                    if (placeItemInSlot(Items.OAK_PLANKS, firstSlot)) {
                        info("Placed first plank for stick crafting");
                        recipeStep++;
                        delayCounter = craftingDelay.get();
                    } else {
                        error("ERROR: Failed to place first Oak Plank for stick crafting!");
                        toggle();
                    }
                } else if (recipeStep == 1) {
                    // Place second plank
                    int secondSlot = (mc.currentScreen instanceof CraftingScreen) ? 4 : 3;
                    if (placeItemInSlot(Items.OAK_PLANKS, secondSlot)) {
                        info("Placed second plank for stick crafting");
                        recipeStep = 0; // Reset for next use
                        currentState = State.WAIT_FOR_STICKS_CRAFT;
                        delayCounter = craftingDelay.get() * 2;
                    } else {
                        error("ERROR: Failed to place second Oak Plank for stick crafting!");
                        toggle();
                    }
                }
            }

            case WAIT_FOR_STICKS_CRAFT -> {
                info("Waiting for sticks recipe to be recognized");
                currentState = State.TAKE_STICKS;
                delayCounter = craftingDelay.get();
            }

            case TAKE_STICKS -> {
                info("Taking crafted sticks");

                // Shift-click the result slot
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    0,
                    0,
                    SlotActionType.QUICK_MOVE,
                    mc.player
                );

                int sticks = InvUtils.find(Items.STICK).count();
                info("After taking sticks - Total sticks: " + sticks);

                // Done with sticks, close crafting and recheck resources
                info("Finished converting planks to sticks, closing GUI");
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                needsSticks = false;
                currentState = State.CHECK_OAK_SIGN_RESOURCES;
                delayCounter = actionDelay.get() * 3; // Longer delay after closing GUI
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
                // Check if the crafting table GUI has opened
                if (mc.currentScreen instanceof CraftingScreen) {
                    info("Crafting table GUI detected");

                    // Check if we need to convert materials for Oak Sign
                    if (needsOakPlanks) {
                        info("Proceeding to convert logs to planks");
                        currentState = State.CONVERT_LOGS_TO_PLANKS;
                    } else if (needsSticks) {
                        info("Proceeding to convert planks to sticks");
                        currentState = State.CONVERT_PLANKS_TO_STICKS;
                    } else {
                        info("Proceeding to craft items");
                        currentState = State.CRAFT_ITEMS;
                    }
                    delayCounter = actionDelay.get();
                    return;
                }

                // GUI not open yet, try opening it
                String purpose = needsOakPlanks ? "for plank conversion" :
                    (needsSticks ? "for stick conversion" : "for crafting");
                info("Attempting to open crafting table " + purpose);

                if (openCraftingTable(craftingTablePos)) {
                    info("Sent crafting table open request, waiting for GUI...");
                    // Stay in this state and wait for the GUI to open
                    delayCounter = actionDelay.get() * 2; // Longer delay for GUI to open
                } else {
                    error("ERROR: Failed to interact with crafting table!");
                    toggle();
                }
            }

            case CRAFT_ITEMS -> {
                // Safety check: if crafting method is CRAFTING_TABLE but we're not in the crafting GUI
                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE && !(mc.currentScreen instanceof CraftingScreen)) {
                    error("ERROR: Crafting table GUI not open when it should be! Retrying...");
                    currentState = State.FIND_CRAFTING_TABLE;
                    delayCounter = actionDelay.get();
                    return;
                }

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

                info("Starting to place items for recipe (" + currentRecipe.size() + " steps)");
                currentState = State.PLACE_CRAFTING_ITEMS;
                delayCounter = craftingDelay.get(); // Add delay before starting to place items
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
                // Just open inventory, we'll handle item selection after splitting
                currentState = State.OPEN_INVENTORY;
                delayCounter = actionDelay.get();
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
                Item item = getSelectedItem();
                int targetAmount = sellingAmount.get();

                // Initialize splitting if this is the first iteration
                if (stacksSplitSoFar == 0 && totalStacksToCreate == 0) {
                    if (!initializeSplitting(item, targetAmount)) {
                        splitAttempts++;
                        if (splitAttempts >= MAX_SPLIT_ATTEMPTS) {
                            info("Stack splitting initialization failed after max attempts");
                            mc.player.closeHandledScreen();

                            // Check if player is holding the item in their hand
                            ItemStack heldItem = mc.player.getMainHandStack();
                            if (heldItem.isEmpty() || heldItem.getItem() != getSelectedItem()) {
                                error("Player's hand is empty or holding wrong item after failed split, restarting sequence");
                                resetCounters();
                                currentState = State.IDLE;
                                delayCounter = loopDelay.get();
                            } else {
                                info("Player is holding item, proceeding to sell anyway");
                                currentState = State.EXECUTE_SELL_COMMAND;
                                delayCounter = actionDelay.get();
                            }
                            splitAttempts = 0;
                        } else {
                            delayCounter = actionDelay.get();
                        }
                        return;
                    }
                    info("Initialized splitting: " + totalStacksToCreate + " stacks to create");
                }

                // Perform one split operation
                if (stacksSplitSoFar < totalStacksToCreate) {
                    if (performOneSplit(item, targetAmount)) {
                        stacksSplitSoFar++;
                        info("Split progress: " + stacksSplitSoFar + "/" + totalStacksToCreate);

                        if (stacksSplitSoFar >= totalStacksToCreate) {
                            // Done splitting
                            info("Splitting complete!");
                            currentState = State.CLOSE_INVENTORY;
                            delayCounter = actionDelay.get();
                            splitAttempts = 0;
                        } else {
                            // Continue splitting with delay
                            delayCounter = splittingDelay.get();
                        }
                    } else {
                        error("Failed to perform split operation");
                        splitAttempts++;
                        if (splitAttempts >= MAX_SPLIT_ATTEMPTS) {
                            error("Too many split failures, aborting");
                            resetCounters();
                            currentState = State.IDLE;
                            delayCounter = loopDelay.get();
                        } else {
                            delayCounter = splittingDelay.get();
                        }
                    }
                } else {
                    // Shouldn't reach here but handle it anyway
                    currentState = State.CLOSE_INVENTORY;
                    delayCounter = actionDelay.get();
                }
            }

            case CLOSE_INVENTORY -> {
                mc.player.closeHandledScreen();

                // After splitting, find and select the first hotbar slot with the item
                Item targetItem = getSelectedItem();
                boolean foundInHotbar = false;

                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == targetItem && stack.getCount() >= sellingAmount.get()) {
                        InvUtils.swap(i, false);
                        info("Selected hotbar slot " + i + " with " + stack.getCount() + " items");
                        foundInHotbar = true;
                        break;
                    }
                }

                if (!foundInHotbar) {
                    error("Could not find split items in hotbar!");
                    resetCounters();
                    currentState = State.IDLE;
                    delayCounter = loopDelay.get();
                    return;
                }

                currentState = State.EXECUTE_SELL_COMMAND;
                delayCounter = actionDelay.get();
            }

            case EXECUTE_SELL_COMMAND -> {
                String price = calculatePrice();
                String command = "ah sell " + price;
                mc.player.networkHandler.sendChatCommand(command);
                info("Executing sell command with price: " + price);
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

                // Always open inventory to check for remaining items
                currentState = State.CHECK_REMAINING_ITEMS;
                delayCounter = actionDelay.get();
            }

            case CHECK_REMAINING_ITEMS -> {
                if (mc.currentScreen == null) {
                    mc.setScreen(new InventoryScreen(mc.player));
                    delayCounter = actionDelay.get();
                    return;
                }

                Item targetItem = getSelectedItem();
                int remainingCount = InvUtils.find(targetItem).count();

                info("Checking for remaining " + getItemDisplayName(targetItem) + " - Found: " + remainingCount);

                if (remainingCount >= sellingAmount.get()) {
                    // Check if items are in hotbar or inventory
                    boolean inHotbar = false;
                    int hotbarSlot = -1;

                    // Check hotbar (slots 36-44 in screen handler)
                    for (int i = 36; i < 45; i++) {
                        ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                        if (stack.getItem() == targetItem && stack.getCount() >= sellingAmount.get()) {
                            inHotbar = true;
                            hotbarSlot = i;
                            info("Found " + getItemDisplayName(targetItem) + " in hotbar slot " + i);
                            break;
                        }
                    }

                    if (inHotbar) {
                        // Items already in hotbar, just close inventory and select the slot
                        mc.player.closeHandledScreen();

                        // Convert screen handler slot to inventory slot (36-44 -> 0-8)
                        int inventoryHotbarSlot = hotbarSlot - 36;
                        InvUtils.swap(inventoryHotbarSlot, false);

                        info("Selected hotbar slot " + inventoryHotbarSlot);
                        currentState = State.EXECUTE_SELL_COMMAND;
                        delayCounter = actionDelay.get();
                    } else {
                        // Items in main inventory, need to move to hotbar
                        info("Items found in inventory, moving to hotbar");
                        currentState = State.MOVE_ITEMS_TO_HOTBAR;
                        delayCounter = actionDelay.get();
                    }
                } else {
                    // No more items to sell
                    info("No more items to sell, entering loop delay");
                    mc.player.closeHandledScreen();
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                }
            }

            case MOVE_ITEMS_TO_HOTBAR -> {
                Item targetItem = getSelectedItem();

                // Find the item in main inventory (slots 9-35)
                int sourceSlot = -1;
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                    if (stack.getItem() == targetItem && stack.getCount() >= sellingAmount.get()) {
                        sourceSlot = i;
                        break;
                    }
                }

                if (sourceSlot == -1) {
                    error("Could not find item to move to hotbar");
                    mc.player.closeHandledScreen();
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                    return;
                }

                // Find empty hotbar slot (slots 36-44)
                int targetHotbarSlot = -1;
                for (int i = 36; i < 45; i++) {
                    ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                    if (stack.isEmpty()) {
                        targetHotbarSlot = i;
                        break;
                    }
                }

                if (targetHotbarSlot == -1) {
                    // No empty slot, just use slot 36 (first hotbar slot)
                    targetHotbarSlot = 36;
                    info("No empty hotbar slot, using slot 36");
                }

                // Move item to hotbar
                InvUtils.move().from(sourceSlot).to(targetHotbarSlot);
                info("Moved item from slot " + sourceSlot + " to hotbar slot " + targetHotbarSlot);

                // Close inventory and select the hotbar slot
                mc.player.closeHandledScreen();

                // Convert to inventory hotbar slot (36-44 -> 0-8)
                int inventoryHotbarSlot = targetHotbarSlot - 36;
                InvUtils.swap(inventoryHotbarSlot, false);

                info("Selected hotbar slot " + inventoryHotbarSlot);
                currentState = State.EXECUTE_SELL_COMMAND;
                delayCounter = actionDelay.get();
            }

            case SEND_AH_SEARCH_COMMAND -> {
                Item item = getSelectedItem();
                String searchTerm;

                if (item == Items.OAK_SIGN) {
                    searchTerm = "sign";
                } else {
                    searchTerm = getItemDisplayName(item);
                }

                mc.player.networkHandler.sendChatCommand("ah " + searchTerm);
                info("Sent /ah " + searchTerm + " command");
                waitingForAuctionGui = true;
                currentState = State.WAIT_FOR_AH_GUI;
                delayCounter = actionDelay.get() * 3;
            }

            case WAIT_FOR_AH_GUI -> {
                if (isAuctionGuiOpen()) {
                    info("Auction GUI detected, parsing prices");
                    currentState = State.PARSE_AH_PRICES;
                    delayCounter = actionDelay.get();
                    waitingForAuctionGui = false;
                } else {
                    info("Still waiting for auction GUI to open...");
                }
            }

            case PARSE_AH_PRICES -> {
                if (parseAuctionPrices()) {
                    info("Successfully parsed adaptive price: " + adaptivePrice);
                    currentState = State.CLOSE_AH_GUI;
                    delayCounter = actionDelay.get();
                } else {
                    error("Failed to parse auction prices, using default 1000");
                    adaptivePrice = "1000";
                    currentState = State.CLOSE_AH_GUI;
                    delayCounter = actionDelay.get();
                }
            }

            case CLOSE_AH_GUI -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                info("Closed auction GUI, returning to resource check");

                // Now proceed with the normal flow
                Item item = getSelectedItem();
                if (item == Items.OAK_SIGN) {
                    currentState = State.CHECK_OAK_SIGN_RESOURCES;
                } else if (getResources.get() == ResourceMethod.INVENTORY) {
                    if (!hasRequiredResources(item)) {
                        toggle();
                        return;
                    }
                    if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                        currentState = State.FIND_CRAFTING_TABLE;
                    } else {
                        currentState = State.CRAFT_ITEMS;
                    }
                } else if (getResources.get() == ResourceMethod.BUY_ORDER) {
                    currentState = State.SEND_ORDERS_COMMAND;
                }
                delayCounter = actionDelay.get();
            }

            case LOOP_DELAY -> {
                sequenceCycleCount++; // Increment cycle counter BEFORE resetting
                info("Completed cycle " + sequenceCycleCount + " - entering loop delay");
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
                    info("Orders GUI detected, clicking on chest");
                    currentState = State.CLICK_CHEST_IN_ORDERS;
                    delayCounter = actionDelay.get();
                } else {
                    info("Still waiting for orders GUI to open...");
                }
            }

            case CLICK_CHEST_IN_ORDERS -> {
                if (clickItemInOrdersGui(Items.CHEST)) {
                    info("Clicked chest in orders GUI, waiting for Your Orders menu");
                    currentState = State.WAIT_FOR_YOUR_ORDERS_GUI;
                    delayCounter = actionDelay.get() * 2;
                } else {
                    error("ERROR: Could not find chest in orders GUI!");
                    toggle();
                }
            }

            case WAIT_FOR_YOUR_ORDERS_GUI -> {
                if (isYourOrdersGuiOpen()) {
                    info("Your Orders GUI detected, clicking on Oak Log order");
                    currentState = State.CLICK_OAK_LOG_ORDER;
                    delayCounter = actionDelay.get();
                } else {
                    info("Still waiting for Your Orders GUI to open...");
                }
            }

            case CLICK_OAK_LOG_ORDER -> {
                if (clickItemInYourOrdersGui(Items.OAK_LOG)) {
                    info("Clicked Oak Log order, waiting for Edit Order menu");
                    currentState = State.WAIT_FOR_EDIT_ORDER_GUI;
                    delayCounter = actionDelay.get() * 2;
                } else {
                    error("ERROR: Could not find Oak Log order in Your Orders GUI!");
                    toggle();
                }
            }

            case WAIT_FOR_EDIT_ORDER_GUI -> {
                if (isEditOrderGuiOpen()) {
                    info("Edit Order GUI detected, clicking on collect chest");
                    currentState = State.CLICK_COLLECT_CHEST;
                    delayCounter = actionDelay.get();
                } else {
                    info("Still waiting for Edit Order GUI to open...");
                }
            }

            case CLICK_COLLECT_CHEST -> {
                if (clickItemInEditOrderGui(Items.CHEST)) {
                    info("Clicked collect chest, collecting Oak Logs");
                    currentState = State.COLLECT_OAK_LOGS;
                    delayCounter = actionDelay.get();
                } else {
                    error("ERROR: Could not find chest in Edit Order GUI!");
                    toggle();
                }
            }

            case COLLECT_OAK_LOGS -> {
                if (shiftClickOakLogsInGui()) {
                    info("Successfully collected Oak Logs, closing GUI");
                    currentState = State.CLOSE_BUY_ORDER_GUI;
                    delayCounter = actionDelay.get();
                } else {
                    info("No Oak Logs found to collect, closing GUI");
                    currentState = State.CLOSE_BUY_ORDER_GUI;
                    delayCounter = actionDelay.get();
                }
            }

            case CLOSE_BUY_ORDER_GUI -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                info("Closed buy order GUI, returning to resource check");
                currentState = State.CHECK_OAK_SIGN_RESOURCES;
                delayCounter = actionDelay.get();
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
        } else if (item == Items.OAK_SIGN) {
            // Oak Sign requires 6 oak planks + 1 stick
            // But we handle this specially in CHECK_OAK_SIGN_RESOURCES
            resources.put(Items.OAK_PLANKS, 6);
            resources.put(Items.STICK, 1);
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
        } else if (item == Items.OAK_SIGN) {
            // Oak Sign recipe: 6 planks + 1 stick
            // Top row: 3 planks (slots 1, 2, 3)
            // Middle row: 3 planks (slots 4, 5, 6)
            // Bottom row: 1 stick in center (slot 8)
            steps.add(new CraftingStep(Items.OAK_PLANKS, 1));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 2));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 3));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 4));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 5));
            steps.add(new CraftingStep(Items.OAK_PLANKS, 6));
            steps.add(new CraftingStep(Items.STICK, 8));
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

    private boolean initializeSplitting(Item item, int targetAmount) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (!(mc.currentScreen instanceof InventoryScreen)) return false;

        info("Initializing splitting for " + getItemDisplayName(item) + " into stacks of " + targetAmount);

        // Count total items
        int totalCount = InvUtils.find(item).count();
        if (totalCount == 0) {
            info("No items to split");
            return false;
        }

        // Calculate stacks to create
        totalStacksToCreate = totalCount / targetAmount;
        if (totalStacksToCreate == 0) {
            info("Not enough items to create even one stack of " + targetAmount);
            return false;
        }

        // Find all item slots
        sourceItemSlots.clear();
        for (int i = 9; i < 45; i++) {
            ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
            if (stack.getItem() == item && stack.getCount() > 0) {
                sourceItemSlots.add(i);
            }
        }

        if (sourceItemSlots.isEmpty()) {
            info("No item stacks found");
            return false;
        }

        // Find empty slots (hotbar first, then inventory)
        targetEmptySlots.clear();
        for (int i = 36; i < 45; i++) { // Hotbar
            ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                targetEmptySlots.add(i);
            }
        }
        for (int i = 9; i < 36; i++) { // Main inventory
            ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                targetEmptySlots.add(i);
            }
        }

        if (targetEmptySlots.size() < totalStacksToCreate - 1) {
            info("Not enough empty slots (need " + (totalStacksToCreate - 1) + ", have " + targetEmptySlots.size() + ")");
            return false;
        }

        // Initialize tracking
        stacksSplitSoFar = 0;
        currentSourceSlot = sourceItemSlots.get(0);
        itemsRemainingInCurrentSource = mc.player.currentScreenHandler.getSlot(currentSourceSlot).getStack().getCount();

        return true;
    }

    private boolean performOneSplit(Item item, int targetAmount) {
        if (mc.player == null || mc.interactionManager == null) return false;

        // Check if this is the last stack - if so, just leave it where it is
        if (stacksSplitSoFar >= totalStacksToCreate - 1) {
            info("Last stack reached, leaving it in place");
            return true;
        }

        // Find next source slot if current one is exhausted
        while (itemsRemainingInCurrentSource < targetAmount) {
            int currentIndex = sourceItemSlots.indexOf(currentSourceSlot);
            if (currentIndex + 1 >= sourceItemSlots.size()) {
                error("Ran out of source slots unexpectedly");
                return false;
            }
            currentSourceSlot = sourceItemSlots.get(currentIndex + 1);
            itemsRemainingInCurrentSource = mc.player.currentScreenHandler.getSlot(currentSourceSlot).getStack().getCount();
        }

        // Get target slot from our list
        int targetSlot = targetEmptySlots.get(stacksSplitSoFar);

        // Pick up source stack
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            currentSourceSlot,
            0,
            SlotActionType.PICKUP,
            mc.player
        );

        // Right-click to place target amount
        for (int i = 0; i < targetAmount; i++) {
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                targetSlot,
                1,
                SlotActionType.PICKUP,
                mc.player
            );
        }

        // Put remaining back
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            currentSourceSlot,
            0,
            SlotActionType.PICKUP,
            mc.player
        );

        itemsRemainingInCurrentSource -= targetAmount;
        info("Created stack of " + targetAmount + " in slot " + targetSlot);

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

    private boolean isYourOrdersGuiOpen() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        return SMPUtils.isYourOrdersMenu(screen);
    }

    private boolean isEditOrderGuiOpen() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        return SMPUtils.isEditOrderMenu(screen);
    }

    private boolean clickItemInOrdersGui(Item targetItem) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return false;

        var handler = screen.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == targetItem) {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    i,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );
                info("Clicked " + getItemDisplayName(targetItem) + " in slot " + i);
                return true;
            }
        }

        return false;
    }

    private boolean clickItemInYourOrdersGui(Item targetItem) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return false;

        var handler = screen.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == targetItem) {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    i,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );
                info("Clicked " + getItemDisplayName(targetItem) + " order in slot " + i);
                return true;
            }
        }

        return false;
    }

    private boolean clickItemInEditOrderGui(Item targetItem) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return false;

        var handler = screen.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == targetItem) {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    i,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );
                info("Clicked " + getItemDisplayName(targetItem) + " in Edit Order GUI, slot " + i);
                return true;
            }
        }

        return false;
    }

    private boolean shiftClickOakLogsInGui() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return false;

        var handler = screen.getScreenHandler();

        // Look for oak logs in the GUI and take only the FIRST stack found
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == Items.OAK_LOG) {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    i,
                    0,
                    SlotActionType.QUICK_MOVE, // Shift-click
                    mc.player
                );
                info("Shift-clicked ONE Oak Log stack in slot " + i);
                return true; // Return immediately after taking one stack
            }
        }

        return false; // No oak logs found
    }

    private boolean isAuctionGuiOpen() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        return SMPUtils.isAuctionMenu(screen);
    }

    private boolean parseAuctionPrices() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            error("Not in a container screen!");
            return false;
        }

        var handler = screen.getScreenHandler();
        List<Double> prices = new ArrayList<>();

        int startSlot = antiBait.get() ? 3 : 0;
        int endSlot = 10; // Scan slots 0-9 (first row has 10 slots)

        info("Parsing auction prices from slots " + startSlot + " to " + (endSlot - 1));

        for (int i = startSlot; i < endSlot; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            List<net.minecraft.text.Text> lore = SMPUtils.getItemLore(stack);
            if (lore == null || lore.isEmpty()) continue;

            for (net.minecraft.text.Text line : lore) {
                String text = line.getString();
                if (text.contains("Price:")) {
                    String priceStr = SMPUtils.extractPrice(text);
                    if (priceStr != null) {
                        double priceValue = SMPUtils.parsePrice(priceStr);
                        if (priceValue > 0) {
                            prices.add(priceValue);
                            info("Found price in slot " + i + ": " + priceStr);
                        }
                    }
                    break;
                }
            }
        }

        if (prices.isEmpty()) {
            error("No prices found in auction house!");
            return false;
        }

        double selectedPrice;

        if (adaptiveFixed.get()) {
            selectedPrice = prices.stream().min(Double::compare).orElse(1000.0);
            info("Using lowest price: " + selectedPrice);
        } else if (adaptiveRandom.get()) {
            double minPrice = prices.stream().min(Double::compare).orElse(1000.0);
            double maxPrice = prices.stream().max(Double::compare).orElse(1000.0);
            selectedPrice = minPrice + (Math.random() * (maxPrice - minPrice));
            info("Using random price between " + minPrice + " and " + maxPrice + ": " + selectedPrice);

            if (adaptiveDecimal.get()) {
                double decimalAdd = Math.random() * 0.99;
                selectedPrice += decimalAdd;
                info("Added decimal: " + decimalAdd);
            }
        } else {
            selectedPrice = prices.get(0);
            info("Using first price: " + selectedPrice);
        }

        // Apply the adaptive subtract
        double subtractAmount = SMPUtils.parsePrice(adaptiveSubtract.get());
        if (subtractAmount > 0) {
            selectedPrice -= subtractAmount;
            info("Subtracted " + adaptiveSubtract.get() + " from price. New price: " + selectedPrice);

            // Ensure price doesn't go negative
            if (selectedPrice < 0) {
                selectedPrice = 1.0;
                info("Price was negative after subtraction, set to minimum: 1");
            }
        }

        adaptivePrice = SMPUtils.formatPrice(selectedPrice, adaptiveRandom.get() && adaptiveDecimal.get());
        return true;
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
