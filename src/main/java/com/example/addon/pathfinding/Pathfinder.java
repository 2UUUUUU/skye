package com.example.addon.pathfinding;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/**
 * A* pathfinding implementation for Minecraft
 */
public class Pathfinder {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // Maximum number of nodes to explore before giving up
    private static final int MAX_ITERATIONS = 10000;

    // Maximum distance from start position
    private static final int MAX_SEARCH_DISTANCE = 128;

    /**
     * Find a path from start to goal using A* algorithm
     */
    public static Path findPath(BlockPos start, BlockPos goal) {
        if (mc.world == null) return null;

        // Validate start and goal
        if (!WalkabilityChecker.isWalkable(mc.world, start)) {
            return null;
        }

        if (!WalkabilityChecker.isWalkable(mc.world, goal)) {
            return null;
        }

        // Check distance
        if (start.getSquaredDistance(goal) > MAX_SEARCH_DISTANCE * MAX_SEARCH_DISTANCE) {
            return null;
        }

        // Initialize data structures
        PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        // Create start node
        PathNode startNode = new PathNode(start, null, 0, heuristic(start, goal));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        int iterations = 0;

        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;

            // Get node with lowest f cost
            PathNode current = openSet.poll();

            // Check if we reached the goal
            if (current.pos.equals(goal)) {
                return Path.fromPathNode(current);
            }

            closedSet.add(current.pos);

            // Explore neighbors
            for (BlockPos neighborPos : getNeighbors(mc.world, current.pos, goal)) {
                // Skip if already evaluated
                if (closedSet.contains(neighborPos)) {
                    continue;
                }

                // Calculate costs
                double moveCost = calculateMoveCost(mc.world, current.pos, neighborPos);
                double newGCost = current.gCost + moveCost;
                double hCost = heuristic(neighborPos, goal);

                PathNode neighbor = allNodes.get(neighborPos);

                if (neighbor == null) {
                    // New node
                    neighbor = new PathNode(neighborPos, current, newGCost, hCost);
                    allNodes.put(neighborPos, neighbor);
                    openSet.add(neighbor);
                } else if (newGCost < neighbor.gCost) {
                    // Found better path to this node
                    openSet.remove(neighbor);
                    neighbor.updateCosts(current, newGCost, hCost);
                    openSet.add(neighbor);
                }
            }
        }

        // No path found
        return null;
    }

    /**
     * Get valid neighbor positions from a given position
     */
    private static List<BlockPos> getNeighbors(World world, BlockPos pos, BlockPos goal) {
        List<BlockPos> neighbors = new ArrayList<>();

        // Cardinal directions (N, S, E, W)
        BlockPos[] cardinals = {
            pos.north(),
            pos.south(),
            pos.east(),
            pos.west()
        };

        // Diagonal directions (NE, NW, SE, SW)
        BlockPos[] diagonals = {
            pos.north().east(),
            pos.north().west(),
            pos.south().east(),
            pos.south().west()
        };

        // Check cardinal directions
        for (BlockPos neighbor : cardinals) {
            addValidNeighbor(world, pos, neighbor, neighbors, false);
        }

        // Check diagonal directions (only if both cardinals are walkable)
        if (WalkabilityChecker.isWalkable(world, pos.north()) &&
            WalkabilityChecker.isWalkable(world, pos.east())) {
            addValidNeighbor(world, pos, pos.north().east(), neighbors, true);
        }

        if (WalkabilityChecker.isWalkable(world, pos.north()) &&
            WalkabilityChecker.isWalkable(world, pos.west())) {
            addValidNeighbor(world, pos, pos.north().west(), neighbors, true);
        }

        if (WalkabilityChecker.isWalkable(world, pos.south()) &&
            WalkabilityChecker.isWalkable(world, pos.east())) {
            addValidNeighbor(world, pos, pos.south().east(), neighbors, true);
        }

        if (WalkabilityChecker.isWalkable(world, pos.south()) &&
            WalkabilityChecker.isWalkable(world, pos.west())) {
            addValidNeighbor(world, pos, pos.south().west(), neighbors, true);
        }

        return neighbors;
    }

    /**
     * Add a neighbor if it's valid
     */
    private static void addValidNeighbor(World world, BlockPos from, BlockPos to,
                                         List<BlockPos> neighbors, boolean isDiagonal) {
        // Try at same level
        if (WalkabilityChecker.isSafeMove(world, from, to)) {
            neighbors.add(to);
            return;
        }

        // Try jumping up (1 block)
        BlockPos jumpUp = to.up();
        if (WalkabilityChecker.isSafeMove(world, from, jumpUp)) {
            neighbors.add(jumpUp);
            return;
        }

        // Try falling down
        int fallDistance = WalkabilityChecker.calculateFallDistance(world, to);
        if (fallDistance > 0 && fallDistance <= PathCost.MAX_FALL_DISTANCE) {
            BlockPos fallPos = to.down(fallDistance);
            if (WalkabilityChecker.isSafeMove(world, from, fallPos)) {
                neighbors.add(fallPos);
            }
        }
    }

    /**
     * Calculate movement cost between two adjacent positions
     */
    private static double calculateMoveCost(World world, BlockPos from, BlockPos to) {
        // Determine movement type
        boolean isDiagonal = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()) == 2;
        boolean isJump = to.getY() > from.getY();
        boolean isFall = to.getY() < from.getY();
        int fallDistance = isFall ? from.getY() - to.getY() : 0;

        // Base movement cost
        double cost = PathCost.getMovementCost(isDiagonal, isJump, isFall, fallDistance);

        // Add block penalty
        cost += WalkabilityChecker.getBlockPenalty(world, to);

        return cost;
    }

    /**
     * Heuristic function (Euclidean distance)
     */
    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Find a path with custom parameters
     */
    public static Path findPath(BlockPos start, BlockPos goal, int maxIterations, int maxDistance) {
        // TODO: Implement custom parameter version if needed
        return findPath(start, goal);
    }
}
