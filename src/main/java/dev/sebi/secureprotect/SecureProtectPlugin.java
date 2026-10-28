package dev.sebi.secureprotect;

import dev.sebi.secureprotect.command.SpCommand;
import dev.sebi.secureprotect.config.Settings;
import dev.sebi.secureprotect.inspect.InspectService;
import dev.sebi.secureprotect.listener.BlockListener;
import dev.sebi.secureprotect.listener.ContainerListener;
import dev.sebi.secureprotect.listener.Recorder;
import dev.sebi.secureprotect.listener.WandListener;
import dev.sebi.secureprotect.storage.Database;
import dev.sebi.secureprotect.storage.Storage;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class SecureProtectPlugin extends JavaPlugin {
    private volatile Settings settings;
    private Storage storage;
    private InspectService inspector;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());

        try {
            getDataFolder().mkdirs();
            Database db = new Database(getDataFolder().toPath().resolve("history.db"), settings.maxItemDataBytes());
            storage = new Storage(db, getLogger(), settings.queueLimit(),
                    () -> settings.retentionDays(), () -> settings.maxSizeBytes());
        } catch (SQLException ex) {
            getLogger().severe("Could not open the history database: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        storage.start(settings.flushIntervalMs());
        inspector = new InspectService(this);

        Recorder recorder = new Recorder(this);
        getServer().getPluginManager().registerEvents(new BlockListener(this, recorder), this);
        getServer().getPluginManager().registerEvents(new ContainerListener(recorder), this);
        getServer().getPluginManager().registerEvents(new WandListener(this), this);

        SpCommand command = new SpCommand(this);
        getCommand("sp").setExecutor(command);
        getCommand("sp").setTabCompleter(command);
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            storage.close();
        }
    }

    public void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
    }

    public Settings settings() {
        return settings;
    }

    public Storage storage() {
        return storage;
    }

    public InspectService inspector() {
        return inspector;
    }
}
