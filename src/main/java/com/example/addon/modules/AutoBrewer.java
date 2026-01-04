package com.example.addon.modules;

import com.example.addon.Main;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

public class AutoBrewer extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPotion = settings.createGroup("Potion");

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

    // Manual potion selection field
    private PotionType potionToBrew = PotionType.INSTANT_DAMAGE;

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay in ticks between actions")
        .defaultValue(10)
        .min(1)
        .max(20)
        .sliderMax(20)
        .build());

    private final Setting<PotionForm> recipe = sgPotion.add(new EnumSetting.Builder<PotionForm>()
        .name("recipe:")
        .description("Drinkable or Splash potion")
        .defaultValue(PotionForm.DRINKABLE)
        .build());

    // State Variables
    private enum State { IDLE, FIND_BREWING_STAND, OPEN_BREWING_STAND, BREW_POTIONS }

    private State currentState = State.IDLE;
    private int delayCounter = 0;
    private BlockPos brewingStandPos = null;

    public AutoBrewer() {
        super(Main.CATEGORY, "auto-brewer", "Automatically brews potions");
    }

    // Override getWidget to create custom potion selector GUI element inside Potion settings
    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();

        // Add label for Potion To Brew
        table.add(theme.label("Potion:     "));

        // Row: Potion to Brew selector
        WHorizontalList potionList = table.add(theme.horizontalList()).expandX().widget();

        // Show potion name with custom color (no icon to avoid z-index overlap with dropdowns)
        var label = potionList.add(theme.label(potionToBrew.toString())).expandX().widget();
        // Try to set a cyan/aqua color
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

    // Potion selection GUI implemented using Meteor GUI components
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

    // Helper getters and brewing logic methods below...
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
        if (recipe.get() == PotionForm.SPLASH) {
            Item[] splashPhases = java.util.Arrays.copyOf(basePhases, basePhases.length + 1);
            splashPhases[basePhases.length] = Items.GUNPOWDER;
            return splashPhases;
        }
        return basePhases;
    }
    @Override
    public void onActivate() {
        currentState = State.IDLE;
        delayCounter = 0;
        brewingStandPos = null;
        info("AutoBrewer activated - " + potionToBrew + " (" + recipe.get() + ")");
    }
    @Override
    public void onDeactivate() {
        currentState = State.IDLE;
        delayCounter = 0;
        brewingStandPos = null;
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        info("AutoBrewer deactivated");
    }
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
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
                currentState = State.FIND_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case FIND_BREWING_STAND -> {
                info("Finding brewing stand...");
                brewingStandPos = findNearestBrewingStand();
                if (brewingStandPos == null) {
                    error("No brewing stand found nearby!");
                    toggle();
                    return;
                }
                currentState = State.OPEN_BREWING_STAND;
                delayCounter = actionDelay.get();
            }
            case OPEN_BREWING_STAND -> {
                info("Opening brewing stand...");
                // Implement opening logic here
                currentState = State.BREW_POTIONS;
                delayCounter = actionDelay.get();
            }
            case BREW_POTIONS -> {
                info("Brewing potions...");
                if (!hasRequiredIngredients()) {
                    error("Missing required ingredients!");
                    toggle();
                    return;
                }
                // Add brewing logic here

                // Loop back to idle for now
                currentState = State.IDLE;
                delayCounter = actionDelay.get() * 10;
            }
        }
    }
    private BlockPos findNearestBrewingStand() {
        if (mc.world == null || mc.player == null) return null;
        BlockPos playerPos = mc.player.getBlockPos();
        int range = 5;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int x = -range; x <= range; x++)
            for (int y = -range; y <= range; y++)
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockEntity(pos) instanceof BrewingStandBlockEntity) {
                        double dist = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
        return nearest;
    }
    private boolean hasRequiredIngredients() {
        if (mc.player == null) return false;
        Item[] phases = getBrewingPhases();
        for (Item ingredient : phases)
            if (InvUtils.find(ingredient).count() == 0) {
                error("Missing ingredient: " + Names.get(ingredient));
                return false;
            }
        if (InvUtils.find(Items.POTION).count() == 0) {
            error("Missing water bottles!");
            return false;
        }
        if (InvUtils.find(Items.BLAZE_POWDER).count() == 0) {
            error("Missing blaze powder for fuel!");
            return false;
        }
        return true;
    }
    private void info(String s) {
        super.info(s);
    }
    private void error(String s) {
        super.error(s);
    }
}
