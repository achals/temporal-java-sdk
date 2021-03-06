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

package io.temporal.internal.replay;

import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.replay.HistoryHelper.DecisionEvents;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.decision.CancelWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.CompleteWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.ContinueAsNewWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.Decision;
import io.temporal.proto.decision.DecisionType;
import io.temporal.proto.decision.FailWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.RecordMarkerDecisionAttributes;
import io.temporal.proto.decision.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.ScheduleActivityTaskDecisionAttributes;
import io.temporal.proto.decision.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.StartTimerDecisionAttributes;
import io.temporal.proto.decision.UpsertWorkflowSearchAttributesDecisionAttributes;
import io.temporal.proto.event.ActivityTaskCancelRequestedEventAttributes;
import io.temporal.proto.event.ActivityTaskCanceledEventAttributes;
import io.temporal.proto.event.ActivityTaskScheduledEventAttributes;
import io.temporal.proto.event.ActivityTaskStartedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.event.EventType;
import io.temporal.proto.event.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.MarkerRecordedEventAttributes;
import io.temporal.proto.event.RequestCancelActivityTaskFailedEventAttributes;
import io.temporal.proto.event.RequestCancelExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.StartChildWorkflowExecutionInitiatedEventAttributes;
import io.temporal.proto.event.TimerCanceledEventAttributes;
import io.temporal.proto.event.TimerFiredEventAttributes;
import io.temporal.proto.event.WorkflowExecutionStartedEventAttributes;
import io.temporal.proto.tasklist.TaskList;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

class DecisionsHelper {

  //  private static final Logger log = LoggerFactory.getLogger(DecisionsHelper.class);

  /**
   * TODO: Update constant once Temporal introduces the limit of decision per completion. Or remove
   * code path if Temporal deals with this problem differently like paginating through decisions.
   */
  private static final int MAXIMUM_DECISIONS_PER_COMPLETION = 10000;

  static final String FORCE_IMMEDIATE_DECISION_TIMER = "FORCE_IMMEDIATE_DECISION";

  private static final String NON_DETERMINISTIC_MESSAGE =
      "The possible causes are a nondeterministic workflow definition code or an incompatible "
          + "change in the workflow definition.";

  private final PollForDecisionTaskResponse.Builder task;

  /**
   * When workflow task completes the decisions are converted to events that follow the decision
   * task completion event. The nextDecisionEventId is the id of an event that corresponds to the
   * next decision to be added.
   */
  private long nextDecisionEventId;

  private long idCounter;

  private DecisionEvents decisionEvents;

  /** Use access-order to ensure that decisions are emitted in order of their creation */
  private final Map<DecisionId, DecisionStateMachine> decisions =
      new LinkedHashMap<>(100, 0.75f, true);

  // TODO: removal of completed activities
  private final Map<String, Long> activityIdToScheduledEventId = new HashMap<>();

  DecisionsHelper(PollForDecisionTaskResponse.Builder task) {
    this.task = task;
  }

  long getNextDecisionEventId() {
    return nextDecisionEventId;
  }

