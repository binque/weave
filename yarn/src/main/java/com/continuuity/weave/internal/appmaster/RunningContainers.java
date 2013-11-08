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
package com.continuuity.weave.internal.appmaster;

import com.continuuity.weave.api.ResourceReport;
import com.continuuity.weave.api.RunId;
import com.continuuity.weave.api.ServiceController;
import com.continuuity.weave.api.WeaveRunResources;
import com.continuuity.weave.internal.ContainerInfo;
import com.continuuity.weave.internal.DefaultResourceReport;
import com.continuuity.weave.internal.DefaultWeaveRunResources;
import com.continuuity.weave.internal.RunIds;
import com.continuuity.weave.internal.WeaveContainerController;
import com.continuuity.weave.internal.WeaveContainerLauncher;
import com.continuuity.weave.internal.container.WeaveContainerMain;
import com.continuuity.weave.internal.state.Message;
import com.continuuity.weave.internal.yarn.YarnContainerStatus;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Table;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A helper class for ApplicationMasterService to keep track of running containers and to interact
 * with them.
 */
final class RunningContainers {
  private static final Logger LOG = LoggerFactory.getLogger(RunningContainers.class);

  /**
   * Function to return cardinality of a given BitSet.
   */
  private static final Function<BitSet, Integer> BITSET_CARDINALITY = new Function<BitSet, Integer>() {
    @Override
    public Integer apply(BitSet input) {
      return input.cardinality();
    }
  };

  // Table of <runnableName, containerId, controller>
  private final Table<String, String, WeaveContainerController> containers;

  // Map from runnableName to a BitSet, with the <instanceId> bit turned on for having an instance running.
  private final Map<String, BitSet> runnableInstances;
  private final DefaultResourceReport resourceReport;
  private final Deque<String> startSequence;
  private final Lock containerLock;
  private final Condition containerChange;

  RunningContainers(String appId, WeaveRunResources appMasterResources) {
    containers = HashBasedTable.create();
    runnableInstances = Maps.newHashMap();
    startSequence = Lists.newLinkedList();
    containerLock = new ReentrantLock();
    containerChange = containerLock.newCondition();
    resourceReport = new DefaultResourceReport(appId, appMasterResources);
  }

  /**
   * Returns {@code true} if there is no live container.
   */
  boolean isEmpty() {
    containerLock.lock();
    try {
      return runnableInstances.isEmpty();
    } finally {
      containerLock.unlock();
    }
  }

  void start(String runnableName, ContainerInfo containerInfo, WeaveContainerLauncher launcher) {
    containerLock.lock();
    try {
      int instanceId = getStartInstanceId(runnableName);
      RunId runId = getRunId(runnableName, instanceId);
      WeaveContainerController controller = launcher.start(runId, instanceId,
                                                           WeaveContainerMain.class, "$HADOOP_CONF_DIR");
      containers.put(runnableName, containerInfo.getId(), controller);

      WeaveRunResources resources = new DefaultWeaveRunResources(instanceId,
                                                                 containerInfo.getId(),
                                                                 containerInfo.getVirtualCores(),
                                                                 containerInfo.getMemoryMB(),
                                                                 containerInfo.getHost().getHostName());
      resourceReport.addRunResources(runnableName, resources);

      if (startSequence.isEmpty() || !runnableName.equals(startSequence.peekLast())) {
        startSequence.addLast(runnableName);
      }
      containerChange.signalAll();

    } finally {
      containerLock.unlock();
    }
  }

  ResourceReport getResourceReport() {
    return resourceReport;
  }

