package ai.labs.schema.bootstrap;

import ai.labs.schema.IJsonSchemaCreator;
import ai.labs.schema.JsonSchemaCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.imifou.jsonschema.module.addon.AddonModule;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;

import javax.inject.Singleton;

public class JsonSchemaModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(IJsonSchemaCreator.class).to(JsonSchemaCreator.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    SchemaGenerator provideSchemaGenerator(ObjectMapper objectMapper) {
        var configBuilder = new SchemaGeneratorConfigBuilder(objectMapper, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule()).with(new AddonModule());
        var config = configBuilder.build();
        return new SchemaGenerator(config);
    }
}
