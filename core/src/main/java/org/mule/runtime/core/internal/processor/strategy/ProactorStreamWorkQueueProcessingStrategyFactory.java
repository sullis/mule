/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.BLOCKING;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.CPU_INTENSIVE;
import static org.mule.runtime.core.internal.context.thread.notification.ThreadNotificationLogger.THREAD_NOTIFICATION_LOGGER_CONTEXT_KEY;
import static reactor.core.publisher.Flux.just;
import static reactor.core.scheduler.Schedulers.fromExecutorService;

import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.internal.context.thread.notification.ThreadLoggingExecutorServiceDecorator;

import java.util.function.Consumer;
import java.util.function.Supplier;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * Creates {@link ReactorProcessingStrategyFactory.ReactorProcessingStrategy} instance that implements the proactor pattern by
 * de-multiplexing incoming events onto a single event-loop using a ring-buffer and then using using the
 * {@link SchedulerService#cpuLightScheduler()} to process these events from the ring-buffer. In contrast to the
 * {@link ReactorStreamProcessingStrategy} the proactor pattern treats {@link ProcessingType#CPU_INTENSIVE} and
 * {@link ProcessingType#BLOCKING} processors differently and schedules there execution on dedicated
 * {@link SchedulerService#cpuIntensiveScheduler()} and {@link SchedulerService#ioScheduler()} ()} schedulers.
 * <p/>
 * This processing strategy is not suitable for transactional flows and will fail if used with an active transaction.
 *
 * @since 4.0
 */
public class ProactorStreamWorkQueueProcessingStrategyFactory extends ReactorStreamProcessingStrategyFactory {

  @Override
  public ProcessingStrategy create(MuleContext muleContext, String schedulersNamePrefix) {
    return new ProactorStreamWorkQueueProcessingStrategy(getRingBufferSchedulerSupplier(muleContext, schedulersNamePrefix),
                                                         getBufferSize(),
                                                         getSubscriberCount(),
                                                         getWaitStrategy(),
                                                         getCpuLightSchedulerSupplier(muleContext, schedulersNamePrefix),
                                                         () -> muleContext.getSchedulerService()
                                                             .ioScheduler(muleContext.getSchedulerBaseConfig()
                                                                 .withName(schedulersNamePrefix + "." + BLOCKING.name())),
                                                         () -> muleContext.getSchedulerService()
                                                             .cpuIntensiveScheduler(muleContext.getSchedulerBaseConfig()
                                                                 .withName(schedulersNamePrefix + "." + CPU_INTENSIVE.name())),
                                                         resolveParallelism(),
                                                         getMaxConcurrency(),
                                                         isMaxConcurrencyEagerCheck(),
                                                         muleContext.getConfiguration().isThreadLoggingEnabled());
  }

  @Override
  protected int resolveParallelism() {
    if (getMaxConcurrency() == Integer.MAX_VALUE) {
      return max(CORES / getSubscriberCount(), 1);
    } else {
      // Resolve maximum factor of max concurrency that is less than number of cores in order to respect maxConcurrency more
      // closely.
      return min(CORES, maxFactor(Float.max((float) getMaxConcurrency() / getSubscriberCount(), 1)));
    }
  }

  private int maxFactor(float test) {
    if (test % 0 == 0) {
      for (int i = CORES; i > 1; i--)
        if (test % i == 0) {
          return i;
        }
    }
    return 1;
  }

  @Override
  public Class<? extends ProcessingStrategy> getProcessingStrategyType() {
    return ProactorStreamWorkQueueProcessingStrategy.class;
  }

  static class ProactorStreamWorkQueueProcessingStrategy extends ProactorStreamProcessingStrategy {

    private final boolean isThreadLoggingEnabled;

    public ProactorStreamWorkQueueProcessingStrategy(Supplier<Scheduler> ringBufferSchedulerSupplier,
                                                     int bufferSize,
                                                     int subscriberCount,
                                                     String waitStrategy,
                                                     Supplier<Scheduler> cpuLightSchedulerSupplier,
                                                     Supplier<Scheduler> blockingSchedulerSupplier,
                                                     Supplier<Scheduler> cpuIntensiveSchedulerSupplier,
                                                     int parallelism,
                                                     int maxConcurrency, boolean maxConcurrencyEagerCheck,
                                                     boolean isThreadLoggingEnabled)

    {
      super(ringBufferSchedulerSupplier, bufferSize, subscriberCount, waitStrategy, cpuLightSchedulerSupplier,
            blockingSchedulerSupplier, cpuIntensiveSchedulerSupplier, parallelism, maxConcurrency,
            maxConcurrencyEagerCheck);
      this.isThreadLoggingEnabled = isThreadLoggingEnabled;
    }

    public ProactorStreamWorkQueueProcessingStrategy(Supplier<Scheduler> ringBufferSchedulerSupplier,
                                                     int bufferSize,
                                                     int subscriberCount,
                                                     String waitStrategy,
                                                     Supplier<Scheduler> cpuLightSchedulerSupplier,
                                                     Supplier<Scheduler> blockingSchedulerSupplier,
                                                     Supplier<Scheduler> cpuIntensiveSchedulerSupplier,
                                                     int parallelism,
                                                     int maxConcurrency, boolean maxConcurrencyEagerCheck)

    {
      this(ringBufferSchedulerSupplier, bufferSize, subscriberCount, waitStrategy, cpuLightSchedulerSupplier,
           blockingSchedulerSupplier, cpuIntensiveSchedulerSupplier, parallelism, maxConcurrency,
           maxConcurrencyEagerCheck, false);
    }

    @Override
    protected Flux<CoreEvent> scheduleProcessor(ReactiveProcessor processor, Scheduler processorScheduler, CoreEvent event) {
      reactor.core.scheduler.Scheduler eventLoopScheduler = fromExecutorService(decorateScheduler(getCpuLightScheduler()));
      return scheduleWithLogging(processor, eventLoopScheduler, processorScheduler, event);
    }

    private Flux<CoreEvent> scheduleWithLogging(ReactiveProcessor processor, reactor.core.scheduler.Scheduler eventLoopScheduler,
                                                Scheduler processorScheduler, CoreEvent event) {
      if (isThreadLoggingEnabled) {
        return just(event)
            .flatMap(e -> Mono.subscriberContext()
                .flatMap(ctx -> Mono.just(e).transform(processor)
                    .publishOn(eventLoopScheduler)
                    .subscribeOn(fromExecutorService(new ThreadLoggingExecutorServiceDecorator(ctx
                        .getOrEmpty(THREAD_NOTIFICATION_LOGGER_CONTEXT_KEY), decorateScheduler(processorScheduler),
                                                                                               e.getContext().getId())))));
      } else {
        return just(event)
            .transform(processor)
            .publishOn(eventLoopScheduler)
            .subscribeOn(fromExecutorService(decorateScheduler(processorScheduler)));
      }
    }

    @Override
    protected <E> ReactorSink<E> buildSink(FluxSink<E> fluxSink, Disposable disposable, Consumer<CoreEvent> onEventConsumer,
                                           int bufferSize) {
      return new ProactorSinkWrapper<E>(super.buildSink(fluxSink, disposable, onEventConsumer, bufferSize));
    }
  }
}
