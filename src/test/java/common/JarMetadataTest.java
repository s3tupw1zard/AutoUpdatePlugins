package common;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;

public class JarMetadataTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void readsBukkitMetadataAndHashes() throws Exception {
        Path jar = createJar("plugin.yml", "name: Example\nversion: 2.3.1\n");
        JarMetadata metadata = JarMetadata.read(jar);
        assertEquals("Example", metadata.name);
        assertEquals("2.3.1", metadata.version);
        assertEquals(Files.size(jar), metadata.size);
        assertEquals(40, metadata.sha1.length());
        assertEquals(64, metadata.sha256.length());
        assertEquals(32, metadata.md5.length());
    }

    @Test
    public void readsVelocityMetadata() throws Exception {
        Path jar = createJar("velocity-plugin.json", "{\"id\":\"sample\",\"name\":\"Sample\",\"version\":\"1.4.0\"}");
        JarMetadata metadata = JarMetadata.read(jar);
        assertEquals("sample", metadata.id);
        assertEquals("Sample", metadata.name);
        assertEquals("1.4.0", metadata.version);
    }

    private Path createJar(String entry, String contents) throws IOException {
        Path jar = temporary.newFile("plugin-" + System.nanoTime() + ".jar").toPath();
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry(entry));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