  long scheduleActivityTask(ScheduleActivityTaskDecisionAttributes schedule) {
    addAllMissingVersionMarker(false, Optional.empty());

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.ACTIVITY, nextDecisionEventId);
    activityIdToScheduledEventId.put(schedule.getActivityId(), nextDecisionEventId);
    addDecision(decisionId, new ActivityDecisionStateMachine(decisionId, schedule));
    return nextDecisionEventId;
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  boolean requestCancelActivityTask(long scheduledEventId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, scheduledEventId));
    if (decision.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
    return decision.isDone();
  }

  void handleActivityTaskStarted(HistoryEvent event) {
    ActivityTaskStartedEventAttributes attributes = event.getActivityTaskStartedEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, attributes.getScheduledEventId()));
    decision.handleStartedEvent(event);
  }

  void handleActivityTaskScheduled(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  boolean handleActivityTaskClosed(long scheduledEventId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, scheduledEventId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleActivityTaskCancelRequested(HistoryEvent event) {
    ActivityTaskCancelRequestedEventAttributes attributes =
        event.getActivityTaskCancelRequestedEventAttributes();
    String activityId = attributes.getActivityId();
    long scheduledEventId = getActivityScheduledEventId(activityId);
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, scheduledEventId));
    decision.handleCancellationInitiatedEvent();
    return decision.isDone();
  }

  private long getActivityScheduledEventId(String activityId) {
    Long scheduledEventId = activityIdToScheduledEventId.get(activityId);
    if (scheduledEventId == null) {
      throw new Error("Unknown activityId: " + activityId);
    }
    return scheduledEventId;
  }

  boolean handleActivityTaskCanceled(HistoryEvent event) {
    ActivityTaskCanceledEventAttributes attributes = event.getActivityTaskCanceledEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, attributes.getScheduledEventId()));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  boolean handleRequestCancelActivityTaskFailed(HistoryEvent event) {
    RequestCancelActivityTaskFailedEventAttributes attributes =
        event.getRequestCancelActivityTaskFailedEventAttributes();
    String activityId = attributes.getActivityId();
    long scheduledEventId = getActivityScheduledEventId(activityId);
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, scheduledEventId));
    decision.handleCancellationFailureEvent(event);
    return decision.isDone();
  }

  long startChildWorkflowExecution(StartChildWorkflowExecutionDecisionAttributes childWorkflow) {
    addAllMissingVersionMarker(false, Optional.empty());

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.CHILD_WORKFLOW, nextDecisionEventId);
    addDecision(decisionId, new ChildWorkflowDecisionStateMachine(decisionId, childWorkflow));
    return nextDecisionEventId;
  }

  /**
   * @return true if it is not replay or retryOptions are present in the
   *     StartChildWorkflowExecutionInitiated event.
   */
  boolean isChildWorkflowExecutionInitiatedWithRetryOptions() {
    Optional<HistoryEvent> optionalEvent = getOptionalDecisionEvent(nextDecisionEventId);
    if (!optionalEvent.isPresent()) {
      return true;
    }
    HistoryEvent event = optionalEvent.get();
    if (event.getEventType() != EventType.StartChildWorkflowExecutionInitiated) {
      return false;
    }
    if (!event.hasStartChildWorkflowExecutionInitiatedEventAttributes()) {
      throw new Error("Corrupted event: " + event);
    }
    StartChildWorkflowExecutionInitiatedEventAttributes attr =
        event.getStartChildWorkflowExecutionInitiatedEventAttributes();
    return attr.hasRetryPolicy();
  }

  /**
   * @return true if it is not replay or retryOptions are present in the ActivityTaskScheduled
   *     event. false is only for the legacy code that used client side retry.
   */
  boolean isActivityScheduledWithRetryOptions() {
    Optional<HistoryEvent> optionalEvent = getOptionalDecisionEvent(nextDecisionEventId);
    if (!optionalEvent.isPresent()) {
      return true;
    }
    HistoryEvent event = optionalEvent.get();
    if (event.getEventType() != EventType.ActivityTaskScheduled) {
      return false;
    }
    if (!event.hasActivityTaskScheduledEventAttributes()) {
      throw new Error("Corrupted event: " + event);
    }
    ActivityTaskScheduledEventAttributes attr = event.getActivityTaskScheduledEventAttributes();
    return attr.hasRetryPolicy();
  }

  void handleStartChildWorkflowExecutionInitiated(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.CHILD_WORKFLOW, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  boolean handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
    StartChildWorkflowExecutionFailedEventAttributes attributes =
        event.getStartChildWorkflowExecutionFailedEventAttributes();
    long initiatedEventId = attributes.getInitiatedEventId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.CHILD_WORKFLOW, initiatedEventId));
    decision.handleInitiationFailedEvent(event);
    return decision.isDone();
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  long requestCancelExternalWorkflowExecution(
      RequestCancelExternalWorkflowExecutionDecisionAttributes schedule) {
    addAllMissingVersionMarker(false, Optional.empty());

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId =
        new DecisionId(DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, nextDecisionEventId);
    addDecision(
        decisionId, new ExternalWorkflowCancellationDecisionStateMachine(decisionId, schedule));
    return nextDecisionEventId;
  }

  void handleRequestCancelExternalWorkflowExecutionInitiated(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  void handleExternalWorkflowExecutionCancelRequested(HistoryEvent event) {
    ExternalWorkflowExecutionCancelRequestedEventAttributes attributes =
        event.getExternalWorkflowExecutionCancelRequestedEventAttributes();
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(
                DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
  }

  void handleRequestCancelExternalWorkflowExecutionFailed(HistoryEvent event) {
    RequestCancelExternalWorkflowExecutionFailedEventAttributes attributes =
        event.getRequestCancelExternalWorkflowExecutionFailedEventAttributes();
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(
                DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
  }

  long signalExternalWorkflowExecution(SignalExternalWorkflowExecutionDecisionAttributes signal) {
    addAllMissingVersionMarker(false, Optional.empty());

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId =
        new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, nextDecisionEventId);
    addDecision(decisionId, new SignalDecisionStateMachine(decisionId, signal));
    return nextDecisionEventId;
  }

  void cancelSignalExternalWorkflowExecution(
      long initiatedEventId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    if (decision.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
  }

  boolean handleSignalExternalWorkflowExecutionFailed(long initiatedEventId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleExternalWorkflowExecutionSignaled(long initiatedEventId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  long startTimer(StartTimerDecisionAttributes request) {
    addAllMissingVersionMarker(false, Optional.empty());

    long startEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.TIMER, startEventId);
    addDecision(decisionId, new TimerDecisionStateMachine(decisionId, request));
    return startEventId;
  }

  boolean cancelTimer(long startEventId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, startEventId));
    if (decision.isDone()) {
      // Cancellation callbacks are not deregistered and might be invoked after timer firing
      return true;
    }
    if (decision.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
    return decision.isDone();
  }

  void handleChildWorkflowExecutionStarted(HistoryEvent event) {
    ChildWorkflowExecutionStartedEventAttributes attributes =
        event.getChildWorkflowExecutionStartedEventAttributes();
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleStartedEvent(event);
  }

  boolean handleChildWorkflowExecutionCompleted(
      ChildWorkflowExecutionCompletedEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionTimedOut(
      ChildWorkflowExecutionTimedOutEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionTerminated(
      ChildWorkflowExecutionTerminatedEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionFailed(
      ChildWorkflowExecutionFailedEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionCanceled(
      ChildWorkflowExecutionCanceledEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  void handleSignalExternalWorkflowExecutionInitiated(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  boolean handleTimerClosed(TimerFiredEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getStartedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleTimerCanceled(HistoryEvent event) {
    TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getStartedEventId()));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  boolean handleCancelTimerFailed(HistoryEvent event) {
    long startedEventId = event.getEventId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, startedEventId));
    decision.handleCancellationFailureEvent(event);
    return decision.isDone();
  }

  void handleTimerStarted(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, event.getEventId()));
    // Timer started event is indeed initiation event for the timer as
    // it doesn't have a separate event for started as an activity does.
    decision.handleInitiatedEvent(event);
  }

  /** This happens during strongly consistent query processing for completed workflows */
  public void handleWorkflowExecutionCompleted(HistoryEvent event) {
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    DecisionStateMachine decision = getDecision(decisionId);
    if (!(decision instanceof CompleteWorkflowStateMachine)) {
      throw new IllegalStateException("Unexpected decision: " + decision);
    }
    decisions.clear();
  }

  void completeWorkflowExecution(byte[] output) {
    addAllMissingVersionMarker(false, Optional.empty());

    Decision decision =
        Decision.newBuilder()
            .setCompleteWorkflowExecutionDecisionAttributes(
                CompleteWorkflowExecutionDecisionAttributes.newBuilder()
                    .setResult(OptionsUtils.toByteString(output)))
            .setDecisionType(DecisionType.CompleteWorkflowExecution)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void continueAsNewWorkflowExecution(ContinueAsNewWorkflowExecutionParameters continueParameters) {
    addAllMissingVersionMarker(false, Optional.empty());

    HistoryEvent firstEvent = task.getHistory().getEvents(0);
    if (!firstEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalStateException(
          "The first event is not WorkflowExecutionStarted: " + firstEvent);
    }
    WorkflowExecutionStartedEventAttributes startedEvent =
        firstEvent.getWorkflowExecutionStartedEventAttributes();
    ContinueAsNewWorkflowExecutionDecisionAttributes.Builder attributes =
        ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder();
    attributes.setInput(OptionsUtils.toByteString(continueParameters.getInput()));
    String workflowType = continueParameters.getWorkflowType();
    if (workflowType != null && !workflowType.isEmpty()) {
      attributes.setWorkflowType(WorkflowType.newBuilder().setName(workflowType));
    } else {
      attributes.setWorkflowType(task.getWorkflowType());
    }
    int executionStartToClose = continueParameters.getExecutionStartToCloseTimeoutSeconds();
    if (executionStartToClose == 0) {
      executionStartToClose = startedEvent.getExecutionStartToCloseTimeoutSeconds();
    }
    attributes.setExecutionStartToCloseTimeoutSeconds(executionStartToClose);
    int taskStartToClose = continueParameters.getTaskStartToCloseTimeoutSeconds();
    if (taskStartToClose == 0) {
      taskStartToClose = startedEvent.getTaskStartToCloseTimeoutSeconds();
    }
    attributes.setTaskStartToCloseTimeoutSeconds(taskStartToClose);
    String taskList = continueParameters.getTaskList();
    if (taskList == null || taskList.isEmpty()) {
      taskList = startedEvent.getTaskList().getName();
    }
    attributes.setTaskList(TaskList.newBuilder().setName(taskList).build());

    // TODO(maxim): Find out what to do about memo, searchAttributes and header

    Decision decision =
        Decision.newBuilder()
            .setDecisionType(DecisionType.ContinueAsNewWorkflowExecution)
            .setContinueAsNewWorkflowExecutionDecisionAttributes(attributes)
            .build();

    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void failWorkflowExecution(WorkflowExecutionException failure) {
    addAllMissingVersionMarker(false, Optional.empty());

    Decision decision =
        Decision.newBuilder()
            .setFailWorkflowExecutionDecisionAttributes(
                FailWorkflowExecutionDecisionAttributes.newBuilder()
                    .setReason(failure.getReason())
                    .setDetails(OptionsUtils.toByteString(failure.getDetails())))
            .setDecisionType(DecisionType.FailWorkflowExecution)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  /**
   * @return <code>false</code> means that cancel failed, <code>true</code> that
   *     CancelWorkflowExecution was created.
   */
  void cancelWorkflowExecution() {
    addAllMissingVersionMarker(false, Optional.empty());

    Decision decision =
        Decision.newBuilder()
            .setCancelWorkflowExecutionDecisionAttributes(
                CancelWorkflowExecutionDecisionAttributes.getDefaultInstance())
            .setDecisionType(DecisionType.CancelWorkflowExecution)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void recordMarker(String markerName, Header header, byte[] details) {
    // no need to call addAllMissingVersionMarker here as all the callers are already doing it.

    RecordMarkerDecisionAttributes.Builder marker =
        RecordMarkerDecisionAttributes.newBuilder()
            .setMarkerName(markerName)
            .setDetails(OptionsUtils.toByteString(details));
    if (header != null) {
      marker.setHeader(header);
    }
    Decision decision =
        Decision.newBuilder()
            .setDecisionType(DecisionType.RecordMarker)
            .setRecordMarkerDecisionAttributes(marker)
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.MARKER, nextDecisionEventId);
    addDecision(decisionId, new MarkerDecisionStateMachine(decisionId, decision));
  }

  void upsertSearchAttributes(SearchAttributes searchAttributes) {
    Decision decision =
        Decision.newBuilder()
            .setDecisionType(DecisionType.UpsertWorkflowSearchAttributes)
            .setUpsertWorkflowSearchAttributesDecisionAttributes(
                UpsertWorkflowSearchAttributesDecisionAttributes.newBuilder()
                    .setSearchAttributes(searchAttributes))
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId =
        new DecisionId(DecisionTarget.UPSERT_SEARCH_ATTRIBUTES, nextDecisionEventId);
    addDecision(decisionId, new UpsertSearchAttributesDecisionStateMachine(decisionId, decision));
  }

  List<Decision> getDecisions() {
    List<Decision> result = new ArrayList<>(MAXIMUM_DECISIONS_PER_COMPLETION + 1);
    for (DecisionStateMachine decisionStateMachine : decisions.values()) {
      Decision decision = decisionStateMachine.getDecision();
      if (decision != null) {
        result.add(decision);
      }
    }
    // Include FORCE_IMMEDIATE_DECISION timer only if there are more then 100 events
    int size = result.size();
    if (size > MAXIMUM_DECISIONS_PER_COMPLETION
        && !isCompletionEvent(result.get(MAXIMUM_DECISIONS_PER_COMPLETION - 2))) {
      result = result.subList(0, MAXIMUM_DECISIONS_PER_COMPLETION - 1);
      Decision d =
          Decision.newBuilder()
              .setStartTimerDecisionAttributes(
                  StartTimerDecisionAttributes.newBuilder()
                      .setStartToFireTimeoutSeconds(0)
                      .setTimerId(FORCE_IMMEDIATE_DECISION_TIMER))
              .setDecisionType(DecisionType.StartTimer)
              .build();
      result.add(d);
    }

    return result;
  }

  private boolean isCompletionEvent(Decision decision) {
    DecisionType type = decision.getDecisionType();
    switch (type) {
      case CancelWorkflowExecution:
      case CompleteWorkflowExecution:
      case FailWorkflowExecution:
      case ContinueAsNewWorkflowExecution:
        return true;
      default:
        return false;
    }
  }

  public void handleDecisionTaskStartedEvent(DecisionEvents decision) {
    this.decisionEvents = decision;
    this.nextDecisionEventId = decision.getNextDecisionEventId();
  }

  void notifyDecisionSent() {
    int count = 0;
    Iterator<DecisionStateMachine> iterator = decisions.values().iterator();
    DecisionStateMachine next = null;

    DecisionStateMachine decisionStateMachine = getNextDecision(iterator);
    while (decisionStateMachine != null) {
      next = getNextDecision(iterator);
      if (++count == MAXIMUM_DECISIONS_PER_COMPLETION
          && next != null
          && !isCompletionEvent(next.getDecision())) {
        break;
      }
      decisionStateMachine.handleDecisionTaskStartedEvent();
      decisionStateMachine = next;
    }
    if (next != null && count < MAXIMUM_DECISIONS_PER_COMPLETION) {
      next.handleDecisionTaskStartedEvent();
    }
  }

  private DecisionStateMachine getNextDecision(Iterator<DecisionStateMachine> iterator) {
    DecisionStateMachine result = null;
    while (result == null && iterator.hasNext()) {
      result = iterator.next();
      if (result.getDecision() == null) {
        result = null;
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return WorkflowExecutionUtils.prettyPrintDecisions(getDecisions());
  }

  PollForDecisionTaskResponse.Builder getTask() {
    return task;
  }

  // addAllMissingVersionMarker should always be called before addDecision. In non-replay mode,
  // addAllMissingVersionMarker is a no-op. In replay mode, it tries to insert back missing
  // version marker decisions, as we allow user to remove getVersion and not breaking their code.
  // Be careful that addAllMissingVersionMarker can add decision and hence change
  // nextDecisionEventId, so any call to determine the event ID for the next decision should happen
  // after that.
  private void addDecision(DecisionId decisionId, DecisionStateMachine decision) {
    Objects.requireNonNull(decisionId);
    decisions.put(decisionId, decision);
    nextDecisionEventId++;
  }

  // This is to support the case where a getVersion call presents during workflow execution but
  // is removed in replay.
  void addAllMissingVersionMarker(
      boolean isNextDecisionVersionMarker,
      Optional<Predicate<MarkerRecordedEventAttributes>> isDifferentChange) {
    boolean added;
    do {
      added = addMissingVersionMarker(isNextDecisionVersionMarker, isDifferentChange);
    } while (added);
  }

  private boolean addMissingVersionMarker(
      boolean isNextDecisionVersionMarker,
      Optional<Predicate<MarkerRecordedEventAttributes>> changeIdEquals) {
    Optional<HistoryEvent> optionalEvent = getOptionalDecisionEvent(nextDecisionEventId);
    if (!optionalEvent.isPresent()) {
      return false;
    }

    HistoryEvent event = optionalEvent.get();
    if (event.getEventType() != EventType.MarkerRecorded) {
      return false;
    }

    if (!event
        .getMarkerRecordedEventAttributes()
        .getMarkerName()
        .equals(ClockDecisionContext.VERSION_MARKER_NAME)) {
      return false;
    }

    // Next decision is for version marker and the event is for the same.
    if (isNextDecisionVersionMarker
        && (!changeIdEquals.isPresent()
            || changeIdEquals.get().test(event.getMarkerRecordedEventAttributes()))) {
      return false;
    }

    // If we have a version marker in history event but not in decisions, let's add one.
    RecordMarkerDecisionAttributes.Builder marker =
        RecordMarkerDecisionAttributes.newBuilder()
            .setMarkerName(ClockDecisionContext.VERSION_MARKER_NAME)
            .setHeader(event.getMarkerRecordedEventAttributes().getHeader())
            .setDetails(event.getMarkerRecordedEventAttributes().getDetails());
    Decision markerDecision =
        Decision.newBuilder()
            .setDecisionType(DecisionType.RecordMarker)
            .setRecordMarkerDecisionAttributes(marker)
            .build();
    DecisionId markerDecisionId = new DecisionId(DecisionTarget.MARKER, nextDecisionEventId);
    decisions.put(
        markerDecisionId, new MarkerDecisionStateMachine(markerDecisionId, markerDecision));
    nextDecisionEventId++;
    return true;
  }

  private DecisionStateMachine getDecision(DecisionId decisionId) {
    DecisionStateMachine result = decisions.get(decisionId);
    if (result == null) {
      throw new NonDeterminisicWorkflowError(
          "Unknown " + decisionId + ". " + NON_DETERMINISTIC_MESSAGE);
    }
    return result;
  }

  String getAndIncrementNextId() {
    return String.valueOf(idCounter++);
  }

  Optional<HistoryEvent> getOptionalDecisionEvent(long eventId) {
    return decisionEvents.getOptionalDecisionEvent(eventId);
  }
}
