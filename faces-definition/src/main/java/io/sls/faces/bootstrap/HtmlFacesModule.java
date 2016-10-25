package io.sls.faces.bootstrap;

import com.google.inject.Scopes;
import io.sls.faces.html.IHtmlFaceStore;
import io.sls.faces.html.impl.HtmlFaceStore;
import io.sls.faces.html.rest.IRestHtmlFaceStore;
import io.sls.faces.html.rest.impl.RestHtmlFaceStore;
import io.sls.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class HtmlFacesModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IHtmlFaceStore.class).to(HtmlFaceStore.class).in(Scopes.SINGLETON);

        bind(IRestHtmlFaceStore.class).to(RestHtmlFaceStore.class);
    }
}