  /**
   * Stops and removes the last running container of the given runnable.
   */
  void removeLast(String runnableName) {
    containerLock.lock();
    try {
      int maxInstanceId = getMaxInstanceId(runnableName);
      if (maxInstanceId < 0) {
        LOG.warn("No running container found for {}", runnableName);
        return;
      }

      String lastContainerId = null;
      WeaveContainerController lastController = null;

      // Find the controller with the maxInstanceId
      for (Map.Entry<String, WeaveContainerController> entry : containers.row(runnableName).entrySet()) {
        if (getInstanceId(entry.getValue().getRunId()) == maxInstanceId) {
          lastContainerId = entry.getKey();
          lastController = entry.getValue();
          break;
        }
      }

      Preconditions.checkState(lastContainerId != null,
                               "No container found for {} with instanceId = {}", runnableName, maxInstanceId);

      LOG.info("Stopping service: {} {}", runnableName, lastController.getRunId());
      lastController.stopAndWait();
      containers.remove(runnableName, lastContainerId);
      removeInstanceId(runnableName, maxInstanceId);
      resourceReport.removeRunnableResources(runnableName, lastContainerId);
      containerChange.signalAll();
    } finally {
      containerLock.unlock();
    }
  }

  /**
   * Blocks until there are changes in running containers.
   */
  void waitForCount(String runnableName, int count) throws InterruptedException {
    containerLock.lock();
    try {
      while (getRunningInstances(runnableName) != count) {
        containerChange.await();
      }
    } finally {
      containerLock.unlock();
    }
  }

  /**
   * Returns the number of running instances of the given runnable.
   */
  int count(String runnableName) {
    containerLock.lock();
    try {
      return getRunningInstances(runnableName);
    } finally {
      containerLock.unlock();
    }
  }

  /**
   * Returns a Map contains running instances of all runnables.
   */
  Map<String, Integer> countAll() {
    containerLock.lock();
    try {
      return ImmutableMap.copyOf(Maps.transformValues(runnableInstances, BITSET_CARDINALITY));
    } finally {
      containerLock.unlock();
    }
  }

  void sendToAll(Message message, Runnable completion) {
    containerLock.lock();
    try {
      if (containers.isEmpty()) {
        completion.run();
      }

      // Sends the command to all running containers
      AtomicInteger count = new AtomicInteger(containers.size());
      for (Map.Entry<String, Map<String, WeaveContainerController>> entry : containers.rowMap().entrySet()) {
        for (WeaveContainerController controller : entry.getValue().values()) {
          sendMessage(entry.getKey(), message, controller, count, completion);
        }
      }
    } finally {
      containerLock.unlock();
    }
  }

  void sendToRunnable(String runnableName, Message message, Runnable completion) {
    containerLock.lock();
    try {
      Collection<WeaveContainerController> controllers = containers.row(runnableName).values();
      if (controllers.isEmpty()) {
        completion.run();
      }

      AtomicInteger count = new AtomicInteger(controllers.size());
      for (WeaveContainerController controller : controllers) {
        sendMessage(runnableName, message, controller, count, completion);
      }
    } finally {
      containerLock.unlock();
    }
  }

  /**
   * Stops all running services. Only called when the AppMaster stops.
   */
  void stopAll() {
    containerLock.lock();
    try {
      // Stop it one by one in reverse order of start sequence
      Iterator<String> itor = startSequence.descendingIterator();
      List<ListenableFuture<ServiceController.State>> futures = Lists.newLinkedList();
      while (itor.hasNext()) {
        String runnableName = itor.next();
        LOG.info("Stopping all instances of " + runnableName);

        futures.clear();
        // Parallel stops all running containers of the current runnable.
        for (WeaveContainerController controller : containers.row(runnableName).values()) {
          futures.add(controller.stop());
        }
        // Wait for containers to stop. Assumes the future returned by Futures.successfulAsList won't throw exception.
        Futures.getUnchecked(Futures.successfulAsList(futures));

        LOG.info("Terminated all instances of " + runnableName);
      }
      containers.clear();
      runnableInstances.clear();
    } finally {
      containerLock.unlock();
    }
  }

  Set<String> getContainerIds() {
    containerLock.lock();
    try {
      return ImmutableSet.copyOf(containers.columnKeySet());
    } finally {
      containerLock.unlock();
    }
  }

