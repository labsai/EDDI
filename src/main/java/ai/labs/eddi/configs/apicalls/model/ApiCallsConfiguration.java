/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * @author ginccc
 */

public class ApiCallsConfiguration {
    private String targetServerUrl;

    @JsonProperty("httpCalls")
    @JsonAlias("apiCalls")
    private List<ApiCall> httpCalls;

    public String getTargetServerUrl() {
        return targetServerUrl;
    }

    public void setTargetServerUrl(String targetServerUrl) {
        this.targetServerUrl = targetServerUrl;
    }

    public List<ApiCall> getHttpCalls() {
        return httpCalls;
    }

    public void setHttpCalls(List<ApiCall> httpCalls) {
        this.httpCalls = httpCalls;
    }
}
