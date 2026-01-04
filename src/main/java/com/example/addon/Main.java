package com.example.addon;

import com.example.addon.commands.*;
import com.example.addon.hud.*;
import com.example.addon.modules.*;
import com.example.addon.modules.AutoBrewer;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;

public class Main extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("SMP");
    public static final Category HYPIXEL_SKYBLOCK = new Category("Hypixel Skyblock");
    public static final Category TEST = new Category("Test");
    public static final HudGroup HUD_GROUP = new HudGroup("Example");

    @Override
    public void onInitialize() {
        ChatUtils.registerCustomPrefix("com.example.addon", () -> {
            return Text.empty()
                .setStyle(Style.EMPTY.withFormatting(Formatting.GRAY))
                .append("[")
                .append(Text.literal("Skye").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFA500)))) // Orange color
                .append("] ");
        });
        LOG.info("Initializing Skye");

        // Modules
        Modules.get().add(new AutoCrafting());
        Modules.get().add(new AutoBrewer());
        Modules.get().add(new SpawnerProtect());
        Modules.get().add(new AutoSpawnerSell());
        Modules.get().add(new SpawnerDropper());
        Modules.get().add(new HypixelExample());
        Modules.get().add(new DragonAssistant());
        Modules.get().add(new EtherwarpTest());
        Modules.get().add(new Pathfinding());

        // Commands
        Commands.add(new CommandExample());
        Commands.add(new PathCommand());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(HYPIXEL_SKYBLOCK);
        Modules.registerCategory(TEST);
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
