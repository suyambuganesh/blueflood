/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.service;

import com.codahale.metrics.Timer;
import com.netflix.astyanax.model.ColumnFamily;
import com.rackspacecloud.blueflood.cache.MetadataCache;
import com.rackspacecloud.blueflood.io.AstyanaxIO;
import com.rackspacecloud.blueflood.io.AstyanaxReader;
import com.rackspacecloud.blueflood.io.AstyanaxWriter;
import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.types.*;
import com.rackspacecloud.blueflood.utils.Metrics;
import com.rackspacecloud.blueflood.utils.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/** rolls up data into one data point, inserts that data point. */
class RollupRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(RollupRunnable.class);

    private static final Timer calcTimer = Metrics.timer(RollupRunnable.class, "Read And Calculate Rollup");
    private static final Timer writeTimer = Metrics.timer(RollupRunnable.class, "Write Rollup");
    private static final MetadataCache rollupTypeCache = MetadataCache.createLoadingCacheInstance(
            new TimeValue(48, TimeUnit.HOURS), // todo: need a good default expiration here.
            Configuration.getInstance().getIntegerProperty(CoreConfig.MAX_ROLLUP_THREADS));
    
    private final RollupContext rollupContext;
    private final RollupExecutionContext executionContext;
    private final long startWait;
    
    RollupRunnable(RollupExecutionContext executionContext, RollupContext rollupContext) {
        this.executionContext = executionContext;
        this.rollupContext = rollupContext;
        startWait = System.currentTimeMillis();
    }
    
    public void run() {
        // done waiting.
        rollupContext.getWaitHist().update(System.currentTimeMillis() - startWait);

        if (log.isDebugEnabled()) {
            log.debug("Executing rollup from {} for {} {}", new Object[] {
                    rollupContext.getSourceGranularity().shortName(),
                    rollupContext.getRange().toString(),
                    rollupContext.getLocator()});
        }
        
        // start timing this action.
        Timer.Context timerContext = rollupContext.getExecuteTimer().time();
        try {
            Timer.Context calcrollupContext = calcTimer.time();

            // Read data and compute rollup
            Points input;
            Rollup rollup = null;
            ColumnFamily<Locator, Long> srcCF;
            ColumnFamily<Locator, Long> dstCF;
            StatType statType = StatType.fromString((String)rollupTypeCache.get(rollupContext.getLocator(), StatType.CACHE_KEY));
            Class<? extends Rollup> rollupClass = RollupRunnable.classOf(statType, rollupContext.getSourceGranularity());
            
            // short circuit for sets, which are not rolled up, since we don't want to waste time reading them from
            // full res.
            if (statType == StatType.SET)
                return;
            
            try {
                // first, get the points.
                if (rollupContext.getSourceGranularity() == Granularity.FULL) {    
                    srcCF = statType == StatType.UNKNOWN
                            ? AstyanaxIO.CF_METRICS_FULL
                            : AstyanaxIO.CF_METRICS_PREAGGREGATED_FULL;
                    input = AstyanaxReader.getInstance().getDataToRoll(rollupClass,
                            rollupContext.getLocator(), rollupContext.getRange(), srcCF);
                } else {
                    srcCF = AstyanaxIO.getColumnFamilyMapper().get(rollupContext.getSourceGranularity());
                    input = AstyanaxReader.getInstance().getBasicRollupDataToRoll(
                            rollupContext.getLocator(),
                            rollupContext.getRange(),
                            srcCF);
                }
                
                dstCF = statType == StatType.UNKNOWN
                        ? AstyanaxIO.getColumnFamilyMapper().get(rollupContext.getSourceGranularity().coarser())
                        : AstyanaxIO.getPreagColumnFamilyMapper().get(rollupContext.getSourceGranularity().coarser());
                
                // next, compute the rollup.
                rollup =  RollupRunnable.getRollupComputer(statType, rollupContext.getSourceGranularity()).compute(input);
                
            } finally {
                calcrollupContext.stop();
            }

            // now save the new rollup.
            Timer.Context writerollupContext = writeTimer.time();
            try {
                AstyanaxWriter.getInstance().insertRollup(
                        rollupContext.getLocator(),
                        rollupContext.getRange().getStart(),
                        rollup,
                        dstCF);
            } finally {
                writerollupContext.stop();
            }

            RollupService.lastRollupTime.set(System.currentTimeMillis());
        } catch (Throwable th) {
            log.error("Rollup failed; Locator : ", rollupContext.getLocator()
                    + ", Source Granularity: " + rollupContext.getSourceGranularity().name());
        } finally {
            executionContext.decrement();
            timerContext.stop();
        }
    }
    
    // derive the class of the type. This will be used to determine which serializer is used.
    public static Class<? extends Rollup> classOf(StatType type, Granularity gran) {
        if (type == StatType.COUNTER)
            return CounterRollup.class;
        else if (type == StatType.TIMER)
            return TimerRollup.class;
        else if (type == StatType.SET)
            return SetRollup.class;
        else if (type == StatType.GAUGE)
            return GaugeRollup.class;
        else if (type == StatType.UNKNOWN && gran == Granularity.FULL)
            return SimpleNumber.class;
        else if (type == StatType.UNKNOWN && gran != Granularity.FULL)
            return BasicRollup.class;
        else
            throw new IllegalArgumentException(String.format("Unexpected type/gran combination: %s, %s", type, gran));
    }
    
    // dertmine which Type to use for serialization.
    public static Rollup.Type getRollupComputer(StatType srcType, Granularity srcGran) {
        switch (srcType) {
            case COUNTER:
                return Rollup.CounterFromCounter;
            case TIMER:
                return Rollup.TimerFromTimer;
            case GAUGE:
                return Rollup.GaugeFromGauge;
            case UNKNOWN:
                return srcGran == Granularity.FULL ? Rollup.BasicFromRaw : Rollup.BasicFromBasic;
            case SET:
                // we do not roll sets up.
            default:
                break;
        }
        throw new IllegalArgumentException(String.format("Cannot compute rollups for %s from %s", srcType.name(), srcGran.shortName()));
    }
}
