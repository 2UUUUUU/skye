package com.example.addon.utils.pathfinding;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Checks line of sight between player and waypoints
 */
public class LineOfSightChecker {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /**
     * Check if waypoint is visible from player's position
     * @param targetPos The waypoint to check
     * @return true if waypoint is visible, false if blocked
     */
    public static boolean isWaypointVisible(BlockPos targetPos) {
        if (mc.player == null || mc.world == null) return false;

        Vec3d playerEyes = mc.player.getEyePos();
        Vec3d targetCenter = new Vec3d(
            targetPos.getX() + 0.5,
            targetPos.getY() + 0.5,
            targetPos.getZ() + 0.5
        );

        return hasLineOfSight(mc.world, playerEyes, targetCenter);
    }

    /**
     * Check if there's a clear line of sight between two positions
     */
    public static boolean hasLineOfSight(World world, Vec3d from, Vec3d to) {
        if (world == null) return false;

        // Perform raycast
        BlockHitResult hitResult = world.raycast(new RaycastContext(
            from,
            to,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));

        // If we hit nothing or hit at the target, line of sight is clear
        if (hitResult.getType() == HitResult.Type.MISS) {
            return true;
        }

        // Check if the hit position is very close to target (within same block)
        BlockPos hitBlock = hitResult.getBlockPos();
        BlockPos targetBlock = BlockPos.ofFloored(to);

        return hitBlock.equals(targetBlock) ||
            hitBlock.equals(targetBlock.down()) ||
            hitBlock.getManhattanDistance(targetBlock) <= 1;
    }

    /**
     * Find the next visible waypoint from player's current position
     * Skips waypoints that are blocked by obstacles
     * @param waypoints List of waypoints in order
     * @param currentIndex Current waypoint index
     * @return Index of next visible waypoint, or currentIndex if none found
     */
    public static int findNextVisibleWaypoint(java.util.List<BlockPos> waypoints, int currentIndex) {
        if (mc.player == null || waypoints == null || currentIndex >= waypoints.size()) {
            return currentIndex;
        }

        // Start from current waypoint
        for (int i = currentIndex; i < waypoints.size(); i++) {
            BlockPos waypoint = waypoints.get(i);

            if (isWaypointVisible(waypoint)) {
                return i;
            }
        }

        // If no visible waypoint found, return current
        return currentIndex;
    }

    /**
     * Find the furthest visible waypoint from player
     * This allows skipping multiple waypoints if there's line of sight
     * @param waypoints List of waypoints
     * @param currentIndex Current waypoint index
     * @param maxLookAhead Maximum number of waypoints to check ahead
     * @return Index of furthest visible waypoint
     */
    public static int findFurthestVisibleWaypoint(java.util.List<BlockPos> waypoints, int currentIndex, int maxLookAhead) {
        if (mc.player == null || waypoints == null || currentIndex >= waypoints.size()) {
            return currentIndex;
        }

        int furthestVisible = currentIndex;
        int lookAheadLimit = Math.min(currentIndex + maxLookAhead, waypoints.size());

        // Check waypoints ahead
        for (int i = currentIndex; i < lookAheadLimit; i++) {
            BlockPos waypoint = waypoints.get(i);

            if (isWaypointVisible(waypoint)) {
                furthestVisible = i;
            } else {
                // Stop at first non-visible waypoint
                break;
            }
        }

        return furthestVisible;
    }

    /**
     * Check if player has passed a waypoint
     * Used to skip waypoints that are behind the player
     */
    public static boolean hasPassedWaypoint(BlockPos waypoint) {
        if (mc.player == null) return false;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d waypointVec = new Vec3d(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5);

        // Calculate direction from player to waypoint
        Vec3d toWaypoint = waypointVec.subtract(playerPos).normalize();

        // Get player's facing direction
        Vec3d playerFacing = Vec3d.fromPolar(0, mc.player.getYaw());

        // Dot product tells us if waypoint is ahead or behind
        // Positive = ahead, negative = behind
        double dotProduct = playerFacing.x * toWaypoint.x + playerFacing.z * toWaypoint.z;

        return dotProduct < -0.5; // Waypoint is significantly behind player
    }
}
