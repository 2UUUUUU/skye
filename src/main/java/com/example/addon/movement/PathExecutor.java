package com.example.addon.movement;

import com.example.addon.pathfinding.Path;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.function.Consumer;

/**
 * Executes a calculated path by controlling player movement with ultra-smooth humanized rotation
 * FIXED: Proper goal reaching with X, Y, Z coordinate checks
 */
public class PathExecutor {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private Path currentPath;
    private MovementState movementState;
    private double waypointCheckpointDistance;
    private double waypointFinalDistance;
    private boolean smoothRotation;
    private int rotationSpeed;
    private int ticksSinceLastJump;
    private static final int MIN_JUMP_COOLDOWN = 10;
    private boolean sprintEnabled;
    private int nodeInterval = 1;
    private int maxFallDistance = 3;

    // Path recalculation
    private int ticksSinceLastPathCheck;
    private static final int PATH_CHECK_INTERVAL = 20;
    private static final int RECALC_AFTER_WAYPOINT_TICKS = 5;
    private int ticksSinceLastWaypoint = 0;
    private boolean shouldRecalcAfterWaypoint = false;

    // Block breaking
    private boolean breakBlocks;
    private BlockPos currentBreakingBlock;
    private int breakingTicks;

    // Track if we've reached the goal to prevent re-activation
    private boolean hasReachedGoal = false;

    // Debug callback
    private Consumer<String> debugCallback = null;

    public PathExecutor() {
        this.movementState = new MovementState();
        this.waypointCheckpointDistance = 0.5;
        this.waypointFinalDistance = 0.5;
        this.smoothRotation = true;
        this.rotationSpeed = 10;
        this.ticksSinceLastJump = 0;
        this.ticksSinceLastPathCheck = 0;
        this.breakBlocks = false;
        this.currentBreakingBlock = null;
        this.breakingTicks = 0;
        this.sprintEnabled = true;
        this.hasReachedGoal = false;
    }

    /**
     * Set debug callback for logging
     */
    public void setDebugCallback(Consumer<String> callback) {
        this.debugCallback = callback;
    }

    private void debug(String message) {
        if (debugCallback != null) {
            debugCallback.accept(message);
        }
    }

    public void setPath(Path path) {
        hasReachedGoal = false;

        if (path != null && nodeInterval > 1) {
            List<BlockPos> originalWaypoints = path.getWaypoints();
            if (!originalWaypoints.isEmpty()) {
                java.util.List<BlockPos> simplifiedWaypoints = new java.util.ArrayList<>();
                simplifiedWaypoints.add(originalWaypoints.get(0));

                for (int i = nodeInterval; i < originalWaypoints.size(); i += nodeInterval) {
                    simplifiedWaypoints.add(originalWaypoints.get(i));
                }

                BlockPos last = originalWaypoints.get(originalWaypoints.size() - 1);
                if (!simplifiedWaypoints.get(simplifiedWaypoints.size() - 1).equals(last)) {
                    simplifiedWaypoints.add(last);
                }

                this.currentPath = new com.example.addon.pathfinding.Path(simplifiedWaypoints, path.getTotalCost());
                this.currentPath.reset();
                return;
            }
        }

        this.currentPath = path;
        if (path != null) {
            path.reset();
        }
    }

    public Path getPath() {
        return currentPath;
    }

    public void clearPath() {
        this.currentPath = null;
        stopMovement();
        stopBreaking();
        movementState.reset();
        hasReachedGoal = false;
        RotationHelper.cancelHumanLikeRotation();
    }

    public void setWaypointCheckpointDistance(double distance) {
        this.waypointCheckpointDistance = distance;
    }

    public void setWaypointFinalDistance(double distance) {
        this.waypointFinalDistance = distance;
    }

    public void setSmoothRotation(boolean smooth, int speed) {
        this.smoothRotation = smooth;
        this.rotationSpeed = Math.max(1, speed);
    }