  /**
   * Handle completion of container.
   * @param status The completion status.
   * @param restartRunnables Set of runnable names that requires restart.
   */
  void handleCompleted(YarnContainerStatus status, Multiset<String> restartRunnables) {
    containerLock.lock();
    String containerId = status.getContainerId();
    int exitStatus = status.getExitStatus();
    ContainerState state = status.getState();

    try {
      Map<String, WeaveContainerController> lookup = containers.column(containerId);
      if (lookup.isEmpty()) {
        // It's OK because if a container is stopped through removeLast, this would be empty.
        return;
      }

      if (lookup.size() != 1) {
        LOG.warn("More than one controller found for container {}", containerId);
      }

      if (exitStatus != 0) {
        LOG.warn("Container {} exited abnormally with state {}, exit code {}. Re-request the container.",
                 containerId, state, exitStatus);
        restartRunnables.add(lookup.keySet().iterator().next());
      } else {
        LOG.info("Container {} exited normally with state {}", containerId, state);
      }

      for (Map.Entry<String, WeaveContainerController> completedEntry : lookup.entrySet()) {
        String runnableName = completedEntry.getKey();
        WeaveContainerController controller = completedEntry.getValue();
        controller.completed(exitStatus);

        removeInstanceId(runnableName, getInstanceId(controller.getRunId()));
        resourceReport.removeRunnableResources(runnableName, containerId);
      }

      lookup.clear();
      containerChange.signalAll();
    } finally {
      containerLock.unlock();
    }
  }

  /**
   * Sends a command through the given {@link com.continuuity.weave.internal.WeaveContainerController} of a runnable. Decrements the count
   * when the sending of command completed. Triggers completion when count reaches zero.
   */
  private void sendMessage(final String runnableName, final Message message,
                           final WeaveContainerController controller, final AtomicInteger count,
                           final Runnable completion) {
    Futures.addCallback(controller.sendMessage(message), new FutureCallback<Message>() {
      @Override
      public void onSuccess(Message result) {
        if (count.decrementAndGet() == 0) {
          completion.run();
        }
      }

      @Override
      public void onFailure(Throwable t) {
        try {
          LOG.error("Failed to send message. Runnable: {}, RunId: {}, Message: {}.",
                    runnableName, controller.getRunId(), message, t);
        } finally {
          if (count.decrementAndGet() == 0) {
            completion.run();
          }
        }
      }
    });
  }

  /**
   * Returns the instanceId to start the given runnable.
   */
  private int getStartInstanceId(String runnableName) {
    BitSet instances = runnableInstances.get(runnableName);
    if (instances == null) {
      instances = new BitSet();
      runnableInstances.put(runnableName, instances);
    }
    int instanceId = instances.nextClearBit(0);
    instances.set(instanceId);
    return instanceId;
  }

  private void removeInstanceId(String runnableName, int instanceId) {
    BitSet instances = runnableInstances.get(runnableName);
    if (instances == null) {
      return;
    }
    instances.clear(instanceId);
    if (instances.isEmpty()) {
      runnableInstances.remove(runnableName);
    }
  }

  /**
   * Returns the largest instanceId for the given runnable. Returns -1 if no container is running.
   */
  private int getMaxInstanceId(String runnableName) {
    BitSet instances = runnableInstances.get(runnableName);
    if (instances == null || instances.isEmpty()) {
      return -1;
    }
    return instances.length() - 1;
  }

  /**
   * Returns nnumber of running instances for the given runnable.
   */
  private int getRunningInstances(String runableName) {
    BitSet instances = runnableInstances.get(runableName);
    return instances == null ? 0 : instances.cardinality();
  }

  private RunId getRunId(String runnableName, int instanceId) {
    RunId baseId;

    Collection<WeaveContainerController> controllers = containers.row(runnableName).values();
    if (controllers.isEmpty()) {
      baseId = RunIds.generate();
    } else {
      String id = controllers.iterator().next().getRunId().getId();
      baseId = RunIds.fromString(id.substring(0, id.lastIndexOf('-')));
    }

    return RunIds.fromString(baseId.getId() + '-' + instanceId);
  }

  private int getInstanceId(RunId runId) {
    String id = runId.getId();
    return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
  }
}
