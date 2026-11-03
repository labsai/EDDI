/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandAssetValidator;
import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * Brands Swagger UI at runtime (white-label plan R6).
 * <p>
 * Quarkus builds Swagger UI's files once, at build time: the title, logo alt
 * and footer from {@code quarkus.swagger-ui.title} / {@code footer}, and the
 * logo, favicon and stylesheet from {@code META-INF/branding/}. They are served
 * by a static handler from the generated web-jar resources. This route runs
 * before that handler, for exactly four files, and only while a brand is set:
 * <ul>
 * <li>{@code index.html} (and the directory URL): the page, rewritten by
 * {@link SwaggerUiBranding#page} — the product's title, its logo or name, no
 * footer;</li>
 * <li>{@code logo.png}: the brand's own logo, in whatever format it was
 * uploaded;</li>
 * <li>{@code favicon.ico}: the brand's favicon, or its icon;</li>
 * <li>{@code style.css}: the stylesheet with the amber accents in the brand's
 * colours ({@link SwaggerUiBranding#stylesheet}).</li>
 * </ul>
 * Anything else, and all of it while unbranded, falls through to the static
 * handler, byte for byte as before. So does a file this route cannot find on
 * the classpath — dev mode serves Swagger UI from the build directory, so there
 * the page stays unbranded.
 */
@ApplicationScoped
public class BrandedSwaggerUiRoute {

    private static final Logger LOGGER = Logger.getLogger(BrandedSwaggerUiRoute.class);

    /** Where Quarkus puts the generated Swagger UI files on the classpath. */
    static final String GENERATED = "META-INF/io.smallrye_smallrye-open-api-ui__jar/META-INF/resources/openapi-ui/";
    /**
     * The source of the generated stylesheet; read when the generated copy is not
     * on the classpath.
     */
    static final String STYLE_SOURCE = "META-INF/branding/style.css";

    record Key(String file, long revision) {
    }

    private final BrandingService branding;
    private final String root;
    private final Cache<Key, String> rendered = Caffeine.newBuilder().maximumSize(8).build();

    @Inject
    public BrandedSwaggerUiRoute(BrandingService branding,
            @ConfigProperty(name = "quarkus.swagger-ui.path", defaultValue = "swagger-ui") String swaggerPath,
            @ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "q") String nonApplicationRoot) {
        this.branding = branding;
        this.root = swaggerRoot(swaggerPath, nonApplicationRoot);
    }

    /** The absolute path Swagger UI is served under, as Quarkus resolves it. */
    static String swaggerRoot(String swaggerPath, String nonApplicationRoot) {
        String path = swaggerPath.startsWith("/") ? swaggerPath : "/" + strip(nonApplicationRoot) + "/" + swaggerPath;
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String strip(String segment) {
        String s = segment.startsWith("/") ? segment.substring(1) : segment;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    void register(@Observes Router router) {
        route(router, root + "/", "index.html");
        route(router, root + "/index.html", "index.html");
        route(router, root + "/logo.png", "logo.png");
        route(router, root + "/favicon.ico", "favicon.ico");
        route(router, root + "/style.css", "style.css");
    }

    private void route(Router router, String path, String file) {
        router.route(path).order(BrandedShellRoute.ORDER).method(HttpMethod.GET).method(HttpMethod.HEAD)
                .blockingHandler(context -> handle(context, file));
    }

    void handle(RoutingContext context, String file) {
        ResolvedBrand brand;
        try {
            brand = branding.current(BrandRequest.ofHost(context.request().getHeader("Host")));
        } catch (RuntimeException e) {
            LOGGER.debugf("Brand unavailable for Swagger UI: %s", e.getMessage());
            context.next();
            return;
        }
        if (!brand.branded()) {
            context.next();
            return;
        }
        switch (file) {
            case "index.html" -> text(context, brand, file, "text/html;charset=UTF-8");
            case "style.css" -> text(context, brand, file, "text/css;charset=UTF-8");
            case "logo.png" -> image(context, brand, BrandAssetSlot.LOGO);
            case "favicon.ico" -> image(context, brand, ownSlot(brand, BrandAssetSlot.FAVICON, BrandAssetSlot.ICON));
            default -> context.next();
        }
    }

    private static BrandAssetSlot ownSlot(ResolvedBrand brand, BrandAssetSlot preferred, BrandAssetSlot fallback) {
        return brand.assets().get(preferred) != null ? preferred : brand.assets().get(fallback) != null ? fallback : null;
    }

    private void text(RoutingContext context, ResolvedBrand brand, String file, String contentType) {
        Optional<String> body = render(brand, file);
        if (body.isEmpty()) {
            context.next();
            return;
        }
        context.response()
                .putHeader("Content-Type", contentType)
                .putHeader("Cache-Control", "no-cache, must-revalidate")
                .putHeader("X-Content-Type-Options", "nosniff")
                .end(body.get());
    }

    Optional<String> render(ResolvedBrand brand, String file) {
        boolean ownLogo = brand.assets().get(BrandAssetSlot.LOGO) != null;
        // The page differs with and without a logo of the brand's own.
        Key key = new Key(file + (ownLogo ? "+logo" : ""), brand.revision());
        String cached = rendered.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> generated = read(GENERATED + file).or(() -> "style.css".equals(file) ? read(STYLE_SOURCE) : Optional.empty());
        if (generated.isEmpty()) {
            LOGGER.debugf("Swagger UI %s is not on the classpath; serving it unbranded", file);
            return Optional.empty();
        }
        String result = "index.html".equals(file)
                ? SwaggerUiBranding.page(generated.get(), brand, ownLogo)
                : SwaggerUiBranding.stylesheet(generated.get(), brand.colors());
        rendered.put(key, result);
        return Optional.of(result);
    }

    private void image(RoutingContext context, ResolvedBrand brand, BrandAssetSlot slot) {
        if (slot == null || brand.assets().get(slot) == null) {
            // No image of the brand's own: the page shows the name instead of a
            // logo, and the favicon stays the bundled one (documented).
            context.next();
            return;
        }
        Optional<BrandAsset> asset = branding.asset(BrandRequest.NONE, slot);
        if (asset.isEmpty()) {
            context.next();
            return;
        }
        var response = context.response()
                .putHeader("Content-Type", asset.get().contentType())
                .putHeader("Cache-Control", "no-cache, must-revalidate")
                .putHeader("X-Content-Type-Options", "nosniff");
        if (BrandAssetValidator.SVG.equals(asset.get().contentType())) {
            response.putHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
        response.end(Buffer.buffer(asset.get().data()));
    }

    private static Optional<String> read(String resource) {
        try (InputStream in = getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warnf("Could not read %s: %s", resource, e.getMessage());
            return Optional.empty();
        }
    }
}
