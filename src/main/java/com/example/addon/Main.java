package com.example.addon;

import com.example.addon.commands.*;
import com.example.addon.hud.*;
import com.example.addon.modules.*;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Main extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("SMP");
    public static final Category HYPIXEL_SKYBLOCK = new Category("Hypixel Skyblock");
    public static final Category ETHERWARP_TEST = new Category("Etherwarp Test");
    public static final HudGroup HUD_GROUP = new HudGroup("Example");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Skye");

        // Modules
        Modules.get().add(new ModuleExample());
        Modules.get().add(new SpawnerProtect());
        Modules.get().add(new AutoSpawnerSell());
        Modules.get().add(new SpawnerDropper()); // Added SpawnerDropper
        Modules.get().add(new HypixelExample());
        Modules.get().add(new DragonAssistant());
        Modules.get().add(new EtherwarpTest());

        // Commands
        Commands.add(new CommandExample());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(HYPIXEL_SKYBLOCK);
        Modules.registerCategory(ETHERWARP_TEST);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
