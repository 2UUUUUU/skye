package com.example.addon.movement;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Advanced rotation controller with multiple smoothing algorithms.
 * Inspired by LiquidBounce and Wurst Client rotation systems.
 *
 * Features:
 * - Multiple smoothing modes (Linear, Sigmoid, Acceleration-based)
 * - Uses last reported rotation for smoother transitions
 * - Independent yaw/pitch handling with proportional scaling
 * - Configurable acceleration and deceleration
 * - Mouse movement simulation
 */
public class SmoothRotationController {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // Rotation state tracking
    private static float lastReportedYaw = 0f;
    private static float lastReportedPitch = 0f;
    private static boolean initialized = false;

    // Velocity tracking for acceleration-based smoothing
    private static float yawVelocity = 0f;
    private static float pitchVelocity = 0f;

    // Smoothing parameters
    private static SmoothingMode smoothingMode = SmoothingMode.LINEAR;
    private static float maxYawSpeed = 30f;
    private static float maxPitchSpeed = 30f;
    private static float acceleration = 0.5f; // How fast to reach max speed (0-1)
    private static float deceleration = 0.3f; // How fast to slow down near target (0-1)
    private static boolean randomizeSpeed = false;
    private static float smoothness = 1.0f; // Overall smoothness multiplier (0.1-2.0)

    // Threshold for "close enough" to target
    private static float completionThreshold = 1.0f; // degrees

    public enum SmoothingMode {
        /**
         * Linear interpolation - constant speed
         * Good for: Fast, predictable movement
         */
        LINEAR,

        /**
         * Sigmoid curve - smooth acceleration and deceleration
         * Good for: Natural-looking mouse movement
         */
        SIGMOID,

        /**
         * Acceleration-based - simulates momentum
         * Good for: Most human-like movement
         */
        ACCELERATION,

        /**
         * Instant - no smoothing (for testing/debugging)
         */
        INSTANT
    }

    /**
     * Initialize or update the controller with current player rotation
     */
    public static void initialize() {
        if (mc.player == null) return;

        if (!initialized) {
            lastReportedYaw = mc.player.getYaw();
            lastReportedPitch = mc.player.getPitch();
            yawVelocity = 0f;
            pitchVelocity = 0f;
            initialized = true;
        }
    }

    /**
     * Reset the controller state
     */
    public static void reset() {
        initialized = false;
        yawVelocity = 0f;
        pitchVelocity = 0f;
    }

    /**
     * Set smoothing mode
     */
    public static void setSmoothingMode(SmoothingMode mode) {
        smoothingMode = mode;
        // Reset velocity when changing modes
        yawVelocity = 0f;
        pitchVelocity = 0f;
    }

    /**
     * Set maximum rotation speed
     */
    public static void setMaxRotationSpeed(float maxSpeed) {
        maxYawSpeed = maxSpeed;
        maxPitchSpeed = maxSpeed;
    }

    /**
     * Set independent max rotation speeds for yaw and pitch
     */
    public static void setMaxRotationSpeed(float maxYaw, float maxPitch) {
        maxYawSpeed = maxYaw;
        maxPitchSpeed = maxPitch;
    }

    /**
     * Set acceleration factor (how quickly to reach max speed)
     * @param accel Value between 0 (slow acceleration) and 1 (instant max speed)
     */
    public static void setAcceleration(float accel) {
        acceleration = MathHelper.clamp(accel, 0.01f, 1.0f);
    }

    /**
     * Set deceleration factor (how quickly to slow down near target)
     * @param decel Value between 0 (no slowdown) and 1 (instant stop)
     */
    public static void setDeceleration(float decel) {
        deceleration = MathHelper.clamp(decel, 0.01f, 1.0f);
    }

    /**
     * Set overall smoothness multiplier
     * @param smooth 0.1 = very smooth/slow, 1.0 = normal, 2.0 = fast
     */
    public static void setSmoothness(float smooth) {
        smoothness = MathHelper.clamp(smooth, 0.1f, 2.0f);
    }

    /**
     * Enable/disable randomized rotation speed
     */
    public static void setRandomizeSpeed(boolean randomize) {
        randomizeSpeed = randomize;
    }

    /**
     * Set completion threshold (how close is "close enough")
     */
    public static void setCompletionThreshold(float threshold) {
        completionThreshold = Math.max(0.1f, threshold);
    }

