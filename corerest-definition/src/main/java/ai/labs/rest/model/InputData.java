package ai.labs.rest.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class InputData {
    private String input = "";
    private List<Context> context = new LinkedList<>();

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class Context {
        public enum ContextType {
            string,
            expressions,
            object
        }

        private ContextType type;
        private String contextKey;
        private Object contextValue;
    }
}
