package io.sls.faces.html.rest;

import io.sls.faces.html.model.HtmlFace;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.net.URI;

/**
 * User: jarisch
 * Date: 21.01.13
 * Time: 16:49
 */
@Path("/htmlfacestore/htmlfaces/")
public interface IRestHtmlFaceStore {
    String resourceURI = "resource://io.sls.htmlface/htmlfacestore/htmlfaces/";

        @GET
        @GZIP
        @Produces(MediaType.APPLICATION_JSON)
        HtmlFace searchFace(@QueryParam("host") String host);

        @GET
        @GZIP
        @Path("/{faceId}")
        @Produces(MediaType.APPLICATION_JSON)
        HtmlFace readFace(@PathParam("faceId") String faceId);

        @PUT
        @Path("/{faceId}")
        @Consumes(MediaType.APPLICATION_JSON)
        void updateFace(@PathParam("faceId") String faceId, @GZIP HtmlFace htmlFace);

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        URI createFace(@GZIP HtmlFace htmlFace);

        @DELETE
        @Path("/{faceId}")
        void deleteFace(@PathParam("faceId") String faceId);
}
