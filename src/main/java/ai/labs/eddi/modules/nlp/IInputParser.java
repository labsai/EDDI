package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    Config getConfig();

    String normalize(String sentence, String userLanguage) throws InterruptedException;

    List<RawSolution> parse(String sentence) throws InterruptedException;

    List<RawSolution> parse(String sentence, String userLanguage, List<IDictionary> temporaryDictionaries) throws InterruptedException;

    class Config {
        private boolean appendExpressions = true;
        private boolean includeUnused = true;
        private boolean includeUnknown = true;

        public Config() {
        }

        public Config(boolean appendExpressions, boolean includeUnused, boolean includeUnknown) {
            this.appendExpressions = appendExpressions;
            this.includeUnused = includeUnused;
            this.includeUnknown = includeUnknown;
        }

        public boolean isAppendExpressions() {
            return appendExpressions;
        }

        public void setAppendExpressions(boolean appendExpressions) {
            this.appendExpressions = appendExpressions;
        }

        public boolean isIncludeUnused() {
            return includeUnused;
        }

        public void setIncludeUnused(boolean includeUnused) {
            this.includeUnused = includeUnused;
        }

        public boolean isIncludeUnknown() {
            return includeUnknown;
        }

        public void setIncludeUnknown(boolean includeUnknown) {
            this.includeUnknown = includeUnknown;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Config that = (Config) o;
            return appendExpressions == that.appendExpressions && includeUnused == that.includeUnused && includeUnknown == that.includeUnknown;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(appendExpressions, includeUnused, includeUnknown);
        }

        @Override
        public String toString() {
            return "Config(" + "appendExpressions=" + appendExpressions + ", includeUnused=" + includeUnused + ", includeUnknown=" + includeUnknown + ")";
        }
    }
}
