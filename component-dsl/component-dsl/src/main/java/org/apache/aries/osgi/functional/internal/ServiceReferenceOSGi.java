/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.aries.osgi.functional.internal;

import org.apache.aries.osgi.functional.OSGi;
import org.apache.aries.osgi.functional.OSGiResult;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @author Carlos Sierra Andrés
 */
public class ServiceReferenceOSGi<T> extends OSGiImpl<ServiceReference<T>> {

	private String _filterString;
	private Class<T> _clazz;

	public ServiceReferenceOSGi(String filterString, Class<T> clazz) {
		super(bundleContext -> {
			Pipe<Tuple<ServiceReference<T>>, Tuple<ServiceReference<T>>>
				added = Pipe.create();

			Pipe<Tuple<ServiceReference<T>>, Tuple<ServiceReference<T>>>
				removed = Pipe.create();

			ServiceTracker<T, AtomicReference<Tuple<ServiceReference<T>>>>
				serviceTracker = new ServiceTracker<>(
					bundleContext,
					buildFilter(bundleContext, filterString, clazz),
					new DefaultServiceTrackerCustomizer<>(
						added.getSource(), removed.getSource()));

			return new OSGiResultImpl<>(
				added, removed, serviceTracker::open, serviceTracker::close);
		});

		_filterString = filterString;
		_clazz = clazz;
	}

	@Override
	public <S> OSGiImpl<S> flatMap(
		Function<? super ServiceReference<T>, OSGi<? extends S>> fun) {

		return new OSGiImpl<>(bundleContext -> {
			Pipe<Tuple<S>, Tuple<S>> added = Pipe.create();

			Pipe<Tuple<S>, Tuple<S>> removed = Pipe.create();

			ServiceTracker<T, ?> serviceTracker =
				new ServiceTracker<>(
					bundleContext,
					buildFilter(
						bundleContext, _filterString, _clazz),
						new FlatMapServiceTrackerCustomizer<>(
							fun, bundleContext, added.getSource(),
							removed.getSource()));

			return new OSGiResultImpl<>(
				added, removed, serviceTracker::open, serviceTracker::close);
		});
	}

	private static class DefaultServiceTrackerCustomizer<T>
		implements ServiceTrackerCustomizer<T, AtomicReference<Tuple<ServiceReference<T>>>> {

		private final Consumer<Tuple<ServiceReference<T>>> _addedSource;
		private final Consumer<Tuple<ServiceReference<T>>> _removedSource;

		public DefaultServiceTrackerCustomizer(
			Consumer<Tuple<ServiceReference<T>>> addedSource,
			Consumer<Tuple<ServiceReference<T>>> removedSource) {

			_addedSource = addedSource;
			_removedSource = removedSource;
		}

		@Override
		public AtomicReference<Tuple<ServiceReference<T>>> addingService(
			ServiceReference<T> reference) {

			Tuple<ServiceReference<T>> tuple = Tuple.create(reference);

			_addedSource.accept(tuple);

			return new AtomicReference<>(tuple);
		}

		@Override
		public void modifiedService(
			ServiceReference<T> reference,
			AtomicReference<Tuple<ServiceReference<T>>> tupleReference) {

			_removedSource.accept(tupleReference.get());

			Tuple<ServiceReference<T>> tuple = Tuple.create(reference);

			tupleReference.set(tuple);

			_addedSource.accept(tuple);
		}

		@Override
		public void removedService(
			ServiceReference<T> reference,
			AtomicReference<Tuple<ServiceReference<T>>> tuple) {

			_removedSource.accept(tuple.get());
		}
	}

	private static class FlatMapServiceTrackerCustomizer<T, S>
		implements ServiceTrackerCustomizer<T, AtomicReference<OSGiResult<S>>> {
		private final Function<? super ServiceReference<T>, OSGi<? extends S>>
			_fun;
		private final BundleContext _bundleContext;
		private final Consumer<Tuple<S>> _addedSource;

		private final Consumer<Tuple<S>> _removedSource;

		FlatMapServiceTrackerCustomizer(
			Function<? super ServiceReference<T>, OSGi<? extends S>> fun,
			BundleContext bundleContext, Consumer<Tuple<S>> addedSource,
			Consumer<Tuple<S>> removedSource) {

			_fun = fun;
			_bundleContext = bundleContext;
			_addedSource = addedSource;
			_removedSource = removedSource;
		}

		@Override
        public AtomicReference<OSGiResult<S>> addingService(
        	ServiceReference<T> reference) {

			OSGiResultImpl<S> osgiResult = doFlatMap(reference);

			return new AtomicReference<>(osgiResult);
        }

		private OSGiResultImpl<S> doFlatMap(ServiceReference<T> reference) {
			OSGiImpl<S> program = (OSGiImpl<S>) _fun.apply(reference);

			OSGiResultImpl<S> osgiResult = program._operation.run(
				_bundleContext);

			osgiResult.pipeTo(_addedSource, _removedSource);

			return osgiResult;
		}

		@Override
        public void modifiedService(
        	ServiceReference<T> reference,
			AtomicReference<OSGiResult<S>> tracked) {

			tracked.get().close();

			tracked.set(doFlatMap(reference));
        }

		@Override
        public void removedService(
            ServiceReference<T> reference,
			AtomicReference<OSGiResult<S>> tracked) {

            tracked.get().close();
        }

	}

}
