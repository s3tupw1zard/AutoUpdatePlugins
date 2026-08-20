package common;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ResourceConfigurationTest {
    @Test
    public void shippedConfigContainsNewProviderAndPolicySections() throws Exception {
        Map<String, Object> config = yaml("config.yml");
        Map<String, Object> updates = section(config, "updates");
        assertTrue(updates.containsKey("githubTokens"));
        assertTrue(updates.containsKey("gitlabTokens"));
        assertTrue(updates.containsKey("voxelShopTokens"));
        assertTrue(section(config, "metadata").containsKey("skipDownloadWhenUnchanged"));
        assertTrue(section(config, "compatibility").containsKey("modrinthMinecraftVersionCheck"));
        assertTrue(section(config, "versioning").containsKey("policy"));
        assertTrue(section(section(config, "logging"), "updates").containsKey("filePattern"));
    }

    @Test
    public void backendDescriptorKeepsAupManagementCommand() throws Exception {
        Map<String, Object> plugin = yaml("plugin.yml");
        assertTrue(section(plugin, "commands").containsKey("aup"));
    }

    @Test
    public void bungeeDescriptorUsesProxyOnlyManagementName() throws Exception {
        Map<String, Object> bungee = yaml("bungee.yml");
        Map<String, Object> commands = section(bungee, "commands");
        assertTrue(commands.containsKey("baup"));
        assertFalse(commands.containsKey("aup"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String name) throws Exception {
        InputStream input = ResourceConfigurationTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("Missing resource " + name, input);
        try (InputStream closeable = input) {
            Object value = new Yaml().load(closeable);
            assertTrue("Expected mapping in " + name, value instanceof Map);
            return (Map<String, Object>) value;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue("Missing mapping " + key, value instanceof Map);
        return (Map<String, Object>) value;
    }
}
