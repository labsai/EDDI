package ai.labs.staticresources.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.staticresources.impl.ResourceFileManager;
import ai.labs.staticresources.rest.IContentTypeProvider;
import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.staticresources.rest.IRestBinaryResource;
import ai.labs.staticresources.rest.IRestHtmlResource;
import ai.labs.staticresources.rest.impl.ContentTypeProvider;
import ai.labs.staticresources.rest.impl.RestBinaryResource;
import ai.labs.staticresources.rest.impl.RestHtmlResource;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class StaticResourcesModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IResourceFileManager.class).to(ResourceFileManager.class).in(Scopes.SINGLETON);
        bind(IContentTypeProvider.class).to(ContentTypeProvider.class).in(Scopes.SINGLETON);

        bind(IRestHtmlResource.class).to(RestHtmlResource.class);
        bind(IRestBinaryResource.class).to(RestBinaryResource.class);
    }
}
