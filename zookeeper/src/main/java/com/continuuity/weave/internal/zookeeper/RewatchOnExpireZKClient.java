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
package com.continuuity.weave.internal.zookeeper;

import com.continuuity.weave.internal.zookeeper.RewatchOnExpireWatcher.ActionType;
import com.continuuity.weave.zookeeper.ForwardingZKClient;
import com.continuuity.weave.zookeeper.NodeChildren;
import com.continuuity.weave.zookeeper.NodeData;
import com.continuuity.weave.zookeeper.OperationFuture;
import com.continuuity.weave.zookeeper.ZKClient;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;

/**
 * A {@link ZKClient} that will rewatch automatically when session expired and reconnect.
 * The rewatch logic is mainly done in {@link RewatchOnExpireWatcher}.
 */
public final class RewatchOnExpireZKClient extends ForwardingZKClient {

  public RewatchOnExpireZKClient(ZKClient delegate) {
    super(delegate);
  }

  @Override
  public OperationFuture<Stat> exists(String path, Watcher watcher) {
    if (watcher == null) {
      return super.exists(path, watcher);
    }
    final RewatchOnExpireWatcher wrappedWatcher = new RewatchOnExpireWatcher(this, ActionType.EXISTS, path, watcher);
    OperationFuture<Stat> result = super.exists(path, wrappedWatcher);
    Futures.addCallback(result, new FutureCallback<Stat>() {
      @Override
      public void onSuccess(Stat result) {
        wrappedWatcher.setLastResult(result);
      }

      @Override
      public void onFailure(Throwable t) {
        // No-op
      }
    });
    return result;
  }

  @Override
  public OperationFuture<NodeChildren> getChildren(String path, Watcher watcher) {
    if (watcher == null) {
      return super.getChildren(path, watcher);
    }
    final RewatchOnExpireWatcher wrappedWatcher = new RewatchOnExpireWatcher(this, ActionType.CHILDREN, path, watcher);
    OperationFuture<NodeChildren> result = super.getChildren(path, wrappedWatcher);
    Futures.addCallback(result, new FutureCallback<NodeChildren>() {
      @Override
      public void onSuccess(NodeChildren result) {
        wrappedWatcher.setLastResult(result);
      }

      @Override
      public void onFailure(Throwable t) {
        // No-op
      }
    });
    return result;
  }

  @Override
  public OperationFuture<NodeData> getData(String path, Watcher watcher) {
    if (watcher == null) {
      return super.getData(path, watcher);
    }
    final RewatchOnExpireWatcher wrappedWatcher = new RewatchOnExpireWatcher(this, ActionType.DATA, path, watcher);
    OperationFuture<NodeData> result = super.getData(path, wrappedWatcher);
    Futures.addCallback(result, new FutureCallback<NodeData>() {
      @Override
      public void onSuccess(NodeData result) {
        wrappedWatcher.setLastResult(result);
      }

      @Override
      public void onFailure(Throwable t) {
        // No-op
      }
    });
    return result;

  }
}
