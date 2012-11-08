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

import java.security.PrivilegedAction;

import org.osgi.service.subsystem.SubsystemException;

public abstract class AbstractAction implements PrivilegedAction<Object> {
	protected final boolean disableRootCheck;
	protected final BasicSubsystem requestor;
	protected final BasicSubsystem target;
	
	public AbstractAction(BasicSubsystem requestor, BasicSubsystem target, boolean disableRootCheck) {
		this.requestor = requestor;
		this.target = target;
		this.disableRootCheck = disableRootCheck;
	}
	
	protected void checkRoot() {
		if (!disableRootCheck && target.isRoot())
			throw new SubsystemException("This operation may not be performed on the root subsystem");
	}
	
	protected void checkValid() {
		BasicSubsystem s = (BasicSubsystem)Activator.getInstance().getSubsystemServiceRegistrar().getSubsystemService(target);
		if (s != target)
			throw new IllegalStateException("Detected stale subsystem instance: " + s);
	}
	
	protected void waitForStateChange() {
		synchronized (target) {
			try {
				target.wait();
			}
			catch (InterruptedException e) {
				throw new SubsystemException(e);
			}
		}
	}
}
