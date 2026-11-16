/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import ai.labs.eddi.configs.IRestVersionInfo;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.function.Function;

/**
 * Sends {@link IRestVersionInfo#getCurrentVersion} over HTTP on REST clients
 * built for EDDI's own store interfaces.
 * <p>
 * <b>Why this exists.</b> {@code getCurrentVersion} is a {@code default} method
 * of the {@link IRestVersionInfo} mixin, and a MicroProfile REST client runs a
 * default method as plain Java in the calling process; it never turns it into a
 * request. On the server that body is right: the store bean overrides
 * {@link IRestVersionInfo#requireCurrentVersionAccess} with its VIEW check. On
 * a client proxy nothing overrides it, so the mixin's fail-closed default threw
 * {@code ForbiddenException("... does not define how its version info is
 * authorized")} for every caller, whatever their rights. (Before that guard
 * existed, the next default, {@code getCurrentResourceId}, threw
 * {@code IllegalStateException} instead: the path never worked through a
 * client.) That broke {@code setup-api/sync} with an HTTP 500 before it wrote
 * anything, and every MCP admin tool that resolves "the latest version" through
 * {@link IRestInterfaceFactory}.
 * <p>
 * Routing the one call over HTTP fixes all those callers at once and keeps the
 * authorization where it belongs: the endpoint runs its role check and the
 * store's VIEW check under the identity the loopback filter forwards. Every
 * other method, default ones included, goes to the client unchanged.
 * <p>
 * {@code redirectToLatestVersion} is not routed: it answers a 303 that a client
 * would follow, and no in-process caller uses it.
 */
final class CurrentVersionRouting {

    /**
     * The one endpoint, addressed relative to the store's own {@code @Path} (the
     * client's base URI carries it). No class-level {@code @Path}, so the server
     * never mistakes it for a resource.
     */
    public interface CurrentVersionEndpoint {
        @GET
        @Path("/{id}/currentversion")
        @Produces(MediaType.TEXT_PLAIN)
        String getCurrentVersion(@PathParam("id") String id);
    }

    private static final Method GET_CURRENT_VERSION;

    static {
        try {
            GET_CURRENT_VERSION = IRestVersionInfo.class.getMethod("getCurrentVersion", String.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CurrentVersionRouting() {
    }

    /**
     * {@code client}, with {@code getCurrentVersion} sent to {@code <baseUri><@Path
     * of type>/{id}/currentversion} through a client {@code endpoints} builds on
     * first use. A type that is not an {@link IRestVersionInfo} or carries no
     * {@code @Path} is returned as it is.
     */
    static <T> T route(Class<T> type, T client, String baseUri, Function<URI, CurrentVersionEndpoint> endpoints) {
        if (!type.isInterface() || !IRestVersionInfo.class.isAssignableFrom(type)) {
            return client;
        }
        Path path = type.getAnnotation(Path.class);
        if (path == null) {
            return client;
        }
        URI storeUri = storeUri(baseUri, path.value());
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new Handler(client, storeUri, endpoints));
        return type.cast(proxy);
    }

    /** {@code baseUri} and the store's path joined with exactly one slash. */
    static URI storeUri(String baseUri, String storePath) {
        String base = baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
        String path = storePath.startsWith("/") ? storePath : "/" + storePath;
        return URI.create(base + path);
    }

    private static final class Handler implements InvocationHandler {
        private final Object client;
        private final URI storeUri;
        private final Function<URI, CurrentVersionEndpoint> endpoints;
        private volatile CurrentVersionEndpoint endpoint;

        Handler(Object client, URI storeUri, Function<URI, CurrentVersionEndpoint> endpoints) {
            this.client = client;
            this.storeUri = storeUri;
            this.endpoints = endpoints;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isGetCurrentVersion(method)) {
                String body = endpoint().getCurrentVersion((String) args[0]);
                return body == null ? null : Integer.valueOf(body.trim());
            }
            try {
                return method.invoke(client, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private CurrentVersionEndpoint endpoint() {
            CurrentVersionEndpoint current = endpoint;
            if (current == null) {
                current = endpoints.apply(storeUri);
                endpoint = current;
            }
            return current;
        }
    }

    private static boolean isGetCurrentVersion(Method method) {
        return method.getName().equals(GET_CURRENT_VERSION.getName())
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == String.class
                && method.getReturnType() == Integer.class;
    }
}
