package ai.labs.eddi.ui;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.Response;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHtmlChatResource implements IRestHtmlChatResource {

    @Override
    public Response viewDefault() {
        return viewHtml("/");
    }

    @Override
    public Response viewHtml(String path) {
        return Response.ok(getResourceAsStream("/META-INF/resources/chat.html")).build();
    }
}
