package at.esoren.vystormchat;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * Message costs. The Vault types live only in {@link Vault}, which is loaded when a Vault economy exists; without
 * Vault every channel is free and no Vault class is ever touched.
 */
interface Money {
    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    String format(double amount);

    /** Vault economy if one is registered, else null. */
    static Money find(Plugin plugin) {
        var manager = plugin.getServer().getPluginManager();
        if (manager.getPlugin("Vault") == null && manager.getPlugin("VaultUnlocked") == null) return null;
        try { return Vault.create(plugin); } catch (LinkageError missing) { return null; }
    }

    /** Vault-backed implementation; only loaded when Vault is installed. */
    final class Vault implements Money {
        private final net.milkbowl.vault.economy.Economy economy;

        private Vault(net.milkbowl.vault.economy.Economy economy) { this.economy = economy; }

        static Money create(Plugin plugin) {
            var registration = plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            return registration == null ? null : new Vault(registration.getProvider());
        }

        @Override public boolean has(OfflinePlayer player, double amount) { return economy.has(player, amount); }

        @Override public boolean withdraw(OfflinePlayer player, double amount) { return economy.withdrawPlayer(player, amount).transactionSuccess(); }

        @Override public String format(double amount) { return economy.format(amount); }
    }
}
