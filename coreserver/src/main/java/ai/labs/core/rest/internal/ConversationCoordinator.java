package ai.labs.core.rest.internal;

import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;

import ai.labs.runtime.SystemRuntime;

@Singleton
public class ConversationCoordinator {

	private final Map<String, Queue<FutureTask<?>>> conversationQueues = new ConcurrentHashMap<>();

	public <T> Future<T> submitInOrder(String conversationId, Callable<T> callable) {
		final Queue<FutureTask<T>> queue = (Queue<FutureTask<T>>) (Queue) conversationQueues.computeIfAbsent(conversationId, (key) -> new LinkedTransferQueue<>());

		FutureTask<T> futureTask = new FutureTask<>(callable);
		synchronized (queue) {
			if (queue.isEmpty()) {
				queue.offer(futureTask);
				SystemRuntime.getRuntime().submitRunable(futureTask, new SystemRuntime.IRuntime.IFinishedExecution<T>() {
					@Override
					public void onComplete(T result) {
						submitNext();
					}

					@Override
					public void onFailure(Throwable t) {
						submitNext();
					}

					private void submitNext() {
						synchronized (queue) {
							queue.remove();
							if (!queue.isEmpty()) {
								SystemRuntime.getRuntime().submitRunable(queue.element(), this, null);
							}
						}
					}
				}, Collections.emptyMap());
			} else {
				queue.offer(futureTask);
			}
		}
		return futureTask;
	}
}
