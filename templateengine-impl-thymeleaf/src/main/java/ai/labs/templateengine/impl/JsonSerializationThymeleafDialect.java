package ai.labs.templateengine.impl;


import ai.labs.serialization.IJsonSerialization;
import ai.labs.serialization.JsonSerialization;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Collections;
import java.util.Set;

import static ai.labs.SerializationUtilities.configureObjectMapper;

public class JsonSerializationThymeleafDialect extends AbstractDialect implements IExpressionObjectDialect {

    private final IJsonSerialization jsonSerialization;

    public JsonSerializationThymeleafDialect(JsonFactory jsonFactory) {
        super("Json Converter");
        ObjectMapper objectMapper = new ObjectMapper(jsonFactory);

        configureObjectMapper(false, objectMapper);
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