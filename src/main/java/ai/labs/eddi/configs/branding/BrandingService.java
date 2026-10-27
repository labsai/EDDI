/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandDocument;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.BrandUpdate;
import ai.labs.eddi.configs.branding.model.BrandViolation;
import ai.labs.eddi.configs.branding.model.BrandingView;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * The deployment's brand: where it is resolved, validated, cached and changed.
 * <p>
 * <b>Resolution</b>, field by field: with {@code eddi.branding.locked=true},
 * the {@code eddi.branding.*} properties and {@code assets-dir} only; otherwise
 * the stored document, then those properties as the seed, then the EDDI
 * defaults. So a {@code null} in the document means "the seed's value, or
 * EDDI's".
 * <p>
 * <b>Caching.</b> The resolved brand is held for {@link #CACHE_TTL} and dropped
 * immediately on a local write. Other cluster nodes converge within the TTL: a
 * brand changes a few times a year, so neither the event bus nor a per-request
 * database read is worth it (plan R2). Every rendered output is keyed on
 * {@link ResolvedBrand#revision()}, which only ever grows.
 * <p>
 * <b>Writes</b> need the revision they were based on (409 when it moved), are
 * refused while locked (409), and leave an INFO audit line with the user, both
 * revisions and the changed fields.
 * <p>
 * {@link #current(BrandRequest)} is the single entry point for reading: a
 * per-tenant resolver would slot in there (plan §4.6).
 */
@ApplicationScoped
public class BrandingService {

    private static final Logger LOGGER = Logger.getLogger(BrandingService.class);

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** How often an image write retries its compare-and-set before a 409. */
    static final int ASSET_WRITE_ATTEMPTS = 5;

    private static final String PROPERTY_PREFIX = "eddi.branding.";
    private static final String CACHE_KEY = "brand";

    /** File extensions read from {@code assets-dir}, in order of preference. */
    private static final String[] SEED_EXTENSIONS = {".svg", ".png", ".webp", ".ico"};

    private final IBrandingStore store;
    private final boolean locked;
    private final BrandDocument seed;
    private final Map<BrandAssetSlot, BrandAsset> seedAssets;
    private final Clock clock;

    private final Cache<String, State> cache;
    private final Cache<String, BrandAsset> assetCache = Caffeine.newBuilder()
            .maximumWeight(16L * BrandAssetValidator.MAX_BYTES)
            .weigher((String hash, BrandAsset asset) -> asset.data().length)
            .build();
    private final Map<BrandAssetSlot, Optional<BrandAsset>> bundled = new ConcurrentHashMap<>();

    /** The stored document and the brand resolved from it. */
    record State(BrandDocument stored, ResolvedBrand resolved) {
    }

    @Inject
    public BrandingService(IBrandingStore store, BrandingConfig config) {
        this(store, config, Clock.systemUTC(), CACHE_TTL);
    }

    BrandingService(IBrandingStore store, BrandingConfig config, Clock clock, Duration cacheTtl) {
        this.store = store;
        this.clock = clock;
        this.locked = config.locked();
        this.seed = seedDocument(config);
        this.seedAssets = seedAssets(config);
        this.cache = Caffeine.newBuilder().expireAfterWrite(cacheTtl).maximumSize(1).build();
    }

    void onStart(@Observes StartupEvent event) {
        // Constructing this bean validated the seed; reaching here means it is valid.
        for (String warning : BrandValidator.contrastWarnings(resolveColors(seed.colors()))) {
            LOGGER.warnf("eddi.branding.color.*: %s", warning);
        }
        if (locked) {
            LOGGER.infof("Branding is locked by eddi.branding.locked=true: the brand comes from eddi.branding.* only (%s)",
                    seed.hasValues() || !seedAssets.isEmpty() ? "branded" : "no values set, so the EDDI brand");
        }
    }

    // ---- reading -------------------------------------------------------------

    /** The brand in effect for {@code request}. */
    public ResolvedBrand current(BrandRequest request) {
        return state().resolved();
    }

    /** What the admin endpoints answer: the editable document and the effect. */
    public BrandingView view(BrandRequest request) {
        return view(state(), List.of());
    }

    public boolean isLocked() {
        return locked;
    }

    /**
     * The image to serve for {@code slot}: the slot's own, else the one of the slot
     * it falls back to ({@link BrandAssetSlot#fallback()}), else the bundled EDDI
     * image. Empty only when the bundled image is not on the classpath (a build
     * without the UIs).
     */
    public Optional<BrandAsset> asset(BrandRequest request, BrandAssetSlot slot) {
        ResolvedBrand brand = current(request);
        for (BrandAssetSlot s = slot; s != null; s = s.fallback().orElse(null)) {
            Optional<BrandAsset> own = ownAsset(brand, s);
            if (own.isPresent()) {
                return own;
            }
        }
        for (BrandAssetSlot s = slot; s != null; s = s.fallback().orElse(null)) {
            Optional<BrandAsset> fallback = bundledAsset(s);
            if (fallback.isPresent()) {
                return fallback;
            }
        }
        return Optional.empty();
    }

    private Optional<BrandAsset> ownAsset(ResolvedBrand brand, BrandAssetSlot slot) {
        String hash = brand.assets().get(slot);
        if (hash == null) {
            return Optional.empty();
        }
        BrandAsset seeded = seedAssets.get(slot);
        if (seeded != null && seeded.hash().equals(hash)) {
            return Optional.of(seeded);
        }
        BrandAsset cached = assetCache.getIfPresent(hash);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<BrandAsset> stored = store.readAsset(slot);
        // A different hash means another node replaced the image after this one
        // cached the brand: serve the newer bytes, but cache them under their own hash.
        stored.ifPresent(asset -> assetCache.put(asset.hash(), asset));
        return stored;
    }

    private Optional<BrandAsset> bundledAsset(BrandAssetSlot slot) {
        if (slot.bundledResource() == null) {
            return Optional.empty();
        }
        return bundled.computeIfAbsent(slot, BrandingService::loadBundled);
    }

    private static Optional<BrandAsset> loadBundled(BrandAssetSlot slot) {
        try (InputStream in = getResourceAsStream(slot.bundledResource())) {
            if (in == null) {
                LOGGER.debugf("Bundled brand image %s is not on the classpath (built without the UIs?)", slot.bundledResource());
                return Optional.empty();
            }
            byte[] data = in.readAllBytes();
            String type = BrandAssetValidator.detectType(data);
            if (type == null) {
                type = slot.bundledResource().endsWith(".ico") ? BrandAssetValidator.ICO : "application/octet-stream";
            }
            return Optional.of(new BrandAsset(slot, BrandAssetValidator.hash(data), type, data));
        } catch (IOException e) {
            LOGGER.warnf(e, "Could not read the bundled brand image %s", slot.bundledResource());
            return Optional.empty();
        }
    }

    private State state() {
        return cache.get(CACHE_KEY, key -> load());
    }

    private State load() {
        if (locked) {
            return new State(BrandDocument.EMPTY, resolve(BrandDocument.EMPTY, seed, seedAssets, true));
        }
        BrandDocument stored;
        try {
            stored = store.read().orElse(BrandDocument.EMPTY);
        } catch (RuntimeException e) {
            // A database hiccup must not take every page down with it: serve the seed
            // brand until the next reload.
            LOGGER.warnf(e, "Could not read the stored brand; serving the eddi.branding.* seed for now");
            stored = BrandDocument.EMPTY;
        }
        return new State(stored, resolve(stored, seed, seedAssets, false));
    }

    /** The resolution rule of the class comment, as a pure function. */
    static ResolvedBrand resolve(BrandDocument stored, BrandDocument seed, Map<BrandAssetSlot, BrandAsset> seedAssets, boolean locked) {
        BrandDocument base = locked ? BrandDocument.EMPTY : stored;
        BrandSource source;
        if (base.hasValues()) {
            source = BrandSource.DATABASE;
        } else if (seed.hasValues() || !seedAssets.isEmpty()) {
            source = BrandSource.PROPERTIES;
        } else {
            return withRevision(ResolvedBrand.defaults(locked), base.revision());
        }
        String productName = first(base.productName(), seed.productName(), ResolvedBrand.DEFAULT_PRODUCT_NAME);
        String shortName = first(base.shortName(), seed.shortName(), productName);
        String tagline = first(base.tagline(), seed.tagline(), null);
        BrandColors colors = resolveColors(base.colors().over(seed.colors()));
        boolean poweredBy = first(base.poweredBy(), seed.poweredBy(), Boolean.TRUE);
        boolean showUpdateCheck = first(base.showUpdateCheck(), seed.showUpdateCheck(), Boolean.FALSE);
        BrandAssets assets = BrandAssets.EMPTY;
        for (BrandAssetSlot slot : BrandAssetSlot.values()) {
            String hash = base.assets().get(slot);
            if (hash == null && seedAssets.containsKey(slot)) {
                hash = seedAssets.get(slot).hash();
            }
            assets = assets.with(slot, hash);
        }
        return new ResolvedBrand(base.revision(), source, locked, true, productName, shortName, tagline, colors, poweredBy,
                showUpdateCheck, assets);
    }

    private static ResolvedBrand withRevision(ResolvedBrand brand, long revision) {
        return new ResolvedBrand(revision, brand.source(), brand.locked(), brand.branded(), brand.productName(), brand.shortName(),
                brand.tagline(), brand.colors(), brand.poweredBy(), brand.showUpdateCheck(), brand.assets());
    }

    /** Dark colours follow the light ones; the chat accent follows primary. */
    static BrandColors resolveColors(BrandColors colors) {
        if (colors.isEmpty()) {
            return BrandColors.EMPTY;
        }
        return new BrandColors(colors.primary(), colors.primaryForeground(),
                first(colors.darkPrimary(), colors.primary(), null),
                first(colors.darkPrimaryForeground(), colors.primaryForeground(), null),
                first(colors.chatAccent(), colors.primary(), null));
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BrandingView view(State state, List<String> warnings) {
        BrandDocument document = locked ? seed : state.stored();
        if (locked) {
            BrandAssets assets = BrandAssets.EMPTY;
            for (var entry : seedAssets.entrySet()) {
                assets = assets.with(entry.getKey(), entry.getValue().hash());
            }
            document = document.withAssets(assets);
        }
        ResolvedBrand effective = state.resolved();
        return new BrandingView(document, effective, locked, effective.source(), warnings);
    }

    // ---- writing -------------------------------------------------------------

    /**
     * Replaces the text fields, colours and toggles.
     *
     * @throws BrandingException
     *             409 when locked or when {@code update.revision()} is not the
     *             stored one, 400 for an invalid value
     */
    public BrandingView update(BrandRequest request, BrandUpdate update, String user) {
        requireUnlocked();
        Objects.requireNonNull(update, "update");
        BrandColors colors = update.colors() == null ? BrandColors.EMPTY : normalize(update.colors());
        String productName = normalize(update.productName());
        String shortName = normalize(update.shortName());
        String tagline = normalize(update.tagline());
        List<BrandViolation> violations = new ArrayList<>(
                BrandValidator.validate(productName, shortName, tagline, colors, UnaryOperator.identity()));
        if (update.revision() == null) {
            violations.add(new BrandViolation("revision", "is required: send the revision you read, so a concurrent edit is not lost"));
        }
        if (!violations.isEmpty()) {
            throw BrandingException.invalid(violations);
        }
        BrandDocument current = store.read().orElse(BrandDocument.EMPTY);
        if (update.revision() != current.revision()) {
            throw BrandingException.conflict(current.revision());
        }
        BrandDocument next = new BrandDocument(current.revision() + 1, productName, shortName, tagline, colors, update.poweredBy(),
                update.showUpdateCheck(), current.assets(), Instant.now(clock).toString(), user);
        if (!store.compareAndSet(current.revision(), next)) {
            throw BrandingException.conflict(store.read().map(BrandDocument::revision).orElse(0L));
        }
        State state = written(current, next, user);
        return view(state, BrandValidator.contrastWarnings(state.resolved().colors()));
    }

    /**
     * Stores an image for {@code slot}.
     *
     * @throws BrandingException
     *             409 when locked; 400, 413 or 415 for an image the checks of
     *             {@link BrandAssetValidator} refuse
     */
    public BrandingView putAsset(BrandRequest request, BrandAssetSlot slot, byte[] data, String user) {
        requireUnlocked();
        BrandAsset asset = BrandAssetValidator.validate(slot, data);
        store.writeAsset(asset);
        assetCache.put(asset.hash(), asset);
        return view(mutate(document -> document.withAssets(document.assets().with(slot, asset.hash())), user), List.of());
    }

    /** Reverts {@code slot} to the seed image or the bundled default. */
    public BrandingView deleteAsset(BrandRequest request, BrandAssetSlot slot, String user) {
        requireUnlocked();
        State state = mutate(document -> document.withAssets(document.assets().with(slot, null)), user);
        store.deleteAsset(slot);
        return view(state, List.of());
    }

    /** Clears every stored value and image: the seed, or EDDI, applies again. */
    public BrandingView reset(BrandRequest request, String user) {
        requireUnlocked();
        State state = mutate(document -> BrandDocument.EMPTY, user);
        for (BrandAssetSlot slot : BrandAssetSlot.values()) {
            store.deleteAsset(slot);
        }
        return view(state, List.of());
    }

    /**
     * A compare-and-set loop for writes that do not carry a revision (images,
     * reset): each one changes only what it names, so it is safe to reapply on top
     * of a concurrent write.
     */
    private State mutate(UnaryOperator<BrandDocument> change, String user) {
        for (int attempt = 0; attempt < ASSET_WRITE_ATTEMPTS; attempt++) {
            BrandDocument current = store.read().orElse(BrandDocument.EMPTY);
            BrandDocument next = change.apply(current).stamped(current.revision() + 1, Instant.now(clock).toString(), user);
            if (store.compareAndSet(current.revision(), next)) {
                return written(current, next, user);
            }
        }
        throw BrandingException.conflict(store.read().map(BrandDocument::revision).orElse(0L));
    }

    private State written(BrandDocument before, BrandDocument after, String user) {
        cache.invalidateAll();
        // The user name comes from a token: strip control characters so it cannot forge
        // log lines.
        String who = user == null ? "anonymous" : user.replaceAll("\\p{Cntrl}", "_");
        LOGGER.infof("Brand changed by %s: revision %d -> %d, fields %s", who, before.revision(), after.revision(),
                changedFields(before, after));
        return state();
    }

    private void requireUnlocked() {
        if (locked) {
            throw BrandingException.locked();
        }
    }

    /** The names of the fields that differ, for the audit line. */
    static List<String> changedFields(BrandDocument before, BrandDocument after) {
        List<String> changed = new ArrayList<>();
        diff(changed, "productName", before.productName(), after.productName());
        diff(changed, "shortName", before.shortName(), after.shortName());
        diff(changed, "tagline", before.tagline(), after.tagline());
        diff(changed, "colors.primary", before.colors().primary(), after.colors().primary());
        diff(changed, "colors.primaryForeground", before.colors().primaryForeground(), after.colors().primaryForeground());
        diff(changed, "colors.darkPrimary", before.colors().darkPrimary(), after.colors().darkPrimary());
        diff(changed, "colors.darkPrimaryForeground", before.colors().darkPrimaryForeground(), after.colors().darkPrimaryForeground());
        diff(changed, "colors.chatAccent", before.colors().chatAccent(), after.colors().chatAccent());
        diff(changed, "poweredBy", before.poweredBy(), after.poweredBy());
        diff(changed, "showUpdateCheck", before.showUpdateCheck(), after.showUpdateCheck());
        for (BrandAssetSlot slot : BrandAssetSlot.values()) {
            diff(changed, "assets." + slot.id(), before.assets().get(slot), after.assets().get(slot));
        }
        return changed;
    }

    private static void diff(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }

    // ---- the seed --------------------------------------------------------------

    /**
     * Stripped, and {@code null} when empty — an empty form field means "unset".
     */
    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static BrandColors normalize(BrandColors colors) {
        return new BrandColors(normalize(colors.primary()), normalize(colors.primaryForeground()), normalize(colors.darkPrimary()),
                normalize(colors.darkPrimaryForeground()), normalize(colors.chatAccent()));
    }

    /**
     * {@code productName} → {@code eddi.branding.product-name},
     * {@code colors.darkPrimary} → {@code eddi.branding.color.dark-primary}.
     */
    static String propertyName(String field) {
        String name = field.startsWith("colors.") ? "color." + field.substring("colors.".length()) : field;
        StringBuilder kebab = new StringBuilder(PROPERTY_PREFIX);
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                kebab.append('-').append(Character.toLowerCase(c));
            } else {
                kebab.append(c);
            }
        }
        return kebab.toString();
    }

    /**
     * The properties as a document, validated with the REST validator. An invalid
     * value fails startup naming the property, like {@code AuthStartupGuard}.
     */
    static BrandDocument seedDocument(BrandingConfig config) {
        BrandingConfig.Color color = config.color();
        String productName = normalize(config.productName().orElse(null));
        String shortName = normalize(config.shortName().orElse(null));
        String tagline = normalize(config.tagline().orElse(null));
        BrandColors colors = new BrandColors(normalize(color.primary().orElse(null)), normalize(color.primaryForeground().orElse(null)),
                normalize(color.darkPrimary().orElse(null)), normalize(color.darkPrimaryForeground().orElse(null)),
                normalize(color.chatAccent().orElse(null)));
        List<BrandViolation> violations = BrandValidator.validate(productName, shortName, tagline, colors, BrandingService::propertyName);
        if (!violations.isEmpty()) {
            StringBuilder message = new StringBuilder("Invalid branding configuration:");
            for (BrandViolation violation : violations) {
                message.append(' ').append(violation.field()).append(' ').append(violation.message()).append('.');
            }
            throw new IllegalStateException(message.toString());
        }
        return new BrandDocument(0, productName, shortName, tagline, colors, config.poweredBy().orElse(null),
                config.showUpdateCheck().orElse(null), BrandAssets.EMPTY, null, null);
    }

    /**
     * The images of {@code eddi.branding.assets-dir}, each through the upload
     * checks; a missing directory or a refused file fails startup.
     */
    static Map<BrandAssetSlot, BrandAsset> seedAssets(BrandingConfig config) {
        Map<BrandAssetSlot, BrandAsset> assets = new EnumMap<>(BrandAssetSlot.class);
        Optional<String> configured = config.assetsDir().map(BrandingService::normalize);
        if (configured.isEmpty()) {
            return Map.of();
        }
        Path dir = Path.of(configured.get());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Invalid branding configuration: eddi.branding.assets-dir " + dir + " is not a directory.");
        }
        for (BrandAssetSlot slot : BrandAssetSlot.values()) {
            for (String extension : SEED_EXTENSIONS) {
                Path file = dir.resolve(slot.seedFileBase() + extension);
                if (Files.isRegularFile(file)) {
                    assets.put(slot, readSeedAsset(slot, file));
                    break;
                }
            }
        }
        return Map.copyOf(assets);
    }

    private static BrandAsset readSeedAsset(BrandAssetSlot slot, Path file) {
        try {
            if (Files.size(file) > BrandAssetValidator.MAX_BYTES) {
                throw new IllegalStateException("Invalid branding configuration: eddi.branding.assets-dir " + file + " is larger than "
                        + BrandAssetValidator.MAX_BYTES + " bytes.");
            }
            return BrandAssetValidator.validate(slot, Files.readAllBytes(file));
        } catch (BrandingException e) {
            throw new IllegalStateException("Invalid branding configuration: eddi.branding.assets-dir " + file + ": " + e.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("Invalid branding configuration: eddi.branding.assets-dir " + file + " cannot be read.", e);
        }
    }
}
