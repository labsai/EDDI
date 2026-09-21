/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * @author ginccc
 */
public interface IZipArchive {

    /**
     * Zips {@code sourceDirPath} into {@code targetZipPath}.
     * <p>
     * {@code allowedBaseDir} is mandatory and is the containment boundary: the
     * target must resolve below it. A two-argument overload used to default that
     * boundary to the process working directory, which permitted writing an archive
     * anywhere under the whole checkout; it had no production caller.
     */
    void createZip(String sourceDirPath, String targetZipPath, Path allowedBaseDir) throws IOException;

    void unzip(InputStream zipFile, File targetDir) throws IOException;
}
