package common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;

/** Provisions explicitly supplied build dependencies into an isolated Maven repository. */
final class BuildLibrarySupport {
    private static final int MAX_REDIRECTS = 5;

    private BuildLibrarySupport() {
    }

    static Provisioned prepare(EntryOptions options, Path root) throws IOException {
        List<Specification> specifications = parse(options);
        if (specifications.isEmpty()) {
            return Provisioned.empty();
        }
        if (root == null) {
            throw new IOException("Build-library workspace is missing");
        }

        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path repository = normalizedRoot.resolve("repository");
        try {
            Files.createDirectories(repository);
            for (Specification specification : specifications) {
                provision(repository, specification);
            }
            Path initScript = normalizedRoot.resolve("aup-build-libraries.gradle");
            writeGradleInitScript(initScript, repository);
            return new Provisioned(normalizedRoot, repository, initScript, specifications.size());
        } catch (IOException failure) {
            deleteRecursively(normalizedRoot);
            throw failure;
        } catch (RuntimeException failure) {
            deleteRecursively(normalizedRoot);
            throw new IOException("Unable to provision build libraries", failure);
        }
    }

    static List<Specification> parse(EntryOptions options) throws IOException {
        if (options == null) {
            return Collections.emptyList();
        }
        List<String> declarations = new ArrayList<String>();
        addAll(declarations, options.queryParams.get("buildlib"));
        addAll(declarations, options.queryParams.get("library"));
        if (declarations.isEmpty()) {
            return Collections.emptyList();
        }

        List<Specification> result = new ArrayList<Specification>();
        Set<String> coordinates = new HashSet<String>();
        for (int index = 0; index < declarations.size(); index++) {
            String declaration = declarations.get(index);
            int equals = declaration == null ? -1 : declaration.indexOf('=');
            if (equals <= 0 || equals == declaration.length() - 1) {
                throw invalidDeclaration(index);
            }
            String coordinateText = declaration.substring(0, equals).trim();
            String sourceText = declaration.substring(equals + 1).trim();
            String[] coordinate = coordinateText.split(":", -1);
            if (coordinate.length != 3
                    || !validGroup(coordinate[0])
                    || !validCoordinatePart(coordinate[1])
                    || !validCoordinatePart(coordinate[2])) {
                throw invalidDeclaration(index);
            }

            URI source = parseSource(sourceText, index);
            String identity = coordinate[0] + ':' + coordinate[1] + ':' + coordinate[2];
            if (!coordinates.add(identity)) {
                throw new IOException("Duplicate build-library coordinates in declaration #" + (index + 1));
            }
            result.add(new Specification(coordinate[0], coordinate[1], coordinate[2], source));
        }
        return Collections.unmodifiableList(result);
    }

    private static void addAll(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }

    private static IOException invalidDeclaration(int zeroBasedIndex) {
        return new IOException("Invalid build-library declaration #" + (zeroBasedIndex + 1)
                + "; expected group:artifact:version=<http(s)-or-file-JAR-URI>");
    }