    public void setBreakBlocks(boolean breakBlocks) {
        this.breakBlocks = breakBlocks;
    }

    public void setSprint(boolean sprint) {
        this.sprintEnabled = sprint;
    }

    public void setNodeInterval(int interval) {
        this.nodeInterval = Math.max(1, interval);
    }

    public void setMaxFallDistance(int distance) {
        this.maxFallDistance = Math.max(0, distance);
    }

    public void tick() {
        if (mc.player == null || currentPath == null) {
            stopMovement();
            return;
        }

        if (hasReachedGoal) {
            stopMovement();
            return;
        }

        if (currentPath.isComplete()) {
            hasReachedGoal = true;
            stopMovement();
            return;
        }

        ticksSinceLastJump++;
        ticksSinceLastPathCheck++;
        ticksSinceLastWaypoint++;

        if (smoothRotation) {
            RotationHelper.tickHumanLikeRotation();
        }

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) {
            hasReachedGoal = true;
            stopMovement();
            return;
        }

        // Check if we should recalculate after reaching a waypoint
        if (shouldRecalcAfterWaypoint && ticksSinceLastWaypoint >= RECALC_AFTER_WAYPOINT_TICKS) {
            shouldRecalcAfterWaypoint = false;
            debug("Recalculating path after reaching waypoint for efficiency");
            recalculatePathFromCurrent();

            // Refresh current waypoint after recalculation
            currentWaypoint = currentPath.getCurrentWaypoint();
            if (currentWaypoint == null) {
                hasReachedGoal = true;
                stopMovement();
                return;
            }
        }

        if (ticksSinceLastPathCheck >= PATH_CHECK_INTERVAL) {
            ticksSinceLastPathCheck = 0;
            if (isPathBlocked()) {
                recalculatePath();
                return;
            }
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        // FIXED: Use block base position instead of center for Y coordinate
        Vec3d waypointVec = new Vec3d(
            currentWaypoint.getX() + 0.5,  // Center X
            currentWaypoint.getY(),         // Base Y (player's feet level)
            currentWaypoint.getZ() + 0.5   // Center Z
        );

        boolean isFinalWaypoint = currentPath.getNextWaypoint() == null;

        double distanceThreshold;
        if (isFinalWaypoint) {
            distanceThreshold = waypointFinalDistance;
        } else {
            distanceThreshold = Math.max(waypointCheckpointDistance * 1.5, 1.0);
        }

        // FIXED: Check X, Y, Z distances separately for proper 3D positioning
        double dx = Math.abs(playerPos.x - waypointVec.x);
        double dy = Math.abs(playerPos.y - waypointVec.y);
        double dz = Math.abs(playerPos.z - waypointVec.z);

        // Calculate 3D Euclidean distance
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // For final waypoint, require stricter positioning on all axes
        boolean isWithinBounds = true;
        if (isFinalWaypoint) {
            // Must be within threshold on all three axes
            double axisBound = distanceThreshold;
            isWithinBounds = (dx <= axisBound) && (dy <= axisBound) && (dz <= axisBound);
        }

        // Debug output for final waypoint
        if (isFinalWaypoint && mc.player != null && ticksSinceLastJump % 20 == 0) {
            debug("Distance to final waypoint: " + String.format("%.3f", distance) + " / Threshold: " + String.format("%.3f", distanceThreshold));
            debug("Axis distances - X: " + String.format("%.3f", dx) + ", Y: " + String.format("%.3f", dy) + ", Z: " + String.format("%.3f", dz));
            debug("Within bounds: " + isWithinBounds);
        }

        // Check if waypoint is reached
        if (distance < distanceThreshold && isWithinBounds) {
            if (isFinalWaypoint && mc.player != null) {
                debug("✓ REACHED final waypoint! Distance: " + String.format("%.3f", distance) + " / Threshold: " + String.format("%.3f", distanceThreshold));
                debug("Player pos: " + String.format("%.2f, %.2f, %.2f", playerPos.x, playerPos.y, playerPos.z));
                debug("Goal pos: " + String.format("%d, %d, %d", currentWaypoint.getX(), currentWaypoint.getY(), currentWaypoint.getZ()));
            }

            currentPath.advanceWaypoint();
            ticksSinceLastWaypoint = 0;
            shouldRecalcAfterWaypoint = true;

            if (currentPath.isComplete()) {
                if (mc.player != null) {
                    debug("Path completed! Stopping movement.");
                }
                hasReachedGoal = true;
                stopMovement();
                return;
            }

            currentWaypoint = currentPath.getCurrentWaypoint();
            if (currentWaypoint == null) {
                hasReachedGoal = true;
                stopMovement();
                return;
            }
            // Update waypointVec with new target using base Y
            waypointVec = new Vec3d(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5
            );
        }

        executeMovement(currentWaypoint, waypointVec);
        movementState.tick();
    }

