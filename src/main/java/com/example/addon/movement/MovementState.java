package com.example.addon.movement;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Tracks the current movement state during path execution
 */
public class MovementState {
    public enum Action {
        WALKING,
        JUMPING,
        FALLING,
        SPRINTING,
        SNEAKING,
        IDLE
    }

    private Action currentAction;
    private BlockPos targetPosition;
    private Vec3d targetVector;
    private boolean needsJump;
    private int ticksInCurrentAction;

    public MovementState() {
        this.currentAction = Action.IDLE;
        this.targetPosition = null;
        this.targetVector = null;
        this.needsJump = false;
        this.ticksInCurrentAction = 0;
    }

    public void setAction(Action action) {
        if (this.currentAction != action) {
            this.currentAction = action;
            this.ticksInCurrentAction = 0;
        }
    }

    public Action getCurrentAction() {
        return currentAction;
    }

    public void setTargetPosition(BlockPos pos) {
        this.targetPosition = pos;
        if (pos != null) {
            this.targetVector = Vec3d.ofCenter(pos);
        } else {
            this.targetVector = null;
        }
    }

    public BlockPos getTargetPosition() {
        return targetPosition;
    }

    public Vec3d getTargetVector() {
        return targetVector;
    }

    public void setNeedsJump(boolean needsJump) {
        this.needsJump = needsJump;
    }

    public boolean needsJump() {
        return needsJump;
    }

    public void tick() {
        ticksInCurrentAction++;
    }

    public int getTicksInCurrentAction() {
        return ticksInCurrentAction;
    }

    public void reset() {
        this.currentAction = Action.IDLE;
        this.targetPosition = null;
        this.targetVector = null;
        this.needsJump = false;
        this.ticksInCurrentAction = 0;
    }
}
