/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.integration.transformer;

import static org.junit.Assert.assertThat;

import org.mule.runtime.core.api.message.InternalMessage;
import org.mule.test.AbstractIntegrationTestCase;

import org.junit.Test;

import static org.hamcrest.collection.IsIterableContainingInOrder.contains;

public class TransformerTrackerLifecycleTestCase extends AbstractIntegrationTestCase {

  @Override
  protected String getConfigFile() {
    return "org/mule/test/transformers/transformer-lifecycle-flow.xml";
  }

  @Test
  public void testLifecycle() throws Exception {
    final InternalMessage result = flowRunner("EchoService").withPayload("foo").run().getMessage();

    final LifecycleTrackerTransformer ltt = (LifecycleTrackerTransformer) result.getPayload().getValue();

    muleContext.dispose();

    assertThat(ltt.getTracker(),
               contains("setMuleContext", "setProperty", "initialise", "start", "stop", "dispose"));
  }
}
