package com.inferno;

import com.inferno.cli.CdfCli;
import com.inferno.config.ConfigManager;
import com.inferno.database.CdfRepository;

public final class Main {
    private Main() {
    }

    static {
        System.setProperty("org.sqlite.disableNativeLoading", "true");
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(String[] args) {
        try (CdfRepository repository = new CdfRepository()) {
            ConfigManager configManager = new ConfigManager();
            configManager.ensureLayout();
            repository.open();
            configManager.syncRoots(repository);
            return new CdfCli(repository, configManager).execute(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }
}
