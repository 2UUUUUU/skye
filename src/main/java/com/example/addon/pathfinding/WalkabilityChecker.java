package com.example.addon.pathfinding;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Checks if a position is walkable for the player
 */
public class WalkabilityChecker {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /**
     * Check if the player can walk to this position
     */
    public static boolean isWalkable(World world, BlockPos pos) {
        if (world == null) return false;

        // Check the block at feet level
        BlockState feetBlock = world.getBlockState(pos);
        // Check the block at head level (player is 2 blocks tall)
        BlockState headBlock = world.getBlockState(pos.up());
        // Check the block below (need solid ground)
        BlockState belowBlock = world.getBlockState(pos.down());

        // Head and feet must be passable
        if (!isPassable(feetBlock) || !isPassable(headBlock)) {
            return false;
        }

        // Must have solid ground below
        return isSolid(belowBlock);
    }

    /**
     * Check if a block is passable (player can walk through it)
     */
    public static boolean isPassable(BlockState state) {
        Block block = state.getBlock();

        // Air and similar blocks are passable
        if (state.isAir() || block instanceof FluidBlock) {
            return true;
        }

        // Plants, torches, signs, etc. are passable
        if (block instanceof PlantBlock ||
            block instanceof TorchBlock ||
            block instanceof SignBlock ||
            block instanceof AbstractBannerBlock ||
            block instanceof AbstractPressurePlateBlock ||
            block instanceof CarpetBlock ||
            block instanceof AbstractRedstoneGateBlock ||
            block instanceof AbstractRailBlock) {
            return true;
        }

        // Non-solid blocks are generally passable
        return !state.isSolidBlock(mc.world, BlockPos.ORIGIN);
    }

    /**
     * Check if a block is solid (can stand on it)
     */
    public static boolean isSolid(BlockState state) {
        Block block = state.getBlock();

        // Air is not solid
        if (state.isAir()) {
            return false;
        }

        // Fluids are not solid
        if (block instanceof FluidBlock) {
            return false;
        }

        // Plants are not solid
        if (block instanceof PlantBlock) {
            return false;
        }

        // Most other blocks are solid
        return state.isSolidBlock(mc.world, BlockPos.ORIGIN);
    }

    /**
     * Check if moving from one position to another is safe
     */
    public static boolean isSafeMove(World world, BlockPos from, BlockPos to) {
        // Check basic walkability
        if (!isWalkable(world, to)) {
            return false;
        }

        // Check for dangerous blocks
        if (isDangerous(world, to)) {
            return false;
        }

        // Check for lava below (within fall distance)
        for (int i = 1; i <= PathCost.MAX_FALL_DISTANCE; i++) {
            BlockPos checkPos = to.down(i);
            BlockState state = world.getBlockState(checkPos);
            FluidState fluidState = state.getFluidState();

            if (!fluidState.isEmpty() && fluidState.isIn(FluidTags.LAVA)) {
                return false;
            }

            if (isSolid(state)) {
                break;
            }
        }

        return true;
    }

    /**
     * Check if a position contains dangerous blocks
     */
    public static boolean isDangerous(World world, BlockPos pos) {
        BlockState feetBlock = world.getBlockState(pos);
        BlockState belowBlock = world.getBlockState(pos.down());

        Block feet = feetBlock.getBlock();
        Block below = belowBlock.getBlock();

        // Check for lava using fluid states
        FluidState feetFluid = feetBlock.getFluidState();
        FluidState belowFluid = belowBlock.getFluidState();

        if ((!feetFluid.isEmpty() && feetFluid.isIn(FluidTags.LAVA)) ||
            (!belowFluid.isEmpty() && belowFluid.isIn(FluidTags.LAVA))) {
            return true;
        }

        // Fire is dangerous
        if (feet instanceof FireBlock || below instanceof FireBlock) {
            return true;
        }

        // Cactus is dangerous
        if (feet instanceof CactusBlock || below instanceof CactusBlock) {
            return true;
        }

        // Sweet berry bushes are dangerous
        if (feet instanceof SweetBerryBushBlock || below instanceof SweetBerryBushBlock) {
            return true;
        }

        return false;
    }

    /**
     * Get the cost penalty for walking on this block
     */
    public static double getBlockPenalty(World world, BlockPos pos) {
        BlockState feetBlock = world.getBlockState(pos);
        Block block = feetBlock.getBlock();
        FluidState fluidState = feetBlock.getFluidState();

        // Water slows movement
        if (!fluidState.isEmpty() && fluidState.isIn(FluidTags.WATER)) {
            return PathCost.WATER;
        }

        // Soul sand slows movement
        if (block instanceof SoulSandBlock) {
            return PathCost.SOUL_SAND;
        }

        // Lava is extremely costly
        if (!fluidState.isEmpty() && fluidState.isIn(FluidTags.LAVA)) {
            return PathCost.LAVA;
        }

        return 0.0;
    }

    /**
     * Calculate fall distance from a position
     */
    public static int calculateFallDistance(World world, BlockPos pos) {
        int distance = 0;
        BlockPos checkPos = pos.down();

        while (distance < PathCost.MAX_FALL_DISTANCE) {
            BlockState state = world.getBlockState(checkPos);

            if (isSolid(state)) {
                return distance;
            }

            distance++;
            checkPos = checkPos.down();
        }

        return PathCost.MAX_FALL_DISTANCE;
    }
}
