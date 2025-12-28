package com.example.addon.movement;

import com.example.addon.pathfinding.Path;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Executes a calculated path by controlling player movement with ultra-smooth humanized rotation
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
    private int nodeInterval = 1; // New: node interval setting

    // Ultra-smooth rotation system using ease curves
    private boolean smoothingInitialized;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;

    // Smooth interpolation state
    private float rotationProgress = 1.0f; // 0 = start, 1 = complete
    private float startYaw;
    private float startPitch;
    private float endYaw;
    private float endPitch;

    // Timing for smooth rotation
    private int rotationTicks = 0;
    private int totalRotationTicks = 15; // How many ticks to complete rotation

    // Small random variations
    private float randomOffsetYaw = 0;
    private float randomOffsetPitch = 0;
    private int randomUpdateTicks = 0;
    private static final int RANDOM_UPDATE_INTERVAL = 12;

    // Path recalculation
    private int ticksSinceLastPathCheck;
    private static final int PATH_CHECK_INTERVAL = 20;
    private BlockPos lastGoal;

    // Block breaking
    private boolean breakBlocks;
    private BlockPos currentBreakingBlock;
    private int breakingTicks;

    public PathExecutor() {
        this.movementState = new MovementState();
        this.waypointCheckpointDistance = 0.5;
        this.waypointFinalDistance = 0.5;
        this.smoothRotation = true;
        this.rotationSpeed = 10;
        this.ticksSinceLastJump = 0;
        this.ticksSinceLastPathCheck = 0;
        this.lastGoal = null;
        this.breakBlocks = false;
        this.currentBreakingBlock = null;
        this.breakingTicks = 0;
        this.smoothingInitialized = false;
        this.sprintEnabled = true;
        this.rotationProgress = 1.0f;
        this.rotationTicks = 0;
        this.randomOffsetYaw = 0;
        this.randomOffsetPitch = 0;
        this.randomUpdateTicks = 0;
    }

    /**
     * Set the path to execute
     * Simplifies path based on node interval
     */
    public void setPath(Path path) {
        if (path != null && nodeInterval > 1) {
            // Simplify path by keeping only every Nth waypoint
            var originalWaypoints = path.getWaypoints();
            if (!originalWaypoints.isEmpty()) {
                java.util.List<BlockPos> simplifiedWaypoints = new java.util.ArrayList<>();

                // Always keep first waypoint
                simplifiedWaypoints.add(originalWaypoints.get(0));

                // Add waypoints at nodeInterval increments
                for (int i = nodeInterval; i < originalWaypoints.size(); i += nodeInterval) {
                    simplifiedWaypoints.add(originalWaypoints.get(i));
                }

                // Always keep last waypoint if not already added
                BlockPos last = originalWaypoints.get(originalWaypoints.size() - 1);
                if (!simplifiedWaypoints.get(simplifiedWaypoints.size() - 1).equals(last)) {
                    simplifiedWaypoints.add(last);
                }

                // Create new simplified path
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
        cancelRotation();
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

    public void tick() {
        if (mc.player == null || currentPath == null) {
            stopMovement();
            return;
        }

        // Check if path is complete FIRST before any movement
        if (currentPath.isComplete()) {
            stopMovement();
            return;
        }

        ticksSinceLastJump++;
        ticksSinceLastPathCheck++;
        randomUpdateTicks++;

        // Always update ultra-smooth rotation
        if (smoothRotation) {
            updateUltraSmoothRotation();
        }

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) {
            stopMovement();
            return;
        }

        // Periodically check if path is blocked
        if (ticksSinceLastPathCheck >= PATH_CHECK_INTERVAL) {
            ticksSinceLastPathCheck = 0;
            if (isPathBlocked()) {
                recalculatePath();
                return;
            }
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d waypointVec = Vec3d.ofCenter(currentWaypoint);

        boolean isFinalWaypoint = currentPath.getNextWaypoint() == null;
        double distanceThreshold = isFinalWaypoint ? waypointFinalDistance : waypointCheckpointDistance;

        // Calculate distance
        double distance;
        if (isFinalWaypoint) {
            distance = playerPos.distanceTo(waypointVec);
        } else {
            double dx = playerPos.x - waypointVec.x;
            double dz = playerPos.z - waypointVec.z;
            distance = Math.sqrt(dx * dx + dz * dz);
        }

        // Check if waypoint reached
        if (distance < distanceThreshold) {
            currentPath.advanceWaypoint();

            if (currentPath.isComplete()) {
                stopMovement();
                return;
            }

            currentWaypoint = currentPath.getCurrentWaypoint();
            if (currentWaypoint == null) {
                stopMovement();
                return;
            }
            waypointVec = Vec3d.ofCenter(currentWaypoint);
        } else {
            // NEW: Smart waypoint skipping - if player is ahead on path, skip to nearest waypoint
            skipToNearestWaypoint(playerPos);
        }

        executeMovement(currentWaypoint, waypointVec);
        movementState.tick();
    }

    /**
     * Skip to nearest waypoint if player is ahead on the path
     * FIXED: Now properly respects node interval
     */
    private void skipToNearestWaypoint(Vec3d playerPos) {
        if (currentPath == null) return;

        var waypoints = currentPath.getWaypoints();
        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) return;

        int currentIndex = waypoints.indexOf(currentWaypoint);
        if (currentIndex == -1) return;

        // Look ahead considering the actual distance between waypoints
        int lookAhead = Math.min(nodeInterval * 4, waypoints.size() - currentIndex - 1);
        double closestDistance = Double.MAX_VALUE;
        int closestIndex = currentIndex;

        // Check waypoints at nodeInterval increments
        for (int i = nodeInterval; i <= lookAhead; i += nodeInterval) {
            if (currentIndex + i >= waypoints.size()) break;

            BlockPos checkPos = waypoints.get(currentIndex + i);
            double dist = playerPos.distanceTo(Vec3d.ofCenter(checkPos));

            if (dist < closestDistance) {
                closestDistance = dist;
                closestIndex = currentIndex + i;
            }
        }

        // If we found a closer waypoint ahead, skip to it
        if (closestIndex > currentIndex && closestDistance < waypointCheckpointDistance * 3) {
            for (int i = currentIndex; i < closestIndex; i++) {
                currentPath.advanceWaypoint();
            }
        }
    }

    private void executeMovement(BlockPos waypoint, Vec3d waypointVec) {
        if (mc.player == null) return;

        movementState.setTargetPosition(waypoint);

        Vec3d lookAheadVec = getLookAheadPoint();
        Vec3d targetVec = lookAheadVec != null ? lookAheadVec : waypointVec;

        if (breakBlocks && isBlockInWay()) {
            handleBlockBreaking();
            return;
        } else {
            stopBreaking();
        }

        if (smoothRotation) {
            updateRotationTarget(targetVec);
        } else {
            RotationHelper.faceTarget(targetVec);
        }

        BlockPos nextWaypoint = currentPath.getNextWaypoint();
        boolean shouldJump = shouldJump(waypoint, nextWaypoint);

        // Always move forward
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);

        // Handle jumping
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

        // FIXED: Sprint only when enabled and only forward movement
        if (sprintEnabled && mc.player.isOnGround() && !shouldJump && isMovingForward()) {
            mc.player.setSprinting(true);
        } else {
            mc.player.setSprinting(false);
        }
    }

    /**
     * Check if player is moving primarily forward (not backward or sideways)
     * FIXED: Also checks if player is stuck against a block
     */
    private boolean isMovingForward() {
        if (mc.player == null || mc.world == null) return false;

        // Check that forward key is pressed and no backward/side keys
        boolean forwardPressed = mc.options.forwardKey.isPressed();
        boolean backPressed = mc.options.backKey.isPressed();
        boolean leftPressed = mc.options.leftKey.isPressed();
        boolean rightPressed = mc.options.rightKey.isPressed();

        if (!forwardPressed || backPressed || leftPressed || rightPressed) {
            return false;
        }

        // Check if player is stuck against a block (velocity is near zero while trying to move)
        Vec3d velocity = mc.player.getVelocity();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        // If horizontal speed is very low while trying to move forward, player is stuck
        if (horizontalSpeed < 0.05) {
            return false; // Don't sprint if stuck
        }

        return true;
    }

    private void updateRotationTarget(Vec3d target) {
        if (mc.player == null) return;

        if (!smoothingInitialized) {
            currentYaw = mc.player.getYaw();
            currentPitch = mc.player.getPitch();
            smoothingInitialized = true;
        }

        targetYaw = RotationHelper.getYawTowards(target);
        targetPitch = RotationHelper.getPitchTowards(target);
    }

    /**
     * Ultra-smooth rotation using ease-in-out curves - NO jitter, NO velocity jumps
     * Uses cubic easing for natural acceleration and deceleration
     */
    private void updateUltraSmoothRotation() {
        if (mc.player == null) return;

        // Initialize on first call
        if (!smoothingInitialized) {
            currentYaw = mc.player.getYaw();
            currentPitch = mc.player.getPitch();
            startYaw = currentYaw;
            startPitch = currentPitch;
            endYaw = targetYaw;
            endPitch = targetPitch;
            rotationProgress = 1.0f;
            smoothingInitialized = true;
            return;
        }

        // Check if target changed significantly - start new rotation
        float yawDiff = angleDifference(targetYaw, endYaw);
        float pitchDiff = Math.abs(targetPitch - endPitch);

        if (Math.abs(yawDiff) > 0.5f || pitchDiff > 0.5f) {
            // Target changed, start new smooth rotation
            startYaw = currentYaw;
            startPitch = currentPitch;
            endYaw = targetYaw;
            endPitch = targetPitch;
            rotationProgress = 0.0f;
            rotationTicks = 0;

            // Calculate rotation duration based on distance (larger rotations take longer)
            float totalAngle = (float)Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
            totalRotationTicks = (int)(8 + totalAngle * 0.4f); // Base 8 ticks + extra for larger rotations
            totalRotationTicks = Math.min(totalRotationTicks, 30); // Cap at 30 ticks
        }

        // Update random offsets periodically for natural imperfection
        if (randomUpdateTicks >= RANDOM_UPDATE_INTERVAL) {
            randomUpdateTicks = 0;
            randomOffsetYaw = (float)(Math.random() - 0.5) * 0.08f; // Very small random offset
            randomOffsetPitch = (float)(Math.random() - 0.5) * 0.04f;
        }

        // If rotation is in progress, interpolate smoothly
        if (rotationProgress < 1.0f) {
            rotationTicks++;
            rotationProgress = (float)rotationTicks / (float)totalRotationTicks;
            rotationProgress = Math.min(rotationProgress, 1.0f);

            // Apply ease-in-out cubic curve for ultra-smooth acceleration/deceleration
            float easedProgress = easeInOutCubic(rotationProgress);

            // Interpolate yaw and pitch with proper angle wrapping
            currentYaw = lerpAngle(startYaw, endYaw, easedProgress);
            currentPitch = lerp(startPitch, endPitch, easedProgress);

            // Add tiny random variations (scaled down during rotation for smoothness)
            float variationScale = 0.5f; // Less variation during active rotation
            currentYaw += randomOffsetYaw * variationScale;
            currentPitch += randomOffsetPitch * variationScale;
        } else {
            // Rotation complete, just hold position with tiny random variations
            currentYaw = endYaw + randomOffsetYaw * 0.3f;
            currentPitch = endPitch + randomOffsetPitch * 0.3f;
        }

        // Normalize and clamp
        currentYaw = normalizeAngle(currentYaw);
        currentPitch = clampPitch(currentPitch);

        // Apply to player
        mc.player.setYaw(currentYaw);
        mc.player.setPitch(currentPitch);
    }

    /**
     * Ease-in-out cubic function for ultra-smooth interpolation
     * Starts slow, speeds up in middle, slows down at end - most natural feeling
     */
    private float easeInOutCubic(float t) {
        if (t < 0.5f) {
            return 4 * t * t * t;
        } else {
            float f = 2 * t - 2;
            return 0.5f * f * f * f + 1;
        }
    }

    /**
     * Calculate angle difference with proper wrapping (handles -180 to 180 range)
     */
    private float angleDifference(float target, float current) {
        float diff = normalizeAngle(target - current);
        return diff;
    }

    /**
     * Linear interpolation for angles (handles wrapping)
     */
    private float lerpAngle(float start, float end, float t) {
        float diff = angleDifference(end, start);
        return normalizeAngle(start + diff * t);
    }

    /**
     * Linear interpolation
     */
    private float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    private void cancelRotation() {
        smoothingInitialized = false;
        rotationProgress = 1.0f;
        rotationTicks = 0;
        randomOffsetYaw = 0;
        randomOffsetPitch = 0;
        randomUpdateTicks = 0;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180) angle -= 360;
        else if (angle < -180) angle += 360;
        return angle;
    }

    private float clampPitch(float pitch) {
        return Math.max(-90, Math.min(90, pitch));
    }

    private boolean shouldJump(BlockPos current, BlockPos next) {
        if (mc.player == null || next == null || mc.world == null) return false;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d currentVec = Vec3d.ofCenter(current);

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
            double horizontalDist = RotationHelper.getHorizontalDistance(currentVec);
            if (horizontalDist < 2.5) return true;
        }

        double horizontalDist = RotationHelper.getHorizontalDistance(currentVec);
        if (horizontalDist < 1.5) {
            BlockPos checkPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());

            if (!mc.world.getBlockState(checkPos).isAir() &&
                mc.world.getBlockState(checkPos.up()).isAir()) {
                return true;
            }

            BlockPos checkPos2 = checkPos.offset(mc.player.getHorizontalFacing());
            if (!mc.world.getBlockState(checkPos2).isAir() &&
                mc.world.getBlockState(checkPos2.up()).isAir()) {
                return true;
            }
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
        cancelRotation();

        if (mc.player != null) {
            mc.player.setSprinting(false);
        }

        movementState.reset();
        ticksSinceLastJump = 0;
    }

    public boolean isExecuting() {
        return currentPath != null && !currentPath.isComplete();
    }

    public MovementState getMovementState() {
        return movementState;
    }

    private Vec3d getLookAheadPoint() {
        if (currentPath == null) return null;

        var waypoints = currentPath.getWaypoints();
        BlockPos current = currentPath.getCurrentWaypoint();

        if (current == null || waypoints.isEmpty()) return null;

        int currentIndex = -1;
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).equals(current)) {
                currentIndex = i;
                break;
            }
        }

        // Look ahead based on node interval
        int lookAheadDistance = Math.max(2, nodeInterval * 2);
        int lookAheadIndex = Math.min(currentIndex + lookAheadDistance, waypoints.size() - 1);
        if (lookAheadIndex > currentIndex) {
            return Vec3d.ofCenter(waypoints.get(lookAheadIndex));
        }

        return null;
    }

    private boolean isPathBlocked() {
        if (mc.player == null || mc.world == null || currentPath == null) return false;

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) return false;

        var waypoints = currentPath.getWaypoints();
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
        }

        return false;
    }

    private void recalculatePath() {
        if (mc.player == null || currentPath == null) return;

        BlockPos goal = currentPath.getGoal();
        if (goal == null) return;

        com.example.addon.pathfinding.Path newPath =
            com.example.addon.pathfinding.Pathfinder.findPath(mc.player.getBlockPos(), goal);

        if (newPath != null) {
            setPath(newPath);
        } else {
            clearPath();
        }
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
