package io.sls.staticresources.impl.contentdelivery;

import io.sls.utilities.RuntimeUtilities;
import io.sls.utilities.SecurityUtilities;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author ginccc
 */
public class FingerprintGenerator {

    enum FingerprintType {
        DATE,
        HASH
    }

    public String calculateFingerprint(String content, FingerprintType type) {
        return calculateFingerprint(content, type, null);
    }

    public String calculateFingerprint(String content, FingerprintType type, Date now) {
        RuntimeUtilities.checkNotNull(content, "content");
        RuntimeUtilities.checkNotNull(type, "type");

        String fingerprint;
        switch (type) {
            case DATE:
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS");
                if (now == null) {
                    now = new Date();
                }
                fingerprint = simpleDateFormat.format(now);
                break;

            case HASH:
            default:
                fingerprint = SecurityUtilities.calculateHash(content);
                break;
        }

        return fingerprint;
    }

    public String addFingerprintToFileName(String filename, String fingerprint) throws IOException {
        RuntimeUtilities.checkNotNull(filename, "filename");
        RuntimeUtilities.checkNotNull(fingerprint, "fingerprint");

        StringBuilder sb = new StringBuilder(filename);

        if (sb.indexOf(".") == -1) {
            throw new IOException("filename does not contain an extension [e.g. example.js]");
        }

        sb.insert(sb.lastIndexOf("."), "_" + fingerprint);

        return sb.toString();
    }
}
