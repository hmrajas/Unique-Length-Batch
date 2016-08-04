/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.extension.siddhi.window.uniquelengthbatch;

import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.event.ComplexEvent;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.executor.ConstantExpressionExecutor;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.processor.Processor;
import org.wso2.siddhi.core.query.processor.stream.window.FindableProcessor;
import org.wso2.siddhi.core.query.processor.stream.window.WindowProcessor;
import org.wso2.siddhi.core.table.EventTable;
import org.wso2.siddhi.core.util.collection.operator.Finder;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaStateHolder;
import org.wso2.siddhi.core.util.parser.OperatorParser;
import org.wso2.siddhi.query.api.exception.ExecutionPlanValidationException;
import org.wso2.siddhi.query.api.expression.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UniqueLengthBatchWindowProcessor extends WindowProcessor implements FindableProcessor {

    private int length;
    private int count = 0;
    private ComplexEventChunk<StreamEvent> currentEventChunk = new ComplexEventChunk<StreamEvent>(false);
    private ComplexEventChunk<StreamEvent> eventsToBeExpired = null;
    private ExecutionPlanContext executionPlanContext;
    private StreamEvent resetEvent = null;
    private VariableExpressionExecutor[] variableExpressionExecutors;
    private ConcurrentHashMap<String, StreamEvent> map = new ConcurrentHashMap<>();

    @Override
    protected void init(ExpressionExecutor[] attributeExpressionExecutors, ExecutionPlanContext executionPlanContext) {
        this.executionPlanContext = executionPlanContext;
        this.variableExpressionExecutors = new VariableExpressionExecutor[attributeExpressionExecutors.length - 1];
        this.eventsToBeExpired = new ComplexEventChunk<>(false);
        if (attributeExpressionExecutors.length == 2) {
            this.variableExpressionExecutors[0] = (VariableExpressionExecutor) attributeExpressionExecutors[0];
            this.length = (Integer) (((ConstantExpressionExecutor) attributeExpressionExecutors[1]).getValue());
        } else {
            throw new ExecutionPlanValidationException("Unique Length batch window should only have Two parameter (<int> windowLength), but found " + attributeExpressionExecutors.length + " input attributes");
        }
    }

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor, StreamEventCloner streamEventCloner) {
        List<ComplexEventChunk<StreamEvent>> streamEventChunks = new ArrayList<ComplexEventChunk<StreamEvent>>();
        synchronized (this) {
            ComplexEventChunk<StreamEvent> outputStreamEventChunk = new ComplexEventChunk<StreamEvent>(true);
            long currentTime = executionPlanContext.getTimestampGenerator().currentTime();
            StreamEvent oldEvent;
            while (streamEventChunk.hasNext()) {
                StreamEvent streamEvent = streamEventChunk.next();
                StreamEvent clonedStreamEvent = streamEventCloner.copyStreamEvent(streamEvent);
                currentEventChunk.add(clonedStreamEvent);
                count++;
                if (count == length) {
                    if (eventsToBeExpired.getFirst() != null) {
                        while (eventsToBeExpired.hasNext()) {
                            StreamEvent expiredEvent = eventsToBeExpired.next();
                            expiredEvent.setTimestamp(currentTime);
                        }
                        outputStreamEventChunk.add(eventsToBeExpired.getFirst());
                    }
                    if (eventsToBeExpired != null) {
                        eventsToBeExpired.clear();
                    }
                    if (currentEventChunk.getFirst() != null) {
                        // add reset event in front of current events
                        outputStreamEventChunk.add(resetEvent);
                        resetEvent = null;
                        if (eventsToBeExpired != null) {
                            currentEventChunk.reset();
                            while (currentEventChunk.hasNext()) {
                                StreamEvent streamEvent1 = currentEventChunk.next();
                                StreamEvent toExpireEvent = streamEventCloner.copyStreamEvent(streamEvent1);
                                toExpireEvent.setType(StreamEvent.Type.EXPIRED);
                                StreamEvent eventClonedForMap = streamEventCloner.copyStreamEvent(streamEvent1);
                                eventClonedForMap.setType(StreamEvent.Type.EXPIRED);
                                oldEvent = map.put(generateKey(eventClonedForMap), eventClonedForMap);
                                eventsToBeExpired.add(toExpireEvent);
                                eventsToBeExpired.reset();
                                while (eventsToBeExpired.hasNext()) {
                                    StreamEvent expiredEvent = eventsToBeExpired.next();
                                    if (oldEvent != null) {
                                        if (expiredEvent.equals(oldEvent)) {
                                            eventsToBeExpired.remove();
//                                            currentEventChunk.remove();
                                            currentEventChunk.insertBeforeCurrent(oldEvent);
                                            oldEvent.setTimestamp(currentTime);
                                            eventsToBeExpired.reset();
                                        }
                                    }
                                }
                            }
                        }
                        resetEvent = streamEventCloner.copyStreamEvent(currentEventChunk.getFirst());
                        resetEvent.setType(ComplexEvent.Type.RESET);
                        outputStreamEventChunk.add(currentEventChunk.getFirst());
                    }
                    currentEventChunk.clear();
                    count = 0;
                    if (outputStreamEventChunk.getFirst() != null) {
                        streamEventChunks.add(outputStreamEventChunk);
                    }
                }
            }
        }
        for (ComplexEventChunk<StreamEvent> outputStreamEventChunk : streamEventChunks) {
            nextProcessor.process(outputStreamEventChunk);
        }
    }

    @Override
    public void start() {
        //Do nothing
    }

    @Override
    public void stop() {
        //Do nothing
    }

    @Override
    public Object[] currentState() {
        if (eventsToBeExpired != null) {
            return new Object[]{currentEventChunk.getFirst(), eventsToBeExpired.getFirst(), count, resetEvent};
        } else {
            return new Object[]{currentEventChunk.getFirst(), count, resetEvent};
        }
    }

    @Override
    public void restoreState(Object[] state) {
        if (state.length > 2) {
            currentEventChunk.clear();
            currentEventChunk.add((StreamEvent) state[0]);
            eventsToBeExpired.clear();
            eventsToBeExpired.add((StreamEvent) state[1]);
            count = (Integer) state[2];
            resetEvent = (StreamEvent) state[3];
        } else {
            currentEventChunk.clear();
            currentEventChunk.add((StreamEvent) state[0]);
            count = (Integer) state[1];
            resetEvent = (StreamEvent) state[2];
        }
    }

    @Override
    public synchronized StreamEvent find(StateEvent matchingEvent, Finder finder) {
        return finder.find(matchingEvent, map.values(), streamEventCloner);
    }

    @Override
    public Finder constructFinder(Expression expression, MatchingMetaStateHolder matchingMetaStateHolder, ExecutionPlanContext executionPlanContext,
                                  List<VariableExpressionExecutor> variableExpressionExecutors, Map<String, EventTable> eventTableMap) {
        if (eventsToBeExpired == null) {
            eventsToBeExpired = new ComplexEventChunk<StreamEvent>(false);
        }
        return OperatorParser.constructOperator(map.values(), expression, matchingMetaStateHolder, executionPlanContext, variableExpressionExecutors, eventTableMap);
    }

    /**
     * Used to generate key in map to get the old event for current event. It will map key which we give as unique
     * attribute with the event
     *
     * @param event the stream event that need to be processed
     */
    private String generateKey(StreamEvent event) {
        StringBuilder stringBuilder = new StringBuilder();
        for (VariableExpressionExecutor executor : variableExpressionExecutors) {
            stringBuilder.append(event.getAttribute(executor.getPosition()));
        }
        return stringBuilder.toString();
    }
}