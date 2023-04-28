package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.jackson.ObjectMapperCustomizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;

@Singleton
public class SerializationCustomizer implements ObjectMapperCustomizer {
    private final Boolean prettyPrint;

    @Inject
    public SerializationCustomizer(@ConfigProperty(name = "json.prettyPrint") Boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
    }

    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(INDENT_OUTPUT, prettyPrint);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
