package com.example.addon.utils.pathfinding;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Handles water physics and movement calculations for pathfinding
 */
public class WaterPhysics {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /**
     * Check if a position is water
     */
    public static boolean isWater(World world, BlockPos pos) {
        if (world == null) return false;
        BlockState state = world.getBlockState(pos);
        FluidState fluidState = state.getFluidState();
        return !fluidState.isEmpty() && fluidState.isIn(FluidTags.WATER);
    }

    /**
     * Check if a position is at water surface (water above air or solid)
     */
    public static boolean isWaterSurface(World world, BlockPos pos) {
        if (!isWater(world, pos)) return false;

        // Check if block below is solid or if this is the bottom water block
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);

        return belowState.isSolidBlock(world, below) ||
            (!isWater(world, below) && !belowState.isAir());
    }

    /**
     * Get the water surface level from a given position
     * Returns the Y coordinate of the water surface, or -1 if not in water
     */
    public static int getWaterSurfaceY(World world, BlockPos pos) {
        if (!isWater(world, pos)) return -1;

        BlockPos checkPos = pos;
        int surfaceY = pos.getY();

        // Search upward to find surface
        for (int i = 0; i < 64; i++) {
            if (!isWater(world, checkPos)) {
                return surfaceY;
            }
            surfaceY = checkPos.getY();
            checkPos = checkPos.up();
        }

        return surfaceY;
    }

    /**
     * Calculate water depth from a position
     */
    public static int getWaterDepth(World world, BlockPos pos) {
        if (!isWater(world, pos)) return 0;

        int depth = 0;
        BlockPos checkPos = pos.down();

        while (depth < 64 && isWater(world, checkPos)) {
            depth++;
            checkPos = checkPos.down();
        }

        return depth;
    }

    /**
     * Check if player can swim through this water column
     */
    public static boolean isSwimmable(World world, BlockPos pos) {
        if (!isWater(world, pos)) return false;

        // Check if head space is clear
        BlockPos headPos = pos.up();
        BlockState headState = world.getBlockState(headPos);

        return headState.isAir() || isWater(world, headPos);
    }

    /**
     * Check if player can jump out of water from this position
     */
    public static boolean canJumpFromWater(World world, BlockPos pos) {
        if (!isWaterSurface(world, pos)) return false;

        // Check if there's air above to jump into
        BlockPos above = pos.up();
        return world.getBlockState(above).isAir();
    }

    /**
     * Get movement cost for water
     * Surface swimming is faster than underwater swimming
     */
    public static double getWaterMovementCost(World world, BlockPos pos) {
        if (!isWater(world, pos)) return 0.0;

        if (isWaterSurface(world, pos)) {
            return 1.5; // Surface swimming is slower than walking but faster than diving
        } else {
            return 2.5; // Underwater swimming is slowest
        }
    }

    /**
     * Check if water path is better than land path
     * Used to decide whether to take water route
     */
    public static boolean shouldUseWaterPath(World world, BlockPos from, BlockPos to, double waterCost, double landCost) {
        // If land path exists and is reasonable, prefer it
        if (landCost > 0 && landCost < waterCost * 1.5) {
            return false;
        }

        // Check if water is the only way
        return waterCost > 0;
    }

    /**
     * Check if a position requires diving (water above and below)
     */
    public static boolean requiresDiving(World world, BlockPos pos) {
        if (!isWater(world, pos)) return false;

        return isWater(world, pos.up()) && isWater(world, pos.down());
    }

    /**
     * Get the optimal Y level to swim at in water
     * Prefers surface but will dive if needed
     */
    public static BlockPos getOptimalSwimPosition(World world, BlockPos pos) {
        if (!isWater(world, pos)) return pos;

        int surfaceY = getWaterSurfaceY(world, pos);
        if (surfaceY > 0) {
            return new BlockPos(pos.getX(), surfaceY, pos.getZ());
        }

        return pos;
    }

    /**
     * Check if player should jump while in water
     * Used to jump out of water or reach higher surfaces
     */
    public static boolean shouldJumpInWater(World world, BlockPos currentPos, BlockPos targetPos) {
        if (!isWater(world, currentPos)) return false;

        // Jump if target is above and we're at surface
        if (targetPos.getY() > currentPos.getY() && isWaterSurface(world, currentPos)) {
            return canJumpFromWater(world, currentPos);
        }

        return false;
    }
}
