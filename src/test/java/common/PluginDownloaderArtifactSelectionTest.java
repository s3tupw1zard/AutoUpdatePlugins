package common;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PluginDownloaderArtifactSelectionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String oldTempPath;
    private boolean oldRollbackEnabled;
    private boolean oldIgnoreDuplicates;

    @Before
    public void saveOptions() {
        oldTempPath = UpdateOptions.tempPath;
        oldRollbackEnabled = UpdateOptions.rollbackEnabled;
        oldIgnoreDuplicates = UpdateOptions.ignoreDuplicates;
        UpdateOptions.rollbackEnabled = false;
        UpdateOptions.ignoreDuplicates = true;
    }

    @After
    public void restoreOptions() {
        UpdateOptions.tempPath = oldTempPath;
        UpdateOptions.rollbackEnabled = oldRollbackEnabled;
        UpdateOptions.ignoreDuplicates = oldIgnoreDuplicates;
    }

    @Test
    public void descriptorBearingOuterJarIsPreservedWhenItContainsNestedDependency() throws Exception {
        Path root = temporaryFolder.newFolder("outer-plugin").toPath();
        byte[] dependency = jar(null, null, singletonEntry("library/Dependency.class", new byte[]{1, 2, 3}));
        byte[] outer = jar("plugin.yml", "name: Outer\nversion: 1.0.0\nmain: test.Outer\n",
                singletonEntry("META-INF/jars/dependency.jar", dependency));
        Path source = write(root.resolve("Outer-source.jar"), outer);
        Path plugins = root.resolve("plugins");
        UpdateOptions.tempPath = root.resolve("tmp").toString();

        TransferOutcome outcome = downloader().installLocalFileDetailed(source, "Outer",
                customPath(plugins), true);

        assertEquals(TransferOutcome.Status.APPLIED, outcome.status);
        Path installed = plugins.resolve("Outer.jar");
        assertArrayEquals(outer, Files.readAllBytes(installed));
        try (JarFile jar = new JarFile(installed.toFile())) {
            assertNotNull(jar.getJarEntry("plugin.yml"));
            assertNotNull(jar.getJarEntry("META-INF/jars/dependency.jar"));
        }
    }

    @Test
    public void genericArchiveSkipsNestedLibrariesAndExtractsDescriptorBearingPlugin() throws Exception {
        Path root = temporaryFolder.newFolder("plugin-archive").toPath();
        byte[] dependency = jar(null, null, singletonEntry("library/Dependency.class", new byte[]{4, 5, 6}));
        byte[] plugin = jar("plugin.yml", "name: Archived\nversion: 2.0.0\nmain: test.Archived\n",
                singletonEntry("test/Archived.class", new byte[]{7, 8, 9}));
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("a-dependency.jar", dependency);
        entries.put("z-plugin.jar", plugin);
        Path source = write(root.resolve("bundle.zip"), zip(entries));
        Path plugins = root.resolve("plugins");
        UpdateOptions.tempPath = root.resolve("tmp").toString();

        TransferOutcome outcome = downloader().installLocalFileDetailed(source, "Archived",
                customPath(plugins), true);

        assertEquals(TransferOutcome.Status.APPLIED, outcome.status);
        assertArrayEquals(plugin, Files.readAllBytes(plugins.resolve("Archived.jar")));
    }

    @Test
    public void genericArchiveWithoutPluginArtifactIsRejected() throws Exception {
        Path root = temporaryFolder.newFolder("library-archive").toPath();
        byte[] dependency = jar(null, null, singletonEntry("library/Dependency.class", new byte[]{1}));
        Path source = write(root.resolve("libraries.zip"),
                zip(singletonEntry("dependency.jar", dependency)));
        Path plugins = root.resolve("plugins");
        UpdateOptions.tempPath = root.resolve("tmp").toString();

        TransferOutcome outcome = downloader().installLocalFileDetailed(source, "NotAPlugin",
                customPath(plugins), true);

        assertEquals(TransferOutcome.Status.FAILED, outcome.status);
        assertFalse(Files.exists(plugins.resolve("NotAPlugin.jar")));
    }

    @Test
    public void multiModuleSelectionIgnoresLibrariesAndUsesDeterministicPluginTieBreak() throws Exception {
        Path root = temporaryFolder.newFolder("multi-module").toPath();
        write(root.resolve("target/library-all.jar"),
                jar(null, null, singletonEntry("library/Root.class", new byte[]{1})));
        Path expected = write(root.resolve("module-a/build/libs/plugin-a.jar"),
                jar("plugin.yml", "name: PluginA\nversion: 1.0.0\nmain: test.A\n", null));
        write(root.resolve("module-b/target/plugin-z.jar"),
                jar("plugin.yml", "name: PluginZ\nversion: 1.0.0\nmain: test.Z\n", null));

        File selected = downloader().pickBuiltJar(root.toFile());

        assertNotNull(selected);
        assertEquals(expected.toAbsolutePath().normalize(), selected.toPath().toAbsolutePath().normalize());
    }

    @Test
    public void sourceBuildSelectionFailsWhenNoOutputHasPluginDescriptor() throws Exception {
        Path root = temporaryFolder.newFolder("no-plugin-module").toPath();
        write(root.resolve("target/project.jar"),
                jar(null, null, singletonEntry("library/Root.class", new byte[]{1})));
        write(root.resolve("module/target/module.jar"),
                jar(null, null, singletonEntry("library/Module.class", new byte[]{2})));

        assertNull(downloader().pickBuiltJar(root.toFile()));
    }

    @Test
    public void expiredDeadlineCannotCrossAtomicInstallHandoff() throws Exception {
        Path root = temporaryFolder.newFolder("deadline-handoff").toPath();
        byte[] original = jar("plugin.yml",
                "name: Deadline\nversion: 1.0.0\nmain: test.Deadline\n", null);
        byte[] replacement = jar("plugin.yml",
                "name: Deadline\nversion: 2.0.0\nmain: test.Deadline\n", null);
        Path plugins = root.resolve("plugins");
        Path installed = write(plugins.resolve("Deadline.jar"), original);
        Path source = write(root.resolve("Deadline-source.jar"), replacement);
        UpdateOptions.tempPath = root.resolve("tmp").toString();
        PluginDownloader downloader = downloader();
        downloader.bindTransferDeadline(new AtomicInteger(PluginDownloader.DEADLINE_TIMED_OUT));

        try {
            TransferOutcome outcome = downloader.installLocalFileDetailed(source, "Deadline",
                    customPath(plugins), true);
            assertEquals(TransferOutcome.Status.FAILED, outcome.status);
            assertArrayEquals(original, Files.readAllBytes(installed));
        } finally {
            downloader.clearTransferDeadline();
        }
    }

    private PluginDownloader downloader() {
        Logger logger = Logger.getLogger("PluginDownloaderArtifactSelectionTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        return new PluginDownloader(logger);
    }

    private static String customPath(Path plugins) {
        return "filePath=" + plugins + "|useUpdateFolder=false";
    }

    private static Path write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return path;
    }

    private static byte[] jar(String descriptorName, String descriptor,
                              Map<String, byte[]> extraEntries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            if (descriptorName != null) {
                output.putNextEntry(new JarEntry(descriptorName));
                output.write(descriptor.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            if (extraEntries != null) {
                for (Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                    output.putNextEntry(new JarEntry(entry.getKey()));
                    output.write(entry.getValue());
                    output.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static LinkedHashMap<String, byte[]> singletonEntry(String name, byte[] value) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put(name, value);
        return entries;
    }
}
