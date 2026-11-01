package com.schematicdropper.drop;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

/**
 * Resolves where drops should go.
 *
 * <p>Feet mode needs no target. Looked-at mode raycasts from the camera and only
 * accepts a living entity, so drops are never thrown at a wall or an item frame
 * by accident.
 *
 * <p>When no valid target is found the manager reports that plainly. It never
 * silently falls back to another mode or another entity: the user asked for one
 * thing, and a quiet substitution would scatter materials somewhere unexpected.
 */
public final class DropTargetManager {
    /** How a drop is aimed. */
    public enum Mode {
        FEET("Feet"),
        LOOKED_AT_ENTITY("Looked-at entity");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Why targeting failed, so the module can explain itself. */
    public enum Status {
        /** A usable target is available. */
        OK,
        /** Looked-at mode is on but nothing valid is in range. */
        NO_TARGET,
        /** The player or world is not ready. */
        NOT_READY
    }

    /** The current aim. */
    public record Target(Status status, Entity entity, Vec3d hitPosition, double distance) {
        public boolean isOk() {
            return status == Status.OK;
        }

        /** A short description for the HUD. */
        public String describe() {
            return switch (status) {
                case OK -> entity == null ? "Feet" : entity.getName().getString();
                case NO_TARGET -> "No target";
                case NOT_READY -> "Not ready";
            };
        }

        static Target notReady() {
            return new Target(Status.NOT_READY, null, null, 0);
        }

        static Target none() {
            return new Target(Status.NO_TARGET, null, null, 0);
        }
    }

    private final MinecraftClient mc;

    /** When set, targeting keeps returning this entity even if the crosshair moves. */
    private Entity locked;

    public DropTargetManager(MinecraftClient mc) {
        this.mc = mc;
    }

    /** Locks onto an entity, or clears the lock when given {@code null}. */
    public void lock(Entity entity) {
        this.locked = entity;
    }

    public void clearLock() {
        this.locked = null;
    }

    public boolean isLocked() {
        return locked != null;
    }

    /**
     * Resolves the current target for {@code mode}.
     *
     * @param range the maximum targeting distance, in blocks
     */
    public Target resolve(Mode mode, double range) {
        if (mc.player == null || mc.world == null) return Target.notReady();

        if (mode == Mode.FEET) {
            return new Target(Status.OK, null, mc.player.getPos(), 0);
        }

        // A lock outlives the raycast, but only while the entity stays valid.
        if (locked != null) {
            if (!isUsable(locked)) {
                locked = null;
            } else {
                double distance = mc.player.getPos().distanceTo(locked.getPos());
                if (distance <= range) {
                    return new Target(Status.OK, locked, locked.getPos(), distance);
                }
                // Locked but out of range: report the miss rather than snapping away.
                return Target.none();
            }
        }

        Entity found = raycastForEntity(range);
        if (found == null) return Target.none();

        return new Target(Status.OK, found, found.getPos(), mc.player.getPos().distanceTo(found.getPos()));
    }

    /**
     * Casts from the camera along the view vector and returns the first living
     * entity hit within {@code range}.
     */
    private Entity raycastForEntity(double range) {
        if (mc.player == null || mc.world == null) return null;

        Vec3d start = mc.player.getCameraPosVec(1.0f);
        Vec3d direction = mc.player.getRotationVec(1.0f);
        Vec3d end = start.add(direction.multiply(range));

        // An expanded box per entity, matching vanilla's own entity picking, so a
        // thin target is still hittable.
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!isUsable(entity)) continue;

            double margin = entity.getTargetingMargin();
            var box = entity.getBoundingBox().expand(margin);

            var hit = box.raycast(start, end);
            if (hit.isEmpty()) continue;

            double distance = start.distanceTo(hit.get());
            if (distance > range || distance >= bestDistance) continue;

            best = entity;
            bestDistance = distance;
        }

        return best;
    }

    /** An entity is targetable when it is alive, not the local player, and not a spectator. */
    private boolean isUsable(Entity entity) {
        if (entity == null || !entity.isAlive()) return false;
        if (entity == mc.player) return false;
        if (entity.isSpectator()) return false;

        // Only living entities: a drop thrown at an item frame or a boat would just
        // scatter materials on the floor next to it.
        return entity instanceof LivingEntity;
    }

    /**
     * The name of whatever is under the crosshair, for the target indicator.
     *
     * <p>Uses a plain raycast so a non-living hit (a block) is reported as such
     * rather than as a missing target.
     */
    public String describeCrosshair(double range) {
        if (mc.player == null || mc.world == null || mc.crosshairTarget == null) return "nothing";

        HitResult hit = mc.crosshairTarget;
        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            return entity.getName().getString();
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            return "block";
        }
        return "nothing";
    }
}
