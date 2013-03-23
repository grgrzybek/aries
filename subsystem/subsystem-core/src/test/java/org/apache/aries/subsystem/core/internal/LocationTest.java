/*
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
package org.apache.aries.subsystem.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.net.MalformedURLException;

import org.junit.Test;

public class LocationTest {
	@Test
	public void testAnyLocationString() {
		String locationStr = "anyLocation";
		Location location = null;
		try {
			location = new Location(locationStr);
		}
		catch (Throwable t) {
			t.printStackTrace();
			fail("Any location string must be supported");
		}
		assertNull("Wrong symbolic name", location.getSymbolicName());
		assertEquals("Wrong value", locationStr, location.getValue());
		assertNull("Wrong version", location.getVersion());
		try {
			location.open();
			fail("Opening a location that does not represent a URL should fail");
		}
		catch (MalformedURLException e) {
			// Okay
		}
		catch (Throwable t) {
			t.printStackTrace();
			fail("Wrong exception");
		}
	}
}
