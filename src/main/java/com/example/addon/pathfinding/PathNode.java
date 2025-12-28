package com.example.addon.pathfinding;

import net.minecraft.util.math.BlockPos;

/**
 * Represents a node in the pathfinding grid
 */
public class PathNode implements Comparable<PathNode> {
    public final BlockPos pos;
    public PathNode parent;
    public double gCost; // Cost from start to this node
    public double hCost; // Heuristic cost from this node to goal
    public double fCost; // Total cost (g + h)

    public PathNode(BlockPos pos) {
        this.pos = pos;
        this.parent = null;
        this.gCost = 0;
        this.hCost = 0;
        this.fCost = 0;
    }

    public PathNode(BlockPos pos, PathNode parent, double gCost, double hCost) {
        this.pos = pos;
        this.parent = parent;
        this.gCost = gCost;
        this.hCost = hCost;
        this.fCost = gCost + hCost;
    }

    public void updateCosts(PathNode parent, double gCost, double hCost) {
        this.parent = parent;
        this.gCost = gCost;
        this.hCost = hCost;
        this.fCost = gCost + hCost;
    }

    @Override
    public int compareTo(PathNode other) {
        int fCompare = Double.compare(this.fCost, other.fCost);
        if (fCompare != 0) return fCompare;
        return Double.compare(this.hCost, other.hCost);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PathNode other)) return false;
        return this.pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}
