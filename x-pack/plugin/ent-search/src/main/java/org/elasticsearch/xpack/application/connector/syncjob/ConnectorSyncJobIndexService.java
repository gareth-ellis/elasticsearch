/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.connector.syncjob;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DelegatingActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.application.connector.Connector;
import org.elasticsearch.xpack.application.connector.ConnectorFiltering;
import org.elasticsearch.xpack.application.connector.ConnectorIndexService;
import org.elasticsearch.xpack.application.connector.ConnectorSyncStatus;
import org.elasticsearch.xpack.application.connector.ConnectorTemplateRegistry;
import org.elasticsearch.xpack.application.connector.filtering.FilteringRules;
import org.elasticsearch.xpack.application.connector.syncjob.action.PostConnectorSyncJobAction;
import org.elasticsearch.xpack.application.connector.syncjob.action.UpdateConnectorSyncJobIngestionStatsAction;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.core.ClientHelper.CONNECTORS_ORIGIN;

/**
 * A service that manages persistent {@link ConnectorSyncJob} configurations.
 */
public class ConnectorSyncJobIndexService {

    private static final Long ZERO = 0L;

    private final Client clientWithOrigin;

    public static final String CONNECTOR_SYNC_JOB_INDEX_NAME = ConnectorTemplateRegistry.CONNECTOR_SYNC_JOBS_INDEX_NAME_PATTERN;

    /**
     * @param client A client for executing actions on the connectors sync jobs index.
     */
    public ConnectorSyncJobIndexService(Client client) {
        this.clientWithOrigin = new OriginSettingClient(client, CONNECTORS_ORIGIN);
    }

