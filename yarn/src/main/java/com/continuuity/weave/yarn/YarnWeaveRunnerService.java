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
package com.continuuity.weave.yarn;

import com.continuuity.weave.api.ResourceSpecification;
import com.continuuity.weave.api.RunId;
import com.continuuity.weave.api.SecureStore;
import com.continuuity.weave.api.WeaveApplication;
import com.continuuity.weave.api.WeaveController;
import com.continuuity.weave.api.WeavePreparer;
import com.continuuity.weave.api.WeaveRunnable;
import com.continuuity.weave.api.WeaveRunnerService;
import com.continuuity.weave.api.WeaveSpecification;
import com.continuuity.weave.api.logging.LogHandler;
import com.continuuity.weave.common.Cancellable;
import com.continuuity.weave.common.ServiceListenerAdapter;
import com.continuuity.weave.common.Threads;
import com.continuuity.weave.filesystem.HDFSLocationFactory;
import com.continuuity.weave.filesystem.Location;
import com.continuuity.weave.filesystem.LocationFactory;
import com.continuuity.weave.internal.Constants;
import com.continuuity.weave.internal.ProcessController;
import com.continuuity.weave.internal.RunIds;
import com.continuuity.weave.internal.SingleRunnableApplication;
import com.continuuity.weave.internal.appmaster.ApplicationMasterLiveNodeData;
import com.continuuity.weave.internal.yarn.VersionDetectYarnAppClientFactory;
import com.continuuity.weave.internal.yarn.YarnAppClient;
import com.continuuity.weave.internal.yarn.YarnApplicationReport;
import com.continuuity.weave.yarn.utils.YarnUtils;
import com.continuuity.weave.zookeeper.NodeChildren;
import com.continuuity.weave.zookeeper.NodeData;
import com.continuuity.weave.zookeeper.RetryStrategies;
import com.continuuity.weave.zookeeper.ZKClient;
import com.continuuity.weave.zookeeper.ZKClientService;
import com.continuuity.weave.zookeeper.ZKClientServices;
import com.continuuity.weave.zookeeper.ZKClients;
import com.continuuity.weave.zookeeper.ZKOperations;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.Callables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An implementation of {@link WeaveRunnerService} that runs application on a YARN cluster.
 */
public final class YarnWeaveRunnerService extends AbstractIdleService implements WeaveRunnerService {

  private static final Logger LOG = LoggerFactory.getLogger(YarnWeaveRunnerService.class);

  private static final int ZK_TIMEOUT = 10000;
  private static final Function<String, RunId> STRING_TO_RUN_ID = new Function<String, RunId>() {
    @Override
    public RunId apply(String input) {
      return RunIds.fromString(input);
    }
  };

  private final YarnConfiguration yarnConfig;
  private final YarnAppClient yarnAppClient;
  private final ZKClientService zkClientService;
  private final LocationFactory locationFactory;
  private final Table<String, RunId, WeaveController> controllers;
  private final ScheduledExecutorService secureStoreScheduler;

  private Iterable<LiveInfo> liveInfos;
  private Cancellable watchCancellable;
  private volatile String jvmOptions = "";

  public YarnWeaveRunnerService(YarnConfiguration config, String zkConnect) {
    this(config, zkConnect, new HDFSLocationFactory(getFileSystem(config), "/weave"));
  }

  public YarnWeaveRunnerService(YarnConfiguration config, String zkConnect, LocationFactory locationFactory) {
    this.yarnConfig = config;
    this.yarnAppClient = new VersionDetectYarnAppClientFactory().create(config);
    this.locationFactory = locationFactory;
    this.zkClientService = getZKClientService(zkConnect);
    this.controllers = HashBasedTable.create();
    this.secureStoreScheduler =
      new ScheduledThreadPoolExecutor(0, Threads.createDaemonThreadFactory("secure-store-updater-%d"));
  }

  /**
   * This methods sets the extra JVM options that will be passed to the java command line for every application
   * started through this {@link YarnWeaveRunnerService} instance. It only affects applications that are started
   * after options is set.
   *
   * This is intended for advance usage. All options will be passed unchanged to the java command line. Invalid
   * options could cause application not able to start.
   *
   * @param options extra JVM options.
   */
  public void setJVMOptions(String options) {
    Preconditions.checkArgument(options != null, "JVM options cannot be null.");
    this.jvmOptions = options;
  }

