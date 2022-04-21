package ai.labs.eddi.configs.http.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class RetryHttpCallInstruction {
    private Integer maxRetries = 3;
    private Integer exponentialBackoffDelayInMillis = 1000;
    private List<Integer> retryOnHttpCodes = Arrays.asList(502, 503);
    private List<MatchingInfo> responseValuePathMatchers;

    @Getter
    @Setter
    public static class MatchingInfo {
        private String valuePath;
        private String contains;
        private String equals;
        private Boolean trueIfNoMatch = false;
    }
}
