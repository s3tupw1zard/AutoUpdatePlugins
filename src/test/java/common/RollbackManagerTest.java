package common;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;

public class RollbackManagerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private boolean enabled;
    private String rollbackPath;
    private List<String> filters;

    @Before
    public void rememberOptions() {
        enabled = UpdateOptions.rollbackEnabled;
        rollbackPath = UpdateOptions.rollbackPath;
        filters = new ArrayList<String>(UpdateOptions.rollbackFilters);
    }

    @After
    public void restoreOptions() {
        UpdateOptions.rollbackEnabled = enabled;
        UpdateOptions.rollbackPath = rollbackPath;
        UpdateOptions.rollbackFilters.clear();
        UpdateOptions.rollbackFilters.addAll(filters);
        RollbackManager.setRollbackListener(null);
        RollbackManager.refreshConfiguration(logger());
    }

    @Test
    public void stagedInstallBacksUpExplicitVersionedLiveJar() throws Exception {
        Path root = temporaryFolder.newFolder("rollback").toPath();
        Path active = root.resolve("plugins/Example-1.2.jar");
        Path staged = root.resolve("plugins/update/Example.jar");
        Files.createDirectories(active.getParent());
        Files.createDirectories(staged.getParent());
        byte[] oldJar = "old-version".getBytes(StandardCharsets.UTF_8);
        byte[] newJar = "new-version".getBytes(StandardCharsets.UTF_8);
        Files.write(active, oldJar);

        UpdateOptions.rollbackEnabled = true;
        UpdateOptions.rollbackPath = root.resolve("backups").toString();
        UpdateOptions.rollbackFilters.clear();
        UpdateOptions.rollbackFilters.add("Could not load plugin");
        RollbackManager.refreshConfiguration(logger());
        RollbackManager.prepareBackup(logger(), "Example", staged, active);
        Files.write(staged, newJar);
        RollbackManager.markInstalled("Example", staged);

        RollbackManager.handleLogLine(logger(), "paper", "Could not load plugin Example");

        assertArrayEquals(oldJar, Files.readAllBytes(active));
        assertArrayEquals(oldJar, Files.readAllBytes(staged));
    }

    private static Logger logger() {
        Logger logger = Logger.getLogger("RollbackManagerTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        return logger;
    }
}
