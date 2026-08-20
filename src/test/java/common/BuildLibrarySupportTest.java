package common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BuildLibrarySupportTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void provisionsRepeatedFileAndHttpLibrariesAndBuildArguments() throws Exception {
        Path localJar = temporary.newFile("local-library.jar").toPath();
        writeJar(localJar, "local.txt", "local dependency");
        Path remoteJar = temporary.newFile("remote-library.jar").toPath();
        writeJar(remoteJar, "remote.txt", "remote dependency");

        HttpServer server = serveJar(remoteJar);
        BuildLibrarySupport.Provisioned provisioned = null;
        Path root = temporary.getRoot().toPath().resolve("provisioned-secret-free");
        String secret = "signed-value-must-not-leak";
        try {
            String remote = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/redirect.jar?token=" + secret;
            String source = "https://github.com/example/project?autobuild=true"
                    + "&buildLib=" + encoded("com.example:local-lib:1.2.3=" + localJar.toUri())
                    + "&library=" + encoded("org.demo:remote-lib:2.0=" + remote);
            EntryOptions options = EntryOptions.parse(source, null);

            provisioned = BuildLibrarySupport.prepare(options, root);

            assertTrue(provisioned.hasLibraries());
            assertEquals(2, provisioned.count);
            Path localArtifact = provisioned.repository.resolve(
                    "com/example/local-lib/1.2.3/local-lib-1.2.3.jar");
            Path remoteArtifact = provisioned.repository.resolve(
                    "org/demo/remote-lib/2.0/remote-lib-2.0.jar");
            assertJarEntry(localArtifact, "local.txt", "local dependency");
            assertJarEntry(remoteArtifact, "remote.txt", "remote dependency");

            String pom = new String(Files.readAllBytes(provisioned.repository.resolve(
                    "com/example/local-lib/1.2.3/local-lib-1.2.3.pom")), StandardCharsets.UTF_8);
            assertTrue(pom.contains("<groupId>com.example</groupId>"));
            assertTrue(pom.contains("<artifactId>local-lib</artifactId>"));
            assertTrue(pom.contains("<version>1.2.3</version>"));

            String init = new String(Files.readAllBytes(provisioned.gradleInitScript), StandardCharsets.UTF_8);
            assertTrue(init.contains("maven { url = uri('"));
            assertTrue(init.contains(provisioned.repository.toUri().toASCIIString()));
            assertFalse(init.contains(secret));

            List<String> maven = provisioned.mavenArguments(
                    Arrays.asList("-q", "-DskipTests", "package"));
            assertEquals("-Dmaven.repo.local=" + provisioned.repository.toAbsolutePath(), maven.get(2));
            assertEquals("package", maven.get(3));
            assertFalse(join(maven).contains(secret));

            List<String> gradle = provisioned.gradleArguments(Arrays.asList("build", "-x", "test"));
            assertEquals("--init-script", gradle.get(0));
            assertEquals(provisioned.gradleInitScript.toAbsolutePath().toString(), gradle.get(1));
            assertEquals("build", gradle.get(2));
            assertFalse(join(gradle).contains(secret));
        } finally {
            if (provisioned != null) {
                provisioned.close();
            }
            server.stop(0);
        }
        assertFalse(Files.exists(root));
    }

    @Test
    public void noLibraryConfigurationDoesNotCreateFilesOrChangeArguments() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("unused");
        EntryOptions options = EntryOptions.parse(
                "https://github.com/example/project?autobuild=true&branch=main", null);
        BuildLibrarySupport.Provisioned provisioned = BuildLibrarySupport.prepare(options, root);
        List<String> maven = Arrays.asList("-q", "-DskipTests", "package");
        List<String> gradle = Arrays.asList("build", "-x", "test");

        assertFalse(provisioned.hasLibraries());
        assertSame(maven, provisioned.mavenArguments(maven));
        assertSame(gradle, provisioned.gradleArguments(gradle));
        assertFalse(Files.exists(root));
    }

    @Test
    public void rejectsUnsafeCoordinatesSourcesAndDuplicatesWithoutLeakingValues() throws Exception {
        Path jar = temporary.newFile("valid.jar").toPath();
        writeJar(jar, "entry", "content");
        assertRejected("buildLib=" + encoded("../escape:artifact:1=" + jar.toUri()), "escape");
        assertRejected("buildLib=" + encoded("com.example:artifact:1=https://example.test/not-a-jar"),
                "not-a-jar");
        assertRejected("buildLib=" + encoded("com.example:artifact:1=ftp://example.test/library.jar"),
                "ftp://");
        assertRejected("buildLib=" + encoded(
                "com.example:artifact:1=https://signed-value@example.test/library.jar"),
                "signed-value");
        assertRejected("buildLib=" + encoded("com.example:artifact:1=" + jar.toUri())
                        + "&library=" + encoded("com.example:artifact:1=" + jar.toUri()),
                jar.toString());
    }

    @Test
    public void rejectsNonJarPayloadAndRemovesPartialRepository() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fake.jar", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = "this is not a jar".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        });
        server.start();
        Path root = temporary.getRoot().toPath().resolve("invalid-payload");
        try {
            String remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/fake.jar";
            EntryOptions options = EntryOptions.parse("https://github.com/example/project?buildLib="
                    + encoded("com.example:fake:1=" + remote), null);
            try {
                BuildLibrarySupport.prepare(options, root);
                fail("Expected invalid JAR payload to be rejected");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().contains(remote));
            }
            assertFalse(Files.exists(root));
        } finally {
            server.stop(0);
        }
    }

    private void assertRejected(String query, String sensitiveValue) throws Exception {
        EntryOptions options = EntryOptions.parse(
                "https://github.com/example/project?autobuild=true&" + query, null);
        Path root = temporary.getRoot().toPath().resolve("rejected-" + System.nanoTime());
        try {
            BuildLibrarySupport.prepare(options, root);
            fail("Expected build-library declaration to be rejected");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
            assertFalse(expected.getMessage().contains(sensitiveValue));
        }
        assertFalse(Files.exists(root));
    }

    private static HttpServer serveJar(Path jar) throws IOException {
        final byte[] bytes = Files.readAllBytes(jar);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect.jar", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String query = exchange.getRequestURI().getRawQuery();
                exchange.getResponseHeaders().set("Location", "/remote.jar"
                        + (query == null ? "" : "?" + query));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        server.createContext("/remote.jar", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            }
        });
        server.start();
        return server;
    }

    private static void writeJar(Path path, String entryName, String content) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void assertJarEntry(Path jar, String entryName, String expected) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(entryName);
            assertNotNull(entry);
            try (InputStream input = file.getInputStream(entry)) {
                assertEquals(expected, new String(readAll(input), StandardCharsets.UTF_8));
            }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String encoded(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value).append('\n');
        }
        return result.toString();
    }
}
