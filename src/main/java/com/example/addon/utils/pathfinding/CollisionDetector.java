package com.example.addon.utils.pathfinding;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Detects when player is stuck against walls and triggers path recalculation
 */
public class CollisionDetector {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // Tracking state
    private BlockPos lastPlayerPos = null;
    private Vec3d lastPlayerVec = null;
    private int stuckTicks = 0;
    private BlockPos stuckLocation = null;
    private static final int STUCK_THRESHOLD = 30; // Ticks before considering stuck
    private static final double MIN_MOVEMENT = 0.1; // Minimum movement per tick

    /**
     * Check if player is stuck and needs path recalculation
     * @return BlockPos of obstacle if stuck, null otherwise
     */
    public BlockPos checkIfStuck() {
        if (mc.player == null || mc.world == null) {
            reset();
            return null;
        }

        BlockPos currentPos = mc.player.getBlockPos();
        Vec3d currentVec = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        // Initialize tracking
        if (lastPlayerPos == null) {
            lastPlayerPos = currentPos;
            lastPlayerVec = currentVec;
            stuckTicks = 0;
            return null;
        }

        // Calculate movement
        double distanceMoved = currentVec.distanceTo(lastPlayerVec);

        // Check if player is moving
        if (distanceMoved < MIN_MOVEMENT) {
            stuckTicks++;

            // Player hasn't moved significantly
            if (stuckTicks >= STUCK_THRESHOLD) {
                // Find the obstacle blocking the player
                BlockPos obstacle = findObstacle(mc.world, currentPos);

                if (obstacle != null && !obstacle.equals(stuckLocation)) {
                    stuckLocation = obstacle;
                    return obstacle;
                }
            }
        } else {
            // Player is moving, reset counter
            stuckTicks = 0;
            stuckLocation = null;
        }

        lastPlayerPos = currentPos;
        lastPlayerVec = currentVec;

        return null;
    }

    /**
     * Find the obstacle blocking the player
     */
    private BlockPos findObstacle(World world, BlockPos playerPos) {
        if (mc.player == null) return null;

        // Check blocks in front of player in their facing direction
        BlockPos frontPos = playerPos.offset(mc.player.getHorizontalFacing());

        // Check multiple positions forward
        for (int distance = 1; distance <= 3; distance++) {
            BlockPos checkPos = playerPos.offset(mc.player.getHorizontalFacing(), distance);

            // Check at player level and above
            for (int y = 0; y <= 2; y++) {
                BlockPos testPos = checkPos.up(y);
                if (!world.getBlockState(testPos).isAir() &&
                    world.getBlockState(testPos).isSolidBlock(world, testPos)) {
                    return testPos;
                }
            }
        }

        return playerPos; // Return player position as fallback
    }

    /**
     * Reset the stuck detection state
     */
    public void reset() {
        lastPlayerPos = null;
        lastPlayerVec = null;
        stuckTicks = 0;
        stuckLocation = null;
    }

    /**
     * Get the number of ticks player has been stuck
     */
    public int getStuckTicks() {
        return stuckTicks;
    }

    /**
     * Check if player is currently considered stuck
     */
    public boolean isStuck() {
        return stuckTicks >= STUCK_THRESHOLD;
    }
}
