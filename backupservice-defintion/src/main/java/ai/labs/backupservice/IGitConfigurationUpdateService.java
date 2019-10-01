package ai.labs.backupservice;

import java.io.InputStream;

/**
 * @author rpi
 */

public interface IGitConfigurationUpdateService {
    void updateBot(InputStream zippedBotConfigFiles, String botId);
}
