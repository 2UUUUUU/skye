package com.example.addon.commands;

import com.example.addon.pathfinding.Path;
import com.example.addon.pathfinding.Pathfinder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.BlockPos;

/**
 * Command to test pathfinding functionality
 * Usage: .path <x> <y> <z> - Find path to coordinates
 * Usage: .path clear - Clear current path visualization
 */
public class PathCommand extends Command {
    private static Path currentPath = null;

    public PathCommand() {
        super("path", "Find a path to specified coordinates.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // .path clear
        builder.then(literal("clear").executes(context -> {
            currentPath = null;
            info("Path cleared.");
            return SINGLE_SUCCESS;
        }));

        // .path <x> <y> <z>
        builder.then(argument("x", IntegerArgumentType.integer())
            .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                    .executes(context -> {
                        int x = IntegerArgumentType.getInteger(context, "x");
                        int y = IntegerArgumentType.getInteger(context, "y");
                        int z = IntegerArgumentType.getInteger(context, "z");

                        return findPath(x, y, z);
                    })
                )
            )
        );

        // .path (use current looking position)
        builder.executes(context -> {
            if (mc.player == null) {
                error("Not in game!");
                return SINGLE_SUCCESS;
            }

            var hitResult = mc.crosshairTarget;
            if (hitResult == null) {
                error("Not looking at a block!");
                return SINGLE_SUCCESS;
            }

            if (hitResult instanceof net.minecraft.util.hit.BlockHitResult blockHit) {
                BlockPos target = blockHit.getBlockPos().up();
                return findPath(target.getX(), target.getY(), target.getZ());
            } else {
                error("Not looking at a block!");
                return SINGLE_SUCCESS;
            }
        });
    }

    private int findPath(int x, int y, int z) {
        if (mc.player == null || mc.world == null) {
            error("Not in game!");
            return SINGLE_SUCCESS;
        }

        BlockPos start = mc.player.getBlockPos();
        BlockPos goal = new BlockPos(x, y, z);

        info("Calculating path from " + formatPos(start) + " to " + formatPos(goal) + "...");

        long startTime = System.currentTimeMillis();
        Path path = Pathfinder.findPath(start, goal);
        long duration = System.currentTimeMillis() - startTime;

        if (path == null) {
            error("No path found! (took " + duration + "ms)");
            return SINGLE_SUCCESS;
        }

        currentPath = path;

        info("Path found! (took " + duration + "ms)");
        info("Waypoints: " + path.getLength());
        info("Total cost: " + String.format("%.2f", path.getTotalCost()));
        info("Path distance: " + String.format("%.1f", path.getPathDistance()) + " blocks");
        info("Crow fly distance: " + String.format("%.1f", path.getStraightLineDistance()) + " blocks");

        return SINGLE_SUCCESS;
    }

    private String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static Path getCurrentPath() {
        return currentPath;
    }
}
