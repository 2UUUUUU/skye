package com.example.addon.modules;

import com.example.addon.Main;
import com.example.addon.commands.PathCommand;
import com.example.addon.movement.PathExecutor;
import com.example.addon.pathfinding.Path;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Module for pathfinding visualization and execution
 */
public class Pathfinding extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General Settings
    private final Setting<Boolean> autoExecute = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-execute")
        .description("Automatically execute the path")
        .defaultValue(true)
        .build());

    private final Setting<NodeCalculation> nodeCalculation = sgGeneral.add(new EnumSetting.Builder<NodeCalculation>()
        .name("node-calculation")
        .description("How often to place nodes on the path")
        .defaultValue(NodeCalculation.EveryBlock)
        .build());

    private final Setting<Double> waypointCheckpointDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("waypoint-checkpoint-distance")
        .description("Distance to consider a checkpoint waypoint reached")
        .defaultValue(0.5)
        .min(0.1)
        .max(3.0)
        .sliderMax(3.0)
        .build());

    private final Setting<Double> waypointFinalDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("waypoint-final-distance")
        .description("Distance to consider the final destination reached")
        .defaultValue(0.5)
        .min(0.1)
        .max(3.0)
        .sliderMax(3.0)
        .build());

    // Movement Settings
    private final Setting<Boolean> smoothRotation = sgMovement.add(new BoolSetting.Builder()
        .name("smooth-rotation")
        .description("Use smooth rotation instead of instant")
        .defaultValue(true)
        .build());

    private final Setting<Integer> rotationSpeed = sgMovement.add(new IntSetting.Builder()
        .name("rotation-speed")
        .description("Time in ticks to complete rotation (higher = slower/smoother)")
        .defaultValue(10)
        .min(1)
        .max(60)
        .sliderMax(60)
        .visible(smoothRotation::get)
        .build());

    private final Setting<Boolean> breakBlocks = sgMovement.add(new BoolSetting.Builder()
        .name("break-blocks")
        .description("Break blocks that are in the way")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> sprint = sgMovement.add(new BoolSetting.Builder()
        .name("sprint")
        .description("Sprint while moving forward")
        .defaultValue(true)
        .build());

    // Render Settings
    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("render-path")
        .description("Render the calculated path")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the path line")
        .defaultValue(new Color(0, 255, 0, 255))
        .build());

    private final Setting<SettingColor> waypointColor = sgRender.add(new ColorSetting.Builder()
        .name("waypoint-color")
        .description("Color of waypoint markers")
        .defaultValue(new Color(255, 255, 0, 150))
        .build());

    private final Setting<SettingColor> currentWaypointColor = sgRender.add(new ColorSetting.Builder()
        .name("current-waypoint-color")
        .description("Color of the current target waypoint")
        .defaultValue(new Color(255, 0, 0, 200))
        .build());

    private final Setting<SettingColor> nextWaypointColor = sgRender.add(new ColorSetting.Builder()
        .name("next-waypoint-color")
        .description("Color of the next waypoint after current")
        .defaultValue(new Color(255, 165, 0, 180))
        .build());

    private final Setting<Double> waypointSize = sgRender.add(new DoubleSetting.Builder()
        .name("waypoint-size")
        .description("Size of waypoint markers")
        .defaultValue(0.3)
        .min(0.1)
        .max(1.0)
        .sliderMax(1.0)
        .build());

    private final Setting<Double> lineWidth = sgRender.add(new DoubleSetting.Builder()
        .name("line-width")
        .description("Width of the path line")
        .defaultValue(2.0)
        .min(0.5)
        .max(5.0)
        .sliderMax(5.0)
        .build());

    // Path executor
    private final PathExecutor pathExecutor = new PathExecutor();

    public enum NodeCalculation {
        EveryBlock("Every Block", 1),
        Every2Blocks("Every 2 Blocks", 2),
        Every3Blocks("Every 3 Blocks", 3);

        private final String name;
        private final int interval;

        NodeCalculation(String name, int interval) {
            this.name = name;
            this.interval = interval;
        }

        public int getInterval() {
            return interval;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public Pathfinding() {
        super(Main.CATEGORY, "pathfinding", "Advanced A* pathfinding with smooth human-like movement");
    }

    @Override
    public void onActivate() {
        info("Pathfinding visualization enabled");

        // Set path from command if available
        Path commandPath = PathCommand.getCurrentPath();
        if (commandPath != null && autoExecute.get()) {
            pathExecutor.setPath(commandPath);
            info("Executing path with " + commandPath.getLength() + " waypoints");
        }
    }

    @Override
    public void onDeactivate() {
        info("Pathfinding visualization disabled");
        pathExecutor.clearPath();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Update executor settings
        pathExecutor.setWaypointCheckpointDistance(waypointCheckpointDistance.get());
        pathExecutor.setWaypointFinalDistance(waypointFinalDistance.get());
        pathExecutor.setSmoothRotation(smoothRotation.get(), rotationSpeed.get());
        pathExecutor.setBreakBlocks(breakBlocks.get());
        pathExecutor.setSprint(sprint.get());
        pathExecutor.setNodeInterval(nodeCalculation.get().getInterval());

        // Check if we should start executing a new path from command
        Path commandPath = PathCommand.getCurrentPath();
        if (autoExecute.get() && commandPath != null && pathExecutor.getPath() != commandPath) {
            pathExecutor.setPath(commandPath);
            info("Started executing new path");
        }

        // Execute path movement
        if (autoExecute.get() && pathExecutor.isExecuting()) {
            pathExecutor.tick();

            // Check if path just completed
            if (!pathExecutor.isExecuting() && commandPath != null && commandPath.isComplete()) {
                info("Path execution complete!");
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderPath.get()) return;

        Path path = PathCommand.getCurrentPath();
        if (path == null) return;

        var waypoints = path.getWaypoints();
        if (waypoints.isEmpty()) return;

        // Render lines between waypoints
        for (int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos current = waypoints.get(i);
            BlockPos next = waypoints.get(i + 1);

            Vec3d currentVec = Vec3d.ofCenter(current);
            Vec3d nextVec = Vec3d.ofCenter(next);

            event.renderer.line(
                currentVec.x, currentVec.y, currentVec.z,
                nextVec.x, nextVec.y, nextVec.z,
                lineColor.get()
            );
        }

        // Render waypoint markers - render INSIDE blocks at foot level
        BlockPos currentWaypoint = path.getCurrentWaypoint();
        BlockPos nextWaypoint = path.getNextWaypoint();

        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos pos = waypoints.get(i);

            // Determine color based on waypoint type
            Color color;
            if (pos.equals(currentWaypoint)) {
                color = currentWaypointColor.get();
            } else if (pos.equals(nextWaypoint)) {
                color = nextWaypointColor.get();
            } else {
                color = waypointColor.get();
            }

            double size = waypointSize.get();

            // Render at the BOTTOM of the block (foot level) - from Y to Y+0.1
            Box box = new Box(
                pos.getX() + 0.5 - size / 2,
                pos.getY(), // Start at bottom of block
                pos.getZ() + 0.5 - size / 2,
                pos.getX() + 0.5 + size / 2,
                pos.getY() + 0.1, // Very thin layer at bottom
                pos.getZ() + 0.5 + size / 2
            );

            event.renderer.box(box, color, color, ShapeMode.Both, 0);
        }

        // Render goal marker (larger)
        BlockPos goal = path.getGoal();
        if (goal != null) {
            double goalSize = waypointSize.get() * 1.5;
            Box goalBox = new Box(
                goal.getX() + 0.5 - goalSize / 2,
                goal.getY() + 0.5 - goalSize / 2,
                goal.getZ() + 0.5 - goalSize / 2,
                goal.getX() + 0.5 + goalSize / 2,
                goal.getY() + 0.5 + goalSize / 2,
                goal.getZ() + 0.5 + goalSize / 2
            );

            event.renderer.box(goalBox, lineColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }

        // Render current position marker
        if (mc.player != null && pathExecutor.isExecuting()) {
            BlockPos playerPos = mc.player.getBlockPos();
            double playerSize = waypointSize.get() * 0.8;
            Box playerBox = new Box(
                playerPos.getX() + 0.5 - playerSize / 2,
                playerPos.getY() + 0.5 - playerSize / 2,
                playerPos.getZ() + 0.5 - playerSize / 2,
                playerPos.getX() + 0.5 + playerSize / 2,
                playerPos.getY() + 0.5 + playerSize / 2,
                playerPos.getZ() + 0.5 + playerSize / 2
            );

            Color playerColor = new Color(0, 150, 255, 200);
            event.renderer.box(playerBox, playerColor, playerColor, ShapeMode.Both, 0);
        }
    }

    /**
     * Get the path executor instance
     */
    public PathExecutor getPathExecutor() {
        return pathExecutor;
    }
}
