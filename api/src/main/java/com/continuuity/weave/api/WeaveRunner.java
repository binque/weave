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

import com.continuuity.weave.common.Cancellable;

import java.util.concurrent.TimeUnit;

/**
 * This interface prepares execution of {@link WeaveRunnable} and {@link WeaveApplication}.
 */
public interface WeaveRunner {

  /**
   * Interface to represents information of a live application.
   */
  interface LiveInfo {

    /**
     * Returns name of the application.
     * @return Application name as a {@link String}.
     */
    String getApplicationName();

    /**
     * Returns {@link WeaveController}s for all live instances of the application.
     * @return An {@link Iterable} of {@link WeaveController}.
     */
    Iterable<WeaveController> getControllers();
  }

  /**
   * Prepares to run the given {@link WeaveRunnable} with {@link ResourceSpecification#BASIC} resource specification.
   * @param runnable The runnable to run through Weave when {@link WeavePreparer#start()} is called.
   * @return A {@link WeavePreparer} for setting up runtime options.
   */
  WeavePreparer prepare(WeaveRunnable runnable);

  /**
   * Prepares to run the given {@link WeaveRunnable} with the given resource specification.
   * @param runnable The runnable to run through Weave when {@link WeavePreparer#start()} is called.
   * @param resourceSpecification The resource specification for running the runnable.
   * @return A {@link WeavePreparer} for setting up runtime options.
   */
  WeavePreparer prepare(WeaveRunnable runnable, ResourceSpecification resourceSpecification);

  /**
   * Prepares to run the given {@link WeaveApplication} as specified by the application.
   * @param application The application to run through Weave when {@link WeavePreparer#start()} is called.
   * @return A {@link WeavePreparer} for setting up runtime options.
   */
  WeavePreparer prepare(WeaveApplication application);

  /**
   * Gets a {@link WeaveController} for the given application and runId.
   * @param applicationName Name of the application.
   * @param runId The runId of the running application.
   * @return A {@link WeaveController} to interact with the application or null if no such runId is found.
   */
  WeaveController lookup(String applicationName, RunId runId);

  /**
   * Gets an {@link Iterable} of {@link WeaveController} for all running instances of the given application.
   * @param applicationName Name of the application.
   * @return A live {@link Iterable} that gives the latest {@link WeaveController} set for all running
   *         instances of the application when {@link Iterable#iterator()} is invoked.
   */
  Iterable<WeaveController> lookup(String applicationName);

  /**
   * Gets an {@link Iterable} of {@link LiveInfo}.
   * @return A live {@link Iterable} that gives the latest information on the set of applications that
   *         have running instances when {@link Iterable#iterator()}} is invoked.
   */
  Iterable<LiveInfo> lookupLive();

  /**
   * Schedules a periodic update of SecureStore. The first call to the given {@link SecureStoreUpdater} will be made
   * after {@code initialDelay}, and subsequently with the given {@code delay} between completion of one update
   * and starting of the next. If exception is thrown on call
   * {@link SecureStoreUpdater#update(String, RunId)}, the exception will only get logged
   * and won't suppress the next update call.
   *
   * @param updater A {@link SecureStoreUpdater} for creating new SecureStore.
   * @param initialDelay Delay before the first call to update method.
   * @param delay Delay between completion of one update call to the next one.
   * @param unit time unit for the initialDelay and delay.
   * @return A {@link Cancellable} for cancelling the scheduled update.
   */
  Cancellable scheduleSecureStoreUpdate(final SecureStoreUpdater updater,
                                        long initialDelay, long delay, TimeUnit unit);
}
