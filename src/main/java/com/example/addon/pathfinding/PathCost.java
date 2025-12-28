package com.example.addon.pathfinding;

/**
 * Defines movement costs for different actions
 */
public class PathCost {
    // Basic movement costs
    public static final double WALK = 1.0;
    public static final double DIAGONAL = 1.414; // sqrt(2)
    public static final double JUMP = 1.2;
    public static final double FALL = 0.5;
    public static final double DIAGONAL_JUMP = 1.7;

    // Penalty costs
    public static final double WATER = 2.0;
    public static final double LAVA = 100.0;
    public static final double SOUL_SAND = 1.5;
    public static final double DANGEROUS = 50.0;

    // Maximum allowed fall distance without damage
    public static final int SAFE_FALL_DISTANCE = 3;
    public static final int MAX_FALL_DISTANCE = 10;

    // Maximum height the player can jump
    public static final int MAX_JUMP_HEIGHT = 1;

    /**
     * Calculate movement cost based on action type
     */
    public static double getMovementCost(boolean isDiagonal, boolean isJump, boolean isFall, int fallDistance) {
        double cost = WALK;

        if (isDiagonal && isJump) {
            cost = DIAGONAL_JUMP;
        } else if (isDiagonal) {
            cost = DIAGONAL;
        } else if (isJump) {
            cost = JUMP;
        } else if (isFall) {
            cost = FALL;
            if (fallDistance > SAFE_FALL_DISTANCE) {
                cost += (fallDistance - SAFE_FALL_DISTANCE) * 0.5;
            }
        }

        return cost;
    }
}
