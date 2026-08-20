package common;

/** Platform information used while resolving compatible provider releases. */
public final class ServerEnvironment {
    public final String platform;
    public final String minecraftVersion;

    public ServerEnvironment(String platform, String minecraftVersion) {
        this.platform = platform == null ? "" : platform.trim();
        this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
    }

    public static ServerEnvironment of(String platform) {
        return new ServerEnvironment(platform, UpdateOptions.serverMinecraftVersion);
    }
}
