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
package com.continuuity.weave.api;

/**
 * Represents class capable of creating update of {@link SecureStore} for live applications.
 */
public interface SecureStoreUpdater {

  /**
   * Invoked when an update to SecureStore is needed.
   *
   * @param application The name of the application.
   * @param runId The runId of the live application.
   * @return A new {@link SecureStore}.
   */
  SecureStore update(String application, RunId runId);
}
