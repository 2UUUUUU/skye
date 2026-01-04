package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.player.SmoothAimUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBrewing = settings.createGroup("Brewing");

    public enum PotionRecipe {
        AWKWARD("Awkward Potion", Items.POTION, new Item[]{Items.NETHER_WART}),
        THICK("Thick Potion", Items.POTION, new Item[]{Items.GLOWSTONE_DUST}),
        MUNDANE("Mundane Potion", Items.POTION, new Item[]{Items.REDSTONE}),
        REGENERATION("Regeneration", Items.POTION, new Item[]{Items.NETHER_WART, Items.GHAST_TEAR}),
        SWIFTNESS("Swiftness", Items.POTION, new Item[]{Items.NETHER_WART, Items.SUGAR}),
        FIRE_RESISTANCE("Fire Resistance", Items.POTION, new Item[]{Items.NETHER_WART, Items.MAGMA_CREAM}),
        POISON("Poison", Items.POTION, new Item[]{Items.NETHER_WART, Items.SPIDER_EYE}),
        HEALING("Healing", Items.POTION, new Item[]{Items.NETHER_WART, Items.GLISTERING_MELON_SLICE}),
        NIGHT_VISION("Night Vision", Items.POTION, new Item[]{Items.NETHER_WART, Items.GOLDEN_CARROT}),
        WEAKNESS("Weakness", Items.POTION, new Item[]{Items.FERMENTED_SPIDER_EYE}),
        STRENGTH("Strength", Items.POTION, new Item[]{Items.NETHER_WART, Items.BLAZE_POWDER}),
        SLOWNESS("Slowness", Items.POTION, new Item[]{Items.NETHER_WART, Items.FERMENTED_SPIDER_EYE}),
        LEAPING("Leaping", Items.POTION, new Item[]{Items.NETHER_WART, Items.RABBIT_FOOT}),
        WATER_BREATHING("Water Breathing", Items.POTION, new Item[]{Items.NETHER_WART, Items.PUFFERFISH}),
        INVISIBILITY("Invisibility", Items.POTION, new Item[]{Items.NETHER_WART, Items.GOLDEN_CARROT, Items.FERMENTED_SPIDER_EYE}),
        SLOW_FALLING("Slow Falling", Items.POTION, new Item[]{Items.NETHER_WART, Items.PHANTOM_MEMBRANE}),
        TURTLE_MASTER("Turtle Master", Items.POTION, new Item[]{Items.NETHER_WART, Items.TURTLE_SCUTE}),
        SPLASH_REGENERATION("Splash Regeneration", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.GHAST_TEAR, Items.GUNPOWDER}),
        SPLASH_SWIFTNESS("Splash Swiftness", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.SUGAR, Items.GUNPOWDER}),
        SPLASH_FIRE_RESISTANCE("Splash Fire Resistance", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.MAGMA_CREAM, Items.GUNPOWDER}),
        SPLASH_POISON("Splash Poison", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.SPIDER_EYE, Items.GUNPOWDER}),
        SPLASH_HEALING("Splash Healing", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.GLISTERING_MELON_SLICE, Items.GUNPOWDER}),
        SPLASH_NIGHT_VISION("Splash Night Vision", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.GOLDEN_CARROT, Items.GUNPOWDER}),
        SPLASH_WEAKNESS("Splash Weakness", Items.SPLASH_POTION, new Item[]{Items.FERMENTED_SPIDER_EYE, Items.GUNPOWDER}),
        SPLASH_STRENGTH("Splash Strength", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.BLAZE_POWDER, Items.GUNPOWDER}),
        SPLASH_SLOWNESS("Splash Slowness", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.FERMENTED_SPIDER_EYE, Items.GUNPOWDER}),
        SPLASH_LEAPING("Splash Leaping", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.RABBIT_FOOT, Items.GUNPOWDER}),
        SPLASH_WATER_BREATHING("Splash Water Breathing", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.PUFFERFISH, Items.GUNPOWDER}),
        SPLASH_INVISIBILITY("Splash Invisibility", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.GOLDEN_CARROT, Items.FERMENTED_SPIDER_EYE, Items.GUNPOWDER}),
        SPLASH_SLOW_FALLING("Splash Slow Falling", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.PHANTOM_MEMBRANE, Items.GUNPOWDER}),
        SPLASH_TURTLE_MASTER("Splash Turtle Master", Items.SPLASH_POTION, new Item[]{Items.NETHER_WART, Items.TURTLE_SCUTE, Items.GUNPOWDER});

        private final String name;
        private final Item result;
        private final Item[] ingredients;

        PotionRecipe(String name, Item result, Item[] ingredients) {
            this.name = name;
            this.result = result;
            this.ingredients = ingredients;
        }

        @Override
        public String toString() { return name; }
        public Item[] getIngredients() { return ingredients; }
        public Item getResult() { return result; }

        public static PotionRecipe fromString(String name) {
            for (PotionRecipe r : values()) if (r.name.equals(name)) return r;
            return POISON;
        }
    }

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder().name("action-delay").description("Delay in ticks between actions").defaultValue(10).min(1).max(20).sliderMax(20).build());
    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder().name("loop-delay").description("Delay in ticks between the sequence loop").defaultValue(100).min(0).max(72000).sliderMax(1200).build());
    private final Setting<Boolean> smoothAim = sgGeneral.add(new BoolSetting.Builder().name("smooth-aim").description("Enable smooth aiming for interactions").defaultValue(false).build());
    private final Setting<Integer> rotationSpeed = sgGeneral.add(new IntSetting.Builder().name("rotation-speed").description("Rotation speed in ticks for smooth aim").defaultValue(10).min(0).max(600).sliderMax(600).visible(smoothAim::get).build());
    private final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder().name("silent-mode").description("Hide info logs and chat feedback").defaultValue(false).build());
    private final Setting<List<String>> potionSelection = sgBrewing.add(new StringListSetting.Builder().name("potion-to-brew").description("Select the potion to brew (type to search)").defaultValue(List.of("Poison")).onChanged(list -> { if (list.size() > 1) { String last = list.get(list.size() - 1); list.clear(); list.add(last); } }).build());
    private final Setting<Integer> brewingStandRange = sgBrewing.add(new IntSetting.Builder().name("brewing-stand-range").description("Range to search for brewing stands").defaultValue(4).min(1).max(4).sliderMax(4).build());
    private final Setting<Boolean> useWater = sgBrewing.add(new BoolSetting.Builder().name("use-water").description("Automatically fill bottles from water sources").defaultValue(true).build());
    private final Setting<Integer> waterSourceRange = sgBrewing.add(new IntSetting.Builder().name("water-source-range").description("Range to search for water sources").defaultValue(12).min(1).max(32).sliderMax(32).visible(useWater::get).build());
    private final Setting<Integer> brewingStandsAmount = sgBrewing.add(new IntSetting.Builder().name("brewing-stands-amount").description("Number of brewing stands to use in rotation").defaultValue(3).min(1).max(32).sliderMax(32).build());

    private enum State { IDLE, FIND_BREWING_STANDS, FIND_WATER_SOURCE, FILL_WATER_BOTTLES, SELECT_BREWING_STAND, MOVE_TO_BREWING_STAND, OPEN_BREWING_STAND, PLACE_WATER_BOTTLES, PLACE_INGREDIENT, CLOSE_BREWING_STAND, COLLECT_POTIONS, LOOP_DELAY }

    private static class BrewingStandData {
        BlockPos pos;
        int currentPhase;
        long cooldownUntil;
        boolean hasBottles, isComplete;

        BrewingStandData(BlockPos pos) { this.pos = pos; }
        boolean isOnCooldown() { return System.currentTimeMillis() < cooldownUntil; }
        void setCooldown(int ticks) { this.cooldownUntil = System.currentTimeMillis() + (ticks * 50L); }
        void nextPhase() { this.currentPhase++; }
    }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private List<BrewingStandData> brewingStands = new ArrayList<>();
    private int currentBrewingStandIndex = 0;
    private BlockPos waterSourcePos = null;
    private int waterBottlesFilled = 0;
    private static final int BREWING_TIME_TICKS = 420;

    public AutoBrewer() { super(Main.CATEGORY, "auto-brewer", "Automatically brews potions using multiple brewing stands"); }

    public PotionRecipe getSelectedRecipe() {
        List<String> selection = potionSelection.get();
        return selection.isEmpty() ? PotionRecipe.POISON : PotionRecipe.fromString(selection.get(0));
    }

    @Override
    public void onActivate() {
        currentState = State.IDLE;
        brewingStands.clear();
        currentBrewingStandIndex = 0;
        waterSourcePos = null;
        waterBottlesFilled = 0;
        delayCounter = 0;
        info("AutoBrewer activated - Recipe: " + getSelectedRecipe().toString());
    }

    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        brewingStands.clear();
        SmoothAimUtils.cancelRotation();
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        info("AutoBrewer deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (smoothAim.get() && SmoothAimUtils.isRotating()) SmoothAimUtils.tickRotation();
        if (delayCounter > 0) { delayCounter--; return; }
        handleState();
    }

    private void handleState() {
        switch (currentState) {
            case IDLE -> { info("Starting brewing sequence..."); currentState = State.FIND_BREWING_STANDS; delayCounter = actionDelay.get(); }
            case FIND_BREWING_STANDS -> {
                List<BlockPos> foundStands = findBrewingStands();
                if (foundStands.isEmpty()) { error("ERROR: No brewing stands found within range!"); toggle(); return; }
                brewingStands.clear();
                int maxStands = Math.min(foundStands.size(), brewingStandsAmount.get());
                for (int i = 0; i < maxStands; i++) brewingStands.add(new BrewingStandData(foundStands.get(i)));
                info("Found " + brewingStands.size() + " brewing stands to use");
                currentBrewingStandIndex = 0;
                currentState = useWater.get() ? State.FIND_WATER_SOURCE : State.SELECT_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case FIND_WATER_SOURCE -> {
                waterSourcePos = findNearestWater();
                if (waterSourcePos == null) { error("ERROR: No water source found within range!"); toggle(); return; }
                info("Found water source at: " + waterSourcePos.toShortString());
                currentState = State.FILL_WATER_BOTTLES;
                delayCounter = actionDelay.get();
            }
            case FILL_WATER_BOTTLES -> {
                int totalBottlesNeeded = brewingStands.size() * 3;
                int waterBottles = InvUtils.find(Items.POTION).count();
                if (waterBottles >= totalBottlesNeeded) { info("Already have enough water bottles"); currentState = State.SELECT_BREWING_STAND; delayCounter = actionDelay.get(); return; }
                if (InvUtils.find(Items.GLASS_BOTTLE).count() == 0) { error("ERROR: No glass bottles in inventory!"); toggle(); return; }
                if (fillWaterBottle(waterSourcePos)) {
                    waterBottlesFilled++;
                    info("Filled water bottle (" + waterBottlesFilled + "/" + totalBottlesNeeded + ")");
                    if (waterBottlesFilled >= totalBottlesNeeded) { waterBottlesFilled = 0; currentState = State.SELECT_BREWING_STAND; }
                    delayCounter = actionDelay.get();
                } else { error("Failed to fill water bottle"); toggle(); }
            }
            case SELECT_BREWING_STAND -> {
                BrewingStandData selectedStand = null;
                int startIndex = currentBrewingStandIndex;
                do {
                    BrewingStandData stand = brewingStands.get(currentBrewingStandIndex);
                    if (!stand.isComplete && !stand.isOnCooldown()) { selectedStand = stand; break; }
                    currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                } while (currentBrewingStandIndex != startIndex);
                if (selectedStand == null) {
                    if (brewingStands.stream().allMatch(s -> s.isComplete)) { info("All brewing stands completed!"); currentState = State.LOOP_DELAY; delayCounter = loopDelay.get(); }
                    else { info("All brewing stands on cooldown, waiting..."); delayCounter = 20; }
                    return;
                }
                info("Selected brewing stand " + (currentBrewingStandIndex + 1) + "/" + brewingStands.size() + " - Phase " + (selectedStand.currentPhase + 1) + "/" + getSelectedRecipe().getIngredients().length);
                currentState = State.MOVE_TO_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case MOVE_TO_BREWING_STAND -> {
                BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);
                double distance = mc.player.squaredDistanceTo(currentStand.pos.getX() + 0.5, currentStand.pos.getY() + 0.5, currentStand.pos.getZ() + 0.5);
                if (distance > 9) { error("Brewing stand too far!"); currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size(); currentState = State.SELECT_BREWING_STAND; delayCounter = actionDelay.get(); return; }
                currentState = State.OPEN_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case OPEN_BREWING_STAND -> {
                if (mc.currentScreen instanceof BrewingStandScreen) {
                    BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);
                    currentState = currentStand.hasBottles ? State.PLACE_INGREDIENT : State.PLACE_WATER_BOTTLES;
                    delayCounter = actionDelay.get();
                    return;
                }
                if (openBrewingStand(brewingStands.get(currentBrewingStandIndex).pos)) delayCounter = actionDelay.get() * 2;
                else { error("Failed to open brewing stand"); toggle(); }
            }
            case PLACE_WATER_BOTTLES -> {
                if (!(mc.currentScreen instanceof BrewingStandScreen)) { currentState = State.SELECT_BREWING_STAND; return; }
                if (placeWaterBottles()) {
                    brewingStands.get(currentBrewingStandIndex).hasBottles = true;
                    info("Placed water bottles");
                    currentState = State.PLACE_INGREDIENT;
                    delayCounter = actionDelay.get();
                } else { error("Failed to place water bottles"); currentState = State.CLOSE_BREWING_STAND; delayCounter = actionDelay.get(); }
            }
            case PLACE_INGREDIENT -> {
                if (!(mc.currentScreen instanceof BrewingStandScreen)) { currentState = State.SELECT_BREWING_STAND; return; }
                BrewingStandData currentStand = brewingStands.get(currentBrewingStandIndex);
                PotionRecipe recipe = getSelectedRecipe();
                if (currentStand.currentPhase >= recipe.getIngredients().length) {
                    info("All phases complete!");
                    currentStand.isComplete = true;
                    currentState = State.COLLECT_POTIONS;
                    delayCounter = actionDelay.get();
                    return;
                }
                if (placeIngredient(recipe.getIngredients()[currentStand.currentPhase])) {
                    info("Placed ingredient (Phase " + (currentStand.currentPhase + 1) + ")");
                    currentStand.setCooldown(BREWING_TIME_TICKS);
                    currentStand.nextPhase();
                    currentState = State.CLOSE_BREWING_STAND;
                    delayCounter = actionDelay.get();
                } else { error("Failed to place ingredient!"); toggle(); }
            }
            case CLOSE_BREWING_STAND -> {
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                info("Closed brewing stand");
                currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                currentState = State.SELECT_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case COLLECT_POTIONS -> {
                info("Potions ready!");
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                currentBrewingStandIndex = (currentBrewingStandIndex + 1) % brewingStands.size();
                currentState = State.SELECT_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case LOOP_DELAY -> { info("Brewing cycle complete!"); brewingStands.clear(); currentState = State.IDLE; }
        }
    }

    private List<BlockPos> findBrewingStands() {
        List<BlockPos> stands = new ArrayList<>();
        if (mc.world == null || mc.player == null) return stands;
        BlockPos playerPos = mc.player.getBlockPos();
        int range = brewingStandRange.get();
        for (int x = -range; x <= range; x++)
            for (int y = -range; y <= range; y++)
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.BREWING_STAND) stands.add(pos.toImmutable());
                }
        stands.sort(Comparator.comparingDouble(pos -> mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        return stands;
    }

    private BlockPos findNearestWater() {
        if (mc.world == null || mc.player == null) return null;
        BlockPos playerPos = mc.player.getBlockPos();
        int range = waterSourceRange.get();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int x = -range; x <= range; x++)
            for (int y = -range; y <= range; y++)
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    var blockState = mc.world.getBlockState(pos);
                    if (blockState.getBlock() == Blocks.WATER || blockState.getBlock() == Blocks.WATER_CAULDRON) {
                        double dist = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (dist < nearestDist) { nearestDist = dist; nearest = pos.toImmutable(); }
                    }
                }
        return nearest;
    }

    private boolean fillWaterBottle(BlockPos waterPos) {
        if (mc.player == null || mc.interactionManager == null) return false;
        FindItemResult bottle = InvUtils.findInHotbar(Items.GLASS_BOTTLE);
        if (!bottle.found()) {
            int invSlot = InvUtils.find(Items.GLASS_BOTTLE).slot();
            if (invSlot == -1) return false;
            InvUtils.move().from(invSlot).toHotbar(0);
            return false;
        }
        InvUtils.swap(bottle.slot(), false);
        Vec3d hitVec = Vec3d.ofCenter(waterPos);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, waterPos, false);
        if (smoothAim.get()) Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), rotationSpeed.get(), () -> mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult));
        else mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        return true;
    }

    private boolean openBrewingStand(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return false;
        Vec3d hitVec = Vec3d.ofCenter(pos);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, pos, false);
        if (smoothAim.get()) Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), rotationSpeed.get(), () -> mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult));
        else mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        return true;
    }

    private boolean placeWaterBottles() {
        if (!(mc.currentScreen instanceof BrewingStandScreen) || mc.player == null || mc.interactionManager == null) return false;
        var handler = mc.player.currentScreenHandler;
        int waterBottlesPlaced = 0;
        for (int bottleSlot = 0; bottleSlot < 3; bottleSlot++) {
            if (!handler.getSlot(bottleSlot).getStack().isEmpty()) { waterBottlesPlaced++; continue; }
            for (int i = 5; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.getItem() == Items.POTION) {
                    mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                    mc.interactionManager.clickSlot(handler.syncId, bottleSlot, 0, SlotActionType.PICKUP, mc.player);
                    if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                    waterBottlesPlaced++;
                    break;
                }
            }
        }
        return waterBottlesPlaced == 3;
    }

    private boolean placeIngredient(Item ingredient) {
        if (!(mc.currentScreen instanceof BrewingStandScreen) || mc.player == null || mc.interactionManager == null) return false;
        var handler = mc.player.currentScreenHandler;
        ItemStack ingredientSlot = handler.getSlot(3).getStack();
        if (!ingredientSlot.isEmpty() && ingredientSlot.getItem() == ingredient) return true;
        for (int i = 5; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == ingredient) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(handler.syncId, 3, 1, SlotActionType.PICKUP, mc.player);
                if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                return true;
            }
        }
        return false;
    }

    private void info(String message) { if (!silentMode.get()) super.info(message); }
    private void error(String message) { if (!silentMode.get()) super.error(message); }
}
