package ai.labs.resources.rest.http.model;

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
    private int maxRetries = 3;
    private int delayBetweenRetriesInMillis = 1000;
    private List<Integer> retryOnHttpCodes = Arrays.asList(502, 503);
    private List<MatchingInfo> valuePathMatchers;

    @Getter
    @Setter
    public static class MatchingInfo {
        private String valuePath;
        private String contains;
        private String equals;
    }
}