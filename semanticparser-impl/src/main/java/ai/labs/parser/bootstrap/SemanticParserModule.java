package ai.labs.parser.bootstrap;

import ai.labs.parser.rest.IRestSemanticParser;
import ai.labs.parser.rest.impl.RestSemanticParser;
import io.sls.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class SemanticParserModule extends AbstractBaseModule {

    @Override
    protected void configure() {


        bind(IRestSemanticParser.class).to(RestSemanticParser.class);
    }
}
