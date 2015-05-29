/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.chunk;

import com.google.android.exoplayer.upstream.BandwidthMeter;

import java.util.List;
import java.util.Random;

/**
 * Selects from a number of available formats during playback.
 */
public interface FormatEvaluator {

  /**
   * The trigger for the initial format selection.
   */
  static final int TRIGGER_INITIAL = 0;
  /**
   * The trigger for a format selection that was triggered by the user.
   */
  static final int TRIGGER_MANUAL = 1;
  /**
   * The trigger for an adaptive format selection.
   */
  static final int TRIGGER_ADAPTIVE = 2;
  /**
   * Implementations may define custom trigger codes greater than or equal to this value.
   */
  static final int TRIGGER_CUSTOM_BASE = 10000;

  /**
   * Enables the evaluator.
   */
  void enable();

  /**
   * Disables the evaluator.
   */
  void disable();

  /**
   * Update the supplied evaluation.
   * <p>
   * When the method is invoked, {@code evaluation} will contain the currently selected
   * format (null for the first evaluation), the most recent trigger (TRIGGER_INITIAL for the
   * first evaluation) and the current queue size. The implementation should update these
   * fields as necessary.
   * <p>
   * The trigger should be considered "sticky" for as long as a given representation is selected,
   * and so should only be changed if the representation is also changed.
   *
   * @param queue A read only representation of the currently buffered {@link MediaChunk}s.
   * @param playbackPositionUs The current playback position.
   * @param formats The formats from which to select, ordered by decreasing bandwidth.
   * @param evaluation The evaluation.
   */
  // TODO: Pass more useful information into this method, and finalize the interface.
  void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs, Format[] formats,
      Evaluation evaluation);

  /**
   * A format evaluation.
   */
  public static final class Evaluation {

    /**
     * The desired size of the queue.
     */
    public int queueSize;

    /**
     * The sticky reason for the format selection.
     */
    public int trigger;

    /**
     * The selected format.
     */
    public Format format;

    public Evaluation() {
      trigger = TRIGGER_INITIAL;
    }

  }

  /**
   * Always selects the first format.
   */
  public static class FixedEvaluator implements FormatEvaluator {

    private int mHeight;
    public FixedEvaluator(int height){
      this.mHeight = height;
    }
    @Override
    public void enable() {
      // Do nothing.
    }

    @Override
    public void disable() {
      // Do nothing.
    }

    @Override
    public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
        Format[] formats, Evaluation evaluation) {
      if (mHeight == 0)
        evaluation.format = formats[formats.length-1];
      else{
        for (int i = 0; i < formats.length; i++) {
          if (formats[i].height == mHeight) {
            evaluation.format = formats[i];
          }
        }
      }

    }
  }

  /**
   * Selects randomly between the available formats.
   */
  public static class RandomEvaluator implements FormatEvaluator {

    private final Random random;

    public RandomEvaluator() {
      this.random = new Random();
    }

    @Override
    public void enable() {
      // Do nothing.
    }

    @Override
    public void disable() {
      // Do nothing.
    }

    @Override
    public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
        Format[] formats, Evaluation evaluation) {
      int i = random.nextInt(formats.length);
      Format newFormat = formats[i];
      if (evaluation.format != null && !evaluation.format.id.equals(newFormat.id)) {
        evaluation.trigger = TRIGGER_ADAPTIVE;
      }
      evaluation.format = newFormat;
      //System.out.printf("We will play Random resolution format[%d] [%d*%d]\n",i,formats[0].width,formats[0].height);
    }

  }

  /**
   * Selects circularly between the available formats.
   */
  public static class LoopEvaluator implements FormatEvaluator {

    static int loop = 0,local_loop = 0;

    @Override
    public void enable() {
        // Do nothing.
    }

    @Override
    public void disable() {
        // Do nothing.
    }

    @Override
    public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
                           Format[] formats, Evaluation evaluation) {
      //System.out.printf("Every time we get in ,we will switch to another resolution. Format[%d --> ",formats.length - loop%formats.length -1);
      ++local_loop ;
      if(local_loop %3 == 0){
        evaluation.format = formats[(formats.length - loop%formats.length -1)];
        loop = (++loop )%formats.length;
      }else {
        evaluation.format = formats[(formats.length - loop%formats.length -1)];
      }
      //System.out.printf("%d] Current resolution.[%d*%d] And",formats.length - loop%formats.length -1,evaluation.format.width,evaluation.format.height);
      //System.out.printf("we will change to [%d*%d]\n",formats[formats.length - loop%formats.length -1].width,formats[formats.length - loop%formats.length -1].height);

    }

  }

  /**
   * An adaptive evaluator for video formats, which attempts to select the best quality possible
   * given the current network conditions and state of the buffer.
   * <p>
   * This implementation should be used for video only, and should not be used for audio. It is a
   * reference implementation only. It is recommended that application developers implement their
   * own adaptive evaluator to more precisely suit their use case.
   */
  public static class AdaptiveEvaluator implements FormatEvaluator {

    public static final int DEFAULT_MAX_INITIAL_BITRATE = 800000;

    public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10000;
    public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25000;
    public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25000;
    public static final float DEFAULT_BANDWIDTH_FRACTION = 0.75f;

    private final BandwidthMeter bandwidthMeter;

    private final int maxInitialBitrate;
    private final long minDurationForQualityIncreaseUs;
    private final long maxDurationForQualityDecreaseUs;
    private final long minDurationToRetainAfterDiscardUs;
    private final float bandwidthFraction;

    /**
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     */
    public AdaptiveEvaluator(BandwidthMeter bandwidthMeter) {
      this (bandwidthMeter, DEFAULT_MAX_INITIAL_BITRATE,
          DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
          DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
          DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, DEFAULT_BANDWIDTH_FRACTION);
    }

    /**
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     * @param maxInitialBitrate The maximum bitrate in bits per second that should be assumed
     *     when bandwidthMeter cannot provide an estimate due to playback having only just started.
     * @param minDurationForQualityIncreaseMs The minimum duration of buffered data required for
     *     the evaluator to consider switching to a higher quality format.
     * @param maxDurationForQualityDecreaseMs The maximum duration of buffered data required for
     *     the evaluator to consider switching to a lower quality format.
     * @param minDurationToRetainAfterDiscardMs When switching to a significantly higher quality
     *     format, the evaluator may discard some of the media that it has already buffered at the
     *     lower quality, so as to switch up to the higher quality faster. This is the minimum
     *     duration of media that must be retained at the lower quality.
     * @param bandwidthFraction The fraction of the available bandwidth that the evaluator should
     *     consider available for use. Setting to a value less than 1 is recommended to account
     *     for inaccuracies in the bandwidth estimator.
     */
    public AdaptiveEvaluator(BandwidthMeter bandwidthMeter,
        int maxInitialBitrate,
        int minDurationForQualityIncreaseMs,
        int maxDurationForQualityDecreaseMs,
        int minDurationToRetainAfterDiscardMs,
        float bandwidthFraction) {
      this.bandwidthMeter = bandwidthMeter;
      this.maxInitialBitrate = maxInitialBitrate;
      this.minDurationForQualityIncreaseUs = minDurationForQualityIncreaseMs * 1000L;
      this.maxDurationForQualityDecreaseUs = maxDurationForQualityDecreaseMs * 1000L;
      this.minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardMs * 1000L;
      this.bandwidthFraction = bandwidthFraction;
    }

    @Override
    public void enable() {
      // Do nothing.
    }

    @Override
    public void disable() {
      // Do nothing.
    }

    @Override
    public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
        Format[] formats, Evaluation evaluation) {
      long bufferedDurationUs = queue.isEmpty() ? 0
          : queue.get(queue.size() - 1).endTimeUs - playbackPositionUs;
      Format current = evaluation.format;
      Format ideal = determineIdealFormat(formats, bandwidthMeter.getBitrateEstimate());
      System.out.printf("ideal format is [%s] [%d*%d].\n",ideal.id,ideal.width,ideal.height);
      boolean isHigher = ideal != null && current != null && ideal.bitrate > current.bitrate;
      boolean isLower = ideal != null && current != null && ideal.bitrate < current.bitrate;
      if (isHigher) {
        if (bufferedDurationUs < minDurationForQualityIncreaseUs) {
          // The ideal format is a higher quality, but we have insufficient buffer to
          // safely switch up. Defer switching up for now.
          ideal = current;
        } else if (bufferedDurationUs >= minDurationToRetainAfterDiscardUs) {
          // We're switching from an SD stream to a stream of higher resolution. Consider
          // discarding already buffered media chunks. Specifically, discard media chunks starting
          // from the first one that is of lower bandwidth, lower resolution and that is not HD.
          for (int i = 1; i < queue.size(); i++) {
            MediaChunk thisChunk = queue.get(i);
            long durationBeforeThisSegmentUs = thisChunk.startTimeUs - playbackPositionUs;
            if (durationBeforeThisSegmentUs >= minDurationToRetainAfterDiscardUs
                && thisChunk.format.bitrate < ideal.bitrate
                && thisChunk.format.height < ideal.height
                && thisChunk.format.height < 720
                && thisChunk.format.width < 1280) {
              // Discard chunks from this one onwards.
              evaluation.queueSize = i;
              break;
            }
          }
        }
      } else if (isLower && current != null
        && bufferedDurationUs >= maxDurationForQualityDecreaseUs) {
        // The ideal format is a lower quality, but we have sufficient buffer to defer switching
        // down for now.
        ideal = current;
      }
      if (current != null && ideal != current) {
        evaluation.trigger = FormatEvaluator.TRIGGER_ADAPTIVE;
        //System.out.printf("We evaluator the bitrate here.\n");
        //System.out.printf("Current format is [%s] [%d*%d].\n",current.id,current.width,current.height);
      }
      evaluation.format = ideal;
    }

    /**
     * Compute the ideal format ignoring buffer health.
     */
    protected Format determineIdealFormat(Format[] formats, long bitrateEstimate) {
      long effectiveBitrate = computeEffectiveBitrateEstimate(bitrateEstimate);
      for (int i = 0; i < formats.length; i++) {
        System.out.printf("format[%d] is [%s] [%d*%d].\n",i,formats[i].id,formats[i].width,formats[i].height);
        Format format = formats[i];
        if (format.bitrate <= effectiveBitrate) {
          return format;
        }
      }
      // We didn't manage to calculate a suitable format. Return the lowest quality format.
      return formats[formats.length - 1];
    }

    /**
     * Apply overhead factor, or default value in absence of estimate.
     */
    protected long computeEffectiveBitrateEstimate(long bitrateEstimate) {
      return bitrateEstimate == BandwidthMeter.NO_ESTIMATE
          ? maxInitialBitrate : (long) (bitrateEstimate * bandwidthFraction);
    }

  }

}
