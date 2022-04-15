/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */


package org.opensearch.sql.opensearch.storage;

import static org.opensearch.search.sort.FieldSortBuilder.DOC_FIELD_NAME;
import static org.opensearch.search.sort.SortOrder.ASC;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortBuilder;
import org.opensearch.sql.common.setting.Settings;
import org.opensearch.sql.data.model.ExprDoubleValue;
import org.opensearch.sql.data.model.ExprTupleValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.expression.ReferenceExpression;
import org.opensearch.sql.opensearch.client.OpenSearchClient;
import org.opensearch.sql.opensearch.data.value.OpenSearchExprValueFactory;
import org.opensearch.sql.opensearch.client.IPrometheusService;
import org.opensearch.sql.opensearch.request.OpenSearchQueryRequest;
import org.opensearch.sql.opensearch.request.OpenSearchRequest;
import org.opensearch.sql.opensearch.response.agg.OpenSearchAggregationResponseParser;
import org.opensearch.sql.storage.TableScanOperator;

/**
 * OpenSearch index scan operator.
 */
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString(onlyExplicitlyIncluded = true)
public class OpenSearchIndexScan extends TableScanOperator {

  /** OpenSearch client. */
  private final OpenSearchClient client;

  private final IPrometheusService prometheusService;

  /** Search request. */
  @EqualsAndHashCode.Include
  @Getter
  @ToString.Include
  private final OpenSearchRequest request;

  /** Search response for current batch. */
  private Iterator<ExprValue> iterator;


  /**
   * Constructor.
   */
  public OpenSearchIndexScan(OpenSearchClient client,
                             IPrometheusService prometheusService,
                             Settings settings, String indexName,
                             OpenSearchExprValueFactory exprValueFactory) {
    this(client, prometheusService, settings, new OpenSearchRequest.IndexName(indexName), exprValueFactory);
  }

  /**
   * Constructor.
   */
  public OpenSearchIndexScan(OpenSearchClient client, IPrometheusService prometheusService,
      Settings settings, OpenSearchRequest.IndexName indexName,
      OpenSearchExprValueFactory exprValueFactory) {
    this.client = client;
    this.prometheusService = prometheusService;
    this.request = new OpenSearchQueryRequest(indexName,
        settings.getSettingValue(Settings.Key.QUERY_SIZE_LIMIT), exprValueFactory);
  }

  @Override
  public void open() {
    super.open();

    JSONObject responseObject = AccessController.doPrivileged((PrivilegedAction<JSONObject>)  ()-> {
      try {
        return prometheusService.queryRange("localhost", 9090,
                request.getPrometheusQueryBuilder().toString(),
                request.getStartTime(), request.getEndTime(), request.getxStep());
      } catch (IOException e) {
        e.printStackTrace();
      }
      return null;
    });
    List<ExprValue> result = new ArrayList<>();
    OpenSearchExprValueFactory exprValueFactory = this.request.getExprValueFactory();
    Map<String, ExprType> typeMapping =  this.request.getExprValueFactory().getTypeMapping();
    Set<String> keySet =  this.request.getExprValueFactory().getTypeMapping().keySet();
    String valueKey = keySet.stream().filter(x -> typeMapping.get(x).equals(ExprCoreType.DOUBLE)).findFirst().get();
    if("matrix".equals(responseObject.getString("resultType"))){
      JSONArray itemArray = responseObject.getJSONArray("result");
      for (int i = 0; i < itemArray.length(); i++) {
        JSONObject item = itemArray.getJSONObject(i);
        JSONObject metric = item.getJSONObject("metric");
        JSONArray values = item.getJSONArray("values");
        for (int j = 0; j < values.length(); j++) {
          LinkedHashMap<String, ExprValue> linkedHashMap = new LinkedHashMap<>();
          JSONArray val = values.getJSONArray(j);
          linkedHashMap.put("@timestamp", exprValueFactory.construct("@timestamp", val.getLong(0)));
          linkedHashMap.put(valueKey, new ExprDoubleValue(val.getDouble(1)));
          Iterator<String> iterator = metric.keys();
          while(iterator.hasNext()) {
            String key = iterator.next();
            linkedHashMap.put(key, exprValueFactory.construct(key, metric.getString(key)));
          }
          result.add(new ExprTupleValue(linkedHashMap));
        }
      }
    }
    iterator = result.iterator();
  }

  @Override
  public boolean hasNext() {
    return iterator.hasNext();
  }

  @Override
  public ExprValue next() {
   return iterator.next();
  }

  /**
   * Push down query to DSL request.
   * @param query  query request
   */
  public void pushDown(QueryBuilder query) {
    SearchSourceBuilder source = request.getSourceBuilder();
    QueryBuilder current = source.query();

    if (current == null) {
      source.query(query);
    } else {
      if (isBoolFilterQuery(current)) {
        ((BoolQueryBuilder) current).filter(query);
      } else {
        source.query(QueryBuilders.boolQuery()
                                  .filter(current)
                                  .filter(query));
      }
    }

    if (source.sorts() == null) {
      source.sort(DOC_FIELD_NAME, ASC); // Make sure consistent order
    }
  }

  /**
   * Push down aggregation to DSL request.
   * @param aggregationBuilder pair of aggregation query and aggregation parser.
   */
  public void pushDownAggregation(
      Pair<List<AggregationBuilder>, OpenSearchAggregationResponseParser> aggregationBuilder) {
    SearchSourceBuilder source = request.getSourceBuilder();
    aggregationBuilder.getLeft().forEach(builder -> source.aggregation(builder));
    source.size(0);
    request.getExprValueFactory().setParser(aggregationBuilder.getRight());
  }

  /**
   * Push down sort to DSL request.
   *
   * @param sortBuilders sortBuilders.
   */
  public void pushDownSort(List<SortBuilder<?>> sortBuilders) {
    SearchSourceBuilder source = request.getSourceBuilder();
    for (SortBuilder<?> sortBuilder : sortBuilders) {
      source.sort(sortBuilder);
    }
  }

  /**
   * Push down size (limit) and from (offset) to DSL request.
   */
  public void pushDownLimit(Integer limit, Integer offset) {
    SearchSourceBuilder sourceBuilder = request.getSourceBuilder();
    sourceBuilder.from(offset).size(limit);
  }

  /**
   * Push down project list to DSL requets.
   */
  public void pushDownProjects(Set<ReferenceExpression> projects) {
    SearchSourceBuilder sourceBuilder = request.getSourceBuilder();
    final Set<String> projectsSet =
        projects.stream().map(ReferenceExpression::getAttr).collect(Collectors.toSet());
    sourceBuilder.fetchSource(projectsSet.toArray(new String[0]), new String[0]);
  }

  public void pushTypeMapping(Map<String, ExprType> typeMapping) {
    request.getExprValueFactory().setTypeMapping(typeMapping);
  }

  @Override
  public void close() {
    super.close();

    client.cleanup(request);
  }

  private boolean isBoolFilterQuery(QueryBuilder current) {
    return (current instanceof BoolQueryBuilder);
  }

  @Override
  public String explain() {
    return getRequest().toString();
  }
}
