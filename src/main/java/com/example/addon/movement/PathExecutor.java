package com.example.addon.movement;

import com.example.addon.pathfinding.Path;
import com.example.addon.utils.pathfinding.CollisionDetector;
import com.example.addon.utils.pathfinding.WaterPhysics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.function.Consumer;

/**
 * Optimized PathExecutor - ~50% smaller while maintaining all functionality
 */
public class PathExecutor {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // Core state
    private Path currentPath;
    private final MovementState movementState = new MovementState();
    private final CollisionDetector collisionDetector = new CollisionDetector();

    // Settings
    private double waypointCheckpointDistance = 0.5;
    private double waypointFinalDistance = 0.5;
    private boolean smoothRotation = true;
    private int rotationSpeed = 10;
    private boolean breakBlocks = false;
    private boolean sprintEnabled = true;
    private boolean useLookAhead = true;
    private int nodeInterval = 1;
    private int maxFallDistance = 3;

    // Timers & cooldowns
    private int ticksSinceLastJump = 0;
    private int ticksSinceLastRecalc = 0;
    private int waterJumpCooldown = 0;
    private int gapJumpTicks = 0;
    private BlockPos currentBreakingBlock = null;
    private int breakingTicks = 0;

    // State flags
    private boolean hasReachedGoal = false;
    private boolean isInWater = false;
    private boolean isPreparingGapJump = false;

    // Constants
    private static final int MIN_JUMP_COOLDOWN = 10;
    private static final int MIN_RECALC_INTERVAL = 40;
    private static final int WATER_JUMP_COOLDOWN = 15;
    private static final int GAP_JUMP_SPRINT_TICKS = 5;
    private static final int LOOKAHEAD_WAYPOINTS = 3;
    private static final double LOOKAHEAD_DISTANCE = 8.0;

    private Consumer<String> debugCallback = null;

    // ==================== PUBLIC API ====================

    public void setPath(Path path) {
        hasReachedGoal = false;
        collisionDetector.reset();
        ticksSinceLastRecalc = 0;

        if (path != null && nodeInterval > 1) {
            path = simplifyPath(path);
        }

        this.currentPath = path;
        if (path != null) path.reset();
    }

    public void clearPath() {
        this.currentPath = null;
        stopMovement();
        stopBreaking();
        movementState.reset();
        collisionDetector.reset();
        hasReachedGoal = false;
        isInWater = false;
        waterJumpCooldown = 0;
        isPreparingGapJump = false;
        gapJumpTicks = 0;
        RotationHelper.cancelHumanLikeRotation();
    }

    public void tick() {
        if (mc.player == null || currentPath == null || hasReachedGoal || currentPath.isComplete()) {
            if (currentPath != null && currentPath.isComplete()) hasReachedGoal = true;
            stopMovement();
            return;
        }

        // Update timers
        ticksSinceLastJump++;
        ticksSinceLastRecalc++;
        if (waterJumpCooldown > 0) waterJumpCooldown--;

        if (smoothRotation) RotationHelper.tickHumanLikeRotation();

        // Check environment
        BlockPos playerPos = mc.player.getBlockPos();
        isInWater = WaterPhysics.isWater(mc.world, playerPos);

        // Check for stuck state
        if (ticksSinceLastRecalc >= MIN_RECALC_INTERVAL) {
            BlockPos obstacle = collisionDetector.checkIfStuck();
            if (obstacle != null) {
                debug("Player stuck! Recalculating path...");
                recalculatePath();
                return;
            }
        }

        BlockPos currentWaypoint = currentPath.getCurrentWaypoint();
        if (currentWaypoint == null) {
            hasReachedGoal = true;
            stopMovement();
            return;
        }

        // Get aim target
        Vec3d aimTarget = useLookAhead ? getWeightedLookAheadTarget() : Vec3d.ofCenter(currentWaypoint);
        Vec3d playerPosVec = mc.player.getPos();
        Vec3d waypointVec = Vec3d.ofCenter(currentWaypoint);

        // Check waypoint completion
        boolean isFinal = currentPath.getNextWaypoint() == null;
        double threshold = isFinal ? waypointFinalDistance : Math.max(waypointCheckpointDistance * 1.5, 1.0);
        double distance = playerPosVec.distanceTo(waypointVec);

        if (distance < threshold) {
            debug(isFinal ? "✓ REACHED final waypoint!" : "✓ Reached waypoint " + currentPath.getCurrentWaypointIndex());
            currentPath.advanceWaypoint();
            if (currentPath.isComplete()) {
                hasReachedGoal = true;
                stopMovement();
                return;
            }
        }

        // Execute movement
        if (isInWater) {
            handleWaterMovement(currentWaypoint, aimTarget);
        } else if (breakBlocks && isBlockInWay()) {
            handleBlockBreaking();
        } else {
            stopBreaking();
            if (shouldPrepareGapJump(currentWaypoint, currentPath.getNextWaypoint())) {
                handleGapJump(currentWaypoint);
            } else {
                executeMovement(currentWaypoint, aimTarget);
            }
        }

        movementState.tick();
    }

