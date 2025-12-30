package com.example.addon.movement;

import com.example.addon.pathfinding.Path;
import com.example.addon.utils.pathfinding.CollisionDetector;
import com.example.addon.utils.pathfinding.LineOfSightChecker;
import com.example.addon.utils.pathfinding.WaterPhysics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.function.Consumer;

/**
 * IMPROVED VERSION - Smoother, more Baritone-like rotation
 * Key changes:
 * 1. Removed aggressive calibration stops
 * 2. Continuous rotation during movement
 * 3. Simplified rotation logic
 * 4. Better close-range handling
 * 5. FIXED: Sprint only when moving forward, disabled when idle/strafing/backward
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

    // Collision detection
    private final CollisionDetector collisionDetector;
    private int ticksSinceLastRecalc = 0;
    private static final int MIN_RECALC_INTERVAL = 40;

    // Water state
    private boolean isInWater = false;
    private boolean isSwimming = false;
    private int waterJumpCooldown = 0;
    private static final int WATER_JUMP_COOLDOWN = 15;

    // Gap jumping
    private boolean isPreparingGapJump = false;
    private int gapJumpTicks = 0;
    private static final int GAP_JUMP_SPRINT_TICKS = 5;

    // Block breaking
    private boolean breakBlocks;
    private BlockPos currentBreakingBlock;
    private int breakingTicks;

    // Track if we've reached the goal
    private boolean hasReachedGoal = false;

    // Debug callback
    private Consumer<String> debugCallback = null;

    // NEW: Rotation smoothness settings
    private static final double ROTATION_SMOOTHNESS = 0.3; // Lower = smoother (0.1 - 0.5)
    private static final double MIN_ROTATION_SPEED = 5.0; // Minimum degrees per tick
    private static final double MAX_ROTATION_SPEED = 30.0; // Maximum degrees per tick

    // PHASE 2.2: Look-ahead prediction settings
    private static final int LOOKAHEAD_WAYPOINTS = 3; // How many waypoints to look ahead
    private static final double LOOKAHEAD_DISTANCE = 8.0; // Max distance to look ahead (blocks)
    private boolean useLookAhead = true; // Can be toggled via settings

    public PathExecutor() {
        this.movementState = new MovementState();
        this.collisionDetector = new CollisionDetector();
        this.waypointCheckpointDistance = 0.5;
        this.waypointFinalDistance = 0.5;
        this.smoothRotation = true;
        this.rotationSpeed = 10;
        this.ticksSinceLastJump = 0;
        this.breakBlocks = false;
        this.currentBreakingBlock = null;
        this.breakingTicks = 0;
        this.sprintEnabled = true;
        this.hasReachedGoal = false;
    }

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
        collisionDetector.reset();
        ticksSinceLastRecalc = 0;

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
        collisionDetector.reset();
        hasReachedGoal = false;
        isInWater = false;
        isSwimming = false;
        waterJumpCooldown = 0;
        isPreparingGapJump = false;
        gapJumpTicks = 0;
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

    public void setUseLookAhead(boolean useLookAhead) {
        this.useLookAhead = useLookAhead;
    }

    public boolean isUsingLookAhead() {
        return useLookAhead;
    }

    public void tick() {
        if (mc.player == null || currentPath == null) {
            stopMovement();
            // SAFETY: Ensure sprint is disabled when no path
            if (mc.player != null) {
                mc.player.setSprinting(false);
            }
            return;
        }

        if (hasReachedGoal) {
            stopMovement();
            // SAFETY: Ensure sprint is disabled when goal reached
            mc.player.setSprinting(false);
            return;
        }

        if (currentPath.isComplete()) {
            hasReachedGoal = true;
            stopMovement();
            // SAFETY: Ensure sprint is disabled when complete
            mc.player.setSprinting(false);
            return;
        }

        ticksSinceLastJump++;
        ticksSinceLastRecalc++;
        if (waterJumpCooldown > 0) waterJumpCooldown--;

        if (smoothRotation) {
            RotationHelper.tickHumanLikeRotation();
        }

        // Check water state
        BlockPos playerPos = mc.player.getBlockPos();
        isInWater = WaterPhysics.isWater(mc.world, playerPos);
        isSwimming = isInWater && mc.player.isSwimming();

        // Check for collision/stuck state
        if (ticksSinceLastRecalc >= MIN_RECALC_INTERVAL) {
            BlockPos obstaclePos = collisionDetector.checkIfStuck();
            if (obstaclePos != null) {
                debug("Player stuck at obstacle! Recalculating path...");
                recalculatePathAvoidingObstacle(obstaclePos);
                return;
            }
        }

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) {
            hasReachedGoal = true;
            stopMovement();
            return;
        }

        // PHASE 2.2: Get optimal aim target using look-ahead prediction
        Vec3d aimTargetVec;
        BlockPos aimTarget;

        if (useLookAhead) {
            // Use weighted look-ahead for smoother curved paths
            aimTargetVec = getWeightedLookAheadTarget();
            // For waypoint completion checks, still use actual waypoint
            aimTarget = currentWaypoint;

            if (debugCallback != null && currentPath.getCurrentWaypointIndex() % 5 == 0) {
                debug("Using look-ahead prediction for smoother curves");
            }
        } else {
            // Use line of sight to find visible waypoint (old method)
            List<BlockPos> waypoints = currentPath.getWaypoints();
            int visibleWaypointIndex = LineOfSightChecker.findFurthestVisibleWaypoint(
                waypoints,
                currentPath.getCurrentWaypointIndex(),
                8
            );

            aimTarget = currentWaypoint;
            if (visibleWaypointIndex > currentPath.getCurrentWaypointIndex() &&
                visibleWaypointIndex < waypoints.size()) {
                aimTarget = waypoints.get(visibleWaypointIndex);
            }

            aimTargetVec = new Vec3d(
                aimTarget.getX() + 0.5,
                aimTarget.getY() + 0.5,
                aimTarget.getZ() + 0.5
            );
        }

        Vec3d playerPosVec = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        // Check distance to CURRENT waypoint (for completion check)
        Vec3d waypointVec = new Vec3d(
            currentWaypoint.getX() + 0.5,
            currentWaypoint.getY(),
            currentWaypoint.getZ() + 0.5
        );

        boolean isFinalWaypoint = currentPath.getNextWaypoint() == null;
        double distanceThreshold = isFinalWaypoint ? waypointFinalDistance :
            Math.max(waypointCheckpointDistance * 1.5, 1.0);

        // Calculate distance to CURRENT waypoint
        double dx = Math.abs(playerPosVec.x - waypointVec.x);
        double dy = Math.abs(playerPosVec.y - waypointVec.y);
        double dz = Math.abs(playerPosVec.z - waypointVec.z);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        boolean isWithinBounds = true;
        if (isFinalWaypoint) {
            double axisBound = distanceThreshold;
            isWithinBounds = (dx <= axisBound) && (dy <= axisBound) && (dz <= axisBound);
        }

        // Aim at next waypoint if very close to current to prevent looking down
        double distanceToCurrentWaypoint = Math.sqrt(
            Math.pow(playerPosVec.x - waypointVec.x, 2) +
                Math.pow(playerPosVec.y - waypointVec.y, 2) +
                Math.pow(playerPosVec.z - waypointVec.z, 2)
        );

        if (distanceToCurrentWaypoint < waypointCheckpointDistance && !isFinalWaypoint) {
            BlockPos nextWp = currentPath.getNextWaypoint();
            if (nextWp != null && !useLookAhead) {
                // Only override if not using look-ahead (look-ahead handles this better)
                aimTargetVec = new Vec3d(
                    nextWp.getX() + 0.5,
                    nextWp.getY() + 0.5,
                    nextWp.getZ() + 0.5
                );
            }
        }

        // Advance waypoint when player reaches it
        if (distance < distanceThreshold && isWithinBounds) {
            if (isFinalWaypoint) {
                debug("✓ REACHED final waypoint!");
            } else {
                debug("✓ Reached waypoint " + currentPath.getCurrentWaypointIndex());
            }

            currentPath.advanceWaypoint();

            if (currentPath.isComplete()) {
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

            // Recalculate aim target for new waypoint
            if (useLookAhead) {
                aimTargetVec = getWeightedLookAheadTarget();
            } else {
                aimTargetVec = new Vec3d(
                    currentWaypoint.getX() + 0.5,
                    currentWaypoint.getY() + 0.5,
                    currentWaypoint.getZ() + 0.5
                );
            }
        }

        // Execute movement toward aim target
        executeMovement(currentWaypoint, aimTargetVec);
        movementState.tick();
    }

    /**
     * PHASE 2.2: Look-ahead prediction for smoother curved paths
     */
    private Vec3d getWeightedLookAheadTarget() {
        if (mc.player == null || currentPath == null) {
            return Vec3d.ZERO;
        }

        List<BlockPos> waypoints = currentPath.getWaypoints();
        int currentIndex = currentPath.getCurrentWaypointIndex();

        if (currentIndex >= waypoints.size()) {
            return Vec3d.ZERO;
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d weightedSum = Vec3d.ZERO;
        double totalWeight = 0.0;

        BlockPos currentWaypoint = waypoints.get(currentIndex);
        Vec3d currentWaypointVec = Vec3d.ofCenter(currentWaypoint);
        double distToCurrent = playerPos.distanceTo(currentWaypointVec);

        int waypointsToCheck = Math.min(LOOKAHEAD_WAYPOINTS, waypoints.size() - currentIndex);

        for (int i = 0; i < waypointsToCheck; i++) {
            int waypointIndex = currentIndex + i;
            if (waypointIndex >= waypoints.size()) break;

            BlockPos waypoint = waypoints.get(waypointIndex);
            Vec3d waypointVec = new Vec3d(
                waypoint.getX() + 0.5,
                waypoint.getY() + 0.5,
                waypoint.getZ() + 0.5
            );

            double distance = playerPos.distanceTo(waypointVec);

            if (distance > LOOKAHEAD_DISTANCE) {
                break;
            }

            double weight;
            if (i == 0) {
                weight = 3.0 / (distance + 1.0);
            } else if (i == 1) {
                weight = 2.0 / (distance + 1.0);
            } else {
                weight = 1.0 / (distance + 1.0);
            }

            if (i > 0 && distToCurrent < 2.0) {
                weight *= (2.0 - distToCurrent);
            }

            weightedSum = weightedSum.add(waypointVec.multiply(weight));
            totalWeight += weight;
        }

        if (totalWeight > 0) {
            Vec3d result = weightedSum.multiply(1.0 / totalWeight);

            double verticalDiff = Math.abs(result.y - currentWaypointVec.y);
            if (verticalDiff > 3.0) {
                result = new Vec3d(
                    result.x,
                    currentWaypointVec.y + Math.signum(result.y - currentWaypointVec.y) * 3.0,
                    result.z
                );
            }

            return result;
        }

        return currentWaypointVec;
    }

    /**
     * IMPROVED: Smoother movement execution with continuous rotation
     * FIXED: Sprint only when moving forward
     */
    private void executeMovement(BlockPos waypoint, Vec3d waypointVec) {
        if (mc.player == null) return;

        movementState.setTargetPosition(waypoint);

        if (isInWater) {
            handleWaterMovement(waypoint, waypointVec);
            return;
        }

        if (breakBlocks && isBlockInWay()) {
            handleBlockBreaking();
            return;
        } else {
            stopBreaking();
        }

        BlockPos nextWaypoint = currentPath.getNextWaypoint();
        if (shouldPrepareGapJump(waypoint, nextWaypoint)) {
            handleGapJump(waypoint);
            return;
        } else {
            // Reset gap jump state if we're not preparing anymore
            if (isPreparingGapJump) {
                isPreparingGapJump = false;
                gapJumpTicks = 0;
                mc.player.setSprinting(false);
            }
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d directionToTarget = waypointVec.subtract(playerPos).normalize();

        double targetYaw = Math.toDegrees(Math.atan2(-directionToTarget.x, directionToTarget.z));
        float playerYaw = mc.player.getYaw();

        double yawDiff = targetYaw - playerYaw;
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        double absYawDiff = Math.abs(yawDiff);

        if (smoothRotation) {
            RotationHelper.rotateTowardsHumanLike(waypointVec, rotationSpeed);
        } else {
            float targetPitch = (float) Math.toDegrees(-Math.asin(directionToTarget.y));
            mc.player.setYaw((float) targetYaw);
            mc.player.setPitch(targetPitch);
        }

        // IMPROVED: Determine movement strategy based on angle
        // Release all movement keys first
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);

        if (absYawDiff < 45) {
            // Nearly aligned - move forward ONLY
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);

            // Sprint ONLY when: enabled, on ground, moving nearly straight forward
            if (sprintEnabled && mc.player.isOnGround() && absYawDiff < 20) {
                mc.player.setSprinting(true);
            } else {
                mc.player.setSprinting(false);
            }
        } else if (absYawDiff < 135) {
            // Medium angle - use diagonal strafing while rotating
            // NO sprinting when strafing
            mc.player.setSprinting(false);

            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
            if (yawDiff > 0) {
                KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), true);
            } else {
                KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), true);
            }
        } else {
            // Target is behind - walk backward while rotating
            // NO sprinting when walking backward
            mc.player.setSprinting(false);
            KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), true);
        }

        boolean shouldJump = shouldJump(waypoint, nextWaypoint);
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
    }

    private void handleWaterMovement(BlockPos waypoint, Vec3d waypointVec) {
        if (mc.player == null || mc.world == null) return;

        if (smoothRotation) {
            RotationHelper.rotateTowardsHumanLike(waypointVec, rotationSpeed);
        } else {
            RotationHelper.faceTarget(waypointVec);
        }

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);

        BlockPos playerBlockPos = mc.player.getBlockPos();

        boolean shouldJumpFromWater = WaterPhysics.shouldJumpInWater(mc.world, playerBlockPos, waypoint);

        if (shouldJumpFromWater && waterJumpCooldown == 0 && ticksSinceLastJump >= MIN_JUMP_COOLDOWN) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
            waterJumpCooldown = WATER_JUMP_COOLDOWN;
            ticksSinceLastJump = 0;
            debug("Jumping out of water");
        } else if (waypoint.getY() > playerBlockPos.getY()) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
        } else if (waypoint.getY() < playerBlockPos.getY() && WaterPhysics.requiresDiving(mc.world, playerBlockPos)) {
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), true);
        } else {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);
        }

        movementState.setAction(MovementState.Action.WALKING);
    }

    private boolean shouldPrepareGapJump(BlockPos current, BlockPos next) {
        if (mc.player == null || mc.world == null || next == null) return false;
        if (!mc.player.isOnGround()) return false;

        // Don't trigger if already preparing
        if (isPreparingGapJump) return true;

        // Check if the next waypoint requires a horizontal jump of 2+ blocks
        int horizontalDist = Math.max(
            Math.abs(next.getX() - current.getX()),
            Math.abs(next.getZ() - current.getZ())
        );

        if (horizontalDist < 2) return false;

        // Check if there's actually a gap in front of the player
        BlockPos frontPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        boolean hasGap = mc.world.getBlockState(frontPos.down()).isAir() ||
            !mc.world.getBlockState(frontPos.down()).isSolidBlock(mc.world, frontPos.down());

        if (!hasGap) return false;

        // Only prepare if we're close to the current waypoint (about to need the jump)
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d currentPos = Vec3d.ofCenter(current);
        double distToCurrent = Math.sqrt(
            Math.pow(playerPos.x - currentPos.x, 2) +
                Math.pow(playerPos.z - currentPos.z, 2)
        );

        // Must be within 2 blocks of the edge waypoint to prepare jump
        return distToCurrent < 2.0;
    }

    private void handleGapJump(BlockPos edgePos) {
        if (!isPreparingGapJump) {
            isPreparingGapJump = true;
            gapJumpTicks = 0;
            debug("Preparing gap jump!");
        }

        Vec3d edgeVec = Vec3d.ofCenter(edgePos);

        if (smoothRotation) {
            RotationHelper.rotateTowardsHumanLike(edgeVec, rotationSpeed);
        } else {
            RotationHelper.faceTarget(edgeVec);
        }

        // Only sprint during the preparation phase
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);

        gapJumpTicks++;

        if (gapJumpTicks >= GAP_JUMP_SPRINT_TICKS || isAtEdge(edgePos)) {
            // Execute the jump
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
            mc.player.setSprinting(true); // Sprint only at jump moment
            ticksSinceLastJump = 0;
            isPreparingGapJump = false;
            gapJumpTicks = 0;
            debug("Gap jump executed!");
        } else {
            // During preparation, sprint to build momentum
            mc.player.setSprinting(sprintEnabled && mc.player.isOnGround());
        }
    }

    private boolean isAtEdge(BlockPos edgePos) {
        if (mc.player == null) return false;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d edgeVec = Vec3d.ofCenter(edgePos);

        double dx = playerPos.x - edgeVec.x;
        double dz = playerPos.z - edgeVec.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        return horizontalDist < 0.5;
    }

    private void recalculatePathAvoidingObstacle(BlockPos obstacle) {
        if (mc.player == null || currentPath == null) return;

        BlockPos goal = currentPath.getGoal();
        if (goal == null) return;

        debug("Player stuck at obstacle! Recalculating path...");

        com.example.addon.pathfinding.Path newPath =
            com.example.addon.pathfinding.Pathfinder.findPath(mc.player.getBlockPos(), goal);

        if (newPath != null) {
            debug("New path found with " + newPath.getLength() + " waypoints");
            setPath(newPath);
            ticksSinceLastRecalc = 0;
        } else {
            debug("No alternative path found - clearing path");
            clearPath();
        }
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
        collisionDetector.reset();
        ticksSinceLastJump = 0;
        isPreparingGapJump = false;
        gapJumpTicks = 0;
    }

    public boolean isExecuting() {
        return currentPath != null && !currentPath.isComplete() && !hasReachedGoal;
    }

    public MovementState getMovementState() {
        return movementState;
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
