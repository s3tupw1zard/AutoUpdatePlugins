package common;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class ConfigManagerReloadTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reloadMutatesTheExistingManagerReference() throws Exception {
        File directory = temporaryFolder.newFolder("config");
        File file = new File(directory, "config.yml");
        Files.write(file.toPath(), "updates:\n  key: before\n".getBytes(StandardCharsets.UTF_8));
        ConfigManager manager = new ConfigManager(directory, "config.yml");

        Files.write(file.toPath(), "updates:\n  key: after\n".getBytes(StandardCharsets.UTF_8));
        manager.reloadConfig();

        assertEquals("after", manager.getString("updates.key"));
        manager.setOption("behavior.debug", true);
        assertEquals(true, manager.getBoolean("behavior.debug"));
    }

    @Test
    public void malformedOrNonMappingYamlRecoversAsEmptyConfiguration() throws Exception {
        File directory = temporaryFolder.newFolder("malformed");
        File file = new File(directory, "config.yml");
        Files.write(file.toPath(), "updates: [unterminated\n".getBytes(StandardCharsets.UTF_8));
        ConfigManager manager = new ConfigManager(directory, "config.yml");
        assertEquals(null, manager.getOption("updates"));

        Files.write(file.toPath(), "- not\n- a\n- mapping\n".getBytes(StandardCharsets.UTF_8));
        manager.reloadConfig();
        assertEquals(null, manager.getOption("updates"));
    }
}
