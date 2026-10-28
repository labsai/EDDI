/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandDocument;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared fixtures for the branding tests: a config, a store and images. */
public final class BrandingTestSupport {

    private BrandingTestSupport() {
    }

    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    public static final byte[] ICO = {0, 0, 1, 0, 1, 0, 16, 16, 0, 0};
    public static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    public static byte[] svg(String body) {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">" + body + "</svg>").getBytes(StandardCharsets.UTF_8);
    }

    /** A mutable {@link BrandingConfig}; every value unset by default. */
    public static final class Config implements BrandingConfig {
        public boolean locked;
        public String productName;
        public String shortName;
        public String tagline;
        public String primary;
        public String primaryForeground;
        public String darkPrimary;
        public String darkPrimaryForeground;
        public String chatAccent;
        public Boolean poweredBy;
        public Boolean showUpdateCheck;
        public String assetsDir;

        @Override
        public boolean locked() {
            return locked;
        }

        @Override
        public Optional<String> productName() {
            return Optional.ofNullable(productName);
        }

        @Override
        public Optional<String> shortName() {
            return Optional.ofNullable(shortName);
        }

        @Override
        public Optional<String> tagline() {
            return Optional.ofNullable(tagline);
        }

        @Override
        public Color color() {
            return new Color() {
                @Override
                public Optional<String> primary() {
                    return Optional.ofNullable(primary);
                }

                @Override
                public Optional<String> primaryForeground() {
                    return Optional.ofNullable(primaryForeground);
                }

                @Override
                public Optional<String> darkPrimary() {
                    return Optional.ofNullable(darkPrimary);
                }

                @Override
                public Optional<String> darkPrimaryForeground() {
                    return Optional.ofNullable(darkPrimaryForeground);
                }

                @Override
                public Optional<String> chatAccent() {
                    return Optional.ofNullable(chatAccent);
                }
            };
        }

        @Override
        public Optional<Boolean> poweredBy() {
            return Optional.ofNullable(poweredBy);
        }

        @Override
        public Optional<Boolean> showUpdateCheck() {
            return Optional.ofNullable(showUpdateCheck);
        }

        @Override
        public Optional<String> assetsDir() {
            return Optional.ofNullable(assetsDir);
        }
    }

    /**
     * An in-memory {@link IBrandingStore} with real compare-and-set semantics, and
     * counters so a test can see when the database was read.
     */
    public static class MemoryStore implements IBrandingStore {
        public BrandDocument document;
        public final Map<BrandAssetSlot, BrandAsset> assets = new EnumMap<>(BrandAssetSlot.class);
        public final AtomicInteger reads = new AtomicInteger();
        public final AtomicInteger assetReads = new AtomicInteger();
        /** Writes that will lose their compare-and-set, as if another node won. */
        public int failNextWrites;

        @Override
        public synchronized Optional<BrandDocument> read() {
            reads.incrementAndGet();
            return Optional.ofNullable(document);
        }

        @Override
        public synchronized boolean compareAndSet(long expectedRevision, BrandDocument next) {
            if (failNextWrites > 0) {
                failNextWrites--;
                return false;
            }
            long current = document == null ? 0 : document.revision();
            if (current != expectedRevision) {
                return false;
            }
            document = next;
            return true;
        }

        @Override
        public synchronized Optional<BrandAsset> readAsset(BrandAssetSlot slot) {
            assetReads.incrementAndGet();
            return Optional.ofNullable(assets.get(slot));
        }

        @Override
        public synchronized void writeAsset(BrandAsset asset) {
            assets.put(asset.slot(), asset);
        }

        @Override
        public synchronized void deleteAsset(BrandAssetSlot slot) {
            assets.remove(slot);
        }
    }
}
