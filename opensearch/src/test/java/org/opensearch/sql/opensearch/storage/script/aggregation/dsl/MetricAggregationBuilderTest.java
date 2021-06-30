/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 *
 *    Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License").
 *    You may not use this file except in compliance with the License.
 *    A copy of the License is located at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    or in the "license" file accompanying this file. This file is distributed
 *    on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *    express or implied. See the License for the specific language governing
 *    permissions and limitations under the License.
 *
 */

package org.opensearch.sql.opensearch.storage.script.aggregation.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.opensearch.sql.data.type.ExprCoreType.INTEGER;
import static org.opensearch.sql.expression.DSL.literal;
import static org.opensearch.sql.expression.DSL.named;
import static org.opensearch.sql.expression.DSL.ref;
import static org.opensearch.sql.expression.aggregation.StdDevAggregator.stddevPopulation;
import static org.opensearch.sql.expression.aggregation.StdDevAggregator.stddevSample;
import static org.opensearch.sql.expression.aggregation.VarianceAggregator.variancePopulation;
import static org.opensearch.sql.expression.aggregation.VarianceAggregator.varianceSample;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.sql.expression.aggregation.AvgAggregator;
import org.opensearch.sql.expression.aggregation.CountAggregator;
import org.opensearch.sql.expression.aggregation.MaxAggregator;
import org.opensearch.sql.expression.aggregation.MinAggregator;
import org.opensearch.sql.expression.aggregation.NamedAggregator;
import org.opensearch.sql.expression.aggregation.SumAggregator;
import org.opensearch.sql.expression.aggregation.VarianceAggregator;
import org.opensearch.sql.expression.function.FunctionName;
import org.opensearch.sql.opensearch.storage.serialization.ExpressionSerializer;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(MockitoExtension.class)
class MetricAggregationBuilderTest {

  @Mock
  private ExpressionSerializer serializer;

  @Mock
  private NamedAggregator aggregator;

  private MetricAggregationBuilder aggregationBuilder;

  @BeforeEach
  void set_up() {
    aggregationBuilder = new MetricAggregationBuilder(serializer);
  }

  @Test
  void should_build_avg_aggregation() {
    assertEquals(
        "{\n"
            + "  \"avg(age)\" : {\n"
            + "    \"avg\" : {\n"
            + "      \"field\" : \"age\"\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("avg(age)",
                    new AvgAggregator(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_sum_aggregation() {
    assertEquals(
        "{\n"
            + "  \"sum(age)\" : {\n"
            + "    \"sum\" : {\n"
            + "      \"field\" : \"age\"\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("sum(age)",
                    new SumAggregator(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_count_aggregation() {
    assertEquals(
        "{\n"
            + "  \"count(age)\" : {\n"
            + "    \"value_count\" : {\n"
            + "      \"field\" : \"age\"\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("count(age)",
                    new CountAggregator(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_count_star_aggregation() {
    assertEquals(
        "{\n"
            + "  \"count(*)\" : {\n"
            + "    \"value_count\" : {\n"
            + "      \"field\" : \"_index\"\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("count(*)",
                    new CountAggregator(Arrays.asList(literal("*")), INTEGER)))));
  }

  @Test
  void should_build_count_other_literal_aggregation() {
    assertEquals(
        "{\n"
            + "  \"count(1)\" : {\n"
            + "    \"value_count\" : {\n"
            + "      \"field\" : \"_index\"\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("count(1)",
                    new CountAggregator(Arrays.asList(literal(1)), INTEGER)))));
  }

  @Test
  void should_build_min_aggregation() {
    assertEquals(
        "{\n"
            + "  \"min(age)\" : {\n"
            + "    \"min\" : {\n"
            + "      \"field\" : \"age\"\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("min(age)",
                    new MinAggregator(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_max_aggregation() {
    assertEquals(
        "{\n"
            + "  \"max(age)\" : {\n"
            + "    \"max\" : {\n"
            + "      \"field\" : \"age\"\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("max(age)",
                    new MaxAggregator(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_varPop_aggregation() {
    assertEquals(
        "{\n"
            + "  \"var_pop(age)\" : {\n"
            + "    \"extended_stats\" : {\n"
            + "      \"field\" : \"age\",\n"
            + "      \"sigma\" : 2.0\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("var_pop(age)",
                    variancePopulation(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_varSamp_aggregation() {
    assertEquals(
        "{\n"
            + "  \"var_samp(age)\" : {\n"
            + "    \"extended_stats\" : {\n"
            + "      \"field\" : \"age\",\n"
            + "      \"sigma\" : 2.0\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("var_samp(age)",
                    varianceSample(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_stddevPop_aggregation() {
    assertEquals(
        "{\n"
            + "  \"stddev_pop(age)\" : {\n"
            + "    \"extended_stats\" : {\n"
            + "      \"field\" : \"age\",\n"
            + "      \"sigma\" : 2.0\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("stddev_pop(age)",
                    stddevPopulation(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_build_stddevSamp_aggregation() {
    assertEquals(
        "{\n"
            + "  \"stddev_samp(age)\" : {\n"
            + "    \"extended_stats\" : {\n"
            + "      \"field\" : \"age\",\n"
            + "      \"sigma\" : 2.0\n"
            + "    }\n"
            + "  }\n"
            + "}",
        buildQuery(
            Arrays.asList(
                named("stddev_samp(age)",
                    stddevSample(Arrays.asList(ref("age", INTEGER)), INTEGER)))));
  }

  @Test
  void should_throw_exception_for_unsupported_aggregator() {
    when(aggregator.getFunctionName()).thenReturn(new FunctionName("unsupported_agg"));
    when(aggregator.getArguments()).thenReturn(Arrays.asList(ref("age", INTEGER)));

    IllegalStateException exception =
        assertThrows(IllegalStateException.class,
            () -> buildQuery(Arrays.asList(named("unsupported_agg(age)", aggregator))));
    assertEquals("unsupported aggregator unsupported_agg", exception.getMessage());
  }

  @Test
  void should_throw_exception_for_unsupported_exception() {
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> buildQuery(Arrays.asList(
            named("count(age)",
                new CountAggregator(Arrays.asList(named("age", ref("age", INTEGER))), INTEGER)))));
    assertEquals(
        "metric aggregation doesn't support expression age",
        exception.getMessage());
  }

  @SneakyThrows
  private String buildQuery(List<NamedAggregator> namedAggregatorList) {
    ObjectMapper objectMapper = new ObjectMapper();
    return objectMapper.readTree(
        aggregationBuilder.build(namedAggregatorList).getLeft().toString())
        .toPrettyString();
  }
}