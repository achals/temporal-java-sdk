/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.common;

import com.google.common.base.Defaults;
import com.google.protobuf.ByteString;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GsonJsonDataConverter;
import io.temporal.internal.worker.Shutdownable;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.tasklist.TaskList;
import io.temporal.proto.tasklist.TaskListKind;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Utility functions shared by the implementation code. */
public final class InternalUtils {

  public static TaskList createStickyTaskList(String taskListName) {
    return TaskList.newBuilder().setName(taskListName).setKind(TaskListKind.Sticky).build();
  }

  public static TaskList createNormalTaskList(String taskListName) {
    return TaskList.newBuilder().setName(taskListName).setKind(TaskListKind.Normal).build();
  }

  public static long awaitTermination(Shutdownable s, long timeoutMillis) {
    if (s == null) {
      return timeoutMillis;
    }
    return awaitTermination(
        timeoutMillis,
        () -> {
          s.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        });
  }

  public static long awaitTermination(ExecutorService s, long timeoutMillis) {
    if (s == null) {
      return timeoutMillis;
    }
    return awaitTermination(
        timeoutMillis,
        () -> {
          try {
            s.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
          }
        });
  }

  public static long awaitTermination(long timeoutMillis, Runnable toTerminate) {
    long started = System.currentTimeMillis();
    toTerminate.run();
    long remainingTimeout = timeoutMillis - (System.currentTimeMillis() - started);
    if (remainingTimeout < 0) {
      remainingTimeout = 0;
    }
    return remainingTimeout;
  }

  public static Object getValueOrDefault(Object value, Class<?> valueClass) {
    if (value != null) {
      return value;
    }
    return Defaults.defaultValue(valueClass);
  }

  public static SearchAttributes convertMapToSearchAttributes(
      Map<String, Object> searchAttributes) {
    DataConverter converter = GsonJsonDataConverter.getInstance();
    Map<String, ByteString> mapOfByteBuffer = new HashMap<>();
    searchAttributes.forEach(
        (key, value) -> {
          mapOfByteBuffer.put(key, ByteString.copyFrom(converter.toData(value)));
        });
    return SearchAttributes.newBuilder().putAllIndexedFields(mapOfByteBuffer).build();
  }

  /** Prohibit instantiation */
  private InternalUtils() {}
}
