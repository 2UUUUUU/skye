package com.example.addon.movement;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Helper class for player rotation towards targets
 */
public class RotationHelper {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

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
     * Smoothly rotate player towards target
     */
    public static void rotateSmoothly(Vec3d target, float maxYawChange, float maxPitchChange) {
        if (mc.player == null) return;

        float targetYaw = getYawTowards(target);
        float targetPitch = getPitchTowards(target);

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        // Calculate yaw difference (handle wrapping)
        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        // Clamp changes
        yawDiff = MathHelper.clamp(yawDiff, -maxYawChange, maxYawChange);
        pitchDiff = MathHelper.clamp(pitchDiff, -maxPitchChange, maxPitchChange);

        // Apply rotation
        mc.player.setYaw(currentYaw + yawDiff);
        mc.player.setPitch(MathHelper.clamp(currentPitch + pitchDiff, -90, 90));
    }

    /**
     * Instantly face towards target
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
}
