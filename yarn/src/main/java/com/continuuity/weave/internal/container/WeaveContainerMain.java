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
package com.continuuity.weave.internal.container;

import com.continuuity.weave.api.LocalFile;
import com.continuuity.weave.api.RunId;
import com.continuuity.weave.api.RuntimeSpecification;
import com.continuuity.weave.api.WeaveRunnableSpecification;
import com.continuuity.weave.api.WeaveSpecification;
import com.continuuity.weave.discovery.DiscoveryService;
import com.continuuity.weave.discovery.ZKDiscoveryService;
import com.continuuity.weave.internal.Arguments;
import com.continuuity.weave.internal.BasicWeaveContext;
import com.continuuity.weave.internal.Constants;
import com.continuuity.weave.internal.ContainerInfo;
import com.continuuity.weave.internal.EnvContainerInfo;
import com.continuuity.weave.internal.EnvKeys;
import com.continuuity.weave.internal.RunIds;
import com.continuuity.weave.internal.ServiceMain;
import com.continuuity.weave.internal.json.ArgumentsCodec;
import com.continuuity.weave.internal.json.WeaveSpecificationAdapter;
import com.continuuity.weave.zookeeper.RetryStrategies;
import com.continuuity.weave.zookeeper.ZKClient;
import com.continuuity.weave.zookeeper.ZKClientService;
import com.continuuity.weave.zookeeper.ZKClientServices;
import com.continuuity.weave.zookeeper.ZKClients;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Service;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public final class WeaveContainerMain extends ServiceMain {

  /**
   * Main method for launching a {@link WeaveContainerService} which runs
   * a {@link com.continuuity.weave.api.WeaveRunnable}.
   */
  public static void main(final String[] args) throws Exception {
    String zkConnectStr = System.getenv(EnvKeys.WEAVE_ZK_CONNECT);
    File weaveSpecFile = new File(Constants.Files.WEAVE_SPEC);
    RunId appRunId = RunIds.fromString(System.getenv(EnvKeys.WEAVE_APP_RUN_ID));
    RunId runId = RunIds.fromString(System.getenv(EnvKeys.WEAVE_RUN_ID));
    String runnableName = System.getenv(EnvKeys.WEAVE_RUNNABLE_NAME);
    int instanceId = Integer.parseInt(System.getenv(EnvKeys.WEAVE_INSTANCE_ID));
    int instanceCount = Integer.parseInt(System.getenv(EnvKeys.WEAVE_INSTANCE_COUNT));

    ZKClientService zkClientService = ZKClientServices.delegate(
      ZKClients.reWatchOnExpire(
        ZKClients.retryOnFailure(ZKClientService.Builder.of(zkConnectStr).build(),
                                 RetryStrategies.fixDelay(1, TimeUnit.SECONDS))));

    DiscoveryService discoveryService = new ZKDiscoveryService(zkClientService);

    WeaveSpecification weaveSpec = loadWeaveSpec(weaveSpecFile);
    renameLocalFiles(weaveSpec.getRunnables().get(runnableName));
    
    WeaveRunnableSpecification runnableSpec = weaveSpec.getRunnables().get(runnableName).getRunnableSpecification();
    ContainerInfo containerInfo = new EnvContainerInfo();
    Arguments arguments = decodeArgs();
    BasicWeaveContext context = new BasicWeaveContext(
      runId, appRunId, containerInfo.getHost(),
      arguments.getRunnableArguments().get(runnableName).toArray(new String[0]),
      arguments.getArguments().toArray(new String[0]),
      runnableSpec, instanceId, discoveryService, instanceCount,
      containerInfo.getMemoryMB(), containerInfo.getVirtualCores()
    );

    Configuration conf = new YarnConfiguration(new HdfsConfiguration(new Configuration()));
    Service service = new WeaveContainerService(context, containerInfo,
                                                getContainerZKClient(zkClientService, appRunId, runnableName),
                                                runId, runnableSpec, getClassLoader(),
                                                createAppLocation(conf));
    new WeaveContainerMain().doMain(zkClientService, service);
  }

  private static void renameLocalFiles(RuntimeSpecification runtimeSpec) {
    for (LocalFile file : runtimeSpec.getLocalFiles()) {
      if (file.isArchive()) {
        String path = file.getURI().toString();
        String name = file.getName() + (path.endsWith(".tar.gz") ? ".tar.gz" : path.substring(path.lastIndexOf('.')));
        Preconditions.checkState(new File(name).renameTo(new File(file.getName())),
                                 "Fail to rename file from %s to %s.",
                                 name, file.getName());
      }
    }
  }

  private static ZKClient getContainerZKClient(ZKClient zkClient, RunId appRunId, String runnableName) {
    return ZKClients.namespace(zkClient, String.format("/%s/runnables/%s", appRunId, runnableName));
  }

  /**
   * Returns the ClassLoader for the runnable.
   */
  private static ClassLoader getClassLoader() {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) {
      return ClassLoader.getSystemClassLoader();
    }
    return classLoader;
  }

  private static WeaveSpecification loadWeaveSpec(File specFile) throws IOException {
    Reader reader = Files.newReader(specFile, Charsets.UTF_8);
    try {
      return WeaveSpecificationAdapter.create().fromJson(reader);
    } finally {
      reader.close();
    }
  }

  private static Arguments decodeArgs() throws IOException {
    return ArgumentsCodec.decode(Files.newReaderSupplier(new File(Constants.Files.ARGUMENTS), Charsets.UTF_8));
  }

  @Override
  protected String getHostname() {
    return System.getenv(EnvKeys.YARN_CONTAINER_HOST);
  }

  @Override
  protected String getKafkaZKConnect() {
    return System.getenv(EnvKeys.WEAVE_LOG_KAFKA_ZK);
  }
}
