package com.cmcplugins.quakerun;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Short-lived server-side movement goal for wild, ground-navigating animals. */
final class WildlifePanic implements AutoCloseable {
    private static final int RADIUS = 16;
    private static final int MAX_ANIMALS_PER_PLAYER = 32;
    private static final int MAX_ACTIVE_GOALS = 512;
    private final Map<UUID, Active> active = new HashMap<>();

    void startAround(ServerPlayer player, long remainingMs) {
        if (remainingMs <= 0) return;
        long until = System.nanoTime() + remainingMs * 1_000_000L;
        int count = 0;
        for (Animal animal : player.serverLevel().getEntitiesOfClass(Animal.class,
                player.getBoundingBox().inflate(RADIUS, 8, RADIUS), WildlifePanic::eligible)) {
            if (count++ >= MAX_ANIMALS_PER_PLAYER) break;
            Active existing = active.get(animal.getUUID());
            if (existing != null) {
                existing.goal.extend(until);
            } else if (active.size() < MAX_ACTIVE_GOALS) {
                PanicGoal goal = new PanicGoal(animal, until);
                animal.goalSelector.addGoal(1, goal);
                active.put(animal.getUUID(), new Active(animal, goal));
            }
        }
    }

    void tick() {
        active.entrySet().removeIf(entry -> {
            Active state = entry.getValue();
            if (state.goal.active()) return false;
            state.animal.goalSelector.removeGoal(state.goal);
            return true;
        });
    }

    private static boolean eligible(Animal animal) {
        return animal.isAlive() && !animal.isLeashed() && !animal.isVehicle()
                && !animal.isPassenger()
                && animal.getNavigation() instanceof GroundPathNavigation
                && !(animal instanceof TamableAnimal tameable && tameable.isTame())
                && !(animal instanceof AbstractHorse horse && horse.isTamed());
    }

    @Override public void close() {
        for (Active state : active.values()) state.animal.goalSelector.removeGoal(state.goal);
        active.clear();
    }

    private record Active(Animal animal, PanicGoal goal) {}

    private static final class PanicGoal extends Goal {
        private final Animal animal;
        private long until;
        private int nextDirection;

        PanicGoal(Animal animal, long until) {
            this.animal = animal;
            this.until = until;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        void extend(long deadline) { until = Math.max(until, deadline); }
        boolean active() { return animal.isAlive() && System.nanoTime() < until; }

        @Override public boolean canUse() { return active(); }
        @Override public boolean canContinueToUse() { return active(); }
        @Override public void start() { chooseDirection(); }
        @Override public void tick() {
            if (--nextDirection <= 0 || animal.getNavigation().isDone()) chooseDirection();
        }
        @Override public void stop() { animal.getNavigation().stop(); }

        private void chooseDirection() {
            nextDirection = 20;
            Vec3 destination = DefaultRandomPos.getPos(animal, 10, 4);
            if (destination != null)
                animal.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.35);
        }
    }
}
