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

package io.temporal.internal.metrics;

/** TODO(maxim): Add gRPC interceptor for metrics */
public class ServiceMethod {
  public static final String DEPRECATE_NAMESPACE =
      MetricsType.TEMPORAL_METRICS_PREFIX + "DeprecateNamespace";
  public static final String DESCRIBE_NAMESPACE =
      MetricsType.TEMPORAL_METRICS_PREFIX + "DescribeNamespace";
  public static final String LIST_NAMESPACES =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ListNamespaces";
  public static final String GET_WORKFLOW_EXECUTION_HISTORY =
      MetricsType.TEMPORAL_METRICS_PREFIX + "GetWorkflowExecutionHistory";
  public static final String LIST_CLOSED_WORKFLOW_EXECUTIONS =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ListClosedWorkflowExecutions";
  public static final String LIST_OPEN_WORKFLOW_EXECUTIONS =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ListOpenWorkflowExecutions";
  public static final String LIST_WORKFLOW_EXECUTIONS =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ListWorkflowExecutions";
  public static final String LIST_ARCHIVED_WORKFLOW_EXECUTIONS =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ListArchivedWorkflowExecutions";
  public static final String LIST_TASK_LIST_PARTITIONS =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ListTaskListPartitions";
  public static final String SCAN_WORKFLOW_EXECUTIONS =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ScanWorkflowExecutions";
  public static final String COUNT_WORKFLOW_EXECUTIONS =
      MetricsType.TEMPORAL_METRICS_PREFIX + "CountWorkflowExecutions";
  public static final String GET_SEARCH_ATTRIBUTES =
      MetricsType.TEMPORAL_METRICS_PREFIX + "GetSearchAttributes";
  public static final String POLL_FOR_ACTIVITY_TASK =
      MetricsType.TEMPORAL_METRICS_PREFIX + "PollForActivityTask";
  public static final String POLL_FOR_DECISION_TASK =
      MetricsType.TEMPORAL_METRICS_PREFIX + "PollForDecisionTask";
  public static final String RECORD_ACTIVITY_TASK_HEARTBEAT =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RecordActivityTaskHeartbeat";
  public static final String RECORD_ACTIVITY_TASK_HEARTBEAT_BY_ID =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RecordActivityTaskHeartbeatById";
  public static final String REGISTER_NAMESPACE =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RegisterNamespace";
  public static final String REQUEST_CANCEL_WORKFLOW_EXECUTION =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RequestCancelWorkflowExecution";
  public static final String RESPOND_ACTIVITY_TASK_CANCELED =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondActivityTaskCanceled";
  public static final String RESPOND_ACTIVITY_TASK_COMPLETED =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondActivityTaskCompleted";
  public static final String RESPOND_ACTIVITY_TASK_FAILED =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondActivityTaskFailed";
  public static final String RESPOND_ACTIVITY_TASK_CANCELED_BY_ID =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondActivityTaskCanceledById";
  public static final String RESPOND_ACTIVITY_TASK_COMPLETED_BY_ID =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondActivityTaskCompletedById";
  public static final String RESPOND_ACTIVITY_TASK_FAILED_BY_ID =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondActivityTaskFailedById";
  public static final String RESPOND_DECISION_TASK_COMPLETED =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondDecisionTaskCompleted";
  public static final String RESPOND_DECISION_TASK_FAILED =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondDecisionTaskFailed";
  public static final String SIGNAL_WORKFLOW_EXECUTION =
      MetricsType.TEMPORAL_METRICS_PREFIX + "SignalWorkflowExecution";
  public static final String SIGNAL_WITH_START_WORKFLOW_EXECUTION =
      MetricsType.TEMPORAL_METRICS_PREFIX + "SignalWithStartWorkflowExecution";
  public static final String START_WORKFLOW_EXECUTION =
      MetricsType.TEMPORAL_METRICS_PREFIX + "StartWorkflowExecution";
  public static final String TERMINATE_WORKFLOW_EXECUTION =
      MetricsType.TEMPORAL_METRICS_PREFIX + "TerminateWorkflowExecution";
  public static final String UPDATE_NAMESPACE =
      MetricsType.TEMPORAL_METRICS_PREFIX + "UpdateNamespace";
  public static final String QUERY_WORKFLOW = MetricsType.TEMPORAL_METRICS_PREFIX + "QueryWorkflow";
  public static final String DESCRIBE_TASK_LIST =
      MetricsType.TEMPORAL_METRICS_PREFIX + "DescribeTaskList";
  public static final String GET_CLUSTER_INFO =
      MetricsType.TEMPORAL_METRICS_PREFIX + "GetClusterInfo";
  public static final String RESPOND_QUERY_TASK_COMPLETED =
      MetricsType.TEMPORAL_METRICS_PREFIX + "RespondQueryTaskCompleted";
  public static final String DESCRIBE_WORKFLOW_EXECUTION =
      MetricsType.TEMPORAL_METRICS_PREFIX + "DescribeWorkflowExecution";
  public static final String RESET_STICKY_TASK_LIST =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ResetStickyTaskList";
  public static final String RESET_WORKFLOW_EXECUTION =
      MetricsType.TEMPORAL_METRICS_PREFIX + "ResetWorkflowExecution";
}
