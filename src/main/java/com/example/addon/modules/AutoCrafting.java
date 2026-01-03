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
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
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
    private final SettingGroup sgListingSettings = settings.createGroup("Listing Settings");
    private final SettingGroup sgAuctionHouse = settings.createGroup("Auction House");
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

    public enum MaxListedMode {
        PAUSE_SEQUENCE("Pause sequence (ticks)"),
        CANCEL_LISTED_ITEMS("Cancel Listed Items");

        private final String name;

        MaxListedMode(String name) {
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

    // Listing Settings
    private final Setting<MaxListedMode> maxListedMode = sgListingSettings.add(new EnumSetting.Builder<MaxListedMode>()
        .name("max-listed-item")
        .description("Action to take when max listings reached")
        .defaultValue(MaxListedMode.PAUSE_SEQUENCE)
        .onModuleActivated(setting -> {
            if (setting.get() == null) setting.set(MaxListedMode.PAUSE_SEQUENCE);
        })
        .build());

    private final Setting<Integer> minimumDelay = sgListingSettings.add(new IntSetting.Builder()
        .name("minimum-delay")
        .description("Minimum delay in ticks")
        .defaultValue(6000)
        .min(1)
        .max(432000)
        .sliderMax(432000)
        .visible(() -> maxListedMode.get() == MaxListedMode.PAUSE_SEQUENCE)
        .build());

    private final Setting<Integer> maximumDelay = sgListingSettings.add(new IntSetting.Builder()
        .name("maximum-delay")
        .description("Maximum delay in ticks")
        .defaultValue(12000)
        .min(1)
        .max(432000)
        .sliderMax(432000)
        .visible(() -> maxListedMode.get() == MaxListedMode.PAUSE_SEQUENCE)
        .build());

    private final Setting<PriceMode> priceMode = sgItem.add(new EnumSetting.Builder<PriceMode>()
        .name("price")
        .description("How to determine the selling price")
        .defaultValue(PriceMode.FIXED)
        .onModuleActivated(setting -> {
            if (setting.get() == null) setting.set(PriceMode.FIXED);
        })
        .build());


    // Auction House Settings

    private final Setting<Integer> ahFetchPrice = sgAuctionHouse.add(new IntSetting.Builder()
        .name("AH-fetch-price")
        .description("How often to fetch new auction house prices (in number of sequences)")
        .defaultValue(3)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Integer> fetchBetweenBatch = sgAuctionHouse.add(new IntSetting.Builder()
        .name("price-check-interval")
        .description("Fetch updated prices every X items sold (1 = fetch after each sale)")
        .defaultValue(9)
        .min(1)
        .max(64)
        .sliderMax(64)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Boolean> fetchAfterCrafting = sgAuctionHouse.add(new BoolSetting.Builder()
        .name("fetch-after-crafting")
        .description("Fetch prices after crafting instead of before (ensures accurate prices for large batches)")
        .defaultValue(false)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Boolean> antiBait = sgAuctionHouse.add(new BoolSetting.Builder()
        .name("anti-bait")
        .description("Skip the first X items in the auction house to avoid bait listings")
        .defaultValue(false)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Integer> skipSlotsAmount = sgAuctionHouse.add(new IntSetting.Builder()
        .name("skip-slots-amount")
        .description("Number of slots to skip at the beginning of auction house")
        .defaultValue(3)
        .min(1)
        .max(8)
        .sliderMax(8)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE && antiBait.get())
        .build());

    private final Setting<List<String>> ignorePlayerListings = sgAuctionHouse.add(new StringListSetting.Builder()
        .name("ignore-player-listings")
        .description("Ignore auction listings from these players when fetching prices")
        .defaultValue(List.of())
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
        .build());

    private final Setting<Boolean> ignoreOwn = sgAuctionHouse.add(new BoolSetting.Builder()
        .name("ignore-own")
        .description("Ignore your own auction listings when fetching prices")
        .defaultValue(true)
        .visible(() -> priceMode.get() == PriceMode.ADAPTIVE)
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
        .name("random-decimal")
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

    private final Setting<String> adaptiveMinimumLowest = sgItem.add(new StringSetting.Builder()
        .name("adaptive-minimum-lowest")
        .description("If all fetched prices are below this value, use this instead (supports K, M, B)")
        .defaultValue("0")
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
        FINALIZE_HOTBAR_MOVE,
        SELL_ALL_HOTBAR_ITEMS,
        PAUSE_FOR_MAX_LISTINGS,
        LOOP_DELAY,
        FETCH_PRICES_AFTER_CRAFTING,
        FETCH_PRICES_BETWEEN_BATCH,

        // Cancel Listed Items states
        SEND_AH_PLAYER_COMMAND,
        WAIT_FOR_PLAYER_AH_GUI,
        CLICK_YOUR_ITEMS_CHEST,
        WAIT_FOR_YOUR_ITEMS_GUI,
        PARSE_AND_CANCEL_EXPENSIVE_ITEMS,
        WAIT_AFTER_CANCEL_CLICK,
        CLOSE_YOUR_ITEMS_GUI,
        CLOSE_PLAYER_AH_GUI,
        CHECK_FOR_CANCELED_ITEMS,
        SPLIT_CANCELED_ITEMS,
        SELL_CANCELED_ITEMS,

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
    private String playerName = null;
    private int pendingHotbarSlot = -1;
    private int pendingSourceSlot = -1;

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

    // Hotbar selling variables
    private List<Integer> hotbarSlotsWithItems = new ArrayList<>();
    private int currentHotbarSellIndex = 0;
    private int itemsSoldInBatch = 0;

    // Max listings pause variables
    private boolean pausedForMaxListings = false;
    private int pauseTicksRemaining = 0;

    // Cancel Listed Items variables
    private List<Integer> itemsToCancelSlots = new ArrayList<>();
    private List<Double> itemsToCancelPrices = new ArrayList<>();
    private int canceledItemsCount = 0;
    private static final int TARGET_CANCEL_COUNT = 10;
    private Set<Integer> alreadySoldSlots = new HashSet<>();
    private boolean hasCanceledItems = false;
    private int cancelAttemptSlot = -1;

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

    private int countItemsInInventory(Item item) {
        if (mc.player == null) return 0;

        int totalCount = 0;

        // Count items in entire inventory (includes hotbar, slots 0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) {
                totalCount += stack.getCount();
            }
        }

        return totalCount;
    }

    private List<Integer> findValidStacksInInventory(Item item, int targetAmount) {
        List<Integer> validSlots = new ArrayList<>();

        if (mc.player == null) return validSlots;

        // Check all inventory slots (0-35, includes hotbar 0-8)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item && stack.getCount() >= targetAmount) {
                validSlots.add(i);
            }
        }

        return validSlots;
    }

    /**
     * Gets the validated crafting amount without any item-specific limits.
     * Oak Signs are no longer limited to 16.
     *
     * @return The crafting amount as an integer
     */
    private int getValidatedCraftAmount() {
        try {
            int amount = Integer.parseInt(craftingAmount.get());
            return amount;
        } catch (NumberFormatException e) {
            error("Invalid crafting amount! Using default: 64");
            return 64;
        }
    }

    /**
     * Calculates the actual number of crafting cycles needed based on the item type.
     * Oak Signs craft 3 at a time, other items craft 1 at a time.
     *
     * @param item The item being crafted
     * @param targetAmount The target number of items to craft
     * @return The number of crafting cycles needed
     */
    private int getActualCraftingCycles(Item item, int targetAmount) {
        // Oak Signs craft 3 at a time
        if (item == Items.OAK_SIGN) {
            return (targetAmount + 2) / 3; // Round up
        }
        // Other items craft 1 at a time
        return targetAmount;
    }

    private String calculatePrice() {
        switch (priceMode.get()) {
            case FIXED -> {
                return fixedPrice.get();
            }
            case RANDOM -> {
                try {
                    double min = parseFormattedPrice(minRandomPrice.get());
                    double max = parseFormattedPrice(maxRandomPrice.get());

                    if (min > max) {
                        double temp = min;
                        min = max;
                        max = temp;
                    }

                    double randomValue = min + (Math.random() * (max - min));

                    if (decimalRandom.get()) {
                        return formatPriceForDisplay(randomValue, true);
                    } else {
                        return formatPriceForDisplay(randomValue, false);
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



    // Helper method to parse price strings (handles K, M, B suffixes)
    private double parseFormattedPrice(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) return 0;

        priceStr = priceStr.trim();
        double multiplier = 1.0;
        String numericPart = priceStr;

        // Check for suffix
        if (priceStr.length() > 0) {
            char lastChar = priceStr.charAt(priceStr.length() - 1);
            if (Character.isLetter(lastChar)) {
                numericPart = priceStr.substring(0, priceStr.length() - 1);
                switch (Character.toLowerCase(lastChar)) {
                    case 'k' -> multiplier = 1000.0;
                    case 'm' -> multiplier = 1000000.0;
                    case 'b' -> multiplier = 1000000000.0;
                }
            }
        }

        return Double.parseDouble(numericPart) * multiplier;
    }

    // Helper method to format price back to string
    private String formatPriceForDisplay(double price, boolean useDecimals) {
        String suffix = "";
        double value = price;

        if (price >= 1000000000.0) {
            value = price / 1000000000.0;
            suffix = "B";
        } else if (price >= 1000000.0) {
            value = price / 1000000.0;
            suffix = "M";
        } else if (price >= 1000.0) {
            value = price / 1000.0;
            suffix = "K";
        }

        if (useDecimals && !suffix.isEmpty()) {
            return String.format("%.2f%s", value, suffix);
        } else if (!suffix.isEmpty()) {
            return String.format("%d%s", (int) Math.round(value), suffix);
        } else {
            if (useDecimals) {
                return String.format("%.2f", value);
            } else {
                return String.format("%d", (int) Math.round(value));
            }
        }
    }

    private String extractPriceFromLine(String line) {
        if (line == null || line.isEmpty()) return null;

        info("      [extractPrice] Parsing line: '" + line + "'");

        // Pattern 1: Look for "Price:" or similar, then extract everything after it
        String lowerLine = line.toLowerCase();
        int priceIndex = -1;

        if (lowerLine.contains("price:")) {
            priceIndex = lowerLine.indexOf("price:") + 6; // Skip "price:"
        } else if (lowerLine.contains("ᴘʀɪᴄᴇ:")) {
            priceIndex = line.indexOf("ᴘʀɪᴄᴇ:") + 6;
        }

        if (priceIndex != -1) {
            String afterPrice = line.substring(priceIndex).trim();
            info("      [extractPrice] After 'Price:': '" + afterPrice + "'");

            // Skip any dollar sign if present
            if (afterPrice.startsWith("$")) {
                afterPrice = afterPrice.substring(1).trim();
            }

            // Extract the price number (supports formats like: "1M", "10K", "1000", "1.5M")
            StringBuilder price = new StringBuilder();
            boolean hasDigit = false;

            for (int i = 0; i < afterPrice.length(); i++) {
                char c = afterPrice.charAt(i);

                if (Character.isDigit(c)) {
                    price.append(c);
                    hasDigit = true;
                } else if (c == '.' && hasDigit) {
                    // Allow decimal point if we already have digits
                    price.append(c);
                } else if ((c == 'K' || c == 'k' || c == 'M' || c == 'm' || c == 'B' || c == 'b') && hasDigit) {
                    // Found suffix, add it and stop
                    price.append(c);
                    break;
                } else if (Character.isWhitespace(c) || c == ')' || c == ']' || c == ',') {
                    // Stop at whitespace or special characters (unless we haven't found anything yet)
                    if (hasDigit) break;
                }
            }

            String result = price.toString();
            info("      [extractPrice] Extracted: '" + result + "'");

            if (result.length() > 0 && hasDigit) {
                return result;
            }
        }

        // Pattern 2: Fallback - look for any number with K/M/B suffix in the line
        info("      [extractPrice] Trying fallback pattern...");
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            // Remove special characters but keep numbers, dots, and K/M/B
            String cleaned = part.replaceAll("[^0-9.KMBkmb]", "");
            if (cleaned.matches("\\d+\\.?\\d*[KMBkmb]")) {
                info("      [extractPrice] Fallback found: '" + cleaned + "'");
                return cleaned;
            }
        }

        info("      [extractPrice] No price found in line");
        return null;
    }




    @Override
    public void onActivate() {
        currentState = State.IDLE;
        resetCounters();
        hotbarSlotsWithItems.clear();
        currentHotbarSellIndex = 0;

        // Capture player name for ignoring own auctions
        if (mc.player != null) {
            playerName = mc.player.getGameProfile().name();
            info("Player name captured: " + playerName);
        }

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

    // Replace the onMessageReceive method in AutoCrafting.java with this fixed version:

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        if (mc.player == null) return;

        String message = event.getMessage().getString();

        // Check for "already bought" message during canceling
        if (currentState == State.WAIT_AFTER_CANCEL_CLICK &&
            message.contains("This item was already bought")) {
            if (cancelAttemptSlot != -1) {
                alreadySoldSlots.add(cancelAttemptSlot);
                info("Item in slot " + cancelAttemptSlot + " was already sold, skipping");
                cancelAttemptSlot = -1;
            }
        }

        // Check for max listings message
        if (message.contains("You have too many listed items")) {
            if (maxListedMode.get() == MaxListedMode.PAUSE_SEQUENCE) {
                // Calculate random delay between min and max
                int minTicks = minimumDelay.get();
                int maxTicks = maximumDelay.get();

                // Ensure min is not greater than max
                if (minTicks > maxTicks) {
                    int temp = minTicks;
                    minTicks = maxTicks;
                    maxTicks = temp;
                }

                // Calculate random delay
                pauseTicksRemaining = minTicks + (int)(Math.random() * (maxTicks - minTicks + 1));

                // Convert to minutes for display
                double minutes = pauseTicksRemaining / 1200.0;

                info(String.format("Max listings reached! Pausing sequence for %.2f minutes (%d ticks)",
                    minutes, pauseTicksRemaining));

                // Close any open GUI
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }

                // Set pause state
                pausedForMaxListings = true;
                currentState = State.PAUSE_FOR_MAX_LISTINGS;
                delayCounter = 0;
            } else if (maxListedMode.get() == MaxListedMode.CANCEL_LISTED_ITEMS) {
                // FIX: Start the cancel sequence properly
                info("Max listings reached! Starting Cancel Listed Items sequence...");

                // Close any open GUI
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }

                // Reset cancel variables
                itemsToCancelSlots.clear();
                itemsToCancelPrices.clear();
                canceledItemsCount = 0;
                alreadySoldSlots.clear();
                hasCanceledItems = false;
                cancelAttemptSlot = -1;

                // Start cancel sequence
                currentState = State.SEND_AH_PLAYER_COMMAND;
                delayCounter = actionDelay.get();
            }
        }
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
        itemsSoldInBatch = 0;

        // NOTE: Do NOT reset sequenceCycleCount here - it persists across sequences
        // NOTE: Do NOT reset adaptivePrice here - it persists until refresh is needed

        // Reset cancel variables (but keep hasCanceledItems if still processing)
        if (currentState != State.CHECK_FOR_CANCELED_ITEMS &&
            currentState != State.SPLIT_CANCELED_ITEMS &&
            currentState != State.SELL_CANCELED_ITEMS) {
            itemsToCancelSlots.clear();
            itemsToCancelPrices.clear();
            canceledItemsCount = 0;
            alreadySoldSlots.clear();
            cancelAttemptSlot = -1;
        }
    }

    private void cleanupControls() {
        SMPUtils.stopBreaking();
        SMPUtils.stopMoving();
        SMPUtils.setJumping(false);
    }

    private void handleState() {
        if (pausedForMaxListings && currentState == State.PAUSE_FOR_MAX_LISTINGS) {

        }

        switch (currentState) {
// Replace the IDLE case in the handleState() method with this:

            case IDLE -> {
                targetCraftAmount = getValidatedCraftAmount();
                Item item = getSelectedItem();

                // Calculate actual crafting cycles needed
                int actualCycles = getActualCraftingCycles(item, targetCraftAmount);
                info("Target: " + targetCraftAmount + " items, will craft " + actualCycles + " times");

                if (craftingMethod.get() == CraftingMethod.INVENTORY) {
                    if (!canCraftInInventory(item)) {
                        error("ERROR: Can't craft " + getItemDisplayName(item) + " in inventory, use \"Crafting Table\" method!");
                        toggle();
                        return;
                    }
                }

                // ===== NEW: Check for existing items to sell first =====
                int existingItemCount = countItemsInInventory(item);
                int sellingAmountValue = sellingAmount.get();

                if (existingItemCount >= sellingAmountValue) {
                    info("Found " + existingItemCount + " existing " + getItemDisplayName(item) + " in inventory/hotbar");
                    info("Will sell existing items before crafting");

                    // Check if items are already in valid selling stacks
                    List<Integer> validStacks = findValidStacksInInventory(item, sellingAmountValue);

                    if (!validStacks.isEmpty()) {
                        // Items are already in proper stacks, skip splitting
                        info("Items already in valid stacks (" + validStacks.size() + " stacks), skipping split phase");

                        // Move items to hotbar if needed
                        boolean hasHotbarItem = false;
                        for (int slot : validStacks) {
                            if (slot < 9) {
                                InvUtils.swap(slot, false);
                                info("Selected existing item in hotbar slot " + slot);
                                hasHotbarItem = true;
                                break;
                            }
                        }

                        if (!hasHotbarItem) {
                            // Need to move items to hotbar
                            info("Opening inventory to move existing items to hotbar");
                            if (mc.currentScreen == null) {
                                mc.setScreen(new InventoryScreen(mc.player));
                            }
                            currentState = State.MOVE_ITEMS_TO_HOTBAR;
                            delayCounter = actionDelay.get();
                            return;
                        }

                        // Items ready in hotbar, proceed to sell
                        currentState = State.EXECUTE_SELL_COMMAND;
                        delayCounter = actionDelay.get();
                        return;
                    } else {
                        // Items need splitting first
                        info("Items need splitting before selling");
                        if (mc.currentScreen == null) {
                            mc.setScreen(new InventoryScreen(mc.player));
                        }
                        currentState = State.SPLIT_STACK;
                        delayCounter = actionDelay.get();
                        return;
                    }
                } else if (existingItemCount > 0) {
                    info("Found " + existingItemCount + " existing " + getItemDisplayName(item) + " (less than selling amount of " + sellingAmountValue + "), will include in batch");
                }
                // ===== END NEW CODE =====

                // Check if we need to fetch adaptive pricing
                if (priceMode.get() == PriceMode.ADAPTIVE) {
                    int fetchInterval = ahFetchPrice.get();
                    boolean shouldFetch = (adaptivePrice == null || sequenceCycleCount >= fetchInterval);

                    // If fetchAfterCrafting is enabled, skip fetching now and do it after splitting
                    if (shouldFetch && !fetchAfterCrafting.get()) {
                        info("Adaptive pricing enabled, fetching auction house prices... (Cycle: " + sequenceCycleCount + ", Interval: " + fetchInterval + ")");
                        sequenceCycleCount = 0;
                        adaptivePrice = null;
                        currentState = State.SEND_AH_SEARCH_COMMAND;
                        delayCounter = actionDelay.get();
                        return;
                    } else if (shouldFetch && fetchAfterCrafting.get()) {
                        info("Adaptive pricing enabled - will fetch after crafting (Cycle: " + sequenceCycleCount + ", Interval: " + fetchInterval + ")");
                        sequenceCycleCount = 0;
                        adaptivePrice = null; // Clear old price to trigger fetch later
                    } else {
                        info("Using cached adaptive price: " + adaptivePrice + " (Cycle: " + sequenceCycleCount + "/" + fetchInterval + ")");
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

                // Calculate required resources based on crafting amount
                // Each craft makes 3 signs, requires 6 planks + 1 stick
                int requiredSigns = (targetCraftAmount + 2) / 3; // Round up
                int requiredPlanks = requiredSigns * 6;  // Planks needed for signs
                int requiredSticks = requiredSigns;       // Sticks needed for signs

                // Calculate how many sticks we still need to craft
                int sticksToMake = Math.max(0, requiredSticks - sticks);
                // Each stick recipe uses 2 planks and makes 4 sticks
                int planksForSticks = ((sticksToMake + 3) / 4) * 2; // Round up to nearest recipe, 2 planks per recipe

                // Total planks needed = planks for signs + planks for sticks
                int totalPlanksNeeded = requiredPlanks + planksForSticks;

                // Calculate logs needed (each log makes 4 planks)
                int planksStillNeeded = Math.max(0, totalPlanksNeeded - oakPlanks);
                int requiredLogs = (planksStillNeeded + 3) / 4; // Round up

                info("Required for " + targetCraftAmount + " signs: Total Planks=" + totalPlanksNeeded + " (Signs: " + requiredPlanks + ", Sticks: " + planksForSticks + "), Sticks=" + requiredSticks + ", Logs=" + requiredLogs);

                // Check if we need to get resources via Buy Order
                if (getResources.get() == ResourceMethod.BUY_ORDER && oakLogs < requiredLogs) {
                    oakSignCheckAttempts++;
                    if (oakSignCheckAttempts > MAX_OAK_SIGN_CHECK_ATTEMPTS) {
                        error("ERROR: Failed to prepare Oak Sign resources after " + MAX_OAK_SIGN_CHECK_ATTEMPTS + " attempts!");
                        toggle();
                        return;
                    }
                    info("Not enough Oak Logs (need " + requiredLogs + "), using Buy Order to obtain resources");
                    currentState = State.SEND_ORDERS_COMMAND;
                    delayCounter = actionDelay.get();
                    return;
                }

                // For inventory method, check if we have enough resources
                if (getResources.get() == ResourceMethod.INVENTORY) {
                    if (oakLogs < requiredLogs) {
                        error("ERROR: Not enough Oak Logs! Need " + requiredLogs + ", have " + oakLogs);
                        toggle();
                        return;
                    }
                }

                // Check if we need to make planks
                if (oakPlanks < totalPlanksNeeded) {
                    info("Need to convert Oak Logs to Oak Planks (need " + totalPlanksNeeded + ", have " + oakPlanks + ")");
                    needsOakPlanks = true;
                    needsSticks = false;
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
                if (sticks < requiredSticks) {
                    info("Need to convert Oak Planks to Sticks (need " + requiredSticks + ", have " + sticks + ")");
                    needsOakPlanks = false;
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

                // All resources ready
                info("All Oak Sign resources ready (Planks: " + oakPlanks + ", Sticks: " + sticks + "), proceeding to craft Oak Signs");
                oakSignCheckAttempts = 0;
                needsOakPlanks = false;
                needsSticks = false;

                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                    currentState = State.FIND_CRAFTING_TABLE;
                } else {
                    currentState = State.CRAFT_ITEMS;
                }
                delayCounter = actionDelay.get();
            }

            case CONVERT_LOGS_TO_PLANKS -> {
                info("Converting Oak Logs to Oak Planks (batch crafting)");

                // Check if we're in the right screen for crafting table
                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                    if (!(mc.currentScreen instanceof CraftingScreen)) {
                        error("ERROR: Not in crafting table screen for plank conversion!");
                        currentState = State.FIND_CRAFTING_TABLE;
                        delayCounter = actionDelay.get();
                        return;
                    }
                }

                // Place oak log in slot 1 - just place one, we'll shift-click repeatedly
                if (placeItemInSlot(Items.OAK_LOG, 1)) {
                    currentState = State.WAIT_FOR_PLANKS_CRAFT;
                    delayCounter = craftingDelay.get();
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
                int oakLogs = InvUtils.find(Items.OAK_LOG).count();
                int oakPlanks = InvUtils.find(Items.OAK_PLANKS).count();

                // Calculate how many more planks we need
                int requiredSigns = (targetCraftAmount + 2) / 3;
                int requiredPlanksForSigns = requiredSigns * 6;
                int requiredSticks = requiredSigns;
                int sticksToMake = Math.max(0, requiredSticks - InvUtils.find(Items.STICK).count());
                int planksForSticks = ((sticksToMake + 3) / 4) * 2;
                int totalPlanksNeeded = requiredPlanksForSigns + planksForSticks;
                int planksStillNeeded = totalPlanksNeeded - oakPlanks;

                info("Taking planks - Need " + planksStillNeeded + " more planks, have " + oakLogs + " logs");

                // Shift-click the result slot to take planks
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    0, // Result slot
                    0,
                    SlotActionType.QUICK_MOVE,
                    mc.player
                );

                // Check if we still need more planks AND have logs
                int newOakPlanks = InvUtils.find(Items.OAK_PLANKS).count();
                int newOakLogs = InvUtils.find(Items.OAK_LOG).count();
                int newPlanksNeeded = totalPlanksNeeded - newOakPlanks;

                info("After taking planks - Logs: " + newOakLogs + ", Planks: " + newOakPlanks + ", Still need: " + newPlanksNeeded);

                if (newPlanksNeeded > 0 && newOakLogs > 0) {
                    // Continue converting logs to planks
                    info("Need more planks, converting another log");
                    currentState = State.CONVERT_LOGS_TO_PLANKS;
                    delayCounter = craftingDelay.get();
                } else {
                    // Done with planks, close GUI and recheck
                    info("Finished converting logs to planks, closing GUI");
                    if (mc.currentScreen != null) {
                        mc.player.closeHandledScreen();
                    }
                    needsOakPlanks = false;
                    currentState = State.CHECK_OAK_SIGN_RESOURCES;
                    delayCounter = actionDelay.get() * 3;
                }
            }

            case CONVERT_PLANKS_TO_STICKS -> {
                info("Converting Oak Planks to Sticks (batch crafting)");

                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE) {
                    if (!(mc.currentScreen instanceof CraftingScreen)) {
                        error("ERROR: Not in crafting table screen for stick conversion!");
                        currentState = State.FIND_CRAFTING_TABLE;
                        delayCounter = actionDelay.get();
                        return;
                    }
                }

                // Place planks vertically (2 planks make 4 sticks)
                if (recipeStep == 0) {
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
                    int secondSlot = (mc.currentScreen instanceof CraftingScreen) ? 4 : 3;
                    if (placeItemInSlot(Items.OAK_PLANKS, secondSlot)) {
                        info("Placed second plank for stick crafting");
                        recipeStep = 0;
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
                int sticks = InvUtils.find(Items.STICK).count();
                int oakPlanks = InvUtils.find(Items.OAK_PLANKS).count();

                // Calculate how many more sticks we need
                int requiredSigns = (targetCraftAmount + 2) / 3;
                int requiredSticks = requiredSigns;
                int sticksStillNeeded = requiredSticks - sticks;

                info("Taking sticks - Need " + sticksStillNeeded + " more sticks, have " + oakPlanks + " planks");

                // Shift-click the result slot
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    0,
                    0,
                    SlotActionType.QUICK_MOVE,
                    mc.player
                );

                int newSticks = InvUtils.find(Items.STICK).count();
                int newOakPlanks = InvUtils.find(Items.OAK_PLANKS).count();
                int newSticksNeeded = requiredSticks - newSticks;

                info("After taking sticks - Planks: " + newOakPlanks + ", Sticks: " + newSticks + ", Still need: " + newSticksNeeded);

                if (newSticksNeeded > 0 && newOakPlanks >= 2) {
                    // Continue converting planks to sticks
                    info("Need more sticks, converting more planks");
                    currentState = State.CONVERT_PLANKS_TO_STICKS;
                    delayCounter = craftingDelay.get();
                } else {
                    // Done with sticks
                    info("Finished converting planks to sticks, closing GUI");
                    if (mc.currentScreen != null) {
                        mc.player.closeHandledScreen();
                    }
                    needsSticks = false;
                    currentState = State.CHECK_OAK_SIGN_RESOURCES;
                    delayCounter = actionDelay.get() * 3;
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
                Item item = getSelectedItem();
                int actualCycles = getActualCraftingCycles(item, targetCraftAmount);

                // Safety check: if crafting method is CRAFTING_TABLE but we're not in the crafting GUI
                if (craftingMethod.get() == CraftingMethod.CRAFTING_TABLE && !(mc.currentScreen instanceof CraftingScreen)) {
                    error("ERROR: Crafting table GUI not open when it should be! Retrying...");
                    currentState = State.FIND_CRAFTING_TABLE;
                    delayCounter = actionDelay.get();
                    return;
                }

                if (itemsCrafted >= actualCycles) {
                    if (mc.currentScreen != null) {
                        mc.player.closeHandledScreen();
                    }
                    currentState = State.SELECT_ITEM_HOTBAR;
                    delayCounter = actionDelay.get();
                    return;
                }

                // Prepare the recipe for the selected item
                currentRecipe = getRecipeSteps(item);
                recipeStep = 0;

                if (currentRecipe.isEmpty()) {
                    error("ERROR: No recipe found for the selected item!");
                    toggle();
                    return;
                }

                info("Starting to place items for recipe (" + currentRecipe.size() + " steps) - Cycle " + (itemsCrafted + 1) + "/" + actualCycles);
                currentState = State.PLACE_CRAFTING_ITEMS;
                delayCounter = craftingDelay.get();
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
                        info("Stack splitting not possible or not needed");
                        mc.player.closeHandledScreen();

                        // Check if player is holding the item in their hand
                        ItemStack heldItem = mc.player.getMainHandStack();
                        if (heldItem.isEmpty() || heldItem.getItem() != getSelectedItem()) {
                            // Check inventory for items with correct stack size
                            boolean foundReadyStack = false;
                            for (int i = 0; i < 36; i++) {
                                ItemStack stack = mc.player.getInventory().getStack(i);
                                if (stack.getItem() == item && stack.getCount() >= targetAmount) {
                                    foundReadyStack = true;
                                    break;
                                }
                            }

                            if (!foundReadyStack) {
                                error("No items ready to sell, restarting sequence");
                                resetCounters();
                                currentState = State.IDLE;
                                delayCounter = loopDelay.get();
                                return;
                            }
                        }

                        info("Items ready to sell, proceeding");
                        currentState = State.EXECUTE_SELL_COMMAND;
                        delayCounter = actionDelay.get();
                        return;
                    }
                    info("Initialized splitting: " + totalStacksToCreate + " stacks to create");
                }

                // Perform one split operation
                if (stacksSplitSoFar < totalStacksToCreate - 1) { // -1 because we leave the last stack as-is
                    if (performOneSplit(item, targetAmount)) {
                        stacksSplitSoFar++;
                        info("Split progress: " + stacksSplitSoFar + "/" + (totalStacksToCreate - 1));

                        if (stacksSplitSoFar >= totalStacksToCreate - 1) {
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
                            error("Too many split failures");

                            // Close inventory and proceed anyway if we have items
                            mc.player.closeHandledScreen();

                            Item checkItem = getSelectedItem();
                            int sellAmount = sellingAmount.get();
                            boolean hasItemsToSell = false;

                            for (int i = 0; i < 36; i++) {
                                ItemStack stack = mc.player.getInventory().getStack(i);
                                if (stack.getItem() == checkItem && stack.getCount() >= sellAmount) {
                                    hasItemsToSell = true;
                                    break;
                                }
                            }

                            if (hasItemsToSell) {
                                info("Have items to sell despite split failures, proceeding");
                                currentState = State.CLOSE_INVENTORY;
                                delayCounter = actionDelay.get();
                            } else {
                                error("No items to sell after split failures, restarting");
                                resetCounters();
                                currentState = State.IDLE;
                                delayCounter = loopDelay.get();
                            }
                            splitAttempts = 0;
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

                // After splitting, find ALL items in inventory that meet selling criteria
                Item targetItem = getSelectedItem();
                int targetAmount = sellingAmount.get();

                // Check both hotbar AND inventory for ready items
                List<Integer> readyItemSlots = new ArrayList<>();

                // Check hotbar first (slots 0-8 in inventory, 36-44 in screen handler)
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == targetItem && stack.getCount() >= targetAmount) {
                        readyItemSlots.add(i);
                    }
                }

                // Check main inventory (slots 9-35)
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == targetItem && stack.getCount() >= targetAmount) {
                        readyItemSlots.add(i);
                    }
                }

                if (readyItemSlots.isEmpty()) {
                    error("No items ready to sell after splitting!");
                    resetCounters();
                    currentState = State.IDLE;
                    delayCounter = loopDelay.get();
                    return;
                }

                info("Found " + readyItemSlots.size() + " stacks ready to sell");

                // Select first hotbar item if available, otherwise we'll move items to hotbar
                boolean hasHotbarItem = false;
                for (int slot : readyItemSlots) {
                    if (slot < 9) {
                        InvUtils.swap(slot, false);
                        info("Selected hotbar slot " + slot);
                        hasHotbarItem = true;
                        break;
                    }
                }

                if (!hasHotbarItem) {
                    // Need to move items to hotbar first
                    info("No items in hotbar, will move from inventory");
                    currentState = State.OPEN_INVENTORY;
                    delayCounter = actionDelay.get();
                    return;
                }

                // Check if we need to fetch prices after crafting
                if (priceMode.get() == PriceMode.ADAPTIVE && fetchAfterCrafting.get() && adaptivePrice == null) {
                    info("Fetch After Crafting enabled - fetching prices now before selling");
                    currentState = State.SEND_AH_SEARCH_COMMAND;
                    delayCounter = actionDelay.get();
                } else {
                    // Proceed directly to selling
                    currentState = State.EXECUTE_SELL_COMMAND;
                    delayCounter = actionDelay.get();
                }
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

                // Increment items sold counter
                itemsSoldInBatch++;
                info("Items sold in current batch: " + itemsSoldInBatch);

                // Check if we need to fetch prices mid-batch
                if (priceMode.get() == PriceMode.ADAPTIVE &&
                    itemsSoldInBatch > 0 &&
                    itemsSoldInBatch % fetchBetweenBatch.get() == 0) {

                    info("Fetch Between Batch triggered (sold " + itemsSoldInBatch + " items, interval: " + fetchBetweenBatch.get() + ")");
                    info("Fetching updated prices before continuing...");
                    currentState = State.SEND_AH_SEARCH_COMMAND;
                    delayCounter = actionDelay.get();
                    return;
                }

                // Normal flow - check for remaining items
                currentState = State.CHECK_REMAINING_ITEMS;
                delayCounter = actionDelay.get();
            }

            case CHECK_REMAINING_ITEMS -> {
                Item targetItem = getSelectedItem();
                int targetAmount = sellingAmount.get();

                // First, check if we still have items in hotbar that haven't been sold yet
                List<Integer> hotbarItemsRemaining = new ArrayList<>();
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == targetItem && stack.getCount() >= targetAmount) {
                        hotbarItemsRemaining.add(i);
                    }
                }

                if (!hotbarItemsRemaining.isEmpty()) {
                    // We still have items in hotbar to sell!
                    info("Found " + hotbarItemsRemaining.size() + " unsold stacks in hotbar, continuing to sell");
                    hotbarSlotsWithItems.clear();
                    hotbarSlotsWithItems.addAll(hotbarItemsRemaining);
                    currentHotbarSellIndex = 0;
                    currentState = State.SELL_ALL_HOTBAR_ITEMS;
                    delayCounter = actionDelay.get();
                    return;
                }

                // No items left in hotbar, check inventory for items with correct stack size
                int validStacksInInventory = 0;
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == targetItem && stack.getCount() >= targetAmount) {
                        validStacksInInventory++;
                    }
                }

                info("Checking for remaining " + getItemDisplayName(targetItem) + " in inventory - Found: " + validStacksInInventory + " valid stacks");

                if (validStacksInInventory > 0) {
                    // More items to sell in inventory, need to move to hotbar
                    info("More valid stacks found in inventory, opening inventory to move to hotbar");
                    if (mc.currentScreen == null) {
                        mc.setScreen(new InventoryScreen(mc.player));
                    }
                    currentState = State.MOVE_ITEMS_TO_HOTBAR;
                    delayCounter = actionDelay.get();
                } else {
                    // Check if there are any items at all (even with wrong stack size)
                    int anyItemsInInventory = 0;
                    for (int i = 0; i < 36; i++) {
                        ItemStack stack = mc.player.getInventory().getStack(i);
                        if (stack.getItem() == targetItem) {
                            anyItemsInInventory += stack.getCount();
                        }
                    }

                    if (anyItemsInInventory > 0 && anyItemsInInventory >= targetAmount) {
                        // We have items but they're not properly split, go back to splitting
                        info("Found " + anyItemsInInventory + " items that need splitting");
                        if (mc.currentScreen == null) {
                            mc.setScreen(new InventoryScreen(mc.player));
                        }

                        // Reset splitting variables
                        totalStacksToCreate = 0;
                        stacksSplitSoFar = 0;
                        sourceItemSlots.clear();
                        targetEmptySlots.clear();
                        currentSourceSlot = -1;
                        itemsRemainingInCurrentSource = 0;

                        currentState = State.SPLIT_STACK;
                        delayCounter = actionDelay.get();
                    } else {
                        // No more items to sell anywhere
                        info("No more items to sell, entering loop delay");
                        if (mc.currentScreen != null) {
                            mc.player.closeHandledScreen();
                        }
                        currentState = State.LOOP_DELAY;
                        delayCounter = loopDelay.get();
                    }
                }
            }


            case MOVE_ITEMS_TO_HOTBAR -> {
                Item targetItem = getSelectedItem();
                int targetAmount = sellingAmount.get();

                info("Batch-moving items to hotbar - Looking for " + getItemDisplayName(targetItem) + " x" + targetAmount);

                // Find all stacks in inventory that match AND have correct stack size
                List<Integer> inventoryStacks = new ArrayList<>();
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                    if (stack.getItem() == targetItem && stack.getCount() >= targetAmount) {
                        inventoryStacks.add(i);
                    }
                }

                if (inventoryStacks.isEmpty()) {
                    // No valid stacks in inventory - check if there are items that need splitting
                    int totalItems = 0;
                    for (int i = 9; i < 36; i++) {
                        ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                        if (stack.getItem() == targetItem) {
                            totalItems += stack.getCount();
                        }
                    }

                    if (totalItems >= targetAmount) {
                        // We have items but wrong stack sizes - go back to splitting
                        info("No properly sized stacks found, but have " + totalItems + " items total - going back to split");

                        // Reset splitting variables
                        totalStacksToCreate = 0;
                        stacksSplitSoFar = 0;
                        sourceItemSlots.clear();
                        targetEmptySlots.clear();
                        currentSourceSlot = -1;
                        itemsRemainingInCurrentSource = 0;

                        currentState = State.SPLIT_STACK;
                        delayCounter = actionDelay.get();
                        return;
                    }

                    // Check hotbar for ready items
                    List<Integer> hotbarItemsReady = new ArrayList<>();
                    for (int i = 36; i < 45; i++) {
                        ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                        if (stack.getItem() == targetItem && stack.getCount() >= targetAmount) {
                            hotbarItemsReady.add(i - 36); // Convert to inventory hotbar index
                        }
                    }

                    if (!hotbarItemsReady.isEmpty()) {
                        // Found items in hotbar that need to be sold!
                        info("Found " + hotbarItemsReady.size() + " items in hotbar ready to sell");
                        mc.player.closeHandledScreen();

                        hotbarSlotsWithItems.clear();
                        hotbarSlotsWithItems.addAll(hotbarItemsReady);
                        currentHotbarSellIndex = 0;

                        currentState = State.FINALIZE_HOTBAR_MOVE;
                        delayCounter = actionDelay.get();
                    } else {
                        // No items in inventory OR hotbar with correct size
                        error("Could not find properly sized items anywhere");
                        mc.player.closeHandledScreen();
                        currentState = State.LOOP_DELAY;
                        delayCounter = loopDelay.get();
                    }
                    return;
                }

                info("Found " + inventoryStacks.size() + " properly sized stacks to move");

                // Find available hotbar slots
                List<Integer> availableHotbarSlots = new ArrayList<>();
                for (int i = 36; i < 45; i++) {
                    ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                    if (stack.isEmpty() || (stack.getItem() == targetItem && stack.getCount() >= targetAmount)) {
                        availableHotbarSlots.add(i);
                    }
                }

                if (availableHotbarSlots.isEmpty()) {
                    availableHotbarSlots.add(36); // Force use first slot
                }

                // Initialize for sequential moving
                if (hotbarSlotsWithItems.isEmpty()) {
                    // First time entering this state - prepare the list
                    hotbarSlotsWithItems.clear();
                    currentHotbarSellIndex = 0; // Reuse this as move index

                    // Store pairs of source->target for moving
                    int itemsToMove = Math.min(inventoryStacks.size(), availableHotbarSlots.size());
                    for (int i = 0; i < itemsToMove; i++) {
                        hotbarSlotsWithItems.add(inventoryStacks.get(i)); // Store source slot temporarily
                    }
                    info("Prepared " + itemsToMove + " items to move");
                }

                // Move one item at a time with delay
                if (currentHotbarSellIndex < hotbarSlotsWithItems.size() &&
                    currentHotbarSellIndex < availableHotbarSlots.size()) {

                    int sourceSlot = hotbarSlotsWithItems.get(currentHotbarSellIndex);
                    int targetSlot = availableHotbarSlots.get(currentHotbarSellIndex);

                    // Pick up from inventory
                    mc.interactionManager.clickSlot(
                        mc.player.currentScreenHandler.syncId,
                        sourceSlot,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );

                    // Place in hotbar
                    mc.interactionManager.clickSlot(
                        mc.player.currentScreenHandler.syncId,
                        targetSlot,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );

                    info("Moved stack " + (currentHotbarSellIndex + 1) + "/" + Math.min(hotbarSlotsWithItems.size(), availableHotbarSlots.size()) +
                        " from slot " + sourceSlot + " to hotbar slot " + (targetSlot - 36));

                    currentHotbarSellIndex++;
                    delayCounter = splittingDelay.get();
                    return;
                }

                if (currentHotbarSellIndex >= availableHotbarSlots.size() &&
                    currentHotbarSellIndex < hotbarSlotsWithItems.size()) {

                    error("Not enough hotbar slots to move all items (" +
                        currentHotbarSellIndex + " moved, " +
                        (hotbarSlotsWithItems.size() - currentHotbarSellIndex) + " remaining)");

                    // Update hotbarSlotsWithItems to only contain what was actually moved
                    List<Integer> finalHotbarSlots = new ArrayList<>();
                    for (int i = 0; i < Math.min(hotbarSlotsWithItems.size(), availableHotbarSlots.size()); i++) {
                        finalHotbarSlots.add(availableHotbarSlots.get(i) - 36);
                    }
                    hotbarSlotsWithItems.clear();
                    hotbarSlotsWithItems.addAll(finalHotbarSlots);

                    currentState = State.FINALIZE_HOTBAR_MOVE;
                    delayCounter = actionDelay.get();
                    return;
                }

                // All items moved, update hotbarSlotsWithItems to contain actual hotbar indices
                List<Integer> finalHotbarSlots = new ArrayList<>();
                for (int i = 0; i < Math.min(hotbarSlotsWithItems.size(), availableHotbarSlots.size()); i++) {
                    finalHotbarSlots.add(availableHotbarSlots.get(i) - 36); // Convert to inventory hotbar index
                }
                hotbarSlotsWithItems.clear();
                hotbarSlotsWithItems.addAll(finalHotbarSlots);

                info("Batch move complete - moved " + hotbarSlotsWithItems.size() + " stacks to hotbar");
                currentState = State.FINALIZE_HOTBAR_MOVE;
                delayCounter = actionDelay.get();
            }


            // Add this new state to the enum and switch
            case FINALIZE_HOTBAR_MOVE -> {
                // Close inventory
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }

                if (hotbarSlotsWithItems.isEmpty()) {
                    error("No hotbar slots with items to sell!");
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                    return;
                }

                info("Ready to sell " + hotbarSlotsWithItems.size() + " hotbar stacks");
                currentHotbarSellIndex = 0;
                currentState = State.SELL_ALL_HOTBAR_ITEMS;
                delayCounter = actionDelay.get();
            }

            // Replace the SELL_ALL_HOTBAR_ITEMS case in AutoCrafting.java with this fixed version:

            // Replace the SELL_ALL_HOTBAR_ITEMS case in AutoCrafting.java with this improved version:

            case SELL_ALL_HOTBAR_ITEMS -> {
                Item targetItem = getSelectedItem();
                int targetAmount = sellingAmount.get();

                if (currentHotbarSellIndex >= hotbarSlotsWithItems.size()) {
                    // All current batch of hotbar items sold, check for more
                    info("Finished selling batch of " + hotbarSlotsWithItems.size() + " hotbar items");
                    currentHotbarSellIndex = 0;
                    hotbarSlotsWithItems.clear();
                    currentState = State.CHECK_REMAINING_ITEMS;
                    delayCounter = actionDelay.get();
                    return;
                }

                // Select the current hotbar slot
                int slotIndex = hotbarSlotsWithItems.get(currentHotbarSellIndex);

                // Verify the slot still has items with correct amount before trying to sell
                ItemStack stackInSlot = mc.player.getInventory().getStack(slotIndex);

                if (stackInSlot.isEmpty() || stackInSlot.getItem() != targetItem) {
                    error("Hotbar slot " + slotIndex + " is empty or has wrong item! Skipping...");
                    currentHotbarSellIndex++;
                    delayCounter = actionDelay.get();
                    return;
                }

                // FIX: Check if the stack size EXACTLY matches the selling amount
                if (stackInSlot.getCount() != targetAmount) {
                    info("Hotbar slot " + slotIndex + " has " + stackInSlot.getCount() +
                        " items but selling amount is " + targetAmount + ". Need to split stacks.");

                    // Count total items in inventory that need splitting
                    int totalItemsToSplit = 0;
                    for (int i = 0; i < 36; i++) {
                        ItemStack stack = mc.player.getInventory().getStack(i);
                        if (stack.getItem() == targetItem && stack.getCount() != targetAmount) {
                            totalItemsToSplit += stack.getCount();
                        }
                    }

                    // Check if we have enough items to make at least one valid stack
                    if (totalItemsToSplit >= targetAmount) {
                        info("Found " + totalItemsToSplit + " items that need splitting. Opening inventory...");

                        // Clear current hotbar tracking
                        hotbarSlotsWithItems.clear();
                        currentHotbarSellIndex = 0;

                        // Reset splitting variables to trigger re-split
                        totalStacksToCreate = 0;
                        stacksSplitSoFar = 0;
                        sourceItemSlots.clear();
                        targetEmptySlots.clear();
                        currentSourceSlot = -1;
                        itemsRemainingInCurrentSource = 0;

                        // Open inventory and go to split state
                        if (mc.currentScreen == null) {
                            mc.setScreen(new InventoryScreen(mc.player));
                        }
                        currentState = State.SPLIT_STACK;
                        delayCounter = actionDelay.get();
                        return;
                    } else {
                        // Not enough items to make a valid stack, skip this one
                        error("Not enough items to create a stack of " + targetAmount + ". Skipping...");
                        currentHotbarSellIndex++;
                        delayCounter = actionDelay.get();
                        return;
                    }
                }

                InvUtils.swap(slotIndex, false);
                info("Selected hotbar slot " + slotIndex + " for selling (" + (currentHotbarSellIndex + 1) +
                    "/" + hotbarSlotsWithItems.size() + ") with exactly " + stackInSlot.getCount() + " items");

                currentHotbarSellIndex++;
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

                info("Closed auction GUI");

                // Determine where to go next based on when we fetched
                if (fetchAfterCrafting.get() && itemsSoldInBatch == 0) {
                    // We fetched after crafting but before any sales
                    info("Returning to sell sequence after initial price fetch");
                    currentState = State.EXECUTE_SELL_COMMAND;
                    delayCounter = actionDelay.get();
                } else if (itemsSoldInBatch > 0) {
                    // We fetched mid-batch (between sales)
                    info("Returning to selling after mid-batch price update");
                    currentState = State.CHECK_REMAINING_ITEMS;
                    delayCounter = actionDelay.get();
                } else {
                    // We fetched before crafting, proceed with normal flow
                    info("Returning to resource check after price fetch");

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
            }

            case LOOP_DELAY -> {
                sequenceCycleCount++;
                info("Completed cycle " + sequenceCycleCount + " - entering loop delay");
                info("Total items sold in this batch: " + itemsSoldInBatch);
                resetCounters(); // This will reset itemsSoldInBatch to 0
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

            case PAUSE_FOR_MAX_LISTINGS -> {
                if (pauseTicksRemaining > 0) {
                    pauseTicksRemaining--;

                    // Log progress every 1200 ticks (1 minute)
                    if (pauseTicksRemaining % 1200 == 0 && pauseTicksRemaining > 0) {
                        double minutesRemaining = pauseTicksRemaining / 1200.0;
                        info(String.format("Pause remaining: %.2f minutes (%d ticks)",
                            minutesRemaining, pauseTicksRemaining));
                    }

                    return; // Stay in this state
                } else {
                    // Pause complete
                    info("Pause complete! Resuming sequence...");
                    pausedForMaxListings = false;
                    pauseTicksRemaining = 0;

                    // Return to loop delay to restart the sequence
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                }
            }

            case SEND_AH_PLAYER_COMMAND -> {
                if (playerName == null) {
                    error("Player name not available for cancel sequence!");
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                    return;
                }

                mc.player.networkHandler.sendChatCommand("ah " + playerName);
                info("Sent /ah " + playerName + " command");
                currentState = State.WAIT_FOR_PLAYER_AH_GUI;
                delayCounter = actionDelay.get() * 2;
            }

            case WAIT_FOR_PLAYER_AH_GUI -> {
                if (isAuctionGuiOpen()) {
                    info("Player auction GUI detected");
                    currentState = State.CLICK_YOUR_ITEMS_CHEST;
                    delayCounter = actionDelay.get();
                } else {
                    info("Waiting for player auction GUI...");
                }
            }

            case CLICK_YOUR_ITEMS_CHEST -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    error("Not in auction GUI!");
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                    return;
                }

                var handler = screen.getScreenHandler();
                // Bottom row is rows 5 (slots 45-53 in a 6-row chest)
                boolean foundChest = false;
                for (int i = 45; i < 54; i++) {
                    ItemStack stack = handler.getSlot(i).getStack();
                    if (stack.getItem() == Items.CHEST) {
                        mc.interactionManager.clickSlot(
                            handler.syncId,
                            i,
                            0,
                            SlotActionType.PICKUP,
                            mc.player
                        );
                        info("Clicked 'Your Items' chest in slot " + i);
                        foundChest = true;
                        currentState = State.WAIT_FOR_YOUR_ITEMS_GUI;
                        delayCounter = actionDelay.get() * 2;
                        break;
                    }
                }

                if (!foundChest) {
                    error("Could not find 'Your Items' chest in bottom row!");
                    currentState = State.CLOSE_PLAYER_AH_GUI;
                    delayCounter = actionDelay.get();
                }
            }

            case WAIT_FOR_YOUR_ITEMS_GUI -> {
                if (isYourItemsGuiOpen()) {
                    info("Your Items GUI detected, parsing items to cancel");
                    currentState = State.PARSE_AND_CANCEL_EXPENSIVE_ITEMS;
                    delayCounter = actionDelay.get();
                } else {
                    info("Waiting for Your Items GUI...");
                }
            }

            case PARSE_AND_CANCEL_EXPENSIVE_ITEMS -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    error("Not in Your Items GUI!");
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                    return;
                }

                // First time in this state - parse all items
                if (itemsToCancelSlots.isEmpty()) {
                    var handler = screen.getScreenHandler();

                    info("=== DEBUG: Parsing Your Items GUI ===");
                    info("Total slots: " + handler.slots.size());

                    // Parse items in first 5 rows (slots 0-44)
                    for (int i = 0; i < 45; i++) {
                        ItemStack stack = handler.getSlot(i).getStack();
                        if (stack.isEmpty()) continue;

                        info("Slot " + i + ": " + stack.getItem() + " x" + stack.getCount());

                        List<net.minecraft.text.Text> lore = SMPUtils.getItemLore(stack);
                        if (lore == null) {
                            info("  -> No lore found");
                            continue;
                        }

                        info("  -> Lore lines: " + lore.size());
                        for (net.minecraft.text.Text line : lore) {
                            info("    Lore: " + line.getString());
                        }

                        // Check if it's from this player
                        if (!SMPUtils.isAuctionFromPlayer(lore, playerName)) {
                            info("  -> Not from player: " + playerName);
                            continue;
                        }

                        info("  -> Confirmed from player: " + playerName);

                        // Extract price with improved logic
                        double price = 0;
                        for (net.minecraft.text.Text line : lore) {
                            String text = line.getString();

                            // Check multiple price patterns
                            if (text.contains("Price:") || text.contains("price:") ||
                                text.contains("ᴘʀɪᴄᴇ:") || text.contains("PRICE:")) {

                                info("    Found price line: " + text);

                                // Try to extract price multiple ways
                                String priceStr = extractPriceFromLine(text);
                                if (priceStr != null) {
                                    price = SMPUtils.parsePrice(priceStr);
                                    info("    Extracted price string: '" + priceStr + "' -> " + price);
                                    break;
                                } else {
                                    info("    Failed to extract price from: " + text);
                                }
                            }
                        }

                        if (price > 0) {
                            itemsToCancelSlots.add(i);
                            itemsToCancelPrices.add(price);
                            info("  -> ADDED to cancel list: slot=" + i + ", price=" + price);
                        } else {
                            info("  -> Price was 0 or invalid, skipping");
                        }
                    }

                    info("=== Total items found to cancel: " + itemsToCancelSlots.size() + " ===");

                    // Sort by price (highest first)
                    List<Integer> sortedIndices = new ArrayList<>();
                    for (int i = 0; i < itemsToCancelPrices.size(); i++) {
                        sortedIndices.add(i);
                    }
                    sortedIndices.sort((a, b) -> Double.compare(itemsToCancelPrices.get(b), itemsToCancelPrices.get(a)));

                    // Reorder slots and prices based on sorted indices
                    List<Integer> sortedSlots = new ArrayList<>();
                    List<Double> sortedPrices = new ArrayList<>();
                    for (int idx : sortedIndices) {
                        sortedSlots.add(itemsToCancelSlots.get(idx));
                        sortedPrices.add(itemsToCancelPrices.get(idx));
                    }
                    itemsToCancelSlots = sortedSlots;
                    itemsToCancelPrices = sortedPrices;

                    info("Found " + itemsToCancelSlots.size() + " items to potentially cancel, sorted by price");

                    // If no items found, close and return to loop
                    if (itemsToCancelSlots.isEmpty()) {
                        error("No items found to cancel! Check debug logs above.");
                        currentState = State.CLOSE_YOUR_ITEMS_GUI;
                        delayCounter = actionDelay.get();
                        return;
                    }
                }

                // Cancel items one by one
                if (canceledItemsCount < TARGET_CANCEL_COUNT && canceledItemsCount < itemsToCancelSlots.size()) {
                    int slotIndex = itemsToCancelSlots.get(canceledItemsCount);

                    // Skip if already sold
                    if (alreadySoldSlots.contains(slotIndex)) {
                        info("Slot " + slotIndex + " already marked as sold, skipping");
                        canceledItemsCount++;
                        delayCounter = splittingDelay.get();
                        return;
                    }

                    double price = itemsToCancelPrices.get(canceledItemsCount);
                    info("Canceling item " + (canceledItemsCount + 1) + "/" + Math.min(TARGET_CANCEL_COUNT, itemsToCancelSlots.size()) +
                        " in slot " + slotIndex + " with price: " + SMPUtils.formatPrice(price, false));

                    cancelAttemptSlot = slotIndex;

                    var handler = ((GenericContainerScreen) mc.currentScreen).getScreenHandler();
                    mc.interactionManager.clickSlot(
                        handler.syncId,
                        slotIndex,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );

                    hasCanceledItems = true;
                    canceledItemsCount++;
                    currentState = State.WAIT_AFTER_CANCEL_CLICK;
                    delayCounter = splittingDelay.get();
                } else {
                    // Done canceling
                    int actualCanceled = canceledItemsCount - alreadySoldSlots.size();
                    info("Finished canceling " + actualCanceled + " items (skipped " + alreadySoldSlots.size() + " already sold)");
                    currentState = State.CLOSE_YOUR_ITEMS_GUI;
                    delayCounter = actionDelay.get();
                }
            }


            case WAIT_AFTER_CANCEL_CLICK -> {
                // Wait for server response and message handler to mark as sold if needed
                cancelAttemptSlot = -1; // Reset
                currentState = State.PARSE_AND_CANCEL_EXPENSIVE_ITEMS;
                delayCounter = actionDelay.get();
            }

            case CLOSE_YOUR_ITEMS_GUI -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                info("Closed Your Items GUI");
                currentState = State.CLOSE_PLAYER_AH_GUI;
                delayCounter = actionDelay.get() * 2; // Wait for auction GUI to appear
            }

            case CLOSE_PLAYER_AH_GUI -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                info("Closed player auction GUI");

                if (hasCanceledItems) {
                    currentState = State.CHECK_FOR_CANCELED_ITEMS;
                    delayCounter = actionDelay.get() * 3; // Give time for items to appear in inventory
                } else {
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                }
            }

            case CHECK_FOR_CANCELED_ITEMS -> {
                Item targetItem = getSelectedItem();
                int canceledItemCount = 0;

                // Count canceled items in inventory
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == targetItem) {
                        canceledItemCount += stack.getCount();
                    }
                }

                info("Found " + canceledItemCount + " canceled items in inventory");

                if (canceledItemCount >= sellingAmount.get()) {
                    info("Need to split and sell canceled items");

                    // Open inventory for splitting
                    if (mc.currentScreen == null) {
                        mc.setScreen(new InventoryScreen(mc.player));
                    }
                    currentState = State.SPLIT_CANCELED_ITEMS;
                    delayCounter = actionDelay.get();
                } else {
                    info("Not enough canceled items to sell, continuing to main sequence");
                    hasCanceledItems = false;
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                }
            }

            case SPLIT_CANCELED_ITEMS -> {
                Item item = getSelectedItem();
                int targetAmount = sellingAmount.get();

                // Initialize splitting if needed
                if (stacksSplitSoFar == 0 && totalStacksToCreate == 0) {
                    if (!initializeSplitting(item, targetAmount)) {
                        error("Failed to initialize splitting for canceled items");
                        mc.player.closeHandledScreen();
                        hasCanceledItems = false;
                        currentState = State.LOOP_DELAY;
                        delayCounter = loopDelay.get();
                        return;
                    }
                    info("Initialized splitting for canceled items: " + totalStacksToCreate + " stacks to create");
                }

                // Perform splitting (same logic as normal splitting)
                if (stacksSplitSoFar < totalStacksToCreate) {
                    if (performOneSplit(item, targetAmount)) {
                        stacksSplitSoFar++;
                        info("Split progress: " + stacksSplitSoFar + "/" + totalStacksToCreate);

                        if (stacksSplitSoFar >= totalStacksToCreate) {
                            info("Splitting complete for canceled items!");
                            mc.player.closeHandledScreen();
                            currentState = State.SELL_CANCELED_ITEMS;
                            delayCounter = actionDelay.get();
                        } else {
                            delayCounter = splittingDelay.get();
                        }
                    } else {
                        error("Failed to split canceled items");
                        mc.player.closeHandledScreen();
                        hasCanceledItems = false;
                        currentState = State.LOOP_DELAY;
                        delayCounter = loopDelay.get();
                    }
                } else {
                    mc.player.closeHandledScreen();
                    currentState = State.SELL_CANCELED_ITEMS;
                    delayCounter = actionDelay.get();
                }
            }

            case SELL_CANCELED_ITEMS -> {
                Item targetItem = getSelectedItem();
                int targetAmount = sellingAmount.get();

                // Find items in hotbar
                hotbarSlotsWithItems.clear();
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == targetItem && stack.getCount() >= targetAmount) {
                        hotbarSlotsWithItems.add(i);
                    }
                }

                if (!hotbarSlotsWithItems.isEmpty()) {
                    info("Found " + hotbarSlotsWithItems.size() + " canceled item stacks in hotbar to sell");
                    currentHotbarSellIndex = 0;
                    currentState = State.SELL_ALL_HOTBAR_ITEMS;
                    delayCounter = actionDelay.get();
                } else {
                    info("No more canceled items to sell, continuing to main sequence");
                    hasCanceledItems = false;
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                }
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

        if (getResources.get() != ResourceMethod.INVENTORY) {
            return true;
        }

        Map<Item, Integer> required = getRequiredResources(item);
        Map<Item, Integer> missing = new HashMap<>();

        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            // Scale required amount by targetCraftAmount
            int requiredAmount = entry.getValue() * targetCraftAmount;
            int count = InvUtils.find(entry.getKey()).count();
            if (count < requiredAmount) {
                missing.put(entry.getKey(), requiredAmount - count);
            }
        }

        if (!missing.isEmpty()) {
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

        // Calculate stacks we WANT to create
        int desiredStacks = totalCount / targetAmount;
        if (desiredStacks == 0) {
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

        // Calculate how many stacks we can ACTUALLY create based on available slots
        // We need (desiredStacks - 1) empty slots because one stack can stay where it is
        int maxPossibleStacks = Math.min(desiredStacks, targetEmptySlots.size() + 1);

        if (maxPossibleStacks < desiredStacks) {
            info("Limited by empty slots: wanted " + desiredStacks + " stacks, can only make " + maxPossibleStacks);
            info("Will leave " + (desiredStacks - maxPossibleStacks) + " stacks unsplit");
        }

        // Set the actual number of stacks we'll create
        totalStacksToCreate = maxPossibleStacks;

        if (totalStacksToCreate <= 1) {
            info("Only one stack possible, no splitting needed");
            return false;
        }

        // Initialize tracking
        stacksSplitSoFar = 0;
        currentSourceSlot = sourceItemSlots.get(0);
        itemsRemainingInCurrentSource = mc.player.currentScreenHandler.getSlot(currentSourceSlot).getStack().getCount();

        info("Will create " + totalStacksToCreate + " stacks (using " + targetEmptySlots.size() + " empty slots)");
        return true;
    }

    private boolean performOneSplit(Item item, int targetAmount) {
        if (mc.player == null || mc.interactionManager == null) return false;

        // Check if this is the last stack - if so, just leave it where it is
        if (stacksSplitSoFar >= totalStacksToCreate - 1) {
            info("Last stack reached, leaving it in place");
            return true;
        }

        // Safety check: make sure we have a target slot available
        if (stacksSplitSoFar >= targetEmptySlots.size()) {
            error("No more empty slots available for splitting (split " + stacksSplitSoFar + ", available: " + targetEmptySlots.size() + ")");
            return false;
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

        int startSlot = antiBait.get() ? skipSlotsAmount.get() : 0;
        int endSlot = 45; // Changed from 10 to 45 (first 5 rows: 9 slots * 5 rows)

        info("Parsing auction prices from slots " + startSlot + " to " + (endSlot - 1) + " (first 5 rows)");

        // Get the whitelist of players to ignore
        List<String> playersToIgnore = new ArrayList<>(ignorePlayerListings.get());

        // Add own name to ignore list if "Ignore Own" is enabled
        if (ignoreOwn.get() && playerName != null) {
            playersToIgnore.add(playerName);
            info("Ignoring own auctions (player: " + playerName + ")");
        }

        // Log all players being ignored
        if (!playersToIgnore.isEmpty()) {
            info("Ignoring listings from " + playersToIgnore.size() + " player(s): " + String.join(", ", playersToIgnore));
        }

        int ignoredCount = 0;
        int parsedCount = 0;

        for (int i = startSlot; i < endSlot; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            List<net.minecraft.text.Text> lore = SMPUtils.getItemLore(stack);
            if (lore == null || lore.isEmpty()) continue;

            // Check if this auction belongs to any ignored player
            boolean shouldIgnore = false;
            for (String ignoredPlayer : playersToIgnore) {
                if (SMPUtils.isAuctionFromPlayer(lore, ignoredPlayer)) {
                    info("Skipping auction from ignored player '" + ignoredPlayer + "' in slot " + i);
                    shouldIgnore = true;
                    ignoredCount++;
                    break;
                }
            }

            if (shouldIgnore) continue;

            // Parse the price
            for (net.minecraft.text.Text line : lore) {
                String text = line.getString();
                if (text.contains("Price:")) {
                    String priceStr = SMPUtils.extractPrice(text);
                    if (priceStr != null) {
                        double priceValue = SMPUtils.parsePrice(priceStr);
                        if (priceValue > 0) {
                            prices.add(priceValue);
                            parsedCount++;
                            info("Found price in slot " + i + ": " + priceStr);
                        }
                    }
                    break;
                }
            }
        }

        info("Price parsing complete: " + parsedCount + " prices found, " + ignoredCount + " auctions ignored");

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

            double minimumLowestValue = parseFormattedPrice(adaptiveMinimumLowest.get());
            if (minimumLowestValue > 0 && selectedPrice < minimumLowestValue) {
                info("Selected price " + selectedPrice + " is below minimum lowest " + minimumLowestValue + ", using minimum instead");
                selectedPrice = minimumLowestValue;
            }
            if (selectedPrice < 0) {
                selectedPrice = 1.0;
                info("Price was negative after subtraction, set to minimum: 1");
            }
        }

        adaptivePrice = SMPUtils.formatPrice(selectedPrice, adaptiveRandom.get() && adaptiveDecimal.get());
        info("Final adaptive price set to: " + adaptivePrice);
        return true;
    }
    private boolean isYourItemsGuiOpen() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            return false;
        }

        String title = screen.getTitle().getString();

        boolean hasYour = title.toLowerCase().contains("your") ||
            title.contains("ʏᴏᴜʀ") ||
            title.contains("ʏᴏᴜ");

        boolean hasItems = title.toLowerCase().contains("items") ||
            title.contains("ɪᴛᴇᴍѕ") ||
            title.contains("ɪᴛᴇᴍ");

        return hasYour && hasItems;
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
