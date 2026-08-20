package velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import common.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

@Plugin(id = "autoupdateplugins", name = "AutoUpdatePlugins", version = "12.2.0", url = "https://www.spigotmc.org/resources/autoupdateplugins.109683/", authors = "NewAmazingPVP")
public final class VelocityUpdate {

    private PluginUpdater pluginUpdater;
    private final ProxyServer proxy;
    private File myFile;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    private ConfigManager cfgMgr;
    private final Logger logger = Logger.getLogger("AutoUpdatePlugins");
    private RollbackMonitor rollbackMonitor;
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);

    @Inject
    public VelocityUpdate(ProxyServer proxy, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
    }

    private void reloadPluginConfig() {
        this.cfgMgr.reloadConfig();
        generateOrUpdateConfig();
        handleUpdateFolder();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        pluginUpdater.reloadPersistence();
        configureRollback();
        logger.info("AutoUpdatePlugins configuration reloaded.");
    }

    private void handleUpdateFolder() {
        Path pluginsDir = dataDirectory != null ? dataDirectory.getParent() : null;
        String configuredUpdatePath = null;
        if (cfgMgr != null) {
            try {
                configuredUpdatePath = cfgMgr.getString("paths.updatePath");
            } catch (Throwable ignored) {
            }
        }
        PluginUpdater.moveStagedUpdatesIfNeeded(logger, "velocity", pluginsDir, configuredUpdatePath);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        metricsFactory.make(this, 18455);
        cfgMgr = new ConfigManager(dataDirectory.toFile(), "config.yml");
        generateOrUpdateConfig();
        handleUpdateFolder();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        pluginUpdater = new PluginUpdater(logger, dataDirectory);
        configureRollback();
        UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
        myFile = dataDirectory.resolve("list.yml").toFile();
        ensureListFileWithExample(myFile);
        periodUpdatePlugins();
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta updateMeta = commandManager.metaBuilder("update").plugin(this).build();
        commandManager.register(updateMeta, new UpdateCommand());

        // Proxy-only names avoid shadowing the backend server's /aup command for connected players.
        CommandMeta aupMeta = commandManager.metaBuilder("vaup").aliases("aupv", "autoupdateplugins-velocity").plugin(this).build();
        commandManager.register(aupMeta, new AupCommand(pluginUpdater, myFile, cfgMgr, this::reloadPluginConfig, this::runInstallAllWithRestart, task -> proxy.getScheduler().buildTask(this, task).schedule()));
    }

    private void configureRollback() {
        RollbackManager.refreshConfiguration(logger);
        String platform = "velocity";
        if (UpdateOptions.rollbackEnabled) {
            RollbackManager.setRollbackListener((rollbackPlatform, pluginName) -> scheduleVelocityRestart(true));
            RollbackManager.processPendingRollbacks(logger, platform);
            setupRollbackMonitor(platform);
        } else {
            RollbackManager.setRollbackListener(null);
            if (rollbackMonitor != null) {
                rollbackMonitor.detach();
                rollbackMonitor = null;
            }
        }
    }

    private void setupRollbackMonitor(String platform) {
        if (rollbackMonitor != null) {
            rollbackMonitor.detach();
            rollbackMonitor = null;
        }
        if (!UpdateOptions.rollbackEnabled) {
            return;
        }
        rollbackMonitor = RollbackMonitor.attach(java.util.logging.Logger.getLogger(""), logger, platform);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        RollbackManager.setRollbackListener(null);
        if (rollbackMonitor != null) {
            rollbackMonitor.detach();
            rollbackMonitor = null;
        }
    }

    private void ensureListFileWithExample(File file) {
        try {
            boolean created = false;
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                created = file.createNewFile();
            }
            if (created || file.length() == 0) {
                String example = "# Map plugin name to its update source URL\n"
                        + "# Example entry:\n"
                        + "AutoUpdatePlugins: \"https://github.com/NewAmazingPVP/AutoUpdatePlugins\"\n";

                Path filePath = file.toPath();
                Files.write(filePath, example.getBytes(StandardCharsets.UTF_8));

                getLogger().info("Created example list.yml with a sample entry.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void periodUpdatePlugins() {
        String cronExpression = cfgMgr.getString("updates.schedule.cron");

        boolean scheduled = false;
        if (cronExpression != null && !cronExpression.isEmpty()) {
            String tz = cfgMgr.getString("updates.schedule.timezone");
            scheduled = CronScheduler.scheduleRecurring(
                    cronExpression,
                    tz,
                    (delay, task) -> proxy.getScheduler().buildTask(this, task).delay(Duration.ofSeconds(delay)).schedule(),
                    getLogger(),
                    this::runConfiguredUpdateWithRestart
            );
        }
        if (!scheduled) {
            scheduleIntervalUpdates();
        }
    }

    private void scheduleIntervalUpdates() {
        long interval = cfgMgr.getInt("updates.interval");
        long bootTime = cfgMgr.getInt("updates.bootTime");

        proxy.getScheduler().buildTask(this, this::runConfiguredUpdateWithRestart).delay(Duration.ofSeconds(bootTime)).repeat(Duration.ofMinutes(interval)).schedule();
        getLogger().info("Scheduled updates with interval: " + interval + " minutes (First run in " + bootTime + " seconds)");
    }

    private void runConfiguredUpdateWithRestart() {
        if (UpdateOptions.manualMode) {
            runManualModeSchedule();
            return;
        }
        runInstallAllWithRestart();
    }

    private void runManualModeSchedule() {
        LinkedHashMap<String, String> enabled = ListEntryLoader.loadEnabledLinks(myFile);
        pluginUpdater.retainPendingUpdates(enabled.keySet());
        if (enabled.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> automatic = new LinkedHashMap<>();
        LinkedHashMap<String, String> manual = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : enabled.entrySet()) {
            if (ListEntryLoader.isAutoUpdateEntry(entry.getValue())) {
                automatic.put(entry.getKey(), entry.getValue());
            } else {
                manual.put(entry.getKey(), entry.getValue());
            }
        }
        String key = cfgMgr.getString("updates.key");
        if (automatic.isEmpty()) {
            pluginUpdater.checkEntries(enabled, "velocity", key, null);
            return;
        }
        pluginUpdater.updateEntries(automatic, "velocity", key, anyUpdated -> {
            handleScheduledUpdateCompletion(anyUpdated);
            if (!manual.isEmpty()) {
                pluginUpdater.checkEntries(manual, "velocity", key, null);
            }
        });
    }

    private void runInstallAllWithRestart() {
        pluginUpdater.updateList(myFile, "velocity", cfgMgr.getString("updates.key"), this::handleScheduledUpdateCompletion);
    }

    private void handleScheduledUpdateCompletion(boolean anyUpdated) {
        if (!UpdateOptions.restartAfterUpdate) {
            return;
        }
        if (!anyUpdated) {
            if (UpdateOptions.debug) {
                logger.info("[DEBUG] No updates applied; skipping Velocity restart.");
            }
            return;
        }
        scheduleVelocityRestart();
    }

    private void scheduleVelocityRestart() {
        scheduleVelocityRestart(false);
    }

    private void scheduleVelocityRestart(boolean rollbackRestart) {
        if (!restartScheduled.compareAndSet(false, true)) {
            return;
        }
        int delay = Math.max(0, UpdateOptions.restartDelaySec);
        long delaySec = Math.max(0, delay);

        scheduleLegacyRestartMessage(delaySec, rollbackRestart);
        scheduleLegacyPreRestartCommand(delaySec, rollbackRestart);
        scheduleConfiguredRestartActions(delaySec, rollbackRestart);

        proxy.getScheduler().buildTask(this, () -> {
            if (!isRestartEnabled(rollbackRestart)) {
                restartScheduled.set(false);
                return;
            }
            logger.info(rollbackRestart
                    ? "[AutoUpdatePlugins] Restarting Velocity to finalize rollback."
                    : "[AutoUpdatePlugins] Restarting Velocity to apply updates.");
            proxy.shutdown();
        }).delay(Duration.ofSeconds(delay)).schedule();
    }

    private boolean isRestartEnabled(boolean rollbackRestart) {
        return rollbackRestart ? UpdateOptions.restartAfterRollback : UpdateOptions.restartAfterUpdate;
    }

    private void scheduleLegacyRestartMessage(long totalDelaySec, boolean rollbackRestart) {
        String message = formatRestartTemplate(UpdateOptions.restartMessage, totalDelaySec, totalDelaySec);
        if (message == null || message.isEmpty()) return;
        scheduleRestartAction(0L, () -> broadcastRestartMessage(message), rollbackRestart);
    }

    private void scheduleLegacyPreRestartCommand(long totalDelaySec, boolean rollbackRestart) {
        String command = formatRestartTemplate(UpdateOptions.preRestartCommand, totalDelaySec, totalDelaySec);
        if (command == null || command.isEmpty()) return;
        scheduleRestartAction(0L, () -> dispatchConsoleCommand(command), rollbackRestart);
    }

    private void scheduleConfiguredRestartActions(long totalDelaySec, boolean rollbackRestart) {
        List<UpdateOptions.RestartAction> actions = new ArrayList<>(UpdateOptions.restartActions);
        for (UpdateOptions.RestartAction action : actions) {
            if (action == null) continue;
            long when = action.timeToRestartSec;
            long runAfter = totalDelaySec - when;
            if (runAfter < 0) {
                if (UpdateOptions.debug) {
                    logger.info("[DEBUG] Skipping restartCommands entry at " + when + "s; restartDelaySec is only " + totalDelaySec + "s.");
                }
                continue;
            }
            String message = formatRestartTemplate(action.message, when, totalDelaySec);
            String command = formatRestartTemplate(action.command, when, totalDelaySec);
            if ((message == null || message.isEmpty()) && (command == null || command.isEmpty())) {
                continue;
            }
            scheduleRestartAction(runAfter, () -> {
                if (message != null && !message.isEmpty()) {
                    broadcastRestartMessage(message);
                }
                if (command != null && !command.isEmpty()) {
                    dispatchConsoleCommand(command);
                }
            }, rollbackRestart);
        }
    }

    private void scheduleRestartAction(long runAfterSec, Runnable action, boolean rollbackRestart) {
        proxy.getScheduler().buildTask(this, () -> {
            if (!isRestartEnabled(rollbackRestart)) {
                return;
            }
            action.run();
        }).delay(Duration.ofSeconds(Math.max(0, runAfterSec))).schedule();
    }

    private void broadcastRestartMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(message);
        proxy.getAllPlayers().forEach(player -> player.sendMessage(component));
    }

    private void dispatchConsoleCommand(String rawCommand) {
        if (rawCommand == null) return;
        String command = rawCommand.trim();
        if (command.isEmpty()) return;
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) return;
        proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command);
    }

    private String formatRestartTemplate(String raw, long secondsUntilRestart, long totalDelaySec) {
        if (raw == null) return "";
        String msg = raw;
        String seconds = Long.toString(Math.max(0, secondsUntilRestart));
        String total = Long.toString(Math.max(0, totalDelaySec));
        msg = msg.replace("{delay}", seconds)
                .replace("{seconds}", seconds)
                .replace("%delay%", seconds)
                .replace("%seconds%", seconds)
                .replace("{timeToRestart}", seconds)
                .replace("%timeToRestart%", seconds)
                .replace("{totalDelay}", total)
                .replace("%totalDelay%", total)
                .replace("{totalSeconds}", total)
                .replace("%totalSeconds%", total);
        return msg.trim();
    }

    private void applyHttpConfigFromCfg() {
        try {
            String userAgent = cfgMgr.getString("http.userAgent");
            Map<String, String> headers = new HashMap<>();
            List<Map<String, Object>> list =
                    (List<Map<String, Object>>) cfgMgr.getList("http.headers");
            if (list != null) {
                for (Map<String, Object> m : list) {
                    Object n = m.get("name"), v = m.get("value");
                    if (n != null && v != null) headers.put(n.toString(), v.toString());
                }
            }
            PluginDownloader.setHttpHeaders(headers, userAgent);
        } catch (Throwable ignored) {
        }
        try {
            String type = cfgMgr.getString("proxy.type");
            String host = cfgMgr.getString("proxy.host");
            int port = cfgMgr.getInt("proxy.port");
            if (type != null && host != null && port > 0) {
                if ("HTTP".equalsIgnoreCase(type)) {
                    System.setProperty("http.proxyHost", host);
                    System.setProperty("http.proxyPort", Integer.toString(port));
                    System.setProperty("https.proxyHost", host);
                    System.setProperty("https.proxyPort", Integer.toString(port));
                } else if ("SOCKS".equalsIgnoreCase(type)) {
                    System.setProperty("socksProxyHost", host);
                    System.setProperty("socksProxyPort", Integer.toString(port));
                } else {
                    System.clearProperty("http.proxyHost");
                    System.clearProperty("http.proxyPort");
                    System.clearProperty("https.proxyHost");
                    System.clearProperty("https.proxyPort");
                    System.clearProperty("socksProxyHost");
                    System.clearProperty("socksProxyPort");
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            boolean sslVerify = cfgMgr.getBoolean("http.sslVerify");
            UpdateOptions.sslVerify = sslVerify;
            if (!sslVerify) {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] c, String a) {
                            }

                            public void checkServerTrusted(X509Certificate[] c, String a) {
                            }

                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
            }
        } catch (Throwable configError) {
            logger.log(java.util.logging.Level.WARNING,
                    "Failed to apply AutoUpdatePlugins HTTP configuration", configError);
        }
    }

    private void applyBehaviorConfig() {
        try {
            UpdateOptions.zipFileCheck = cfgMgr.getBoolean("behavior.zipFileCheck");
            UpdateOptions.ignoreDuplicates = cfgMgr.getBoolean("behavior.ignoreDuplicates");
            UpdateOptions.autoCompileEnable = cfgMgr.getBoolean("behavior.autoCompile.enable");
            UpdateOptions.autoCompileWhenNoJarAsset = cfgMgr.getBoolean("behavior.autoCompile.whenNoJarAsset");
            UpdateOptions.autoCompileBranchNewerMonths = cfgMgr.getInt("behavior.autoCompile.branchNewerMonths");
            UpdateOptions.allowPreReleaseDefault = cfgMgr.getBoolean("behavior.allowPreRelease");
            UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
            UpdateOptions.manualMode = cfgMgr.getBoolean("behavior.manualMode");
            UpdateOptions.debug = cfgMgr.getBoolean("behavior.debug");
            UpdateOptions.restartAfterUpdate = cfgMgr.getBoolean("behavior.restartAfterUpdate");
            UpdateOptions.restartDelaySec = Math.max(0, cfgMgr.getInt("behavior.restartDelaySec"));
            String restartMsg = cfgMgr.getString("behavior.restartMessage");
            UpdateOptions.restartMessage = (restartMsg != null) ? restartMsg : UpdateOptions.restartMessage;
            String preRestartCmd = cfgMgr.getString("behavior.preRestartCommand");
            UpdateOptions.preRestartCommand = (preRestartCmd != null) ? preRestartCmd.trim() : "";
            UpdateOptions.restartActions.clear();
            UpdateOptions.restartActions.addAll(RestartActionParser.parse(cfgMgr.getList("behavior.restartCommands"), logger));
            UpdateOptions.tempPath = cfgMgr.getString("paths.tempPath");
            UpdateOptions.updatePath = cfgMgr.getString("paths.updatePath");
            UpdateOptions.rollbackPath = cfgMgr.getString("paths.rollbackPath");
            UpdateOptions.filePath = cfgMgr.getString("paths.filePath");
            UpdateOptions.maxParallel = Math.max(1, cfgMgr.getInt("performance.maxParallel"));
            UpdateOptions.connectTimeoutMs = Math.max(1000, cfgMgr.getInt("performance.connectTimeoutMs"));
            UpdateOptions.readTimeoutMs = Math.max(1000, cfgMgr.getInt("performance.readTimeoutMs"));
            UpdateOptions.perDownloadTimeoutSec = Math.max(0, cfgMgr.getInt("performance.perDownloadTimeoutSec"));
            UpdateOptions.maxRetries = Math.max(1, cfgMgr.getInt("performance.maxRetries"));
            UpdateOptions.backoffBaseMs = Math.max(0, cfgMgr.getInt("performance.backoffBaseMs"));
            UpdateOptions.backoffMaxMs = Math.max(UpdateOptions.backoffBaseMs, cfgMgr.getInt("performance.backoffMaxMs"));
            UpdateOptions.maxPerHost = Math.max(1, cfgMgr.getInt("performance.maxPerHost"));
            if (pluginUpdater == null || !pluginUpdater.isUpdating()) UpdateOptions.hostSemaphores.clear();
            UpdateOptions.rollbackEnabled = cfgMgr.getBoolean("rollback.enabled");
            UpdateOptions.restartAfterRollback = cfgMgr.contains("rollback.restartAfterRollback")
                    ? cfgMgr.getBoolean("rollback.restartAfterRollback")
                    : true;
            UpdateOptions.rollbackMaxCopies = Math.max(0, cfgMgr.getInt("rollback.maxBackups"));

            UpdateOptions.metadataCacheEnabled = cfgMgr.getBoolean("metadata.enabled");
            String metadataFile = cfgMgr.getString("metadata.file");
            UpdateOptions.metadataCacheFile = metadataFile == null || metadataFile.trim().isEmpty()
                    ? "metadata.json" : metadataFile.trim();
            UpdateOptions.metadataCacheTtlMinutes = Math.max(0, cfgMgr.getInt("metadata.ttlMinutes"));
            UpdateOptions.skipDownloadWhenMetadataUnchanged = cfgMgr.getBoolean("metadata.skipDownloadWhenUnchanged");
            UpdateOptions.cacheDirectUrlHeadMetadata = cfgMgr.getBoolean("metadata.directUrlHeadMetadata");
            String configuredMinecraftVersion = cfgMgr.getString("metadata.minecraftVersion");
            UpdateOptions.serverMinecraftVersion = configuredMinecraftVersion == null
                    ? "" : configuredMinecraftVersion.trim();

            UpdateOptions.modrinthMinecraftVersionCheck = cfgMgr.getBoolean("compatibility.modrinthMinecraftVersionCheck");
            UpdateOptions.hangarMinecraftVersionCheck = cfgMgr.getBoolean("compatibility.hangarMinecraftVersionCheck");
            UpdateOptions.strictMinecraftVersionMetadata = cfgMgr.getBoolean("compatibility.strictMinecraftVersionMetadata");
            if (UpdateOptions.debug && UpdateOptions.serverMinecraftVersion.isEmpty()
                    && (UpdateOptions.modrinthMinecraftVersionCheck || UpdateOptions.hangarMinecraftVersionCheck)) {
                logger.info("[DEBUG] Minecraft compatibility filtering is enabled, but proxies cannot infer the backend version; set metadata.minecraftVersion.");
            }

            String versionPolicy = cfgMgr.getString("versioning.policy");
            UpdateOptions.versionPolicyDefault = versionPolicy == null || versionPolicy.trim().isEmpty()
                    ? "any" : versionPolicy.trim();
            String unknownVersionPolicy = cfgMgr.getString("versioning.unknownVersionPolicy");
            UpdateOptions.unknownVersionPolicy = unknownVersionPolicy == null || unknownVersionPolicy.trim().isEmpty()
                    ? "allow" : unknownVersionPolicy.trim();
            UpdateOptions.allowSameVersionSnapshotUpdates = cfgMgr.getBoolean("versioning.allowSameVersionSnapshotUpdates");
            UpdateOptions.allowSameVersionReleaseHashUpdates = cfgMgr.getBoolean("versioning.allowSameVersionReleaseHashUpdates");

            UpdateOptions.updateLogEnabled = cfgMgr.getBoolean("logging.updates.enabled");
            String logPath = cfgMgr.getString("logging.updates.path");
            UpdateOptions.updateLogPath = logPath == null || logPath.trim().isEmpty() ? "logs" : logPath.trim();
            String logPattern = cfgMgr.getString("logging.updates.filePattern");
            UpdateOptions.updateLogFilePattern = logPattern == null || logPattern.trim().isEmpty()
                    ? "yyyy-MM-dd'.log'" : logPattern.trim();
            UpdateOptions.updateLogCommandPageSize = Math.max(1, cfgMgr.getInt("logging.updates.commandPageSize"));
            UpdateOptions.updateLogIncludeUnchanged = cfgMgr.getBoolean("logging.updates.includeUnchanged");
            UpdateOptions.updateLogIncludeChecks = cfgMgr.getBoolean("logging.updates.includeChecks");

            UpdateOptions.githubTokens.clear();
            Map<String, Object> tokenSection = cfgMgr.getSection("updates.githubTokens");
            if (tokenSection != null) {
                for (Map.Entry<String, Object> tokenEntry : tokenSection.entrySet()) {
                    if (tokenEntry.getKey() != null && tokenEntry.getValue() != null) {
                        String token = tokenEntry.getValue().toString().trim();
                        if (!token.isEmpty()) {
                            String account = tokenEntry.getKey().trim();
                            UpdateOptions.githubTokens.put(account, token);
                            UpdateOptions.githubTokens.put(account.toLowerCase(Locale.ROOT), token);
                        }
                    }
                }
            }
            UpdateOptions.gitlabTokens.clear();
            Map<String, Object> gitlabTokenSection = cfgMgr.getSection("updates.gitlabTokens");
            if (gitlabTokenSection != null) {
                for (Map.Entry<String, Object> tokenEntry : gitlabTokenSection.entrySet()) {
                    if (tokenEntry.getKey() != null && tokenEntry.getValue() != null) {
                        String token = tokenEntry.getValue().toString().trim();
                        if (!token.isEmpty()) {
                            String account = tokenEntry.getKey().trim();
                            UpdateOptions.gitlabTokens.put(account, token);
                            UpdateOptions.gitlabTokens.put(account.toLowerCase(Locale.ROOT), token);
                        }
                    }
                }
            }
            UpdateOptions.voxelShopTokens.clear();
            Map<String, Object> voxelTokenSection = cfgMgr.getSection("updates.voxelShopTokens");
            if (voxelTokenSection != null) {
                for (Map.Entry<String, Object> tokenEntry : voxelTokenSection.entrySet()) {
                    if (tokenEntry.getKey() != null && tokenEntry.getValue() != null) {
                        String token = tokenEntry.getValue().toString().trim();
                        if (!token.isEmpty()) {
                            String account = tokenEntry.getKey().trim();
                            UpdateOptions.voxelShopTokens.put(account, token);
                            UpdateOptions.voxelShopTokens.put(account.toLowerCase(Locale.ROOT), token);
                        }
                    }
                }
            }
            List<Map<String, Object>> uaList =
                    (List<Map<String, Object>>) cfgMgr.getList("http.userAgents");
            UpdateOptions.userAgents.clear();
            if (uaList != null) {
                for (Map<String, Object> m : uaList) {
                    Object v = m.get("ua");
                    if (v != null) UpdateOptions.userAgents.add(v.toString());
                }
            }
            List<?> filters = cfgMgr.getList("rollback.filters");
            UpdateOptions.rollbackFilters.clear();
            if (filters != null) {
                for (Object o : filters) {
                    if (o != null) UpdateOptions.rollbackFilters.add(o.toString());
                }
            }
        } catch (Throwable configError) {
            logger.log(java.util.logging.Level.WARNING,
                    "Failed to apply AutoUpdatePlugins behavior configuration", configError);
        }
    }


    private void generateOrUpdateConfig() {
        cfgMgr.addDefault("updates.interval", 120, "Time between plugin updates in minutes");
        cfgMgr.addDefault("updates.bootTime", 50, "Delay in seconds after server startup before updating");
        cfgMgr.addDefault("updates.schedule.cron", "", "Experimental: A cron expression to schedule updates. Overrides interval and bootTime if set.");
        cfgMgr.addDefault("updates.schedule.timezone", "UTC", "The timezone for the cron schedule.");
        cfgMgr.addDefault("updates.key", "", "GitHub token for Actions/authenticated requests (optional)");
        cfgMgr.addDefault("updates.githubTokens", new LinkedHashMap<String, String>(), "Optional named GitHub tokens. Select one per entry with ?account=name.");
        cfgMgr.addDefault("updates.gitlabTokens", new LinkedHashMap<String, String>(), "Optional named GitLab PRIVATE-TOKEN values. Select one per entry with ?account=name.");
        cfgMgr.addDefault("updates.voxelShopTokens", new LinkedHashMap<String, String>(), "Optional named VoxelShop user tokens. Select one per entry with ?account=name.");

        cfgMgr.addDefault("http.userAgent", "AutoUpdatePlugins", "HTTP User-Agent override (leave blank to auto-rotate)");
        cfgMgr.addDefault("http.headers", new ArrayList<>(), "Extra headers: list of {name, value}");
        ArrayList<Map<String, String>> uas = new ArrayList<>();
        HashMap<String, String> ua1 = new HashMap<>();
        ua1.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
        HashMap<String, String> ua2 = new HashMap<>();
        ua2.put("ua", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        HashMap<String, String> ua3 = new HashMap<>();
        ua3.put("ua", "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/126.0");
        uas.add(ua1);
        uas.add(ua2);
        uas.add(ua3);
        cfgMgr.addDefault("http.userAgents", uas, "Optional pool of User-Agents; rotates on retry");

        cfgMgr.addDefault("proxy.type", "DIRECT", "Proxy type: DIRECT | HTTP | SOCKS");
        cfgMgr.addDefault("proxy.host", "proxy.example.com", "Proxy host");
        cfgMgr.addDefault("proxy.port", 8080, "Proxy port");

        cfgMgr.addDefault("behavior.zipFileCheck", true, "Open .jar/.zip to verify integrity");
        cfgMgr.addDefault("behavior.ignoreDuplicates", true, "Skip replace when MD5 is identical");
        cfgMgr.addDefault("behavior.allowPreRelease", false, "Allow GitHub pre-releases by default");
        cfgMgr.addDefault("behavior.debug", false, "Enable verbose debug logging (toggle via /aup debug)");
        cfgMgr.addDefault("behavior.autoCompile.enable", false, "Enable source build fallback for GitHub");
        cfgMgr.addDefault("behavior.autoCompile.whenNoJarAsset", true, "Build when release has no jar assets");
        cfgMgr.addDefault("behavior.autoCompile.branchNewerMonths", 6, "Build when default branch is newer by N months");
        cfgMgr.addDefault("behavior.useUpdateFolder", true, "Use the update folder for updates. This requires a server restart to apply the update. For Velocity, it may require two restarts.");
        cfgMgr.addDefault("behavior.manualMode", false, "Check for updates without installing them automatically. Scheduled runs still check on updates.interval; entries with ?auto=true still install.");
        cfgMgr.addDefault("behavior.restartAfterUpdate", false, "Restart the proxy automatically after updates.");
        cfgMgr.addDefault("behavior.restartDelaySec", 5, "Delay in seconds before restarting after updates.");
        cfgMgr.addDefault("behavior.restartMessage", "Server restarting to apply updates.", "Broadcast message before restarting (supports {delay}).");
        cfgMgr.addDefault("behavior.preRestartCommand", "", "Console command to run when restart is scheduled (optional).");
        cfgMgr.addDefault("behavior.restartCommands", new ArrayList<>(), "Timed pre-restart actions: list of {timeToRestart, command, message}.");

        cfgMgr.addDefault("metadata.enabled", true, "Cache lightweight provider metadata to avoid unchanged artifact downloads.");
        cfgMgr.addDefault("metadata.file", "metadata.json", "Metadata cache file, relative to the plugin data folder unless absolute.");
        cfgMgr.addDefault("metadata.ttlMinutes", 0, "Metadata cache lifetime in minutes (0 keeps entries until provider metadata changes).");
        cfgMgr.addDefault("metadata.skipDownloadWhenUnchanged", true, "Skip payload downloads when provider metadata and the target jar are unchanged.");
        cfgMgr.addDefault("metadata.directUrlHeadMetadata", false, "Use HTTP HEAD metadata for direct URLs when the origin supports it.");
        cfgMgr.addDefault("metadata.minecraftVersion", "", "Backend Minecraft version override; proxies do not guess this value.");

        cfgMgr.addDefault("compatibility.modrinthMinecraftVersionCheck", true, "Require Modrinth releases compatible with the configured Minecraft version.");
        cfgMgr.addDefault("compatibility.hangarMinecraftVersionCheck", true, "Require Hangar releases compatible with the configured Minecraft version.");
        cfgMgr.addDefault("compatibility.strictMinecraftVersionMetadata", false, "Reject provider releases whose Minecraft compatibility is unknown.");

        cfgMgr.addDefault("versioning.policy", "any", "Default version policy: any, patch, same-major, or none.");
        cfgMgr.addDefault("versioning.unknownVersionPolicy", "allow", "Whether updates with unparseable versions are allowed or blocked.");
        cfgMgr.addDefault("versioning.allowSameVersionSnapshotUpdates", true, "Allow same-version snapshot builds when their metadata or hash changes.");
        cfgMgr.addDefault("versioning.allowSameVersionReleaseHashUpdates", false, "Allow same-version release builds when their hash changes.");

        cfgMgr.addDefault("logging.updates.enabled", true, "Write update decisions to daily history files.");
        cfgMgr.addDefault("logging.updates.path", "logs", "Update history directory, relative to the plugin data folder unless absolute.");
        cfgMgr.addDefault("logging.updates.filePattern", "yyyy-MM-dd'.log'", "Java date pattern used for daily update history file names.");
        cfgMgr.addDefault("logging.updates.commandPageSize", 8, "Number of update history entries shown by /vaup log.");
        cfgMgr.addDefault("logging.updates.includeUnchanged", false, "Include ordinary duplicate/unchanged payload decisions in update history.");
        cfgMgr.addDefault("logging.updates.includeChecks", true, "Include available updates found by check-only runs in update history.");


        cfgMgr.addDefault("paths.tempPath", "", "Custom temp/cache path (optional)");
        cfgMgr.addDefault("paths.updatePath", "", "Custom update folder path (optional)");
        cfgMgr.addDefault("paths.rollbackPath", "", "Custom rollback storage path (optional)");
        cfgMgr.addDefault("paths.filePath", "", "Custom final plugin path (optional)");

        cfgMgr.addDefault("performance.maxParallel", 4, "Parallel downloads (1 to CPU count)");
        cfgMgr.addDefault("performance.connectTimeoutMs", 10000, "HTTP connect timeout in ms");
        cfgMgr.addDefault("performance.readTimeoutMs", 30000, "HTTP read timeout in ms");
        cfgMgr.addDefault("performance.perDownloadTimeoutSec", 0, "Optional per-download cap (0=off)");
        cfgMgr.addDefault("performance.maxRetries", 4, "Retries per download on 403/429/5xx");
        cfgMgr.addDefault("performance.backoffBaseMs", 500, "Backoff base in ms for retries");
        cfgMgr.addDefault("performance.backoffMaxMs", 5000, "Backoff max in ms for retries");
        cfgMgr.addDefault("performance.maxPerHost", 3, "Max concurrent downloads per host");

        ArrayList<String> rollbackFilters = new ArrayList<>();
        rollbackFilters.add("Unsupported API version");
        rollbackFilters.add("Could not load plugin");
        rollbackFilters.add("Error occurred while enabling");
        rollbackFilters.add("Unsupported MC version");
        rollbackFilters.add("You are running an unsupported server version");
        cfgMgr.addDefault("rollback.enabled", false, "Monitor server logs for plugin load errors and restore the previous jar automatically.");
        cfgMgr.addDefault("rollback.restartAfterRollback", true, "Restart automatically after rollback restores plugin files.");
        cfgMgr.addDefault("rollback.maxBackups", 3, "Maximum rollback snapshots to retain per plugin.");
        cfgMgr.addDefault("rollback.filters", rollbackFilters, "Case-insensitive regex patterns that trigger rollback when matched in logs.");

        cfgMgr.saveConfig();
    }

    private Logger getLogger() {
        return logger;
    }


    public class UpdateCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("autoupdateplugins.update");
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (pluginUpdater.isUpdating()) {
                source.sendMessage(Component.text("An update is already in progress. Please wait.").color(NamedTextColor.RED));
                return;
            }
            runConfiguredUpdateWithRestart();
            source.sendMessage(Component.text("Update check started.").color(NamedTextColor.AQUA));
        }
    }
}
