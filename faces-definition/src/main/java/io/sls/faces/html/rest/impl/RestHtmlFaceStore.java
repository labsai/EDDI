package io.sls.faces.html.rest.impl;

import io.sls.faces.html.IHtmlFaceStore;
import io.sls.faces.html.model.HtmlFace;
import io.sls.faces.html.rest.IRestHtmlFaceStore;
import io.sls.persistence.IResourceStore;
import io.sls.utilities.RestUtilities;
import org.jboss.resteasy.plugins.guice.RequestScoped;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * User: jarisch
 * Date: 21.01.13
 * Time: 16:52
 */
@RequestScoped
public class RestHtmlFaceStore implements IRestHtmlFaceStore {
    private final HttpResponse httpResponse;
    private final IHtmlFaceStore htmlFaceStore;

    @Inject
    public RestHtmlFaceStore(@Context HttpResponse httpResponse,
                             IHtmlFaceStore htmlFaceStore) {
        this.httpResponse = httpResponse;
        this.htmlFaceStore = htmlFaceStore;
    }

    @Override
    public HtmlFace searchFace(String host) {
        try {
            return htmlFaceStore.searchFaceByHost(host);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(e, Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public HtmlFace readFace(String faceId) {
        try {
            return htmlFaceStore.readFace(faceId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(e, Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void updateFace(String faceId, HtmlFace htmlFace) {
        try {
            htmlFaceStore.updateFace(faceId, htmlFace);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public URI createFace(HtmlFace htmlFace) {
        try {
            String id = htmlFaceStore.createFace(htmlFace);
            httpResponse.setStatus(Response.Status.CREATED.getStatusCode());
            return RestUtilities.createURI(resourceURI, id);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteFace(String faceId) {
        htmlFaceStore.deleteFace(faceId);
    }
}
