package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

public class HypixelExample extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> exampleSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("example-setting")
        .description("An example setting for the Hypixel Skyblock category.")
        .defaultValue(true)
        .build()
    );

    public HypixelExample() {
        super(AddonTemplate.HYPIXEL_SKYBLOCK, "hypixel-example", "An example module for the Hypixel Skyblock category.");
    }

    @Override
    public void onActivate() {
        info("Hypixel Skyblock Example module activated!");
    }

    @Override
    public void onDeactivate() {
        info("Hypixel Skyblock Example module deactivated!");
    }
}
