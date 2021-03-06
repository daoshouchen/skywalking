/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.analyzer.provider.meter.process;

import groovy.lang.GroovyShell;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig;
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.Scope;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AvgHistogramPercentileFunction;
import org.apache.skywalking.oap.server.core.analysis.meter.function.BucketedValues;

import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Help to build meter into Meter System.
 */
@Slf4j
public class MeterBuilder {

    private final MeterConfig config;
    private final MeterSystem meterSystem;

    /**
     * Current meter has init finished.
     */
    private final AtomicBoolean init = new AtomicBoolean(false);

    public MeterBuilder(MeterConfig config, MeterSystem meterSystem) {
        this.config = config;
        this.meterSystem = meterSystem;
    }

    /**
     * Init current meter to {@link MeterSystem}
     */
    public void initMeter() {
        meterSystem.create(
            formatMeterName(config.getName()), config.getMeter().getOperation(), config.getScope().getType());
        init.set(true);
    }

    /**
     * Get current meter has been init
     */
    public boolean hasInit() {
        return init.get();
    }

    /**
     * Build the meter and send to Meter System.
     */
    public void buildAndSend(MeterProcessor processor, GroovyShell shell) {
        final MeterEntity entity = buildEntity(processor, config.getScope());
        final String metricsName = formatMeterName(config.getName());

        // Parsing meter value
        EvalMultipleData values;
        try {
            values = (EvalMultipleData) shell.evaluate(config.getMeter().getValue());
        } catch (Exception e) {
            log.warn("Building {} meter value failure", config.getName(), e);
            return;
        }

        switch (config.getMeter().getOperation()) {
            case "avg":
                final EvalData combinedSingleAvgData = values.combineAsSingleData();
                if (combinedSingleAvgData instanceof EvalSingleData) {
                    AcceptableValue<Long> avgValue = meterSystem.buildMetrics(metricsName, Long.class);
                    avgValue.accept(entity, (long) ((EvalSingleData) combinedSingleAvgData).getValue());
                    avgValue.setTimeBucket(TimeBucket.getMinuteTimeBucket(processor.timestamp()));
                    meterSystem.doStreamingCalculation(avgValue);
                } else {
                    log.warn("avg function not support histogram value, please check meter:{}", combinedSingleAvgData.getName());
                }
                break;
            case "avgHistogram":
            case "avgHistogramPercentile":
                final EvalData combinedHistogramData = values.combineAsSingleData();
                if (combinedHistogramData instanceof EvalHistogramData) {
                    final EvalHistogramData histogram = (EvalHistogramData) combinedHistogramData;
                    long[] buckets = new long[histogram.getBuckets().size()];
                    long[] bucketValues = new long[histogram.getBuckets().size()];
                    int i = 0;
                    for (Map.Entry<Double, Long> entry : histogram.getBuckets().entrySet()) {
                        buckets[i] = entry.getKey().intValue();
                        bucketValues[i] = entry.getValue();
                        i++;
                    }

                    if (config.getMeter().getOperation().equals("avgHistogram")) {
                        AcceptableValue<BucketedValues> avgHistogramValue = meterSystem.buildMetrics(metricsName, BucketedValues.class);
                        avgHistogramValue.accept(entity, new BucketedValues(buckets, bucketValues));
                        avgHistogramValue.setTimeBucket(TimeBucket.getMinuteTimeBucket(processor.timestamp()));
                        meterSystem.doStreamingCalculation(avgHistogramValue);
                    } else {
                        final AcceptableValue<AvgHistogramPercentileFunction.AvgPercentileArgument> percentileValue =
                            meterSystem.buildMetrics(metricsName, AvgHistogramPercentileFunction.AvgPercentileArgument.class);
                        percentileValue.accept(entity, new AvgHistogramPercentileFunction.AvgPercentileArgument(new BucketedValues(buckets, bucketValues),
                            config.getMeter().getPercentile().stream().mapToInt(Integer::intValue).toArray()));
                        percentileValue.setTimeBucket(TimeBucket.getMinuteTimeBucket(processor.timestamp()));
                        meterSystem.doStreamingCalculation(percentileValue);
                    }
                } else {
                    log.warn(config.getMeter().getOperation() + " function not support single value, please check meter:{}", combinedHistogramData.getName());
                }
                break;
            default:
                log.warn("Cannot support function:{}", config.getMeter().getOperation());
        }
    }

    /**
     * Build meter entity
     */
    public MeterEntity buildEntity(MeterProcessor processor, Scope scope) {
        switch (scope.getType()) {
            case SERVICE:
                return MeterEntity.newService(processor.service());
            case SERVICE_INSTANCE:
                return MeterEntity.newServiceInstance(processor.service(), processor.serviceInstance());
            case ENDPOINT:
                return MeterEntity.newEndpoint(processor.service(), config.getScope().getEndpoint());
            default:
                throw new UnsupportedOperationException("Unsupported scope type:" + scope.getType());
        }
    }

    String formatMeterName(String meterName) {
        StringJoiner metricName = new StringJoiner("_");
        metricName.add("meter").add(meterName);
        return metricName.toString();
    }

}
