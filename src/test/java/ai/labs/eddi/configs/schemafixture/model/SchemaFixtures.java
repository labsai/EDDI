/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.schemafixture.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;

import java.util.List;
import java.util.Map;

/**
 * Configuration-model fixtures for {@code StrictConfigurationSchemaFilterTest}.
 * They sit in a {@code configs.<area>.model} package because that is what makes
 * a request body strict.
 */
public final class SchemaFixtures {

    private SchemaFixtures() {
    }

    /** A strict configuration body exercising every kind of property. */
    public static class StrictConfig {
        public String name;
        public Nested nested;
        public List<Nested> nestedList;
        public Map<String, String> parameters;
        public Map<String, Nested> nestedByKey;
        public Tolerant tolerant;
        public Polymorphic polymorphic;
        public Object anything;
        public WithIgnoredName withIgnoredName;
        public WithAlias withAlias;
        public EmitsUnlistedKey emitsUnlistedKey;
        public EmitsAnyKey emitsAnyKey;
        public WithUnwrapped withUnwrapped;
        public Wrapped wrapped;
    }

    public static class Nested {
        public String value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tolerant {
        public String value;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY, visible = true)
    @JsonSubTypes(@JsonSubTypes.Type(value = PolymorphicText.class, name = "text"))
    public static class Polymorphic {
        public String type;
    }

    public static class PolymorphicText extends Polymorphic {
        public String text;
    }

    /** Accepts {@code legacy} on input and drops it; never writes it. */
    public static class WithIgnoredName {
        public String value;
        @JsonIgnore
        public String legacy;
    }

    /** Accepts the legacy name {@code oldValue} for {@code value}. */
    public static class WithAlias {
        @JsonAlias("oldValue")
        public String value;
    }

    /** Writes {@code computed}, which its schema does not list. */
    public static class EmitsUnlistedKey {
        public String value;

        public String getComputed() {
            return "derived";
        }
    }

    /** Writes arbitrary keys through an any-getter. */
    public static class EmitsAnyKey {
        public String value;

        @JsonAnyGetter
        public Map<String, Object> extra() {
            return Map.of("free", "form");
        }
    }

    /** Reads and writes the keys of {@code inner} flattened into its own. */
    public static class WithUnwrapped {
        public String own;
        @JsonUnwrapped
        public Nested inner;
    }

    /**
     * Referenced through {@code allOf}, the way a {@code $ref} carries siblings.
     */
    public static class Wrapped {
        public String value;
    }

    /** A configuration body that tolerates unknown keys at its root. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TolerantConfig {
        public String value;
    }

    /**
     * Resource methods without a {@code @Path}: enough for the Jandex scan, and
     * never mistaken for an endpoint when a {@code @QuarkusTest} indexes test
     * classes.
     */
    public interface FixtureResource {
        @POST
        void strict(StrictConfig body);

        @POST
        void tolerant(@PathParam("id") String id, TolerantConfig body);
    }
}