    public boolean isExecuting() {
        return currentPath != null && !currentPath.isComplete() && !hasReachedGoal;
    }

    // ==================== SETTERS ====================

    public void setDebugCallback(Consumer<String> callback) { this.debugCallback = callback; }
    public void setWaypointCheckpointDistance(double d) { this.waypointCheckpointDistance = d; }
    public void setWaypointFinalDistance(double d) { this.waypointFinalDistance = d; }
    public void setSmoothRotation(boolean smooth, int speed) { this.smoothRotation = smooth; this.rotationSpeed = Math.max(1, speed); }
    public void setBreakBlocks(boolean b) { this.breakBlocks = b; }
    public void setSprint(boolean b) { this.sprintEnabled = b; }
    public void setNodeInterval(int i) { this.nodeInterval = Math.max(1, i); }
    public void setMaxFallDistance(int d) { this.maxFallDistance = Math.max(0, d); }
    public void setUseLookAhead(boolean b) { this.useLookAhead = b; }

    // ==================== GETTERS ====================

    public Path getPath() { return currentPath; }
    public MovementState getMovementState() { return movementState; }
    public boolean isUsingLookAhead() { return useLookAhead; }

    // ==================== MOVEMENT LOGIC ====================

    private void executeMovement(BlockPos waypoint, Vec3d aimTarget) {
        movementState.setTargetPosition(waypoint);

        Vec3d playerPos = mc.player.getPos();
        Vec3d direction = aimTarget.subtract(playerPos).normalize();

        double targetYaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float yawDiff = normalizeAngle((float)(targetYaw - mc.player.getYaw()));
        double absYawDiff = Math.abs(yawDiff);

        // Rotate
        if (smoothRotation) {
            RotationHelper.rotateTowardsHumanLike(aimTarget, rotationSpeed);
        } else {
            float targetPitch = (float) Math.toDegrees(-Math.asin(direction.y));
            mc.player.setYaw((float) targetYaw);
            mc.player.setPitch(targetPitch);
        }

        // Move based on angle
        releaseAllMovementKeys();

        if (absYawDiff < 45) {
            // Forward
            mc.options.forwardKey.setPressed(true);
            if (sprintEnabled && mc.player.isOnGround() && absYawDiff < 20) {
                mc.player.setSprinting(true);
            }
        } else if (absYawDiff < 135) {
            // Strafe
            mc.player.setSprinting(false);
            mc.options.forwardKey.setPressed(true);
            if (yawDiff > 0) mc.options.leftKey.setPressed(true);
            else mc.options.rightKey.setPressed(true);
        } else {
            // Backward
            mc.player.setSprinting(false);
            mc.options.backKey.setPressed(true);
        }

        // Jump
        boolean shouldJump = shouldJump(waypoint, currentPath.getNextWaypoint());
        if (shouldJump && ticksSinceLastJump >= MIN_JUMP_COOLDOWN) {
            mc.options.jumpKey.setPressed(true);
            movementState.setAction(MovementState.Action.JUMPING);
            ticksSinceLastJump = 0;
        } else {
            mc.options.jumpKey.setPressed(false);
            movementState.setAction(mc.player.isOnGround() ? MovementState.Action.WALKING : MovementState.Action.FALLING);
        }
    }

    private void handleWaterMovement(BlockPos waypoint, Vec3d aimTarget) {
        if (smoothRotation) RotationHelper.rotateTowardsHumanLike(aimTarget, rotationSpeed);
        else RotationHelper.faceTarget(aimTarget);

        mc.options.forwardKey.setPressed(true);

        BlockPos playerPos = mc.player.getBlockPos();
        boolean shouldJumpFromWater = WaterPhysics.shouldJumpInWater(mc.world, playerPos, waypoint);

        if (shouldJumpFromWater && waterJumpCooldown == 0 && ticksSinceLastJump >= MIN_JUMP_COOLDOWN) {
            mc.options.jumpKey.setPressed(true);
            waterJumpCooldown = WATER_JUMP_COOLDOWN;
            ticksSinceLastJump = 0;
        } else if (waypoint.getY() > playerPos.getY()) {
            mc.options.jumpKey.setPressed(true);
        } else if (waypoint.getY() < playerPos.getY() && WaterPhysics.requiresDiving(mc.world, playerPos)) {
            mc.options.sneakKey.setPressed(true);
        }

        movementState.setAction(MovementState.Action.WALKING);
    }

