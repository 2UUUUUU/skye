package com.example.addon.movement;

import com.example.addon.pathfinding.Path;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Executes a calculated path by controlling player movement
 */
public class PathExecutor {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private Path currentPath;
    private MovementState movementState;
    private double waypointCheckpointDistance;
    private double waypointFinalDistance;
    private boolean smoothRotation;
    private int rotationSpeed; // Time in ticks to complete rotation
    private int ticksSinceLastJump;
    private static final int MIN_JUMP_COOLDOWN = 10; // Minimum ticks between jumps

    // Buttery smooth rotation state - continuous velocity model
    private boolean isRotating;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private float yawVelocity;
    private float pitchVelocity;
    private boolean smoothingInitialized;

    // Advanced smoothing parameters for buttery smooth movement
    private static final float MAX_ROTATION_SPEED = 20.0f; // Max degrees per tick (reduced for smoother)
    private static final float ACCELERATION = 1.2f; // How fast velocity builds up (reduced for smoother)
    private static final float DECELERATION = 0.8f; // How fast velocity slows down (reduced for smoother)
    private static final float DRAG = 0.92f; // Continuous drag/friction (increased for smoother - 8% loss per tick)
    private static final float MICRO_JITTER_AMOUNT = 0.04f; // Subtle randomness (reduced for smoother)
    private static final float OVERSHOOT_FACTOR = 0.01f; // Tiny overshoot for realism (reduced)
    private static final float SMOOTHING_THRESHOLD = 0.5f; // Start fine-tuning below this angle

