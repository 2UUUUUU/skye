package com.example.addon.utils.hypixel;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;

public class EtherwarpUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // State tracking for etherwarp sequence
    private static EtherwarpState currentState = EtherwarpState.IDLE;
    private static int tickCounter = 0;
    private static boolean isCrouching = false;

    /**
     * Executes an etherwarp sequence with crouching
     * @param onComplete Callback to run when sequence completes
     * @param onError Callback to run if an error occurs
     * @return true if sequence started successfully, false if already running
     */
    public static boolean executeEtherwarpSequence(Runnable onComplete, Runnable onError) {
        if (currentState != EtherwarpState.IDLE) {
            return false; // Already running
        }

        currentState = EtherwarpState.CHECKING_SHOVEL;
        tickCounter = 0;
        return true;
    }

    /**
     * Tick the etherwarp sequence - call this from onTick event
     * @param onComplete Callback when sequence completes
     * @param onError Callback when error occurs (with error message)
     */
    public static void tickEtherwarpSequence(Runnable onComplete, java.util.function.Consumer<String> onError) {
        if (mc.player == null || currentState == EtherwarpState.IDLE) return;

        switch (currentState) {
            case CHECKING_SHOVEL:
                int shovelSlot = findDiamondShovelSlot();
                if (shovelSlot == -1) {
                    onError.accept("ERROR: No diamond shovel in hotbar!");
                    resetEtherwarpSequence();
                    return;
                }
                currentState = EtherwarpState.EQUIPPING_SHOVEL;
                InvUtils.swap(shovelSlot, false);
                tickCounter = 0;
                break;

            case EQUIPPING_SHOVEL:
                if (tickCounter >= 20) {
                    if (mc.player.getMainHandStack().getItem() == Items.DIAMOND_SHOVEL) {
                        currentState = EtherwarpState.CROUCHING;
                        mc.options.sneakKey.setPressed(true);
                        isCrouching = true;
                        tickCounter = 0;
                    } else {
                        onError.accept("ERROR: Failed to equip diamond shovel!");
                        resetEtherwarpSequence();
                        return;
                    }
                } else {
                    tickCounter++;
                }
                break;

            case CROUCHING:
                if (tickCounter >= 20) {
                    currentState = EtherwarpState.USING_SHOVEL;
                    mc.options.useKey.setPressed(true);
                    tickCounter = 0;
                } else {
                    tickCounter++;
                }
                break;

            case USING_SHOVEL:
                if (tickCounter >= 5) {
                    mc.options.useKey.setPressed(false);
                    mc.options.sneakKey.setPressed(false);
                    isCrouching = false;
                    currentState = EtherwarpState.IDLE;
                    onComplete.run();
                } else {
                    tickCounter++;
                }
                break;
        }
    }

    /**
     * Reset the etherwarp sequence state
     */
    public static void resetEtherwarpSequence() {
        currentState = EtherwarpState.IDLE;
        tickCounter = 0;
        if (mc.options != null && mc.options.sneakKey != null && isCrouching) {
            mc.options.sneakKey.setPressed(false);
        }
        isCrouching = false;
        if (mc.options != null && mc.options.useKey != null) {
            mc.options.useKey.setPressed(false);
        }
    }

    /**
     * Check if etherwarp sequence is currently running
     */
    public static boolean isEtherwarpSequenceRunning() {
        return currentState != EtherwarpState.IDLE;
    }

    /**
     * Find diamond shovel slot in hotbar
     */
    private static int findDiamondShovelSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.DIAMOND_SHOVEL) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Internal state machine for etherwarp sequence
     */
    private enum EtherwarpState {
        IDLE,
        CHECKING_SHOVEL,
        EQUIPPING_SHOVEL,
        CROUCHING,
        USING_SHOVEL
    }
}
