package io.sls.core.survey.parser;

import io.sls.core.survey.model.SurveyElement;
import io.sls.serialization.JSONSerialization;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;

public class SurveyDefinitionParser {

    public static SurveyElement parse(String content) throws IOException {
        return JSONSerialization.deserialize(content, new TypeReference<SurveyElement>() {
        });
    }
}