    // Path recalculation
    private int ticksSinceLastPathCheck;
    private static final int PATH_CHECK_INTERVAL = 20; // Check every second
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
        this.isRotating = false;
        this.smoothingInitialized = false;
        this.yawVelocity = 0;
        this.pitchVelocity = 0;
    }

    /**
     * Set the path to execute
     */
    public void setPath(Path path) {
        this.currentPath = path;
        if (path != null) {
            path.reset();
        }
    }

    /**
     * Get current path
     */
    public Path getPath() {
        return currentPath;
    }

    /**
     * Clear current path and stop movement
     */
    public void clearPath() {
        this.currentPath = null;
        stopMovement();
        stopBreaking();
        movementState.reset();
        cancelRotation();
    }

    /**
     * Set waypoint checkpoint distance
     */
    public void setWaypointCheckpointDistance(double distance) {
        this.waypointCheckpointDistance = distance;
    }

    /**
     * Set waypoint final distance
     */
    public void setWaypointFinalDistance(double distance) {
        this.waypointFinalDistance = distance;
    }

    /**
     * Set smooth rotation settings
     */
    public void setSmoothRotation(boolean smooth, int speed) {
        this.smoothRotation = smooth;
        this.rotationSpeed = Math.max(1, speed);
    }

    /**
     * Set block breaking behavior
     */
    public void setBreakBlocks(boolean breakBlocks) {
        this.breakBlocks = breakBlocks;
    }

    /**
     * Tick the path executor (call this every tick)
     */
    public void tick() {
        if (mc.player == null || currentPath == null) {
            stopMovement();
            return;
        }

        // Increment counters
        ticksSinceLastJump++;
        ticksSinceLastPathCheck++;

        // Update buttery smooth rotation every tick
        if (smoothRotation) {
            updateButterySmoothRotation();
        }

        // Check if path is complete
        if (currentPath.isComplete()) {
            stopMovement();
            return;
        }

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) {
            stopMovement();
            return;
        }

        // Periodically check if path is blocked and recalculate if needed
        if (ticksSinceLastPathCheck >= PATH_CHECK_INTERVAL) {
            ticksSinceLastPathCheck = 0;
            if (isPathBlocked()) {
                recalculatePath();
                return;
            }
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()); // Use actual player position
        Vec3d waypointVec = Vec3d.ofCenter(currentWaypoint);

        // Determine which distance threshold to use
        boolean isFinalWaypoint = currentPath.getNextWaypoint() == null;
        double distanceThreshold = isFinalWaypoint ? waypointFinalDistance : waypointCheckpointDistance;

        // Check if we reached current waypoint - use horizontal distance for checkpoints, full distance for final
        double distance;
        if (isFinalWaypoint) {
            // For final waypoint, use full 3D distance
            distance = playerPos.distanceTo(waypointVec);
        } else {
            // For checkpoints, primarily use horizontal distance (ignore Y difference)
            double dx = playerPos.x - waypointVec.x;
            double dz = playerPos.z - waypointVec.z;
            distance = Math.sqrt(dx * dx + dz * dz);
        }

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
        }

        // Execute movement towards waypoint
        executeMovement(currentWaypoint, waypointVec);
        movementState.tick();
    }

    /**
     * Execute movement towards a waypoint
     */
    private void executeMovement(BlockPos waypoint, Vec3d waypointVec) {
        if (mc.player == null) return;

        // Update movement state
        movementState.setTargetPosition(waypoint);

        // Get look-ahead point for smoother rotation (or use waypoint if no look-ahead)
        Vec3d lookAheadVec = getLookAheadPoint();
        Vec3d targetVec = lookAheadVec != null ? lookAheadVec : waypointVec;

        // Handle block breaking if enabled
        if (breakBlocks && isBlockInWay()) {
            handleBlockBreaking();
            return; // Don't move while breaking
        } else {
            stopBreaking();
        }

        // Update target rotation
        if (smoothRotation) {
            updateRotationTarget(targetVec);
        } else {
            RotationHelper.faceTarget(targetVec);
        }

        // Check if we need to jump
        BlockPos nextWaypoint = currentPath.getNextWaypoint();
        boolean shouldJump = shouldJump(waypoint, nextWaypoint);

        // Set movement keys
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);

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

        // Sprint if moving forward and not jumping up
        if (mc.player.isOnGround() && !shouldJump) {
            mc.player.setSprinting(true);
        }
    }

    /**
     * Update rotation target (doesn't apply immediately, velocity system handles it)
     */
    private void updateRotationTarget(Vec3d target) {
        if (mc.player == null) return;

        // Initialize smoothing state on first call
        if (!smoothingInitialized) {
            currentYaw = mc.player.getYaw();
            currentPitch = mc.player.getPitch();
            smoothingInitialized = true;
        }

        // Calculate new target angles
        targetYaw = RotationHelper.getYawTowards(target);
        targetPitch = RotationHelper.getPitchTowards(target);
    }

    /**
     * Update buttery smooth rotation using physics-based velocity model
     */
    private void updateButterySmoothRotation() {
        if (mc.player == null) return;

        if (!smoothingInitialized) {
            currentYaw = mc.player.getYaw();
            currentPitch = mc.player.getPitch();
            smoothingInitialized = true;
            return;
        }

        // Calculate angle differences (with wrapping for yaw)
        float yawDiff = normalizeAngle(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        // Calculate distances to target
        float yawDistance = Math.abs(yawDiff);
        float pitchDistance = Math.abs(pitchDiff);

        // Determine if we should accelerate or decelerate based on distance
        float yawAcceleration = calculateSmartAcceleration(yawDiff, yawVelocity, yawDistance);
        float pitchAcceleration = calculateSmartAcceleration(pitchDiff, pitchVelocity, pitchDistance);

        // Apply acceleration to velocity
        yawVelocity += yawAcceleration;
        pitchVelocity += pitchAcceleration;

        // Apply drag (friction) for natural slowdown
        yawVelocity *= DRAG;
        pitchVelocity *= DRAG;

        // Add micro-jitter for human-like imperfection (less when velocity is high)
        float jitterScale = 1.0f - Math.min(1.0f, (Math.abs(yawVelocity) + Math.abs(pitchVelocity)) / 10.0f);
        yawVelocity += (Math.random() - 0.5) * MICRO_JITTER_AMOUNT * jitterScale;
        pitchVelocity += (Math.random() - 0.5) * MICRO_JITTER_AMOUNT * jitterScale;

        // Clamp velocities to maximum speed
        yawVelocity = clamp(yawVelocity, -MAX_ROTATION_SPEED, MAX_ROTATION_SPEED);
        pitchVelocity = clamp(pitchVelocity, -MAX_ROTATION_SPEED, MAX_ROTATION_SPEED);

        // Apply velocities to current angles
        currentYaw = normalizeAngle(currentYaw + yawVelocity);
        currentPitch = clampPitch(currentPitch + pitchVelocity);

        // Set player rotation
        mc.player.setYaw(currentYaw);
        mc.player.setPitch(currentPitch);
    }

    /**
     * Calculate smart acceleration - accelerates when far, decelerates when close
     */
    private float calculateSmartAcceleration(float angleDiff, float currentVelocity, float distance) {
        // Normalize the target direction
        float targetDirection = Math.signum(angleDiff);

        // Calculate how much we're already moving in the right direction
        float velocityAlignment = currentVelocity * targetDirection;

        // When far from target, accelerate
        // When close to target, decelerate
        float acceleration;

        if (distance > 15.0f) {
            // Very far from target - accelerate moderately
            acceleration = targetDirection * ACCELERATION * 1.2f;
        } else if (distance > 5.0f) {
            // Far from target - normal acceleration
            acceleration = targetDirection * ACCELERATION;
        } else if (distance > 2.0f) {
            // Medium distance - smooth acceleration with slight overshoot
            float distanceFactor = distance / 5.0f;
            acceleration = targetDirection * ACCELERATION * distanceFactor * 0.7f;

            // Add tiny overshoot for more natural movement
            if (velocityAlignment < distance * 0.08f) {
                acceleration += targetDirection * OVERSHOOT_FACTOR;
            }
        } else if (distance > SMOOTHING_THRESHOLD) {
            // Getting close - gentle deceleration with smooth approach
            if (Math.abs(currentVelocity) > distance * 0.4f) {
                // Moving too fast for this distance, brake gently
                acceleration = -Math.signum(currentVelocity) * DECELERATION * 0.5f;
            } else {
                // Fine-tuning approach
                acceleration = targetDirection * (distance * 0.2f);
            }
        } else {
            // Very close to target - ultra-smooth final adjustment
            if (Math.abs(currentVelocity) > 0.3f) {
                // Still moving, gentle brake
                acceleration = -Math.signum(currentVelocity) * DECELERATION * 0.3f;
            } else {
                // Micro-adjustment
                acceleration = targetDirection * (distance * 0.15f);
            }
        }

        return acceleration;
    }

    /**
     * Cancel ongoing rotation
     */
    private void cancelRotation() {
        smoothingInitialized = false;
        yawVelocity = 0;
        pitchVelocity = 0;
    }

    /**
     * Clamp value between min and max
     */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Normalize angle to [-180, 180]
     */
    private float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180) {
            angle -= 360;
        } else if (angle < -180) {
            angle += 360;
        }
        return angle;
    }

    /**
     * Clamp pitch to valid range
     */
    private float clampPitch(float pitch) {
        return Math.max(-90, Math.min(90, pitch));
    }

    /**
     * Determine if player should jump
     */
    private boolean shouldJump(BlockPos current, BlockPos next) {
        if (mc.player == null || next == null || mc.world == null) return false;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d currentVec = Vec3d.ofCenter(current);

        // Only jump if on ground
        if (!mc.player.isOnGround()) {
            return false;
        }

        // Check if stuck against a wall (distance is very small but can't move forward)
        BlockPos playerBlock = mc.player.getBlockPos();
        BlockPos frontBlock = playerBlock.offset(mc.player.getHorizontalFacing());

        // If there's a solid block directly in front at feet level
        if (!mc.world.getBlockState(frontBlock).isAir() &&
            mc.world.getBlockState(frontBlock).isSolidBlock(mc.world, frontBlock)) {

            // And the block above it is passable
            if (mc.world.getBlockState(frontBlock.up()).isAir() ||
                !mc.world.getBlockState(frontBlock.up()).isSolidBlock(mc.world, frontBlock.up())) {

                // And we're very close to it (stuck against it)
                double distanceToFront = Math.abs(mc.player.getX() - frontBlock.getX() - 0.5) +
                    Math.abs(mc.player.getZ() - frontBlock.getZ() - 0.5);

                if (distanceToFront < 1.5) {
                    return true; // Jump to get over the obstacle
                }
            }
        }

        // Jump if next waypoint is higher
        if (next.getY() > current.getY()) {
            // Check if we're close enough to the current waypoint to jump
            double horizontalDist = RotationHelper.getHorizontalDistance(currentVec);
            if (horizontalDist < 2.5) { // Increased from 2.0 for earlier jumping
                return true;
            }
        }

        // Check for obstacles in front that require jumping (more aggressive detection)
        double horizontalDist = RotationHelper.getHorizontalDistance(currentVec);
        if (horizontalDist < 1.5) { // Increased from 1.0 for earlier detection
            BlockPos checkPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());

            // Check if there's a block in front
            if (!mc.world.getBlockState(checkPos).isAir() &&
                mc.world.getBlockState(checkPos.up()).isAir()) {
                return true;
            }

            // Also check one block ahead
            BlockPos checkPos2 = checkPos.offset(mc.player.getHorizontalFacing());
            if (!mc.world.getBlockState(checkPos2).isAir() &&
                mc.world.getBlockState(checkPos2.up()).isAir()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Stop all movement
     */
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

    /**
     * Check if currently executing a path
     */
    public boolean isExecuting() {
        return currentPath != null && !currentPath.isComplete();
    }

    /**
     * Get movement state
     */
    public MovementState getMovementState() {
        return movementState;
    }

    /**
     * Get look-ahead point for smoother rotation
     */
    private Vec3d getLookAheadPoint() {
        if (currentPath == null) return null;

        var waypoints = currentPath.getWaypoints();
        BlockPos current = currentPath.getCurrentWaypoint();

        if (current == null || waypoints.isEmpty()) return null;

        // Find current waypoint index
        int currentIndex = -1;
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).equals(current)) {
                currentIndex = i;
                break;
            }
        }

        // Look ahead 2-3 waypoints for smoother curves
        int lookAheadIndex = Math.min(currentIndex + 3, waypoints.size() - 1);
        if (lookAheadIndex > currentIndex) {
            return Vec3d.ofCenter(waypoints.get(lookAheadIndex));
        }

        return null;
    }

    /**
     * Check if the path ahead is blocked
     */
    private boolean isPathBlocked() {
        if (mc.player == null || mc.world == null || currentPath == null) return false;

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) return false;

        // Check next 3 waypoints for obstacles
        var waypoints = currentPath.getWaypoints();
        int startIndex = waypoints.indexOf(currentWaypoint);

        for (int i = startIndex; i < Math.min(startIndex + 3, waypoints.size()); i++) {
            BlockPos pos = waypoints.get(i);

            // Check if waypoint is blocked by solid block
            if (!mc.world.getBlockState(pos).isAir() &&
                mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) {
                return true;
            }

            // Check for dangerous fluids
            var fluidState = mc.world.getBlockState(pos).getFluidState();
            if (!fluidState.isEmpty() &&
                (fluidState.isIn(net.minecraft.registry.tag.FluidTags.LAVA))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Recalculate path to goal
     */
    private void recalculatePath() {
        if (mc.player == null || currentPath == null) return;

        BlockPos goal = currentPath.getGoal();
        if (goal == null) return;

        // Import Pathfinder
        com.example.addon.pathfinding.Path newPath =
            com.example.addon.pathfinding.Pathfinder.findPath(mc.player.getBlockPos(), goal);

        if (newPath != null) {
            setPath(newPath);
        } else {
            // Path not found, stop movement
            clearPath();
        }
    }

    /**
     * Check if there's a block in the way
     */
    private boolean isBlockInWay() {
        if (mc.player == null || mc.world == null) return false;

        BlockPos frontPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        BlockPos frontUpPos = frontPos.up();

        // Check if block in front at player level or head level
        boolean blockAtFeet = !mc.world.getBlockState(frontPos).isAir() &&
            mc.world.getBlockState(frontPos).getHardness(mc.world, frontPos) >= 0;
        boolean blockAtHead = !mc.world.getBlockState(frontUpPos).isAir() &&
            mc.world.getBlockState(frontUpPos).getHardness(mc.world, frontUpPos) >= 0;

        return blockAtFeet || blockAtHead;
    }

    /**
     * Handle block breaking logic
     */
    private void handleBlockBreaking() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        BlockPos frontPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        BlockPos frontUpPos = frontPos.up();

        // Determine which block to break
        BlockPos targetBlock = null;
        if (!mc.world.getBlockState(frontUpPos).isAir() &&
            mc.world.getBlockState(frontUpPos).getHardness(mc.world, frontUpPos) >= 0) {
            targetBlock = frontUpPos; // Break head-level block first
        } else if (!mc.world.getBlockState(frontPos).isAir() &&
            mc.world.getBlockState(frontPos).getHardness(mc.world, frontPos) >= 0) {
            targetBlock = frontPos; // Break feet-level block
        }

        if (targetBlock == null) {
            stopBreaking();
            return;
        }

        // Check if block is unbreakable
        if (mc.world.getBlockState(targetBlock).getHardness(mc.world, targetBlock) < 0) {
            stopBreaking();
            return;
        }

        // Start breaking new block
        if (currentBreakingBlock == null || !currentBreakingBlock.equals(targetBlock)) {
            currentBreakingBlock = targetBlock;
            breakingTicks = 0;
        }

        // Look at the block
        RotationHelper.faceTarget(Vec3d.ofCenter(targetBlock));

        // Attack the block
        mc.interactionManager.updateBlockBreakingProgress(targetBlock, net.minecraft.util.math.Direction.UP);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);

        breakingTicks++;

        // Check if block is broken
        if (mc.world.getBlockState(targetBlock).isAir()) {
            stopBreaking();
            currentBreakingBlock = null;
            breakingTicks = 0;
        }
    }

    /**
     * Stop breaking blocks
     */
    private void stopBreaking() {
        if (mc.interactionManager != null) {
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
            mc.interactionManager.cancelBlockBreaking();
        }
        currentBreakingBlock = null;
        breakingTicks = 0;
    }
}
