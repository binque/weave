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
package com.continuuity.weave.internal.yarn;

import com.continuuity.weave.common.Cancellable;

/**
 * Abstraction for dealing with API differences in different hadoop yarn version
 */
public interface YarnNMClient {

  /**
   * Starts a process based on the given launch context.
   *
   * @param containerInfo The containerInfo that the new process will launch in.
   * @param launchContext Contains information about the process going to start.
   * @return A {@link Cancellable} that when {@link Cancellable#cancel()}} is invoked,
   *         it will try to shutdown the process.
   *
   */
  Cancellable start(YarnContainerInfo containerInfo, YarnLaunchContext launchContext);
}