  /**
   * Schedules a periodic update of SecureStore. The first call to the given {@link SecureStoreUpdater} will be made
   * after {@code initialDelay}, and subsequently with the given {@code delay} between completion of one update
   * and starting of the next. If exception is thrown on call
   * {@link SecureStoreUpdater#update(String, com.continuuity.weave.api.RunId)}, the exception will only get logged
   * and won't suppress the next update call.
   *
   * @param updater A {@link SecureStoreUpdater} for creating new SecureStore.
   * @param initialDelay Delay before the first call to update method.
   * @param delay Delay between completion of one update call to the next one.
   * @param unit time unit for the initialDelay and delay.
   * @return A {@link Cancellable} for cancelling the scheduled update.
   */
  public Cancellable scheduleSecureStoreUpdate(final SecureStoreUpdater updater,
                                               long initialDelay, long delay, TimeUnit unit) {
    final ScheduledFuture<?> future = secureStoreScheduler.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        // Collects all <application, runId> pairs first
        Multimap<String, RunId> liveApps = HashMultimap.create();
        synchronized (YarnWeaveRunnerService.this) {
          for (Table.Cell<String, RunId, WeaveController> cell : controllers.cellSet()) {
            liveApps.put(cell.getRowKey(), cell.getColumnKey());
          }
        }

        // Collect all secure stores that needs to be updated.
        Table<String, RunId, SecureStore> secureStores = HashBasedTable.create();
        for (Map.Entry<String, RunId> entry : liveApps.entries()) {
          try {
            secureStores.put(entry.getKey(), entry.getValue(), updater.update(entry.getKey(), entry.getValue()));
          } catch (Throwable t) {
            LOG.warn("Exception thrown by SecureStoreUpdater {}", updater, t);
          }
        }

        // Update secure stores.
        updateSecureStores(secureStores);
      }
    }, initialDelay, delay, unit);

    return new Cancellable() {
      @Override
      public void cancel() {
        future.cancel(false);
      }
    };
  }

  @Override
  public WeavePreparer prepare(WeaveRunnable runnable) {
    return prepare(runnable, ResourceSpecification.BASIC);
  }

  @Override
  public WeavePreparer prepare(WeaveRunnable runnable, ResourceSpecification resourceSpecification) {
    return prepare(new SingleRunnableApplication(runnable, resourceSpecification));
  }

  @Override
  public WeavePreparer prepare(WeaveApplication application) {
    Preconditions.checkState(isRunning(), "Service not start. Please call start() first.");
    final WeaveSpecification weaveSpec = application.configure();
    final String appName = weaveSpec.getName();

    return new YarnWeavePreparer(yarnConfig, weaveSpec, yarnAppClient, zkClientService, locationFactory,
                                 Suppliers.ofInstance(jvmOptions),
                                 new YarnWeaveControllerFactory() {
      @Override
      public YarnWeaveController create(RunId runId, Iterable<LogHandler> logHandlers,
                                        Callable<ProcessController<YarnApplicationReport>> startUp) {
        ZKClient zkClient = ZKClients.namespace(zkClientService, "/" + appName);
        YarnWeaveController controller = listenController(new YarnWeaveController(runId, zkClient,
                                                                                  logHandlers, startUp));
        synchronized (YarnWeaveRunnerService.this) {
          Preconditions.checkArgument(!controllers.contains(appName, runId),
                                      "Application %s with runId %s is already running.", appName, runId);
          controllers.put(appName, runId, controller);
        }
        return controller;
      }
    });
  }

  @Override
  public synchronized WeaveController lookup(String applicationName, final RunId runId) {
    return controllers.get(applicationName, runId);
  }

  @Override
  public Iterable<WeaveController> lookup(final String applicationName) {
    return new Iterable<WeaveController>() {
      @Override
      public Iterator<WeaveController> iterator() {
        synchronized (YarnWeaveRunnerService.this) {
          return ImmutableList.copyOf(controllers.row(applicationName).values()).iterator();
        }
      }
    };
  }

  @Override
  public Iterable<LiveInfo> lookupLive() {
    return liveInfos;
  }

  @Override
  protected void startUp() throws Exception {
    yarnAppClient.startAndWait();
    zkClientService.startAndWait();

    // Create the root node, so that the namespace root would get created if it is missing
    // If the exception is caused by node exists, then it's ok. Otherwise propagate the exception.
    ZKOperations.ignoreError(zkClientService.create("/", null, CreateMode.PERSISTENT),
                             KeeperException.NodeExistsException.class, null).get();

    watchCancellable = watchLiveApps();
    liveInfos = createLiveInfos();

    // Schedule an updater for updating HDFS delegation tokens
    if (UserGroupInformation.isSecurityEnabled()) {
      long delay = yarnConfig.getLong(DFSConfigKeys.DFS_NAMENODE_DELEGATION_TOKEN_RENEW_INTERVAL_KEY,
                                      DFSConfigKeys.DFS_NAMENODE_DELEGATION_TOKEN_RENEW_INTERVAL_DEFAULT);
      scheduleSecureStoreUpdate(new LocationSecureStoreUpdater(yarnConfig, locationFactory),
                                delay, delay, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  protected void shutDown() throws Exception {
    // Shutdown shouldn't stop any controllers, as stopping this client service should let the remote containers
    // running. However, this assumes that this WeaveRunnerService is a long running service and you only stop it
    // when the JVM process is about to exit. Hence it is important that threads created in the controllers are
    // daemon threads.
    watchCancellable.cancel();
    zkClientService.stopAndWait();
    yarnAppClient.stopAndWait();
  }

  private Cancellable watchLiveApps() {
    final Map<String, Cancellable> watched = Maps.newConcurrentMap();

    final AtomicBoolean cancelled = new AtomicBoolean(false);
    // Watch child changes in the root, which gives all application names.
    final Cancellable cancellable = ZKOperations.watchChildren(zkClientService, "/",
                                                               new ZKOperations.ChildrenCallback() {
      @Override
      public void updated(NodeChildren nodeChildren) {
        if (cancelled.get()) {
          return;
        }

        Set<String> apps = ImmutableSet.copyOf(nodeChildren.getChildren());

        // For each for the application name, watch for ephemeral nodes under /instances.
        for (final String appName : apps) {
          if (watched.containsKey(appName)) {
            continue;
          }

          final String instancePath = String.format("/%s/instances", appName);
          watched.put(appName,
                      ZKOperations.watchChildren(zkClientService, instancePath, new ZKOperations.ChildrenCallback() {
            @Override
            public void updated(NodeChildren nodeChildren) {
              if (cancelled.get()) {
                return;
              }
              if (nodeChildren.getChildren().isEmpty()) {     // No more child, means no live instances
                Cancellable removed = watched.remove(appName);
                if (removed != null) {
                  removed.cancel();
                }
                return;
              }
              synchronized (YarnWeaveRunnerService.this) {
                // For each of the children, which the node name is the runId,
                // fetch the application Id and construct WeaveController.
                for (final RunId runId : Iterables.transform(nodeChildren.getChildren(), STRING_TO_RUN_ID)) {
                  if (controllers.contains(appName, runId)) {
                    continue;
                  }
                  updateController(appName, runId, cancelled);
                }
              }
            }
          }));
        }

        // Remove app watches for apps that are gone. Removal of controller from controllers table is done
        // in the state listener attached to the weave controller.
        for (String removeApp : Sets.difference(watched.keySet(), apps)) {
          watched.remove(removeApp).cancel();
        }
      }
    });
    return new Cancellable() {
      @Override
      public void cancel() {
        cancelled.set(true);
        cancellable.cancel();
        for (Cancellable c : watched.values()) {
          c.cancel();
        }
      }
    };
  }

  private YarnWeaveController listenController(final YarnWeaveController controller) {
    controller.addListener(new ServiceListenerAdapter() {
      @Override
      public void terminated(State from) {
        removeController();
      }

      @Override
      public void failed(State from, Throwable failure) {
        removeController();
      }

      private void removeController() {
        synchronized (YarnWeaveRunnerService.this) {
          Iterables.removeIf(controllers.values(),
                             new Predicate<WeaveController>() {
             @Override
             public boolean apply(WeaveController input) {
               return input == controller;
             }
           });
        }
      }
    }, Threads.SAME_THREAD_EXECUTOR);
    return controller;
  }

  private ZKClientService getZKClientService(String zkConnect) {
    return ZKClientServices.delegate(
      ZKClients.reWatchOnExpire(
        ZKClients.retryOnFailure(ZKClientService.Builder.of(zkConnect)
                                   .setSessionTimeout(ZK_TIMEOUT)
                                   .build(), RetryStrategies.exponentialDelay(100, 2000, TimeUnit.MILLISECONDS))));
  }

  private Iterable<LiveInfo> createLiveInfos() {
    return new Iterable<LiveInfo>() {

      @Override
      public Iterator<LiveInfo> iterator() {
        Map<String, Map<RunId, WeaveController>> controllerMap = ImmutableTable.copyOf(controllers).rowMap();
        return Iterators.transform(controllerMap.entrySet().iterator(),
                                   new Function<Map.Entry<String, Map<RunId, WeaveController>>, LiveInfo>() {
          @Override
          public LiveInfo apply(final Map.Entry<String, Map<RunId, WeaveController>> entry) {
            return new LiveInfo() {
              @Override
              public String getApplicationName() {
                return entry.getKey();
              }

              @Override
              public Iterable<WeaveController> getControllers() {
                return entry.getValue().values();
              }
            };
          }
        });
      }
    };
  }

  private void updateController(final String appName, final RunId runId, final AtomicBoolean cancelled) {
    String instancePath = String.format("/%s/instances/%s", appName, runId.getId());

    // Fetch the content node.
    Futures.addCallback(zkClientService.getData(instancePath), new FutureCallback<NodeData>() {
      @Override
      public void onSuccess(NodeData result) {
        if (cancelled.get()) {
          return;
        }
        ApplicationId appId = getApplicationId(result);
        if (appId == null) {
          return;
        }

        synchronized (YarnWeaveRunnerService.this) {
          if (!controllers.contains(appName, runId)) {
            ZKClient zkClient = ZKClients.namespace(zkClientService, "/" + appName);
            YarnWeaveController controller = listenController(
              new YarnWeaveController(runId, zkClient,
                                      Callables.returning(yarnAppClient.createProcessController(appId))));
            controllers.put(appName, runId, controller);
            controller.start();
          }
        }
      }

      @Override
      public void onFailure(Throwable t) {
        LOG.warn("Failed in fetching application instance node.", t);
      }
    }, Threads.SAME_THREAD_EXECUTOR);
  }


  /**
   * Decodes application ID stored inside the node data.
   * @param nodeData The node data to decode from. If it is {@code null}, this method would return {@code null}.
   * @return The ApplicationId or {@code null} if failed to decode.
   */
  private ApplicationId getApplicationId(NodeData nodeData) {
    byte[] data = nodeData == null ? null : nodeData.getData();
    if (data == null) {
      return null;
    }

    Gson gson = new Gson();
    JsonElement json = gson.fromJson(new String(data, Charsets.UTF_8), JsonElement.class);
    if (!json.isJsonObject()) {
      LOG.warn("Unable to decode live data node.");
      return null;
    }

    JsonObject jsonObj = json.getAsJsonObject();
    json = jsonObj.get("data");
    if (!json.isJsonObject()) {
      LOG.warn("Property data not found in live data node.");
      return null;
    }

    try {
      ApplicationMasterLiveNodeData amLiveNode = gson.fromJson(json, ApplicationMasterLiveNodeData.class);
      return YarnUtils.createApplicationId(amLiveNode.getAppIdClusterTime(), amLiveNode.getAppId());
    } catch (Exception e) {
      LOG.warn("Failed to decode application live node data.", e);
      return null;
    }
  }

  private void updateSecureStores(Table<String, RunId, SecureStore> secureStores) {
    for (Table.Cell<String, RunId, SecureStore> cell : secureStores.cellSet()) {
      Object store = cell.getValue().getStore();
      if (!(store instanceof Credentials)) {
        LOG.warn("Only Hadoop Credentials is supported. Ignore update for {}.", cell);
        continue;
      }

      Credentials credentials = (Credentials) store;
      if (credentials.getAllTokens().isEmpty()) {
        // Nothing to update.
        continue;
      }

      try {
        updateCredentials(cell.getRowKey(), cell.getColumnKey(), credentials);
      } catch (Throwable t) {
        LOG.warn("Failed to update secure store for {}.", cell);
      }
    }
  }

  private void updateCredentials(String application, RunId runId, Credentials updates) throws IOException {
    Location credentialsLocation = locationFactory.create(String.format("/%s/%s/%s", application, runId.getId(),
                                                                        Constants.Files.CREDENTIALS));
    // Try to read the old credentials.
    Credentials credentials = new Credentials();
    if (credentialsLocation.exists()) {
      DataInputStream is = new DataInputStream(new BufferedInputStream(credentialsLocation.getInputStream()));
      try {
        credentials.readTokenStorageStream(is);
      } finally {
        is.close();
      }
    }

    // Overwrite with the updates.
    credentials.addAll(updates);

    // Overwrite the credentials.
    Location tmpLocation = credentialsLocation.getTempFile(Constants.Files.CREDENTIALS);
    DataOutputStream os = new DataOutputStream(new BufferedOutputStream(tmpLocation.getOutputStream()));
    try {
      credentials.writeTokenStorageToStream(os);
    } finally {
      os.close();
    }

    // Rename the tmp file into the credentials location
    tmpLocation.renameTo(credentialsLocation);
  }

  private static FileSystem getFileSystem(YarnConfiguration configuration) {
    try {
      return FileSystem.get(configuration);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }
}