    /**
     * @param request   Request for creating a connector sync job.
     * @param listener  Listener to respond to a successful response or an error.
     */
    public void createConnectorSyncJob(
        PostConnectorSyncJobAction.Request request,
        ActionListener<PostConnectorSyncJobAction.Response> listener
    ) {
        try {
            getSyncJobConnectorInfo(request.getId(), listener.delegateFailure((l, connector) -> {
                Instant now = Instant.now();
                ConnectorSyncJobType jobType = Objects.requireNonNullElse(request.getJobType(), ConnectorSyncJob.DEFAULT_JOB_TYPE);
                ConnectorSyncJobTriggerMethod triggerMethod = Objects.requireNonNullElse(
                    request.getTriggerMethod(),
                    ConnectorSyncJob.DEFAULT_TRIGGER_METHOD
                );

                try {

                    final IndexRequest indexRequest = new IndexRequest(CONNECTOR_SYNC_JOB_INDEX_NAME).opType(DocWriteRequest.OpType.INDEX)
                        .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

                    ConnectorSyncJob syncJob = new ConnectorSyncJob.Builder().setJobType(jobType)
                        .setTriggerMethod(triggerMethod)
                        .setStatus(ConnectorSyncJob.DEFAULT_INITIAL_STATUS)
                        .setConnector(connector)
                        .setCreatedAt(now)
                        .setLastSeen(now)
                        .setTotalDocumentCount(ZERO)
                        .setIndexedDocumentCount(ZERO)
                        .setIndexedDocumentVolume(ZERO)
                        .setDeletedDocumentCount(ZERO)
                        .build();

                    indexRequest.source(syncJob.toXContent(jsonBuilder(), ToXContent.EMPTY_PARAMS));

                    clientWithOrigin.index(
                        indexRequest,
                        l.delegateFailureAndWrap(
                            (ll, indexResponse) -> ll.onResponse(new PostConnectorSyncJobAction.Response(indexResponse.getId()))
                        )
                    );
                } catch (IOException e) {
                    l.onFailure(e);
                }
            }));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Deletes the {@link ConnectorSyncJob} in the underlying index.
     *
     * @param connectorSyncJobId The id of the connector sync job object.
     * @param listener               The action listener to invoke on response/failure.
     */
    public void deleteConnectorSyncJob(String connectorSyncJobId, ActionListener<DeleteResponse> listener) {
        final DeleteRequest deleteRequest = new DeleteRequest(CONNECTOR_SYNC_JOB_INDEX_NAME).id(connectorSyncJobId)
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        try {
            clientWithOrigin.delete(
                deleteRequest,
                new DelegatingIndexNotFoundOrDocumentMissingActionListener<>(connectorSyncJobId, listener, (l, deleteResponse) -> {
                    if (deleteResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                        l.onFailure(new ResourceNotFoundException(connectorSyncJobId));
                        return;
                    }
                    l.onResponse(deleteResponse);
                })
            );
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Checks in the {@link ConnectorSyncJob} in the underlying index.
     * In this context "checking in" means to update the "last_seen" timestamp to the time, when the method was called.
     *
     * @param connectorSyncJobId     The id of the connector sync job object.
     * @param listener               The action listener to invoke on response/failure.
     */
    public void checkInConnectorSyncJob(String connectorSyncJobId, ActionListener<UpdateResponse> listener) {
        Instant newLastSeen = Instant.now();

        final UpdateRequest updateRequest = new UpdateRequest(CONNECTOR_SYNC_JOB_INDEX_NAME, connectorSyncJobId).setRefreshPolicy(
            WriteRequest.RefreshPolicy.IMMEDIATE
        ).doc(Map.of(ConnectorSyncJob.LAST_SEEN_FIELD.getPreferredName(), newLastSeen));

        try {
            clientWithOrigin.update(
                updateRequest,
                new DelegatingIndexNotFoundOrDocumentMissingActionListener<>(connectorSyncJobId, listener, (l, updateResponse) -> {
                    if (updateResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                        l.onFailure(new ResourceNotFoundException(connectorSyncJobId));
                        return;
                    }
                    l.onResponse(updateResponse);
                })
            );
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Gets the {@link ConnectorSyncJob} from the underlying index.
     *
     * @param connectorSyncJobId The id of the connector sync job object.
     * @param listener           The action listener to invoke on response/failure.
     */
    public void getConnectorSyncJob(String connectorSyncJobId, ActionListener<ConnectorSyncJobSearchResult> listener) {
        final GetRequest getRequest = new GetRequest(CONNECTOR_SYNC_JOB_INDEX_NAME).id(connectorSyncJobId).realtime(true);

        try {
            clientWithOrigin.get(
                getRequest,
                new DelegatingIndexNotFoundOrDocumentMissingActionListener<>(connectorSyncJobId, listener, (l, getResponse) -> {
                    if (getResponse.isExists() == false) {
                        l.onFailure(new ResourceNotFoundException(connectorSyncJobId));
                        return;
                    }

                    try {
                        ConnectorSyncJobSearchResult syncJobSearchResult = new ConnectorSyncJobSearchResult.Builder().setId(
                            getResponse.getId()
                        ).setResultBytes(getResponse.getSourceAsBytesRef()).setResultMap(getResponse.getSourceAsMap()).build();
                        l.onResponse(syncJobSearchResult);
                    } catch (Exception e) {
                        listener.onFailure(e);
                    }
                })
            );
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Cancels the {@link ConnectorSyncJob} in the underlying index.
     * Canceling means to set the {@link ConnectorSyncStatus} to "canceling" and not "canceled" as this is an async operation.
     * It also updates 'cancelation_requested_at' to the time, when the method was called.
     *
     * @param connectorSyncJobId     The id of the connector sync job object.
     * @param listener               The action listener to invoke on response/failure.
     */
    public void cancelConnectorSyncJob(String connectorSyncJobId, ActionListener<UpdateResponse> listener) {
        Instant cancellationRequestedAt = Instant.now();

        final UpdateRequest updateRequest = new UpdateRequest(CONNECTOR_SYNC_JOB_INDEX_NAME, connectorSyncJobId).setRefreshPolicy(
            WriteRequest.RefreshPolicy.IMMEDIATE
        )
            .doc(
                Map.of(
                    ConnectorSyncJob.STATUS_FIELD.getPreferredName(),
                    ConnectorSyncStatus.CANCELING,
                    ConnectorSyncJob.CANCELATION_REQUESTED_AT_FIELD.getPreferredName(),
                    cancellationRequestedAt
                )
            );

        try {
            clientWithOrigin.update(
                updateRequest,
                new DelegatingIndexNotFoundOrDocumentMissingActionListener<>(connectorSyncJobId, listener, (l, updateResponse) -> {
                    if (updateResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                        l.onFailure(new ResourceNotFoundException(connectorSyncJobId));
                        return;
                    }
                    l.onResponse(updateResponse);
                })
            );
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * List the {@link ConnectorSyncJob} in ascending order of their 'created_at'.
     *
     * @param from          From index to start the search from.
     * @param size          The maximum number of {@link Connector}s to return.
     * @param connectorId   The id of a {@link Connector} to return sync jobs from.
     * @param syncStatus    The status to filter the sync jobs on.
     * @param listener      The action listener to invoke on response/failure.
     */
    public void listConnectorSyncJobs(
        int from,
        int size,
        String connectorId,
        ConnectorSyncStatus syncStatus,
        ActionListener<ConnectorSyncJobIndexService.ConnectorSyncJobsResult> listener
    ) {
        try {
            QueryBuilder query = buildListQuery(connectorId, syncStatus);

            final SearchSourceBuilder searchSource = new SearchSourceBuilder().from(from)
                .size(size)
                .query(query)
                .fetchSource(true)
                .sort(ConnectorSyncJob.CREATED_AT_FIELD.getPreferredName(), SortOrder.ASC);

            final SearchRequest searchRequest = new SearchRequest(CONNECTOR_SYNC_JOB_INDEX_NAME).source(searchSource);

            clientWithOrigin.search(searchRequest, new ActionListener<>() {
                @Override
                public void onResponse(SearchResponse searchResponse) {
                    try {
                        listener.onResponse(mapSearchResponseToConnectorSyncJobsList(searchResponse));
                    } catch (Exception e) {
                        listener.onFailure(e);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    if (e instanceof IndexNotFoundException) {
                        listener.onResponse(new ConnectorSyncJobIndexService.ConnectorSyncJobsResult(Collections.emptyList(), 0L));
                        return;
                    }
                    listener.onFailure(e);
                }
            });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private static QueryBuilder buildListQuery(String connectorId, ConnectorSyncStatus syncStatus) {
        boolean usesFilter = Stream.of(connectorId, syncStatus).anyMatch(Objects::nonNull);
        BoolQueryBuilder boolFilterQueryBuilder = new BoolQueryBuilder();

        if (usesFilter) {
            if (Objects.nonNull(connectorId)) {
                TermQueryBuilder connectorIdQuery = new TermQueryBuilder(
                    ConnectorSyncJob.CONNECTOR_FIELD.getPreferredName() + "." + Connector.ID_FIELD.getPreferredName(),
                    connectorId
                );
                boolFilterQueryBuilder.must().add(connectorIdQuery);
            }

            if (Objects.nonNull(syncStatus)) {
                TermQueryBuilder syncStatusQuery = new TermQueryBuilder(ConnectorSyncJob.STATUS_FIELD.getPreferredName(), syncStatus);
                boolFilterQueryBuilder.must().add(syncStatusQuery);
            }
        }

        return usesFilter ? boolFilterQueryBuilder : new MatchAllQueryBuilder();
    }

    private ConnectorSyncJobsResult mapSearchResponseToConnectorSyncJobsList(SearchResponse searchResponse) {
        final List<ConnectorSyncJobSearchResult> connectorSyncJobs = Arrays.stream(searchResponse.getHits().getHits())
            .map(ConnectorSyncJobIndexService::hitToConnectorSyncJob)
            .toList();

        return new ConnectorSyncJobIndexService.ConnectorSyncJobsResult(
            connectorSyncJobs,
            (int) searchResponse.getHits().getTotalHits().value
        );
    }

    private static ConnectorSyncJobSearchResult hitToConnectorSyncJob(SearchHit searchHit) {
        // TODO: don't return sensitive data from configuration inside connector in list endpoint

        return new ConnectorSyncJobSearchResult.Builder().setId(searchHit.getId())
            .setResultBytes(searchHit.getSourceRef())
            .setResultMap(searchHit.getSourceAsMap())
            .build();

    }

    public record ConnectorSyncJobsResult(List<ConnectorSyncJobSearchResult> connectorSyncJobs, long totalResults) {}

    /**
    * Updates the ingestion stats of the {@link ConnectorSyncJob} in the underlying index.
    *
    * @param request                Request containing the updates to the ingestion stats.
    * @param listener               The action listener to invoke on response/failure.
    */
    public void updateConnectorSyncJobIngestionStats(
        UpdateConnectorSyncJobIngestionStatsAction.Request request,
        ActionListener<UpdateResponse> listener
    ) {
        String syncJobId = request.getConnectorSyncJobId();

        Map<String, Object> fieldsToUpdate = new HashMap<>(
            Map.of(
                ConnectorSyncJob.DELETED_DOCUMENT_COUNT_FIELD.getPreferredName(),
                request.getDeletedDocumentCount(),
                ConnectorSyncJob.INDEXED_DOCUMENT_COUNT_FIELD.getPreferredName(),
                request.getIndexedDocumentCount(),
                ConnectorSyncJob.INDEXED_DOCUMENT_VOLUME_FIELD.getPreferredName(),
                request.getIndexedDocumentVolume()
            )
        );

        if (Objects.nonNull(request.getTotalDocumentCount())) {
            fieldsToUpdate.put(ConnectorSyncJob.TOTAL_DOCUMENT_COUNT_FIELD.getPreferredName(), request.getTotalDocumentCount());
        }
        // TODO: what to do, if no total document count is specified? Calculate it via the current count and params of the request? fetch
        // the size of the target index?

        Instant lastSeen = Objects.nonNull(request.getLastSeen()) ? request.getLastSeen() : Instant.now();
        fieldsToUpdate.put(ConnectorSyncJob.LAST_SEEN_FIELD.getPreferredName(), lastSeen);

        final UpdateRequest updateRequest = new UpdateRequest(CONNECTOR_SYNC_JOB_INDEX_NAME, syncJobId).setRefreshPolicy(
            WriteRequest.RefreshPolicy.IMMEDIATE
        ).doc(fieldsToUpdate);

        try {
            clientWithOrigin.update(
                updateRequest,
                new DelegatingIndexNotFoundOrDocumentMissingActionListener<>(syncJobId, listener, (l, updateResponse) -> {
                    if (updateResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                        l.onFailure(new ResourceNotFoundException(syncJobId));
                        return;
                    }
                    l.onResponse(updateResponse);
                })
            );
        } catch (Exception e) {
            listener.onFailure(e);
        }

    }

    private String generateId() {
        /* Workaround: only needed for generating an id upfront, autoGenerateId() has a side effect generating a timestamp,
         * which would raise an error on the response layer later ("autoGeneratedTimestamp should not be set externally").
         * TODO: do we even need to copy the "_id" and set it as "id"?
         */
        return UUIDs.base64UUID();
    }

    private void getSyncJobConnectorInfo(String connectorId, ActionListener<Connector> listener) {
        try {

            final GetRequest request = new GetRequest(ConnectorIndexService.CONNECTOR_INDEX_NAME, connectorId);

            clientWithOrigin.get(request, new ActionListener<>() {
                @Override
                public void onResponse(GetResponse response) {
                    final boolean connectorDoesNotExist = response.isExists() == false;

                    if (connectorDoesNotExist) {
                        onFailure(new ResourceNotFoundException("Connector with id '" + connectorId + "' does not exist."));
                        return;
                    }
                    try {
                        final Connector connector = Connector.fromXContentBytes(
                            response.getSourceAsBytesRef(),
                            connectorId,
                            XContentType.JSON
                        );

                        // Build the connector representation for sync job
                        final Connector syncJobConnector = new Connector.Builder().setConnectorId(connector.getConnectorId())
                            .setSyncJobFiltering(transformConnectorFilteringToSyncJobRepresentation(connector.getFiltering()))
                            .setIndexName(connector.getIndexName())
                            .setLanguage(connector.getLanguage())
                            .setPipeline(connector.getPipeline())
                            .setServiceType(connector.getServiceType())
                            .setConfiguration(connector.getConfiguration())
                            .build();

                        listener.onResponse(syncJobConnector);
                    } catch (Exception e) {
                        listener.onFailure(e);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Transforms the first {@link ConnectorFiltering} object from a list into a {@link FilteringRules} representation for a sync job.
     * This method specifically extracts the 'active' filtering rules from the first {@link ConnectorFiltering} object in the list,
     * if the list is neither null nor empty.
     *
     * @param connectorFiltering The list of {@link ConnectorFiltering} objects to be transformed. Can be null or empty.
     */
    FilteringRules transformConnectorFilteringToSyncJobRepresentation(List<ConnectorFiltering> connectorFiltering) {
        return Optional.ofNullable(connectorFiltering)
            .filter(list -> list.isEmpty() == false)
            .map(list -> list.get(0).getActive())
            .orElse(null);
    }

    /**
     * Sets the error for the {@link ConnectorSyncJob} in the underlying index.
     * This also sets the {@link ConnectorSyncStatus} to 'ERROR'.
     *
     * @param connectorSyncJobId     The id of the connector sync job object.
     * @param error                  The error to set.
     * @param listener               The action listener to invoke on response/failure.
     */
    public void updateConnectorSyncJobError(String connectorSyncJobId, String error, ActionListener<UpdateResponse> listener) {
        final UpdateRequest updateRequest = new UpdateRequest(CONNECTOR_SYNC_JOB_INDEX_NAME, connectorSyncJobId).setRefreshPolicy(
            WriteRequest.RefreshPolicy.IMMEDIATE
        )
            .doc(
                Map.of(
                    ConnectorSyncJob.ERROR_FIELD.getPreferredName(),
                    error,
                    ConnectorSyncJob.STATUS_FIELD.getPreferredName(),
                    ConnectorSyncStatus.ERROR
                )
            );

        try {
            clientWithOrigin.update(
                updateRequest,
                new DelegatingIndexNotFoundOrDocumentMissingActionListener<>(connectorSyncJobId, listener, (l, updateResponse) -> {
                    if (updateResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                        l.onFailure(new ResourceNotFoundException(connectorSyncJobId));
                        return;
                    }
                    l.onResponse(updateResponse);
                })
            );
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Listeners that checks failures for IndexNotFoundException and DocumentMissingException,
     * and transforms them in ResourceNotFoundException, invoking onFailure on the delegate listener.
     */
    static class DelegatingIndexNotFoundOrDocumentMissingActionListener<T, R> extends DelegatingActionListener<T, R> {

        private final BiConsumer<ActionListener<R>, T> bc;
        private final String connectorSyncJobId;

        DelegatingIndexNotFoundOrDocumentMissingActionListener(
            String connectorSyncJobId,
            ActionListener<R> delegate,
            BiConsumer<ActionListener<R>, T> bc
        ) {
            super(delegate);
            this.bc = bc;
            this.connectorSyncJobId = connectorSyncJobId;
        }

        @Override
        public void onResponse(T t) {
            bc.accept(delegate, t);
        }

        @Override
        public void onFailure(Exception e) {
            Throwable cause = ExceptionsHelper.unwrapCause(e);
            if (cause instanceof IndexNotFoundException || cause instanceof DocumentMissingException) {
                delegate.onFailure(new ResourceNotFoundException("connector sync job [" + connectorSyncJobId + "] not found"));
                return;
            }
            delegate.onFailure(e);
        }
    }
}
