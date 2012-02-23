/*
 * Copyright (c) OSGi Alliance (2012). All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.osgi.service.repository;

import java.io.IOException;
import java.io.InputStream;

import org.osgi.resource.Resource;

/**
 * An accessor for the content of a resource.
 * 
 * All {@link Resource} objects which represent resources in a
 * {@link Repository} must implement this interface. A user of the resource can
 * then cast the {@link Resource} object to this type and then obtain an
 * {@code InputStream} to the content of the resource.
 * 
 * @ThreadSafe
 * @version $Id: 6a91b25ad4eff5054b1436ba9f5c9c4a58e89cf7 $
 */
public interface RepositoryContent {

	/**
	 * Returns a new input stream to the underlying artifact for the associated
	 * resource.
	 * 
	 * @return A new input stream for associated resource.
	 * @throws IOException If an I/O error occurs while creating the input
	 *         stream.
	 */
	InputStream getContent() throws IOException;
}
