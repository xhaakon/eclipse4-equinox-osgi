/*
 * Copyright (c) OSGi Alliance (2005, 2013). All Rights Reserved.
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

package org.osgi.service.event;

import org.osgi.annotation.versioning.ProviderType;

/**
 * The Event Admin service. Bundles wishing to publish events must obtain the
 * Event Admin service and call one of the event delivery methods.
 * 
 * @ThreadSafe
 * @author $Id: 529f0431b3cb8f682c95107b2b7d0bc255a968dd $
 */
@ProviderType
public interface EventAdmin {
	/**
	 * Initiate asynchronous, ordered delivery of an event. This method returns
	 * to the caller before delivery of the event is completed. Events are
	 * delivered in the order that they are received by this method.
	 * 
	 * @param event The event to send to all listeners which subscribe to the
	 *        topic of the event.
	 * 
	 * @throws SecurityException If the caller does not have
	 *         {@code TopicPermission[topic,PUBLISH]} for the topic specified in
	 *         the event.
	 */
	void postEvent(Event event);

	/**
	 * Initiate synchronous delivery of an event. This method does not return to
	 * the caller until delivery of the event is completed.
	 * 
	 * @param event The event to send to all listeners which subscribe to the
	 *        topic of the event.
	 * 
	 * @throws SecurityException If the caller does not have
	 *         {@code TopicPermission[topic,PUBLISH]} for the topic specified in
	 *         the event.
	 */
	void sendEvent(Event event);
}
