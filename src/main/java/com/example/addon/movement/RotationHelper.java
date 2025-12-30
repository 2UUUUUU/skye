package com.example.addon.movement;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Helper class for player rotation with SMOOTH movement using SmoothRotationController
 * This is a simplified API wrapper around the advanced rotation system
 */
public class RotationHelper {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // Fallback human-like rotation state (for legacy compatibility)
    private static boolean isRotating = false;
    private static float currentYaw = 0;
    private static float currentPitch = 0;
    private static float targetYaw = 0;
    private static float targetPitch = 0;
    private static float startYaw = 0;
    private static float startPitch = 0;

    private static float rotationProgress = 1.0f;
    private static int rotationTicks = 0;
    private static int totalRotationTicks = 15;

    private static float microAdjustmentYaw = 0;
    private static float microAdjustmentPitch = 0;
    private static int microAdjustmentTimer = 0;
    private static final int MICRO_ADJUSTMENT_INTERVAL = 7;

    private static float driftVelocityYaw = 0;
    private static float driftVelocityPitch = 0;
    private static final float DRIFT_DAMPING = 0.82f;
    private static final float DRIFT_STRENGTH = 0.05f;

    private static float lastYawSpeed = 0;
    private static float lastPitchSpeed = 0;
    private static final float ACCELERATION_FACTOR = 0.15f;

    /**
     * Rotate towards target with smooth A* pathfinding rotation
     * Uses the advanced SmoothRotationController system
     */
    public static void rotateTowardsSmooth(Vec3d target, float maxChangePerTick) {
        if (mc.player == null) return;

        // Set rotation speed and apply
        SmoothRotationController.setMaxRotationSpeed(maxChangePerTick);
        SmoothRotationController.rotateTowards(target);
    }

    /**
     * Rotate towards target with human-like movement (legacy system)
     * This is kept for compatibility with existing code
     */
    public static void rotateTowardsHumanLike(Vec3d target, int speedTicks) {
        if (mc.player == null) return;

        float newTargetYaw = getYawTowards(target);
        float newTargetPitch = getPitchTowards(target);

        float yawDiff = angleDifference(newTargetYaw, targetYaw);
        float pitchDiff = Math.abs(newTargetPitch - targetPitch);

        if (!isRotating || Math.abs(yawDiff) > 2.0f || pitchDiff > 1.0f) {
            startYaw = mc.player.getYaw();
            startPitch = mc.player.getPitch();
            currentYaw = startYaw;
            currentPitch = startPitch;
            targetYaw = newTargetYaw;
            targetPitch = newTargetPitch;

            rotationProgress = 0.0f;
            rotationTicks = 0;

            float totalAngle = (float)Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
            totalRotationTicks = (int)(Math.max(speedTicks, 8) + totalAngle * 0.25f);
            totalRotationTicks = Math.min(totalRotationTicks, 50);

            driftVelocityYaw += (float)(Math.random() - 0.5) * DRIFT_STRENGTH;
            driftVelocityPitch += (float)(Math.random() - 0.5) * DRIFT_STRENGTH * 0.5f;

            isRotating = true;
        }
    }

