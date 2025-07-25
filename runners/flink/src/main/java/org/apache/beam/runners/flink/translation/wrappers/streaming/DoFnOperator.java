/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink.translation.wrappers.streaming;

import static org.apache.flink.util.Preconditions.checkArgument;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.InMemoryBundleFinalizer;
import org.apache.beam.runners.core.NullSideInputReader;
import org.apache.beam.runners.core.ProcessFnRunner;
import org.apache.beam.runners.core.PushbackSideInputDoFnRunner;
import org.apache.beam.runners.core.SideInputHandler;
import org.apache.beam.runners.core.SideInputReader;
import org.apache.beam.runners.core.SimplePushbackSideInputDoFnRunner;
import org.apache.beam.runners.core.SplittableParDoViaKeyedWorkItems;
import org.apache.beam.runners.core.StateInternals;
import org.apache.beam.runners.core.StateNamespace;
import org.apache.beam.runners.core.StateNamespaces.WindowNamespace;
import org.apache.beam.runners.core.StatefulDoFnRunner;
import org.apache.beam.runners.core.StepContext;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.runners.core.TimerInternals.TimerData;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.runners.flink.adapter.FlinkKey;
import org.apache.beam.runners.flink.metrics.DoFnRunnerWithMetricsUpdate;
import org.apache.beam.runners.flink.metrics.FlinkMetricContainer;
import org.apache.beam.runners.flink.translation.types.CoderTypeSerializer;
import org.apache.beam.runners.flink.translation.utils.CheckpointStats;
import org.apache.beam.runners.flink.translation.utils.Workarounds;
import org.apache.beam.runners.flink.translation.wrappers.streaming.stableinput.BufferingDoFnRunner;
import org.apache.beam.runners.flink.translation.wrappers.streaming.state.FlinkBroadcastStateInternals;
import org.apache.beam.runners.flink.translation.wrappers.streaming.state.FlinkStateInternals;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StructuredCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.BundleFinalizer;
import org.apache.beam.sdk.transforms.DoFnSchemaInformation;
import org.apache.beam.sdk.transforms.join.RawUnionValue;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.util.NoopLock;
import org.apache.beam.sdk.util.WindowedValueMultiReceiver;
import org.apache.beam.sdk.util.WindowedValueReceiver;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Joiner;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.apache.flink.api.common.operators.ProcessingTimeService.ProcessingTimeCallback;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.state.InternalPriorityQueue;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManager;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManagerImpl;
import org.apache.flink.streaming.api.operators.InternalTimer;
import org.apache.flink.streaming.api.operators.InternalTimerService;
import org.apache.flink.streaming.api.operators.InternalTimerServiceImpl;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.Triggerable;
import org.apache.flink.streaming.api.operators.TwoInputStreamOperator;
import org.apache.flink.streaming.api.operators.sorted.state.BatchExecutionInternalTimeService;
import org.apache.flink.streaming.api.operators.sorted.state.BatchExecutionInternalTimeServiceManager;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.function.BiConsumerWithException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink operator for executing {@link DoFn DoFns}.
 *
 * @param <InputT> the input type of the {@link DoFn}
 * @param <OutputT> the output type of the {@link DoFn}
 */
