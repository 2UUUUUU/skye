package com.example.addon.modules;

import com.example.addon.Main;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BrewingStandScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;

import java.util.*;

public class AutoBrewer extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPotion = settings.createGroup("Potion");
    private final SettingGroup sgResources = settings.createGroup("Resources");
    private final SettingGroup sgSendProfits = settings.createGroup("Send Profits");
    private final SettingGroup sgWebhook = settings.createGroup("Discord Webhook");

    // Enums
    public enum PotionType {
        INSTANT_DAMAGE("Instant Damage", Items.POTION, new Item[]{Items.NETHER_WART, Items.SPIDER_EYE, Items.FERMENTED_SPIDER_EYE}, PotionEffect.HARMFUL),
        POISON("Poison", Items.POTION, new Item[]{Items.NETHER_WART, Items.SPIDER_EYE}, PotionEffect.HARMFUL),
        REGENERATION("Regeneration", Items.POTION, new Item[]{Items.NETHER_WART, Items.GHAST_TEAR}, PotionEffect.BENEFICIAL),
        STRENGTH("Strength", Items.POTION, new Item[]{Items.NETHER_WART, Items.BLAZE_POWDER}, PotionEffect.BENEFICIAL),
        SWIFTNESS("Swiftness", Items.POTION, new Item[]{Items.NETHER_WART, Items.SUGAR}, PotionEffect.BENEFICIAL),
        NIGHT_VISION("Night Vision", Items.POTION, new Item[]{Items.NETHER_WART, Items.GOLDEN_CARROT}, PotionEffect.NEUTRAL),
        INVISIBILITY("Invisibility", Items.POTION, new Item[]{Items.NETHER_WART, Items.GOLDEN_CARROT, Items.FERMENTED_SPIDER_EYE}, PotionEffect.NEUTRAL),
        FIRE_RESISTANCE("Fire Resistance", Items.POTION, new Item[]{Items.NETHER_WART, Items.MAGMA_CREAM}, PotionEffect.BENEFICIAL),
        WATER_BREATHING("Water Breathing", Items.POTION, new Item[]{Items.NETHER_WART, Items.PUFFERFISH}, PotionEffect.BENEFICIAL),
        LEAPING("Leaping", Items.POTION, new Item[]{Items.NETHER_WART, Items.RABBIT_FOOT}, PotionEffect.BENEFICIAL),
        SLOWNESS("Slowness", Items.POTION, new Item[]{Items.NETHER_WART, Items.FERMENTED_SPIDER_EYE}, PotionEffect.HARMFUL),
        WEAKNESS("Weakness", Items.POTION, new Item[]{Items.FERMENTED_SPIDER_EYE}, PotionEffect.HARMFUL),
        TURTLE_MASTER("Turtle Master", Items.POTION, new Item[]{Items.NETHER_WART, Items.TURTLE_HELMET}, PotionEffect.NEUTRAL),
        SLOW_FALLING("Slow Falling", Items.POTION, new Item[]{Items.NETHER_WART, Items.PHANTOM_MEMBRANE}, PotionEffect.BENEFICIAL);

        private final String name;
        private final Item displayItem;
        private final Item[] brewingPhases;
        private final PotionEffect effect;

        PotionType(String name, Item displayItem, Item[] brewingPhases, PotionEffect effect) {
            this.name = name;
            this.displayItem = displayItem;
            this.brewingPhases = brewingPhases;
            this.effect = effect;
        }

        public String getName() { return name; }
        public Item getDisplayItem() { return displayItem; }
        public Item[] getBrewingPhases() { return brewingPhases; }
        public PotionEffect getEffect() { return effect; }
        public ItemStack getDisplayItemStack() { return new ItemStack(displayItem); }
        @Override public String toString() { return name; }
    }

    public enum PotionEffect { HARMFUL, BENEFICIAL, NEUTRAL }

    public enum PotionForm {
        DRINKABLE("Drinkable Potion", Items.POTION),
        SPLASH("Splash Potion", Items.SPLASH_POTION);
        private final String name;
        private final Item resultItem;
        PotionForm(String name, Item resultItem) { this.name = name; this.resultItem = resultItem; }
        public String getName() { return name; }
        public Item getResultItem() { return resultItem; }
        @Override public String toString() { return name; }
    }

    public enum ResourceMethod {
        INVENTORY("Inventory"), AUCTION_HOUSE("Auction House"), BUY_ORDER("Buy Order"), NEARBY_CHEST("Nearby Chest");
        private final String name;
        ResourceMethod(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    public enum OutputMethod {
        STORE_IN_CHEST("Store in Nearby Chest"), SELL_ORDERS("Sell through Orders"), SELL_AH("Sell through AH");
        private final String name;
        OutputMethod(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    private PotionType potionToBrew = PotionType.INSTANT_DAMAGE;

    // Settings - General
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay").description("Delay in ticks between actions")
        .defaultValue(10).min(1).max(20).sliderMax(20).build());

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay").description("Delay in ticks between sequence loops")
        .defaultValue(100).min(0).max(72000).sliderMax(1200).build());

    private final Setting<Boolean> smoothAim = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-aim").description("Enable smooth aiming for interactions").defaultValue(false).build());

    private final Setting<Integer> rotationSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("rotation-speed").description("Rotation speed in ticks for smooth aim")
        .defaultValue(10).min(0).max(600).sliderMax(600).visible(smoothAim::get).build());

    private final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-mode").description("Hide info logs and chat feedback").defaultValue(false).build());

    private final Setting<Boolean> hideErrors = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-errors").description("Also hide error messages")
        .defaultValue(false).visible(silentMode::get).build());

    // Settings - Potion
    private final Setting<Integer> brewingStandRange = sgPotion.add(new IntSetting.Builder()
        .name("brewing-stand-range").description("Range to search for brewing stands")
        .defaultValue(4).min(1).max(4).sliderMax(4).build());

    private final Setting<Integer> brewingDelay = sgPotion.add(new IntSetting.Builder()
        .name("brewing-delay").description("Delay in ticks when placing/removing items from brewing stand")
        .defaultValue(3).min(0).max(20).sliderMax(20).build());

    private final Setting<Boolean> useWater = sgPotion.add(new BoolSetting.Builder()
        .name("use-water").description("Use water source to fill bottles").defaultValue(false).build());

    private final Setting<Integer> waterSourceRange = sgPotion.add(new IntSetting.Builder()
        .name("water-source-range").description("Range to search for water sources")
        .defaultValue(12).min(1).max(32).sliderMax(32).visible(useWater::get).build());

    private final Setting<Integer> brewingStandsAmount = sgPotion.add(new IntSetting.Builder()
        .name("brewing-stands-amount").description("Number of brewing stands to use in rotation")
        .defaultValue(1).min(1).max(32).sliderMax(32).build());

    private final Setting<PotionForm> recipe = sgPotion.add(new EnumSetting.Builder<PotionForm>()
        .name("recipe").description("Drinkable or Splash potion").defaultValue(PotionForm.DRINKABLE).build());

    private final Setting<Boolean> useRedstone = sgPotion.add(new BoolSetting.Builder()
        .name("use-redstone").description("Add redstone to extend potion duration").defaultValue(false).build());

    private final Setting<Boolean> useGlowstone = sgPotion.add(new BoolSetting.Builder()
        .name("use-glowstone").description("Add glowstone to upgrade potion level").defaultValue(false).build());

    private final Setting<OutputMethod> output = sgPotion.add(new EnumSetting.Builder<OutputMethod>()
        .name("output").description("What to do with finished potions").defaultValue(OutputMethod.STORE_IN_CHEST).build());

    // Settings - Resources
    private final Setting<ResourceMethod> getResources = sgResources.add(new EnumSetting.Builder<ResourceMethod>()
        .name("get-resources").description("How to obtain brewing resources")
        .defaultValue(ResourceMethod.INVENTORY)
        .onModuleActivated(setting -> { if (setting.get() == null) setting.set(ResourceMethod.INVENTORY); })
        .build());

    private final Setting<Integer> nearbyChestRange = sgResources.add(new IntSetting.Builder()
        .name("nearby-chest-range").description("Range to search for nearby chest")
        .defaultValue(4).min(1).max(4).sliderMax(4)
        .visible(() -> getResources.get() == ResourceMethod.NEARBY_CHEST).build());

    // Settings - Send Profits (keeping original structure)
    private final Setting<Boolean> enableSendProfits = sgSendProfits.add(new BoolSetting.Builder()
        .name("enable").description("Enable automatic profit sending").defaultValue(false).build());

    private final Setting<String> profitPlayer = sgSendProfits.add(new StringSetting.Builder()
        .name("player").description("Player name to send profits to").defaultValue("").visible(enableSendProfits::get).build());

    private final Setting<Boolean> randomizeAmount = sgSendProfits.add(new BoolSetting.Builder()
        .name("randomize-amount").description("Randomize the amount sent")
        .defaultValue(false).visible(enableSendProfits::get).build());

    private final Setting<Integer> minimumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("minimum-percent").description("Minimum percentage of profit")
        .defaultValue(20).min(0).max(100).visible(() -> enableSendProfits.get() && randomizeAmount.get()).build());

    private final Setting<Integer> maximumPercent = sgSendProfits.add(new IntSetting.Builder()
        .name("maximum-percent").description("Maximum percentage of profit")
        .defaultValue(100).min(0).max(100).visible(() -> enableSendProfits.get() && randomizeAmount.get()).build());

    // Settings - Discord Webhook (keeping original structure)
    private final Setting<Boolean> webhook = sgWebhook.add(new BoolSetting.Builder()
        .name("webhook").description("Enable webhook notifications").defaultValue(false).build());

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
        .name("webhook-url").description("Discord webhook URL").defaultValue("").visible(webhook::get).build());

    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
        .name("self-ping").description("Ping yourself in webhook").defaultValue(false).visible(webhook::get).build());

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
        .name("discord-id").description("Your Discord user ID").defaultValue("")
        .visible(() -> webhook.get() && selfPing.get()).build());

    // State management
    private enum State {
        IDLE, WAIT_FOR_CHEST_SELECTION, OPEN_RESOURCE_CHEST, SCAN_RESOURCE_CHEST,
        TAKE_RESOURCES_FROM_CHEST, CLOSE_RESOURCE_CHEST, FIND_BREWING_STANDS, CHECK_RESOURCES,
        FIND_WATER_SOURCE, MOVE_TO_WATER, FILL_WATER_BOTTLES, SELECT_NEXT_BREWING_STAND,
        OPEN_BREWING_STAND, WAIT_FOR_BREWING_STAND_GUI, ADD_WATER_BOTTLES, ADD_BREWING_INGREDIENT,
        CLOSE_BREWING_STAND, WAIT_FOR_BREWING, CHECK_BREWING_COMPLETION, COLLECT_POTIONS, LOOP_DELAY
    }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private List<BrewingStandInfo> brewingStands = new ArrayList<>();
    private int currentBrewingStandIndex = 0;
    private int currentBrewingPhase = 0;
    private BlockPos selectedChestPos = null;
    private boolean waitingForChestSelection = false;
    private BlockPos waterSourcePos = null;
    private int bottleAdditionStep = 0; // Track which bottle/fuel we're adding

    private static class BrewingStandInfo {
        BlockPos pos;
        int cooldownTicks;
        int currentPhase;
        boolean isActive;
        BrewingStandInfo(BlockPos pos) {
            this.pos = pos;
            this.cooldownTicks = 0;
            this.currentPhase = 0;
            this.isActive = false;
        }
    }

    public AutoBrewer() {
        super(Main.CATEGORY, "auto-brewer", "Automatically brews potions");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();
        table.add(theme.label("Potion:     "));
        WHorizontalList potionList = table.add(theme.horizontalList()).expandX().widget();

        var label = potionList.add(theme.label(potionToBrew.toString())).expandX().widget();
        try {
            label.color = getPotionColor(potionToBrew.getEffect());
        } catch (Exception ignored) {}

        WButton selectButton = potionList.add(theme.button("Select")).widget();
        selectButton.action = () -> mc.setScreen(new PotionSelectionScreen(theme, this, mc.currentScreen));
        table.row();
        return table;
    }

    private Color getPotionColor(PotionEffect effect) {
        // Special colors for specific potions
        return switch (potionToBrew) {
            case REGENERATION -> new Color(255, 192, 203); // Pink
            case STRENGTH -> new Color(255, 0, 0); // Bright Red
            case SWIFTNESS -> new Color(173, 216, 230); // Light Blue
            case FIRE_RESISTANCE -> new Color(255, 165, 0); // Orange
            default -> switch (effect) {
                case HARMFUL -> new Color(139, 0, 0); // Dark Red
                case BENEFICIAL -> new Color(0, 200, 0); // Green
                case NEUTRAL -> new Color(128, 0, 128); // Purple
            };
        };
    }

    private class PotionSelectionScreen extends meteordevelopment.meteorclient.gui.WindowScreen {
        private final AutoBrewer module;
        private final net.minecraft.client.gui.screen.Screen parentScreen;
        private meteordevelopment.meteorclient.gui.widgets.input.WTextBox searchBox;
        private meteordevelopment.meteorclient.gui.widgets.containers.WTable potionTable;
        private String filter = "";

        PotionSelectionScreen(GuiTheme theme, AutoBrewer module, net.minecraft.client.gui.screen.Screen parentScreen) {
            super(theme, "Select Potion");
            this.module = module;
            this.parentScreen = parentScreen;
        }

        @Override
        public void initWidgets() {
            add(theme.label("Search: "));
            searchBox = add(theme.textBox("", (text, c) -> true)).minWidth(200).expandX().widget();
            searchBox.setFocused(true);
            searchBox.action = () -> {
                filter = searchBox.get();
                rebuildPotionList();
            };
            add(theme.horizontalSeparator()).expandX();
            potionTable = add(theme.table()).expandX().widget();
            rebuildPotionList();
        }

        private void rebuildPotionList() {
            potionTable.clear();
            for (PotionType potion : PotionType.values()) {
                if (filter.isEmpty() || potion.toString().toLowerCase().contains(filter.toLowerCase())) {
                    boolean selected = module.potionToBrew == potion;
                    WHorizontalList row = potionTable.add(theme.horizontalList()).expandX().widget();
                    row.add(theme.item(potion.getDisplayItemStack()));
                    var potionLabel = row.add(theme.label(potion.toString())).expandX().widget();
                    try {
                        potionLabel.color = getPotionColor(potion.getEffect());
                    } catch (Exception ignored) {}
                    WButton selectBtn = row.add(theme.button(selected ? "Selected" : "Select")).widget();
                    final PotionType finalPotion = potion;
                    selectBtn.action = () -> {
                        module.potionToBrew = finalPotion;
                        module.info("Selected: " + finalPotion.toString());
                        mc.setScreen(null);
                        mc.setScreen(parentScreen);
                        if (parentScreen instanceof meteordevelopment.meteorclient.gui.WidgetScreen widgetScreen) {
                            widgetScreen.reload();
                        }
                    };
                    potionTable.row();
                }
            }
        }
    }

    public Item[] getBrewingPhases() {
        Item[] basePhases = potionToBrew.getBrewingPhases();
        List<Item> phasesList = new ArrayList<>(Arrays.asList(basePhases));
        if (useRedstone.get()) phasesList.add(Items.REDSTONE);
        if (useGlowstone.get()) phasesList.add(Items.GLOWSTONE_DUST);
        if (recipe.get() == PotionForm.SPLASH) phasesList.add(Items.GUNPOWDER);
        return phasesList.toArray(new Item[0]);
    }

    @Override
    public void onActivate() {
        currentState = State.IDLE;
        delayCounter = 0;
        brewingStands.clear();
        currentBrewingStandIndex = 0;
        currentBrewingPhase = 0;
        selectedChestPos = null;
        waitingForChestSelection = false;
        waterSourcePos = null;
        bottleAdditionStep = 0;
        info("AutoBrewer activated - " + potionToBrew + " (" + recipe.get() + ")");
        if (getResources.get() == ResourceMethod.NEARBY_CHEST) {
            currentState = State.WAIT_FOR_CHEST_SELECTION;
            waitingForChestSelection = true;
            info("Left-click a chest to select it as the resource chest");
        }
    }

    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        delayCounter = 0;
        brewingStands.clear();
        selectedChestPos = null;
        waitingForChestSelection = false;
        waterSourcePos = null;
        if (mc.currentScreen != null && mc.player != null) mc.player.closeHandledScreen();
        info("AutoBrewer deactivated");
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!waitingForChestSelection) return;
        if (event.packet instanceof PlayerInteractBlockC2SPacket packet) {
            BlockPos pos = packet.getBlockHitResult().getBlockPos();
            if (mc.world != null && mc.world.getBlockState(pos).getBlock() == Blocks.CHEST) {
                selectedChestPos = pos.toImmutable();
                info("Chest selected at: " + selectedChestPos);
                waitingForChestSelection = false;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        for (BrewingStandInfo stand : brewingStands) {
            if (stand.cooldownTicks > 0) stand.cooldownTicks--;
        }
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }
        handleState();
    }

    private void handleState() {
        switch (currentState) {
            case IDLE -> {
                info("Starting brewing sequence...");
                currentState = State.FIND_BREWING_STANDS;
                delayCounter = actionDelay.get();
            }
            case WAIT_FOR_CHEST_SELECTION -> {
                if (selectedChestPos != null) {
                    currentState = State.OPEN_RESOURCE_CHEST;
                    delayCounter = actionDelay.get();
                }
            }
            case OPEN_RESOURCE_CHEST -> {
                info("Opening resource chest at " + selectedChestPos);
                if (openChest(selectedChestPos)) {
                    currentState = State.SCAN_RESOURCE_CHEST;
                    delayCounter = actionDelay.get() * 2;
                } else {
                    error("Failed to open resource chest!");
                    toggle();
                }
            }
            case SCAN_RESOURCE_CHEST -> {
                if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.GenericContainerScreen) {
                    info("Scanning resource chest for required items...");
                    if (scanAndTakeResourcesFromChest()) {
                        info("Successfully took resources from chest");
                        currentState = State.CLOSE_RESOURCE_CHEST;
                        delayCounter = brewingDelay.get();
                    } else {
                        error("Failed to find all required resources in chest!");
                        currentState = State.CLOSE_RESOURCE_CHEST;
                        delayCounter = brewingDelay.get();
                    }
                } else {
                    info("Waiting for chest GUI to open...");
                    delayCounter = 5;
                }
            }
            case TAKE_RESOURCES_FROM_CHEST -> {
                // This state is now handled in SCAN_RESOURCE_CHEST
                currentState = State.CLOSE_RESOURCE_CHEST;
                delayCounter = brewingDelay.get();
            }
            case CLOSE_RESOURCE_CHEST -> {
                if (mc.currentScreen != null && mc.player != null) {
                    mc.player.closeHandledScreen();
                }
                info("Closed resource chest");
                currentState = State.FIND_BREWING_STANDS;
                delayCounter = actionDelay.get();
            }
            case FIND_BREWING_STANDS -> {
                info("Finding brewing stands...");
                brewingStands = findBrewingStands();
                int requestedAmount = brewingStandsAmount.get();
                if (brewingStands.isEmpty()) {
                    error("No brewing stands found nearby!");
                    toggle();
                    return;
                } else if (brewingStands.size() < requestedAmount) {
                    info("Found " + brewingStands.size() + " brewing stands (requested: " + requestedAmount + ")");
                } else {
                    brewingStands = brewingStands.subList(0, requestedAmount);
                    info("Using " + brewingStands.size() + " brewing stands");
                }
                currentBrewingStandIndex = 0;
                currentBrewingPhase = 0;
                currentState = State.CHECK_RESOURCES;
                delayCounter = actionDelay.get();
            }
            case CHECK_RESOURCES -> {
                info("Checking resources...");
                if (useWater.get()) {
                    int emptyBottles = InvUtils.find(Items.GLASS_BOTTLE).count();
                    int waterBottles = InvUtils.find(Items.POTION).count();
                    int standsCount = Math.min(brewingStands.size(), brewingStandsAmount.get());
                    int requiredBottles = standsCount * 3;
                    if (emptyBottles > 0 && waterBottles < requiredBottles) {
                        info("Found " + emptyBottles + " empty glass bottles, need to fill them");
                        currentState = State.FIND_WATER_SOURCE;
                        delayCounter = actionDelay.get();
                        return;
                    }
                }
                if (!hasRequiredIngredients()) {
                    error("Missing required ingredients!");
                    toggle();
                    return;
                }
                currentState = State.SELECT_NEXT_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case FIND_WATER_SOURCE -> {
                info("Finding water source...");
                waterSourcePos = findNearestWaterSource();
                if (waterSourcePos == null) {
                    error("No water source found within range!");
                    toggle();
                    return;
                }
                info("Found water source at " + waterSourcePos);
                currentState = State.MOVE_TO_WATER;
                delayCounter = actionDelay.get();
            }
            case MOVE_TO_WATER -> {
                double distance = mc.player.squaredDistanceTo(
                    waterSourcePos.getX() + 0.5,
                    waterSourcePos.getY() + 0.5,
                    waterSourcePos.getZ() + 0.5
                );
                if (distance <= 16) {
                    info("Close enough to water source");
                    currentState = State.FILL_WATER_BOTTLES;
                    delayCounter = actionDelay.get();
                } else {
                    info("Moving towards water source...");
                    delayCounter = actionDelay.get() * 2;
                }
            }
            case FILL_WATER_BOTTLES -> {
                int emptyBottles = InvUtils.find(Items.GLASS_BOTTLE).count();
                int waterBottles = InvUtils.find(Items.POTION).count();
                int standsCount = Math.min(brewingStands.size(), brewingStandsAmount.get());
                int requiredBottles = standsCount * 3;
                if (emptyBottles == 0 || waterBottles >= requiredBottles) {
                    info("Bottle filling complete! Water bottles: " + waterBottles);
                    currentState = State.CHECK_RESOURCES;
                    delayCounter = actionDelay.get();
                    return;
                }
                info("Filling water bottles... (" + emptyBottles + " empty bottles remaining, " + waterBottles + "/" + requiredBottles + " water bottles)");
                if (fillBottleWithWater(waterSourcePos)) {
                    delayCounter = actionDelay.get() * 2;
                } else {
                    error("Failed to fill water bottle!");
                    currentState = State.CHECK_RESOURCES;
                    delayCounter = actionDelay.get();
                }
            }
            case SELECT_NEXT_BREWING_STAND -> {
                BrewingStandInfo nextStand = null;
                int startIndex = currentBrewingStandIndex;
                do {
                    BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);
                    if (stand.cooldownTicks == 0) {
                        nextStand = stand;
                        break;
                    }
                    currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                } while (currentBrewingStandIndex != startIndex);
                if (nextStand == null) {
                    info("All brewing stands are busy, waiting...");
                    delayCounter = 20;
                    return;
                }
                info("Selected brewing stand " + (currentBrewingStandIndex + 1) + "/" + brewingStands.size());
                currentState = State.OPEN_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case OPEN_BREWING_STAND -> {
                if (mc.currentScreen instanceof BrewingStandScreen) {
                    info("Brewing stand GUI already open");
                    currentState = State.ADD_WATER_BOTTLES;
                    delayCounter = brewingDelay.get();
                    return;
                }
                BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);
                info("Opening brewing stand at " + stand.pos);
                if (openBrewingStand(stand.pos)) {
                    currentState = State.WAIT_FOR_BREWING_STAND_GUI;
                    delayCounter = actionDelay.get() * 2;
                } else {
                    error("Failed to open brewing stand!");
                    toggle();
                }
            }
            case WAIT_FOR_BREWING_STAND_GUI -> {
                if (mc.currentScreen instanceof BrewingStandScreen) {
                    info("Brewing stand GUI opened");
                    BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);
                    if (stand.currentPhase == 0) {
                        bottleAdditionStep = 0; // Reset for water bottle addition
                        currentState = State.ADD_WATER_BOTTLES;
                    } else {
                        currentState = State.ADD_BREWING_INGREDIENT;
                    }
                    delayCounter = brewingDelay.get();
                } else {
                    info("Still waiting for brewing stand GUI...");
                }
            }
            case ADD_WATER_BOTTLES -> {
                if (addWaterBottlesToBrewingStandOneByOne()) {
                    // Still adding bottles/fuel
                    delayCounter = brewingDelay.get();
                } else {
                    // All bottles and fuel added
                    info("All water bottles and fuel added");
                    BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);
                    stand.currentPhase = 0;
                    bottleAdditionStep = 0;
                    currentState = State.ADD_BREWING_INGREDIENT;
                    delayCounter = brewingDelay.get();
                }
            }
            case ADD_BREWING_INGREDIENT -> {
                BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);
                Item[] phases = getBrewingPhases();
                if (stand.currentPhase >= phases.length) {
                    info("All brewing phases complete for stand " + (currentBrewingStandIndex + 1));
                    currentState = State.CLOSE_BREWING_STAND;
                    delayCounter = brewingDelay.get();
                    return;
                }
                Item ingredient = phases[stand.currentPhase];
                info("Adding ingredient: " + Names.get(ingredient) + " (Phase " + (stand.currentPhase + 1) + "/" + phases.length + ")");
                if (addIngredientToBrewingStand(ingredient)) {
                    stand.currentPhase++;
                    stand.cooldownTicks = 420;
                    stand.isActive = true;
                    currentState = State.CLOSE_BREWING_STAND;
                    delayCounter = brewingDelay.get();
                } else {
                    error("Failed to add ingredient!");
                    toggle();
                }
            }

            // Continuation of AutoBrewer.java - Add these methods to complete the class

            case CLOSE_BREWING_STAND -> {
                if (mc.currentScreen != null && mc.player != null) {
                    mc.player.closeHandledScreen();
                }
                info("Closed brewing stand GUI");
                currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();

                Item[] phases = getBrewingPhases();
                boolean allComplete = brewingStands.stream().allMatch(s -> s.currentPhase >= phases.length);

                if (allComplete) {
                    info("All brewing stands have completed all phases!");
                    currentState = State.LOOP_DELAY;
                    delayCounter = loopDelay.get();
                } else {
                    currentState = State.SELECT_NEXT_BREWING_STAND;
                    delayCounter = actionDelay.get();
                }
            }
            case LOOP_DELAY -> {
                info("Sequence complete, entering loop delay");
                for (BrewingStandInfo stand : brewingStands) {
                    stand.currentPhase = 0;
                    stand.cooldownTicks = 0;
                    stand.isActive = false;
                }
                currentBrewingStandIndex = 0;
                currentBrewingPhase = 0;
                currentState = State.IDLE;
            }
        }
    }

    private List<BrewingStandInfo> findBrewingStands() {
        List<BrewingStandInfo> stands = new ArrayList<>();
        if (mc.world == null || mc.player == null) return stands;
        BlockPos playerPos = mc.player.getBlockPos();
        int range = brewingStandRange.get();
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockEntity(pos) instanceof BrewingStandBlockEntity) {
                        double dist = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (dist <= 9) {
                            stands.add(new BrewingStandInfo(pos));
                        }
                    }
                }
            }
        }
        stands.sort((a, b) -> {
            double distA = mc.player.squaredDistanceTo(a.pos.getX() + 0.5, a.pos.getY() + 0.5, a.pos.getZ() + 0.5);
            double distB = mc.player.squaredDistanceTo(b.pos.getX() + 0.5, b.pos.getY() + 0.5, b.pos.getZ() + 0.5);
            return Double.compare(distA, distB);
        });
        return stands;
    }

    private boolean hasRequiredIngredients() {
        if (mc.player == null) return false;
        Item[] phases = getBrewingPhases();
        int standsCount = Math.min(brewingStands.size(), brewingStandsAmount.get());
        for (Item ingredient : phases) {
            int required = standsCount;
            int available = InvUtils.find(ingredient).count();
            if (available < required) {
                error("Missing ingredient: " + Names.get(ingredient) + " (need " + required + ", have " + available + ")");
                return false;
            }
        }
        int requiredBottles = standsCount * 3;
        int availableBottles = InvUtils.find(Items.POTION).count();
        if (availableBottles < requiredBottles) {
            error("Missing water bottles! Need " + requiredBottles + ", have " + availableBottles);
            return false;
        }
        int requiredFuel = standsCount;
        int availableFuel = InvUtils.find(Items.BLAZE_POWDER).count();
        if (availableFuel < requiredFuel) {
            error("Missing blaze powder! Need " + requiredFuel + ", have " + availableFuel);
            return false;
        }
        return true;
    }

    private boolean openBrewingStand(BlockPos pos) {
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

    private boolean openChest(BlockPos pos) {
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

    private boolean scanAndTakeResourcesFromChest() {
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.GenericContainerScreen screen)) return false;

        var handler = screen.getScreenHandler();
        Item[] phases = getBrewingPhases();
        int standsCount = Math.min(brewingStands.size(), brewingStandsAmount.get());

        // Calculate what we need
        Map<Item, Integer> requiredItems = new HashMap<>();

        // Add brewing phase ingredients
        for (Item ingredient : phases) {
            requiredItems.put(ingredient, requiredItems.getOrDefault(ingredient, 0) + standsCount);
        }

        // Add water bottles (3 per stand)
        requiredItems.put(Items.POTION, standsCount * 3);

        // Add blaze powder for fuel
        requiredItems.put(Items.BLAZE_POWDER, standsCount);

        // Scan chest for items and take them
        int containerSlots = handler.getRows() * 9;
        boolean foundAll = true;

        info("Required items:");
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            info("  - " + Names.get(entry.getKey()) + " x" + entry.getValue());
        }

        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            int found = 0;

            // Search chest slots for this item
            for (int slot = 0; slot < containerSlots; slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (stack.getItem() == item) {
                    int toTake = Math.min(needed - found, stack.getCount());

                    if (toTake > 0) {
                        // Shift-click to take items
                        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        found += toTake;
                        info("Taking " + toTake + "x " + Names.get(item) + " from chest");
                    }

                    if (found >= needed) break;
                }
            }

            if (found < needed) {
                error("Only found " + found + "/" + needed + " of " + Names.get(item));
                foundAll = false;
            }
        }

        return foundAll;
    }

    private boolean addWaterBottlesToBrewingStandOneByOne() {
        if (!(mc.currentScreen instanceof BrewingStandScreen screen)) return false;
        var handler = screen.getScreenHandler();

        // Step 0-2: Add water bottles to slots 0, 1, 2
        if (bottleAdditionStep <= 2) {
            int slot = bottleAdditionStep;
            ItemStack currentStack = handler.getSlot(slot).getStack();

            if (currentStack.isEmpty()) {
                int waterBottleSlot = findItemInInventory(Items.POTION);
                if (waterBottleSlot == -1) {
                    error("No water bottles found in inventory!");
                    return false;
                }

                // Pick up the whole stack with left-click
                mc.interactionManager.clickSlot(handler.syncId, waterBottleSlot, 0, SlotActionType.PICKUP, mc.player);

                // Place ONE bottle by right-clicking on the brewing stand slot
                mc.interactionManager.clickSlot(handler.syncId, slot, 1, SlotActionType.PICKUP, mc.player);

                // Return the rest to the original slot
                mc.interactionManager.clickSlot(handler.syncId, waterBottleSlot, 0, SlotActionType.PICKUP, mc.player);

                info("Added water bottle to slot " + slot);
            }

            bottleAdditionStep++;
            return true; // Continue adding
        }

        // Step 3: Add blaze powder as fuel (slot 4)
        if (bottleAdditionStep == 3) {
            ItemStack fuelStack = handler.getSlot(4).getStack();
            if (fuelStack.isEmpty() || fuelStack.getItem() != Items.BLAZE_POWDER) {
                int blazePowderSlot = findItemInInventory(Items.BLAZE_POWDER);
                if (blazePowderSlot != -1) {
                    // Pick up the whole stack
                    mc.interactionManager.clickSlot(handler.syncId, blazePowderSlot, 0, SlotActionType.PICKUP, mc.player);

                    // Place ONE by right-clicking
                    mc.interactionManager.clickSlot(handler.syncId, 4, 1, SlotActionType.PICKUP, mc.player);

                    // Return the rest
                    mc.interactionManager.clickSlot(handler.syncId, blazePowderSlot, 0, SlotActionType.PICKUP, mc.player);

                    info("Added blaze powder as fuel");
                }
            }

            bottleAdditionStep++;
            return false; // Done adding
        }

        return false; // All done
    }

    private boolean addIngredientToBrewingStand(Item ingredient) {
        if (!(mc.currentScreen instanceof BrewingStandScreen screen)) return false;
        var handler = screen.getScreenHandler();

        // Slot 3 is the ingredient slot
        ItemStack currentIngredient = handler.getSlot(3).getStack();

        // If there's already an ingredient in slot 3, remove it first
        if (!currentIngredient.isEmpty()) {
            info("Removing old ingredient from slot 3: " + Names.get(currentIngredient.getItem()));
            // Pick up the old ingredient
            mc.interactionManager.clickSlot(handler.syncId, 3, 0, SlotActionType.PICKUP, mc.player);
            // Find empty slot in inventory to place it
            for (int i = 5; i < handler.slots.size(); i++) {
                ItemStack invStack = handler.getSlot(i).getStack();
                if (invStack.isEmpty() || (invStack.getItem() == currentIngredient.getItem() && invStack.getCount() < invStack.getMaxCount())) {
                    mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                    break;
                }
            }
            return true; // Wait for next tick to add new ingredient
        }

        // Now add the new ingredient
        int ingredientSlot = findItemInInventory(ingredient);
        if (ingredientSlot == -1) {
            error("Ingredient " + Names.get(ingredient) + " not found in inventory!");
            return false;
        }

        // Pick up the whole stack with left-click
        mc.interactionManager.clickSlot(handler.syncId, ingredientSlot, 0, SlotActionType.PICKUP, mc.player);

        // Place ONE item by right-clicking on the ingredient slot
        mc.interactionManager.clickSlot(handler.syncId, 3, 1, SlotActionType.PICKUP, mc.player);

        // Return the rest of the stack to the original slot
        mc.interactionManager.clickSlot(handler.syncId, ingredientSlot, 0, SlotActionType.PICKUP, mc.player);

        info("Added ONE " + Names.get(ingredient) + " to brewing stand");
        return true;
    }

    private int findItemInInventory(Item item) {
        if (mc.currentScreen instanceof BrewingStandScreen screen) {
            var handler = screen.getScreenHandler();
            for (int i = 5; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.getItem() == item) {
                    return i;
                }
            }
        }
        return -1;
    }

    private BlockPos findNearestWaterSource() {
        if (mc.world == null || mc.player == null) return null;
        if (!useWater.get()) return null;
        BlockPos playerPos = mc.player.getBlockPos();
        int range = waterSourceRange.get();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    var blockState = mc.world.getBlockState(pos);
                    if (blockState.getBlock() == Blocks.WATER || blockState.getBlock() == Blocks.WATER_CAULDRON) {
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

    private boolean fillBottleWithWater(BlockPos waterPos) {
        if (mc.player == null || mc.interactionManager == null) return false;
        int waterBottles = InvUtils.find(Items.POTION).count();
        int standsCount = Math.min(brewingStands.size(), brewingStandsAmount.get());
        int requiredBottles = standsCount * 3;
        if (waterBottles >= requiredBottles) {
            info("Already have enough water bottles");
            return false;
        }
        int bottleSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.GLASS_BOTTLE) {
                bottleSlot = i;
                break;
            }
        }
        if (bottleSlot == -1) {
            int invSlot = InvUtils.find(Items.GLASS_BOTTLE).slot();
            if (invSlot == -1) {
                info("No more glass bottles to fill");
                return false;
            }
            InvUtils.move().from(invSlot).toHotbar(0);
            bottleSlot = 0;
            delayCounter = actionDelay.get();
            return true;
        }
        InvUtils.swap(bottleSlot, false);
        Vec3d hitVec = Vec3d.ofCenter(waterPos);
        Direction side = Direction.UP;
        if (smoothAim.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), rotationSpeed.get(), () -> {
                BlockHitResult hitResult = new BlockHitResult(hitVec, side, waterPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            });
        } else {
            BlockHitResult hitResult = new BlockHitResult(hitVec, side, waterPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }
        return true;
    }

    private void info(String s) {
        if (!silentMode.get()) {
            super.info(s);
        }
    }

    private void error(String s) {
        if (!silentMode.get() || !hideErrors.get()) {
            super.error(s);
        }
    }
}
