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
 * Executes a calculated path with water physics, collision detection, gap jumping, and line-of-sight targeting
 * FIXED VERSION - Compatible with Minecraft 1.21.10
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

    // Calibration state
    private boolean isCalibrating = false;
    private int calibrationTicks = 0;
    private static final int MAX_CALIBRATION_TICKS = 40; // 2 seconds max
    private static final double CALIBRATION_ANGLE_THRESHOLD = 10.0; // 10 degrees tolerance

    // Track if we've reached the goal
    private boolean hasReachedGoal = false;

    // Debug callback
    private Consumer<String> debugCallback = null;

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
        isCalibrating = false;
        calibrationTicks = 0;
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

        // Use line of sight to find visible waypoint for AIMING only
        // We don't advance waypoints until player physically reaches them
        List<BlockPos> waypoints = currentPath.getWaypoints();
        int visibleWaypointIndex = LineOfSightChecker.findFurthestVisibleWaypoint(
            waypoints,
            currentPath.getCurrentWaypointIndex(),
            8
        );

        // Get the visible waypoint for aiming, but keep currentWaypoint for distance checking
        BlockPos aimTarget = currentWaypoint;
        if (visibleWaypointIndex > currentPath.getCurrentWaypointIndex() &&
            visibleWaypointIndex < waypoints.size()) {
            aimTarget = waypoints.get(visibleWaypointIndex);
            if (visibleWaypointIndex != currentPath.getCurrentWaypointIndex()) {
                debug("Aiming at visible waypoint " + visibleWaypointIndex + " while moving to waypoint " + currentPath.getCurrentWaypointIndex());
            }
        }

        if (currentWaypoint == null) {
            hasReachedGoal = true;
            stopMovement();
            return;
        }

        Vec3d playerPosVec = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        // Check distance to CURRENT waypoint (the one we need to reach)
        Vec3d waypointVec = new Vec3d(
            currentWaypoint.getX() + 0.5,
            currentWaypoint.getY(),
            currentWaypoint.getZ() + 0.5
        );

        // Get aim target vector (may be different from waypoint for line of sight)
        // Aim slightly higher (+0.5 Y) for smoother movement
        Vec3d aimTargetVec = new Vec3d(
            aimTarget.getX() + 0.5,
            aimTarget.getY() + 0.5,
            aimTarget.getZ() + 0.5
        );

        boolean isFinalWaypoint = currentPath.getNextWaypoint() == null;

        double distanceThreshold = isFinalWaypoint ? waypointFinalDistance :
            Math.max(waypointCheckpointDistance * 1.5, 1.0);

        // Calculate distance to CURRENT waypoint (for completion check)
        double dx = Math.abs(playerPosVec.x - waypointVec.x);
        double dy = Math.abs(playerPosVec.y - waypointVec.y);
        double dz = Math.abs(playerPosVec.z - waypointVec.z);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        boolean isWithinBounds = true;
        if (isFinalWaypoint) {
            double axisBound = distanceThreshold;
            isWithinBounds = (dx <= axisBound) && (dy <= axisBound) && (dz <= axisBound);
        }

        // Only advance waypoint when player PHYSICALLY reaches it
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

            // Update vectors for new waypoint
            waypointVec = new Vec3d(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5
            );

            // Update aim target to new current waypoint (or visible one)
            aimTarget = currentWaypoint;
            visibleWaypointIndex = LineOfSightChecker.findFurthestVisibleWaypoint(
                waypoints,
                currentPath.getCurrentWaypointIndex(),
                8
            );
            if (visibleWaypointIndex > currentPath.getCurrentWaypointIndex() &&
                visibleWaypointIndex < waypoints.size()) {
                aimTarget = waypoints.get(visibleWaypointIndex);
            }

            // Aim slightly higher (+0.5 Y) for smoother movement
            aimTargetVec = new Vec3d(
                aimTarget.getX() + 0.5,
                aimTarget.getY() + 0.5,
                aimTarget.getZ() + 0.5
            );
        }

        // Check if player is already very close to current waypoint (within checkpoint distance)
        // If so, don't aim at it to prevent looking down and getting stuck
        double distanceToCurrentWaypoint = Math.sqrt(
            Math.pow(playerPosVec.x - waypointVec.x, 2) +
                Math.pow(playerPosVec.y - waypointVec.y, 2) +
                Math.pow(playerPosVec.z - waypointVec.z, 2)
        );

        // If we're within checkpoint distance of current waypoint, skip aiming at it
        // and look at the next waypoint instead to avoid downward aim issues
        if (distanceToCurrentWaypoint < waypointCheckpointDistance && !isFinalWaypoint) {
            BlockPos nextWp = currentPath.getNextWaypoint();
            if (nextWp != null) {
                // Aim at next waypoint instead
                aimTargetVec = new Vec3d(
                    nextWp.getX() + 0.5,
                    nextWp.getY() + 0.5,
                    nextWp.getZ() + 0.5
                );
                debug("Very close to waypoint, aiming at next one to avoid looking down");
            }
        }

        // Execute movement toward aim target (visible waypoint)
        executeMovement(currentWaypoint, aimTargetVec);
        movementState.tick();
    }

    private void executeMovement(BlockPos waypoint, Vec3d waypointVec) {
        if (mc.player == null) return;

        movementState.setTargetPosition(waypoint);

        Vec3d targetVec = waypointVec;

        // Handle water movement
        if (isInWater) {
            handleWaterMovement(waypoint, waypointVec);
            return;
        }

        // Handle block breaking
        if (breakBlocks && isBlockInWay()) {
            handleBlockBreaking();
            return;
        } else {
            stopBreaking();
        }

        // Check for gaps and prepare sprint jump
        BlockPos nextWaypoint = currentPath.getNextWaypoint();
        if (shouldPrepareGapJump(waypoint, nextWaypoint)) {
            handleGapJump(waypoint);
            return;
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d directionToTarget = targetVec.subtract(playerPos).normalize();

        // Calculate angle to target
        double targetYaw = Math.toDegrees(Math.atan2(-directionToTarget.x, directionToTarget.z));
        float playerYaw = mc.player.getYaw();

        // Normalize angle difference to [-180, 180]
        double yawDiff = targetYaw - playerYaw;
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        // Check if we need to calibrate (large angle difference)
        boolean needsCalibration = Math.abs(yawDiff) > 60; // More than 60 degrees off

        if (needsCalibration && !isCalibrating) {
            // Start calibration mode
            isCalibrating = true;
            calibrationTicks = 0;
            debug("Starting calibration - angle off by " + String.format("%.1f", Math.abs(yawDiff)) + " degrees");
        }

        // Handle calibration mode - ONLY rotate, don't move
        if (isCalibrating) {
            calibrationTicks++;

            // Stop all movement
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
            mc.player.setSprinting(false);

            // Only rotate
            if (smoothRotation) {
                RotationHelper.rotateTowardsHumanLike(targetVec, rotationSpeed);
            } else {
                RotationHelper.faceTarget(targetVec);
            }

            // Check if calibration is complete
            if (Math.abs(yawDiff) < CALIBRATION_ANGLE_THRESHOLD) {
                debug("Calibration complete - angle now " + String.format("%.1f", Math.abs(yawDiff)) + " degrees");
                isCalibrating = false;
                calibrationTicks = 0;
            } else if (calibrationTicks >= MAX_CALIBRATION_TICKS) {
                debug("Calibration timeout - proceeding anyway");
                isCalibrating = false;
                calibrationTicks = 0;
            }

            // Don't execute movement while calibrating
            return;
        }

        // Normal movement (when not calibrating)
        boolean shouldRotate = Math.abs(yawDiff) > 15;
        boolean shouldStrafe = Math.abs(yawDiff) > 45 && Math.abs(yawDiff) < 135;
        boolean shouldMoveBackward = Math.abs(yawDiff) > 135;

        if (shouldRotate && !shouldStrafe && !shouldMoveBackward) {
            // Small angle difference - rotate and move forward
            if (smoothRotation) {
                RotationHelper.rotateTowardsHumanLike(targetVec, rotationSpeed);
            } else {
                RotationHelper.faceTarget(targetVec);
            }

            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
            KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
        } else if (shouldStrafe) {
            // Medium angle - strafe while rotating slowly
            if (smoothRotation) {
                RotationHelper.rotateTowardsHumanLike(targetVec, rotationSpeed * 2);
            } else {
                RotationHelper.faceTarget(targetVec);
            }

            if (yawDiff > 0) {
                KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
                KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), true);
                KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
                KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), false);
            } else {
                KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
                KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), true);
                KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
                KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), false);
            }
        } else if (shouldMoveBackward) {
            // Target is behind - walk backward while rotating
            if (smoothRotation) {
                RotationHelper.rotateTowardsHumanLike(targetVec, rotationSpeed);
            } else {
                RotationHelper.faceTarget(targetVec);
            }

            KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), true);
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
        } else {
            // Very close to target angle - just move forward
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
            KeyBinding.setKeyPressed(mc.options.backKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
            KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
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

        // Only sprint when moving forward in straight line
        if (sprintEnabled && mc.player.isOnGround() && !shouldJump &&
            !shouldStrafe && !shouldMoveBackward && Math.abs(yawDiff) < 30) {
            mc.player.setSprinting(true);
        } else {
            mc.player.setSprinting(false);
        }
    }

    private void handleWaterMovement(BlockPos waypoint, Vec3d waypointVec) {
        if (mc.player == null || mc.world == null) return;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d directionToTarget = waypointVec.subtract(playerPos).normalize();

        // Rotate towards target
        if (smoothRotation) {
            RotationHelper.rotateTowardsHumanLike(waypointVec, rotationSpeed);
        } else {
            RotationHelper.faceTarget(waypointVec);
        }

        // Move forward in water
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);

        BlockPos playerBlockPos = mc.player.getBlockPos();

        // Check if we should jump out of water
        boolean shouldJumpFromWater = WaterPhysics.shouldJumpInWater(mc.world, playerBlockPos, waypoint);

        if (shouldJumpFromWater && waterJumpCooldown == 0 && ticksSinceLastJump >= MIN_JUMP_COOLDOWN) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
            waterJumpCooldown = WATER_JUMP_COOLDOWN;
            ticksSinceLastJump = 0;
            debug("Jumping out of water");
        } else if (waypoint.getY() > playerBlockPos.getY()) {
            // Swim upward by holding jump
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
        } else if (waypoint.getY() < playerBlockPos.getY() && WaterPhysics.requiresDiving(mc.world, playerBlockPos)) {
            // Dive by holding sneak
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

        // Calculate horizontal gap distance
        int horizontalDist = Math.max(
            Math.abs(next.getX() - current.getX()),
            Math.abs(next.getZ() - current.getZ())
        );

        // Only prepare for gaps 2+ blocks away horizontally
        if (horizontalDist < 2) return false;

        // Check if there's actually a gap (air/fall)
        BlockPos between = current.offset(mc.player.getHorizontalFacing());

        boolean hasGap = mc.world.getBlockState(between.down()).isAir() ||
            !mc.world.getBlockState(between.down()).isSolidBlock(mc.world, between.down());

        if (!hasGap) return false;

        // Check distance to edge - use manual calculation
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d edgePos = Vec3d.ofCenter(current);
        double dx = playerPos.x - edgePos.x;
        double dz = playerPos.z - edgePos.z;
        double distToEdge = Math.sqrt(dx * dx + dz * dz);

        return distToEdge < 3.0;
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

        // Sprint towards edge
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
        mc.player.setSprinting(true);

        gapJumpTicks++;

        // Jump at the edge
        if (gapJumpTicks >= GAP_JUMP_SPRINT_TICKS || isAtEdge(edgePos)) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);
            ticksSinceLastJump = 0;
            isPreparingGapJump = false;
            gapJumpTicks = 0;
            debug("Gap jump executed!");
        }
    }

    private boolean isAtEdge(BlockPos edgePos) {
        if (mc.player == null) return false;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d edgeVec = Vec3d.ofCenter(edgePos);

        // Manual horizontal distance calculation
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

    private boolean isMovingForward() {
        if (mc.player == null || mc.world == null) return false;

        Vec3d velocity = mc.player.getVelocity();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        return horizontalSpeed >= 0.05;
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
        isCalibrating = false;
        calibrationTicks = 0;
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