    /**
     * Smoothly rotate towards a target position
     *
     * @param target Target position to look at
     * @return true if rotation is complete (within threshold)
     */
    public static boolean rotateTowards(Vec3d target) {
        if (mc.player == null) return true;

        initialize();

        // Calculate needed rotation
        Vec3d eyes = mc.player.getEyePos();
        float[] needed = getNeededRotation(eyes, target);
        float neededYaw = needed[0];
        float neededPitch = needed[1];

        // Check if already facing target
        if (isAlreadyFacing(neededYaw, neededPitch)) {
            // Dampen velocity when target reached
            yawVelocity *= 0.5f;
            pitchVelocity *= 0.5f;
            return true;
        }

        // Apply smoothing based on mode
        float[] newRotation;
        switch (smoothingMode) {
            case SIGMOID:
                newRotation = applySigmoidSmoothing(neededYaw, neededPitch);
                break;
            case ACCELERATION:
                newRotation = applyAccelerationSmoothing(neededYaw, neededPitch);
                break;
            case INSTANT:
                newRotation = new float[] { neededYaw, neededPitch };
                break;
            case LINEAR:
            default:
                newRotation = applyLinearSmoothing(neededYaw, neededPitch);
                break;
        }

        float newYaw = newRotation[0];
        float newPitch = newRotation[1];

        // Apply rotation
        mc.player.setYaw(newYaw);
        mc.player.setPitch(MathHelper.clamp(newPitch, -90f, 90f));

        // Update last reported rotation
        lastReportedYaw = newYaw;
        lastReportedPitch = newPitch;

        return false;
    }

    /**
     * Linear smoothing - constant speed towards target
     */
    private static float[] applyLinearSmoothing(float targetYaw, float targetPitch) {
        // Calculate current speed limits (with optional randomization)
        float currentMaxYaw = maxYawSpeed * smoothness;
        float currentMaxPitch = maxPitchSpeed * smoothness;

        if (randomizeSpeed) {
            currentMaxYaw *= (0.8f + Math.random() * 0.4f); // 80-120% of max
            currentMaxPitch *= (0.8f + Math.random() * 0.4f);
        }

        // Apply proportional scaling for smooth diagonal movements
        float yawDiff = Math.abs(wrapDegrees(targetYaw - lastReportedYaw));
        float pitchDiff = Math.abs(wrapDegrees(targetPitch - lastReportedPitch));

        // Scale down the faster axis to maintain proportional movement
        if (yawDiff > 0.1f && pitchDiff > 0.1f) {
            float ratio = Math.min(yawDiff / pitchDiff, pitchDiff / yawDiff);
            if (yawDiff > pitchDiff) {
                currentMaxYaw *= MathHelper.lerp(ratio, 0.5f, 1.0f);
            } else {
                currentMaxPitch *= MathHelper.lerp(ratio, 0.5f, 1.0f);
            }
        }

        // Apply deceleration near target
        if (yawDiff < 15f) {
            currentMaxYaw *= MathHelper.lerp(yawDiff / 15f, deceleration, 1.0f);
        }
        if (pitchDiff < 15f) {
            currentMaxPitch *= MathHelper.lerp(pitchDiff / 15f, deceleration, 1.0f);
        }

        // Smoothly limit angle changes
        float newYaw = limitAngleChange(lastReportedYaw, targetYaw, currentMaxYaw);
        float newPitch = limitAngleChange(lastReportedPitch, targetPitch, currentMaxPitch);

        return new float[] { newYaw, newPitch };
    }

    /**
     * Sigmoid smoothing - smooth S-curve acceleration and deceleration
     */
    private static float[] applySigmoidSmoothing(float targetYaw, float targetPitch) {
        float yawDiff = wrapDegrees(targetYaw - lastReportedYaw);
        float pitchDiff = wrapDegrees(targetPitch - lastReportedPitch);

        // Calculate sigmoid factor based on distance to target
        float yawProgress = Math.abs(yawDiff) / 180f; // Normalize to 0-1
        float pitchProgress = Math.abs(pitchDiff) / 90f;

        // Sigmoid function: smooth start and end
        float yawSigmoid = sigmoid(yawProgress * 6f - 3f); // Map to sigmoid curve
        float pitchSigmoid = sigmoid(pitchProgress * 6f - 3f);

        // Apply sigmoid-scaled speed
        float yawSpeed = maxYawSpeed * smoothness * yawSigmoid;
        float pitchSpeed = maxPitchSpeed * smoothness * pitchSigmoid;

        // Add randomization
        if (randomizeSpeed) {
            yawSpeed *= (0.85f + Math.random() * 0.3f);
            pitchSpeed *= (0.85f + Math.random() * 0.3f);
        }

        float newYaw = limitAngleChange(lastReportedYaw, targetYaw, yawSpeed);
        float newPitch = limitAngleChange(lastReportedPitch, targetPitch, pitchSpeed);

        return new float[] { newYaw, newPitch };
    }

