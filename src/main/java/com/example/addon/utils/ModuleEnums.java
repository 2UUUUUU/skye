package com.example.addon.utils;

/**
 * Centralized enums for all modules in the addon.
 * This class contains all enum types used across different modules.
 */
public class ModuleEnums {

    // ==================== DRAGON ASSISTANT ENUMS ====================

    public enum TeleportLocation {
        MIDDLE_LEFT("Middle Left"),
        MIDDLE_RIGHT("Middle Right"),
        RIGHT_TOWER("Right Tower"),
        GATE("Gate");

        private final String name;

        TeleportLocation(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum WarpLocation {
        THE_END("The End"),
        DRAGONS_NEST("Dragon's Nest");

        private final String name;

        WarpLocation(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum EyesToPlace {
        NONE("None", 0),
        TWO("2", 2),
        THREE("3", 3),
        FOUR("4", 4);

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
        INVENTORY("Inventory"),
        STASH("Stash"),
        BZ_INSTA_BUY("BZ Insta-Buy"),
        BZ_ORDER("BZ Order");

        private final String name;

        InventoryOption(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // ==================== DATA CLASSES ====================

    /**
     * Data class for teleport locations used in DragonAssistant
     */
    public static class TeleportLocationData {
        public String name;
        public int x;
        public int y;
        public int z;

        public TeleportLocationData(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
