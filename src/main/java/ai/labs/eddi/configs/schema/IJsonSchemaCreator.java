package ai.labs.eddi.configs.schema;

public interface IJsonSchemaCreator {
    String generateSchema(Class<?> clazz) throws Exception;
}
