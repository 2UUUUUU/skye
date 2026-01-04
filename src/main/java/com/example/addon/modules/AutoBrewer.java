package com.example.addon.modules;

import com.example.addon.Main;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.events.world.TickEvent;
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

import java.util.*;

public class AutoBrewer extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPotion = settings.createGroup("Potion");
    private final SettingGroup sgResources = settings.createGroup("Resources");
    private final SettingGroup sgSendProfits = settings.createGroup("Send Profits");
    private final SettingGroup sgWebhook = settings.createGroup("Discord Webhook");

    // Potion Type Enum
    public enum PotionType {
        INSTANT_DAMAGE("Instant Damage", Items.POTION, new Item[]{Items.NETHER_WART, Items.SPIDER_EYE, Items.FERMENTED_SPIDER_EYE}),
        POISON("Poison", Items.POTION, new Item[]{Items.NETHER_WART, Items.SPIDER_EYE});

        private final String name;
        private final Item displayItem;
        private final Item[] brewingPhases;

        PotionType(String name, Item displayItem, Item[] brewingPhases) {
            this.name = name;
            this.displayItem = displayItem;
            this.brewingPhases = brewingPhases;
        }

        public String getName() {
            return name;
        }

        public Item getDisplayItem() {
            return displayItem;
        }

        public Item[] getBrewingPhases() {
            return brewingPhases;
        }

        public ItemStack getDisplayItemStack() {
            return new ItemStack(displayItem);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum PotionForm {
        DRINKABLE("Drinkable Potion", Items.POTION),
        SPLASH("Splash Potion", Items.SPLASH_POTION);

        private final String name;
        private final Item resultItem;

        PotionForm(String name, Item resultItem) {
            this.name = name;
            this.resultItem = resultItem;
        }

        public String getName() {
            return name;
        }

        public Item getResultItem() {
            return resultItem;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum ResourceMethod {
        INVENTORY("Inventory"),
        AUCTION_HOUSE("Auction House"),
        BUY_ORDER("Buy Order"),
        NEARBY_CHEST("Nearby Chest");

        private final String name;

        ResourceMethod(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Manual potion selection field
    private PotionType potionToBrew = PotionType.INSTANT_DAMAGE;

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
        .description("Delay in ticks between sequence loops")
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

    // Potion Settings
    private final Setting<Integer> brewingStandRange = sgPotion.add(new IntSetting.Builder()
        .name("brewing-stand-range")
        .description("Range to search for brewing stands")
        .defaultValue(4)
        .min(1)
        .max(4)
        .sliderMax(4)
        .build());

    private final Setting<Integer> brewingDelay = sgPotion.add(new IntSetting.Builder()
        .name("brewing-delay")
        .description("Delay in ticks when placing/removing items from brewing stand")
        .defaultValue(3)
        .min(0)
        .max(20)
        .sliderMax(20)
        .build());

    private final Setting<Boolean> useWater = sgPotion.add(new BoolSetting.Builder()
        .name("use-water")
        .description("Use water source to fill bottles")
        .defaultValue(false)
        .build());

    private final Setting<Integer> waterSourceRange = sgPotion.add(new IntSetting.Builder()
        .name("water-source-range")
        .description("Range to search for water sources")
        .defaultValue(12)
        .min(1)
        .max(32)
        .sliderMax(32)
        .visible(useWater::get)
        .build());

    private final Setting<Integer> brewingStandsAmount = sgPotion.add(new IntSetting.Builder()
        .name("brewing-stands-amount")
        .description("Number of brewing stands to use in rotation")
        .defaultValue(1)
        .min(1)
        .max(32)
        .sliderMax(32)
        .build());

    private final Setting<PotionForm> recipe = sgPotion.add(new EnumSetting.Builder<PotionForm>()
        .name("recipe")
        .description("Drinkable or Splash potion")
        .defaultValue(PotionForm.DRINKABLE)
        .build());

    private final Setting<Boolean> useRedstone = sgPotion.add(new BoolSetting.Builder()
        .name("use-redstone")
        .description("Add redstone to extend potion duration")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> useGlowstone = sgPotion.add(new BoolSetting.Builder()
        .name("use-glowstone")
        .description("Add glowstone to upgrade potion level")
        .defaultValue(false)
        .build());

    // Resources Settings
    private final Setting<ResourceMethod> getResources = sgResources.add(new EnumSetting.Builder<ResourceMethod>()
        .name("get-resources")
        .description("How to obtain brewing resources")
        .defaultValue(ResourceMethod.INVENTORY)
        .onModuleActivated(setting -> {
            if (setting.get() == null) setting.set(ResourceMethod.INVENTORY);
        })
        .build());

    private final Setting<Integer> nearbyChestRange = sgResources.add(new IntSetting.Builder()
        .name("nearby-chest-range")
        .description("Range to search for nearby chest")
        .defaultValue(4)
        .min(1)
        .max(4)
        .sliderMax(4)
        .visible(() -> getResources.get() == ResourceMethod.NEARBY_CHEST)
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

    // State Variables
    private enum State {
        IDLE,
        WAIT_FOR_CHEST_SELECTION,
        FIND_BREWING_STANDS,
        CHECK_RESOURCES,
        FIND_WATER_SOURCE,
        MOVE_TO_WATER,
        FILL_WATER_BOTTLES,
        SELECT_NEXT_BREWING_STAND,
        OPEN_BREWING_STAND,
        WAIT_FOR_BREWING_STAND_GUI,
        ADD_WATER_BOTTLES,
        ADD_BREWING_INGREDIENT,
        CLOSE_BREWING_STAND,
        WAIT_FOR_BREWING,
        CHECK_BREWING_COMPLETION,
        COLLECT_POTIONS,
        LOOP_DELAY
    }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private List<BrewingStandInfo> brewingStands = new ArrayList<>();
    private int currentBrewingStandIndex = 0;
    private int currentBrewingPhase = 0;
    private BlockPos selectedChestPos = null;
    private boolean waitingForChestSelection = false;
    private BlockPos waterSourcePos = null;

    // Brewing Stand tracking
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

    // Override getWidget to create custom potion selector GUI
    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();

        // Add label for Potion To Brew
        table.add(theme.label("Potion:     "));

        // Row: Potion to Brew selector
        WHorizontalList potionList = table.add(theme.horizontalList()).expandX().widget();

        // Show potion name with custom color
        var label = potionList.add(theme.label(potionToBrew.toString())).expandX().widget();
        try {
            label.color = new Color(85, 255, 255); // Aqua/Cyan color
        } catch (Exception e) {
            // If color property doesn't exist, just use default
        }

        // Select button
        WButton selectButton = potionList.add(theme.button("Select")).widget();
        selectButton.action = () -> mc.setScreen(new PotionSelectionScreen(theme, this, mc.currentScreen));

        table.row();

        return table;
    }

    // Potion selection GUI
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
                    row.add(theme.label(potion.toString())).expandX();

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

    // Helper getters
    public PotionType getSelectedPotionType() {
        return potionToBrew;
    }

    public PotionForm getSelectedPotionForm() {
        return recipe.get();
    }

    public Item getResultItem() {
        return recipe.get().getResultItem();
    }

    public Item[] getBrewingPhases() {
        Item[] basePhases = potionToBrew.getBrewingPhases();
        List<Item> phasesList = new ArrayList<>(Arrays.asList(basePhases));

        // Add redstone before glowstone/gunpowder if enabled
        if (useRedstone.get()) {
            phasesList.add(Items.REDSTONE);
        }

        // Add glowstone before gunpowder if enabled
        if (useGlowstone.get()) {
            phasesList.add(Items.GLOWSTONE_DUST);
        }

        // Add gunpowder last if splash potion
        if (recipe.get() == PotionForm.SPLASH) {
            phasesList.add(Items.GUNPOWDER);
        }

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

        info("AutoBrewer activated - " + potionToBrew + " (" + recipe.get() + ")");

        // If using nearby chest, wait for selection
        if (getResources.get() == ResourceMethod.NEARBY_CHEST) {
            currentState = State.WAIT_FOR_CHEST_SELECTION;
            waitingForChestSelection = true;
            info("Right-click a chest to select it as the resource chest");
        }
    }

    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        delayCounter = 0;
        brewingStands.clear();
        currentBrewingStandIndex = 0;
        currentBrewingPhase = 0;
        selectedChestPos = null;
        waitingForChestSelection = false;
        waterSourcePos = null;
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        info("AutoBrewer deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Tick cooldowns for all brewing stands
        for (BrewingStandInfo stand : brewingStands) {
            if (stand.cooldownTicks > 0) {
                stand.cooldownTicks--;
            }
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
                // This state waits for the player to right-click a chest
                // The chest selection is handled by the user manually
                if (selectedChestPos != null) {
                    info("Chest selected at: " + selectedChestPos);
                    waitingForChestSelection = false;
                    currentState = State.FIND_BREWING_STANDS;
                    delayCounter = actionDelay.get();
                }
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
                    // Limit to requested amount
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

                // If Use Water is enabled, check if we need to fill bottles first
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
                // Check if we're close enough to the water source
                double distance = mc.player.squaredDistanceTo(
                    waterSourcePos.getX() + 0.5,
                    waterSourcePos.getY() + 0.5,
                    waterSourcePos.getZ() + 0.5
                );

                if (distance <= 16) { // Within 4 blocks
                    info("Close enough to water source");
                    currentState = State.FILL_WATER_BOTTLES;
                    delayCounter = actionDelay.get();
                } else {
                    info("Moving towards water source...");
                    // Just wait and let player move, or we could add movement logic
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
                    // Successfully filled one bottle, wait before next fill
                    delayCounter = actionDelay.get() * 2;
                } else {
                    error("Failed to fill water bottle!");
                    // Try to continue anyway
                    currentState = State.CHECK_RESOURCES;
                    delayCounter = actionDelay.get();
                }
            }

            case SELECT_NEXT_BREWING_STAND -> {
                // Find next available brewing stand (one without cooldown)
                BrewingStandInfo nextStand = null;
                int startIndex = currentBrewingStandIndex;

                do {
                    BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);

                    // Check if this stand is ready (no cooldown or cooldown expired)
                    if (stand.cooldownTicks == 0) {
                        nextStand = stand;
                        break;
                    }

                    // Move to next stand
                    currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();

                } while (currentBrewingStandIndex != startIndex);

                if (nextStand == null) {
                    // All stands are on cooldown, wait
                    info("All brewing stands are busy, waiting...");
                    delayCounter = 20; // Wait 1 second
                    return;
                }

                info("Selected brewing stand " + (currentBrewingStandIndex + 1) + "/" + brewingStands.size());
                currentState = State.OPEN_BREWING_STAND;
                delayCounter = actionDelay.get();
            }

            case OPEN_BREWING_STAND -> {
                // Check if GUI already open
                if (mc.currentScreen instanceof BrewingStandScreen) {
                    info("Brewing stand GUI already open");
                    currentState = State.ADD_WATER_BOTTLES;
                    delayCounter = actionDelay.get();
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

                    // Determine what to add based on current phase
                    if (stand.currentPhase == 0) {
                        currentState = State.ADD_WATER_BOTTLES;
                    } else {
                        currentState = State.ADD_BREWING_INGREDIENT;
                    }
                    delayCounter = actionDelay.get();
                } else {
                    info("Still waiting for brewing stand GUI...");
                }
            }

            case ADD_WATER_BOTTLES -> {
                info("Adding water bottles...");

                if (addWaterBottlesToBrewingStand()) {
                    BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);
                    stand.currentPhase = 0; // Mark as phase 0 (water bottles added)
                    currentState = State.ADD_BREWING_INGREDIENT;
                    delayCounter = brewingDelay.get();
                } else {
                    error("Failed to add water bottles!");
                    toggle();
                }
            }

            case ADD_BREWING_INGREDIENT -> {
                BrewingStandInfo stand = brewingStands.get(currentBrewingStandIndex);
                Item[] phases = getBrewingPhases();

                if (stand.currentPhase >= phases.length) {
                    // All phases complete for this stand
                    info("All brewing phases complete for stand " + (currentBrewingStandIndex + 1));
                    currentState = State.CLOSE_BREWING_STAND;
                    delayCounter = brewingDelay.get();
                    return;
                }

                Item ingredient = phases[stand.currentPhase];
                info("Adding ingredient: " + Names.get(ingredient) + " (Phase " + (stand.currentPhase + 1) + "/" + phases.length + ")");

                if (addIngredientToBrewingStand(ingredient)) {
                    stand.currentPhase++;
                    stand.cooldownTicks = 420; // 21 seconds brewing time
                    stand.isActive = true;
                    currentState = State.CLOSE_BREWING_STAND;
                    delayCounter = brewingDelay.get();
                } else {
                    error("Failed to add ingredient!");
                    toggle();
                }
            }

            case CLOSE_BREWING_STAND -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                info("Closed brewing stand GUI");

                // Move to next stand
                currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();

                // Check if all stands are at the same phase
                boolean allSamePhase = true;
                int firstPhase = brewingStands.get(0).currentPhase;
                for (BrewingStandInfo stand : brewingStands) {
                    if (stand.currentPhase != firstPhase) {
                        allSamePhase = false;
                        break;
                    }
                }

                Item[] phases = getBrewingPhases();
                boolean allComplete = brewingStands.stream().allMatch(s -> s.currentPhase >= phases.length);

                if (allComplete) {
                    // All brewing complete
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

                // Reset all stands for next cycle
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
                        // Only use stands within 3 blocks for interaction
                        if (dist <= 9) { // 3 blocks squared
                            stands.add(new BrewingStandInfo(pos));
                        }
                    }
                }
            }
        }

        // Sort by distance
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

        // Check ingredients for all phases
        for (Item ingredient : phases) {
            int required = standsCount; // One ingredient per stand per phase
            int available = InvUtils.find(ingredient).count();
            if (available < required) {
                error("Missing ingredient: " + Names.get(ingredient) + " (need " + required + ", have " + available + ")");
                return false;
            }
        }

        // Check water bottles (3 per stand)
        int requiredBottles = standsCount * 3;
        int availableBottles = InvUtils.find(Items.POTION).count();
        if (availableBottles < requiredBottles) {
            error("Missing water bottles! Need " + requiredBottles + ", have " + availableBottles);
            return false;
        }

        // Check blaze powder for fuel
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

    private boolean addWaterBottlesToBrewingStand() {
        if (!(mc.currentScreen instanceof BrewingStandScreen screen)) return false;

        var handler = screen.getScreenHandler();

        // Brewing stand slots: 0, 1, 2 are potion slots, 3 is ingredient slot, 4 is fuel slot
        for (int slot = 0; slot <= 2; slot++) {
            ItemStack currentStack = handler.getSlot(slot).getStack();
            if (currentStack.isEmpty()) {
                // Find water bottle in inventory
                int waterBottleSlot = findItemInInventory(Items.POTION);
                if (waterBottleSlot == -1) {
                    error("No water bottles found in inventory!");
                    return false;
                }

                // Pick up ONE water bottle (right-click to split stack)
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    waterBottleSlot,
                    1, // Right click to pick up half/one
                    SlotActionType.PICKUP,
                    mc.player
                );

                // Place it in the brewing stand slot
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    slot,
                    0, // Left click to place
                    SlotActionType.PICKUP,
                    mc.player
                );

                // Return remaining bottles to inventory if any on cursor
                ItemStack cursorStack = handler.getCursorStack();
                if (!cursorStack.isEmpty()) {
                    mc.interactionManager.clickSlot(
                        handler.syncId,
                        waterBottleSlot,
                        0, // Left click to return
                        SlotActionType.PICKUP,
                        mc.player
                    );
                }

                info("Added water bottle to slot " + slot);
            }
        }

        // Add blaze powder as fuel if needed
        ItemStack fuelStack = handler.getSlot(4).getStack();
        if (fuelStack.isEmpty() || fuelStack.getItem() != Items.BLAZE_POWDER) {
            int blazePowderSlot = findItemInInventory(Items.BLAZE_POWDER);
            if (blazePowderSlot != -1) {
                // Pick up ONE blaze powder
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    blazePowderSlot,
                    1, // Right click
                    SlotActionType.PICKUP,
                    mc.player
                );

                // Place in fuel slot
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    4,
                    0, // Left click
                    SlotActionType.PICKUP,
                    mc.player
                );

                // Return remaining if any
                ItemStack cursorStack = handler.getCursorStack();
                if (!cursorStack.isEmpty()) {
                    mc.interactionManager.clickSlot(
                        handler.syncId,
                        blazePowderSlot,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );
                }

                info("Added blaze powder as fuel");
            }
        }

        return true;
    }

    private boolean addIngredientToBrewingStand(Item ingredient) {
        if (!(mc.currentScreen instanceof BrewingStandScreen screen)) return false;

        var handler = screen.getScreenHandler();

        // Slot 3 is the ingredient slot
        ItemStack currentIngredient = handler.getSlot(3).getStack();
        if (currentIngredient.isEmpty()) {
            // Find ingredient in inventory
            int ingredientSlot = findItemInInventory(ingredient);
            if (ingredientSlot == -1) {
                error("Ingredient " + Names.get(ingredient) + " not found in inventory!");
                return false;
            }

            // Pick up ONE ingredient (right-click to get one item)
            mc.interactionManager.clickSlot(
                handler.syncId,
                ingredientSlot,
                1, // Right click to pick up one
                SlotActionType.PICKUP,
                mc.player
            );

            // Place the one ingredient in slot 3
            mc.interactionManager.clickSlot(
                handler.syncId,
                3, // Ingredient slot
                0, // Left click to place
                SlotActionType.PICKUP,
                mc.player
            );

            // Return remaining items to inventory if any on cursor
            ItemStack cursorStack = handler.getCursorStack();
            if (!cursorStack.isEmpty()) {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    ingredientSlot,
                    0, // Left click to return
                    SlotActionType.PICKUP,
                    mc.player
                );
            }

            info("Added ONE " + Names.get(ingredient) + " to brewing stand");
            return true;
        }

        return true;
    }

    private int findItemInInventory(Item item) {
        if (mc.currentScreen instanceof BrewingStandScreen screen) {
            var handler = screen.getScreenHandler();

            // Search inventory slots (typically starts after brewing stand slots)
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

                    // Check for water blocks or cauldrons with water
                    if (blockState.getBlock() == Blocks.WATER ||
                        blockState.getBlock() == Blocks.WATER_CAULDRON) {
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

        // Check if we already have enough water bottles
        int waterBottles = InvUtils.find(Items.POTION).count();
        int standsCount = Math.min(brewingStands.size(), brewingStandsAmount.get());
        int requiredBottles = standsCount * 3;

        if (waterBottles >= requiredBottles) {
            info("Already have enough water bottles");
            return false;
        }

        // Find glass bottle in hotbar or inventory
        int bottleSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.GLASS_BOTTLE) {
                bottleSlot = i;
                break;
            }
        }

        if (bottleSlot == -1) {
            // Try to move glass bottle to hotbar
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

        // Select the glass bottle using InvUtils
        InvUtils.swap(bottleSlot, false);

        // Right-click the water source
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