// We use Flink's lifecycle methods to initialize transient fields
@SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "keyfor",
  "nullness"
}) // TODO(https://github.com/apache/beam/issues/20497)
public class DoFnOperator<PreInputT, InputT, OutputT>
    extends AbstractStreamOperator<WindowedValue<OutputT>>
    implements OneInputStreamOperator<WindowedValue<PreInputT>, WindowedValue<OutputT>>,
        TwoInputStreamOperator<WindowedValue<PreInputT>, RawUnionValue, WindowedValue<OutputT>>,
        Triggerable<FlinkKey, TimerData> {

  private static final Logger LOG = LoggerFactory.getLogger(DoFnOperator.class);
  private final boolean isStreaming;

  protected DoFn<InputT, OutputT> doFn;

  protected final SerializablePipelineOptions serializedOptions;

  protected final TupleTag<OutputT> mainOutputTag;
  protected final List<TupleTag<?>> additionalOutputTags;

  protected final Collection<PCollectionView<?>> sideInputs;
  protected final Map<Integer, PCollectionView<?>> sideInputTagMapping;

  protected final WindowingStrategy<?, ?> windowingStrategy;

  protected final OutputManagerFactory<OutputT> outputManagerFactory;

  protected transient DoFnRunner<InputT, OutputT> doFnRunner;
  protected transient PushbackSideInputDoFnRunner<InputT, OutputT> pushbackDoFnRunner;
  protected transient BufferingDoFnRunner<InputT, OutputT> bufferingDoFnRunner;

  protected transient SideInputHandler sideInputHandler;

  protected transient SideInputReader sideInputReader;

  protected transient BufferedOutputManager<OutputT> outputManager;

  private transient DoFnInvoker<InputT, OutputT> doFnInvoker;

  protected transient FlinkStateInternals<?> keyedStateInternals;
  protected transient FlinkTimerInternals timerInternals;

  protected final String stepName;

  final Coder<WindowedValue<InputT>> windowedInputCoder;

  final Map<TupleTag<?>, Coder<?>> outputCoders;

  final Coder<?> keyCoder;

  final KeySelector<WindowedValue<InputT>, ?> keySelector;

  final TimerInternals.TimerDataCoderV2 timerCoder;

  /** Max number of elements to include in a bundle. */
  private final long maxBundleSize;
  /** Max duration of a bundle. */
  private final long maxBundleTimeMills;

  private final DoFnSchemaInformation doFnSchemaInformation;

  private final Map<String, PCollectionView<?>> sideInputMapping;

  /** If true, we must process elements only after a checkpoint is finished. */
  final boolean requiresStableInput;

  /**
   * If both requiresStableInput and this parameter are true, we must flush the buffer during drain
   * operation.
   */
  final boolean enableStableInputDrain;

  final int numConcurrentCheckpoints;

  private final boolean usesOnWindowExpiration;

  private final boolean finishBundleBeforeCheckpointing;

  /** Stores new finalizations being gathered. */
  private transient InMemoryBundleFinalizer bundleFinalizer;
  /** Pending bundle finalizations which have not been acknowledged yet. */
  private transient LinkedHashMap<Long, List<InMemoryBundleFinalizer.Finalization>>
      pendingFinalizations;
  /**
   * Keep a maximum of 32 bundle finalizations for {@link
   * BundleFinalizer.Callback#onBundleSuccess()}.
   */
  private static final int MAX_NUMBER_PENDING_BUNDLE_FINALIZATIONS = 32;

  protected transient InternalTimerService<TimerData> timerService;
  private transient InternalTimeServiceManager<?> timeServiceManager;

  private transient PushedBackElementsHandler<WindowedValue<InputT>> pushedBackElementsHandler;

  /** Metrics container for reporting Beam metrics to Flink (null if metrics are disabled). */
  transient @Nullable FlinkMetricContainer flinkMetricContainer;

  /** Helper class to report the checkpoint duration. */
  private transient @Nullable CheckpointStats checkpointStats;

  /** A timer that finishes the current bundle after a fixed amount of time. */
  private transient ScheduledFuture<?> checkFinishBundleTimer;

  /**
   * This and the below fields need to be volatile because we use multiple threads to access these.
   * (a) the main processing thread (b) a timer thread to finish bundles by a timeout instead of the
   * number of element However, we do not need a lock because Flink makes sure to acquire the
   * "checkpointing" lock for the main processing but also for timer set via its {@code
   * timerService}.
   *
   * <p>The volatile flag can be removed once https://issues.apache.org/jira/browse/FLINK-12481 has
   * been addressed.
   */
  private transient volatile boolean bundleStarted;
  /** Number of processed elements in the current bundle. */
  private transient volatile long elementCount;
  /** Time that the last bundle was finished (to set the timer). */
  private transient volatile long lastFinishBundleTime;
  /** Callback to be executed before the current bundle is started. */
  private transient volatile Runnable preBundleCallback;
  /** Callback to be executed after the current bundle was finished. */
  private transient volatile Runnable bundleFinishedCallback;

  // Watermark state.
  // Volatile because these can be set in two mutually exclusive threads (see above).
  private transient volatile long currentInputWatermark;
  private transient volatile long currentSideInputWatermark;
  private transient volatile long currentOutputWatermark;
  private transient volatile long pushedBackWatermark;

  /** Constructor for DoFnOperator. */
  public DoFnOperator(
      @Nullable DoFn<InputT, OutputT> doFn,
      String stepName,
      Coder<WindowedValue<InputT>> inputWindowedCoder,
      Map<TupleTag<?>, Coder<?>> outputCoders,
      TupleTag<OutputT> mainOutputTag,
      List<TupleTag<?>> additionalOutputTags,
      OutputManagerFactory<OutputT> outputManagerFactory,
      WindowingStrategy<?, ?> windowingStrategy,
      Map<Integer, PCollectionView<?>> sideInputTagMapping,
      Collection<PCollectionView<?>> sideInputs,
      PipelineOptions options,
      @Nullable Coder<?> keyCoder,
      @Nullable KeySelector<WindowedValue<InputT>, ?> keySelector,
      DoFnSchemaInformation doFnSchemaInformation,
      Map<String, PCollectionView<?>> sideInputMapping) {
    this.doFn = doFn;
    this.stepName = stepName;
    this.windowedInputCoder = inputWindowedCoder;
    this.outputCoders = outputCoders;
    this.mainOutputTag = mainOutputTag;
    this.additionalOutputTags = additionalOutputTags;
    this.sideInputTagMapping = sideInputTagMapping;
    this.sideInputs = sideInputs;
    this.serializedOptions = new SerializablePipelineOptions(options);
    this.isStreaming = serializedOptions.get().as(FlinkPipelineOptions.class).isStreaming();
    this.windowingStrategy = windowingStrategy;
    this.outputManagerFactory = outputManagerFactory;

    setChainingStrategy(ChainingStrategy.ALWAYS);

    this.keyCoder = keyCoder;
    this.keySelector = keySelector;

    this.timerCoder =
        TimerInternals.TimerDataCoderV2.of(windowingStrategy.getWindowFn().windowCoder());

    FlinkPipelineOptions flinkOptions = options.as(FlinkPipelineOptions.class);

    this.maxBundleSize = flinkOptions.getMaxBundleSize();
    Preconditions.checkArgument(maxBundleSize > 0, "Bundle size must be at least 1");
    this.maxBundleTimeMills = flinkOptions.getMaxBundleTimeMills();
    Preconditions.checkArgument(maxBundleTimeMills > 0, "Bundle time must be at least 1");
    this.doFnSchemaInformation = doFnSchemaInformation;
    this.sideInputMapping = sideInputMapping;

    this.requiresStableInput = isRequiresStableInput(doFn);

    this.usesOnWindowExpiration =
        doFn != null && DoFnSignatures.getSignature(doFn.getClass()).onWindowExpiration() != null;

    if (requiresStableInput) {
      Preconditions.checkState(
          CheckpointingMode.valueOf(flinkOptions.getCheckpointingMode())
              == CheckpointingMode.EXACTLY_ONCE,
          "Checkpointing mode is not set to exactly once but @RequiresStableInput is used.");
      Preconditions.checkState(
          flinkOptions.getCheckpointingInterval() > 0,
          "No checkpointing configured but pipeline uses @RequiresStableInput");
      LOG.warn(
          "Enabling stable input for transform {}. Will only process elements at most every {} milliseconds.",
          stepName,
          flinkOptions.getCheckpointingInterval()
              + Math.max(0, flinkOptions.getMinPauseBetweenCheckpoints()));
    }

    this.enableStableInputDrain = flinkOptions.getEnableStableInputDrain();

    this.numConcurrentCheckpoints = flinkOptions.getNumConcurrentCheckpoints();

    this.finishBundleBeforeCheckpointing = flinkOptions.getFinishBundleBeforeCheckpointing();
  }

  private boolean isRequiresStableInput(DoFn<InputT, OutputT> doFn) {
    // WindowDoFnOperator does not use a DoFn
    return doFn != null
        && DoFnSignatures.getSignature(doFn.getClass()).processElement().requiresStableInput();
  }

  @VisibleForTesting
  boolean getRequiresStableInput() {
    return requiresStableInput;
  }

  // allow overriding this in WindowDoFnOperator because this one dynamically creates
  // the DoFn
  protected DoFn<InputT, OutputT> getDoFn() {
    return doFn;
  }

  protected Iterable<WindowedValue<InputT>> preProcess(WindowedValue<PreInputT> input) {
    // Assume Input is PreInputT
    return Collections.singletonList((WindowedValue<InputT>) input);
  }

  // allow overriding this, for example SplittableDoFnOperator will not create a
  // stateful DoFn runner because ProcessFn, which is used for executing a Splittable DoFn
  // doesn't play by the normal DoFn rules and WindowDoFnOperator uses LateDataDroppingDoFnRunner
  protected DoFnRunner<InputT, OutputT> createWrappingDoFnRunner(
      DoFnRunner<InputT, OutputT> wrappedRunner, StepContext stepContext) {

    if (keyCoder != null) {
      StatefulDoFnRunner.CleanupTimer<InputT> cleanupTimer =
          new StatefulDoFnRunner.TimeInternalsCleanupTimer<InputT>(
              timerInternals, windowingStrategy) {
            @Override
            public void setForWindow(InputT input, BoundedWindow window) {
              if (!window.equals(GlobalWindow.INSTANCE) || usesOnWindowExpiration) {
                // Skip setting a cleanup timer for the global window as these timers
                // lead to potentially unbounded state growth in the runner, depending on key
                // cardinality. Cleanup for global window will be performed upon arrival of the
                // final watermark.
                // In the case of OnWindowExpiration, we still set the timer.
                super.setForWindow(input, window);
              }
            }
          };

      // we don't know the window type
      //      @SuppressWarnings({"unchecked", "rawtypes"})
      Coder windowCoder = windowingStrategy.getWindowFn().windowCoder();

      @SuppressWarnings({"unchecked"})
      StatefulDoFnRunner.StateCleaner<?> stateCleaner =
          new StatefulDoFnRunner.StateInternalsStateCleaner<>(
              doFn, keyedStateInternals, windowCoder);

      return DoFnRunners.defaultStatefulDoFnRunner(
          doFn,
          getInputCoder(),
          wrappedRunner,
          stepContext,
          windowingStrategy,
          cleanupTimer,
          stateCleaner,
          true /* requiresTimeSortedInput is supported */);

    } else {
      return doFnRunner;
    }
  }

  @Override
  public void setup(
      StreamTask<?, ?> containingTask,
      StreamConfig config,
      Output<StreamRecord<WindowedValue<OutputT>>> output) {

    // make sure that FileSystems is initialized correctly
    FileSystems.setDefaultPipelineOptions(serializedOptions.get());

    super.setup(containingTask, config, output);
  }

  protected boolean shoudBundleElements() {
    return isStreaming;
  }

  @Override
  public void initializeState(StateInitializationContext context) throws Exception {
    super.initializeState(context);

    ListStateDescriptor<WindowedValue<InputT>> pushedBackStateDescriptor =
        new ListStateDescriptor<>(
            "pushed-back-elements",
            new CoderTypeSerializer<>(windowedInputCoder, serializedOptions));

    if (keySelector != null) {
      pushedBackElementsHandler =
          KeyedPushedBackElementsHandler.create(
              keySelector, getKeyedStateBackend(), pushedBackStateDescriptor);
    } else {
      ListState<WindowedValue<InputT>> listState =
          getOperatorStateBackend().getListState(pushedBackStateDescriptor);
      pushedBackElementsHandler = NonKeyedPushedBackElementsHandler.create(listState);
    }

    currentInputWatermark = BoundedWindow.TIMESTAMP_MIN_VALUE.getMillis();
    currentSideInputWatermark = BoundedWindow.TIMESTAMP_MIN_VALUE.getMillis();
    currentOutputWatermark = BoundedWindow.TIMESTAMP_MIN_VALUE.getMillis();

    sideInputReader = NullSideInputReader.of(sideInputs);

    if (!sideInputs.isEmpty()) {

      FlinkBroadcastStateInternals sideInputStateInternals =
          new FlinkBroadcastStateInternals<>(
              getContainingTask().getIndexInSubtaskGroup(),
              getOperatorStateBackend(),
              serializedOptions);

      sideInputHandler = new SideInputHandler(sideInputs, sideInputStateInternals);
      sideInputReader = sideInputHandler;

      Stream<WindowedValue<InputT>> pushedBack = pushedBackElementsHandler.getElements();
      long min =
          pushedBack.map(v -> v.getTimestamp().getMillis()).reduce(Long.MAX_VALUE, Math::min);
      pushedBackWatermark = min;
    } else {
      pushedBackWatermark = Long.MAX_VALUE;
    }

    // StatefulPardo or WindowDoFn
    if (keyCoder != null) {
      keyedStateInternals =
          new FlinkStateInternals<>(
              (KeyedStateBackend) getKeyedStateBackend(),
              keyCoder,
              windowingStrategy.getWindowFn().windowCoder(),
              serializedOptions);

      if (timerService == null) {
        timerService =
            getInternalTimerService(
                "beam-timer", new CoderTypeSerializer<>(timerCoder, serializedOptions), this);
      }

      timerInternals = new FlinkTimerInternals(timerService);
      timeServiceManager =
          getTimeServiceManager()
              .orElseThrow(() -> new IllegalStateException("Time service manager is not set."));
    }

    outputManager =
        outputManagerFactory.create(
            output, getLockToAcquireForStateAccessDuringBundles(), getOperatorStateBackend());
  }

  /**
   * Subclasses may provide a lock to ensure that the state backend is not accessed concurrently
   * during bundle execution.
   */
  protected Lock getLockToAcquireForStateAccessDuringBundles() {
    return NoopLock.get();
  }

  @Override
  public void open() throws Exception {
    // WindowDoFnOperator need use state and timer to get DoFn.
    // So must wait StateInternals and TimerInternals ready.
    // This will be called after initializeState()
    this.doFn = getDoFn();

    FlinkPipelineOptions options = serializedOptions.get().as(FlinkPipelineOptions.class);
    doFnInvoker = DoFnInvokers.tryInvokeSetupFor(doFn, options);

    StepContext stepContext = new FlinkStepContext();
    doFnRunner =
        DoFnRunners.simpleRunner(
            options,
            doFn,
            sideInputReader,
            outputManager,
            mainOutputTag,
            additionalOutputTags,
            stepContext,
            getInputCoder(),
            outputCoders,
            windowingStrategy,
            doFnSchemaInformation,
            sideInputMapping);

    doFnRunner =
        createBufferingDoFnRunnerIfNeeded(createWrappingDoFnRunner(doFnRunner, stepContext));
    earlyBindStateIfNeeded();

    if (!options.getDisableMetrics()) {
      flinkMetricContainer = new FlinkMetricContainer(getRuntimeContext());
      doFnRunner = new DoFnRunnerWithMetricsUpdate<>(stepName, doFnRunner, flinkMetricContainer);
      String checkpointMetricNamespace = options.getReportCheckpointDuration();
      if (checkpointMetricNamespace != null) {
        MetricName checkpointMetric =
            MetricName.named(checkpointMetricNamespace, "checkpoint_duration");
        checkpointStats =
            new CheckpointStats(
                () ->
                    flinkMetricContainer
                        .getMetricsContainer(stepName)
                        .getDistribution(checkpointMetric));
      }
    }

    elementCount = 0L;
    lastFinishBundleTime = getProcessingTimeService().getCurrentProcessingTime();

    // Schedule timer to check timeout of finish bundle.
    long bundleCheckPeriod = Math.max(maxBundleTimeMills / 2, 1);
    checkFinishBundleTimer =
        getProcessingTimeService()
            .scheduleAtFixedRate(
                timestamp -> checkInvokeFinishBundleByTime(), bundleCheckPeriod, bundleCheckPeriod);

    if (doFn instanceof SplittableParDoViaKeyedWorkItems.ProcessFn) {
      pushbackDoFnRunner =
          new ProcessFnRunner<>((DoFnRunner) doFnRunner, sideInputs, sideInputHandler);
    } else {
      pushbackDoFnRunner =
          SimplePushbackSideInputDoFnRunner.create(doFnRunner, sideInputs, sideInputHandler);
    }

    bundleFinalizer = new InMemoryBundleFinalizer();
    pendingFinalizations = new LinkedHashMap<>();
  }

  DoFnRunner<InputT, OutputT> createBufferingDoFnRunnerIfNeeded(
      DoFnRunner<InputT, OutputT> wrappedRunner) throws Exception {

    if (requiresStableInput) {
      // put this in front of the root FnRunner before any additional wrappers
      return this.bufferingDoFnRunner =
          BufferingDoFnRunner.create(
              wrappedRunner,
              "stable-input-buffer",
              windowedInputCoder,
              windowingStrategy.getWindowFn().windowCoder(),
              getOperatorStateBackend(),
              getBufferingKeyedStateBackend(),
              numConcurrentCheckpoints,
              serializedOptions);
    }
    return wrappedRunner;
  }

  /**
   * Retrieve a keyed state backend that should be used to buffer elements for {@link @{code @}
   * RequiresStableInput} functionality. By default this is the default keyed backend, but can be
   * override in @{link ExecutableStageDoFnOperator}.
   *
   * @return the keyed backend to use for element buffering
   */
  <K> @Nullable KeyedStateBackend<K> getBufferingKeyedStateBackend() {
    return getKeyedStateBackend();
  }

  private void earlyBindStateIfNeeded() throws IllegalArgumentException, IllegalAccessException {
    if (keyCoder != null) {
      if (doFn != null) {
        DoFnSignature signature = DoFnSignatures.getSignature(doFn.getClass());
        FlinkStateInternals.EarlyBinder earlyBinder =
            new FlinkStateInternals.EarlyBinder(
                getKeyedStateBackend(),
                serializedOptions,
                windowingStrategy.getWindowFn().windowCoder());
        for (DoFnSignature.StateDeclaration value : signature.stateDeclarations().values()) {
          StateSpec<?> spec =
              (StateSpec<?>) signature.stateDeclarations().get(value.id()).field().get(doFn);
          spec.bind(value.id(), earlyBinder);
        }
        if (doFnRunner instanceof StatefulDoFnRunner) {
          ((StatefulDoFnRunner<InputT, OutputT, BoundedWindow>) doFnRunner)
              .getSystemStateTags()
              .forEach(tag -> tag.getSpec().bind(tag.getId(), earlyBinder));
        }
      }
    }
  }

  void cleanUp() throws Exception {
    Optional.ofNullable(flinkMetricContainer)
        .ifPresent(FlinkMetricContainer::registerMetricsForPipelineResult);
    Optional.ofNullable(checkFinishBundleTimer).ifPresent(timer -> timer.cancel(true));
    Workarounds.deleteStaticCaches();
    Optional.ofNullable(doFnInvoker).ifPresent(DoFnInvoker::invokeTeardown);
  }

  void flushData() throws Exception {
    // This is our last change to block shutdown of this operator while
    // there are still remaining processing-time timers. Flink will ignore pending
    // processing-time timers when upstream operators have shut down and will also
    // shut down this operator with pending processing-time timers.
    if (numProcessingTimeTimers() > 0) {
      timerInternals.processPendingProcessingTimeTimers();
    }
    if (numProcessingTimeTimers() > 0) {
      throw new RuntimeException(
          "There are still "
              + numProcessingTimeTimers()
              + " processing-time timers left, this indicates a bug");
    }
    // make sure we send a +Inf watermark downstream. It can happen that we receive +Inf
    // in processWatermark*() but have holds, so we have to re-evaluate here.
    processWatermark(new Watermark(Long.MAX_VALUE));
    // Make sure to finish the current bundle
    while (bundleStarted) {
      invokeFinishBundle();
    }
    if (requiresStableInput && enableStableInputDrain) {
      // Flush any buffered events here before draining the pipeline. Note that this is best-effort
      // and requiresStableInput contract might be violated in cases where buffer processing fails.
      bufferingDoFnRunner.checkpointCompleted(Long.MAX_VALUE);
      updateOutputWatermark();
    }
    if (currentOutputWatermark < Long.MAX_VALUE) {
      throw new RuntimeException(
          String.format(
              "There are still watermark holds left when terminating operator %s Watermark held %d",
              getOperatorName(), currentOutputWatermark));
    }

    // sanity check: these should have been flushed out by +Inf watermarks
    if (!sideInputs.isEmpty()) {

      List<WindowedValue<InputT>> pushedBackElements =
          pushedBackElementsHandler.getElements().collect(Collectors.toList());

      if (pushedBackElements.size() > 0) {
        String pushedBackString = Joiner.on(",").join(pushedBackElements);
        throw new RuntimeException(
            "Leftover pushed-back data: " + pushedBackString + ". This indicates a bug.");
      }
    }
  }

  @Override
  public void finish() throws Exception {
    try {
      flushData();
    } finally {
      super.finish();
    }
  }

  @Override
  public void close() throws Exception {
    try {
      cleanUp();
    } finally {
      super.close();
    }
  }

  protected int numProcessingTimeTimers() {
    return getTimeServiceManager()
        .map(
            manager -> {
              if (timeServiceManager instanceof InternalTimeServiceManagerImpl) {
                final InternalTimeServiceManagerImpl<?> cast =
                    (InternalTimeServiceManagerImpl<?>) timeServiceManager;
                return cast.numProcessingTimeTimers();
              } else if (timeServiceManager instanceof BatchExecutionInternalTimeServiceManager) {
                return 0;
              } else {
                throw new IllegalStateException(
                    String.format(
                        "Unknown implementation of InternalTimerServiceManager. %s",
                        timeServiceManager));
              }
            })
        .orElse(0);
  }

  public long getEffectiveInputWatermark() {
    // hold back by the pushed back values waiting for side inputs
    long combinedPushedBackWatermark = pushedBackWatermark;
    if (requiresStableInput) {
      combinedPushedBackWatermark =
          Math.min(combinedPushedBackWatermark, bufferingDoFnRunner.getOutputWatermarkHold());
    }
    return Math.min(combinedPushedBackWatermark, currentInputWatermark);
  }

  public long getCurrentOutputWatermark() {
    return currentOutputWatermark;
  }

  protected final void setPreBundleCallback(Runnable callback) {
    this.preBundleCallback = callback;
  }

  protected final void setBundleFinishedCallback(Runnable callback) {
    this.bundleFinishedCallback = callback;
  }

  @Override
  public final void processElement(StreamRecord<WindowedValue<PreInputT>> streamRecord) {
    for (WindowedValue<InputT> e : preProcess(streamRecord.getValue())) {
      checkInvokeStartBundle();
      LOG.trace("Processing element {} in {}", streamRecord.getValue().getValue(), doFn.getClass());
      long oldHold = keyCoder != null ? keyedStateInternals.minWatermarkHoldMs() : -1L;
      doFnRunner.processElement(e);
      checkInvokeFinishBundleByCount();
      emitWatermarkIfHoldChanged(oldHold);
    }
  }

  @Override
  public final void processElement1(StreamRecord<WindowedValue<PreInputT>> streamRecord)
      throws Exception {
    for (WindowedValue<InputT> e : preProcess(streamRecord.getValue())) {
      checkInvokeStartBundle();
      Iterable<WindowedValue<InputT>> justPushedBack =
          pushbackDoFnRunner.processElementInReadyWindows(e);

      long min = pushedBackWatermark;
      for (WindowedValue<InputT> pushedBackValue : justPushedBack) {
        min = Math.min(min, pushedBackValue.getTimestamp().getMillis());
        pushedBackElementsHandler.pushBack(pushedBackValue);
      }
      pushedBackWatermark = min;

      checkInvokeFinishBundleByCount();
    }
  }

  /**
   * Add the side input value. Here we are assuming that views have already been materialized and
   * are sent over the wire as {@link Iterable}. Subclasses may elect to perform materialization in
   * state and receive side input incrementally instead.
   *
   * @param streamRecord
   */
  protected void addSideInputValue(StreamRecord<RawUnionValue> streamRecord) {
    @SuppressWarnings("unchecked")
    WindowedValue<Iterable<?>> value =
        (WindowedValue<Iterable<?>>) streamRecord.getValue().getValue();

    PCollectionView<?> sideInput = sideInputTagMapping.get(streamRecord.getValue().getUnionTag());
    sideInputHandler.addSideInputValue(sideInput, value);
  }

  @Override
  public final void processElement2(StreamRecord<RawUnionValue> streamRecord) throws Exception {
    // we finish the bundle because the newly arrived side-input might
    // make a view available that was previously not ready.
    // The PushbackSideInputRunner will only reset its cache of non-ready windows when
    // finishing a bundle.
    invokeFinishBundle();
    checkInvokeStartBundle();

    // add the side input, which may cause pushed back elements become eligible for processing
    addSideInputValue(streamRecord);

    List<WindowedValue<InputT>> newPushedBack = new ArrayList<>();

    Iterator<WindowedValue<InputT>> it = pushedBackElementsHandler.getElements().iterator();

    while (it.hasNext()) {
      WindowedValue<InputT> element = it.next();
      // we need to set the correct key in case the operator is
      // a (keyed) window operator
      if (keySelector != null) {
        setCurrentKey(keySelector.getKey(element));
      }

      Iterable<WindowedValue<InputT>> justPushedBack =
          pushbackDoFnRunner.processElementInReadyWindows(element);
      Iterables.addAll(newPushedBack, justPushedBack);
    }

    pushedBackElementsHandler.clear();
    long min = Long.MAX_VALUE;
    for (WindowedValue<InputT> pushedBackValue : newPushedBack) {
      min = Math.min(min, pushedBackValue.getTimestamp().getMillis());
      pushedBackElementsHandler.pushBack(pushedBackValue);
    }
    pushedBackWatermark = min;

    checkInvokeFinishBundleByCount();

    // maybe output a new watermark
    processWatermark1(new Watermark(currentInputWatermark));
  }

  @Override
  public final void processWatermark(Watermark mark) throws Exception {
    LOG.trace("Processing watermark {} in {}", mark.getTimestamp(), doFn.getClass());
    processWatermark1(mark);
  }

  @Override
  public final void processWatermark1(Watermark mark) throws Exception {
    // Flush any data buffered during snapshotState().
    outputManager.flushBuffer();

    // We do the check here because we are guaranteed to at least get the +Inf watermark on the
    // main input when the job finishes.
    if (currentSideInputWatermark >= BoundedWindow.TIMESTAMP_MAX_VALUE.getMillis()) {
      // this means we will never see any more side input
      // we also do the check here because we might have received the side-input MAX watermark
      // before receiving any main-input data
      emitAllPushedBackData();
    }

    currentInputWatermark = mark.getTimestamp();
    processInputWatermark(true);
  }

  private void processInputWatermark(boolean advanceInputWatermark) throws Exception {
    long inputWatermarkHold = applyInputWatermarkHold(getEffectiveInputWatermark());
    if (keyCoder != null && advanceInputWatermark) {
      timeServiceManager.advanceWatermark(new Watermark(inputWatermarkHold));
    }

    long potentialOutputWatermark =
        applyOutputWatermarkHold(
            currentOutputWatermark, computeOutputWatermark(inputWatermarkHold));

    maybeEmitWatermark(potentialOutputWatermark);
  }

  /**
   * Allows to apply a hold to the input watermark. By default, just passes the input watermark
   * through.
   */
  public long applyInputWatermarkHold(long inputWatermark) {
    return inputWatermark;
  }

  /**
   * Allows to apply a hold to the output watermark before it is sent out. Used to apply hold on
   * output watermark for delayed (asynchronous or buffered) processing.
   *
   * @param currentOutputWatermark the current output watermark
   * @param potentialOutputWatermark The potential new output watermark which can be adjusted, if
   *     needed. The input watermark hold has already been applied.
   * @return The new output watermark which will be emitted.
   */
  public long applyOutputWatermarkHold(long currentOutputWatermark, long potentialOutputWatermark) {
    return potentialOutputWatermark;
  }

  private long computeOutputWatermark(long inputWatermarkHold) {
    final long potentialOutputWatermark;
    if (keyCoder == null) {
      potentialOutputWatermark = inputWatermarkHold;
    } else {
      potentialOutputWatermark =
          Math.min(keyedStateInternals.minWatermarkHoldMs(), inputWatermarkHold);
    }
    return potentialOutputWatermark;
  }

  private void maybeEmitWatermark(long watermark) {
    if (watermark > currentOutputWatermark) {
      // Must invoke finishBatch before emit the +Inf watermark otherwise there are some late
      // events.
      if (watermark >= BoundedWindow.TIMESTAMP_MAX_VALUE.getMillis()) {
        invokeFinishBundle();
      }

      if (bundleStarted) {
        // do not update watermark in the middle of bundle, because it might cause
        // user-buffered data to be emitted past watermark
        return;
      }

      LOG.debug("Emitting watermark {} from {}", watermark, getOperatorName());
      currentOutputWatermark = watermark;
      output.emitWatermark(new Watermark(watermark));

      // Check if the final watermark was triggered to perform state cleanup for global window
      // TODO: Do we need to do this when OnWindowExpiration is set, since in that case we have a
      // cleanup timer?
      if (keyedStateInternals != null
          && currentOutputWatermark
              > adjustTimestampForFlink(GlobalWindow.INSTANCE.maxTimestamp().getMillis())) {
        keyedStateInternals.clearGlobalState();
      }
    }
  }

  @Override
  public final void processWatermark2(Watermark mark) throws Exception {
    currentSideInputWatermark = mark.getTimestamp();
    if (mark.getTimestamp() >= BoundedWindow.TIMESTAMP_MAX_VALUE.getMillis()) {
      // this means we will never see any more side input
      emitAllPushedBackData();

      // maybe output a new watermark
      processWatermark1(new Watermark(currentInputWatermark));
    }
  }

  /**
   * Emits all pushed-back data. This should be used once we know that there will not be any future
   * side input, i.e. that there is no point in waiting.
   */
  private void emitAllPushedBackData() throws Exception {

    Iterator<WindowedValue<InputT>> it = pushedBackElementsHandler.getElements().iterator();

    while (it.hasNext()) {
      checkInvokeStartBundle();
      WindowedValue<InputT> element = it.next();
      // we need to set the correct key in case the operator is
      // a (keyed) window operator
      setKeyContextElement1(new StreamRecord<>(element));

      doFnRunner.processElement(element);
    }

    pushedBackElementsHandler.clear();
    pushedBackWatermark = Long.MAX_VALUE;
  }

  /**
   * Check whether invoke startBundle, if it is, need to output elements that were buffered as part
   * of finishing a bundle in snapshot() first.
   *
   * <p>In order to avoid having {@link DoFnRunner#processElement(WindowedValue)} or {@link
   * DoFnRunner#onTimer(String, String, Object, BoundedWindow, Instant, Instant, TimeDomain)} not
   * between StartBundle and FinishBundle, this method needs to be called in each processElement and
   * each processWatermark and onProcessingTime. Do not need to call in onEventTime, because it has
   * been guaranteed in the processWatermark.
   */
  private void checkInvokeStartBundle() {
    if (!bundleStarted) {
      // Flush any data buffered during snapshotState().
      outputManager.flushBuffer();
      LOG.debug("Starting bundle.");
      if (preBundleCallback != null) {
        preBundleCallback.run();
      }
      pushbackDoFnRunner.startBundle();
      bundleStarted = true;
    }
  }

  /** Check whether invoke finishBundle by elements count. Called in processElement. */
  @SuppressWarnings("NonAtomicVolatileUpdate")
  @SuppressFBWarnings("VO_VOLATILE_INCREMENT")
  private void checkInvokeFinishBundleByCount() {
    if (!shoudBundleElements()) {
      return;
    }
    // We do not access this statement concurrently, but we want to make sure that each thread
    // sees the latest value, which is why we use volatile. See the class field section above
    // for more information.
    //noinspection NonAtomicOperationOnVolatileField
    elementCount++;
    if (elementCount >= maxBundleSize) {
      invokeFinishBundle();
      updateOutputWatermark();
    }
  }

  /** Check whether invoke finishBundle by timeout. */
  private void checkInvokeFinishBundleByTime() {
    if (!shoudBundleElements()) {
      return;
    }
    long now = getProcessingTimeService().getCurrentProcessingTime();
    if (now - lastFinishBundleTime >= maxBundleTimeMills) {
      invokeFinishBundle();
      scheduleForCurrentProcessingTime(ts -> updateOutputWatermark());
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  protected void scheduleForCurrentProcessingTime(ProcessingTimeCallback callback) {
    // We are scheduling a timer for advancing the watermark, to not delay finishing the bundle
    // and temporarily release the checkpoint lock. Otherwise, we could potentially loop when a
    // timer keeps scheduling a timer for the same timestamp.
    ProcessingTimeService timeService = getProcessingTimeService();
    timeService.registerTimer(timeService.getCurrentProcessingTime(), callback);
  }

  void updateOutputWatermark() {
    try {
      processInputWatermark(false);
    } catch (Exception ex) {
      failBundleFinalization(ex);
    }
  }

  protected final void invokeFinishBundle() {
    long previousBundleFinishTime = lastFinishBundleTime;
    if (bundleStarted) {
      LOG.debug("Finishing bundle.");
      pushbackDoFnRunner.finishBundle();
      LOG.debug("Finished bundle. Element count: {}", elementCount);
      elementCount = 0L;
      lastFinishBundleTime = getProcessingTimeService().getCurrentProcessingTime();
      bundleStarted = false;
      // callback only after current bundle was fully finalized
      // it could start a new bundle, for example resulting from timer processing
      if (bundleFinishedCallback != null) {
        LOG.debug("Invoking bundle finish callback.");
        bundleFinishedCallback.run();
      }
    }
    try {
      if (previousBundleFinishTime - getProcessingTimeService().getCurrentProcessingTime()
          > maxBundleTimeMills) {
        processInputWatermark(false);
      }
    } catch (Exception ex) {
      LOG.warn("Failed to update downstream watermark", ex);
    }
  }

  @Override
  public void prepareSnapshotPreBarrier(long checkpointId) {
    if (finishBundleBeforeCheckpointing) {
      // We finish the bundle and flush any pending data.
      // This avoids buffering any data as part of snapshotState() below.
      while (bundleStarted) {
        invokeFinishBundle();
      }
      updateOutputWatermark();
    }
  }

  @Override
  public void snapshotState(StateSnapshotContext context) throws Exception {
    if (checkpointStats != null) {
      checkpointStats.snapshotStart(context.getCheckpointId());
    }

    if (requiresStableInput) {
      // We notify the BufferingDoFnRunner to associate buffered state with this
      // snapshot id and start a new buffer for elements arriving after this snapshot.
      bufferingDoFnRunner.checkpoint(context.getCheckpointId());
    }

    int diff = pendingFinalizations.size() - MAX_NUMBER_PENDING_BUNDLE_FINALIZATIONS;
    if (diff >= 0) {
      for (Iterator<Long> iterator = pendingFinalizations.keySet().iterator(); diff >= 0; diff--) {
        iterator.next();
        iterator.remove();
      }
    }
    pendingFinalizations.put(context.getCheckpointId(), bundleFinalizer.getAndClearFinalizations());

    try {
      outputManager.openBuffer();
      // Ensure that no new bundle gets started as part of finishing a bundle
      while (bundleStarted) {
        invokeFinishBundle();
      }
      outputManager.closeBuffer();
    } catch (Exception e) {
      failBundleFinalization(e);
    }

    super.snapshotState(context);
  }

  private void failBundleFinalization(Exception e) {
    // https://jira.apache.org/jira/browse/FLINK-14653
    // Any regular exception during checkpointing will be tolerated by Flink because those
    // typically do not affect the execution flow. We need to fail hard here because errors
    // in bundle execution are application errors which are not related to checkpointing.
    throw new Error("Checkpointing failed because bundle failed to finalize.", e);
  }

  public BundleFinalizer getBundleFinalizer() {
    return bundleFinalizer;
  }

  @Override
  public void notifyCheckpointComplete(long checkpointId) throws Exception {
    if (checkpointStats != null) {
      checkpointStats.reportCheckpointDuration(checkpointId);
    }

    if (requiresStableInput) {
      // We can now release all buffered data which was held back for
      // @RequiresStableInput guarantees.
      bufferingDoFnRunner.checkpointCompleted(checkpointId);
      updateOutputWatermark();
    }

    List<InMemoryBundleFinalizer.Finalization> finalizations =
        pendingFinalizations.remove(checkpointId);
    if (finalizations != null) {
      // confirm all finalizations that were associated with the checkpoint
      for (InMemoryBundleFinalizer.Finalization finalization : finalizations) {
        finalization.getCallback().onBundleSuccess();
      }
    }

    super.notifyCheckpointComplete(checkpointId);
  }

  @Override
  public void onEventTime(InternalTimer<FlinkKey, TimerData> timer) {
    checkInvokeStartBundle();
    fireTimerInternal(timer.getKey(), timer.getNamespace());
  }

  @Override
  public void onProcessingTime(InternalTimer<FlinkKey, TimerData> timer) {
    checkInvokeStartBundle();
    fireTimerInternal(timer.getKey(), timer.getNamespace());
  }

  // allow overriding this in ExecutableStageDoFnOperator to set the key context
  protected void fireTimerInternal(FlinkKey key, TimerData timerData) {
    long oldHold = keyCoder != null ? keyedStateInternals.minWatermarkHoldMs() : -1L;
    fireTimer(timerData);
    emitWatermarkIfHoldChanged(oldHold);
  }

  void emitWatermarkIfHoldChanged(long currentWatermarkHold) {
    if (keyCoder != null) {
      long newWatermarkHold = keyedStateInternals.minWatermarkHoldMs();
      if (newWatermarkHold > currentWatermarkHold) {
        try {
          processInputWatermark(false);
        } catch (Exception ex) {
          // should not happen
          throw new IllegalStateException(ex);
        }
      }
    }
  }

  // allow overriding this in WindowDoFnOperator
  protected void fireTimer(TimerData timerData) {
    LOG.debug(
        "Firing timer: {} at {} with output time {}",
        timerData.getTimerId(),
        timerData.getTimestamp().getMillis(),
        timerData.getOutputTimestamp().getMillis());
    StateNamespace namespace = timerData.getNamespace();
    // This is a user timer, so namespace must be WindowNamespace
    checkArgument(namespace instanceof WindowNamespace);
    BoundedWindow window = ((WindowNamespace) namespace).getWindow();
    timerInternals.onFiredOrDeletedTimer(timerData);

    pushbackDoFnRunner.onTimer(
        timerData.getTimerId(),
        timerData.getTimerFamilyId(),
        keyedStateInternals.getKey(),
        window,
        timerData.getTimestamp(),
        timerData.getOutputTimestamp(),
        timerData.getDomain());
  }

  @SuppressWarnings("unchecked")
  Coder<InputT> getInputCoder() {
    return (Coder<InputT>) Iterables.getOnlyElement(windowedInputCoder.getCoderArguments());
  }

  /** Factory for creating an {@link BufferedOutputManager} from a Flink {@link Output}. */
  interface OutputManagerFactory<OutputT> extends Serializable {
    BufferedOutputManager<OutputT> create(
        Output<StreamRecord<WindowedValue<OutputT>>> output,
        Lock bufferLock,
        OperatorStateBackend operatorStateBackend)
        throws Exception;
  }

  /**
   * A {@link WindowedValueReceiver} that can buffer its outputs. Uses {@link
   * PushedBackElementsHandler} to buffer the data. Buffering data is necessary because no elements
   * can be emitted during {@code snapshotState} which is called when the checkpoint barrier already
   * has been sent downstream. Emitting elements would break the flow of checkpoint barrier and
   * violate exactly-once semantics.
   *
   * <p>This buffering can be deactived using {@code
   * FlinkPipelineOptions#setFinishBundleBeforeCheckpointing(true)}. If activated, we flush out
   * bundle data before the barrier is sent downstream. This is done via {@code
   * prepareSnapshotPreBarrier}. When Flink supports unaligned checkpoints, this should become the
   * default and this class should be removed as in https://github.com/apache/beam/pull/9652.
   */
  public static class BufferedOutputManager<OutputT> implements WindowedValueMultiReceiver {

    private final TupleTag<OutputT> mainTag;
    private final Map<TupleTag<?>, OutputTag<WindowedValue<?>>> tagsToOutputTags;
    private final Map<TupleTag<?>, Integer> tagsToIds;
    /**
     * A lock to be acquired before writing to the buffer. This lock will only be acquired during
     * buffering. It will not be acquired during flushing the buffer.
     */
    private final Lock bufferLock;

    private final boolean isStreaming;

    private Map<Integer, TupleTag<?>> idsToTags;
    /** Elements buffered during a snapshot, by output id. */
    @VisibleForTesting
    final PushedBackElementsHandler<KV<Integer, WindowedValue<?>>> pushedBackElementsHandler;

    protected final Output<StreamRecord<WindowedValue<OutputT>>> output;

    /** Indicates whether we are buffering data as part of snapshotState(). */
    private boolean openBuffer = false;
    /** For performance, to avoid having to access the state backend when the buffer is empty. */
    private boolean bufferIsEmpty = false;

    BufferedOutputManager(
        Output<StreamRecord<WindowedValue<OutputT>>> output,
        TupleTag<OutputT> mainTag,
        Map<TupleTag<?>, OutputTag<WindowedValue<?>>> tagsToOutputTags,
        Map<TupleTag<?>, Integer> tagsToIds,
        Lock bufferLock,
        PushedBackElementsHandler<KV<Integer, WindowedValue<?>>> pushedBackElementsHandler,
        boolean isStreaming) {
      this.output = output;
      this.mainTag = mainTag;
      this.tagsToOutputTags = tagsToOutputTags;
      this.tagsToIds = tagsToIds;
      this.bufferLock = bufferLock;
      this.idsToTags = new HashMap<>();
      for (Map.Entry<TupleTag<?>, Integer> entry : tagsToIds.entrySet()) {
        idsToTags.put(entry.getValue(), entry.getKey());
      }
      this.pushedBackElementsHandler = pushedBackElementsHandler;
      this.isStreaming = isStreaming;
    }

    void openBuffer() {
      this.openBuffer = true;
    }

    void closeBuffer() {
      this.openBuffer = false;
    }

    @Override
    public <T> void output(TupleTag<T> tag, WindowedValue<T> value) {
      // Don't buffer elements in Batch mode
      if (!openBuffer || !isStreaming) {
        emit(tag, value);
      } else {
        buffer(KV.of(tagsToIds.get(tag), value));
      }
    }

    private void buffer(KV<Integer, WindowedValue<?>> taggedValue) {
      bufferLock.lock();
      try {
        pushedBackElementsHandler.pushBack(taggedValue);
      } catch (Exception e) {
        throw new RuntimeException("Couldn't pushback element.", e);
      } finally {
        bufferLock.unlock();
        bufferIsEmpty = false;
      }
    }

    /**
     * Flush elements of bufferState to Flink Output. This method should not be invoked in {@link
     * #snapshotState(StateSnapshotContext)} because the checkpoint barrier has already been sent
     * downstream; emitting elements at this point would violate the checkpoint barrier alignment.
     *
     * <p>The buffer should be flushed before starting a new bundle when the buffer cannot be
     * concurrently accessed and thus does not need to be guarded by a lock.
     */
    void flushBuffer() {
      if (openBuffer || bufferIsEmpty) {
        // Checkpoint currently in progress or nothing buffered, do not proceed
        return;
      }
      try {
        pushedBackElementsHandler
            .getElements()
            .forEach(
                element ->
                    emit(idsToTags.get(element.getKey()), (WindowedValue) element.getValue()));
        pushedBackElementsHandler.clear();
        bufferIsEmpty = true;
      } catch (Exception e) {
        throw new RuntimeException("Couldn't flush pushed back elements.", e);
      }
    }

    private <T> void emit(TupleTag<T> tag, WindowedValue<T> value) {
      if (tag.equals(mainTag)) {
        // with tagged outputs we can't get around this because we don't
        // know our own output type...
        @SuppressWarnings("unchecked")
        WindowedValue<OutputT> castValue = (WindowedValue<OutputT>) value;
        output.collect(new StreamRecord<>(castValue));
      } else {
        @SuppressWarnings("unchecked")
        OutputTag<WindowedValue<T>> outputTag = (OutputTag) tagsToOutputTags.get(tag);
        output.collect(outputTag, new StreamRecord<>(value));
      }
    }
  }

  /** Coder for KV of id and value. It will be serialized in Flink checkpoint. */
  private static class TaggedKvCoder extends StructuredCoder<KV<Integer, WindowedValue<?>>> {

    private final Map<Integer, Coder<WindowedValue<?>>> idsToCoders;

    TaggedKvCoder(Map<Integer, Coder<WindowedValue<?>>> idsToCoders) {
      this.idsToCoders = idsToCoders;
    }

    @Override
    public void encode(KV<Integer, WindowedValue<?>> kv, OutputStream out) throws IOException {
      Coder<WindowedValue<?>> coder = idsToCoders.get(kv.getKey());
      VarIntCoder.of().encode(kv.getKey(), out);
      coder.encode(kv.getValue(), out);
    }

    @Override
    public KV<Integer, WindowedValue<?>> decode(InputStream in) throws IOException {
      Integer id = VarIntCoder.of().decode(in);
      Coder<WindowedValue<?>> coder = idsToCoders.get(id);
      WindowedValue<?> value = coder.decode(in);
      return KV.of(id, value);
    }

    @Override
    public List<? extends Coder<?>> getCoderArguments() {
      return new ArrayList<>(idsToCoders.values());
    }

    @Override
    public void verifyDeterministic() throws NonDeterministicException {
      for (Coder<?> coder : idsToCoders.values()) {
        verifyDeterministic(this, "Coder must be deterministic", coder);
      }
    }
  }

  /**
   * Implementation of {@link OutputManagerFactory} that creates an {@link BufferedOutputManager}
   * that can write to multiple logical outputs by Flink side output.
   */
  public static class MultiOutputOutputManagerFactory<OutputT>
      implements OutputManagerFactory<OutputT> {

    private final TupleTag<OutputT> mainTag;
    private final Map<TupleTag<?>, Integer> tagsToIds;
    private final Map<TupleTag<?>, OutputTag<WindowedValue<?>>> tagsToOutputTags;
    private final Map<TupleTag<?>, Coder<WindowedValue<?>>> tagsToCoders;
    private final SerializablePipelineOptions pipelineOptions;
    private final boolean isStreaming;

    // There is no side output.
    @SuppressWarnings("unchecked")
    public MultiOutputOutputManagerFactory(
        TupleTag<OutputT> mainTag,
        Coder<WindowedValue<OutputT>> mainCoder,
        SerializablePipelineOptions pipelineOptions) {
      this(
          mainTag,
          new HashMap<>(),
          ImmutableMap.<TupleTag<?>, Coder<WindowedValue<?>>>builder()
              .put(mainTag, (Coder) mainCoder)
              .build(),
          ImmutableMap.<TupleTag<?>, Integer>builder().put(mainTag, 0).build(),
          pipelineOptions);
    }

    public MultiOutputOutputManagerFactory(
        TupleTag<OutputT> mainTag,
        Map<TupleTag<?>, OutputTag<WindowedValue<?>>> tagsToOutputTags,
        Map<TupleTag<?>, Coder<WindowedValue<?>>> tagsToCoders,
        Map<TupleTag<?>, Integer> tagsToIds,
        SerializablePipelineOptions pipelineOptions) {
      this.mainTag = mainTag;
      this.tagsToOutputTags = tagsToOutputTags;
      this.tagsToCoders = tagsToCoders;
      this.tagsToIds = tagsToIds;
      this.pipelineOptions = pipelineOptions;
      this.isStreaming = pipelineOptions.get().as(FlinkPipelineOptions.class).isStreaming();
    }

    @Override
    public BufferedOutputManager<OutputT> create(
        Output<StreamRecord<WindowedValue<OutputT>>> output,
        Lock bufferLock,
        OperatorStateBackend operatorStateBackend)
        throws Exception {
      Preconditions.checkNotNull(output);
      Preconditions.checkNotNull(bufferLock);
      Preconditions.checkNotNull(operatorStateBackend);

      TaggedKvCoder taggedKvCoder = buildTaggedKvCoder();
      ListStateDescriptor<KV<Integer, WindowedValue<?>>> taggedOutputPushbackStateDescriptor =
          new ListStateDescriptor<>(
              "bundle-buffer-tag", new CoderTypeSerializer<>(taggedKvCoder, pipelineOptions));
      ListState<KV<Integer, WindowedValue<?>>> listStateBuffer =
          operatorStateBackend.getListState(taggedOutputPushbackStateDescriptor);
      PushedBackElementsHandler<KV<Integer, WindowedValue<?>>> pushedBackElementsHandler =
          NonKeyedPushedBackElementsHandler.create(listStateBuffer);

      return new BufferedOutputManager<>(
          output,
          mainTag,
          tagsToOutputTags,
          tagsToIds,
          bufferLock,
          pushedBackElementsHandler,
          isStreaming);
    }

    private TaggedKvCoder buildTaggedKvCoder() {
      ImmutableMap.Builder<Integer, Coder<WindowedValue<?>>> idsToCodersBuilder =
          ImmutableMap.builder();
      for (Map.Entry<TupleTag<?>, Integer> entry : tagsToIds.entrySet()) {
        idsToCodersBuilder.put(entry.getValue(), tagsToCoders.get(entry.getKey()));
      }
      return new TaggedKvCoder(idsToCodersBuilder.build());
    }
  }

  /**
   * {@link StepContext} for running {@link DoFn DoFns} on Flink. This does not allow accessing
   * state or timer internals.
   */
  protected class FlinkStepContext implements StepContext {

    @Override
    public StateInternals stateInternals() {
      return keyedStateInternals;
    }

    @Override
    public TimerInternals timerInternals() {
      return timerInternals;
    }

    @Override
    public BundleFinalizer bundleFinalizer() {
      return bundleFinalizer;
    }
  }

  class FlinkTimerInternals implements TimerInternals {

    private static final String PENDING_TIMERS_STATE_NAME = "pending-timers";

    /**
     * Pending Timers (=not been fired yet) by context id. The id is generated from the state
     * namespace of the timer and the timer's id. Necessary for supporting removal of existing
     * timers. In Flink removal of timers can only be done by providing id and time of the timer.
     *
     * <p>CAUTION: This map is scoped by the current active key. Do not attempt to perform any
     * calculations which span across keys.
     */
    @VisibleForTesting final MapState<String, TimerData> pendingTimersById;

    private final InternalTimerService<TimerData> timerService;

    private FlinkTimerInternals(InternalTimerService<TimerData> timerService) throws Exception {
      MapStateDescriptor<String, TimerData> pendingTimersByIdStateDescriptor =
          new MapStateDescriptor<>(
              PENDING_TIMERS_STATE_NAME,
              new StringSerializer(),
              new CoderTypeSerializer<>(timerCoder, serializedOptions));

      this.pendingTimersById = getKeyedStateStore().getMapState(pendingTimersByIdStateDescriptor);
      this.timerService = timerService;
      populateOutputTimestampQueue(timerService);
    }

    /**
     * Processes all pending processing timers. This is intended for use during shutdown. From Flink
     * 1.10 on, processing timer execution is stopped when the operator is closed. This leads to
     * problems for applications which assume all pending timers will be completed. Although Flink
     * does drain the remaining timers after close(), this is not sufficient because no new timers
     * are allowed to be scheduled anymore. This breaks Beam pipelines which rely on all processing
     * timers to be scheduled and executed.
     */
    void processPendingProcessingTimeTimers() {
      final KeyedStateBackend<Object> keyedStateBackend = getKeyedStateBackend();
      final InternalPriorityQueue<InternalTimer<Object, TimerData>> processingTimeTimersQueue =
          Workarounds.retrieveInternalProcessingTimerQueue(timerService);

      InternalTimer<Object, TimerData> internalTimer;
      while ((internalTimer = processingTimeTimersQueue.poll()) != null) {
        keyedStateBackend.setCurrentKey(internalTimer.getKey());
        TimerData timer = internalTimer.getNamespace();
        checkInvokeStartBundle();
        fireTimerInternal((FlinkKey) internalTimer.getKey(), timer);
      }
    }

    private void populateOutputTimestampQueue(InternalTimerService<TimerData> timerService)
        throws Exception {

      BiConsumerWithException<TimerData, Long, Exception> consumer =
          (timerData, stamp) ->
              keyedStateInternals.addWatermarkHoldUsage(timerData.getOutputTimestamp());
      if (timerService instanceof InternalTimerServiceImpl) {
        timerService.forEachEventTimeTimer(consumer);
        timerService.forEachProcessingTimeTimer(consumer);
      }
    }

    private String constructTimerId(String timerFamilyId, String timerId) {
      return timerFamilyId + "+" + timerId;
    }

    @Override
    public void setTimer(
        StateNamespace namespace,
        String timerId,
        String timerFamilyId,
        Instant target,
        Instant outputTimestamp,
        TimeDomain timeDomain) {
      setTimer(
          TimerData.of(timerId, timerFamilyId, namespace, target, outputTimestamp, timeDomain));
    }

    /**
     * @deprecated use {@link #setTimer(StateNamespace, String, String, Instant, Instant,
     *     TimeDomain)}.
     */
    @Deprecated
    @Override
    public void setTimer(TimerData timer) {
      try {
        LOG.debug(
            "Setting timer: {} at {} with output time {}",
            timer.getTimerId(),
            timer.getTimestamp().getMillis(),
            timer.getOutputTimestamp().getMillis());
        String contextTimerId =
            getContextTimerId(
                constructTimerId(timer.getTimerFamilyId(), timer.getTimerId()),
                timer.getNamespace());
        @Nullable final TimerData oldTimer = pendingTimersById.get(contextTimerId);
        if (!timer.equals(oldTimer)) {
          // Only one timer can exist at a time for a given timer id and context.
          // If a timer gets set twice in the same context, the second must
          // override the first. Thus, we must cancel any pending timers
          // before we set the new one.
          cancelPendingTimer(oldTimer);
          registerTimer(timer, contextTimerId);
        }
      } catch (Exception e) {
        throw new RuntimeException("Failed to set timer", e);
      }
    }

    private void registerTimer(TimerData timer, String contextTimerId) throws Exception {
      LOG.debug("Registering timer {}", timer);
      pendingTimersById.put(contextTimerId, timer);
      long time = timer.getTimestamp().getMillis();
      switch (timer.getDomain()) {
        case EVENT_TIME:
          timerService.registerEventTimeTimer(timer, adjustTimestampForFlink(time));
          break;
        case PROCESSING_TIME:
        case SYNCHRONIZED_PROCESSING_TIME:
          timerService.registerProcessingTimeTimer(timer, adjustTimestampForFlink(time));
          break;
        default:
          throw new UnsupportedOperationException("Unsupported time domain: " + timer.getDomain());
      }
      keyedStateInternals.addWatermarkHoldUsage(timer.getOutputTimestamp());
    }

    /**
     * Looks up a timer by its id. This is necessary to support canceling existing timers with the
     * same id. Flink does not provide this functionality.
     *
     * @param contextTimerId Timer ID o cancel.
     */
    private void cancelPendingTimerById(String contextTimerId) throws Exception {
      cancelPendingTimer(pendingTimersById.get(contextTimerId));
    }

    /**
     * Cancels a pending timer.
     *
     * @param timer Timer to cancel.
     */
    private void cancelPendingTimer(@Nullable TimerData timer) {
      if (timer != null) {
        deleteTimerInternal(timer);
      }
    }

    /**
     * Hook which must be called when a timer is fired or deleted to perform cleanup. Note: Make
     * sure that the state backend key is set correctly. It is best to run this in the fireTimer()
     * method.
     */
    void onFiredOrDeletedTimer(TimerData timer) {
      try {
        pendingTimersById.remove(
            getContextTimerId(
                constructTimerId(timer.getTimerFamilyId(), timer.getTimerId()),
                timer.getNamespace()));
        keyedStateInternals.removeWatermarkHoldUsage(timer.getOutputTimestamp());
      } catch (Exception e) {
        throw new RuntimeException("Failed to cleanup pending timers state.", e);
      }
    }

    /** @deprecated use {@link #deleteTimer(StateNamespace, String, TimeDomain)}. */
    @Deprecated
    @Override
    public void deleteTimer(StateNamespace namespace, String timerId, String timerFamilyId) {
      throw new UnsupportedOperationException("Canceling of a timer by ID is not yet supported.");
    }

    @Override
    public void deleteTimer(
        StateNamespace namespace, String timerId, String timerFamilyId, TimeDomain timeDomain) {
      try {
        cancelPendingTimerById(getContextTimerId(timerId, namespace));
      } catch (Exception e) {
        throw new RuntimeException("Failed to cancel timer", e);
      }
    }

    /** @deprecated use {@link #deleteTimer(StateNamespace, String, TimeDomain)}. */
    @Override
    @Deprecated
    public void deleteTimer(TimerData timer) {
      deleteTimer(
          timer.getNamespace(),
          constructTimerId(timer.getTimerFamilyId(), timer.getTimerId()),
          timer.getTimerFamilyId(),
          timer.getDomain());
    }

    void deleteTimerInternal(TimerData timer) {
      long time = timer.getTimestamp().getMillis();
      switch (timer.getDomain()) {
        case EVENT_TIME:
          timerService.deleteEventTimeTimer(timer, adjustTimestampForFlink(time));
          break;
        case PROCESSING_TIME:
        case SYNCHRONIZED_PROCESSING_TIME:
          timerService.deleteProcessingTimeTimer(timer, adjustTimestampForFlink(time));
          break;
        default:
          throw new UnsupportedOperationException("Unsupported time domain: " + timer.getDomain());
      }
      onFiredOrDeletedTimer(timer);
    }

    @Override
    public Instant currentProcessingTime() {
      return new Instant(timerService.currentProcessingTime());
    }

    @Override
    public @Nullable Instant currentSynchronizedProcessingTime() {
      return new Instant(timerService.currentProcessingTime());
    }

    @Override
    public Instant currentInputWatermarkTime() {
      if (timerService instanceof BatchExecutionInternalTimeService) {
        // In batch mode, this method will only either return BoundedWindow.TIMESTAMP_MIN_VALUE,
        // or BoundedWindow.TIMESTAMP_MAX_VALUE.
        //
        // For batch execution mode, the currentInputWatermark variable will never be updated
        // until all the records are processed. However, every time when a record with a new
        // key arrives, the Flink timer service watermark will be set to
        // MAX_WATERMARK(LONG.MAX_VALUE) so that all the timers associated with the current
        // key can fire. After that the Flink timer service watermark will be reset to
        // LONG.MIN_VALUE, so the next key will start from a fresh env as if the previous
        // records of a different key never existed. So the watermark is either Long.MIN_VALUE
        // or long MAX_VALUE. So we should just use the Flink time service watermark in batch mode.
        //
        // In Flink the watermark ranges from
        // [LONG.MIN_VALUE (-9223372036854775808), LONG.MAX_VALUE (9223372036854775807)] while the
        // beam
        // watermark range is [BoundedWindow.TIMESTAMP_MIN_VALUE (-9223372036854775),
        // BoundedWindow.TIMESTAMP_MAX_VALUE (9223372036854775)]. To ensure the timestamp visible to
        // the users follow the Beam convention, we just use the Beam range instead.
        return timerService.currentWatermark() == Long.MAX_VALUE
            ? new Instant(Long.MAX_VALUE)
            : BoundedWindow.TIMESTAMP_MIN_VALUE;
      } else {
        return new Instant(getEffectiveInputWatermark());
      }
    }

    @Override
    public @Nullable Instant currentOutputWatermarkTime() {
      return new Instant(currentOutputWatermark);
    }

    /**
     * Check whether event time timers lower or equal to the given timestamp exist. Caution: This is
     * scoped by the current key.
     */
    public boolean hasPendingEventTimeTimers(long maxTimestamp) throws Exception {
      for (TimerData timer : pendingTimersById.values()) {
        if (timer.getDomain() == TimeDomain.EVENT_TIME
            && timer.getTimestamp().getMillis() <= maxTimestamp) {
          return true;
        }
      }
      return false;
    }

    /** Unique contextual id of a timer. Used to look up any existing timers in a context. */
    private String getContextTimerId(String timerId, StateNamespace namespace) {
      return timerId + namespace.stringKey();
    }
  }

  /**
   * In Beam, a timer with timestamp {@code T} is only illegible for firing when the time has moved
   * past this time stamp, i.e. {@code T < current_time}. In the case of event time, current_time is
   * the watermark, in the case of processing time it is the system time.
   *
   * <p>Flink's TimerService has different semantics because it only ensures {@code T <=
   * current_time}.
   *
   * <p>To make up for this, we need to add one millisecond to Flink's internal timer timestamp.
   * Note that we do not modify Beam's timestamp and we are not exposing Flink's timestamp.
   *
   * <p>See also https://jira.apache.org/jira/browse/BEAM-3863
   */
  static long adjustTimestampForFlink(long beamTimerTimestamp) {
    if (beamTimerTimestamp == Long.MAX_VALUE) {
      // We would overflow, do not adjust timestamp
      return Long.MAX_VALUE;
    }
    return beamTimerTimestamp + 1;
  }
}
