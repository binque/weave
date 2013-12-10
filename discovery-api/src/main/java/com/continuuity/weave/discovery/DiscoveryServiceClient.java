/*
 * Copyright 2012-2013 Continuuity,Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.discovery;

/**
 * Interface for {@link DiscoveryServiceClient} to discover services registered with {@link DiscoveryService}.
 */
public interface DiscoveryServiceClient {

  /**
   * Retrieves a list of {@link Discoverable} for the a service with the given name.
   *
   * @param name Name of the service
   * @return A {@link com.continuuity.weave.discovery.ServiceDiscovered} object representing the result.
   */
  ServiceDiscovered discover(String name);
}
