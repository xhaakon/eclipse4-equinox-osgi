/*******************************************************************************
 * Copyright (c) 2004, 2013 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.location;

import java.io.IOException;

/**
 * Internal class.
 */
public interface Locker {
	public boolean lock() throws IOException;

	public boolean isLocked() throws IOException;

	public void release();

	static class MockLocker implements Locker {
		/**
		 * @throws IOException  
		 */
		public boolean lock() throws IOException {
			// locking always successful
			return true;
		}

		public boolean isLocked() {
			// this lock is never locked
			return false;
		}

		public void release() {
			// nothing to release
		}

	}
}
