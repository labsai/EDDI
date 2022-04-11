package ai.labs.eddi.backup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author ginccc
 */
public interface IZipArchive {
    void createZip(String sourceDirPath, String targetZipPath) throws IOException;

    void unzip(InputStream zipFile, File targetDir) throws IOException;
}
