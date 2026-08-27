package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.yaml.snakeyaml.Yaml;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class PluginDownloader {

    static final int DEADLINE_ACTIVE = 0;
    static final int DEADLINE_TIMED_OUT = 1;
    static final int DEADLINE_COMMIT_CLAIMED = 2;
    static final int DEADLINE_FINISHED = 3;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public enum CheckResult {
        AVAILABLE,
        UNCHANGED,
        FAILED
    }

    private enum TransferResult {
        APPLIED,
        BLOCKED,
        UNCHANGED,
        FAILED
    }

    public interface InstallListener {
        void onInstall(String pluginName, Path targetPath);
    }

    private final Logger logger;
    private static Map<String, String> extraHeaders = new HashMap<>();
    private static String overrideUserAgent = null;
    private static volatile CloseableHttpClient pooledClient = null;
    private static volatile PoolingHttpClientConnectionManager connMgr = null;
    private static Boolean java11HttpAvailable = null;
    private volatile InstallListener installListener;
    private final ThreadLocal<JarMetadata> transferCandidate = new ThreadLocal<>();
    private final ThreadLocal<String> transferBlockReason = new ThreadLocal<>();
    private final ThreadLocal<AtomicInteger> transferDeadline = new ThreadLocal<>();

    public PluginDownloader(Logger logger) {
        this.logger = logger;
    }

    public void setInstallListener(InstallListener listener) {
        this.installListener = listener;
    }

    void bindTransferDeadline(AtomicInteger state) {
        if (state == null) transferDeadline.remove();
        else transferDeadline.set(state);
    }

    void clearTransferDeadline() {
        transferDeadline.remove();
    }

    private boolean claimInstallCommit() {
        if (Thread.currentThread().isInterrupted()) return false;
        AtomicInteger state = transferDeadline.get();
        if (state == null) return true;
        int current = state.get();
        return current == DEADLINE_COMMIT_CLAIMED
                || state.compareAndSet(DEADLINE_ACTIVE, DEADLINE_COMMIT_CLAIMED);
    }

    private void backoffDelay(int attempt, int code, String link) {
        int base = Math.max(0, UpdateOptions.backoffBaseMs);
        int max = Math.max(base, UpdateOptions.backoffMaxMs);
        int delay = Math.min(max, base * (1 << Math.min(attempt, 10))) + new Random().nextInt(250);
        if (UpdateOptions.debug) {
            logger.info("[DEBUG] HTTP " + code + " for " + link + ", retry in ~" + delay + "ms (attempt " + attempt + ")");
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static synchronized void setHttpHeaders(Map<String, String> headers, String userAgent) {
        extraHeaders = headers != null ? new HashMap<>(headers) : new HashMap<>();
        overrideUserAgent = (userAgent != null && !userAgent.trim().isEmpty()) ? userAgent.trim() : null;
        resetPooledClient();
    }

    public static Map<String, String> getExtraHeaders() {
        return extraHeaders;
    }

    public static String getEffectiveUserAgent() {
        if (overrideUserAgent != null && !overrideUserAgent.isEmpty()) return overrideUserAgent;
        if (!UpdateOptions.userAgents.isEmpty()) return UpdateOptions.userAgents.get(0);
        return "AutoUpdatePlugins";
    }

    private void notifyInstalled(String pluginName, Path targetPath) {
        InstallListener listener = installListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onInstall(pluginName, targetPath);
        } catch (Throwable ignored) {
        }
    }

    private static void ensureClient() {
        if (pooledClient != null) return;
        synchronized (PluginDownloader.class) {
            if (pooledClient != null) return;
            connMgr = new PoolingHttpClientConnectionManager();
            connMgr.setMaxTotal(Math.max(32, UpdateOptions.maxParallel * 4));
            connMgr.setDefaultMaxPerRoute(Math.max(8, UpdateOptions.maxPerHost * 2));

            RequestConfig rc = RequestConfig.custom()
                    .setConnectTimeout(UpdateOptions.connectTimeoutMs)
                    .setSocketTimeout(UpdateOptions.readTimeoutMs)
                    .setConnectionRequestTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs))
                    .build();

            HttpClientBuilder builder = HttpClients.custom()
                    .setDefaultRequestConfig(rc)
                    .setConnectionManager(connMgr)
                    .disableAutomaticRetries()
                    .disableContentCompression();

            if (!UpdateOptions.sslVerify) {
                try {
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, new TrustManager[]{new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {
                        }

                        public void checkServerTrusted(X509Certificate[] c, String a) {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }}, new SecureRandom());
                    SSLConnectionSocketFactory sslsf =
                            new SSLConnectionSocketFactory(
                                    sc, NoopHostnameVerifier.INSTANCE);
                    builder.setSSLSocketFactory(sslsf);
                } catch (Exception ignored) {
                }
            }

            pooledClient = builder.build();

        }
    }

    public boolean downloadPlugin(String link, String fileName, String githubToken) throws IOException {
        return downloadPlugin(link, fileName, githubToken, null);
    }

    /** Abort current Apache transfers and rebuild the pool with current HTTP settings on demand. */
    public void cancelInFlightDownloads() {
        resetPooledClient();
    }

    private static void resetPooledClient() {
        synchronized (PluginDownloader.class) {
            CloseableHttpClient client = pooledClient;
            PoolingHttpClientConnectionManager manager = connMgr;
            pooledClient = null;
            connMgr = null;
            if (client != null) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
            if (manager != null) {
                try {
                    manager.shutdown();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public boolean downloadPlugin(String link, String fileName, String githubToken, String customPath) throws IOException {
        return transferRemotePluginDetailed(link, fileName, githubToken, customPath, true).handled();
    }

    public CheckResult checkRemotePlugin(String link, String fileName, String githubToken, String customPath) throws IOException {
        TransferOutcome outcome = transferRemotePluginDetailed(link, fileName, githubToken, customPath, false);
        if (outcome.status == TransferOutcome.Status.AVAILABLE) return CheckResult.AVAILABLE;
        if (outcome.status == TransferOutcome.Status.UNCHANGED) return CheckResult.UNCHANGED;
        return CheckResult.FAILED;
    }

    public Path resolveInstallTargetPath(String fileName, String customPath) {
        return resolveInstallPaths(fileName, customPath).targetPath;
    }

    public Path resolveLivePluginPath(String fileName, String customPath) {
        InstallPaths paths = resolveInstallPaths(fileName, customPath);
        return paths.livePath != null ? paths.livePath : paths.targetPath;
    }

    public TransferOutcome transferRemotePluginDetailed(String link,
                                                         String fileName,
                                                         String githubToken,
                                                         String customPath,
                                                         boolean installMode) throws IOException {
        return transferRemotePluginDetailed(link, fileName, githubToken, customPath, installMode, null);
    }

    public TransferOutcome transferRemotePluginDetailed(String link,
                                                         String fileName,
                                                         String githubToken,
                                                         String customPath,
                                                         boolean installMode,
                                                         ResolvedUpdate expected) throws IOException {
        return transferRemotePluginDetailed(link, fileName, githubToken, customPath, installMode,
                expected, Collections.<String, String>emptyMap());
    }

    public TransferOutcome transferRemotePluginDetailed(String link,
                                                         String fileName,
                                                         String githubToken,
                                                         String customPath,
                                                         boolean installMode,
                                                         ResolvedUpdate expected,
                                                         Map<String, String> requestHeaders) throws IOException {
        return transferRemotePluginDetailedInternal(link, fileName, githubToken, customPath,
                installMode, expected, requestHeaders, null);
    }

    TransferOutcome transferRemotePluginDetailedWithPolicy(String link,
                                                            String fileName,
                                                            String githubToken,
                                                            String customPath,
                                                            boolean installMode,
                                                            EntryOptions entryOptions) throws IOException {
        return transferRemotePluginDetailedInternal(link, fileName, githubToken, customPath,
                installMode, null, Collections.<String, String>emptyMap(), entryOptions);
    }

    private TransferOutcome transferRemotePluginDetailedInternal(String link,
                                                                  String fileName,
                                                                  String githubToken,
                                                                  String customPath,
                                                                  boolean installMode,
                                                                  ResolvedUpdate expected,
                                                                  Map<String, String> requestHeaders,
                                                                  EntryOptions entryOptions) throws IOException {
        InstallPaths paths = resolveInstallPaths(fileName, customPath);
        Path existing = paths.livePath != null && Files.isRegularFile(paths.livePath) ? paths.livePath : paths.targetPath;
        JarMetadata before = readMetadata(existing);
        transferCandidate.remove();
        transferBlockReason.remove();
        TransferResult result = transferRemotePlugin(link, fileName, githubToken, customPath,
                installMode, expected, requestHeaders, entryOptions);
        TransferOutcome.Status status;
        String reason;
        if (result == TransferResult.UNCHANGED) {
            status = TransferOutcome.Status.UNCHANGED;
            reason = "payload matches installed jar";
        } else if (result == TransferResult.BLOCKED) {
            status = TransferOutcome.Status.BLOCKED;
            reason = transferBlockReason.get();
            if (reason == null) reason = "blocked by version policy";
        } else if (result == TransferResult.APPLIED && installMode) {
            status = TransferOutcome.Status.APPLIED;
            reason = "installed";
        } else if (result == TransferResult.APPLIED) {
            status = TransferOutcome.Status.AVAILABLE;
            reason = "payload differs from installed jar";
        } else {
            status = TransferOutcome.Status.FAILED;
            reason = "download, validation, or install failed";
        }
        JarMetadata after = status == TransferOutcome.Status.APPLIED
                ? readMetadata(paths.targetPath) : transferCandidate.get();
        transferCandidate.remove();
        transferBlockReason.remove();
        return new TransferOutcome(status, fileName, paths.targetPath, paths.livePath,
                before, after, reason);
    }

    private JarMetadata readMetadata(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            return JarMetadata.read(path);
        } catch (IOException ignored) {
            return null;
        }
    }

    private TransferResult transferRemotePlugin(String link, String fileName, String githubToken, String customPath,
                                                boolean installMode, ResolvedUpdate expected,
                                                Map<String, String> requestHeaders,
                                                EntryOptions entryOptions) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            return TransferResult.FAILED;
        }
        String host = null;
        Semaphore hostSem = null;
        boolean hostPermitAcquired = false;
        try {
            host = new URL(link).getHost();
        } catch (Throwable ignored) {
        }
        if (host != null) {
            hostSem = UpdateOptions.hostSemaphores.computeIfAbsent(host.toLowerCase(Locale.ROOT),
                    h -> new Semaphore(Math.max(1, UpdateOptions.maxPerHost)));
            try {
                hostSem.acquire();
                hostPermitAcquired = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return TransferResult.FAILED;
            }
        }
        boolean requiresAuth = link.toLowerCase().contains("actions")
                && link.toLowerCase().contains("github")
                && githubToken != null && !githubToken.isEmpty();

        String tempBase = UpdateOptions.tempPath != null && !UpdateOptions.tempPath.isEmpty() ? ensureDir(UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        InstallPaths installPaths = resolveInstallPaths(fileName, customPath);
        String outputFilePath = installPaths.targetPath.toString();
        String outputTempPath = outputFilePath + ".temp";

        try {
            for (int attempt = 1; attempt <= Math.max(2, UpdateOptions.maxRetries); attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    return TransferResult.FAILED;
                }
                File rawTmp = new File(rawTempPath);
                File outTmp = new File(outputTempPath);
                cleanupQuietly(rawTmp);
                cleanupQuietly(outTmp);
                try {
                    boolean downloaded = false;
                    if (requestHeaders != null && !requestHeaders.isEmpty()) {
                        downloaded = downloadWithScopedHeaders(link, githubToken, requiresAuth,
                                rawTmp, attempt, fileName, requestHeaders);
                    } else if (hasJava11HttpClient()) {
                        downloaded = downloadWithJava11(link, githubToken, requiresAuth, rawTmp, attempt, requestHeaders);
                    } else {
                        downloaded = downloadWithApache(link, githubToken, requiresAuth, rawTmp, attempt, requestHeaders);
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        return TransferResult.FAILED;
                    }
                    if (!downloaded && (requestHeaders == null || requestHeaders.isEmpty())) {
                        downloaded = downloadWithUrlConnection(link, githubToken, requiresAuth, rawTmp, attempt,
                                fileName, requestHeaders);
                    }

                    if (downloaded) {
                        if (Thread.currentThread().isInterrupted()) {
                            return TransferResult.FAILED;
                        }
                        TransferResult result = postProcessDownloadedFile(rawTmp, outTmp, outputFilePath, rawTempPath,
                                outputTempPath, fileName, pathString(installPaths.livePath), installMode,
                                expected, entryOptions);
                        if (result != TransferResult.FAILED) {
                            return result;
                        }
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        logger.warning("Failed to download or extract plugin " + fileName + ": " + e.getMessage());
                    }
                } finally {
                    cleanupQuietly(new File(rawTempPath));
                    cleanupQuietly(new File(outputTempPath));
                }
            }
            return TransferResult.FAILED;
        } finally {
            if (hostSem != null && hostPermitAcquired) hostSem.release();
        }
    }

    /**
     * Follows payload redirects explicitly so provider credentials are never
     * replayed to a different origin (for example an object-storage CDN).
     */
    private boolean downloadWithScopedHeaders(String link, String githubToken, boolean requiresAuth,
                                              File rawTmp, int attempt, String pluginName,
                                              Map<String, String> requestHeaders) throws IOException {
        final URI original;
        try {
            original = URI.create(link);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid download URL", invalid);
        }
        URI current = original;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!httpUri(current)) return false;
            if ("https".equalsIgnoreCase(original.getScheme())
                    && "http".equalsIgnoreCase(current.getScheme())) {
                logger.warning("Refusing insecure payload redirect for " + pluginName);
                return false;
            }
            boolean sameOrigin = sameOrigin(original, current);
            Map<String, String> scoped = sameOrigin
                    ? requestHeaders : Collections.<String, String>emptyMap();
            HttpURLConnection connection = openConnection(current.toASCIIString(), githubToken,
                    requiresAuth && sameOrigin, scoped, false);
            try {
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 5) return false;
                    try {
                        current = current.resolve(location);
                    } catch (RuntimeException invalid) {
                        return false;
                    }
                    continue;
                }
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    backoffDelay(attempt, code, current.toASCIIString());
                    return false;
                }
                if (code < 200 || code >= 300) return false;
                if (!downloadWithVerification(rawTmp, connection)) return false;
                return verifyChecksumIfProvided(rawTmp, connection);
            } finally {
                connection.disconnect();
            }
        }
        return false;
    }

    private static boolean httpUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null) return false;
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null) return false;
        return equalsIgnoreCase(left.getScheme(), right.getScheme())
                && equalsIgnoreCase(left.getHost(), right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    public boolean installLocalFile(Path source, String fileName, String customPath) throws IOException {
        return installLocalFileDetailed(source, fileName, customPath, true).handled();
    }

    public CheckResult checkLocalFile(Path source, String fileName, String customPath) throws IOException {
        TransferOutcome outcome = installLocalFileDetailed(source, fileName, customPath, false);
        if (outcome.status == TransferOutcome.Status.AVAILABLE) return CheckResult.AVAILABLE;
        if (outcome.status == TransferOutcome.Status.UNCHANGED) return CheckResult.UNCHANGED;
        return CheckResult.FAILED;
    }

    public TransferOutcome installLocalFileDetailed(Path source, String fileName,
                                                    String customPath, boolean installMode) throws IOException {
        InstallPaths paths = resolveInstallPaths(fileName, customPath);
        Path existing = paths.livePath != null && Files.isRegularFile(paths.livePath) ? paths.livePath : paths.targetPath;
        JarMetadata before = readMetadata(existing);
        transferCandidate.remove();
        TransferResult result = processLocalFile(source, fileName, customPath, installMode);
        TransferOutcome.Status status;
        if (result == TransferResult.UNCHANGED) status = TransferOutcome.Status.UNCHANGED;
        else if (result == TransferResult.APPLIED && installMode) status = TransferOutcome.Status.APPLIED;
        else if (result == TransferResult.APPLIED) status = TransferOutcome.Status.AVAILABLE;
        else status = TransferOutcome.Status.FAILED;
        JarMetadata after = status == TransferOutcome.Status.APPLIED
                ? readMetadata(paths.targetPath) : transferCandidate.get();
        transferCandidate.remove();
        String reason = status == TransferOutcome.Status.UNCHANGED ? "payload matches installed jar"
                : status == TransferOutcome.Status.AVAILABLE ? "local payload differs from installed jar"
                : status == TransferOutcome.Status.APPLIED ? "installed local file"
                : "local file validation or install failed";
        return new TransferOutcome(status, fileName, paths.targetPath, paths.livePath, before, after, reason);
    }

    private CheckResult mapCheckResult(TransferResult result) {
        if (result == TransferResult.APPLIED) {
            return CheckResult.AVAILABLE;
        }
        if (result == TransferResult.UNCHANGED || result == TransferResult.BLOCKED) {
            return CheckResult.UNCHANGED;
        }
        return CheckResult.FAILED;
    }

    private TransferResult processLocalFile(Path source, String fileName, String customPath, boolean installMode) throws IOException {
        return processLocalFile(source, fileName, customPath, installMode, null);
    }

    private TransferResult processLocalFile(Path source, String fileName, String customPath,
                                            boolean installMode, EntryOptions entryOptions) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            return TransferResult.FAILED;
        }
        if (source == null) {
            throw new IOException("Local file path is null");
        }
        if (!Files.isRegularFile(source)) {
            logger.info("Local file not found: " + source);
            return TransferResult.FAILED;
        }
        String tempBase = UpdateOptions.tempPath != null && !UpdateOptions.tempPath.isEmpty() ? ensureDir(UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        InstallPaths installPaths = resolveInstallPaths(fileName, customPath);
        String outputFilePath = installPaths.targetPath.toString();
        String outputTempPath = outputFilePath + ".temp";

        File rawTmp = new File(rawTempPath);
        File outTmp = new File(outputTempPath);
        cleanupQuietly(rawTmp);
        cleanupQuietly(outTmp);

        Files.copy(source, rawTmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            if (Thread.currentThread().isInterrupted()) {
                return TransferResult.FAILED;
            }
            return postProcessDownloadedFile(rawTmp, outTmp, outputFilePath, rawTempPath, outputTempPath,
                    fileName, pathString(installPaths.livePath), installMode, null, entryOptions);
        } finally {
            cleanupQuietly(new File(rawTempPath));
            cleanupQuietly(new File(outputTempPath));
        }
    }

    private boolean downloadWithJava11(String link, String githubToken, boolean requiresAuth, File rawTmp, int attempt,
                                       Map<String, String> requestHeaders) throws IOException {
        try {
            Java11Response r = executeJava11Get(link, githubToken, requiresAuth, requestHeaders);
            try {
                int code = r.statusCode;
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    backoffDelay(attempt, code, link);
                    return false;
                }
                if (!downloadWithVerificationStream(rawTmp, r.body, r.contentLength, r.headers)) return false;
                return verifyChecksumIfProvidedHeaders(rawTmp, r.headers);
            } finally {
                closeQuietly(r.body);
            }
        } catch (Exception e) {
            restoreInterruptFromCause(e);
            return false;
        }
    }

    private boolean downloadWithApache(String link, String githubToken, boolean requiresAuth, File rawTmp, int attempt,
                                       Map<String, String> requestHeaders) throws IOException {
        try {
            ensureClient();
            CloseableHttpResponse resp = executeApacheGet(link, githubToken, requiresAuth, requestHeaders);
            try {
                int code = resp.getStatusLine() != null ? resp.getStatusLine().getStatusCode() : 0;
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    backoffDelay(attempt, code, link);
                    return false;
                }
                if (!downloadWithVerificationApache(rawTmp, resp)) return false;
                return verifyChecksumIfProvidedApache(rawTmp, resp);
            } finally {
                try {
                    resp.close();
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            restoreInterruptFromCause(e);
            return false;
        }
    }

    private static void restoreInterruptFromCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            current = current.getCause();
        }
    }

    private static void throwIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("operation interrupted");
        }
    }

    private boolean downloadWithUrlConnection(String link, String githubToken, boolean requiresAuth, File rawTmp,
                                              int attempt, String pluginName,
                                              Map<String, String> requestHeaders) throws IOException {
        HttpURLConnection connection = openConnection(link, githubToken, requiresAuth, requestHeaders);
        int code = 0;
        try {
            code = connection.getResponseCode();
        } catch (IOException ignored) {
        }
        if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
            backoffDelay(attempt, code, link);
            return false;
        }
        if (!downloadWithVerification(rawTmp, connection)) {
            logger.warning("Download failed for " + pluginName + " (attempt " + attempt + ") - retrying lenient mode (old-plugin behavior)");
            try {
                connection = openConnection(link, githubToken, requiresAuth, requestHeaders);
                return downloadLenient(rawTmp, connection);
            } catch (IOException ex) {
                return false;
            }
        }
        if (!verifyChecksumIfProvided(rawTmp, connection)) {
            logger.warning("Checksum mismatch from server");
            return false;
        }
        return true;
    }

    private TransferResult postProcessDownloadedFile(File rawTmp, File outTmp, String outputFilePath, String rawTempPath, String outputTempPath, String pluginName, String livePathOverride, boolean installMode) throws IOException {
        return postProcessDownloadedFile(rawTmp, outTmp, outputFilePath, rawTempPath, outputTempPath,
                pluginName, livePathOverride, installMode, null, null);
    }

    private TransferResult postProcessDownloadedFile(File rawTmp, File outTmp, String outputFilePath,
                                                     String rawTempPath, String outputTempPath, String pluginName,
                                                     String livePathOverride, boolean installMode,
                                                     ResolvedUpdate expected) throws IOException {
        return postProcessDownloadedFile(rawTmp, outTmp, outputFilePath, rawTempPath, outputTempPath,
                pluginName, livePathOverride, installMode, expected, null);
    }

    private TransferResult postProcessDownloadedFile(File rawTmp, File outTmp, String outputFilePath,
                                                     String rawTempPath, String outputTempPath, String pluginName,
                                                     String livePathOverride, boolean installMode,
                                                     ResolvedUpdate expected, EntryOptions entryOptions) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            return TransferResult.FAILED;
        }
        if (looksLikePluginJar(rawTmp)) {
            // Plugin jars are ZIP containers too. Keep a valid outer plugin intact even when
            // it embeds dependency jars (for example under META-INF/jars).
            moveReplace(rawTmp, outTmp);
        } else if (isZipFile(rawTempPath)) {
            if (!extractFirstPluginJarFromZip(rawTempPath, outputTempPath)) {
                logger.warning("Downloaded archive for " + pluginName
                        + " did not contain a supported plugin JAR");
                return TransferResult.FAILED;
            }
        } else {
            moveReplace(rawTmp, outTmp);
        }

        if (!validateJar(outTmp)) {
            logger.warning("Downloaded file for " + pluginName + " is not a valid JAR");
            return TransferResult.FAILED;
        }

        if (!matchesExpectedMetadata(outTmp, expected)) {
            logger.warning("Downloaded file for " + pluginName + " did not match provider metadata");
            return TransferResult.FAILED;
        }
        JarMetadata candidate = readMetadata(outTmp.toPath());
        transferCandidate.set(candidate);

        File target = new File(outputFilePath);
        if (entryOptions != null && !entryOptions.bool("force", false)) {
            Path live = null;
            try {
                if (livePathOverride != null) live = Paths.get(livePathOverride);
            } catch (InvalidPathException ignored) {
            }
            Path existing = live != null && Files.isRegularFile(live) ? live : target.toPath();
            JarMetadata local = readMetadata(existing);
            if (local != null && candidate != null) {
                boolean metadataChanged = local.sha1 == null || candidate.sha1 == null
                        || !local.sha1.equalsIgnoreCase(candidate.sha1);
                VersionPolicy policy = VersionPolicy.from(entryOptions);
                VersionDecision decision = policy.evaluate(local.version, candidate.version, metadataChanged);
                if (!decision.allowed) {
                    transferBlockReason.set("versionPolicy " + policy.name().toLowerCase(Locale.ROOT)
                            + ": " + decision.reason);
                    cleanupQuietly(outTmp);
                    cleanupQuietly(rawTmp);
                    return TransferResult.BLOCKED;
                }
            }
        }
        if (UpdateOptions.debug)
            logger.info("[DEBUG] " + (installMode ? "Ready to install" : "Ready to compare (check mode)") + ": temp=" + outTmp.getAbsolutePath() + " -> target=" + target.getAbsolutePath());
        if (shouldSkipDuplicateInstall(outTmp, target, pluginName, livePathOverride)) {
            cleanupQuietly(outTmp);
            cleanupQuietly(rawTmp);
            return TransferResult.UNCHANGED;
        }
        if (!installMode) {
            if (UpdateOptions.debug) {
                logger.info("[DEBUG] Check mode detected an available update for " + pluginName + "; no files were installed.");
            }
            cleanupQuietly(outTmp);
            cleanupQuietly(rawTmp);
            return TransferResult.APPLIED;
        }
        if (Thread.currentThread().isInterrupted()) {
            cleanupQuietly(outTmp);
            cleanupQuietly(rawTmp);
            return TransferResult.FAILED;
        }
        if (UpdateOptions.rollbackEnabled) {
            try {
                Path livePath = null;
                if (livePathOverride != null && !livePathOverride.trim().isEmpty()) {
                    try {
                        livePath = Paths.get(livePathOverride);
                    } catch (InvalidPathException ignored) {
                    }
                }
                RollbackManager.prepareBackup(logger, pluginName, target.toPath(), livePath);
            } catch (Exception ex) {
                if (UpdateOptions.debug) {
                    logger.log(java.util.logging.Level.FINE, "[DEBUG] Unable to snapshot rollback for " + pluginName, ex);
                }
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            cleanupQuietly(outTmp);
            cleanupQuietly(rawTmp);
            return TransferResult.FAILED;
        }
        // Claim the destructive handoff atomically against the timeout task. If the
        // deadline won first, a fully downloaded payload must not cross the install boundary.
        if (!claimInstallCommit()) {
            cleanupQuietly(outTmp);
            cleanupQuietly(rawTmp);
            return TransferResult.FAILED;
        }
        moveReplace(outTmp, target);
        if (UpdateOptions.rollbackEnabled) {
            try {
                RollbackManager.markInstalled(pluginName, target.toPath());
            } catch (Exception ignored) {
            }
        }
        notifyInstalled(pluginName, target.toPath());
        cleanupQuietly(rawTmp);
        return TransferResult.APPLIED;
    }

    private boolean matchesExpectedMetadata(File file, ResolvedUpdate expected) {
        if (expected == null || file == null || !file.isFile()) return true;
        try {
            if (expected.size >= 0 && file.length() != expected.size) {
                logger.warning("Size mismatch for " + file.getName() + ": expected=" + expected.size + ", actual=" + file.length());
                return false;
            }
            if (notBlank(expected.sha512) && !digestMatches(file, "SHA-512", expected.sha512)) return false;
            if (notBlank(expected.sha256) && !digestMatches(file, "SHA-256", expected.sha256)) return false;
            if (notBlank(expected.sha1) && !digestMatches(file, "SHA-1", expected.sha1)) return false;
            return !notBlank(expected.md5) || digestMatches(file, "MD5", expected.md5);
        } catch (Exception ex) {
            logger.warning("Unable to verify provider checksum for " + file.getName() + ": " + ex.getMessage());
            return false;
        }
    }


    private InstallPaths resolveInstallPaths(String fileName, String customPath) {
        EntryPathOptions options = EntryPathOptions.parse(customPath, logger);
        String customFilePath = options.getFilePath();
        String customUpdatePath = options.getUpdatePath();
        Boolean customUseUpdateFolder = options.getUseUpdateFolder();

        String configuredFilePath = sanitizeCustomPath(UpdateOptions.filePath);
        String configuredUpdatePath = sanitizeCustomPath(UpdateOptions.updatePath);
        String effectiveFilePath = (customFilePath != null && !customFilePath.isEmpty()) ? customFilePath : configuredFilePath;
        Path liveDir = (effectiveFilePath != null && !effectiveFilePath.isEmpty())
                ? normalizePath(effectiveFilePath)
                : Paths.get("plugins").toAbsolutePath().normalize();
        ensureDirectory(liveDir);

        Path exactLiveJar = liveDir.resolve(fileName + ".jar").toAbsolutePath().normalize();
        Path matchedLiveJar = Files.exists(exactLiveJar) ? exactLiveJar : findInstalledPluginJar(liveDir, fileName);
        Path liveTarget = matchedLiveJar != null ? matchedLiveJar : exactLiveJar;
        boolean useUpdateFolder = (customUseUpdateFolder != null)
                ? customUseUpdateFolder.booleanValue()
                : (customFilePath != null && !customFilePath.isEmpty()
                ? false
                : UpdateOptions.useUpdateFolder);
        if (!useUpdateFolder) {
            return new InstallPaths(liveTarget, matchedLiveJar);
        }

        Path updateDir = resolveUpdateDirectory(liveDir, customFilePath, customUpdatePath, configuredUpdatePath);
        Path stagedTarget = updateDir.resolve(fileName + ".jar")
                .toAbsolutePath()
                .normalize();
        return new InstallPaths(stagedTarget, matchedLiveJar);
    }

    private static final class InstallPaths {
        private final Path targetPath;
        private final Path livePath;

        private InstallPaths(Path targetPath, Path livePath) {
            this.targetPath = targetPath;
            this.livePath = livePath;
        }
    }

    static LinkedHashSet<String> readPluginIdentifiers(Path jarPath) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return identifiers;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            addYamlIdentifier(jar, "plugin.yml", identifiers);
            addYamlIdentifier(jar, "bungee.yml", identifiers);
            addVelocityIdentifiers(jar, identifiers);
        } catch (IOException ignored) {
        }
        return identifiers;
    }

    static Path findPluginJarByIdentifiers(Path dir, Collection<String> identifiers) {
        if (dir == null || identifiers == null || identifiers.isEmpty() || !Files.isDirectory(dir)) {
            return null;
        }

        LinkedHashSet<String> expected = new LinkedHashSet<>();
        for (String identifier : identifiers) {
            String normalized = normalizeIdentifier(identifier);
            if (normalized != null) {
                expected.add(normalized);
            }
        }
        if (expected.isEmpty()) {
            return null;
        }

        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path candidate : stream) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String name = candidate.getFileName() != null ? candidate.getFileName().toString() : "";
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                candidates.add(candidate.toAbsolutePath().normalize());
            }
        } catch (IOException ignored) {
            return null;
        }

        candidates.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (Path candidate : candidates) {
            LinkedHashSet<String> candidateIdentifiers = readPluginIdentifiers(candidate);
            for (String candidateIdentifier : candidateIdentifiers) {
                if (expected.contains(candidateIdentifier)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Path resolveUpdateDirectory(Path liveDir, String customFilePath, String customUpdatePath, String configuredUpdatePath) {
        String effectiveUpdatePath = customUpdatePath;
        if (effectiveUpdatePath == null || effectiveUpdatePath.isEmpty()) {
            if (customFilePath != null && !customFilePath.isEmpty()) {
                effectiveUpdatePath = liveDir.resolve("update").toString();
            } else if (configuredUpdatePath != null && !configuredUpdatePath.isEmpty()) {
                effectiveUpdatePath = configuredUpdatePath;
            } else {
                effectiveUpdatePath = liveDir.resolve("update").toString();
            }
        }
        Path updateDir = normalizePath(effectiveUpdatePath);
        ensureDirectory(updateDir);
        return updateDir;
    }

    private Path findInstalledPluginJar(Path liveDir, String pluginName) {
        String normalized = normalizeIdentifier(pluginName);
        if (normalized == null) {
            return null;
        }
        return findPluginJarByIdentifiers(liveDir, Collections.singleton(normalized));
    }

    private static void addYamlIdentifier(JarFile jar, String entryName, Set<String> identifiers) {
        ZipEntry entry = jar.getEntry(entryName);
        if (entry == null) {
            return;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            Object loaded = new Yaml().load(in);
            if (loaded instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) loaded;
                addIdentifier(identifiers, map.get("name"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addVelocityIdentifiers(JarFile jar, Set<String> identifiers) {
        ZipEntry entry = jar.getEntry("velocity-plugin.json");
        if (entry == null) {
            return;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            JsonNode node = JSON_MAPPER.readTree(in);
            if (node == null) {
                return;
            }
            addIdentifier(identifiers, node.path("id").asText(null));
            addIdentifier(identifiers, node.path("name").asText(null));
        } catch (Throwable ignored) {
        }
    }

    private static void addIdentifier(Set<String> identifiers, Object value) {
        String normalized = normalizeIdentifier(value);
        if (normalized != null) {
            identifiers.add(normalized);
        }
    }

    private static String normalizeIdentifier(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static String pathString(Path path) {
        return path != null ? path.toString() : null;
    }

    private Path normalizePath(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private void ensureDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
    }

    private String sanitizeCustomPath(String cp) {
        if (cp == null) return null;
        cp = cp.trim();
        if (cp.isEmpty()) return null;
        cp = expandUserHome(cp);
        try {
            Path path = Paths.get(cp).normalize();
            String normalized = path.toString();
            return normalized.isEmpty() ? null : normalized;
        } catch (InvalidPathException ex) {
            if (UpdateOptions.debug) {
                logger.info("[DEBUG] Ignoring custom path '" + cp + "' due to invalid path: " + ex.getMessage());
            }
            return null;
        }
    }

    private String expandUserHome(String path) {
        if (path == null || !path.startsWith("~")) {
            return path;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return path;
        }
        if (path.equals("~")) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }

    private String ensureDir(String dir) {
        if (dir == null || dir.isEmpty()) return dir;
        File directory = new File(dir);
        directory.mkdirs();
        String path = directory.getPath();
        if (!path.endsWith(File.separator)) path = path + File.separator;
        return path;
    }

    private boolean downloadPluginToFile(String outputFilePath, HttpURLConnection connection) throws IOException {
        return downloadWithVerification(new File(outputFilePath), connection);
    }


    private boolean extractFirstPluginJarFromZip(String zipFilePath, String outputFilePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipFilePath)) {
            List<ZipEntry> candidates = new ArrayList<ZipEntry>();
            for (ZipEntry entry : Collections.list(zipFile.entries())) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!entry.isDirectory()
                        && !name.contains("javadoc")
                        && !name.contains("sources")
                        && !name.contains("api/")
                        && name.endsWith(".jar")) {
                    candidates.add(entry);
                }
            }
            Collections.sort(candidates, new Comparator<ZipEntry>() {
                @Override
                public int compare(ZipEntry left, ZipEntry right) {
                    int insensitive = left.getName().compareToIgnoreCase(right.getName());
                    return insensitive != 0 ? insensitive : left.getName().compareTo(right.getName());
                }
            });

            File output = new File(outputFilePath);
            cleanupQuietly(output);
            for (ZipEntry candidate : candidates) {
                throwIfInterrupted();
                try (InputStream in = new BufferedInputStream(zipFile.getInputStream(candidate), 65536);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(output), 65536)) {
                    byte[] buffer = new byte[65536];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        throwIfInterrupted();
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                }
                if (looksLikePluginJar(output)) {
                    return true;
                }
                cleanupQuietly(output);
            }
            return false;
        }
    }

    public boolean downloadJenkinsPlugin(String link, String fileName) {
        return downloadJenkinsPlugin(link, fileName, null);
    }

    public boolean downloadJenkinsPlugin(String link, String fileName, String customPath) {
        return transferJenkinsPluginDetailed(link, fileName, customPath, true).handled();
    }

    public CheckResult checkJenkinsPlugin(String link, String fileName, String customPath) {
        TransferOutcome outcome = transferJenkinsPluginDetailed(link, fileName, customPath, false);
        if (outcome.status == TransferOutcome.Status.AVAILABLE) return CheckResult.AVAILABLE;
        if (outcome.status == TransferOutcome.Status.UNCHANGED
                || outcome.status == TransferOutcome.Status.BLOCKED) return CheckResult.UNCHANGED;
        return CheckResult.FAILED;
    }

    public TransferOutcome transferJenkinsPluginDetailed(String link, String fileName,
                                                          String customPath, boolean installMode) {
        return transferJenkinsPluginDetailed(link, fileName, customPath, installMode, null);
    }

    TransferOutcome transferJenkinsPluginDetailed(String link, String fileName,
                                                   String customPath, boolean installMode,
                                                   EntryOptions entryOptions) {
        InstallPaths paths = resolveInstallPaths(fileName, customPath);
        Path existing = paths.livePath != null && Files.isRegularFile(paths.livePath) ? paths.livePath : paths.targetPath;
        JarMetadata before = readMetadata(existing);
        transferCandidate.remove();
        transferBlockReason.remove();
        TransferResult result = transferJenkinsPlugin(link, fileName, customPath, installMode, entryOptions);
        TransferOutcome.Status status;
        if (result == TransferResult.UNCHANGED) status = TransferOutcome.Status.UNCHANGED;
        else if (result == TransferResult.BLOCKED) status = TransferOutcome.Status.BLOCKED;
        else if (result == TransferResult.APPLIED && installMode) status = TransferOutcome.Status.APPLIED;
        else if (result == TransferResult.APPLIED) status = TransferOutcome.Status.AVAILABLE;
        else status = TransferOutcome.Status.FAILED;
        JarMetadata after = status == TransferOutcome.Status.APPLIED
                ? readMetadata(paths.targetPath) : transferCandidate.get();
        transferCandidate.remove();
        String reason = status == TransferOutcome.Status.UNCHANGED ? "payload matches installed jar"
                : status == TransferOutcome.Status.BLOCKED
                ? (transferBlockReason.get() == null ? "blocked by version policy" : transferBlockReason.get())
                : status == TransferOutcome.Status.AVAILABLE ? "payload differs from installed jar"
                : status == TransferOutcome.Status.APPLIED ? "installed"
                : "Jenkins download, validation, or install failed";
        transferBlockReason.remove();
        return new TransferOutcome(status, fileName, paths.targetPath, paths.livePath, before, after, reason);
    }

    private TransferResult transferJenkinsPlugin(String link, String fileName, String customPath,
                                                 boolean installMode, EntryOptions entryOptions) {
        String tempBase = UpdateOptions.tempPath != null && !UpdateOptions.tempPath.isEmpty() ? ensureDir(UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        InstallPaths installPaths = resolveInstallPaths(fileName, customPath);
        String outputFilePath = installPaths.targetPath.toString();
        String outputTempPath = outputFilePath + ".temp";

        for (int attempt = 1; attempt <= Math.max(2, UpdateOptions.maxRetries); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return TransferResult.FAILED;
            }
            File rawTmp = new File(rawTempPath);
            File outTmp = new File(outputTempPath);
            cleanupQuietly(rawTmp);
            cleanupQuietly(outTmp);
            try {
                HttpURLConnection connection = openConnection(link, null, false);
                int code = 0;
                try {
                    code = connection.getResponseCode();
                } catch (IOException ignored) {
                }
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    int base = Math.max(0, UpdateOptions.backoffBaseMs);
                    int max = Math.max(base, UpdateOptions.backoffMaxMs);
                    int delay = Math.min(max, base * (1 << Math.min(attempt, 10))) + new Random().nextInt(250);
                    if (UpdateOptions.debug)
                        logger.info("[DEBUG] HTTP " + code + " for " + link + ", retry in ~" + delay + "ms (attempt " + attempt + ")");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored2) {
                        Thread.currentThread().interrupt();
                        return TransferResult.FAILED;
                    }
                    continue;
                }
                if (!downloadWithVerification(rawTmp, connection)) {
                    logger.info("Download failed (attempt " + attempt + ")");
                    continue;
                }
                if (!verifyChecksumIfProvided(rawTmp, connection)) {
                    logger.info("Checksum mismatch from server (attempt " + attempt + ")");
                    continue;
                }
                TransferResult result = postProcessDownloadedFile(rawTmp, outTmp, outputFilePath,
                        rawTempPath, outputTempPath, fileName, pathString(installPaths.livePath),
                        installMode, null, entryOptions);
                if (result != TransferResult.FAILED) {
                    return result;
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    logger.info("Failed to download or extract plugin: " + e.getMessage());
                }
            } finally {
                cleanupQuietly(new File(rawTempPath));
                cleanupQuietly(new File(outputTempPath));
            }
        }
        return TransferResult.FAILED;
    }


    public static boolean isZipFile(String filePath) {
        Objects.requireNonNull(filePath, "filePath");
        Path path = Paths.get(filePath);

        try (ZipFile ignored = new ZipFile(path.toFile())) {
            return true;
        } catch (ZipException ex) {
            return false;
        } catch (IOException ex) {
            throw new UncheckedIOException("I/O while probing ZIP", ex);
        }
    }

    private void moveReplace(File from, File to) throws IOException {
        try {
            Files.createDirectories(to.getParentFile().toPath());
        } catch (IOException ignored) {
        }
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HttpURLConnection openConnection(String link, String githubToken, boolean requiresAuth) throws IOException {
        return openConnection(link, githubToken, requiresAuth, Collections.<String, String>emptyMap());
    }

    private HttpURLConnection openConnection(String link, String githubToken, boolean requiresAuth,
                                             Map<String, String> requestHeaders) throws IOException {
        return openConnection(link, githubToken, requiresAuth, requestHeaders, true);
    }

    private HttpURLConnection openConnection(String link, String githubToken, boolean requiresAuth,
                                             Map<String, String> requestHeaders,
                                             boolean followRedirects) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
        connection.setInstanceFollowRedirects(followRedirects);

        String ua = (overrideUserAgent != null && !overrideUserAgent.trim().isEmpty()
                && !"AutoUpdatePlugins".equalsIgnoreCase(overrideUserAgent))
                ? overrideUserAgent.trim() : null;
        if (ua == null && UpdateOptions.userAgents != null && !UpdateOptions.userAgents.isEmpty()) {
            ua = UpdateOptions.userAgents.get(new Random().nextInt(UpdateOptions.userAgents.size()));
        }
        if (ua == null) {
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
        }

        connection.setRequestProperty("User-Agent", ua);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Accept", "application/octet-stream, */*");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Connection", "keep-alive");

        if (requiresAuth && githubToken != null && !githubToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + githubToken);
        }
        try {
            URI uri = URI.create(link);
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? (":" + uri.getPort()) : "");
            connection.setRequestProperty("Referer", origin);
        } catch (Throwable ignored) {
        }

        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    connection.setRequestProperty(e.getKey(), e.getValue());
                }
            }
        }
        if (requestHeaders != null) {
            for (Map.Entry<String, String> e : requestHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    connection.setRequestProperty(e.getKey(), e.getValue());
                }
            }
        }

        connection.setConnectTimeout(UpdateOptions.connectTimeoutMs);
        connection.setReadTimeout(UpdateOptions.readTimeoutMs);

        if (UpdateOptions.debug) {
            boolean authenticated = (requiresAuth && githubToken != null && !githubToken.isEmpty())
                    || hasNonBlankHeader(extraHeaders, "Authorization")
                    || hasNonBlankHeader(requestHeaders, "Authorization");
            logger.info("[DEBUG] OpenConnection url=" + link + ", auth=" + authenticated + ", ua=" + ua);
        }
        return connection;
    }

    private static boolean hasNonBlankHeader(Map<String, String> headers, String name) {
        if (headers == null || name == null) return false;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && name.equalsIgnoreCase(header.getKey())
                    && header.getValue() != null && !header.getValue().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJava11HttpClient() {
        if (java11HttpAvailable != null) return java11HttpAvailable.booleanValue();
        synchronized (PluginDownloader.class) {
            if (java11HttpAvailable != null) return java11HttpAvailable.booleanValue();
            try {
                Class.forName("java.net.http.HttpClient");
                java11HttpAvailable = Boolean.TRUE;
            } catch (Throwable t) {
                java11HttpAvailable = Boolean.FALSE;
            }
            return java11HttpAvailable.booleanValue();
        }
    }

    private static class Java11Response {
        final InputStream body;
        final int statusCode;
        final long contentLength;
        final Map<String, String> headers;

        Java11Response(InputStream body, int statusCode, long contentLength, Map<String, String> headers) {
            this.body = body;
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            this.headers = headers;
        }
    }

    private Java11Response executeJava11Get(String link, String githubToken, boolean requiresAuth) throws Exception {
        return executeJava11Get(link, githubToken, requiresAuth, Collections.<String, String>emptyMap());
    }

    private Java11Response executeJava11Get(String link, String githubToken, boolean requiresAuth,
                                            Map<String, String> requestHeaders) throws Exception {
        Class<?> httpClientCls = Class.forName("java.net.http.HttpClient");
        Class<?> httpRequestCls = Class.forName("java.net.http.HttpRequest");
        Class<?> httpResponseCls = Class.forName("java.net.http.HttpResponse");
        Class<?> bodyHandlersCls = Class.forName("java.net.http.HttpResponse$BodyHandlers");
        Class<?> bodyHandlerIface = Class.forName("java.net.http.HttpResponse$BodyHandler");
        Class<?> redirectCls = Class.forName("java.net.http.HttpClient$Redirect");
        Class<?> headersCls = Class.forName("java.net.http.HttpHeaders");
        Class<?> durationCls = Class.forName("java.time.Duration");

        Object builder = httpClientCls.getMethod("newBuilder").invoke(null);
        Object redirectNormal = Enum.valueOf((Class<? extends Enum>) redirectCls.asSubclass(Enum.class), "NORMAL");
        builder.getClass().getMethod("followRedirects", redirectCls).invoke(builder, redirectNormal);
        Object connTimeout = durationCls.getMethod("ofMillis", long.class).invoke(null, (long) UpdateOptions.connectTimeoutMs);
        builder.getClass().getMethod("connectTimeout", durationCls).invoke(builder, connTimeout);
        Object client = builder.getClass().getMethod("build").invoke(builder);

        URI uri = URI.create(link);
        Object reqBuilder = httpRequestCls.getMethod("newBuilder", URI.class).invoke(null, uri);

        String ua = (overrideUserAgent != null && !overrideUserAgent.trim().isEmpty()
                && !"AutoUpdatePlugins".equalsIgnoreCase(overrideUserAgent))
                ? overrideUserAgent.trim() : null;
        if (ua == null && UpdateOptions.userAgents != null && !UpdateOptions.userAgents.isEmpty()) {
            ua = UpdateOptions.userAgents.get(new Random().nextInt(UpdateOptions.userAgents.size()));
        }
        if (ua == null) {
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
        }


        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "User-Agent", ua);
        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Accept-Encoding", "identity");
        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Accept", "application/octet-stream, */*");
        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Accept-Language", "en-US,en;q=0.9");
        if (requiresAuth && githubToken != null && !githubToken.isEmpty()) {
            reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Authorization", "Bearer " + githubToken);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, e.getKey(), e.getValue());
                }
            }
        }
        if (requestHeaders != null) {
            for (Map.Entry<String, String> e : requestHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    reqBuilder.getClass().getMethod("header", String.class, String.class)
                            .invoke(reqBuilder, e.getKey(), e.getValue());
                }
            }
        }

        Object readTimeout = durationCls.getMethod("ofMillis", long.class).invoke(null, (long) UpdateOptions.readTimeoutMs);
        reqBuilder.getClass().getMethod("timeout", durationCls).invoke(reqBuilder, readTimeout);
        Object request = reqBuilder.getClass().getMethod("GET").invoke(reqBuilder);
        request = reqBuilder.getClass().getMethod("build").invoke(reqBuilder);

        Object bodyHandler = bodyHandlersCls.getMethod("ofInputStream").invoke(null);
        Object response = client.getClass().getMethod("send", httpRequestCls, bodyHandlerIface).invoke(client, request, bodyHandler);

        int status = (Integer) response.getClass().getMethod("statusCode").invoke(response);
        Object headers = response.getClass().getMethod("headers").invoke(response);
        Map<String, List<String>> rawMap = (Map<String, List<String>>) headers.getClass().getMethod("map").invoke(headers);
        Map<String, String> flat = new HashMap<>();
        if (rawMap != null) {
            for (Map.Entry<String, List<String>> e : rawMap.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty())
                    flat.put(e.getKey(), e.getValue().get(0));
            }
        }
        long expected = -1L;
        try {
            String cl = flat.get("Content-Length");
            if (cl == null) cl = flat.get("content-length");
            if (cl != null) expected = Long.parseLong(cl.trim());
        } catch (Throwable ignored) {
        }

        InputStream body = (InputStream) response.getClass().getMethod("body").invoke(response);
        return new Java11Response(body, status, expected, flat);
    }

    private boolean downloadWithVerificationStream(File outFile, InputStream in, long expected, Map<String, String> headers) throws IOException {
        boolean canTrustLength = expected >= 0;
        long written = 0L;
        try (InputStream in0 = new BufferedInputStream(in, 65536); OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in0.read(buffer)) != -1) {
                throwIfInterrupted();
                out.write(buffer, 0, n);
                written += n;
            }
            out.flush();
        }

        if (canTrustLength && expected >= 0 && written != expected) {
            cleanupQuietly(outFile);
            return false;
        }
        try (FileInputStream fis = new FileInputStream(outFile)) {
            byte[] probe = new byte[64];
            int n = fis.read(probe);
            String head = (n > 0) ? new String(probe, 0, n, StandardCharsets.ISO_8859_1) : "";
            String t = head.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("<!doctype html") || t.startsWith("<html")) {
                cleanupQuietly(outFile);
                return false;
            }
        } catch (Throwable ignored) {
        }

        return true;
    }

    private boolean verifyChecksumIfProvidedHeaders(File file, Map<String, String> headers) {
        try {
            if (headers == null || headers.isEmpty()) return true;
            Map<String, String> h = new HashMap<>();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null)
                    h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
            String sha256 = h.get("x-checksum-sha256");
            if (sha256 == null) sha256 = h.get("x-checksum-sha256");
            String sha1 = h.get("x-checksum-sha1");
            String md5 = h.get("x-checksum-md5");
            String etag = h.get("etag");
            if (etag != null) etag = stripQuotes(etag);

            if (notBlank(sha256)) return digestMatches(file, "SHA-256", sha256);
            if (notBlank(sha1)) return digestMatches(file, "SHA-1", sha1);
            if (notBlank(md5)) return digestMatches(file, "MD5", md5);
            if (notBlank(etag) && isHex(etag)) {
                int len = etag.length();
                if (len == 32) return digestMatches(file, "MD5", etag);
                if (len == 40) return digestMatches(file, "SHA-1", etag);
                if (len == 64) return digestMatches(file, "SHA-256", etag);
            }
        } catch (Exception e) {
            logger.fine("Checksum verification skipped (headers): " + e.getMessage());
            return true;
        }
        return true;
    }

    private void closeQuietly(InputStream in) {
        if (in != null) try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private CloseableHttpResponse executeApacheGet(String link, String githubToken, boolean requiresAuth) throws IOException {
        return executeApacheGet(link, githubToken, requiresAuth, Collections.<String, String>emptyMap());
    }

    private CloseableHttpResponse executeApacheGet(String link, String githubToken, boolean requiresAuth,
                                                    Map<String, String> requestHeaders) throws IOException {
        ensureClient();
        HttpGet get = new HttpGet(link);
        String ua = (overrideUserAgent != null && !overrideUserAgent.trim().isEmpty()
                && !"AutoUpdatePlugins".equalsIgnoreCase(overrideUserAgent))
                ? overrideUserAgent.trim() : null;
        if (ua == null && UpdateOptions.userAgents != null && !UpdateOptions.userAgents.isEmpty()) {
            ua = UpdateOptions.userAgents.get(new Random().nextInt(UpdateOptions.userAgents.size()));
        }
        if (ua == null) {
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
        }
        get.setHeader("User-Agent", ua);
        get.setHeader("Accept-Encoding", "identity");
        get.setHeader("Accept", "application/octet-stream, */*");
        get.setHeader("Accept-Language", "en-US,en;q=0.9");
        get.setHeader("Connection", "keep-alive");
        if (requiresAuth && githubToken != null && !githubToken.isEmpty()) {
            get.setHeader("Authorization", "Bearer " + githubToken);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    get.setHeader(e.getKey(), e.getValue());
                }
            }
        }
        if (requestHeaders != null) {
            for (Map.Entry<String, String> e : requestHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    get.setHeader(e.getKey(), e.getValue());
                }
            }
        }
        return pooledClient.execute(get);
    }


    private static boolean isGithubishHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith("github.com")
                || h.endsWith("githubusercontent.com")
                || h.endsWith("codeload.github.com")
                || h.endsWith("objects.githubusercontent.com");
    }

    private boolean downloadWithVerification(File outFile, HttpURLConnection connection) throws IOException {
        long expected = -1L;
        boolean canTrustLength = true;

        try {
            expected = connection.getContentLengthLong();
            String ce = connection.getHeaderField("Content-Encoding");
            String te = connection.getHeaderField("Transfer-Encoding");
            if (expected < 0 || (ce != null && !"identity".equalsIgnoreCase(ce)) || ("chunked".equalsIgnoreCase(te))) {
                canTrustLength = false;
            }
        } catch (Throwable ignored) {
        }

        long written = 0L;
        try (InputStream in = new BufferedInputStream(connection.getInputStream(), 65536); OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
            if (UpdateOptions.debug) {
                try {
                    logger.info("[DEBUG] HTTP code=" + connection.getResponseCode()
                            + ", type=" + connection.getContentType()
                            + ", length=" + expected
                            + (connection.getHeaderField("Content-Encoding") != null
                            ? ", enc=" + connection.getHeaderField("Content-Encoding") : ""));
                } catch (IOException ignored) {
                }
            }
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                throwIfInterrupted();
                out.write(buffer, 0, n);
                written += n;
            }
            out.flush();
        }

        if (canTrustLength && expected >= 0 && written != expected) {
            logger.warning("Content-Length mismatch: expected=" + expected + ", got=" + written);
            cleanupQuietly(outFile);
            return false;
        }
        try (FileInputStream fis = new FileInputStream(outFile)) {
            byte[] probe = new byte[64];
            int n = fis.read(probe);
            String head = (n > 0) ? new String(probe, 0, n, StandardCharsets.ISO_8859_1) : "";
            String t = head.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("<!doctype html") || t.startsWith("<html")) {
                cleanupQuietly(outFile);
                return false;
            }
        } catch (Throwable ignored) {
        }

        return true;
    }

    private boolean downloadWithVerificationApache(File outFile, CloseableHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) return false;
        long expected = entity.getContentLength();
        boolean canTrustLength = expected >= 0;

        long written = 0L;
        try (InputStream in = new BufferedInputStream(entity.getContent(), 65536);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                throwIfInterrupted();
                out.write(buffer, 0, n);
                written += n;
            }
            out.flush();
        }

        if (canTrustLength && expected >= 0 && written != expected) {
            cleanupQuietly(outFile);
            return false;
        }
        try (FileInputStream fis = new FileInputStream(outFile)) {
            byte[] probe = new byte[64];
            int n = fis.read(probe);
            String head = (n > 0) ? new String(probe, 0, n, StandardCharsets.ISO_8859_1) : "";
            String t = head.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("<!doctype html") || t.startsWith("<html")) {
                cleanupQuietly(outFile);
                return false;
            }
        } catch (Throwable ignored) {
        }

        return true;
    }

    private boolean verifyChecksumIfProvidedApache(File file, CloseableHttpResponse response) {
        try {
            Header h;
            String sha256 = (h = response.getFirstHeader("X-Checksum-SHA256")) != null ? h.getValue() : null;
            if (sha256 == null && (h = response.getFirstHeader("X-Checksum-Sha256")) != null) sha256 = h.getValue();
            String sha1 = (h = response.getFirstHeader("X-Checksum-SHA1")) != null ? h.getValue() : null;
            if (sha1 == null && (h = response.getFirstHeader("X-Checksum-Sha1")) != null) sha1 = h.getValue();
            String md5 = (h = response.getFirstHeader("X-Checksum-MD5")) != null ? h.getValue() : null;
            String etag = (h = response.getFirstHeader("ETag")) != null ? stripQuotes(h.getValue()) : null;

            if (notBlank(sha256)) return digestMatches(file, "SHA-256", sha256);
            if (notBlank(sha1)) return digestMatches(file, "SHA-1", sha1);
            if (notBlank(md5)) return digestMatches(file, "MD5", md5);

            if (notBlank(etag) && isHex(etag)) {
                int len = etag.length();
                if (len == 32) return digestMatches(file, "MD5", etag);
                if (len == 40) return digestMatches(file, "SHA-1", etag);
                if (len == 64) return digestMatches(file, "SHA-256", etag);
            }
        } catch (Exception e) {
            logger.fine("Checksum verification skipped (apache): " + e.getMessage());
            return true;
        }
        return true;
    }


    private boolean downloadLenient(File outFile, HttpURLConnection connection) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new BufferedInputStream(connection.getInputStream(), 65536);
            out = new BufferedOutputStream(new FileOutputStream(outFile), 65536);
            byte[] buffer = new byte[65536];
            int r;
            while ((r = in.read(buffer)) != -1) {
                throwIfInterrupted();
                out.write(buffer, 0, r);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
            if (out != null) try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }


    public boolean buildFromGitHubRepo(String repoPath, String fileName, String key) throws IOException {
        return buildFromGitHubRepo(repoPath, fileName, key, null);
    }

    public boolean buildFromGitHubRepo(String repoPath, String fileName, String key, String customPath) throws IOException {
        return buildFromGitHubRepo(repoPath, fileName, key, customPath, null);
    }

    public boolean buildFromGitHubRepo(String repoPath, String fileName, String key, String customPath, String branchOverride) throws IOException {
        return buildFromGitHubRepo(repoPath, fileName, key, customPath, branchOverride, null);
    }

    boolean buildFromGitHubRepo(String repoPath, String fileName, String key, String customPath,
                                String branchOverride, EntryOptions entryOptions) throws IOException {
        return transferBuiltGitHubRepo(repoPath, fileName, key, customPath,
                branchOverride, true, entryOptions) != TransferResult.FAILED;
    }

    public CheckResult checkBuildFromGitHubRepo(String repoPath, String fileName, String key, String customPath, String branchOverride) throws IOException {
        return checkBuildFromGitHubRepo(repoPath, fileName, key, customPath, branchOverride, null);
    }

    CheckResult checkBuildFromGitHubRepo(String repoPath, String fileName, String key, String customPath,
                                         String branchOverride, EntryOptions entryOptions) throws IOException {
        return mapCheckResult(transferBuiltGitHubRepo(repoPath, fileName, key, customPath,
                branchOverride, false, entryOptions));
    }

    TransferOutcome buildFromGitHubRepoDetailed(String repoPath, String fileName, String key,
                                                String customPath, String branchOverride,
                                                boolean installMode, EntryOptions entryOptions) throws IOException {
        InstallPaths paths = resolveInstallPaths(fileName, customPath);
        Path existing = paths.livePath != null && Files.isRegularFile(paths.livePath)
                ? paths.livePath : paths.targetPath;
        JarMetadata before = readMetadata(existing);
        transferCandidate.remove();
        transferBlockReason.remove();
        TransferResult result = transferBuiltGitHubRepo(repoPath, fileName, key, customPath,
                branchOverride, installMode, entryOptions);
        TransferOutcome.Status status;
        if (result == TransferResult.BLOCKED) status = TransferOutcome.Status.BLOCKED;
        else if (result == TransferResult.UNCHANGED) status = TransferOutcome.Status.UNCHANGED;
        else if (result == TransferResult.APPLIED && installMode) status = TransferOutcome.Status.APPLIED;
        else if (result == TransferResult.APPLIED) status = TransferOutcome.Status.AVAILABLE;
        else status = TransferOutcome.Status.FAILED;
        JarMetadata after = status == TransferOutcome.Status.APPLIED
                ? readMetadata(paths.targetPath) : transferCandidate.get();
        String blockReason = transferBlockReason.get();
        transferCandidate.remove();
        transferBlockReason.remove();
        String reason = status == TransferOutcome.Status.BLOCKED
                ? (blockReason == null ? "version policy blocked source build" : blockReason)
                : status == TransferOutcome.Status.UNCHANGED ? "built payload matches installed jar"
                : status == TransferOutcome.Status.AVAILABLE ? "built payload differs from installed jar"
                : status == TransferOutcome.Status.APPLIED ? "installed source build"
                : "source build, validation, or install failed";
        return new TransferOutcome(status, fileName, paths.targetPath, paths.livePath,
                before, after, reason);
    }

    private TransferResult transferBuiltGitHubRepo(String repoPath, String fileName, String key,
                                                   String customPath, String branchOverride,
                                                   boolean installMode, EntryOptions entryOptions) throws IOException {
        throwIfInterrupted();
        if (repoPath == null || repoPath.isEmpty()) throw new IOException("Invalid repo path");
        if (UpdateOptions.debug) logger.info("[DEBUG] Starting GitHub build for " + repoPath);

        String defaultBranch = "main";
        try {
            HttpURLConnection info = openConnection("https://api.github.com/repos" + repoPath, key, true);
            info.setRequestProperty("Accept", "application/vnd.github+json");
            if (info.getResponseCode() == 200) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                InputStream in = info.getInputStream();
                try {
                    while ((r = in.read(buf)) != -1) {
                        throwIfInterrupted();
                        baos.write(buf, 0, r);
                    }
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
                String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                int i = json.indexOf("\"default_branch\"");
                if (i >= 0) {
                    int c = json.indexOf(':', i);
                    if (c > 0) {
                        int q1 = json.indexOf('"', c + 1);
                        int q2 = (q1 > 0) ? json.indexOf('"', q1 + 1) : -1;
                        if (q1 > 0 && q2 > q1) {
                            defaultBranch = json.substring(q1 + 1, q2);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("source metadata request interrupted");
            }
        }
        if (defaultBranch == null || defaultBranch.trim().isEmpty()) defaultBranch = "main";

        LinkedHashSet<String> branchCandidates = new LinkedHashSet<>();
        if (branchOverride != null && !branchOverride.trim().isEmpty()) {
            branchCandidates.add(branchOverride.trim());
        }
        branchCandidates.add(defaultBranch);
        branchCandidates.add("main");
        branchCandidates.add("master");

        String[] branches = branchCandidates.toArray(new String[0]);
        File workDir = new File("plugins/build/" + fileName + "-" + System.currentTimeMillis());
        if (!workDir.mkdirs()) throw new IOException("Unable to create build dir: " + workDir);
        try {

        File zipFile = new File(workDir, "repo.zip");
        boolean gotZip = false;
        for (String br : branches) {
            throwIfInterrupted();
            String zipUrl = "https://codeload.github.com" + repoPath + "/zip/refs/heads/" + br;
            try {
                HttpURLConnection c = openConnection(zipUrl, key, true);
                c.setRequestProperty("Accept", "application/zip");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (downloadLenient(zipFile, c)) {
                    gotZip = true;
                    break;
                }
            } catch (Throwable ignored) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("source archive request interrupted");
                }
            }
        }
        if (!gotZip) {
            logger.warning("Could not download repo zip for " + repoPath);
            throw new IOException("Could not download repo zip for " + repoPath);
        }
        if (UpdateOptions.debug) logger.info("[DEBUG] Downloaded repo zip to " + zipFile.getAbsolutePath());

        File repoRoot = new File(workDir, "repo");
        if (!repoRoot.exists() && !repoRoot.mkdirs()) {
            throw new IOException("Unable to create repo root");
        }
        unzipTo(zipFile, repoRoot);

        File buildRoot = findBuildRoot(repoRoot);
        if (buildRoot == null) {
            logger.warning("No Maven/Gradle build file found in " + repoRoot.getAbsolutePath());
            throw new IOException("No Maven/Gradle build file found in " + repoRoot.getAbsolutePath());
        }
        if (UpdateOptions.debug) logger.info("[DEBUG] Build root: " + buildRoot.getAbsolutePath());

        boolean isMaven = new File(buildRoot, "pom.xml").exists();
        boolean isGradle = !isMaven && (new File(buildRoot, "build.gradle").exists() || new File(buildRoot, "build.gradle.kts").exists());
        if (!isMaven && !isGradle) {
            logger.warning("Unknown build system at " + buildRoot);
            throw new IOException("Unknown build system at " + buildRoot);
        }

        int exit;
        BuildLibrarySupport.Provisioned libraries = BuildLibrarySupport.prepare(
                entryOptions, workDir.toPath().resolve("build-libraries"));
        try {
            if (isMaven) {
                File mvnw = findFile(buildRoot, "mvnw", "mvnw.cmd", "mvnw.bat");
                if (mvnw != null) setExecutable(mvnw);
                String cmd = (mvnw != null) ? mvnw.getAbsolutePath() : "mvn";
                exit = run(buildRoot, cmd, libraries.mavenArguments(
                        Arrays.asList("-q", "-U", "-DskipTests", "package")));
            } else {
                File grw = findFile(buildRoot, "gradlew", "gradlew.bat");
                if (grw != null) setExecutable(grw);
                String cmd = (grw != null) ? grw.getAbsolutePath() : "gradle";

                boolean hasShadow = fileContains(new File(buildRoot, "build.gradle"))
                        || fileContains(new File(buildRoot, "build.gradle.kts"));
                String task = hasShadow ? "shadowJar" : "build";
                exit = run(buildRoot, cmd, libraries.gradleArguments(
                        Arrays.asList("--no-daemon", "-x", "test", task)));
                if (exit != 0 && hasShadow) {
                    exit = run(buildRoot, cmd, libraries.gradleArguments(
                            Arrays.asList("--no-daemon", "-x", "test", "build")));
                }
            }
        } finally {
            libraries.close();
        }
        if (exit != 0) {
            logger.warning("Build failed for " + fileName + " (" + repoPath + ") with exit code " + exit);
            throw new IOException("Build failed for " + fileName + " (" + repoPath + ") with exit code " + exit);
        }


        File jar = pickBuiltJar(buildRoot);
        if (jar == null) {
            logger.warning("Could not locate built jar in " + buildRoot);
            throw new IOException("Could not locate built jar in " + buildRoot);
        }


        if (UpdateOptions.debug) logger.info("[DEBUG] Built jar selected: " + jar.getAbsolutePath());
        throwIfInterrupted();
        return processLocalFile(jar.toPath(), fileName, customPath, installMode, entryOptions);
        } finally {
            cleanupTreeQuietly(workDir.toPath());
        }
    }


    private Optional<Path> findSingleTopDir(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            Path only = null;
            int count = 0;
            for (Path p : ds) {
                count++;
                only = p;
                if (count > 1) return Optional.empty();
            }
            if (only != null && Files.isDirectory(only)) return Optional.of(only);
            return Optional.empty();
        }
    }

    private boolean hasFile(Path dir, String name) {
        return Files.exists(dir.resolve(name));
    }

    private boolean runGradle(Path projectRoot, boolean useWrapper) throws IOException, InterruptedException {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb;
        if (useWrapper) {
            File script = projectRoot.resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
            if (!isWindows) script.setExecutable(true);
            pb = new ProcessBuilder(script.getAbsolutePath(), "-q", "build", "-x", "test");
        } else {
            pb = new ProcessBuilder(isWindows ? "gradle.bat" : "gradle", "-q", "build", "-x", "test");
        }
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
            }
        }
        int code = p.waitFor();
        if (code != 0) logger.info("Gradle build exited with code " + code);
        return code == 0;
    }

    private boolean runMaven(Path projectRoot, boolean useWrapper) throws IOException, InterruptedException {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb;
        if (useWrapper) {
            File script = projectRoot.resolve(isWindows ? "mvnw.cmd" : "mvnw").toFile();
            if (!isWindows) script.setExecutable(true);
            pb = new ProcessBuilder(script.getAbsolutePath(), "-q", "-DskipTests", "package");
        } else {
            pb = new ProcessBuilder(isWindows ? "mvn.cmd" : "mvn", "-q", "-DskipTests", "package");
        }
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
            }
        }
        int code = p.waitFor();
        if (code != 0) logger.info("Maven build exited with code " + code);
        return code == 0;
    }

    Path selectBuiltPluginJar(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) return null;
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> jars;
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            jars = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isBuildOutputJar)
                    .filter(path -> !isAuxiliaryJar(path.getFileName().toString()))
                    .filter(path -> looksLikePluginJar(path.toFile()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        Collections.sort(jars, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                int scoreOrder = Integer.compare(
                        scoreJar(right.getFileName().toString().toLowerCase(Locale.ROOT)),
                        scoreJar(left.getFileName().toString().toLowerCase(Locale.ROOT)));
                if (scoreOrder != 0) return scoreOrder;
                String leftPath = normalizedRoot.relativize(left.toAbsolutePath().normalize()).toString();
                String rightPath = normalizedRoot.relativize(right.toAbsolutePath().normalize()).toString();
                int insensitive = leftPath.compareToIgnoreCase(rightPath);
                return insensitive != 0 ? insensitive : leftPath.compareTo(rightPath);
            }
        });
        return jars.isEmpty() ? null : jars.get(0);
    }

    private boolean isBuildOutputJar(Path path) {
        if (path == null || path.getFileName() == null
                || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) return false;
        String parentName = parent.getFileName().toString();
        if ("target".equalsIgnoreCase(parentName)) return true;
        Path grandparent = parent.getParent();
        return "libs".equalsIgnoreCase(parentName)
                && grandparent != null
                && grandparent.getFileName() != null
                && "build".equalsIgnoreCase(grandparent.getFileName().toString());
    }

    private boolean isAuxiliaryJar(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.contains("-sources")
                || name.contains("-javadoc")
                || name.startsWith("original-")
                || name.contains("tests")
                || name.contains("test-fixtures");
    }

    private boolean looksLikePluginJar(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            return jf.getEntry("plugin.yml") != null
                    || jf.getEntry("paper-plugin.yml") != null
                    || jf.getEntry("bungee.yml") != null
                    || jf.getEntry("velocity-plugin.json") != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void cleanupTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void cleanupQuietly(File f) {
        if (f != null && f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private boolean validateJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) return false;
        try (JarFile jf = new JarFile(jarFile)) {
            return jf.size() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean verifyChecksumIfProvided(File file, HttpURLConnection connection) {
        try {
            String sha256 = coalesceHeader(connection, "X-Checksum-SHA256", "X-Checksum-Sha256");
            String sha1 = coalesceHeader(connection, "X-Checksum-SHA1", "X-Checksum-Sha1");
            String md5 = coalesceHeader(connection, "X-Checksum-MD5");
            String etag = stripQuotes(connection.getHeaderField("ETag"));

            if (notBlank(sha256)) return digestMatches(file, "SHA-256", sha256);
            if (notBlank(sha1)) return digestMatches(file, "SHA-1", sha1);
            if (notBlank(md5)) return digestMatches(file, "MD5", md5);

            if (notBlank(etag) && isHex(etag)) {
                int len = etag.length();
                if (len == 32) return digestMatches(file, "MD5", etag);
                if (len == 40) return digestMatches(file, "SHA-1", etag);
                if (len == 64) return digestMatches(file, "SHA-256", etag);
            }
        } catch (Exception e) {
            logger.fine("Checksum verification skipped: " + e.getMessage());
            return true;
        }
        return true;
    }

    private boolean shouldSkipDuplicateInstall(File incoming, File target, String pluginName, String livePathOverride) {
        if (!UpdateOptions.ignoreDuplicates || incoming == null || !incoming.exists()) {
            return false;
        }
        if (hasSameContent(target, incoming)) {
            logDuplicateSkip(pluginName, target);
            return true;
        }

        File livePlugin;
        if (livePathOverride != null && !livePathOverride.trim().isEmpty()) {
            livePlugin = new File(livePathOverride);
            if (target != null && pathsEqual(livePlugin.toPath(), target.toPath())) {
                livePlugin = null;
            }
        } else {
            livePlugin = resolveLivePluginTarget(pluginName, target);
        }
        if (hasSameContent(livePlugin, incoming)) {
            logDuplicateSkip(pluginName, livePlugin);
            return true;
        }

        File derivedLivePlugin = null;
        if (shouldUseDerivedLiveFallback(livePathOverride, pluginName)) {
            derivedLivePlugin = resolveLikelyLivePluginFromUpdateTarget(target);
            if (hasSameContent(derivedLivePlugin, incoming)) {
                logDuplicateSkip(pluginName, derivedLivePlugin);
                return true;
            }
        }

        if (UpdateOptions.debug) {
            String targetPath = target != null ? target.getAbsolutePath() : "<none>";
            String livePath = livePlugin != null ? livePlugin.getAbsolutePath() : "<none>";
            String derivedPath = derivedLivePlugin != null ? derivedLivePlugin.getAbsolutePath() : "<none>";
            logger.info("[DEBUG] No duplicate match for " + pluginName + ": incoming=" + incoming.getAbsolutePath()
                    + ", target=" + targetPath
                    + ", live=" + livePath
                    + ", derivedLive=" + derivedPath);
        }
        return false;
    }

    private boolean shouldUseDerivedLiveFallback(String livePathOverride, String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return true;
        }
        if (livePathOverride == null || livePathOverride.trim().isEmpty()) {
            return true;
        }
        try {
            Path overridePath = Paths.get(livePathOverride).toAbsolutePath().normalize();
            Path defaultPath = Paths.get("plugins", pluginName + ".jar").toAbsolutePath().normalize();
            return pathsEqual(overridePath, defaultPath);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean hasSameContent(File existing, File incoming) {
        return existing != null
                && existing.exists()
                && existing.length() == incoming.length()
                && sameDigest(existing, incoming, "MD5");
    }

    private void logDuplicateSkip(String pluginName, File matched) {
        if (UpdateOptions.debug && matched != null) {
            logger.info("[DEBUG] Duplicate detected for " + pluginName + "; incoming jar matches " + matched.getAbsolutePath() + ". Skipping install.");
        }
    }

    private File resolveLivePluginTarget(String pluginName, File target) {
        if (!UpdateOptions.useUpdateFolder || target == null || pluginName == null || pluginName.trim().isEmpty()) {
            return null;
        }
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Path targetParent = targetPath.getParent();
        if (targetParent == null) {
            return null;
        }

        String configured = sanitizeCustomPath(UpdateOptions.updatePath);
        Path updateDir = (configured != null && !configured.isEmpty())
                ? Paths.get(configured).toAbsolutePath().normalize()
                : Paths.get("plugins", "update").toAbsolutePath().normalize();
        if (!pathsEqual(targetParent, updateDir)) {
            return null;
        }

        File live = Paths.get("plugins", pluginName + ".jar").toFile();
        if (!live.exists()) {
            return null;
        }
        if (pathsEqual(live.toPath(), targetPath)) {
            return null;
        }
        return live;
    }

    private File resolveLikelyLivePluginFromUpdateTarget(File target) {
        if (target == null) {
            return null;
        }
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Path targetParent = targetPath.getParent();
        if (targetParent == null) {
            return null;
        }
        Path fileName = targetPath.getFileName();
        if (fileName == null) {
            return null;
        }

        String configured = sanitizeCustomPath(UpdateOptions.updatePath);
        Path configuredUpdateDir = (configured != null && !configured.isEmpty())
                ? Paths.get(configured).toAbsolutePath().normalize()
                : null;
        Path defaultUpdateDir = Paths.get("plugins", "update").toAbsolutePath().normalize();

        if ((configuredUpdateDir != null && pathsEqual(targetParent, configuredUpdateDir))
                || pathsEqual(targetParent, defaultUpdateDir)) {
            Path defaultLive = Paths.get("plugins").toAbsolutePath().normalize().resolve(fileName);
            if (!pathsEqual(defaultLive, targetPath)) {
                return defaultLive.toFile();
            }
        }

        Path parentName = targetParent.getFileName();
        if (parentName != null && "update".equalsIgnoreCase(parentName.toString())) {
            Path base = targetParent.getParent();
            if (base != null) {
                Path siblingLive = base.resolve(fileName).normalize();
                if (!pathsEqual(siblingLive, targetPath)) {
                    return siblingLive.toFile();
                }
            }
        }
        return null;
    }

    private boolean pathsEqual(Path left, Path right) {
        if (left == null || right == null) {
            return false;
        }
        String a = left.toAbsolutePath().normalize().toString();
        String b = right.toAbsolutePath().normalize().toString();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private boolean sameDigest(File a, File b, String algorithm) {
        try {
            String da = computeDigestHex(a, algorithm);
            String db = computeDigestHex(b, algorithm);
            return da.equalsIgnoreCase(db);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean digestMatches(File file, String algorithm, String expectedHex) throws Exception {
        String actual = computeDigestHex(file, algorithm);
        boolean ok = actual.equalsIgnoreCase(expectedHex.trim());
        if (!ok) {
            logger.warning("Checksum mismatch for " + file.getName() + ": expected=" + expectedHex + ", actual=" + actual);
        }
        return ok;
    }

    private String computeDigestHex(File file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65536); DigestInputStream dis = new DigestInputStream(in, md)) {
            byte[] buf = new byte[65536];
            while (dis.read(buf) != -1) {
                throwIfInterrupted();
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private String coalesceHeader(HttpURLConnection conn, String... names) {
        for (String n : names) {
            String v = conn.getHeaderField(n);
            if (notBlank(v)) return stripQuotes(v);
        }
        return null;
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String stripQuotes(String s) {
        return s == null ? null : s.replace("\"", "").trim();
    }

    private boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }


    private void unzipTo(File zip, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 65536))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                throwIfInterrupted();
                Path outPath = destDir.toPath().resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destDir.toPath())) {
                    throw new IOException("Zip slip detected: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outPath.toFile()))) {
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            throwIfInterrupted();
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private File findBuildRoot(File root) {
        ArrayDeque<File> q = new ArrayDeque<File>();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) if (f.isDirectory()) q.add(f);
        }
        int depth = 0;
        while (!q.isEmpty() && depth <= 3) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                File d = q.poll();
                if (new File(d, "pom.xml").exists()) return d;
                if (new File(d, "build.gradle").exists() || new File(d, "build.gradle.kts").exists()) return d;
                File[] subs = d.listFiles(new FileFilter() {
                    public boolean accept(File f) {
                        return f.isDirectory();
                    }
                });
                if (subs != null) Collections.addAll(q, subs);
            }
            depth++;
        }
        return null;
    }

    private File findFile(File dir, String... names) {
        for (String n : names) {
            File f = new File(dir, n);
            if (f.exists()) return f;
        }
        return null;
    }

    private void setExecutable(File f) {
        try {
            f.setExecutable(true);
        } catch (Throwable ignored) {
        }
    }

    private boolean fileContains(File f) {
        if (!f.exists()) return false;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String s = new String(bytes, StandardCharsets.UTF_8);
            return s.indexOf("shadowJar") >= 0 || s.indexOf("com.github.johnrengelman.shadow") >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private int run(File cwd, String... cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process running = process;
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(running.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (UpdateOptions.debug) logger.info("[DEBUG] " + line);
                    }
                } catch (IOException ignored) {
                }
            }, "aup-build-output");
            outputReader.setDaemon(true);
            outputReader.start();
            while (process.isAlive()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("build interrupted");
                }
                process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            try {
                outputReader.join(1000L);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    process.destroyForcibly();
                }
            }
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            if (UpdateOptions.debug) logger.info("[DEBUG] Build failed to start: " + e.getMessage());
            return -1;
        }
    }

    private int run(File cwd, String executable, List<String> arguments) {
        List<String> command = new ArrayList<String>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(executable);
        if (arguments != null) command.addAll(arguments);
        return run(cwd, command.toArray(new String[command.size()]));
    }

    File pickBuiltJar(File buildRoot) {
        try {
            Path selected = selectBuiltPluginJar(buildRoot == null ? null : buildRoot.toPath());
            return selected == null ? null : selected.toFile();
        } catch (IOException selectionFailure) {
            if (UpdateOptions.debug) {
                logger.info("[DEBUG] Unable to inspect source-build outputs: "
                        + selectionFailure.getMessage());
            }
            return null;
        }
    }

    private int scoreJar(String n) {
        int s = 0;
        if (n.indexOf("shadow") >= 0 || n.indexOf("-all") >= 0 || n.indexOf("shaded") >= 0) s += 10;
        if (n.indexOf("plugin") >= 0) s += 3;
        if (n.endsWith(".jar")) s += 1;
        return s;
    }

    private void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

}
