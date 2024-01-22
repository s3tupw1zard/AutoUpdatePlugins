package io.github.aplini.autoupdateplugins;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;


public final class AutoUpdatePlugins extends JavaPlugin implements Listener, CommandExecutor, TabExecutor {
    boolean lock = false;
    boolean awaitReload = false;
    Timer timer = null;

    File tempFile;
    FileConfiguration temp;

    List<String> logList = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("aup")).setExecutor(this);

        // bStats
        if(getConfig().getBoolean("bStats", true)){
            new Metrics(this, 20629);
        }

        // Disable certificate verification
        if(getConfig().getBoolean("disableCertificateVerification", false)) {
            // Create a TrustManager that will accept any certificate
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    }
            };
            // Get the default SSLContext
            SSLContext sslContext;
            try {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            //Set the default SSLSocketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        }
    }
    @Override
    public void onDisable() {}


    public void saveDate(){
        try {
            temp.save(tempFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @EventHandler // Server startup completion event
    public void onServerLoad(ServerLoadEvent event) {
        // asynchronous
        CompletableFuture.runAsync(this::setTimer);

        // Check for outdated configuration
        if(getConfig().getBoolean("debugLog", false)){
            getLogger().warning("`debugLog` 配置已弃用, 请使用 `logLevel` - 启用哪些日志等级");
        }

        // Check for missing configuration
        if(getConfig().get("setRequestProperty") == null){
            getLogger().warning("缺少配置 `setRequestProperty` - HTTP 请求中编辑请求头");
        }
    }

    public void loadConfig(){
        reloadConfig();

        tempFile = new File("./plugins/AutoUpdatePlugins/temp.yml");
        temp = YamlConfiguration.loadConfiguration(tempFile);
        if(temp.get("previous") == null){
            temp.set("previous", new HashMap<>());
        }
        saveDate();
    }
    public void setTimer(){
        long startupDelay = getConfig().getLong("startupDelay", 64);
        long startupCycle = getConfig().getLong("startupCycle", 61200);
        // Check if the update interval is too low
        if(startupCycle < 256 && !getConfig().getBoolean("disableUpdateCheckIntervalTooLow", false)){
            getLogger().warning("### 更新检查间隔过低将造成性能问题! ###");
            startupCycle = 512;
        }
        // timer
        getLogger().info("更新检查将在 "+ startupDelay +" 秒后运行, 并以每 "+ startupCycle +" 秒的间隔重复运行");
        if(timer != null){
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        timer.schedule(new updatePlugins(), startupDelay * 1000, startupCycle * 1000);
    }

    @Override // run command
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        //Default output plug-in information
        if(args.length == 0){
            sender.sendMessage("IpacEL > AutoUpdatePlugins: 自动更新插件");
            sender.sendMessage("  指令: ");
            sender.sendMessage("    - /aup reload - 重载配置");
            sender.sendMessage("    - /aup update - 运行更新");
            sender.sendMessage("    - /aup log - 查看完整日志");
            return true;
        }

        // Reload configuration
        else if(args[0].equals("reload")){
            if(lock){
                awaitReload = true;
                sender.sendMessage("[AUP] 当前正在运行更新, 配置重载将被推迟");
                return true;
            }
            loadConfig();
            sender.sendMessage("[AUP] 已完成重载");
            setTimer();
            return true;
        }

        //debug mode
        else if(args[0].equals("update")){
            if(lock && !getConfig().getBoolean("disableLook", false)){
                sender.sendMessage("[AUP] 已有一个未完成的更新正在运行");
                return true;
            }
            sender.sendMessage("[AUP] 更新开始运行!");
            new Timer().schedule(new updatePlugins(), 0);
            return true;
        }

        // View log
        else if(args[0].equals("log")){
            sender.sendMessage("[AUP] 完整日志:");
            for(String li : logList){
                sender.sendMessage("  | " + li);
            }
            return true;
        }
        return false;
    }

    @Override //command completion
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (args.length == 1) {
            return  List.of(
                    "reload",   // Reload plugin
                    "update"    // run update
            );
        }
        return null;
    }

    private class updatePlugins extends TimerTask {
        String _nowFile = "[???] ";     // The name of the current file
        String _nowParser = "[???] ";   // The name of the parser used to parse direct links
        int _fail = 0;              // Number of update failures
        int _success = 0;           // Update the number of successes
        int _allRequests = 0;       // Total number of network requests made
        long _startTime;            // The final time consuming
        float _allFileSize = 0;     // Total downloaded file size

        // Store the current plug-in configuration here
        String c_file;              // file name
        String c_url;               // Download link
        String c_tempPath;          // Download cache path, global configuration is used by default
        String c_updatePath;        // Update storage path, use global configuration by default
        String c_filePath;          // Final installation path, global configuration is used by default
        String c_get;               // Regular expression to find a single file, the first one is selected by default. Github, Jenkins, Modrinth only
        boolean c_zipFileCheck;     // Enable zip file integrity checking, default true
        boolean c_getPreRelease;    // Allow downloading of pre-release versions, default false. Github only

        public void run() {
            // Prevent duplicate runs
            if(lock && !getConfig().getBoolean("disableLook", false)){
                log(logLevel.WARN, "### 更新程序重复启动或出现错误? 尝试提高更新检查间隔? ###");
                return;
            }
            lock = true;
            logList = new ArrayList<>();    // Clear the previous log
            _startTime = System.nanoTime(); // Record running time
            // new thread
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                log(logLevel.INFO, "[## 开始运行自动更新 ##]");

                List<?> list = (List<?>) getConfig().get("list");
                if(list == null){
                    log(logLevel.WARN, "更新列表配置错误? ");
                    return;
                }

                for(Object _li : list){
                    _fail ++;

                    Map<?, ?> li = (Map<?, ?>) _li;
                    if(li == null){
                        log(logLevel.WARN, "更新列表配置错误? 项目为空");
                        continue;
                    }

                    // Check basic configuration
                    c_file = (String) SEL(li.get("file"), "");
                    c_url = ((String) SEL(li.get("url"), "")).trim();
                    if(c_file.isEmpty() || c_url.isEmpty()){
                        log(logLevel.WARN, "更新列表配置错误? 缺少基本配置");
                        continue;
                    }

                    _nowFile = "["+ c_file +"] "; // Plugin name used to display logs
                    c_tempPath = getPath(getConfig().getString("tempPath", "./plugins/AutoUpdatePlugins/temp/")) + c_file;

                    // each individual configuration
                    c_updatePath = getPath((String) SEL(li.get("updatePath"), getConfig().getString("updatePath", "./plugins/update/"))) + c_file;
                    c_filePath = getPath((String) SEL(li.get("filePath"), getConfig().getString("filePath", "./plugins/"))) + c_file;
                    c_get = (String) SEL(li.get("get"), "");
                    c_zipFileCheck = (boolean) SEL(li.get("zipFileCheck"), true);
                    c_getPreRelease = (boolean) SEL(li.get("getPreRelease"), false);

                    // Download files to cache directory
                    log(logLevel.DEBUG, "正在检查更新...");
                    String dUrl = getFileUrl(c_url, c_get);
                    if(dUrl == null){
                        log(logLevel.WARN, _nowFile + _nowParser +"解析文件直链时出现错误, 将跳过此更新");
                        continue;
                    }
                    dUrl = checkURL(dUrl);
//                    outInfo(dUrl);

                    // Enable last update logging and checking
                    String feature = "";
                    String pPath = "";
                    if(getConfig().getBoolean("enablePreviousUpdate", true)){
                        // Get file size
                        feature = getFeature(dUrl);
                        // Is it the same as the previous version?
                        pPath = "previous." + li.toString().hashCode();
                        if (temp.get(pPath) != null) {
                            // Check data differences
                            if(temp.getString(pPath + ".dUrl", "").equals(dUrl) &&
                                    temp.getString(pPath + ".feature", "").equals(feature)){
                                log(logLevel.MARK, "[缓存] 文件已是最新版本");
                                _fail--;
                                continue;
                            }
                        }
                    }

                    if(!downloadFile(dUrl, c_tempPath)){
                        log(logLevel.WARN, "下载文件时出现异常, 将跳过此更新");
                        delFile(c_tempPath);
                        continue;
                    }

                    // Record file size
                    float fileSize = new File(c_tempPath).length();
                    _allFileSize += fileSize;

                    // File integrity check
                    if(c_zipFileCheck && !isJARFileIntact(c_tempPath)){
                        log(logLevel.WARN, "[Zip 完整性检查] 文件不完整, 将跳过此更新");
                        delFile(c_tempPath);
                        continue;
                    }

                    // At this point it has been ensured that the file (information) is normal
                    if(getConfig().getBoolean("enablePreviousUpdate", true)){
                        // update data
                        temp.set(pPath + ".file", c_file);
                        temp.set(pPath + ".time", nowDate());
                        temp.set(pPath + ".dUrl", dUrl);
                        temp.set(pPath + ".feature", feature);
                    }

                    // Implement the function of running system commands here

                     // Hash value check, if the new file hash is equal to the one in the update directory, or equal to the running version, no update is needed
                    String tempFileHas = fileHash(c_tempPath);
                    String updatePathFileHas = fileHash(c_updatePath);
                    if(Objects.equals(tempFileHas, updatePathFileHas) || Objects.equals(tempFileHas, fileHash(c_filePath))){
                        log(logLevel.MARK, "文件已是最新版本");
                        _fail --;
                        delFile(c_tempPath);
                        continue;
                    }

                    // Get file size of old version
                    float oldFileSize = updatePathFileHas.equals("null") ? new File(c_filePath).length() : new File(c_updatePath).length();

                    // Move to update directory
                    try {
                        Files.move(Path.of(c_tempPath), Path.of(c_updatePath), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        log(logLevel.WARN, e.getMessage());
                    }

                    // The update is completed and the file size changes are displayed
                    log(logLevel.DEBUG, "更新完成 ["+ String.format("%.2f", oldFileSize / 1048576) +"MB] -> ["+ String.format("%.2f", fileSize / 1048576) +"MB]");
                    _success ++;

                    _nowFile = "[???] ";
                    _nowParser = "[???] ";
                    _fail --;
                }

                saveDate();

                log(logLevel.INFO, "[## 更新全部完成 ##]");
                log(logLevel.INFO, "  - 耗时: "+ Math.round((System.nanoTime() - _startTime) / 1_000_000_000.0) +" 秒");

                String st = "  - ";
                if(_fail != 0){st += "失败: "+ _fail +", ";}
                if(_success != 0){st += "更新: "+ _success +", ";}
                log(logLevel.INFO, st +"完成: "+ list.size());

                log(logLevel.INFO, "  - 网络请求: "+ _allRequests +", 下载文件: "+ String.format("%.2f", _allFileSize / 1048576) +"MB");

                lock = false;

                // Run deferred configuration reload
                if(awaitReload){
                    awaitReload = false;
                    loadConfig();
                    getLogger().info("[AUP] 已完成重载");
                    setTimer();
                }
            });
            executor.shutdown();
        }

        // Try opening the jar file to determine if the file is complete
        public boolean isJARFileIntact(String filePath) {
            // Whether to enable integrity checking
            if(getConfig().getBoolean("zipFileCheck", true)){
                try {
                    JarFile jarFile = new JarFile(new File(filePath));
                    jarFile.close();
                    return true;
                } catch (ZipException e) { // File is incomplete
                    return false;
                } catch (Exception e) { // Other exceptions
                    return false;
                }
            }else{
                return true;
            }
        }

        // Calculate file hash
        public String fileHash(String filePath) {
            try {
                byte[] data = Files.readAllBytes(Paths.get(filePath));
                byte[] hash = MessageDigest.getInstance("MD5").digest(data);
                return new BigInteger(1, hash).toString(16);
            } catch (Exception e) {
//                outInfo(logLevel.WARN, e.getMessage()); // An exception will be output when the file does not exist
            }
            return "null";
        }

        // Get direct links to some files
        public  String getFileUrl(String _url, String matchFileName) {
            // Remove the final slash from the URL
            String url = _url.replaceAll("/$", "");

            if(url.contains("://github.com/")){ // Github releases
                _nowParser = "[Github] ";
                // Get the path "/ApliNi/Chat2QQ"
                Matcher matcher = Pattern.compile("/([^/]+)/([^/]+)$").matcher(url);
                if(matcher.find()){
                    String data;
                    Map<?, ?> map;
                    // Whether to allow pre-release downloads
                    if(c_getPreRelease){
                        // Get the first version of all releases
                        data = httpGet("https://api.github.com/repos" + matcher.group(0) + "/releases");
                        if(data == null){return null;}
                        map = (Map<?, ?>) new Gson().fromJson(data, ArrayList.class).get(0);
                    }else{
                        // Get the latest version
                        data = httpGet("https://api.github.com/repos" + matcher.group(0) + "/releases/latest");
                        if(data == null){return null;}
                        map = new Gson().fromJson(data, HashMap.class);
                    }
                    // Traverse the list of published files
                    ArrayList<?> assets = (ArrayList<?>) map.get("assets");
                    for(Object _li : assets){
                        Map<?, ?> li = (Map<?, ?>) _li;
                        String fileName = (String) li.get("name");
                        if(matchFileName.isEmpty() || Pattern.compile(matchFileName).matcher(fileName).matches()){
                            String dUrl = (String) li.get("browser_download_url");
                            log(logLevel.DEBUG, _nowParser +"找到版本: "+ dUrl);
                            return dUrl;
                        }
                    }
                    log(logLevel.WARN, "[Github] 没有匹配的文件: "+ url);
                    return null;
                }
                log(logLevel.WARN, "[Github] 未找到存储库路径: "+ url);
                return null;
            }

            else if(url.contains("://ci.")){ // Jenkins
                _nowParser = "[Jenkins] ";
                // https://ci.viaversion.com/view/ViaBackwards/job/ViaBackwards-DEV/lastSuccessfulBuild/artifact/build/libs/ViaBackwards-4.10.0-23w51b-SNAPSHOT.jar
                String data = httpGet(url +"/lastSuccessfulBuild/api/json");
                if(data == null){return null;}
                Map<?, ?> map = new Gson().fromJson(data, HashMap.class);
                ArrayList<?> artifacts = (ArrayList<?>) map.get("artifacts");
                // Traverse the list of published files
                for(Object _li : artifacts){
                    Map<?, ?> li = (Map<?, ?> ) _li;
                    String fileName = (String) li.get("fileName");
                    if(matchFileName.isEmpty() || Pattern.compile(matchFileName).matcher(fileName).matches()){
                        String dUrl = url +"/lastSuccessfulBuild/artifact/"+ li.get("relativePath");
                        log(logLevel.DEBUG, _nowParser +"找到版本: "+ dUrl);
                        return dUrl;
                    }
                }
                log(logLevel.WARN, "[Jenkins] 没有匹配的文件: "+ url);
                return null;
            }

            else if(url.contains("://www.spigotmc.org/")){ // Spigot page
                _nowParser = "[Spigot] ";
                // Get plugin ID
                Matcher matcher = Pattern.compile("([0-9]+)$").matcher(url);
                if(matcher.find()){
                    String dUrl = "https://api.spiget.org/v2/resources/"+ matcher.group(1) +"/download";
                    log(logLevel.DEBUG, _nowParser +"找到版本: "+ dUrl);
                    return dUrl;
                }
                log(logLevel.WARN, "[Spigot] URL 解析错误, 不包含插件 ID?: "+ url);
                return null;
            }

            else if(url.contains("://modrinth.com/")){ // Modrinth page
                _nowParser = "[Modrinth] ";
                Matcher matcher = Pattern.compile("/([^/]+)$").matcher(url);
                if(matcher.find()) {
                    String data = httpGet("https://api.modrinth.com/v2/project"+ matcher.group(0) +"/version");
                    if(data == null){return null;}
                    // 0 For the latest version
                    Map<?, ?> map = (Map<?, ?>) ((ArrayList<?>) new Gson().fromJson(data, ArrayList.class)).get(0);
                    ArrayList<?> files = (ArrayList<?>) map.get("files");

                    // Traverse the list of published files
                    for(Object _li : files){
                        Map<?, ?> li = (Map<?, ?>) _li;
                        String fileName = (String) li.get("filename");
                        if(matchFileName.isEmpty() || Pattern.compile(matchFileName).matcher(fileName).matches()){
                            String dUrl = (String) li.get("url");
                            log(logLevel.DEBUG, _nowParser +"找到版本: "+ dUrl);
                            return dUrl;
                        }
                    }
                    log(logLevel.WARN, "[Modrinth] 没有匹配的文件: "+ url);
                    return null;
                }
                log(logLevel.WARN, "[Modrinth] URL 解析错误, 未找到项目名称: "+ url);
                return null;
            }

            else if(url.contains("://dev.bukkit.org/")){ // Bukkit page
                _nowParser = "[Bukkit] ";
                String dUrl = url +"/files/latest";
                log(logLevel.DEBUG, _nowParser +"找到版本: "+ dUrl);
                return dUrl;
            }

            else if(url.contains("://builds.guizhanss.com/")){ // 鬼斩构建站
                _nowParser = "[鬼斩构建站] ";
                // https://builds.guizhanss.com/SlimefunGuguProject/AlchimiaVitae/master

                // Get path "/ApliNi/plugin/master"
                Matcher matcher = Pattern.compile("/([^/]+)/([^/]+)/([^/]+)$").matcher(url);
                if(matcher.find()){
                    // Get the first version of all releases
                    String data = httpGet("https://builds.guizhanss.com/api/builds" + matcher.group(0));
                    if(data == null){return null;}
                    ArrayList<?> arr = (ArrayList<?>) new Gson().fromJson(data, HashMap.class).get("data");
                    Map<?, ?> map = (Map<?, ?>) arr.get(arr.size() - 1); // Get the last item

                    String dUrl = "https://builds.guizhanss.com/r2"+ matcher.group(0) +"/"+ map.get("target");
                    log(logLevel.DEBUG, _nowParser +"找到版本: "+ dUrl);
                    return dUrl;
                }
                log(logLevel.WARN, _nowParser +"未找到存储库路径: "+ url);
                return null;
            }

            else if(url.contains("://legacy.curseforge.com/")){ // CurseForge page
                _nowParser = "[CurseForge] ";
                // https://legacy.curseforge.com/minecraft/bukkit-plugins/dynmap
                // data-project-id="31620"

                String html = httpGet(url); // Download the html web page and get the project-id
                if(html == null){return null;}
                Matcher matcher = Pattern.compile("data-project-id=\"([0-9+])\"").matcher(html);
                if(matcher.find()){
                    String data = httpGet(matcher.group(1));
                    if(data == null){return null;}
                    ArrayList<?> arr = (ArrayList<?>) new Gson().fromJson(data, ArrayList.class);
                    Map<?, ?> map = (Map<?, ?>) arr.get(arr.size() - 1); // Get the last item
                    String dUrl = (String) map.get("downloadUrl");
                    log(logLevel.DEBUG, _nowParser +"找到版本: "+ dUrl);
                    return dUrl;
                }
                log(logLevel.WARN, _nowParser +"未找到项目 ID: "+ url);
                return null;
            }

            else{ // No matches
                _nowParser = "[URL] ";
                log(logLevel.DEBUG, _nowParser + _url);
                return _url;
            }
        }

        // If in1 is empty, select in2, otherwise select in1
        public Object SEL(Object in1, Object in2) {
            if(in1 == null){
                return in2;
            }
            return in1;
        }

        // Create a directory
        public String getPath(String path) {
            Path directory = Paths.get(path);
            try {
                Files.createDirectories(directory);
                return directory + "/";
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // http Request to get string
        public String httpGet(String url) {
            HttpURLConnection cxn = getHttpCxn(url);
            if(cxn == null){return null;}
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(cxn.getInputStream()));
                StringBuilder stringBuilder = new StringBuilder();
                String line;
                while((line = reader.readLine()) != null){
                    stringBuilder.append(line);
                }
                reader.close();
                cxn.disconnect();
                return String.valueOf(stringBuilder);
            } catch (Exception e) {
                log(logLevel.NET_WARN, "[HTTP] "+ e.getMessage());
            }
            cxn.disconnect();
            return null;
        }

        // Download the file to the specified location and use the specified file name
        public boolean downloadFile(String url, String path){
            delFile(path); // Delete old files that may exist
            HttpURLConnection cxn = getHttpCxn(url);
            if(cxn == null){return false;}
            try {
                BufferedInputStream in = new BufferedInputStream(cxn.getInputStream());
                Path savePath = Path.of(path);
                Files.copy(in, savePath, StandardCopyOption.REPLACE_EXISTING);
                cxn.disconnect();
                return true;
            } catch (Exception e) {
                log(logLevel.NET_WARN, "[HTTP] "+ e.getMessage());
            }
            cxn.disconnect();
            return false;
        }

        // Obtain some feature information through HEAD request
        public String getFeature(String url){
            String out = "_"+ nowDate().hashCode();
            try {
                HttpURLConnection cxn = (HttpURLConnection) new URI(url).toURL().openConnection();
                cxn.setRequestMethod("HEAD");
                _allRequests ++;

                int cl = cxn.getContentLength();
                String lh = cxn.getHeaderField("Location");

                if(cl != -1) {
                    out = "cl_"+ cl;
                }
                else if(lh != null){
                    out = "lh_"+ lh.hashCode();
                }
                cxn.disconnect();
            } catch (Exception e) {
                log(logLevel.NET_WARN, "[HTTP.HEAD] "+ e.getMessage());
            }
            return out;
        }

        // Get HTTP connection
        public HttpURLConnection getHttpCxn(String url){
            HttpURLConnection cxn = null;
            try {
                cxn = (HttpURLConnection) new URI(url).toURL().openConnection();
                cxn.setRequestMethod("GET");
                _allRequests ++;
                // Populate request header data
                List<?> list = (List<?>) getConfig().get("setRequestProperty");
                if(list != null){
                    for(Object _li : list) {
                        Map<?, ?> li = (Map<?, ?>) _li;
                        cxn.setRequestProperty((String) li.get("name"), (String) li.get("value"));
                    }
                }
                if(cxn.getResponseCode() == 200){
                    return cxn;
                }
                cxn.disconnect();
                log(logLevel.NET_WARN, "[HTTP] 请求失败? ("+ cxn.getResponseCode() +"): "+ url);
            } catch (Exception e) {
                log(logLevel.NET_WARN, "[HTTP] "+ e.getMessage());
            }
            if(cxn != null){cxn.disconnect();}
            return null;
        }

        // Output as detailed logs as possible during plugin updates
        public void log(logLevel level, String text){

            // Get which log levels the user has enabled
            List<String> userLogLevel = getConfig().getStringList("logLevel");
            if(userLogLevel.isEmpty()){
                userLogLevel = List.of("DEBUG", "MARK", "INFO", "WARN", "NET_WARN");
            }

            if(userLogLevel.contains(level.name)){
                switch(level.name){
                    case "DEBUG":
                        getLogger().info(_nowFile + text);
                        break;
                    case "INFO":
                        getLogger().info(text);
                        break;
                    case "MARK":
                        // Some newer versions of consoles seem to have difficulty displaying colors
                        Bukkit.getConsoleSender().sendMessage(level.color +"[AUP] "+ _nowFile + text);
                        break;
                    case "WARN", "NET_WARN":
                        getLogger().warning(_nowFile + text);
                        break;
                }
            }

            // Add style code according to the log level and record it to logList
             // Non-INFO logs add _nowFile text
            logList.add(level.color + (level.name.equals("INFO") ? "" : _nowFile) +  text);
        }
        enum logLevel {
            // Allow ignored INFO
            DEBUG("", "DEBUG"),
            // INFO that cannot be ignored
            INFO("", "INFO"),
            // Used to mark task completion
            MARK("§a", "MARK"),
            // warn
            WARN("§e", "WARN"),
            // Network request pattern warning
            NET_WARN("§e", "NET_WARN"),
            ;
            private final String color;
            private final String name;
            logLevel(String color, String name) {
                this.color = color;
                this.name = name;
            }
        }

        // Get formatted time
        public String nowDate(){
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return now.format(formatter);
        }

        // Handling special characters in URLs
        public String checkURL(String url){
            // Clear spaces before and after
             // Escape spaces in the URL
            try {
                return new URI(url.trim()
                        .replace(" ", "%20"))
                        .toASCIIString();
            } catch (URISyntaxException e) {
                log(logLevel.WARN, "[URI] URL 无效或不规范: "+ url);
                return null;
            }
        }

        // Delete Files
        public void delFile(String path){
            new File(path).delete();
            // Delete file outInfo(logLevel.WARN, _nowFile +"[FILE] Failed to delete file: "+ path);
        }
    }
}
