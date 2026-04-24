/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

public interface ICompleteListener {
    void onComplete(IResponse response) throws IResponse.HttpResponseException;
}