    /**
     * Acceleration-based smoothing - simulates mouse momentum
     */
    private static float[] applyAccelerationSmoothing(float targetYaw, float targetPitch) {
        float yawDiff = wrapDegrees(targetYaw - lastReportedYaw);
        float pitchDiff = wrapDegrees(targetPitch - lastReportedPitch);

        // Calculate desired velocity
        float targetYawVelocity = MathHelper.clamp(yawDiff * 0.5f, -maxYawSpeed * smoothness, maxYawSpeed * smoothness);
        float targetPitchVelocity = MathHelper.clamp(pitchDiff * 0.5f, -maxPitchSpeed * smoothness, maxPitchSpeed * smoothness);

        // Apply acceleration towards target velocity
        float accelFactor = acceleration * (randomizeSpeed ? (0.7f + (float)Math.random() * 0.6f) : 1.0f);
        yawVelocity += (targetYawVelocity - yawVelocity) * accelFactor;
        pitchVelocity += (targetPitchVelocity - pitchVelocity) * accelFactor;

        // Apply deceleration when close to target
        float yawDist = Math.abs(yawDiff);
        float pitchDist = Math.abs(pitchDiff);

        if (yawDist < 10f) {
            float decelFactor = 1.0f - ((10f - yawDist) / 10f) * (1.0f - deceleration);
            yawVelocity *= decelFactor;
        }
        if (pitchDist < 10f) {
            float decelFactor = 1.0f - ((10f - pitchDist) / 10f) * (1.0f - deceleration);
            pitchVelocity *= decelFactor;
        }

        // Apply friction
        yawVelocity *= 0.95f;
        pitchVelocity *= 0.95f;

        // Apply velocity to rotation
        float newYaw = lastReportedYaw + yawVelocity;
        float newPitch = lastReportedPitch + pitchVelocity;

        return new float[] { newYaw, newPitch };
    }

    /**
     * Sigmoid function for smooth S-curve
     */
    private static float sigmoid(float x) {
        return (float)(1.0 / (1.0 + Math.exp(-x)));
    }

    /**
     * Instantly face target (no smoothing)
     */
    public static void faceTarget(Vec3d target) {
        if (mc.player == null) return;

        Vec3d eyes = mc.player.getEyePos();
        float[] needed = getNeededRotation(eyes, target);

        mc.player.setYaw(needed[0]);
        mc.player.setPitch(MathHelper.clamp(needed[1], -90f, 90f));

        lastReportedYaw = needed[0];
        lastReportedPitch = needed[1];
        yawVelocity = 0f;
        pitchVelocity = 0f;
    }

    /**
     * Check if already facing within threshold
     */
    private static boolean isAlreadyFacing(float targetYaw, float targetPitch) {
        float yawDiff = Math.abs(wrapDegrees(targetYaw - lastReportedYaw));
        float pitchDiff = Math.abs(wrapDegrees(targetPitch - lastReportedPitch));

        return yawDiff <= completionThreshold && pitchDiff <= completionThreshold;
    }

    /**
     * Limit angle change with proper wrapping (from Wurst Client)
     * Key: Does NOT wrap current angle before calculation!
     */
    private static float limitAngleChange(float current, float intended, float maxChange) {
        float currentWrapped = wrapDegrees(current);
        float intendedWrapped = wrapDegrees(intended);

        float change = wrapDegrees(intendedWrapped - currentWrapped);
        change = MathHelper.clamp(change, -maxChange, maxChange);

        return current + change;
    }

    /**
     * Get needed rotation to look at target
     */
    private static float[] getNeededRotation(Vec3d eyes, Vec3d target) {
        double diffX = target.x - eyes.x;
        double diffY = target.y - eyes.y;
        double diffZ = target.z - eyes.z;

        // Calculate yaw
        double yaw = Math.toDegrees(Math.atan2(diffZ, diffX)) - 90f;

        // Calculate pitch
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double pitch = -Math.toDegrees(Math.atan2(diffY, diffXZ));

        return new float[] {
            wrapDegrees((float) yaw),
            (float) pitch
        };
    }

    /**
     * Wrap angle to [-180, 180] range
     */
    private static float wrapDegrees(float angle) {
        return MathHelper.wrapDegrees(angle);
    }

    /**
     * Calculate angle between current rotation and target
     */
    public static double getAngleToTarget(Vec3d target) {
        if (mc.player == null) return 0;

        Vec3d eyes = mc.player.getEyePos();
        float[] needed = getNeededRotation(eyes, target);

        float yawDiff = wrapDegrees(needed[0] - lastReportedYaw);
        float pitchDiff = wrapDegrees(needed[1] - lastReportedPitch);

        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    /**
     * Get current rotation state
     */
    public static float[] getCurrentRotation() {
        return new float[] { lastReportedYaw, lastReportedPitch };
    }

    /**
     * Get current velocity (for acceleration mode)
     */
    public static float[] getCurrentVelocity() {
        return new float[] { yawVelocity, pitchVelocity };
    }
}