    private void executeMovement(BlockPos waypoint, Vec3d waypointVec) {
        if (mc.player == null) return;

        movementState.setTargetPosition(waypoint);

        Vec3d targetVec = getFarLookAheadPoint();
        if (targetVec == null) {
            targetVec = waypointVec;
        }

        if (breakBlocks && isBlockInWay()) {
            handleBlockBreaking();
            return;
        } else {
            stopBreaking();
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d directionToTarget = targetVec.subtract(playerPos).normalize();

        float playerYaw = mc.player.getYaw();
        Vec3d playerFacing = Vec3d.fromPolar(0, playerYaw);

        double dotProduct = playerFacing.x * directionToTarget.x + playerFacing.z * directionToTarget.z;
        double angleDiff = Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct)));

        boolean useStrafing = shouldUseStrafing(directionToTarget, angleDiff);

        if (smoothRotation) {
            RotationHelper.rotateTowardsHumanLike(targetVec, rotationSpeed);
        } else {
            RotationHelper.faceTarget(targetVec);
        }

        BlockPos nextWaypoint = currentPath.getNextWaypoint();
        boolean shouldJump = shouldJump(waypoint, nextWaypoint);

        if (useStrafing) {
            applyStrafeMovement(directionToTarget, angleDiff);
        } else {
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
        }

        if (shouldJump && ticksSinceLastJump >= MIN_JUMP_COOLDOWN) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
            movementState.setAction(MovementState.Action.JUMPING);
            ticksSinceLastJump = 0;
        } else {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
            if (mc.player.isOnGround()) {
                movementState.setAction(MovementState.Action.WALKING);
            } else {
                movementState.setAction(MovementState.Action.FALLING);
            }
        }

        if (sprintEnabled && mc.player.isOnGround() && !shouldJump && isMovingForward()) {
            mc.player.setSprinting(true);
        } else {
            mc.player.setSprinting(false);
        }
    }

    private boolean shouldUseStrafing(Vec3d directionToTarget, double angleDiff) {
        if (mc.player == null || mc.world == null) return false;

        BlockPos frontBlock = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        if (!mc.world.getBlockState(frontBlock).isAir() &&
            mc.world.getBlockState(frontBlock).isSolidBlock(mc.world, frontBlock)) {
            return true;
        }

        if (angleDiff > Math.toRadians(45)) {
            return true;
        }

        return false;
    }

    private void applyStrafeMovement(Vec3d directionToTarget, double angleDiff) {
        if (mc.player == null) return;

        float playerYaw = mc.player.getYaw();
        Vec3d playerFacing = Vec3d.fromPolar(0, playerYaw);

        double cross = playerFacing.x * directionToTarget.z - playerFacing.z * directionToTarget.x;

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);

        if (cross > 0.1) {
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), true);
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
        } else if (cross < -0.1) {
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), true);
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        } else {
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
        }
    }

    private boolean isMovingForward() {
        if (mc.player == null || mc.world == null) return false;

        boolean forwardPressed = mc.options.forwardKey.isPressed();
        boolean backPressed = mc.options.backKey.isPressed();

        if (!forwardPressed || backPressed) {
            return false;
        }

        Vec3d velocity = mc.player.getVelocity();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        if (horizontalSpeed < 0.05) {
            return false;
        }

        return true;
    }

    private boolean shouldJump(BlockPos current, BlockPos next) {
        if (mc.player == null || next == null || mc.world == null) return false;

        if (!mc.player.isOnGround()) return false;

        BlockPos playerBlock = mc.player.getBlockPos();
        BlockPos frontBlock = playerBlock.offset(mc.player.getHorizontalFacing());

        if (!mc.world.getBlockState(frontBlock).isAir() &&
            mc.world.getBlockState(frontBlock).isSolidBlock(mc.world, frontBlock)) {

            if (mc.world.getBlockState(frontBlock.up()).isAir() ||
                !mc.world.getBlockState(frontBlock.up()).isSolidBlock(mc.world, frontBlock.up())) {

                double distanceToFront = Math.abs(mc.player.getX() - frontBlock.getX() - 0.5) +
                    Math.abs(mc.player.getZ() - frontBlock.getZ() - 0.5);

                if (distanceToFront < 1.5) return true;
            }
        }

        if (next.getY() > current.getY()) {
            Vec3d currentVec = Vec3d.ofCenter(current);
            double horizontalDist = RotationHelper.getHorizontalDistance(currentVec);
            if (horizontalDist < 2.5) return true;
        }

        if (next.getY() < current.getY()) {
            int fallDistance = current.getY() - next.getY();

            if (fallDistance > maxFallDistance) {
                return false;
            }

            return false;
        }

        return false;
    }

    public void stopMovement() {
        if (mc.options == null) return;

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);

        stopBreaking();
        RotationHelper.cancelHumanLikeRotation();

        if (mc.player != null) {
            mc.player.setSprinting(false);
        }

        movementState.reset();
        ticksSinceLastJump = 0;
    }

    public boolean isExecuting() {
        return currentPath != null && !currentPath.isComplete() && !hasReachedGoal;
    }

    public MovementState getMovementState() {
        return movementState;
    }

    private Vec3d getFarLookAheadPoint() {
        if (currentPath == null) return null;

        List<BlockPos> waypoints = currentPath.getWaypoints();
        BlockPos current = currentPath.getCurrentWaypoint();

        if (current == null || waypoints.isEmpty()) return null;

        int currentIndex = -1;
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).equals(current)) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex == -1) return null;

        int maxLookAhead = Math.min(8, waypoints.size() - currentIndex - 1);

        if (maxLookAhead <= 0) {
            return new Vec3d(
                waypoints.get(waypoints.size() - 1).getX() + 0.5,
                waypoints.get(waypoints.size() - 1).getY(),
                waypoints.get(waypoints.size() - 1).getZ() + 0.5
            );
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        for (int i = maxLookAhead; i > 0; i--) {
            BlockPos lookAheadPos = waypoints.get(currentIndex + i);
            Vec3d lookAheadVec = new Vec3d(
                lookAheadPos.getX() + 0.5,
                lookAheadPos.getY(),
                lookAheadPos.getZ() + 0.5
            );
            double distance = playerPos.distanceTo(lookAheadVec);

            if (distance <= 16.0) {
                return lookAheadVec;
            }
        }

        BlockPos nextPos = waypoints.get(currentIndex + 1);
        return new Vec3d(
            nextPos.getX() + 0.5,
            nextPos.getY(),
            nextPos.getZ() + 0.5
        );
    }

    private boolean isPathBlocked() {
        if (mc.player == null || mc.world == null || currentPath == null) return false;

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) return false;

        List<BlockPos> waypoints = currentPath.getWaypoints();
        int startIndex = waypoints.indexOf(currentWaypoint);

        for (int i = startIndex; i < Math.min(startIndex + 3, waypoints.size()); i++) {
            BlockPos pos = waypoints.get(i);

            if (!mc.world.getBlockState(pos).isAir() &&
                mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) {
                return true;
            }

            var fluidState = mc.world.getBlockState(pos).getFluidState();
            if (!fluidState.isEmpty() &&
                (fluidState.isIn(net.minecraft.registry.tag.FluidTags.LAVA))) {
                return true;
            }

            if (i > startIndex) {
                BlockPos prev = waypoints.get(i - 1);
                int fallDist = prev.getY() - pos.getY();
                if (fallDist > maxFallDistance) {
                    debug("Path blocked: Fall distance " + fallDist + " exceeds max " + maxFallDistance);
                    return true;
                }
            }
        }

        return false;
    }

    private void recalculatePath() {
        if (mc.player == null || currentPath == null) return;

        BlockPos goal = currentPath.getGoal();
        if (goal == null) return;

        debug("Path blocked - recalculating from current position");

        com.example.addon.pathfinding.Path newPath =
            com.example.addon.pathfinding.Pathfinder.findPath(mc.player.getBlockPos(), goal);

        if (newPath != null) {
            debug("New path found with " + newPath.getLength() + " waypoints");
            setPath(newPath);
        } else {
            debug("No alternative path found - clearing path");
            clearPath();
        }
    }

    /**
     * Recalculate path from current position to goal (used after reaching waypoints for efficiency)
     * DISABLED: Causes the bot to go backward. Only use regular recalculation on blocked paths.
     */
    private void recalculatePathFromCurrent() {
        // DISABLED - this feature causes backward movement
        // The pathfinding should stick to the original calculated path
        // Only recalculate when the path is actually blocked
        debug("Path recalculation after waypoint is disabled - continuing on current path");
        shouldRecalcAfterWaypoint = false;
        return;
    }

    private boolean isBlockInWay() {
        if (mc.player == null || mc.world == null) return false;

        BlockPos frontPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        BlockPos frontUpPos = frontPos.up();

        boolean blockAtFeet = !mc.world.getBlockState(frontPos).isAir() &&
            mc.world.getBlockState(frontPos).getHardness(mc.world, frontPos) >= 0;
        boolean blockAtHead = !mc.world.getBlockState(frontUpPos).isAir() &&
            mc.world.getBlockState(frontUpPos).getHardness(mc.world, frontUpPos) >= 0;

        return blockAtFeet || blockAtHead;
    }

    private void handleBlockBreaking() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        BlockPos frontPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        BlockPos frontUpPos = frontPos.up();

        BlockPos targetBlock = null;
        if (!mc.world.getBlockState(frontUpPos).isAir() &&
            mc.world.getBlockState(frontUpPos).getHardness(mc.world, frontUpPos) >= 0) {
            targetBlock = frontUpPos;
        } else if (!mc.world.getBlockState(frontPos).isAir() &&
            mc.world.getBlockState(frontPos).getHardness(mc.world, frontPos) >= 0) {
            targetBlock = frontPos;
        }

        if (targetBlock == null) {
            stopBreaking();
            return;
        }

        if (mc.world.getBlockState(targetBlock).getHardness(mc.world, targetBlock) < 0) {
            stopBreaking();
            return;
        }

        if (currentBreakingBlock == null || !currentBreakingBlock.equals(targetBlock)) {
            currentBreakingBlock = targetBlock;
            breakingTicks = 0;
        }

        RotationHelper.faceTarget(Vec3d.ofCenter(targetBlock));
        mc.interactionManager.updateBlockBreakingProgress(targetBlock, net.minecraft.util.math.Direction.UP);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);

        breakingTicks++;

        if (mc.world.getBlockState(targetBlock).isAir()) {
            stopBreaking();
            currentBreakingBlock = null;
            breakingTicks = 0;
        }
    }

    private void stopBreaking() {
        if (mc.interactionManager != null) {
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
            mc.interactionManager.cancelBlockBreaking();
        }
        currentBreakingBlock = null;
        breakingTicks = 0;
    }
}
