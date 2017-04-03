package ai.labs.core.rest.internal;

import ai.labs.rest.rest.IFacebookEndpoint;

public interface FacebookEndpointFactory {

	IFacebookEndpoint create(String botId);
}
