/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.rest;

import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.integrations.teams.TeamsChannelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Teams app package of a Teams integration: a zip with a
 * {@code manifest.json} (schema {@value #MANIFEST_VERSION}) for its bot and the
 * two icons Teams requires, ready to upload in Teams ("Upload a custom app") or
 * the Teams admin center.
 * <p>
 * Not on the public {@code /integrations/teams} path: the integration is read
 * through {@link IRestChannelIntegrationStore}, so the caller needs the same
 * role and workspace access as for reading the integration itself.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@Path("/channelstore/teams")
@Tag(name = "Integrations / Teams", description = "Microsoft Teams app package")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public class RestTeamsAppPackage {

    static final String MANIFEST_VERSION = "1.29";
    static final String MANIFEST_SCHEMA = "https://developer.microsoft.com/json-schemas/teams/v1.29/MicrosoftTeams.schema.json";
    static final String DEFAULT_WEBSITE = "https://eddi.technology";

    private final IRestChannelIntegrationStore channels;
    private final ObjectMapper objectMapper;

    @Inject
    public RestTeamsAppPackage(IRestChannelIntegrationStore channels, ObjectMapper objectMapper) {
        this.channels = channels;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{id}/app-package")
    @Produces("application/zip")
    @Operation(description = "Download the Teams app package (manifest and icons) for a Teams channel integration.")
    public Response appPackage(@PathParam("id") String id, @QueryParam("version") Integer version,
                               @QueryParam("developerName")
                               @DefaultValue("EDDI") String developerName,
                               @QueryParam("websiteUrl")
                               @DefaultValue(DEFAULT_WEBSITE) String websiteUrl,
                               @QueryParam("privacyUrl") String privacyUrl, @QueryParam("termsOfUseUrl") String termsOfUseUrl,
                               @QueryParam("accentColor")
                               @DefaultValue("#4F46E5") String accentColor) {
        ChannelIntegrationConfiguration integration = channels.readChannel(id, version);
        if (integration == null || !TeamsChannelType.ID.equals(integration.getChannelType())) {
            throw new BadRequestException("Channel integration " + id + " is not a Teams integration.");
        }
        String appId = integration.getPlatformConfig() == null ? null : integration.getPlatformConfig().get(TeamsChannelType.KEY_APP_ID);
        if (!TeamsChannelType.isGuid(appId)) {
            throw new BadRequestException("The integration has no valid platformConfig.appId.");
        }
        requireHttps("websiteUrl", websiteUrl);
        String privacy = privacyUrl != null && !privacyUrl.isBlank() ? privacyUrl : websiteUrl;
        String terms = termsOfUseUrl != null && !termsOfUseUrl.isBlank() ? termsOfUseUrl : websiteUrl;
        requireHttps("privacyUrl", privacy);
        requireHttps("termsOfUseUrl", terms);
        if (accentColor == null || !accentColor.matches("^#[0-9A-Fa-f]{6}$")) {
            throw new BadRequestException("accentColor must be a hex colour such as #4F46E5.");
        }
        try {
            byte[] zip = build(manifest(integration, appId.toLowerCase(Locale.ROOT), developerName, websiteUrl, privacy, terms, accentColor),
                    Color.decode(accentColor));
            String fileName = "teams-app-" + safeFileName(integration.getName()) + ".zip";
            return Response.ok(zip).header("Content-Disposition", "attachment; filename=\"" + fileName + "\"").build();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the Teams app package", e);
        }
    }

    Map<String, Object> manifest(ChannelIntegrationConfiguration integration, String appId, String developerName, String websiteUrl,
                                 String privacyUrl, String termsOfUseUrl, String accentColor) {
        String name = integration.getName() == null ? "EDDI" : integration.getName();
        String description = "An EDDI agent in Microsoft Teams.";
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("$schema", MANIFEST_SCHEMA);
        manifest.put("manifestVersion", MANIFEST_VERSION);
        manifest.put("version", "1.0.0");
        manifest.put("id", appId);
        manifest.put("developer", Map.of("name", truncate(developerName, 32), "websiteUrl", websiteUrl, "privacyUrl", privacyUrl,
                "termsOfUseUrl", termsOfUseUrl));
        manifest.put("name", Map.of("short", truncate(name, 30), "full", truncate(name, 100)));
        manifest.put("description", Map.of("short", truncate(description, 80), "full", truncate(description, 4000)));
        manifest.put("icons", Map.of("color", "color.png", "outline", "outline.png"));
        manifest.put("accentColor", accentColor);
        Map<String, Object> bot = new LinkedHashMap<>();
        bot.put("botId", appId);
        bot.put("scopes", List.of("personal", "team", "groupChat"));
        bot.put("supportsFiles", false);
        bot.put("isNotificationOnly", false);
        manifest.put("bots", List.of(bot));
        manifest.put("permissions", List.of("identity", "messageTeamMembers"));
        manifest.put("validDomains", List.of());
        return manifest;
    }

    private byte[] build(Map<String, Object> manifest, Color accent) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("color.png"));
            zip.write(icon(192, accent, Color.WHITE));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("outline.png"));
            zip.write(icon(32, null, Color.WHITE));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /**
     * A plain icon: a filled circle on {@code background} (the colour icon,
     * 192×192) or a white ring on transparency (the outline icon, 32×32, which
     * Teams requires to be white and transparent).
     */
    static byte[] icon(int size, Color background, Color foreground) throws IOException {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (background != null) {
                g.setColor(background);
                g.fillRect(0, 0, size, size);
                g.setColor(foreground);
                g.fillOval(size / 4, size / 4, size / 2, size / 2);
            } else {
                g.setColor(foreground);
                g.setStroke(new BasicStroke(Math.max(2f, size / 10f)));
                g.drawOval(size / 6, size / 6, size * 2 / 3, size * 2 / 3);
            }
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void requireHttps(String name, String url) {
        try {
            URI uri = URI.create(url);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the refusal
        }
        throw new BadRequestException(name + " must be an https URL.");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeFileName(String name) {
        String safe = name == null ? "integration" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isBlank() ? "integration" : safe;
    }
}
