package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.utils.hypixel.EtherwarpUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

public class EtherwarpTest extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> exampleSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("test-setting")
        .description("The testing settings for the Hypixel Skyblock category.")
        .defaultValue(true)
        .build()
    );

    public EtherwarpTest() {
        super(Main.ETHERWARP_TEST, "etherwarp-test", "Hypixel Testing purposes.");
    }

    @Override
    public void onActivate() {
        info("Etherwarp Test module activated!");

        // Start the etherwarp sequence
        boolean started = EtherwarpUtils.executeEtherwarpSequence(
            this::onSequenceComplete,
            () -> onSequenceError("ERROR: Failed to start sequence!")
        );

        if (!started) {
            error("Etherwarp sequence already running!");
        }
    }

    @Override
    public void onDeactivate() {
        info("Etherwarp Test module deactivated!");
        EtherwarpUtils.resetEtherwarpSequence();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Tick the etherwarp sequence
        EtherwarpUtils.tickEtherwarpSequence(
            this::onSequenceComplete,
            this::onSequenceError
        );
    }

    private void onSequenceComplete() {
        info("Etherwarp sequence completed successfully!");
        // Optionally auto-disable the module after completion
        toggle();
    }

    private void onSequenceError(String errorMessage) {
        error(errorMessage);
        toggle(); // Disable module on error
    }
}
