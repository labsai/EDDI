/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import java.net.URI;

public interface IHttpClient {
    enum Method {
        HEAD, GET, POST, PUT, DELETE, PATCH
    }

    IRequest newRequest(URI uri);

    IRequest newRequest(URI uri, Method method);
}
