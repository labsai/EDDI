package ai.labs.rest;

import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.TimeoutHandler;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MockAsyncResponse implements AsyncResponse {

    @Override
    public boolean resume(Object response) {
        return false;
    }

    @Override
    public boolean resume(Throwable response) {
        return false;
    }

    @Override
    public boolean cancel() {
        return false;
    }

    @Override
    public boolean cancel(int retryAfter) {
        return false;
    }

    @Override
    public boolean cancel(Date retryAfter) {
        return false;
    }

    @Override
    public boolean isSuspended() {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        return false;
    }

    @Override
    public void setTimeoutHandler(TimeoutHandler handler) {

    }

    @Override
    public Collection<Class<?>> register(Class<?> callback) {
        return null;
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
        return null;
    }

    @Override
    public Collection<Class<?>> register(Object callback) {
        return null;
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
        return null;
    }
}