/*
 * Copyright (c) 2005 - 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.wso2.carbon.event.receiver.core.internal;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.databridge.commons.Event;
import org.wso2.carbon.databridge.commons.StreamDefinition;
import org.wso2.carbon.event.input.adapter.core.InputEventAdapterSubscription;
import org.wso2.carbon.event.input.adapter.core.exception.InputEventAdapterException;
import org.wso2.carbon.event.input.adapter.core.exception.InputEventAdapterRuntimeException;
import org.wso2.carbon.event.processor.manager.core.EventManagementUtil;
import org.wso2.carbon.event.processor.manager.core.EventSync;
import org.wso2.carbon.event.processor.manager.core.Manager;
import org.wso2.carbon.event.processor.manager.core.config.DistributedConfiguration;
import org.wso2.carbon.event.processor.manager.core.config.HAConfiguration;
import org.wso2.carbon.event.processor.manager.core.config.Mode;
import org.wso2.carbon.event.receiver.core.InputMapper;
import org.wso2.carbon.event.receiver.core.config.EventReceiverConfiguration;
import org.wso2.carbon.event.receiver.core.config.EventReceiverConstants;
import org.wso2.carbon.event.receiver.core.exception.EventReceiverConfigurationException;
import org.wso2.carbon.event.receiver.core.exception.EventReceiverProcessingException;
import org.wso2.carbon.event.receiver.core.internal.ds.EventReceiverServiceValueHolder;
import org.wso2.carbon.event.receiver.core.internal.management.AbstractInputEventDispatcher;
import org.wso2.carbon.event.receiver.core.internal.management.InputEventDispatcher;
import org.wso2.carbon.event.receiver.core.internal.management.QueueInputEventDispatcher;
import org.wso2.carbon.event.receiver.core.internal.util.EventReceiverUtil;
import org.wso2.carbon.event.receiver.core.internal.util.helper.EventReceiverConfigurationHelper;
import org.wso2.carbon.event.stream.core.EventProducer;
import org.wso2.carbon.event.stream.core.EventProducerCallback;


import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

public class EventReceiver implements EventProducer {

    private static final Log log = LogFactory.getLog(EventReceiver.class);
    private boolean isEventDuplicatedInCluster;
    private boolean traceEnabled = false;
    private boolean statisticsEnabled = false;
    private boolean customMappingEnabled = false;
    private boolean isWorkerNode = false;
    private boolean sufficientToSend = false;
    private Log trace = LogFactory.getLog(EventReceiverConstants.EVENT_TRACE_LOGGER);
    private EventReceiverConfiguration eventReceiverConfiguration = null;
    private StreamDefinition exportedStreamDefinition;
    private InputMapper inputMapper = null;
    private String beforeTracerPrefix;
    private String afterTracerPrefix;
    private AbstractInputEventDispatcher inputEventDispatcher;
    private Mode mode;

//    private volatile AtomicLong totalLatency = new AtomicLong();
//    private volatile AtomicLong totalEvents = new AtomicLong();
//    private double elapseCount = 10000;
//    private long tempstartTime = System.currentTimeMillis();

    public EventReceiver(EventReceiverConfiguration eventReceiverConfiguration,
                         StreamDefinition exportedStreamDefinition, Mode mode)
            throws EventReceiverConfigurationException {
        this.eventReceiverConfiguration = eventReceiverConfiguration;
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();

        if (this.eventReceiverConfiguration != null) {
            this.traceEnabled = eventReceiverConfiguration.isTraceEnabled();
            this.statisticsEnabled = eventReceiverConfiguration.isStatisticsEnabled() &&
                    EventReceiverServiceValueHolder.isGlobalStatisticsEnabled();
            this.customMappingEnabled = eventReceiverConfiguration.getInputMapping().isCustomMappingEnabled();
            String mappingType = this.eventReceiverConfiguration.getInputMapping().getMappingType();
            this.inputMapper = EventReceiverServiceValueHolder.getMappingFactoryMap().get(mappingType)
                    .constructInputMapper(this.eventReceiverConfiguration, exportedStreamDefinition);
            String metricId = EventReceiverConstants.METRICS_ROOT + EventReceiverConstants.METRIC_DELIMITER +
                    EventReceiverConstants.METRICS_EVENT_RECEIVERS + EventReceiverConstants.METRIC_AGGREGATE_ANNOTATION +
                    EventReceiverConstants.METRIC_DELIMITER + eventReceiverConfiguration.getEventReceiverName() +
                    EventReceiverConstants.METRIC_DELIMITER + EventReceiverConstants.METRICS_RECEIVED_EVENTS;

            // The input mapper should not be null. For configurations where custom mapping is disabled,
            // an input mapper would be created without the mapping details.
            if (this.inputMapper != null) {
                if (customMappingEnabled) {
                    EventReceiverConfigurationHelper.validateExportedStream(
                            eventReceiverConfiguration, exportedStreamDefinition, this.inputMapper);
                }
                this.exportedStreamDefinition = exportedStreamDefinition;
            } else {
                throw new EventReceiverConfigurationException("Could not create input mapper for mapping type "
                        + mappingType + " for event receiver :" + eventReceiverConfiguration.getEventReceiverName());
            }

            // Initialize tracer and statistics.
            if (statisticsEnabled) {
            }
            if (traceEnabled) {
                this.beforeTracerPrefix = "TenantId : " + tenantId + ", " + EventReceiverConstants.EVENT_RECEIVER +
                        " : " + eventReceiverConfiguration.getEventReceiverName() + ", before processing " +
                        System.getProperty("line.separator");
                this.afterTracerPrefix = "TenantId : " + tenantId + ", " + EventReceiverConstants.EVENT_RECEIVER +
                        " : " + eventReceiverConfiguration.getEventReceiverName() + ", " +
                        EventReceiverConstants.EVENT_STREAM + " : " +
                        EventReceiverUtil.getExportedStreamIdFrom(eventReceiverConfiguration) + ", after processing " +
                        System.getProperty("line.separator");
            }

            String inputEventAdapterName = eventReceiverConfiguration.getFromAdapterConfiguration().getName();
            try {
                InputEventAdapterSubscription inputEventAdapterSubscription;
                if (this.customMappingEnabled) {
                    inputEventAdapterSubscription = new MappedEventSubscription();
                } else {
                    inputEventAdapterSubscription = new TypedEventSubscription();
                }
                EventReceiverServiceValueHolder.getInputEventAdapterService().create(
                        eventReceiverConfiguration.getFromAdapterConfiguration(), inputEventAdapterSubscription);

                isEventDuplicatedInCluster = EventReceiverServiceValueHolder.getInputEventAdapterService()
                        .isEventDuplicatedInCluster(eventReceiverConfiguration.getFromAdapterConfiguration().getName());

                DistributedConfiguration distributedConfiguration = EventReceiverServiceValueHolder
                        .getEventManagementService().getManagementModeInfo().getDistributedConfiguration();
                if (distributedConfiguration != null) {
                    this.isWorkerNode = distributedConfiguration.isWorkerNode();
                }
                sufficientToSend = mode == Mode.SingleNode;

            } catch (InputEventAdapterException e) {
                throw new EventReceiverConfigurationException("Cannot subscribe to input event adapter :" +
                        inputEventAdapterName + ", error in configuration. " + e.getMessage(), e);
            } catch (InputEventAdapterRuntimeException e) {
                throw new EventReceiverProcessingException("Cannot subscribe to input event adapter :" +
                        inputEventAdapterName + ", error while connecting by adapter. " + e.getMessage(), e);
            }
            inputEventDispatcher = new InputEventDispatcher();
        }
    }

    /**
     * Returns the stream definition that is exported by this event receiver.
     * This stream definition will available to any object that consumes the event receiver service
     * (e.g. EventProcessors)
     *
     * @return the {@link StreamDefinition} of the stream that will be
     * sending out events from this event receiver
     */
    public StreamDefinition getExportedStreamDefinition() {
        return exportedStreamDefinition;
    }

    /**
     * Returns the event receiver configuration associated with this event receiver
     *
     * @return the {@link EventReceiverConfiguration} instance
     */
    public EventReceiverConfiguration getEventReceiverConfiguration() {
        return this.eventReceiverConfiguration;
    }

    protected void processMappedEvent(Object object) {
        if (traceEnabled) {
            trace.info(beforeTracerPrefix + object.toString());
        }

        if (object instanceof List) {
            for (Object obj : (List) object) {
                try {
                    processMappedEvent(obj);
                } catch (EventReceiverProcessingException e) {
                    log.error("Dropping event. Error processing event : ", e);
                }
            }
        } else {
            try {
                Object convertedEvent = this.inputMapper.convertToMappedInputEvent(object);
                if (convertedEvent != null) {
                    if (convertedEvent instanceof Event[]) {
                        Event[] arrayOfEvents = (Event[]) convertedEvent;
                        for (Event event : arrayOfEvents) {
                            if (event != null) {
                                sendEvent(event);
                            }
                        }
                    } else {
                        sendEvent((Event) convertedEvent);
                    }
                } else {
                    log.warn("Dropping the empty/null event, Event does not match with mapping");
                }
            } catch (EventReceiverProcessingException e) {
                log.error("Dropping event. Error processing event : ", e);
            } catch (RuntimeException e) {
                log.error("Dropping event. Unexpected error while processing event : " + e.getMessage(), e);
            }
        }

    }

    protected void processTypedEvent(Object obj) {
        if (traceEnabled) {
            trace.info(beforeTracerPrefix + obj.toString());
        }
        if (obj instanceof List) {
            for (Object object : (List) obj) {
                try {
                    processTypedEvent(object);
                } catch (EventReceiverProcessingException e) {
                    log.error("Dropping event. Error processing event: " + e.getMessage(), e);
                }
            }
        } else {
            try {
                Object convertedEvent = this.inputMapper.convertToTypedInputEvent(obj);
                if (convertedEvent != null) {
                    if (convertedEvent instanceof Event[]) {
                        Event[] arrayOfEvents = (Event[]) convertedEvent;
                        for (Event event : arrayOfEvents) {
                            if (event != null) {
                                sendEvent(event);
                            }
                        }
                    } else {
                        sendEvent((Event) convertedEvent);
                    }
                }
            } catch (EventReceiverProcessingException e) {
                log.error("Dropping event. Error processing event: " + e.getMessage(), e);
            }
        }
    }

    protected void sendEvent(Event event) {
        if (traceEnabled) {
            trace.info(afterTracerPrefix + event);
        }
        if (statisticsEnabled) {
        }
//
//        long timestamp = event.getTimeStamp();
//        long currentTimestamp = System.currentTimeMillis();
//        event.setTimeStamp(currentTimestamp);
//        long eventLatency = currentTimestamp - timestamp;
//        totalLatency.addAndGet(eventLatency);
//        totalEvents.incrementAndGet();
//        if (totalEvents.get() % elapseCount == 0) {
//            double avgLatency = (totalLatency.get() * 1.0) / elapseCount;
//            double throughput = ((elapseCount * 1.0) / (currentTimestamp - tempstartTime + 0.0)) * 1000;
//
//            log.info("Received " + elapseCount + " events with a throughput of " + throughput +
//                    " events per second || and latency: " + avgLatency);
//            tempstartTime = currentTimestamp;
//            totalLatency.set(0);
//        }


        // In distributed mode if events are duplicated in cluster, send event only if the node is receiver coordinator.
        // Also do not send if this is a manager node.
        if (sufficientToSend
                || EventReceiverServiceValueHolder.getCarbonEventReceiverManagementService().isReceiverCoordinator()) {
            this.inputEventDispatcher.onEvent(event);
        }
    }

    public AbstractInputEventDispatcher getInputEventDispatcher() {
        return inputEventDispatcher;
    }

