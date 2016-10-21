/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.pushstream;

import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.apache.aries.pushstream.AbstractPushStreamImpl.State.*;
import static org.osgi.util.pushstream.PushEventConsumer.*;

import java.time.Duration;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamBuilder;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.PushEvent.EventType;

public abstract class AbstractPushStreamImpl<T> implements PushStream<T> {
	
	public static enum State {
		BUILDING, STARTED, CLOSED
	}
	
	protected final PushStreamProvider								psp;
	
	protected final Executor										defaultExecutor;
	protected final ScheduledExecutorService						scheduler;

	protected final AtomicReference<State> closed = new AtomicReference<>(BUILDING);
	
	protected final AtomicReference<PushEventConsumer<T>>			next			= new AtomicReference<>();
	
	protected final AtomicReference<Runnable> onCloseCallback = new AtomicReference<>();
	protected final AtomicReference<Consumer<? super Throwable>> onErrorCallback = new AtomicReference<>();

	protected abstract boolean begin();
	
	protected AbstractPushStreamImpl(PushStreamProvider psp,
			Executor executor, ScheduledExecutorService scheduler) {
		this.psp = psp;
		this.defaultExecutor = executor;
		this.scheduler = scheduler;
	}

	protected long handleEvent(PushEvent< ? extends T> event) {
		if(closed.get() != CLOSED) {
			try {
				if(event.isTerminal()) {
					close(event.nodata());
					return ABORT;
				} else {
					PushEventConsumer<T> consumer = next.get();
					long val;
					if(consumer == null) {
						//TODO log a warning
						val = CONTINUE;
					} else {
						val = consumer.accept(event);
					}
					if(val < 0) {
						close();
					}
					return val;
				}
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		}
		return ABORT;
	}
	
	@Override
	public void close() {
		close(PushEvent.close());
	}
	
	protected boolean close(PushEvent<T> event) {
		if(!event.isTerminal()) {
			throw new IllegalArgumentException("The event " + event  + " is not a close event.");
		}
		if(closed.getAndSet(CLOSED) != CLOSED) {
			PushEventConsumer<T> aec = next.getAndSet(null);
			if(aec != null) {
				try {
					aec.accept(event);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			Runnable handler = onCloseCallback.getAndSet(null);
			if(handler != null) {
				try {
					handler.run();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (event.getType() == EventType.ERROR) {
				Consumer<? super Throwable> errorHandler = onErrorCallback.getAndSet(null);
				if(errorHandler != null) {
					try {
						errorHandler.accept(event.getFailure());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			return true;
		}
		return false;
	}
	
	@Override
	public PushStream<T> onClose(Runnable closeHandler) {
		if(onCloseCallback.compareAndSet(null, closeHandler)) {
			if(closed.get() == State.CLOSED && onCloseCallback.compareAndSet(closeHandler, null)) {
				closeHandler.run();
			}
		} else {
			throw new IllegalStateException("A close handler has already been defined for this stream object");
		}
		return this;
	}

	@Override
	public PushStream<T> onError(Consumer< ? super Throwable> closeHandler) {
		if(onErrorCallback.compareAndSet(null, closeHandler)) {
			if(closed.get() == State.CLOSED) { 
				//TODO log already closed
				onErrorCallback.set(null);
			}
		} else {
			throw new IllegalStateException("A close handler has already been defined for this stream object");
		}
		return this;
	}

	private void updateNext(PushEventConsumer<T> consumer) {
		if(!next.compareAndSet(null, consumer)) {
			throw new IllegalStateException("This stream has already been chained");
		} else if(closed.get() == CLOSED && next.compareAndSet(consumer, null)) {
			try {
				consumer.accept(PushEvent.close());
			} catch (Exception e) {
				//TODO log
				e.printStackTrace();
			}
		}
	}

	@Override
	public PushStream<T> filter(Predicate< ? super T> predicate) {
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		updateNext((event) -> {
			try {
				if (!event.isTerminal()) {
					if (predicate.test(event.getData())) {
						return eventStream.handleEvent(event);
					} else {
						return CONTINUE;
					}
				}
				return eventStream.handleEvent(event);
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public <R> PushStream<R> map(Function< ? super T, ? extends R> mapper) {
		
		AbstractPushStreamImpl<R> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		updateNext(event -> {
			try {
				if (!event.isTerminal()) {
					return eventStream.handleEvent(
							PushEvent.data(mapper.apply(event.getData())));
				} else {
					return eventStream.handleEvent(event.nodata());
				}
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public <R> PushStream<R> flatMap(
			Function< ? super T, ? extends PushStream< ? extends R>> mapper) {
		AbstractPushStreamImpl<R> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);

		PushEventConsumer<R> consumer = e -> {
			switch (e.getType()) {
				case ERROR :
					close(e.nodata());
					return ABORT;
				case CLOSE :
					// Close should allow the next flat mapped entry
					// without closing the stream;
					return ABORT;
				case DATA :
					long returnValue = eventStream.handleEvent(e);
					if (returnValue < 0) {
						close();
						return ABORT;
					}
					return returnValue;
				default :
					throw new IllegalArgumentException(
							"The event type " + e.getType() + " is unknown");
			}
		};

		updateNext(event -> {
			try {
				if (!event.isTerminal()) {
					PushStream< ? extends R> mappedStream = mapper
							.apply(event.getData());

					return mappedStream.forEachEvent(consumer)
							.getValue()
							.longValue();
				} else {
					return eventStream.handleEvent(event.nodata());
				}
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public PushStream<T> distinct() {
		Set<T> set = Collections.<T>newSetFromMap(new ConcurrentHashMap<>());
		return filter(set::add);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public PushStream<T> sorted() {
		return sorted((Comparator)Comparator.naturalOrder());
	}

	@Override
	public PushStream<T> sorted(Comparator< ? super T> comparator) {
		List<T> list = Collections.synchronizedList(new ArrayList<>());
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		updateNext(event -> {
			try {
				switch(event.getType()) {
					case DATA : 
						list.add(event.getData());
						return CONTINUE;
					case CLOSE :
						list.sort(comparator);
						for(T t : list) {
							eventStream.handleEvent(PushEvent.data(t));
						}
						return ABORT;
					case ERROR :
						return eventStream.handleEvent(event.nodata());
				}
				return eventStream.handleEvent(event.nodata());
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public PushStream<T> limit(long maxSize) {
		if(maxSize <= 0) {
			throw new IllegalArgumentException("The limit must be greater than zero");
		}
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		AtomicLong counter = new AtomicLong(maxSize);
		updateNext(event -> {
			try {
				if (!event.isTerminal()) {
					long count = counter.decrementAndGet();
					if (count > 0) {
						return eventStream.handleEvent(event);
					} else if (count == 0) {
						eventStream.handleEvent(event);
					}
					return ABORT;
				} else {
					return eventStream.handleEvent(event.nodata());
				}
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public PushStream<T> skip(long n) {
		if(n <= 0) {
			throw new IllegalArgumentException("The number to skip must be greater than zero");
		}
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		AtomicLong counter = new AtomicLong(n);
		updateNext(event -> {
			try {
				if (!event.isTerminal()) {
					if (counter.get() > 0 && counter.decrementAndGet() >= 0) {
						return CONTINUE;
					} else {
						return eventStream.handleEvent(event);
					} 				
				} else {
					return eventStream.handleEvent(event.nodata());
				}
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public PushStream<T> fork(int n, int delay, Executor ex) {
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, ex, scheduler, this);
		Semaphore s = new Semaphore(n);
		updateNext(event -> {
			try {
				if (event.isTerminal()) {
					s.acquire(n);
					eventStream.close(event.nodata());
					return ABORT;
				}
	
				s.acquire(1);
	
				ex.execute(() -> {
					try {
						if (eventStream.handleEvent(event) < 0) {
							eventStream.close(PushEvent.close());
						}
					} catch (Exception e1) {
						close(PushEvent.error(e1));
					} finally {
						s.release(1);
					}
				});
	
				return s.getQueueLength() * delay;
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});

		return eventStream;
	}
	
	@Override
	public PushStream<T> buffer() {
		return psp.createStream(c -> {
			forEachEvent(c);
			return this;
		});
	}

	@Override
	public <U extends BlockingQueue<PushEvent< ? extends T>>> PushStreamBuilder<T,U> buildBuffer() {
		return psp.buildStream(c -> {
			forEachEvent(c);
			return this;
		});
	}

	@Override
	public PushStream<T> merge(
			PushEventSource< ? extends T> source) {
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		AtomicInteger count = new AtomicInteger(2);
		PushEventConsumer<T> consumer = event -> {
			try {
				if (!event.isTerminal()) {
					return eventStream.handleEvent(event);
				}
	
				if (count.decrementAndGet() == 0) {
					eventStream.handleEvent(event.nodata());
					return ABORT;
				}
				return CONTINUE;
			} catch (Exception e) {
				PushEvent<T> error = PushEvent.error(e);
				close(error);
				eventStream.close(event.nodata());
				return ABORT;
			}
		};
		updateNext(consumer);
		AutoCloseable second;
		try {
			second = source.open((PushEvent< ? extends T> event) -> {
				return consumer.accept(event);
			});
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new IllegalStateException(
					"Unable to merge events as the event source could not be opened.",
					e);
		}
		
		return eventStream.onClose(() -> {
			try {
				second.close();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} 
		}).map(Function.identity());
	}

	@Override
	public PushStream<T> merge(PushStream< ? extends T> source) {
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		AtomicInteger count = new AtomicInteger(2);
		PushEventConsumer<T> consumer = event -> {
			try {
				if (!event.isTerminal()) {
					return eventStream.handleEvent(event);
				}
				
				if (count.decrementAndGet() == 0) {
					eventStream.handleEvent(event.nodata());
					return ABORT;
				}
				return CONTINUE;
			} catch (Exception e) {
				PushEvent<T> error = PushEvent.error(e);
				close(error);
				eventStream.close(event.nodata());
				return ABORT;
			}
		};
		updateNext(consumer);
		try {
			source.forEachEvent(event -> {
				return consumer.accept(event);
			}).then(p -> {
				count.decrementAndGet();
				consumer.accept(PushEvent.close());
				return null;
			}, p -> {
				count.decrementAndGet();
				consumer.accept(PushEvent.error((Exception) p.getFailure()));
			});
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new IllegalStateException(
					"Unable to merge events as the event source could not be opened.",
					e);
		}
		
		return eventStream.onClose(() -> {
			try {
				source.close();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} 
		}).map(Function.identity());
	}

	@SuppressWarnings("unchecked")
	@Override
	public PushStream<T>[] split(Predicate< ? super T>... predicates) {
		Predicate<? super T>[] tests = Arrays.copyOf(predicates, predicates.length);
		AbstractPushStreamImpl<T>[] rsult = new AbstractPushStreamImpl[tests.length];
		for(int i = 0; i < tests.length; i++) {
			rsult[i] = new IntermediatePushStreamImpl<>(psp, defaultExecutor,
					scheduler, this);
		}
		AtomicReferenceArray<Boolean> off = new AtomicReferenceArray<>(tests.length);
		AtomicInteger count = new AtomicInteger(tests.length);
		updateNext(event -> {
			if (!event.isTerminal()) {
				long delay = CONTINUE;
				for (int i = 0; i < tests.length; i++) {
					try {
						if (off.get(i).booleanValue()
								&& tests[i].test(event.getData())) {
							long accept = rsult[i].handleEvent(event);
							if (accept < 0) {
								off.set(i, Boolean.TRUE);
								count.decrementAndGet();
							} else if (accept > delay) {
								accept = delay;
							}
						}
					} catch (Exception e) {
						try {
							rsult[i].close(PushEvent.error(e));
						} catch (Exception e2) {
							//TODO log
						}
						off.set(i, Boolean.TRUE);
					}
				}
				if (count.get() == 0)
					return ABORT;

				return delay;
			}
			for (AbstractPushStreamImpl<T> as : rsult) {
				try {
					as.handleEvent(event.nodata());
				} catch (Exception e) {
					try {
						as.close(PushEvent.error(e));
					} catch (Exception e2) {
						//TODO log
					}
				}
			}
			return ABORT;
		});
		return Arrays.copyOf(rsult, tests.length);
	}

	@Override
	public PushStream<T> sequential() {
		AbstractPushStreamImpl<T> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		Lock lock = new ReentrantLock();
		updateNext((event) -> {
			try {
				lock.lock();
				try {
					return eventStream.handleEvent(event);
				} finally {
					lock.unlock();
				}
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public <R> PushStream<R> coalesce(
			Function< ? super T,Optional<R>> accumulator) {
		AbstractPushStreamImpl<R> eventStream = new IntermediatePushStreamImpl<>(
				psp, defaultExecutor, scheduler, this);
		updateNext((event) -> {
			try {
				if (!event.isTerminal()) {
					Optional<PushEvent<R>> coalesced = accumulator
							.apply(event.getData()).map(PushEvent::data);
					if (coalesced.isPresent()) {
						try {
							return eventStream.handleEvent(coalesced.get());
						} catch (Exception ex) {
							close(PushEvent.error(ex));
							return ABORT;
						}
					} else {
						return CONTINUE;
					}
				}
				return eventStream.handleEvent(event.nodata());
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	@Override
	public <R> PushStream<R> coalesce(int count, Function<Collection<T>,R> f) {
		if (count <= 0)
			throw new IllegalArgumentException(
					"A coalesce operation must collect a positive number of events");
		// This could be optimised to only use a single collection queue.
		// It would save some GC, but is it worth it?
		return coalesce(() -> count, f);
	}

	@Override
	public <R> PushStream<R> coalesce(IntSupplier count,
			Function<Collection<T>,R> f) {
		AtomicReference<Queue<T>> queueRef = new AtomicReference<Queue<T>>(
				null);

		Runnable init = () -> queueRef
				.set(getQueueForInternalBuffering(count.getAsInt()));

		@SuppressWarnings("resource")
		AbstractPushStreamImpl<R> eventStream = new IntermediatePushStreamImpl<R>(
				psp, defaultExecutor, scheduler, this) {
			@Override
			protected void beginning() {
				init.run();
			}
		};

		AtomicBoolean endPending = new AtomicBoolean();
		Object lock = new Object();
		updateNext((event) -> {
			try {
				Queue<T> queue;
				if (!event.isTerminal()) {
					synchronized (lock) {
						for (;;) {
							queue = queueRef.get();
							if (queue == null) {
								if (endPending.get()) {
									return ABORT;
								} else {
									continue;
								}
							} else if (queue.offer(event.getData())) {
								return CONTINUE;
							} else {
								queueRef.lazySet(null);
								break;
							}
						}
					}

					queueRef.set(
							getQueueForInternalBuffering(count.getAsInt()));

					// This call is on the same thread and so must happen
					// outside
					// the synchronized block.
					return aggregateAndForward(f, eventStream, event,
							queue);
				} else {
					synchronized (lock) {
						queue = queueRef.get();
						queueRef.lazySet(null);
						endPending.set(true);
					}
					if (queue != null) {
						eventStream.handleEvent(
								PushEvent.data(f.apply(queue)));
					}
				}
				return eventStream.handleEvent(event.nodata());
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	private <R> long aggregateAndForward(Function<Collection<T>,R> f,
			AbstractPushStreamImpl<R> eventStream,
			PushEvent< ? extends T> event, Queue<T> queue) {
		if (!queue.offer(event.getData())) {
			((ArrayQueue<T>) queue).forcePush(event.getData());
		}
		return eventStream.handleEvent(PushEvent.data(f.apply(queue)));
	}
	
	
	@Override
	public <R> PushStream<R> window(Duration time,
			Function<Collection<T>,R> f) {
		return window(time, defaultExecutor, f);
	}

	@Override
	public <R> PushStream<R> window(Duration time, Executor executor,
			Function<Collection<T>,R> f) {
		return window(() -> time, () -> 0, executor, (t, c) -> f.apply(c));
	}

	@Override
	public <R> PushStream<R> window(Supplier<Duration> time,
			IntSupplier maxEvents,
			BiFunction<Long,Collection<T>,R> f) {
		return window(time, maxEvents, defaultExecutor, f);
	}

	@Override
	public <R> PushStream<R> window(Supplier<Duration> time,
			IntSupplier maxEvents, Executor ex,
			BiFunction<Long,Collection<T>,R> f) {

		AtomicLong timestamp = new AtomicLong();
		AtomicLong counter = new AtomicLong();
		Object lock = new Object();
		AtomicReference<Queue<T>> queueRef = new AtomicReference<Queue<T>>(
				null);

		// This code is declared as a separate block to avoid any confusion
		// about which instance's methods and variables are in scope
		Consumer<AbstractPushStreamImpl<R>> begin = p -> {

			synchronized (lock) {
				timestamp.lazySet(System.nanoTime());
				long count = counter.get();


				scheduler.schedule(
						getWindowTask(p, f, time, maxEvents, lock, count,
								queueRef, timestamp, counter, ex),
						time.get().toNanos(), NANOSECONDS);
			}

			queueRef.set(getQueueForInternalBuffering(maxEvents.getAsInt()));
		};

		@SuppressWarnings("resource")
		AbstractPushStreamImpl<R> eventStream = new IntermediatePushStreamImpl<R>(
				psp, ex, scheduler, this) {
			@Override
			protected void beginning() {
				begin.accept(this);
			}
		};

		AtomicBoolean endPending = new AtomicBoolean(false);
		updateNext((event) -> {
			try {
				if (eventStream.closed.get() == CLOSED) {
					return ABORT;
				}
				Queue<T> queue;
				if (!event.isTerminal()) {
					long elapsed;
					long newCount;
					synchronized (lock) {
						for (;;) {
							queue = queueRef.get();
							if (queue == null) {
								if (endPending.get()) {
									return ABORT;
								} else {
									continue;
								}
							} else if (queue.offer(event.getData())) {
								return CONTINUE;
							} else {
								queueRef.lazySet(null);
								break;
							}
						}

						long now = System.nanoTime();
						elapsed = now - timestamp.get();
						timestamp.lazySet(now);
						newCount = counter.get() + 1;
						counter.lazySet(newCount);

						// This is a non-blocking call, and must happen in the
						// synchronized block to avoid re=ordering the executor
						// enqueue with a subsequent incoming close operation
						aggregateAndForward(f, eventStream, event, queue,
								ex, elapsed);
					}
					// These must happen outside the synchronized block as we
					// call out to user code
					queueRef.set(
							getQueueForInternalBuffering(maxEvents.getAsInt()));
					scheduler.schedule(
							getWindowTask(eventStream, f, time, maxEvents, lock,
									newCount, queueRef, timestamp, counter, ex),
							time.get().toNanos(), NANOSECONDS);

					return CONTINUE;
				} else {
					long elapsed;
					synchronized (lock) {
						queue = queueRef.get();
						queueRef.lazySet(null);
						endPending.set(true);
						long now = System.nanoTime();
						elapsed = now - timestamp.get();
						counter.lazySet(counter.get() + 1);
					}
					Collection<T> collected = queue == null ? emptyList()
							: queue;
					ex.execute(() -> {
						try {
							eventStream
									.handleEvent(PushEvent.data(f.apply(
											Long.valueOf(NANOSECONDS
													.toMillis(elapsed)),
											collected)));
						} catch (Exception e) {
							close(PushEvent.error(e));
						}
					});
				}
				ex.execute(() -> eventStream.handleEvent(event.nodata()));
				return ABORT;
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		return eventStream;
	}

	protected Queue<T> getQueueForInternalBuffering(int size) {
		if (size == 0) {
			return new LinkedList<T>();
		} else {
			return new ArrayQueue<>(size - 1);
		}
	}
	
	@SuppressWarnings("unchecked")
	/**
	 * A special queue that keeps one element in reserve and can have that last
	 * element set using forcePush. After the element is set the capacity is
	 * permanently increased by one and cannot grow further.
	 * 
	 * @param <E> The element type
	 */
	private static class ArrayQueue<E> extends AbstractQueue<E>
			implements Queue<E> {

		final Object[]	store;

		int				normalLength;

		int				nextIndex;

		int				size;

		ArrayQueue(int capacity) {
			store = new Object[capacity + 1];
			normalLength = store.length - 1;
		}

		@Override
		public boolean offer(E e) {
			if (e == null)
				throw new NullPointerException("Null values are not supported");
			if (size < normalLength) {
				store[nextIndex] = e;
				size++;
				nextIndex++;
				nextIndex = nextIndex % normalLength;
				return true;
			}
			return false;
		}

		public void forcePush(E e) {
			store[normalLength] = e;
			normalLength++;
			size++;
		}

		@Override
		public E poll() {
			if (size == 0) {
				return null;
			} else {
				int idx = nextIndex - size;
				if (idx < 0) {
					idx += normalLength;
				}
				E value = (E) store[idx];
				store[idx] = null;
				size--;
				return value;
			}
		}

		@Override
		public E peek() {
			if (size == 0) {
				return null;
			} else {
				int idx = nextIndex - size;
				if (idx < 0) {
					idx += normalLength;
				}
				return (E) store[idx];
			}
		}

		@Override
		public Iterator<E> iterator() {
			final int previousNext = nextIndex;
			return new Iterator<E>() {

				int idx;

				int	remaining	= size;

				{
					idx = nextIndex - size;
					if (idx < 0) {
						idx += normalLength;
					}
				}

				@Override
				public boolean hasNext() {
					if (nextIndex != previousNext) {
						throw new ConcurrentModificationException(
								"The queue was concurrently modified");
					}
					return remaining > 0;
				}

				@Override
				public E next() {
					if (!hasNext()) {
						throw new NoSuchElementException(
								"The iterator has no more values");
					}
					E value = (E) store[idx];
					idx++;
					remaining--;
					if (idx == normalLength) {
						idx = 0;
					}
					return value;
				}

			};
		}

		@Override
		public int size() {
			return size;
		}

	}

	private <R> Runnable getWindowTask(AbstractPushStreamImpl<R> eventStream,
			BiFunction<Long,Collection<T>,R> f, Supplier<Duration> time,
			IntSupplier maxEvents, Object lock, long expectedCounter,
			AtomicReference<Queue<T>> queueRef, AtomicLong timestamp,
			AtomicLong counter, Executor executor) {
		return () -> {

			Queue<T> queue = null;
			long elapsed;
			synchronized (lock) {
				
				if (counter.get() != expectedCounter) {
					return;
				}
				counter.lazySet(expectedCounter + 1);

				long now = System.nanoTime();
				elapsed = now - timestamp.get();
				timestamp.lazySet(now);

				queue = queueRef.get();
				queueRef.lazySet(null);

				// This is a non-blocking call, and must happen in the
				// synchronized block to avoid re=ordering the executor
				// enqueue with a subsequent incoming close operation

				Collection<T> collected = queue == null ? emptyList() : queue;
				executor.execute(() -> {
					try {
						eventStream.handleEvent(PushEvent.data(f.apply(
								Long.valueOf(NANOSECONDS.toMillis(elapsed)),
								collected)));
					} catch (Exception e) {
						close(PushEvent.error(e));
					}
				});
			}

			// These must happen outside the synchronized block as we
			// call out to user code
			queueRef.set(getQueueForInternalBuffering(maxEvents.getAsInt()));
			scheduler.schedule(
					getWindowTask(eventStream, f, time, maxEvents, lock,
							expectedCounter + 1, queueRef, timestamp, counter,
							executor),
					time.get().toNanos(), NANOSECONDS);
		};
	}

	private <R> void aggregateAndForward(BiFunction<Long,Collection<T>,R> f,
			AbstractPushStreamImpl<R> eventStream,
			PushEvent< ? extends T> event, Queue<T> queue, Executor executor,
			long elapsed) {
		executor.execute(() -> {
			try {
				if (!queue.offer(event.getData())) {
					((ArrayQueue<T>) queue).forcePush(event.getData());
				}
				long result = eventStream.handleEvent(PushEvent.data(
						f.apply(Long.valueOf(NANOSECONDS.toMillis(elapsed)),
								queue)));
				if (result < 0) {
					close();
				}
			} catch (Exception e) {
				close(PushEvent.error(e));
			}
		});
	}

	@Override
	public Promise<Void> forEach(Consumer< ? super T> action) {
		Deferred<Void> d = new Deferred<>();
		updateNext((event) -> {
				try {
					switch(event.getType()) {
						case DATA:
							action.accept(event.getData());
							return CONTINUE;
						case CLOSE:
							d.resolve(null);
							break;
						case ERROR:
							d.fail(event.getFailure());
							break;
					}
					close(event.nodata());
					return ABORT;
				} catch (Exception e) {
					d.fail(e);
					return ABORT;
				}
			});
		begin();
		return d.getPromise();
	}

	@Override
	public Promise<Object[]> toArray() {
		return collect(Collectors.toList())
				.map(List::toArray);
	}

	@Override
	public <A extends T> Promise<A[]> toArray(IntFunction<A[]> generator) {
		return collect(Collectors.toList())
				.map(l -> l.toArray(generator.apply(l.size())));
	}

	@Override
	public Promise<T> reduce(T identity, BinaryOperator<T> accumulator) {
		Deferred<T> d = new Deferred<>();
		AtomicReference<T> iden = new AtomicReference<T>(identity);

		updateNext(event -> {
			try {
				switch(event.getType()) {
					case DATA:
						iden.accumulateAndGet(event.getData(), accumulator);
						return CONTINUE;
					case CLOSE:
						d.resolve(iden.get());
						break;
					case ERROR:
						d.fail(event.getFailure());
						break;
				}
				close(event.nodata());
				return ABORT;
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		begin();
		return d.getPromise();
	}

	@Override
	public Promise<Optional<T>> reduce(BinaryOperator<T> accumulator) {
		Deferred<Optional<T>> d = new Deferred<>();
		AtomicReference<T> iden = new AtomicReference<T>(null);

		updateNext(event -> {
			try {
				switch(event.getType()) {
					case DATA:
						if (!iden.compareAndSet(null, event.getData()))
							iden.accumulateAndGet(event.getData(), accumulator);
						return CONTINUE;
					case CLOSE:
						d.resolve(Optional.ofNullable(iden.get()));
						break;
					case ERROR:
						d.fail(event.getFailure());
						break;
				}
				close(event.nodata());
				return ABORT;
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		begin();
		return d.getPromise();
	}

	@Override
	public <U> Promise<U> reduce(U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
		Deferred<U> d = new Deferred<>();
		AtomicReference<U> iden = new AtomicReference<>(identity);

		updateNext(event -> {
			try {
				switch(event.getType()) {
					case DATA:
						iden.updateAndGet((e) -> accumulator.apply(e, event.getData()));
						return CONTINUE;
					case CLOSE:
						d.resolve(iden.get());
						break;
					case ERROR:
						d.fail(event.getFailure());
						break;
				}
				close(event.nodata());
				return ABORT;
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		begin();
		return d.getPromise();
	}

	@Override
	public <R, A> Promise<R> collect(Collector<? super T, A, R> collector) {
		A result = collector.supplier().get();
		Deferred<R> d = new Deferred<>();
		updateNext(event -> {
			try {
				switch(event.getType()) {
					case DATA:
						collector.accumulator().accept(result, event.getData());
						return CONTINUE;
					case CLOSE:
						d.resolve(collector.finisher().apply(result));
						break;
					case ERROR:
						d.fail(event.getFailure());
						break;
				}
				close(event.nodata());
				return ABORT;
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		begin();
		return d.getPromise();
	}

	@Override
	public Promise<Optional<T>> min(Comparator<? super T> comparator)  {
		return reduce((a, b) -> comparator.compare(a, b) <= 0 ? a : b);
	}

	@Override
	public Promise<Optional<T>> max(Comparator<? super T> comparator) {
		return reduce((a, b) -> comparator.compare(a, b) > 0 ? a : b);
	}

	@Override
	public Promise<Long> count() {
		Deferred<Long> d = new Deferred<>();
		LongAdder counter = new LongAdder();
		updateNext((event) -> {
				try {
					switch(event.getType()) {
						case DATA:
						counter.add(1);
							return CONTINUE;
						case CLOSE:
						d.resolve(Long.valueOf(counter.sum()));
							break;
						case ERROR:
							d.fail(event.getFailure());
							break;
					}
					close(event.nodata());
					return ABORT;
				} catch (Exception e) {
				close(PushEvent.error(e));
					return ABORT;
				}
			});
		begin();
		return d.getPromise();
	}

	@Override
	public Promise<Boolean> anyMatch(Predicate<? super T> predicate) {
		return filter(predicate).findAny()
			.map(Optional::isPresent);
	}

	@Override
	public Promise<Boolean> allMatch(Predicate<? super T> predicate) {
		return filter(x -> !predicate.test(x)).findAny()
				.map(o -> Boolean.valueOf(!o.isPresent()));
	}

	@Override
	public Promise<Boolean> noneMatch(Predicate<? super T> predicate) {
		return filter(predicate).findAny()
				.map(o -> Boolean.valueOf(!o.isPresent()));
	}

	@Override
	public Promise<Optional<T>> findFirst() {
		Deferred<Optional<T>> d = new Deferred<>();
		updateNext((event) -> {
				try {
					Optional<T> o = null;
					switch(event.getType()) {
						case DATA:
							o = Optional.of(event.getData());
							break;
						case CLOSE:
							o = Optional.empty();
							break;
						case ERROR:
							d.fail(event.getFailure());
							return ABORT;
					}
					if(!d.getPromise().isDone())
						d.resolve(o);
					return ABORT;
				} catch (Exception e) {
				close(PushEvent.error(e));
					return ABORT;
				}
			});
		begin();
		return d.getPromise();
	}

	@Override
	public Promise<Optional<T>> findAny() {
		return findFirst();
	}

	@Override
	public Promise<Long> forEachEvent(PushEventConsumer< ? super T> action) {
		Deferred<Long> d = new Deferred<>();
		LongAdder la = new LongAdder();
		updateNext((event) -> {
			try {
				switch(event.getType()) {
					case DATA:
						long value = action.accept(event);
						la.add(value);
						return value;
					case CLOSE:
						try {
							action.accept(event);
						} finally {
							d.resolve(Long.valueOf(la.sum()));
						}
						break;
					case ERROR:
						try {
							action.accept(event);
						} finally {
							d.fail(event.getFailure());
						}
						break;
				}
				return ABORT;
			} catch (Exception e) {
				close(PushEvent.error(e));
				return ABORT;
			}
		});
		begin();
		return d.getPromise();
	}

}
