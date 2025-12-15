package com.example.addon.utils.player;

import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Utility class for smooth aiming functionality
 */
public class SmoothAimUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // State for smooth rotation
    private static boolean isRotating = false;
    private static float startYaw = 0;
    private static float startPitch = 0;
    private static float targetYaw = 0;
    private static float targetPitch = 0;
    private static int currentTick = 0;
    private static int totalTicks = 0;
    private static Runnable onComplete = null;

    /**
     * Start a smooth rotation to a target block position
     * @param targetPos The target block position to aim at
     * @param rotationSpeed The rotation speed in ticks
     * @param callback Callback to execute when rotation completes
     */
    public static void startSmoothRotation(BlockPos targetPos, int rotationSpeed, Runnable callback) {
        if (mc.player == null) return;

        Vec3d targetVec = Vec3d.ofCenter(targetPos);
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = targetVec.subtract(playerPos).normalize();

        startYaw = mc.player.getYaw();
        startPitch = mc.player.getPitch();
        targetYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        targetPitch = (float) Math.toDegrees(-Math.asin(direction.y));

        currentTick = 0;
        totalTicks = Math.max(1, rotationSpeed);
        onComplete = callback;
        isRotating = true;
    }

    /**
     * Start a smooth rotation to specific yaw and pitch angles
     * @param yaw Target yaw angle
     * @param pitch Target pitch angle
     * @param rotationSpeed The rotation speed in ticks
     * @param callback Callback to execute when rotation completes
     */
    public static void startSmoothRotation(float yaw, float pitch, int rotationSpeed, Runnable callback) {
        if (mc.player == null) return;

        startYaw = mc.player.getYaw();
        startPitch = mc.player.getPitch();
        targetYaw = yaw;
        targetPitch = pitch;

        currentTick = 0;
        totalTicks = Math.max(1, rotationSpeed);
        onComplete = callback;
        isRotating = true;
    }

    /**
     * Tick the smooth rotation - should be called every tick from TickEvent.Pre
     * @return true if rotation is complete, false otherwise
     */
    public static boolean tickRotation() {
        if (!isRotating || mc.player == null) return false;

        if (currentTick >= totalTicks) {
            // Rotation complete - snap to final position
            Rotations.rotate(targetYaw, targetPitch);
            isRotating = false;

            if (onComplete != null) {
                onComplete.run();
                onComplete = null;
            }
            return true;
        }

        // Calculate interpolated angles
        float progress = (float) currentTick / (float) totalTicks;
        float currentYaw = lerpAngle(startYaw, targetYaw, progress);
        float currentPitch = lerp(startPitch, targetPitch, progress);

        // Apply rotation
        Rotations.rotate(currentYaw, currentPitch);
        currentTick++;

        return false;
    }

    /**
     * Check if a smooth rotation is currently in progress
     */
    public static boolean isRotating() {
        return isRotating;
    }

    /**
     * Cancel the current smooth rotation
     */
    public static void cancelRotation() {
        isRotating = false;
        onComplete = null;
        currentTick = 0;
    }

    /**
     * Get the current target yaw
     */
    public static float getTargetYaw() {
        return targetYaw;
    }

    /**
     * Get the current target pitch
     */
    public static float getTargetPitch() {
        return targetPitch;
    }

    /**
     * Linear interpolation between two values
     */
    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    /**
     * Linear interpolation for angles (handles wrapping)
     */
    private static float lerpAngle(float start, float end, float progress) {
        start = normalizeAngle(start);
        end = normalizeAngle(end);

        float diff = end - start;
        if (diff > 180) {
            diff -= 360;
        } else if (diff < -180) {
            diff += 360;
        }

        return normalizeAngle(start + diff * progress);
    }

    /**
     * Normalize angle to [-180, 180] range
     */
    private static float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180) {
            angle -= 360;
        } else if (angle < -180) {
            angle += 360;
        }
        return angle;
    }

    /**
     * Calculate aim angles to a block position
     * @param targetPos The target block position
     * @param result Array to store [yaw, pitch]
     */
    public static void calculateAimAngles(BlockPos targetPos, float[] result) {
        if (mc.player == null) return;

        Vec3d targetCenter = Vec3d.ofCenter(targetPos);
        Vec3d playerEyes = mc.player.getEyePos();

        result[0] = (float) Math.toDegrees(Math.atan2(targetCenter.z - playerEyes.z,
            targetCenter.x - playerEyes.x)) - 90.0f;

        double deltaX = targetCenter.x - playerEyes.x;
        double deltaY = targetCenter.y - playerEyes.y;
        double deltaZ = targetCenter.z - playerEyes.z;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        result[1] = (float) -Math.toDegrees(Math.atan2(deltaY, distance));
    }
}
