/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import java.util.Arrays;
import java.util.List;

public class RetryApiCallInstruction {
    private Integer maxRetries = 3;
    private Integer exponentialBackoffDelayInMillis = 1000;
    /**
     * Upper bound for a single backoff delay in milliseconds. A retry waits on the
     * conversation thread, so the engine additionally clamps this to its own hard
     * ceiling — configuring a larger value has no effect.
     */
    private Integer maxBackoffDelayInMillis;
    private List<Integer> retryOnHttpCodes = Arrays.asList(502, 503);
    private List<MatchingInfo> responseValuePathMatchers;

    public static class MatchingInfo {
        private String valuePath;
        private String contains;
        private String equals;
        private Boolean trueIfNoMatch = false;

        public String getValuePath() {
            return valuePath;
        }

        public void setValuePath(String valuePath) {
            this.valuePath = valuePath;
        }

        public String getContains() {
            return contains;
        }

        public void setContains(String contains) {
            this.contains = contains;
        }

        public String getEquals() {
            return equals;
        }

        public void setEquals(String equals) {
            this.equals = equals;
        }

        public Boolean getTrueIfNoMatch() {
            return trueIfNoMatch;
        }

        public void setTrueIfNoMatch(Boolean trueIfNoMatch) {
            this.trueIfNoMatch = trueIfNoMatch;
        }
    }

    public RetryApiCallInstruction() {
    }

    public RetryApiCallInstruction(Integer maxRetries, Integer exponentialBackoffDelayInMillis, List<Integer> retryOnHttpCodes,
            List<MatchingInfo> responseValuePathMatchers) {
        this.maxRetries = maxRetries;
        this.exponentialBackoffDelayInMillis = exponentialBackoffDelayInMillis;
        this.retryOnHttpCodes = retryOnHttpCodes;
        this.responseValuePathMatchers = responseValuePathMatchers;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Integer getExponentialBackoffDelayInMillis() {
        return exponentialBackoffDelayInMillis;
    }

    public void setExponentialBackoffDelayInMillis(Integer exponentialBackoffDelayInMillis) {
        this.exponentialBackoffDelayInMillis = exponentialBackoffDelayInMillis;
    }

    public Integer getMaxBackoffDelayInMillis() {
        return maxBackoffDelayInMillis;
    }

    public void setMaxBackoffDelayInMillis(Integer maxBackoffDelayInMillis) {
        this.maxBackoffDelayInMillis = maxBackoffDelayInMillis;
    }

    public List<Integer> getRetryOnHttpCodes() {
        return retryOnHttpCodes;
    }

    public void setRetryOnHttpCodes(List<Integer> retryOnHttpCodes) {
        this.retryOnHttpCodes = retryOnHttpCodes;
    }

    public List<MatchingInfo> getResponseValuePathMatchers() {
        return responseValuePathMatchers;
    }

    public void setResponseValuePathMatchers(List<MatchingInfo> responseValuePathMatchers) {
        this.responseValuePathMatchers = responseValuePathMatchers;
    }
}