    private void handleGapJump(BlockPos edgePos) {
        if (!isPreparingGapJump) {
            isPreparingGapJump = true;
            gapJumpTicks = 0;
            debug("Preparing gap jump!");
        }

        Vec3d edgeVec = Vec3d.ofCenter(edgePos);
        if (smoothRotation) RotationHelper.rotateTowardsHumanLike(edgeVec, rotationSpeed);
        else RotationHelper.faceTarget(edgeVec);

        mc.options.forwardKey.setPressed(true);
        mc.player.setSprinting(true);

        gapJumpTicks++;
        if (gapJumpTicks >= GAP_JUMP_SPRINT_TICKS || isAtEdge(edgePos)) {
            mc.options.jumpKey.setPressed(true);
            ticksSinceLastJump = 0;
            isPreparingGapJump = false;
            gapJumpTicks = 0;
            debug("Gap jump executed!");
        }
    }

    private void handleBlockBreaking() {
        if (mc.interactionManager == null) return;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos frontPos = playerPos.offset(mc.player.getHorizontalFacing());
        BlockPos frontUpPos = frontPos.up();

        BlockPos targetBlock = !mc.world.getBlockState(frontUpPos).isAir() ? frontUpPos :
            !mc.world.getBlockState(frontPos).isAir() ? frontPos : null;

        if (targetBlock == null || mc.world.getBlockState(targetBlock).getHardness(mc.world, targetBlock) < 0) {
            stopBreaking();
            return;
        }

        if (currentBreakingBlock == null || !currentBreakingBlock.equals(targetBlock)) {
            currentBreakingBlock = targetBlock;
            breakingTicks = 0;
        }

        RotationHelper.faceTarget(Vec3d.ofCenter(targetBlock));
        mc.interactionManager.updateBlockBreakingProgress(targetBlock, net.minecraft.util.math.Direction.UP);
        mc.options.attackKey.setPressed(true);
        breakingTicks++;

        if (mc.world.getBlockState(targetBlock).isAir()) {
            stopBreaking();
        }
    }

    // ==================== HELPER METHODS ====================

    private Vec3d getWeightedLookAheadTarget() {
        List<BlockPos> waypoints = currentPath.getWaypoints();
        int currentIndex = currentPath.getCurrentWaypointIndex();
        if (currentIndex >= waypoints.size()) return Vec3d.ZERO;

        Vec3d playerPos = mc.player.getPos();
        Vec3d weightedSum = Vec3d.ZERO;
        double totalWeight = 0.0;

        BlockPos currentWaypoint = waypoints.get(currentIndex);
        Vec3d currentWaypointVec = Vec3d.ofCenter(currentWaypoint);
        double distToCurrent = playerPos.distanceTo(currentWaypointVec);

        int lookAheadLimit = Math.min(currentIndex + LOOKAHEAD_WAYPOINTS, waypoints.size());

        for (int i = currentIndex; i < lookAheadLimit; i++) {
            Vec3d waypointVec = Vec3d.ofCenter(waypoints.get(i));
            double distance = playerPos.distanceTo(waypointVec);

            if (distance > LOOKAHEAD_DISTANCE) break;

            double weight = (i == currentIndex ? 3.0 : i == currentIndex + 1 ? 2.0 : 1.0) / (distance + 1.0);
            if (i > currentIndex && distToCurrent < 2.0) weight *= (2.0 - distToCurrent);

            weightedSum = weightedSum.add(waypointVec.multiply(weight));
            totalWeight += weight;
        }

        if (totalWeight > 0) {
            Vec3d result = weightedSum.multiply(1.0 / totalWeight);
            double verticalDiff = Math.abs(result.y - currentWaypointVec.y);
            if (verticalDiff > 3.0) {
                result = new Vec3d(result.x,
                    currentWaypointVec.y + Math.signum(result.y - currentWaypointVec.y) * 3.0,
                    result.z);
            }
            return result;
        }

        return currentWaypointVec;
    }