    private static URI parseSource(String value, int declarationIndex) throws IOException {
        final URI source;
        try {
            source = new URI(value);
        } catch (URISyntaxException failure) {
            throw invalidDeclaration(declarationIndex);
        }
        String scheme = source.getScheme();
        if (scheme == null) {
            throw invalidDeclaration(declarationIndex);
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme)
                && !"https".equals(normalizedScheme)
                && !"file".equals(normalizedScheme)) {
            throw invalidDeclaration(declarationIndex);
        }
        if (("http".equals(normalizedScheme) || "https".equals(normalizedScheme))
                && (source.getHost() == null || source.getUserInfo() != null)) {
            throw invalidDeclaration(declarationIndex);
        }
        if ("file".equals(normalizedScheme)
                && (source.getQuery() != null || source.getFragment() != null)) {
            throw invalidDeclaration(declarationIndex);
        }
        String path = source.getPath();
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw invalidDeclaration(declarationIndex);
        }
        return source;
    }

    private static boolean validGroup(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        for (String part : parts) {
            if (!validCoordinatePart(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validCoordinatePart(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.+\\-]*");
    }

    private static void provision(Path repository, Specification specification) throws IOException {
        String groupPath = specification.group.replace('.', '/');
        Path artifactDirectory = repository.resolve(groupPath)
                .resolve(specification.artifact)
                .resolve(specification.version)
                .normalize();
        if (!artifactDirectory.startsWith(repository)) {
            throw new IOException("Invalid build-library repository path");
        }
        Files.createDirectories(artifactDirectory);
        String baseName = specification.artifact + '-' + specification.version;
        Path jar = artifactDirectory.resolve(baseName + ".jar");
        Path partial = artifactDirectory.resolve(baseName + ".jar.part");
        try {
            copySource(specification.source, partial);
            verifyJar(partial);
            Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
            writePom(artifactDirectory.resolve(baseName + ".pom"), specification);
        } catch (IOException failure) {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(jar);
            throw new IOException("Unable to provision build library " + specification.coordinate(), failure);
        }
    }

    private static void copySource(URI source, Path destination) throws IOException {
        if ("file".equalsIgnoreCase(source.getScheme())) {
            final Path sourcePath;
            try {
                sourcePath = Paths.get(source);
            } catch (RuntimeException failure) {
                throw new IOException("Invalid local build-library source", failure);
            }
            if (!Files.isRegularFile(sourcePath)) {
                throw new IOException("Local build-library source is not a regular file");
            }
            Files.copy(sourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        download(source, destination);
    }

    private static void download(URI source, Path destination) throws IOException {
        URI current = source;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current.toASCIIString()).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
            connection.setReadTimeout(Math.max(1000, UpdateOptions.readTimeoutMs));
            connection.setRequestProperty("Accept", "application/java-archive, application/octet-stream;q=0.9, */*;q=0.1");
            connection.setRequestProperty("User-Agent", "AutoUpdatePlugins/1.0");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == MAX_REDIRECTS) {
                        throw new IOException("Build-library download redirect failed");
                    }
                    URI redirected = current.resolve(location);
                    String scheme = redirected.getScheme();
                    if (scheme == null
                            || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                            || redirected.getHost() == null
                            || redirected.getUserInfo() != null
                            || ("https".equalsIgnoreCase(current.getScheme())
                            && "http".equalsIgnoreCase(scheme))) {
                        throw new IOException("Build-library download redirected to an unsupported source");
                    }
                    current = redirected;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Build-library download returned HTTP " + status);
                }
                try (InputStream input = connection.getInputStream();
                     OutputStream output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Build-library download exceeded redirect limit");
    }

    private static void verifyJar(Path file) throws IOException {
        try (JarFile ignored = new JarFile(file.toFile())) {
            // Opening JarFile verifies that the supplied payload is a ZIP/JAR archive.
        } catch (IOException failure) {
            throw new IOException("Build-library source is not a valid JAR", failure);
        }
    }

    private static void writePom(Path pom, Specification specification) throws IOException {
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
                + "https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>" + specification.group + "</groupId>\n"
                + "  <artifactId>" + specification.artifact + "</artifactId>\n"
                + "  <version>" + specification.version + "</version>\n"
                + "</project>\n";
        Files.write(pom, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeGradleInitScript(Path script, Path repository) throws IOException {
        String repositoryUri = repository.toUri().toASCIIString()
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String content = "allprojects {\n"
                + "    repositories {\n"
                + "        maven { url = uri('" + repositoryUri + "') }\n"
                + "    }\n"
                + "}\n";
        Files.write(script, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            List<Path> paths = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                stream.forEach(paths::add);
            }
            Collections.sort(paths, new Comparator<Path>() {
                @Override
                public int compare(Path left, Path right) {
                    return right.compareTo(left);
                }
            });
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    static final class Specification {
        final String group;
        final String artifact;
        final String version;
        final URI source;

        private Specification(String group, String artifact, String version, URI source) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.source = source;
        }

        String coordinate() {
            return group + ':' + artifact + ':' + version;
        }
    }

    static final class Provisioned implements AutoCloseable {
        private static final Provisioned EMPTY = new Provisioned(null, null, null, 0);

        final Path root;
        final Path repository;
        final Path gradleInitScript;
        final int count;

        private Provisioned(Path root, Path repository, Path gradleInitScript, int count) {
            this.root = root;
            this.repository = repository;
            this.gradleInitScript = gradleInitScript;
            this.count = count;
        }

        static Provisioned empty() {
            return EMPTY;
        }

        boolean hasLibraries() {
            return count > 0;
        }

        List<String> mavenArguments(List<String> base) {
            if (!hasLibraries()) {
                return base;
            }
            List<String> result = new ArrayList<String>(base.size() + 1);
            int goal = Math.max(0, base.size() - 1);
            result.addAll(base.subList(0, goal));
            result.add("-Dmaven.repo.local=" + repository.toAbsolutePath());
            result.addAll(base.subList(goal, base.size()));
            return result;
        }

        List<String> gradleArguments(List<String> base) {
            if (!hasLibraries()) {
                return base;
            }
            List<String> result = new ArrayList<String>(base.size() + 2);
            result.add("--init-script");
            result.add(gradleInitScript.toAbsolutePath().toString());
            result.addAll(base);
            return result;
        }

        @Override
        public void close() {
            deleteRecursively(root);
        }
    }
}
