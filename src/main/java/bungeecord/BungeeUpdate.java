package bungeecord;

import common.*;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;

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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BungeeUpdate extends Plugin {

    private PluginUpdater pluginUpdater;
    private File myFile;
    private ConfigManager cfgMgr;
    private RollbackMonitor rollbackMonitor;
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        new Metrics(this, 18456);

        cfgMgr = new ConfigManager(getDataFolder(), "config.yml");
        generateOrUpdateConfig();
        handleUpdateFolder();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
        pluginUpdater = new PluginUpdater(this.getLogger(), getDataFolder().toPath());
        configureRollback();
        File dataFolder = getDataFolder();
        myFile = new File(dataFolder, "list.yml");
        ensureListFileWithExample(myFile);
        periodUpdatePlugins();
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new UpdateCommand());
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new AupCommand(pluginUpdater, myFile, cfgMgr, this::reloadPluginConfig, this::runInstallAllWithRestart, Runnable::run));
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

    private void reloadPluginConfig() {
        this.cfgMgr.reloadConfig();
        generateOrUpdateConfig();
        handleUpdateFolder();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
        pluginUpdater.reloadPersistence();
        configureRollback();
        getLogger().info("AutoUpdatePlugins configuration reloaded.");
    }

    public void periodUpdatePlugins() {
        String cronExpression = cfgMgr.getString("updates.schedule.cron");

        boolean scheduled = false;
        if (cronExpression != null && !cronExpression.isEmpty()) {
            String tz = cfgMgr.getString("updates.schedule.timezone");
            scheduled = CronScheduler.scheduleRecurring(
                    cronExpression,
                    tz,
                    (delay, task) -> getProxy().getScheduler().schedule(this, task, delay, TimeUnit.SECONDS),
                    getLogger(),
                    () -> getProxy().getScheduler().runAsync(this, this::runConfiguredUpdateWithRestart)
            );
        }
        if (!scheduled) {
            scheduleIntervalUpdates();
        }
    }

    private void scheduleIntervalUpdates() {
        int interval = cfgMgr.getInt("updates.interval");
        long bootTime = cfgMgr.getInt("updates.bootTime");
        long periodSeconds = 60L * interval;
        getProxy().getScheduler().schedule(this,
                () -> getProxy().getScheduler().runAsync(this, this::runConfiguredUpdateWithRestart),
                bootTime, periodSeconds, TimeUnit.SECONDS);
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
            pluginUpdater.checkEntries(enabled, "waterfall", key, null);
            return;
        }
        pluginUpdater.updateEntries(automatic, "waterfall", key, anyUpdated -> {
            handleScheduledUpdateCompletion(anyUpdated);
            if (!manual.isEmpty()) {
                pluginUpdater.checkEntries(manual, "waterfall", key, null);
            }
        });
    }

    private void runInstallAllWithRestart() {
        pluginUpdater.updateList(myFile, "waterfall", cfgMgr.getString("updates.key"), this::handleScheduledUpdateCompletion);
    }

    private void handleScheduledUpdateCompletion(boolean anyUpdated) {
        if (!UpdateOptions.restartAfterUpdate) {
            return;
        }
        if (!anyUpdated) {
            if (UpdateOptions.debug) {
                getLogger().info("[DEBUG] No updates applied; skipping restart.");
            }
            return;
        }
        scheduleRestart();
    }

    private void scheduleRestart() {
        scheduleRestart(false);
    }

    private void scheduleRestart(boolean rollbackRestart) {
        if (!restartScheduled.compareAndSet(false, true)) {
            return;
        }
        long delaySec = Math.max(0, UpdateOptions.restartDelaySec);
        scheduleLegacyRestartMessage(delaySec, rollbackRestart);
        scheduleLegacyPreRestartCommand(delaySec, rollbackRestart);
        scheduleConfiguredRestartActions(delaySec, rollbackRestart);

        getProxy().getScheduler().schedule(this, () -> {
            if (!isRestartEnabled(rollbackRestart)) {
                restartScheduled.set(false);
                return;
            }
            getLogger().info(rollbackRestart
                    ? "[AutoUpdatePlugins] Restarting proxy to finalize rollback."
                    : "[AutoUpdatePlugins] Restarting proxy to apply updates.");
            ProxyServer.getInstance().stop();
        }, delaySec, TimeUnit.SECONDS);
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
                    getLogger().info("[DEBUG] Skipping restartCommands entry at " + when + "s; restartDelaySec is only " + totalDelaySec + "s.");
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
        getProxy().getScheduler().schedule(this, () -> {
            if (!isRestartEnabled(rollbackRestart)) {
                return;
            }
            action.run();
        }, Math.max(0, runAfterSec), TimeUnit.SECONDS);
    }

    private void broadcastRestartMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        ProxyServer.getInstance().broadcast(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', message)));
    }

    private void dispatchConsoleCommand(String rawCommand) {
        if (rawCommand == null) return;
        String command = rawCommand.trim();
        if (command.isEmpty()) return;
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) return;
        ProxyServer.getInstance().getPluginManager().dispatchCommand(ProxyServer.getInstance().getConsole(), command);
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

    private void applyHttpConfigBungee(Configuration config) {
        try {
            boolean sslVerify = config.getBoolean("http.sslVerify", true);
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
            getLogger().log(java.util.logging.Level.WARNING,
                    "Failed to apply AutoUpdatePlugins HTTP configuration", configError);
        }
    }

    private void handleUpdateFolder() {
        Path pluginsDir = getDataFolder() != null && getDataFolder().getParentFile() != null
                ? getDataFolder().getParentFile().toPath()
                : null;
        String configuredUpdatePath = null;
        if (cfgMgr != null) {
            try {
                configuredUpdatePath = cfgMgr.getString("paths.updatePath");
            } catch (Throwable ignored) {
            }
        }
        PluginUpdater.moveStagedUpdatesIfNeeded(getLogger(), "waterfall", pluginsDir, configuredUpdatePath);
    }

    private void configureRollback() {
        RollbackManager.refreshConfiguration(getLogger());
        String platform = "waterfall";
        if (UpdateOptions.rollbackEnabled) {
            RollbackManager.setRollbackListener((rollbackPlatform, pluginName) -> scheduleRestart(true));
            RollbackManager.processPendingRollbacks(getLogger(), platform);
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
        rollbackMonitor = RollbackMonitor.attach(ProxyServer.getInstance().getLogger(), getLogger(), platform);
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
            getLogger().log(java.util.logging.Level.WARNING,
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
            UpdateOptions.restartActions.addAll(RestartActionParser.parse(cfgMgr.getList("behavior.restartCommands"), getLogger()));
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
                getLogger().info("[DEBUG] Minecraft compatibility filtering is enabled, but proxies cannot infer the backend version; set metadata.minecraftVersion.");
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
            getLogger().log(java.util.logging.Level.WARNING,
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
        cfgMgr.addDefault("logging.updates.commandPageSize", 8, "Number of update history entries shown by /baup log.");
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

    @SuppressWarnings("unchecked")
    public class UpdateCommand extends Command {

        public UpdateCommand() {
            super("update", "autoupdateplugins.update");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (pluginUpdater.isUpdating()) {
                sender.sendMessage(ChatColor.RED + "An update is already in progress. Please wait.");
                return;
            }
            runConfiguredUpdateWithRestart();
            sender.sendMessage(ChatColor.AQUA + "Update check started.");
        }
    }

    @Override
    public void onDisable() {
        RollbackManager.setRollbackListener(null);
        if (rollbackMonitor != null) {
            rollbackMonitor.detach();
            rollbackMonitor = null;
        }
    }

    private String proxyPlatform() {
        if (hasClass("io.github.waterfallmc.waterfall.conf.WaterfallConfiguration")
                || containsIgnoreCase(ProxyServer.getInstance().getVersion(), "waterfall")
                || containsIgnoreCase(ProxyServer.getInstance().getName(), "waterfall")) {
            return "waterfall";
        }

        return "bungee";
    }

    private boolean isWaterfallProxy() {
        return "waterfall".equalsIgnoreCase(proxyPlatform());
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && needle != null
                && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
