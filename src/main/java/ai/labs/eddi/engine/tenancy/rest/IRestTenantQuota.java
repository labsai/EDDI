package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * REST API for tenant quota management.
 */
@Path("/administration/quotas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Tenant Quotas")
@RolesAllowed("eddi-admin")
public interface IRestTenantQuota {

    /**
     * List all configured tenant quotas.
     */
    @GET
    List<TenantQuota> listQuotas();

    /**
     * Get quota configuration for a specific tenant.
     */
    @GET
    @Path("/{tenantId}")
    TenantQuota getQuota(@PathParam("tenantId") String tenantId);

    /**
     * Create or update quota configuration for a tenant.
     */
    @PUT
    @Path("/{tenantId}")
    Response updateQuota(@PathParam("tenantId") String tenantId, TenantQuota quota);

    /**
     * Get current usage counters for a tenant.
     */
    @GET
    @Path("/{tenantId}/usage")
    UsageSnapshot getUsage(@PathParam("tenantId") String tenantId);

    /**
     * Reset usage counters for a tenant.
     */
    @POST
    @Path("/{tenantId}/usage/reset")
    Response resetUsage(@PathParam("tenantId") String tenantId);
}
