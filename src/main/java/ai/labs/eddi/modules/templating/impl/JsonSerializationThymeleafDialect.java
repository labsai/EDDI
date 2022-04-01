package ai.labs.eddi.modules.templating.impl;


import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Collections;
import java.util.Set;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;

public class JsonSerializationThymeleafDialect extends AbstractDialect implements IExpressionObjectDialect {

    private final IJsonSerialization jsonSerialization;

    public JsonSerializationThymeleafDialect(ObjectMapper objectMapper) {
        super("Json Converter");
        objectMapper.configure(INDENT_OUTPUT, true);
        this.jsonSerialization = new JsonSerialization(objectMapper);
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new IExpressionObjectFactory() {

            @Override
            public Set<String> getAllExpressionObjectNames() {
                return Collections.singleton("json");
            }

            @Override
            public Object buildObject(IExpressionContext context,
                                      String expressionObjectName) {

                return jsonSerialization;
            }

            @Override
            public boolean isCacheable(String expressionObjectName) {
                return true;
            }
        };
    }
}