/*
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

import static junit.framework.TestCase.assertEquals;

import io.temporal.SearchAttributes;
import io.temporal.WorkflowExecutionStartedEventAttributes;
import io.temporal.converter.DataConverter;
import io.temporal.converter.JsonDataConverter;
import io.temporal.workflow.WorkflowUtils;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class WorkflowContextTest {

  @Test
  public void TestMergeSearchAttributes() {
    WorkflowExecutionStartedEventAttributes startAttr =
        new WorkflowExecutionStartedEventAttributes();
    WorkflowContext workflowContext = new WorkflowContext("domain", null, startAttr);

    DataConverter converter = JsonDataConverter.getInstance();
    Map<String, ByteBuffer> indexedFields = new HashMap<>();
    indexedFields.put("CustomKeywordField", ByteBuffer.wrap(converter.toData("key")));

    SearchAttributes searchAttributes = new SearchAttributes();
    searchAttributes.setIndexedFields(indexedFields);

    workflowContext.mergeSearchAttributes(searchAttributes);

    assertEquals(
        "key",
        WorkflowUtils.getValueFromSearchAttributes(
            workflowContext.getSearchAttributes(), "CustomKeywordField", String.class));
  }
}