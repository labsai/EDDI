/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import com.mongodb.MongoDriverInformation;

/**
 * Builds the {@link MongoDriverInformation} used to identify EDDI in MongoDB
 * server-side telemetry and handshake metadata.
 */
public final class MongoDriverInfoFactory {

    static final String DRIVER_NAME = "EDDI";

    private MongoDriverInfoFactory() {
    }

    /**
     * Returns a {@link MongoDriverInformation} with {@code driverName = "EDDI"}
     * and, when available, the implementation version from the JAR manifest.
     */
    public static MongoDriverInformation build() {
        MongoDriverInformation.Builder builder = MongoDriverInformation.builder().driverName(DRIVER_NAME);
        Package pkg = MongoDriverInfoFactory.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        if (version != null) {
            builder.driverVersion(version);
        }
        return builder.build();
    }
}
