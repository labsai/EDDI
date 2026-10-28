/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * {@code eddi.branding.*}: the brand an operator sets in configuration.
 * <p>
 * Unlocked (the default), these values are the <em>seed</em>: they apply to
 * every field the stored brand document leaves unset, so a fresh install comes
 * up branded and an admin can still change it on the settings page. With
 * {@code locked=true} they are authoritative: the stored document is ignored
 * and every write answers 409, for installs that keep their brand in Git.
 * <p>
 * Every value goes through the same validator as a REST write, and an invalid
 * one fails startup with the property's name.
 */
@ConfigMapping(prefix = "eddi.branding")
public interface BrandingConfig {

    /** Properties are authoritative and the brand cannot be edited at runtime. */
    @WithDefault("false")
    boolean locked();

    /** Shown wherever the UIs say "EDDI". At most 40 characters. */
    Optional<String> productName();

    /** For tight spaces (the sidebar monogram). At most 12 characters. */
    Optional<String> shortName();

    /** One line for link previews and the welcome page. At most 120 characters. */
    Optional<String> tagline();

    /** {@code eddi.branding.color.*}. */
    Color color();

    /** Show "Powered by EDDI". On by default. */
    Optional<Boolean> poweredBy();

    /** Keep the update check UI while branded. Off by default. */
    Optional<Boolean> showUpdateCheck();

    /**
     * A directory holding the brand's images under fixed names:
     * {@code logo.(svg|png|webp)}, {@code logo-dark.*}, {@code icon.*},
     * {@code favicon.ico} (or {@code .png}/{@code .svg}), {@code og-image.*}. Each
     * file goes through the upload checks; a rejected one fails startup.
     */
    Optional<String> assetsDir();

    interface Color {
        Optional<String> primary();

        Optional<String> primaryForeground();

        Optional<String> darkPrimary();

        Optional<String> darkPrimaryForeground();

        Optional<String> chatAccent();
    }
}
