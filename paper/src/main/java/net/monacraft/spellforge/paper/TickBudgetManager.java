package net.monacraft.spellforge.paper;

import org.bukkit.Bukkit;

final class TickBudgetManager {
    private final double minimumTps;
    private final int maxRuntimeCost;
    TickBudgetManager(double minimumTps, int maxRuntimeCost) { this.minimumTps = minimumTps; this.maxRuntimeCost = maxRuntimeCost; }
    boolean canStart(int runtimeCost) {
        double[] tps = Bukkit.getTPS();
        return runtimeCost <= maxRuntimeCost && (tps.length == 0 || tps[0] >= minimumTps);
    }
}