    /**
     * Tick the human-like rotation system (legacy)
     */
    public static void tickHumanLikeRotation() {
        if (mc.player == null || !isRotating) return;

        microAdjustmentTimer++;
        if (microAdjustmentTimer >= MICRO_ADJUSTMENT_INTERVAL) {
            microAdjustmentTimer = 0;
            microAdjustmentYaw = (float)(Math.random() - 0.5) * 0.12f;
            microAdjustmentPitch = (float)(Math.random() - 0.5) * 0.06f;
            driftVelocityYaw += (float)(Math.random() - 0.5) * DRIFT_STRENGTH;
            driftVelocityPitch += (float)(Math.random() - 0.5) * DRIFT_STRENGTH * 0.5f;
        }

        driftVelocityYaw *= DRIFT_DAMPING;
        driftVelocityPitch *= DRIFT_DAMPING;

        if (rotationProgress < 1.0f) {
            rotationTicks++;
            rotationProgress = (float)rotationTicks / (float)totalRotationTicks;
            rotationProgress = Math.min(rotationProgress, 1.0f);

            float easedProgress = easeMouseMovement(rotationProgress);

            float targetYawInterp = lerpAngle(startYaw, targetYaw, easedProgress);
            float targetPitchInterp = lerp(startPitch, targetPitch, easedProgress);

            float yawSpeed = angleDifference(targetYawInterp, currentYaw);
            float pitchSpeed = targetPitchInterp - currentPitch;

            yawSpeed = lastYawSpeed + (yawSpeed - lastYawSpeed) * ACCELERATION_FACTOR;
            pitchSpeed = lastPitchSpeed + (pitchSpeed - lastPitchSpeed) * ACCELERATION_FACTOR;

            lastYawSpeed = yawSpeed;
            lastPitchSpeed = pitchSpeed;

            currentYaw = normalizeAngle(currentYaw + yawSpeed);
            currentPitch = currentPitch + pitchSpeed;

            float activeScale = 0.3f * (1.0f - easedProgress * 0.6f);
            currentYaw += microAdjustmentYaw * activeScale + driftVelocityYaw * 0.7f;
            currentPitch += microAdjustmentPitch * activeScale + driftVelocityPitch * 0.7f;

        } else {
            currentYaw = targetYaw + microAdjustmentYaw * 0.4f + driftVelocityYaw * 0.5f;
            currentPitch = targetPitch + microAdjustmentPitch * 0.4f + driftVelocityPitch * 0.5f;

            lastYawSpeed *= 0.9f;
            lastPitchSpeed *= 0.9f;
        }

        currentYaw = normalizeAngle(currentYaw);
        currentPitch = clampPitch(currentPitch);

        mc.player.setYaw(currentYaw);
        mc.player.setPitch(currentPitch);
    }

    private static float easeMouseMovement(float t) {
        if (t < 0.1f) {
            return 6f * t * t;
        } else if (t < 0.85f) {
            float adjusted = (t - 0.1f) / 0.75f;
            return 0.06f + 0.78f * adjusted;
        } else {
            float adjusted = (t - 0.85f) / 0.15f;
            float eased = 1.0f - (float)Math.pow(1.0f - adjusted, 3);
            return 0.84f + 0.16f * eased;
        }
    }

    /**
     * Cancel human-like rotation
     */
    public static void cancelHumanLikeRotation() {
        isRotating = false;
        rotationProgress = 1.0f;
        microAdjustmentYaw = 0;
        microAdjustmentPitch = 0;
        driftVelocityYaw = 0;
        driftVelocityPitch = 0;
        lastYawSpeed = 0;
        lastPitchSpeed = 0;

        // Also reset the smooth controller
        SmoothRotationController.reset();
    }

    /**
     * Calculate yaw angle to face a position
     */
    public static float getYawTowards(Vec3d target) {
        if (mc.player == null) return 0;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d direction = target.subtract(playerPos);

        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    /**
     * Calculate pitch angle to face a position
     */
    public static float getPitchTowards(Vec3d target) {
        if (mc.player == null) return 0;

        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = target.subtract(playerPos);

        double horizontalDistance = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        return (float) -Math.toDegrees(Math.atan2(direction.y, horizontalDistance));
    }

    /**
     * Instantly face towards target (no smoothing)
     */
    public static void faceTarget(Vec3d target) {
        if (mc.player == null) return;

        mc.player.setYaw(getYawTowards(target));
        mc.player.setPitch(MathHelper.clamp(getPitchTowards(target), -90, 90));
    }

    /**
     * Check if player is facing towards target (within tolerance)
     */
    public static boolean isFacingTarget(Vec3d target, float yawTolerance, float pitchTolerance) {
        if (mc.player == null) return false;

        float targetYaw = getYawTowards(target);
        float targetPitch = getPitchTowards(target);

        float yawDiff = Math.abs(MathHelper.wrapDegrees(targetYaw - mc.player.getYaw()));
        float pitchDiff = Math.abs(targetPitch - mc.player.getPitch());

        return yawDiff <= yawTolerance && pitchDiff <= pitchTolerance;
    }

    /**
     * Get horizontal distance to target
     */
    public static double getHorizontalDistance(Vec3d target) {
        if (mc.player == null) return 0;

        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();

        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================== UTILITY METHODS ====================

    private static float angleDifference(float target, float current) {
        return normalizeAngle(target - current);
    }

    private static float lerpAngle(float start, float end, float t) {
        float diff = angleDifference(end, start);
        return normalizeAngle(start + diff * t);
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    private static float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180) angle -= 360;
        else if (angle < -180) angle += 360;
        return angle;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90, Math.min(90, pitch));
    }
}
