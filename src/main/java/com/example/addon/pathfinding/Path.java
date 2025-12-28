package com.example.addon.pathfinding;

import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a calculated path from start to goal
 */
public class Path {
    private final List<BlockPos> waypoints;
    private final double totalCost;
    private int currentWaypointIndex;

    public Path(List<BlockPos> waypoints, double totalCost) {
        this.waypoints = new ArrayList<>(waypoints);
        this.totalCost = totalCost;
        this.currentWaypointIndex = 0;
    }

    /**
     * Create a path from a PathNode (traces back through parents)
     */
    public static Path fromPathNode(PathNode goalNode) {
        List<BlockPos> waypoints = new ArrayList<>();
        PathNode current = goalNode;

        while (current != null) {
            waypoints.add(current.pos);
            current = current.parent;
        }

        Collections.reverse(waypoints);
        return new Path(waypoints, goalNode.gCost);
    }

    /**
     * Get all waypoints in the path
     */
    public List<BlockPos> getWaypoints() {
        return new ArrayList<>(waypoints);
    }

    /**
     * Get the total cost of the path
     */
    public double getTotalCost() {
        return totalCost;
    }

    /**
     * Get the number of waypoints
     */
    public int getLength() {
        return waypoints.size();
    }

    /**
     * Get the current waypoint the player should move towards
     */
    public BlockPos getCurrentWaypoint() {
        if (currentWaypointIndex >= waypoints.size()) {
            return null;
        }
        return waypoints.get(currentWaypointIndex);
    }

    /**
     * Get the next waypoint after the current one
     */
    public BlockPos getNextWaypoint() {
        if (currentWaypointIndex + 1 >= waypoints.size()) {
            return null;
        }
        return waypoints.get(currentWaypointIndex + 1);
    }

    /**
     * Advance to the next waypoint
     */
    public void advanceWaypoint() {
        if (currentWaypointIndex < waypoints.size() - 1) {
            currentWaypointIndex++;
        }
    }

    /**
     * Check if the path is complete
     */
    public boolean isComplete() {
        return currentWaypointIndex >= waypoints.size() - 1;
    }

    /**
     * Get the goal position (final waypoint)
     */
    public BlockPos getGoal() {
        if (waypoints.isEmpty()) return null;
        return waypoints.get(waypoints.size() - 1);
    }

    /**
     * Get the start position (first waypoint)
     */
    public BlockPos getStart() {
        if (waypoints.isEmpty()) return null;
        return waypoints.get(0);
    }

    /**
     * Reset the path to the beginning
     */
    public void reset() {
        currentWaypointIndex = 0;
    }

    /**
     * Get current progress through the path (0.0 to 1.0)
     */
    public double getProgress() {
        if (waypoints.isEmpty()) return 1.0;
        return (double) currentWaypointIndex / (waypoints.size() - 1);
    }

    @Override
    public String toString() {
        return String.format("Path[waypoints=%d, cost=%.2f, progress=%d/%d]",
            waypoints.size(), totalCost, currentWaypointIndex, waypoints.size() - 1);
    }
}
