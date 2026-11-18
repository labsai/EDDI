/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to start on a datastore type EDDI does not know, or one that
 * contradicts the active profile, before any observer touches a database.
 * <p>
 * Two misconfigurations used to boot MongoDB silently against the built-in
 * default URL: a spelling nothing matched ({@code Postgres}, a typo), and the
 * {@code postgres} profile combined with {@code EDDI_DATASTORE_TYPE=mongodb}
 * (the profile activates the JDBC datasource and sets the type to
 * {@code postgres}, but an environment variable outranks the profile's line, so
 * the {@code .env.example} default {@code EDDI_DATASTORE_TYPE=mongodb} won, and
 * MongoDB was selected while its health check was disabled by the profile).
 * Runs first among the startup observers, like {@code AuthStartupGuard}: a
 * misconfigured deployment is refused on its configuration alone.
 */
@ApplicationScoped
public class DatastoreTypeGuard {

    static final String POSTGRES_PROFILE = "postgres";

    @ConfigProperty(name = "eddi.datastore.type", defaultValue = "mongodb")
    String datastoreType;

    void onStart(@Observes
    @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        check(datastoreType, ConfigUtils.isProfileActive(POSTGRES_PROFILE));
    }

    /**
     * @throws IllegalArgumentException
     *             for an unknown type
     * @throws IllegalStateException
     *             when the {@code postgres} profile is active but the type selects
     *             MongoDB
     */
    static void check(String configuredType, boolean postgresProfileActive) {
        DatastoreType type = DatastoreType.parse(configuredType);
        if (postgresProfileActive && type != DatastoreType.POSTGRES) {
            throw new IllegalStateException("The 'postgres' profile is active (QUARKUS_PROFILE=postgres) but eddi.datastore.type is '" + configuredType
                    + "', which selects MongoDB. The profile sets the type to postgres itself, so an EDDI_DATASTORE_TYPE environment variable or a "
                    + "-Deddi.datastore.type that says otherwise (for example the 'mongodb' in a copied .env.example) overrides it. Remove it or "
                    + "set it to 'postgres'.");
        }
    }
}
