/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */


package org.opensearch.sql.plugin;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionResponse;
import org.opensearch.action.ActionType;
import org.opensearch.client.Client;
import org.opensearch.client.node.NodeClient;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.commons.sql.action.SQLActions;
import org.opensearch.commons.sql.action.TransportQueryResponse;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.ScriptService;
import org.opensearch.sql.legacy.esdomain.LocalClusterState;
import org.opensearch.sql.legacy.executor.AsyncRestExecutor;
import org.opensearch.sql.legacy.metrics.Metrics;
import org.opensearch.sql.legacy.plugin.OpenSearchSQLPluginConfig;
import org.opensearch.sql.legacy.plugin.RestSqlAction;
import org.opensearch.sql.legacy.plugin.RestSqlStatsAction;
import org.opensearch.sql.opensearch.security.SecurityAccess;
import org.opensearch.sql.opensearch.setting.LegacyOpenDistroSettings;
import org.opensearch.sql.opensearch.setting.OpenSearchSettings;
import org.opensearch.sql.opensearch.storage.script.ExpressionScriptEngine;
import org.opensearch.sql.opensearch.storage.serialization.DefaultExpressionSerializer;
import org.opensearch.sql.plugin.rest.OpenSearchPluginConfig;
import org.opensearch.sql.plugin.rest.RestPPLQueryAction;
import org.opensearch.sql.plugin.rest.RestPPLStatsAction;
import org.opensearch.sql.plugin.rest.RestQuerySettingsAction;
import org.opensearch.sql.plugin.transport.PPLQueryHelper;
import org.opensearch.sql.plugin.transport.SQLQueryHelper;
import org.opensearch.sql.plugin.transport.TransportQueryAction;
import org.opensearch.sql.ppl.PPLService;
import org.opensearch.sql.ppl.config.PPLServiceConfig;
import org.opensearch.sql.sql.SQLService;
import org.opensearch.sql.sql.config.SQLServiceConfig;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.FixedExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.watcher.ResourceWatcherService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class SQLPlugin extends Plugin implements ActionPlugin, ScriptPlugin {

  private ClusterService clusterService;

  /**
   * Settings should be inited when bootstrap the plugin.
   */
  private org.opensearch.sql.common.setting.Settings pluginSettings;

  public String name() {
    return "sql";
  }

  public String description() {
    return "Use sql to query OpenSearch.";
  }

  @Override
  public List<RestHandler> getRestHandlers(Settings settings, RestController restController,
                                           ClusterSettings clusterSettings,
                                           IndexScopedSettings indexScopedSettings,
                                           SettingsFilter settingsFilter,
                                           IndexNameExpressionResolver indexNameExpressionResolver,
                                           Supplier<DiscoveryNodes> nodesInCluster) {
    Objects.requireNonNull(clusterService, "Cluster service is required");
    Objects.requireNonNull(pluginSettings, "Cluster settings is required");

    LocalClusterState.state().setResolver(indexNameExpressionResolver);
    Metrics.getInstance().registerDefaultMetrics();

    return Arrays.asList(
        new RestPPLQueryAction(restController, clusterService, pluginSettings, settings),
        new RestSqlAction(settings, clusterService, pluginSettings),
        new RestSqlStatsAction(settings, restController),
        new RestPPLStatsAction(settings, restController),
        new RestQuerySettingsAction(settings, restController)
    );
  }

  /*
   * Register action and handler so that transportClient can find proxy for action
   */
  @Override
  public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
    return Arrays
            .asList(
                    new ActionHandler<>(
                        new ActionType<>(SQLActions.SEND_SQL_QUERY_NAME, TransportQueryResponse::new),
                        TransportQueryAction.class
                    )
            );
  }


  @Override
  public Collection<Object> createComponents(Client client, ClusterService clusterService,
                                             ThreadPool threadPool,
                                             ResourceWatcherService resourceWatcherService,
                                             ScriptService scriptService,
                                             NamedXContentRegistry contentRegistry,
                                             Environment environment,
                                             NodeEnvironment nodeEnvironment,
                                             NamedWriteableRegistry namedWriteableRegistry,
                                             IndexNameExpressionResolver indexNameResolver,
                                             Supplier<RepositoriesService>
                                                       repositoriesServiceSupplier) {
    this.clusterService = clusterService;
    this.pluginSettings = new OpenSearchSettings(clusterService.getClusterSettings());

    LocalClusterState.state().setClusterService(clusterService);
    LocalClusterState.state().setPluginSettings((OpenSearchSettings) pluginSettings);

    PPLQueryHelper.getInstance().setPplService(createPPLService((NodeClient) client));
    SQLQueryHelper.getInstance().setSqlService(createSQLService((NodeClient) client));
    
    
    return super
        .createComponents(client, clusterService, threadPool, resourceWatcherService, scriptService,
            contentRegistry, environment, nodeEnvironment, namedWriteableRegistry,
            indexNameResolver, repositoriesServiceSupplier);
  }

  private <T> T doPrivileged(PrivilegedExceptionAction<T> action) {
    try {
      return SecurityAccess.doPrivileged(action);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to perform privileged action", e);
    }
  }


  private PPLService createPPLService(NodeClient client) {
    return doPrivileged(() -> {
      AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
      context.registerBean(ClusterService.class, () -> clusterService);
      context.registerBean(NodeClient.class, () -> client);
      context.registerBean(org.opensearch.sql.common.setting.Settings.class, () -> pluginSettings);
      context.register(OpenSearchPluginConfig.class);
      context.register(PPLServiceConfig.class);
      context.refresh();
      return context.getBean(PPLService.class);
    });
  }

  private SQLService createSQLService(NodeClient client) {
    return doPrivileged(() -> {
      AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
      context.registerBean(ClusterService.class, () -> clusterService);
      context.registerBean(NodeClient.class, () -> client);
      context.registerBean(org.opensearch.sql.common.setting.Settings.class, () -> pluginSettings);
      context.register(OpenSearchSQLPluginConfig.class);
      context.register(SQLServiceConfig.class);
      context.refresh();
      return context.getBean(SQLService.class);
    });
  }

  @Override
  public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
    return Collections.singletonList(
        new FixedExecutorBuilder(
            settings,
            AsyncRestExecutor.SQL_WORKER_THREAD_POOL_NAME,
            OpenSearchExecutors.allocatedProcessors(settings),
            1000,
            null
        )
    );
  }

  @Override
  public List<Setting<?>> getSettings() {
    return new ImmutableList.Builder<Setting<?>>()
        .addAll(LegacyOpenDistroSettings.legacySettings())
        .addAll(OpenSearchSettings.pluginSettings())
        .build();
  }

  @Override
  public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
    return new ExpressionScriptEngine(new DefaultExpressionSerializer());
  }

}
