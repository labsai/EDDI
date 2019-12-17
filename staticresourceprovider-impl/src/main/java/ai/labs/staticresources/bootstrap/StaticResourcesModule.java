package ai.labs.staticresources.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.staticresources.impl.ResourceFileManager;
import ai.labs.staticresources.rest.*;
import ai.labs.staticresources.rest.impl.*;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class StaticResourcesModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IResourceFileManager.class).to(ResourceFileManager.class).in(Scopes.SINGLETON);
        bind(IContentTypeProvider.class).to(ContentTypeProvider.class).in(Scopes.SINGLETON);

        bind(IRestHtmlApiResource.class).to(RestHtmlApiResource.class).in(Scopes.SINGLETON);
        bind(IRestHtmlChatResource.class).to(RestHtmlChatResource.class).in(Scopes.SINGLETON);
        bind(IRestBinaryResource.class).to(RestBinaryResource.class).in(Scopes.SINGLETON);
        bind(IRestOAuth2HtmlRedirect.class).to(RestOAuth2HtmlRedirect.class).in(Scopes.SINGLETON);
    }
}