    private boolean shouldJump(BlockPos current, BlockPos next) {
        if (!mc.player.isOnGround()) return false;

        BlockPos playerBlock = mc.player.getBlockPos();
        BlockPos frontBlock = playerBlock.offset(mc.player.getHorizontalFacing());

        // Jump over obstacle
        if (!mc.world.getBlockState(frontBlock).isAir() &&
            mc.world.getBlockState(frontBlock).isSolidBlock(mc.world, frontBlock) &&
            mc.world.getBlockState(frontBlock.up()).isAir()) {
            double dist = Math.abs(mc.player.getX() - frontBlock.getX() - 0.5) +
                Math.abs(mc.player.getZ() - frontBlock.getZ() - 0.5);
            if (dist < 1.5) return true;
        }

        // Jump up
        if (next != null && next.getY() > current.getY() &&
            RotationHelper.getHorizontalDistance(Vec3d.ofCenter(current)) < 2.5) {
            return true;
        }

        return false;
    }

    private boolean shouldPrepareGapJump(BlockPos current, BlockPos next) {
        if (next == null || !mc.player.isOnGround()) return false;

        int horizontalDist = Math.max(Math.abs(next.getX() - current.getX()),
            Math.abs(next.getZ() - current.getZ()));
        if (horizontalDist < 2) return false;

        BlockPos between = current.offset(mc.player.getHorizontalFacing());
        if (!mc.world.getBlockState(between.down()).isAir() &&
            mc.world.getBlockState(between.down()).isSolidBlock(mc.world, between.down())) {
            return false;
        }

        Vec3d edgePos = Vec3d.ofCenter(current);
        double distToEdge = Math.sqrt(Math.pow(mc.player.getX() - edgePos.x, 2) +
            Math.pow(mc.player.getZ() - edgePos.z, 2));
        return distToEdge < 3.0;
    }

    private boolean isAtEdge(BlockPos edgePos) {
        Vec3d playerPos = mc.player.getPos();
        Vec3d edgeVec = Vec3d.ofCenter(edgePos);
        double horizontalDist = Math.sqrt(Math.pow(playerPos.x - edgeVec.x, 2) +
            Math.pow(playerPos.z - edgeVec.z, 2));
        return horizontalDist < 0.5;
    }

    private boolean isBlockInWay() {
        BlockPos frontPos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        BlockPos frontUpPos = frontPos.up();
        return (!mc.world.getBlockState(frontPos).isAir() &&
            mc.world.getBlockState(frontPos).getHardness(mc.world, frontPos) >= 0) ||
            (!mc.world.getBlockState(frontUpPos).isAir() &&
                mc.world.getBlockState(frontUpPos).getHardness(mc.world, frontUpPos) >= 0);
    }

    private void recalculatePath() {
        BlockPos goal = currentPath.getGoal();
        if (goal == null) return;

        debug("Recalculating path...");
        Path newPath = com.example.addon.pathfinding.Pathfinder.findPath(mc.player.getBlockPos(), goal);

        if (newPath != null) {
            debug("New path found with " + newPath.getLength() + " waypoints");
            setPath(newPath);
        } else {
            debug("No alternative path found - clearing path");
            clearPath();
        }
    }

    private Path simplifyPath(Path path) {
        List<BlockPos> originalWaypoints = path.getWaypoints();
        if (originalWaypoints.isEmpty()) return path;

        List<BlockPos> simplified = new java.util.ArrayList<>();
        simplified.add(originalWaypoints.get(0));

        for (int i = nodeInterval; i < originalWaypoints.size(); i += nodeInterval) {
            simplified.add(originalWaypoints.get(i));
        }

        BlockPos last = originalWaypoints.get(originalWaypoints.size() - 1);
        if (!simplified.get(simplified.size() - 1).equals(last)) {
            simplified.add(last);
        }

        return new com.example.addon.pathfinding.Path(simplified, path.getTotalCost());
    }

    private void stopMovement() {
        releaseAllMovementKeys();
        stopBreaking();
        RotationHelper.cancelHumanLikeRotation();
        if (mc.player != null) mc.player.setSprinting(false);
        movementState.reset();
        collisionDetector.reset();
        ticksSinceLastJump = 0;
        isPreparingGapJump = false;
        gapJumpTicks = 0;
    }

    private void releaseAllMovementKeys() {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void stopBreaking() {
        if (mc.interactionManager != null) {
            mc.options.attackKey.setPressed(false);
            mc.interactionManager.cancelBlockBreaking();
        }
        currentBreakingBlock = null;
        breakingTicks = 0;
    }

    private float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180) angle -= 360;
        else if (angle < -180) angle += 360;
        return angle;
    }

    private void debug(String msg) {
        if (debugCallback != null) debugCallback.accept(msg);
    }
}
