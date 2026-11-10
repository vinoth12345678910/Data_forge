package forge.core;

/** forge.yml is not valid. The message names the key by its path. */
public class ConfigError extends RuntimeException {
    public ConfigError(String message) {
        super(message);
    }
}
