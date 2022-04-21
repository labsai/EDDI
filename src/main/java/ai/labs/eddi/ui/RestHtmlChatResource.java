package ai.labs.eddi.ui;

import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHtmlChatResource implements IRestHtmlChatResource {
    private final IResourceFileManager resourceFileManager;

    @Inject
    public RestHtmlChatResource(IResourceFileManager resourceFileManager) {
        this.resourceFileManager = resourceFileManager;
    }

    @Override
    public Response viewDefault() {
        return viewHtml("/");
    }

    @Override
    public Response viewHtml(String path) {
        try {
            return Response.ok(resourceFileManager.getResourceAsInputStream("META-INF/resources/chat.html")).build();
        } catch (IResourceFileManager.NotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }
}
