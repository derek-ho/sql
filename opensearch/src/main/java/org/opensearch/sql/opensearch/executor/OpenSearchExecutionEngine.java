/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */


package org.opensearch.sql.opensearch.executor;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.opensearch.sql.common.response.ResponseListener;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.Explain;
import org.opensearch.sql.opensearch.client.OpenSearchClient;
import org.opensearch.sql.opensearch.executor.protector.ExecutionProtector;
import org.opensearch.sql.opensearch.security.SecurityAccess;
import org.opensearch.sql.planner.physical.PhysicalPlan;
import org.opensearch.sql.storage.TableScanOperator;

/** OpenSearch execution engine implementation. */
@RequiredArgsConstructor
public class OpenSearchExecutionEngine implements ExecutionEngine {

  private final OpenSearchClient client;

  private final ExecutionProtector executionProtector;

  @Override
  public QueryResponse execute(PhysicalPlan physicalPlan) {
    return doPrivileged(() -> {
      try (PhysicalPlan plan = executionProtector.protect(physicalPlan)) {
        List<ExprValue> result = new ArrayList<>();
        plan.open();

        while (plan.hasNext()) {
          result.add(plan.next());
        }
        return new QueryResponse(physicalPlan.schema(), result);
      }
    });
  }

  @Override
  public void execute(PhysicalPlan physicalPlan, ResponseListener<QueryResponse> listener) {
    PhysicalPlan plan = executionProtector.protect(physicalPlan);
    client.schedule(
        () -> {
          try {
            List<ExprValue> result = new ArrayList<>();
            plan.open();

            while (plan.hasNext()) {
              result.add(plan.next());
            }

            QueryResponse response = new QueryResponse(physicalPlan.schema(), result);
            listener.onResponse(response);
          } catch (Exception e) {
            listener.onFailure(e);
          } finally {
            plan.close();
          }
        });
  }

  @Override
  public void explain(PhysicalPlan plan, ResponseListener<ExplainResponse> listener) {
    client.schedule(() -> {
      try {
        Explain openSearchExplain = new Explain() {
          @Override
          public ExplainResponseNode visitTableScan(TableScanOperator node, Object context) {
            return explain(node, context, explainNode -> {
              explainNode.setDescription(ImmutableMap.of("request", node.explain()));
            });
          }
        };

        listener.onResponse(openSearchExplain.apply(plan));
      } catch (Exception e) {
        listener.onFailure(e);
      }
    });
  }

  private <T> T doPrivileged(PrivilegedExceptionAction<T> action) {
    try {
      return SecurityAccess.doPrivileged(action);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to perform privileged action", e);
    }
  }

}
