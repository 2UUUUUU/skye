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
    private double waypointReachDistance;
    private boolean smoothRotation;
    private float rotationSpeed;
    private int ticksSinceLastJump;
    private static final int MIN_JUMP_COOLDOWN = 10; // Minimum ticks between jumps

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
        this.waypointReachDistance = 0.5;
        this.smoothRotation = true;
        this.rotationSpeed = 15.0f; // Slower default for more human-like movement
        this.ticksSinceLastJump = 0;
        this.ticksSinceLastPathCheck = 0;
        this.lastGoal = null;
        this.breakBlocks = false;
        this.currentBreakingBlock = null;
        this.breakingTicks = 0;
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
    }

    /**
     * Set waypoint reach distance
     */
    public void setWaypointReachDistance(double distance) {
        this.waypointReachDistance = distance;
    }

    /**
     * Set smooth rotation settings
     */
    public void setSmoothRotation(boolean smooth, float speed) {
        this.smoothRotation = smooth;
        this.rotationSpeed = speed;
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

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d waypointVec = Vec3d.ofCenter(currentWaypoint);

        // Check if we reached current waypoint
        double distance = playerPos.distanceTo(waypointVec);
        if (distance < waypointReachDistance) {
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

        // Get look-ahead point for smoother rotation
        Vec3d lookAheadVec = getLookAheadPoint();

        // Handle block breaking if enabled
        if (breakBlocks && isBlockInWay()) {
            handleBlockBreaking();
            return; // Don't move while breaking
        } else {
            stopBreaking();
        }

        // Rotate towards waypoint with look-ahead
        if (smoothRotation) {
            RotationHelper.rotateSmoothly(waypointVec, lookAheadVec, rotationSpeed, rotationSpeed / 2);
        } else {
            RotationHelper.faceTarget(waypointVec);
        }

        // Check if we need to jump
        BlockPos nextWaypoint = currentPath.getNextWaypoint();
        boolean shouldJump = shouldJump(waypoint, nextWaypoint);

        // Set movement keys
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);

        if (shouldJump && ticksSinceLastJump >= MIN_JUMP_COOLDOWN) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
            movementState.setAction(MovementState.Action.JUMPING);
            ticksSinceLastJump = 0; // Reset cooldown
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
     * Determine if player should jump
     */
    private boolean shouldJump(BlockPos current, BlockPos next) {
        if (mc.player == null || next == null) return false;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d currentVec = Vec3d.ofCenter(current);

        // Only jump if on ground
        if (!mc.player.isOnGround()) {
            return false;
        }

        // Jump if next waypoint is higher
        if (next.getY() > current.getY()) {
            // Check if we're close enough to the current waypoint to jump
            double horizontalDist = RotationHelper.getHorizontalDistance(currentVec);
            if (horizontalDist < 2.0) {
                return true;
            }
        }

        // Check for obstacles in front that require jumping
        double horizontalDist = RotationHelper.getHorizontalDistance(currentVec);
        if (horizontalDist < 1.0 && mc.world != null) {
            BlockPos checkPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
            if (!mc.world.getBlockState(checkPos).isAir() &&
                mc.world.getBlockState(checkPos.up()).isAir()) {
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
