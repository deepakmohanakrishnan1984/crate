/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport.task.elasticsearch;

import io.crate.Constants;
import io.crate.analyze.where.DocKeys;
import io.crate.concurrent.CompletableFutures;
import io.crate.data.InMemoryBatchIterator;
import io.crate.data.Row;
import io.crate.data.Row1;
import io.crate.data.RowConsumer;
import io.crate.exceptions.SQLExceptions;
import io.crate.executor.JobTask;
import io.crate.planner.node.dml.ESDelete;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.delete.TransportDeleteAction;
import org.elasticsearch.index.engine.VersionConflictEngineException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.crate.data.SentinelRow.SENTINEL;

public class ESDeleteTask extends JobTask {

    private final List<CompletableFuture<Long>> results;
    private final TransportDeleteAction deleteAction;
    private final List<DeleteRequest> requests;
    private final List<DeleteResponseListener> listeners;

    public ESDeleteTask(ESDelete esDelete, TransportDeleteAction deleteAction) {
        super(esDelete.jobId());

        results = new ArrayList<>(esDelete.getBulkSize());
        this.deleteAction = deleteAction;
        for (int i = 0; i < esDelete.getBulkSize(); i++) {
            CompletableFuture<Long> result = new CompletableFuture<>();
            results.add(result);
        }

        requests = new ArrayList<>(esDelete.docKeys().size());
        listeners = new ArrayList<>(esDelete.docKeys().size());
        int resultIdx = 0;
        for (DocKeys.DocKey docKey : esDelete.docKeys()) {
            DeleteRequest request = new DeleteRequest(
                ESGetTask.indexName(esDelete.tableInfo(), docKey.partitionValues().orElse(null)),
                Constants.DEFAULT_MAPPING_TYPE, docKey.id());
            request.routing(docKey.routing());
            if (docKey.version().isPresent()) {
                //noinspection OptionalGetWithoutIsPresent
                request.version(docKey.version().get());
            }
            requests.add(request);
            CompletableFuture<Long> result = results.get(esDelete.getItemToBulkIdx().get(resultIdx));
            listeners.add(new DeleteResponseListener(result));
            resultIdx++;
        }
        for (int i = 0; i < results.size(); i++) {
            if (!esDelete.getItemToBulkIdx().values().contains(i)) {
                results.get(i).complete(0L);
            }
        }
    }

    private void sendRequests() {
        assert requests.size() == listeners.size() : "number of requests and listeners must match";
        for (int i = 0; i < requests.size(); i++) {
            deleteAction.execute(requests.get(i), listeners.get(i));
        }
    }

    @Override
    public void execute(final RowConsumer consumer, Row parameters) {
        CompletableFuture<Long> result = results.get(0);
        try {
            sendRequests();
        } catch (Throwable throwable) {
            consumer.accept(null, throwable);
            return;
        }
        result.whenComplete((Long futureResult, Throwable t) -> {
            if (t == null) {
                consumer.accept(InMemoryBatchIterator.of(new Row1(futureResult), SENTINEL), null);
            } else {
                consumer.accept(null, t);
            }
        });
    }

    @Override
    public final List<CompletableFuture<Long>> executeBulk() {
        try {
            sendRequests();
        } catch (Throwable throwable) {
            return Collections.singletonList(CompletableFutures.failedFuture(throwable));
        }
        return results;
    }

    private static class DeleteResponseListener implements ActionListener<DeleteResponse> {

        private final CompletableFuture<Long> result;

        DeleteResponseListener(CompletableFuture<Long> result) {
            this.result = result;
        }

        @Override
        public void onResponse(DeleteResponse response) {
            if (response.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                result.complete(0L);
            } else {
                result.complete(1L);
            }
        }

        @Override
        public void onFailure(Exception e) {
            Throwable t = SQLExceptions.unwrap(e); // unwrap to get rid of RemoteTransportException
            if (t instanceof VersionConflictEngineException) {
                // treat version conflict as rows affected = 0
                result.complete(0L);
            } else {
                result.completeExceptionally(e);
            }
        }
    }
}
