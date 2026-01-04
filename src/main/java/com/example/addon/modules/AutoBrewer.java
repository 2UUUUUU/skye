package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.player.SmoothAimUtils;
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

import java.util.*;

public class AutoBrewer extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Setting Groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBrewing = settings.createGroup("Brewing");

    // Potion Recipe Enum
    public enum PotionRecipe {
        AWKWARD_POTION("Awkward Potion", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart")
        }),
        THICK_POTION("Thick Potion", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.GLOWSTONE_DUST, "Glowstone Dust")
        }),
        MUNDANE_POTION("Mundane Potion", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.REDSTONE, "Redstone")
        }),
        POTION_OF_REGENERATION("Potion of Regeneration", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GHAST_TEAR, "Ghast Tear")
        }),
        POTION_OF_SWIFTNESS("Potion of Swiftness", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.SUGAR, "Sugar")
        }),
        POTION_OF_FIRE_RESISTANCE("Potion of Fire Resistance", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.MAGMA_CREAM, "Magma Cream")
        }),
        POTION_OF_POISON("Potion of Poison", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.SPIDER_EYE, "Spider Eye")
        }),
        POTION_OF_HEALING("Potion of Healing", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GLISTERING_MELON_SLICE, "Glistering Melon Slice")
        }),
        POTION_OF_NIGHT_VISION("Potion of Night Vision", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GOLDEN_CARROT, "Golden Carrot")
        }),
        POTION_OF_WEAKNESS("Potion of Weakness", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.FERMENTED_SPIDER_EYE, "Fermented Spider Eye")
        }),
        POTION_OF_STRENGTH("Potion of Strength", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.BLAZE_POWDER, "Blaze Powder")
        }),
        POTION_OF_SLOWNESS("Potion of Slowness", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.FERMENTED_SPIDER_EYE, "Fermented Spider Eye")
        }),
        POTION_OF_LEAPING("Potion of Leaping", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.RABBIT_FOOT, "Rabbit Foot")
        }),
        POTION_OF_WATER_BREATHING("Potion of Water Breathing", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.PUFFERFISH, "Pufferfish")
        }),
        POTION_OF_INVISIBILITY("Potion of Invisibility", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GOLDEN_CARROT, "Golden Carrot"),
            new BrewingPhase(Items.FERMENTED_SPIDER_EYE, "Fermented Spider Eye")
        }),
        POTION_OF_SLOW_FALLING("Potion of Slow Falling", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.PHANTOM_MEMBRANE, "Phantom Membrane")
        }),
        POTION_OF_TURTLE_MASTER("Potion of Turtle Master", Items.POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.TURTLE_SCUTE, "Turtle Scute")
        }),

        // Splash Potions
        SPLASH_POTION_OF_REGENERATION("Splash Potion of Regeneration", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GHAST_TEAR, "Ghast Tear"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_SWIFTNESS("Splash Potion of Swiftness", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.SUGAR, "Sugar"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_FIRE_RESISTANCE("Splash Potion of Fire Resistance", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.MAGMA_CREAM, "Magma Cream"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_POISON("Splash Potion of Poison", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.SPIDER_EYE, "Spider Eye"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_HEALING("Splash Potion of Healing", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GLISTERING_MELON_SLICE, "Glistering Melon Slice"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_NIGHT_VISION("Splash Potion of Night Vision", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GOLDEN_CARROT, "Golden Carrot"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_WEAKNESS("Splash Potion of Weakness", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.FERMENTED_SPIDER_EYE, "Fermented Spider Eye"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_STRENGTH("Splash Potion of Strength", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.BLAZE_POWDER, "Blaze Powder"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_SLOWNESS("Splash Potion of Slowness", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.FERMENTED_SPIDER_EYE, "Fermented Spider Eye"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_LEAPING("Splash Potion of Leaping", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.RABBIT_FOOT, "Rabbit Foot"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_WATER_BREATHING("Splash Potion of Water Breathing", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.PUFFERFISH, "Pufferfish"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_INVISIBILITY("Splash Potion of Invisibility", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.GOLDEN_CARROT, "Golden Carrot"),
            new BrewingPhase(Items.FERMENTED_SPIDER_EYE, "Fermented Spider Eye"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_SLOW_FALLING("Splash Potion of Slow Falling", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.PHANTOM_MEMBRANE, "Phantom Membrane"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        }),
        SPLASH_POTION_OF_TURTLE_MASTER("Splash Potion of Turtle Master", Items.SPLASH_POTION, new BrewingPhase[]{
            new BrewingPhase(Items.NETHER_WART, "Nether Wart"),
            new BrewingPhase(Items.TURTLE_SCUTE, "Turtle Scute"),
            new BrewingPhase(Items.GUNPOWDER, "Gunpowder")
        });

        private final String displayName;
        private final Item resultItem;
        private final BrewingPhase[] phases;

        PotionRecipe(String displayName, Item resultItem, BrewingPhase[] phases) {
            this.displayName = displayName;
            this.resultItem = resultItem;
            this.phases = phases;
        }

        @Override
        public String toString() {
            return displayName;
        }

        public BrewingPhase[] getPhases() {
            return phases;
        }

        public Item getResultItem() {
            return resultItem;
        }
    }

    private static class BrewingPhase {
        Item ingredient;
        String name;

        BrewingPhase(Item ingredient, String name) {
            this.ingredient = ingredient;
            this.name = name;
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

    // Map items to their recipes
    private static final Map<Item, List<PotionRecipe>> POTION_RECIPES_MAP = new HashMap<>();

    static {
        POTION_RECIPES_MAP.put(Items.POTION, Arrays.asList(
            PotionRecipe.AWKWARD_POTION,
            PotionRecipe.THICK_POTION,
            PotionRecipe.MUNDANE_POTION,
            PotionRecipe.POTION_OF_REGENERATION,
            PotionRecipe.POTION_OF_SWIFTNESS,
            PotionRecipe.POTION_OF_FIRE_RESISTANCE,
            PotionRecipe.POTION_OF_POISON,
            PotionRecipe.POTION_OF_HEALING,
            PotionRecipe.POTION_OF_NIGHT_VISION,
            PotionRecipe.POTION_OF_WEAKNESS,
            PotionRecipe.POTION_OF_STRENGTH,
            PotionRecipe.POTION_OF_SLOWNESS,
            PotionRecipe.POTION_OF_LEAPING,
            PotionRecipe.POTION_OF_WATER_BREATHING,
            PotionRecipe.POTION_OF_INVISIBILITY,
            PotionRecipe.POTION_OF_SLOW_FALLING,
            PotionRecipe.POTION_OF_TURTLE_MASTER
        ));

        POTION_RECIPES_MAP.put(Items.SPLASH_POTION, Arrays.asList(
            PotionRecipe.SPLASH_POTION_OF_REGENERATION,
            PotionRecipe.SPLASH_POTION_OF_SWIFTNESS,
            PotionRecipe.SPLASH_POTION_OF_FIRE_RESISTANCE,
            PotionRecipe.SPLASH_POTION_OF_POISON,
            PotionRecipe.SPLASH_POTION_OF_HEALING,
            PotionRecipe.SPLASH_POTION_OF_NIGHT_VISION,
            PotionRecipe.SPLASH_POTION_OF_WEAKNESS,
            PotionRecipe.SPLASH_POTION_OF_STRENGTH,
            PotionRecipe.SPLASH_POTION_OF_SLOWNESS,
            PotionRecipe.SPLASH_POTION_OF_LEAPING,
            PotionRecipe.SPLASH_POTION_OF_WATER_BREATHING,
            PotionRecipe.SPLASH_POTION_OF_INVISIBILITY,
            PotionRecipe.SPLASH_POTION_OF_SLOW_FALLING,
            PotionRecipe.SPLASH_POTION_OF_TURTLE_MASTER
        ));
    }

    // Brewing Settings
    public enum PotionType {
        REGULAR("Potion", Items.POTION),
        SPLASH("Splash Potion", Items.SPLASH_POTION);

        private final String name;
        private final Item item;

        PotionType(String name, Item item) {
            this.name = name;
            this.item = item;
        }

        public Item getItem() {
            return item;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Convert recipes to items for the list setting
    private static final List<PotionRecipe> DEFAULT_RECIPE = List.of(PotionRecipe.POTION_OF_POISON);

    // This will be used to map between items and recipes
    private static final Map<String, PotionRecipe> RECIPE_NAME_MAP = new HashMap<>();

    static {
        for (PotionRecipe recipe : PotionRecipe.values()) {
            RECIPE_NAME_MAP.put(recipe.toString(), recipe);
        }
    }

    private final Setting<List<String>> potionRecipeSelection = sgBrewing.add(new StringListSetting.Builder()
        .name("potion-to-brew")
        .description("Select the specific potion to brew")
        .defaultValue(List.of("Potion of Poison"))
        .onChanged(list -> {
            if (list.size() > 1) {
                String last = list.get(list.size() - 1);
                list.clear();
                list.add(last);
            }
        })
        .build());

    private final Setting<PotionType> potionTypeToBrew = sgBrewing.add(new EnumSetting.Builder<PotionType>()
        .name("potion-type-to-brew")
        .description("Select the potion type (Regular or Splash)")
        .defaultValue(PotionType.REGULAR)
        .onModuleActivated(setting -> {
            if (setting.get() == null) setting.set(PotionType.REGULAR);
        })
        .build());

    private final Setting<Integer> brewingStandRange = sgBrewing.add(new IntSetting.Builder()
        .name("brewing-stand-range")
        .description("Range to search for brewing stands")
        .defaultValue(4)
        .min(1)
        .max(4)
        .sliderMax(4)
        .build());

    private final Setting<Boolean> useWater = sgBrewing.add(new BoolSetting.Builder()
        .name("use-water")
        .description("Automatically fill bottles from water sources")
        .defaultValue(true)
        .build());

    private final Setting<Integer> waterSourceRange = sgBrewing.add(new IntSetting.Builder()
        .name("water-source-range")
        .description("Range to search for water sources")
        .defaultValue(12)
        .min(1)
        .max(32)
        .sliderMax(32)
        .visible(useWater::get)
        .build());

    private final Setting<Integer> brewingStandsAmount = sgBrewing.add(new IntSetting.Builder()
        .name("brewing-stands-amount")
        .description("Number of brewing stands to use in rotation")
        .defaultValue(3)
        .min(1)
        .max(32)
        .sliderMax(32)
        .build());

    // State variables
    private enum State {
        IDLE,
        FIND_BREWING_STANDS,
        FIND_WATER_SOURCE,
        FILL_WATER_BOTTLES,
        SELECT_BREWING_STAND,
        MOVE_TO_BREWING_STAND,
        OPEN_BREWING_STAND,
        PLACE_WATER_BOTTLES,
        PLACE_INGREDIENT,
        WAIT_FOR_BREWING,
        CLOSE_BREWING_STAND,
        COLLECT_POTIONS,
        LOOP_DELAY
    }

    private static class BrewingStandData {
        BlockPos pos;
        int currentPhase;
        long cooldownUntil;
        boolean hasBottles;
        boolean isComplete;

        BrewingStandData(BlockPos pos) {
            this.pos = pos;
            this.currentPhase = 0;
            this.cooldownUntil = 0;
            this.hasBottles = false;
            this.isComplete = false;
        }

        boolean isOnCooldown() {
            return System.currentTimeMillis() < cooldownUntil;
        }

        void setCooldown(int ticks) {
            this.cooldownUntil = System.currentTimeMillis() + (ticks * 50L);
        }

        void nextPhase() {
            this.currentPhase++;
        }

        void reset() {
            this.currentPhase = 0;
            this.cooldownUntil = 0;
            this.hasBottles = false;
            this.isComplete = false;
        }
    }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private List<BrewingStandData> brewingStands = new ArrayList<>();
    private int currentBrewingStandIndex = 0;
    private BlockPos waterSourcePos = null;
    private int waterBottlesFilled = 0;
    private static final int BREWING_TIME_TICKS = 420;
    private static final int BOTTLES_PER_STAND = 3;

    public AutoBrewer() {
        super(Main.CATEGORY, "auto-brewer", "Automatically brews potions using multiple brewing stands");
    }


    public Item getSelectedPotionType() {
        return potionTypeToBrew.get().getItem();
    }

    public PotionRecipe getSelectedRecipe() {
        List<String> selection = potionRecipeSelection.get();
        if (selection.isEmpty()) return PotionRecipe.POTION_OF_POISON;

        String selectedName = selection.get(0);
        return RECIPE_NAME_MAP.getOrDefault(selectedName, PotionRecipe.POTION_OF_POISON);
    }

    @Override
    public void onActivate() {
        currentState = State.IDLE;
        resetCounters();
        brewingStands.clear();
        currentBrewingStandIndex = 0;
        waterSourcePos = null;
        waterBottlesFilled = 0;

        info("AutoBrewer activated - Recipe: " + getSelectedRecipe().toString());
    }

    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        resetCounters();
        brewingStands.clear();
        SmoothAimUtils.cancelRotation();

        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }

        info("AutoBrewer deactivated");
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

    private void handleState() {
        switch (currentState) {
            case IDLE -> {
                info("Starting brewing sequence...");
                currentState = State.FIND_BREWING_STANDS;
                delayCounter = actionDelay.get();
            }

            case FIND_BREWING_STANDS -> {
                List<BlockPos> foundStands = findBrewingStands();

                if (foundStands.isEmpty()) {
                    error("ERROR: No brewing stands found within range!");
                    toggle();
                    return;
                }

                // Initialize brewing stand data
                brewingStands.clear();
                int maxStands = Math.min(foundStands.size(), brewingStandsAmount.get());
                for (int i = 0; i < maxStands; i++) {
                    brewingStands.add(new BrewingStandData(foundStands.get(i)));
                }

                info("Found " + brewingStands.size() + " brewing stands to use");
                currentBrewingStandIndex = 0;

                if (useWater.get()) {
                    currentState = State.FIND_WATER_SOURCE;
                } else {
                    currentState = State.SELECT_BREWING_STAND;
                }
                delayCounter = actionDelay.get();
            }

            case FIND_WATER_SOURCE -> {
                waterSourcePos = findNearestWater();

                if (waterSourcePos == null) {
                    error("ERROR: No water source found within range!");
                    error("Tip: Place a water source nearby or disable 'Use Water' option");
                    toggle();
                    return;
                }

                info("Found water source at: " + waterSourcePos.toShortString());
                currentState = State.FILL_WATER_BOTTLES;
                delayCounter = actionDelay.get();
            }

            case FILL_WATER_BOTTLES -> {
                int glassBottles = InvUtils.find(Items.GLASS_BOTTLE).count();
                int waterBottles = InvUtils.find(Items.POTION).count(); // Water bottles are potions
                int totalBottlesNeeded = brewingStands.size() * BOTTLES_PER_STAND;

                if (waterBottles >= totalBottlesNeeded) {
                    info("Already have enough water bottles (" + waterBottles + "/" + totalBottlesNeeded + ")");
                    currentState = State.SELECT_BREWING_STAND;
                    delayCounter = actionDelay.get();
                    return;
                }

                if (glassBottles == 0) {
                    error("ERROR: No glass bottles in inventory!");
                    toggle();
                    return;
                }

                // Check distance to water source
                double distance = mc.player.squaredDistanceTo(
                    waterSourcePos.getX() + 0.5,
                    waterSourcePos.getY() + 0.5,
                    waterSourcePos.getZ() + 0.5
                );

                if (distance > 16) { // 4 blocks reach
                    info("Moving closer to water source...");
                    currentState = State.MOVE_TO_BREWING_STAND; // Reuse movement logic
                    delayCounter = actionDelay.get();
                    return;
                }

                // Fill one bottle
                if (fillWaterBottle(waterSourcePos)) {
                    waterBottlesFilled++;
                    info("Filled water bottle (" + waterBottlesFilled + "/" + totalBottlesNeeded + ")");

                    if (waterBottlesFilled >= totalBottlesNeeded) {
                        info("All water bottles filled!");
                        waterBottlesFilled = 0;
                        currentState = State.SELECT_BREWING_STAND;
                    }

                    delayCounter = actionDelay.get();
                } else {
                    error("Failed to fill water bottle");
                    toggle();
                }
            }

            case SELECT_BREWING_STAND -> {
                // Find next brewing stand that needs processing
                BrewingStandData selectedStand = null;
                int startIndex = currentBrewingStandIndex;

                do {
                    BrewingStandData stand = brewingStands.get(currentBrewingStandIndex);

                    if (!stand.isComplete && !stand.isOnCooldown()) {
                        selectedStand = stand;
                        break;
                    }

                    currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                } while (currentBrewingStandIndex != startIndex);

                if (selectedStand == null) {
                    // All stands are either complete or on cooldown
                    boolean allComplete = brewingStands.stream().allMatch(s -> s.isComplete);

                    if (allComplete) {
                        info("All brewing stands completed their recipes!");
                        currentState = State.LOOP_DELAY;
                        delayCounter = loopDelay.get();
                    } else {
                        // Wait for cooldowns
                        info("All brewing stands on cooldown, waiting...");
                        delayCounter = 20; // Check again in 1 second
                    }
                    return;
                }

                info("Selected brewing stand " + (currentBrewingStandIndex + 1) + "/" + brewingStands.size() +
                    " - Phase " + (selectedStand.currentPhase + 1) + "/" + getSelectedRecipe().getPhases().length);

                currentState = State.MOVE_TO_BREWING_STAND;
                delayCounter = actionDelay.get();
            }

            case MOVE_TO_BREWING_STAND -> {
                BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);
                double distance = mc.player.squaredDistanceTo(
                    currentStand.pos.getX() + 0.5,
                    currentStand.pos.getY() + 0.5,
                    currentStand.pos.getZ() + 0.5
                );

                if (distance > 9) { // More than 3 blocks
                    error("Brewing stand too far away! Distance: " + Math.sqrt(distance));
                    currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                    currentState = State.SELECT_BREWING_STAND;
                    delayCounter = actionDelay.get();
                    return;
                }

                currentState = State.OPEN_BREWING_STAND;
                delayCounter = actionDelay.get();
            }

            case OPEN_BREWING_STAND -> {
                if (mc.currentScreen instanceof BrewingStandScreen) {
                    info("Brewing stand GUI opened");
                    BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);

                    if (!currentStand.hasBottles) {
                        currentState = State.PLACE_WATER_BOTTLES;
                    } else {
                        currentState = State.PLACE_INGREDIENT;
                    }
                    delayCounter = actionDelay.get();
                    return;
                }

                BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);
                if (openBrewingStand(currentStand.pos)) {
                    info("Sent brewing stand open request");
                    delayCounter = actionDelay.get() * 2;
                } else {
                    error("Failed to open brewing stand");
                    toggle();
                }
            }

            case PLACE_WATER_BOTTLES -> {
                if (!(mc.currentScreen instanceof BrewingStandScreen)) {
                    error("Not in brewing stand screen!");
                    currentState = State.SELECT_BREWING_STAND;
                    return;
                }

                if (placeWaterBottles()) {
                    BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);
                    currentStand.hasBottles = true;
                    info("Placed water bottles in brewing stand");
                    currentState = State.PLACE_INGREDIENT;
                    delayCounter = actionDelay.get();
                } else {
                    error("Failed to place water bottles");
                    currentState = State.CLOSE_BREWING_STAND;
                    delayCounter = actionDelay.get();
                }
            }

            case PLACE_INGREDIENT -> {
                if (!(mc.currentScreen instanceof BrewingStandScreen)) {
                    error("Not in brewing stand screen!");
                    currentState = State.SELECT_BREWING_STAND;
                    return;
                }

                BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);
                PotionRecipe recipe = getSelectedRecipe();

                if (currentStand.currentPhase >= recipe.getPhases().length) {
                    info("All phases complete for this stand!");
                    currentStand.isComplete = true;
                    currentState = State.COLLECT_POTIONS;
                    delayCounter = actionDelay.get();
                    return;
                }

                BrewingPhase phase = recipe.getPhases()[currentStand.currentPhase];

                if (placeIngredient(phase.ingredient)) {
                    info("Placed " + phase.name + " (Phase " + (currentStand.currentPhase + 1) + ")");
                    currentStand.setCooldown(BREWING_TIME_TICKS);
                    currentStand.nextPhase();
                    currentState = State.CLOSE_BREWING_STAND;
                    delayCounter = actionDelay.get();
                } else {
                    error("Failed to place " + phase.name + " - not enough in inventory!");
                    toggle();
                }
            }

            case CLOSE_BREWING_STAND -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }

                info("Closed brewing stand, moving to next...");
                currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                currentState = State.SELECT_BREWING_STAND;
                delayCounter = actionDelay.get();
            }

            case COLLECT_POTIONS -> {
                // TODO: Implement potion collection
                info("Potions ready for collection!");

                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }

                currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                currentState = State.SELECT_BREWING_STAND;
                delayCounter = actionDelay.get();
            }

            case LOOP_DELAY -> {
                info("Brewing cycle complete! Restarting...");
                resetCounters();
                brewingStands.clear();
                currentState = State.IDLE;
            }
        }
    }

    private List<BlockPos> findBrewingStands() {
        if (mc.world == null || mc.player == null) return new ArrayList<>();

        List<BlockPos> stands = new ArrayList<>();
        BlockPos playerPos = mc.player.getBlockPos();
        int range = brewingStandRange.get();

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.BREWING_STAND) {
                        stands.add(pos.toImmutable());
                    }
                }
            }
        }

        // Sort by distance
        stands.sort(Comparator.comparingDouble(pos ->
            mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
        ));

        return stands;
    }

    private BlockPos findNearestWater() {
        if (mc.world == null || mc.player == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        int range = waterSourceRange.get();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    var blockState = mc.world.getBlockState(pos);

                    if (blockState.getBlock() == Blocks.WATER ||
                        blockState.getBlock() == Blocks.WATER_CAULDRON) {
                        double dist = mc.player.squaredDistanceTo(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5
                        );
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos.toImmutable();
                        }
                    }
                }
            }
        }

        return nearest;
    }

    private boolean fillWaterBottle(BlockPos waterPos) {
        if (mc.player == null || mc.interactionManager == null) return false;

        FindItemResult bottle = InvUtils.findInHotbar(Items.GLASS_BOTTLE);
        if (!bottle.found()) {
            // Try to move from inventory to hotbar
            int invSlot = InvUtils.find(Items.GLASS_BOTTLE).slot();
            if (invSlot == -1) return false;
            InvUtils.move().from(invSlot).toHotbar(0);
            return false;
        }

        // Select bottle in hotbar
        InvUtils.swap(bottle.slot(), false);

        Vec3d hitVec = Vec3d.ofCenter(waterPos);
        Direction side = Direction.UP;

        if (smoothAim.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), rotationSpeed.get(), () -> {
                BlockHitResult hitResult = new BlockHitResult(hitVec, side, waterPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            });
        } else {
            BlockHitResult hitResult = new BlockHitResult(hitVec, side, waterPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
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

    private boolean placeWaterBottles() {
        if (!(mc.currentScreen instanceof BrewingStandScreen)) return false;
        if (mc.player == null || mc.interactionManager == null) return false;

        var handler = mc.player.currentScreenHandler;

        // Brewing stand slots: 0-2 are bottle slots, 3 is ingredient slot, 4 is fuel slot
        // Inventory slots start after that

        int waterBottlesPlaced = 0;

        for (int bottleSlot = 0; bottleSlot < 3; bottleSlot++) {
            ItemStack slotStack = handler.getSlot(bottleSlot).getStack();

            // Skip if already has a bottle
            if (!slotStack.isEmpty()) {
                waterBottlesPlaced++;
                continue;
            }

            // Find water bottle in inventory
            for (int i = 5; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();

                // Water bottles are Items.POTION with no effects
                if (stack.getItem() == Items.POTION) {
                    // Pick up the water bottle
                    mc.interactionManager.clickSlot(
                        handler.syncId,
                        i,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );

                    // Place it in the bottle slot
                    mc.interactionManager.clickSlot(
                        handler.syncId,
                        bottleSlot,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );

                    // Return remaining to inventory if any
                    if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                        mc.interactionManager.clickSlot(
                            handler.syncId,
                            i,
                            0,
                            SlotActionType.PICKUP,
                            mc.player
                        );
                    }

                    waterBottlesPlaced++;
                    break;
                }
            }
        }

        return waterBottlesPlaced == 3;
    }

    private boolean placeIngredient(Item ingredient) {
        if (!(mc.currentScreen instanceof BrewingStandScreen)) return false;
        if (mc.player == null || mc.interactionManager == null) return false;

        var handler = mc.player.currentScreenHandler;

        // Slot 3 is the ingredient slot
        ItemStack ingredientSlot = handler.getSlot(3).getStack();

        // Check if ingredient is already there
        if (!ingredientSlot.isEmpty() && ingredientSlot.getItem() == ingredient) {
            return true;
        }

        // Find ingredient in inventory
        for (int i = 5; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();

            if (stack.getItem() == ingredient) {
                // Pick up one ingredient
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    i,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );

                // Place one in ingredient slot (right-click to place one)
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    3,
                    1,
                    SlotActionType.PICKUP,
                    mc.player
                );

                // Return remaining to inventory
                if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                    mc.interactionManager.clickSlot(
                        handler.syncId,
                        i,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );
                }

                return true;
            }
        }

        return false;
    }

    private void resetCounters() {
        delayCounter = 0;
        currentBrewingStandIndex = 0;
        waterBottlesFilled = 0;
    }

    // Helper methods to respect silent mode
    private void info(String message) {
        if (!silentMode.get()) {
            super.info(message);
        }
    }

    private void error(String message) {
        if (!silentMode.get()) {
            super.error(message);
        }
    }
}
