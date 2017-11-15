package ai.labs.backupservice;

import java.io.IOException;

/**
 * @author ginccc
 */
public interface IZipArchive {
    void createZip(String sourceDirPath, String targetZipPath) throws IOException;
}