/*
    protected void defineEventStream(Object definition) throws EventReceiverConfigurationException {
        if (log.isDebugEnabled()) {
            log.debug("EventReceiver: " + eventReceiverConfiguration.getEventReceiverName() +
                    ", notifying event definition addition :" + definition.toString());
        }
        if (definition instanceof StreamDefinition) {
            StreamDefinition inputStreamDefinition = (StreamDefinition) definition;
            String mappingType = eventReceiverConfiguration.getInputMapping().getMappingType();
            this.inputMapper = EventReceiverServiceValueHolder.getMappingFactoryMap().get(mappingType)
                    .constructInputMapper(eventReceiverConfiguration, exportedStreamDefinition);
        }
    }

    protected void removeEventStream(Object definition) {
        if (log.isDebugEnabled()) {
            log.debug("EventReceiver: " + eventReceiverConfiguration.getEventReceiverName() +
                    ", notifying event definition addition :" + definition.toString());
        }
        this.inputMapper = null;
    }
*/

    @Override
    public String getStreamId() {
        return exportedStreamDefinition.getStreamId();
    }

    @Override
    public void setCallBack(EventProducerCallback callBack) {
        this.inputEventDispatcher.setCallBack(callBack);
    }

    public void destroy() {
        EventReceiverServiceValueHolder.getInputEventAdapterService()
                .destroy(eventReceiverConfiguration.getFromAdapterConfiguration().getName());
    }

    public boolean isEventDuplicatedInCluster() {
        return isEventDuplicatedInCluster;
    }

    private class MappedEventSubscription implements InputEventAdapterSubscription {
        @Override
        public void onEvent(Object o) {
            processMappedEvent(o);
        }
    }

    private class TypedEventSubscription implements InputEventAdapterSubscription {
        @Override
        public void onEvent(Object o) {
            processTypedEvent(o);
        }
    }
}