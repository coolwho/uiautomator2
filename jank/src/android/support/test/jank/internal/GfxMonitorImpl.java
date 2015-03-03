/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.support.test.jank.internal;

import android.app.UiAutomation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import junit.framework.Assert;

/**
 * Monitors dumpsys gfxinfo to detect janky frames.
 *
 * Reports average and max jank. Additionally reports summary statistics for common problems that
 * can lead to dropped frames.
 */
class GfxMonitorImpl implements JankMonitor {

    // Jank metrics namespace and helper class
    private static final String MONITOR_PREFIX = "gfx";
    private static final MetricsHelper mMetricsHelper = new MetricsHelper(MONITOR_PREFIX);

    // Key values for the metrics reported by this monitor
    private static final String KEY_NUM_JANKY = "jank";
    private static final String KEY_MISSED_VSYNC = "missed-vsync";
    private static final String KEY_HIGH_INPUT_LATENCY = "high-input-latency";
    private static final String KEY_SLOW_UI_THREAD = "slow-ui-thread";
    private static final String KEY_SLOW_BITMAP_UPLOADS = "slow-bitmap-uploads";
    private static final String KEY_SLOW_DRAW = "slow-draw";

    // Patterns used for parsing dumpsys gfxinfo output
    private static final Pattern TOTAL_FRAMES_PATTERN =
            Pattern.compile("\\s*Total frames rendered: (\\d+)");
    private static final Pattern JANKY_FRAMES_PATTERN =
            Pattern.compile("\\s*Janky frames: (\\d+) \\(.*\\)");
    private static final Pattern MISSED_VSYNC_PATTERN =
            Pattern.compile("\\s*Number Missed Vsync: (\\d+)");
    private static final Pattern INPUT_LATENCY_PATTERN =
            Pattern.compile("\\s*Number High input latency: (\\d+)");
    private static final Pattern SLOW_UI_PATTERN =
            Pattern.compile("\\s*Number Slow UI thread: (\\d+)");
    private static final Pattern SLOW_BITMAP_PATTERN =
            Pattern.compile("\\s*Number Slow bitmap uploads: (\\d+)");
    private static final Pattern SLOW_DRAW_PATTERN =
            Pattern.compile("\\s*Number Slow draw: (\\d+)");

    // Used to invoke dumpsys gfxinfo
    private UiAutomation mUiAutomation;
    private String mProcess;

    // Metrics accumulated for each iteration
    private List<Integer> jankyFrames = new ArrayList<Integer>();
    private List<Integer> missedVsync = new ArrayList<Integer>();
    private List<Integer> highInputLatency = new ArrayList<Integer>();
    private List<Integer> slowUiThread = new ArrayList<Integer>();
    private List<Integer> slowBitmapUploads = new ArrayList<Integer>();
    private List<Integer> slowDraw = new ArrayList<Integer>();


    public GfxMonitorImpl(UiAutomation automation, String process) {
        mUiAutomation = automation;
        mProcess = process;
    }

    @Override
    public void startIteration() throws IOException {
        // Clear out any previous data
        ParcelFileDescriptor stdout = mUiAutomation.executeShellCommand(
                String.format("dumpsys gfxinfo %s reset", mProcess));

        // Read the output, but don't do anything with it
        BufferedReader stream = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(stdout)));
        while (stream.readLine() != null) {
        }
    }

    @Override
    public int stopIteration() throws IOException {
        ParcelFileDescriptor stdout = mUiAutomation.executeShellCommand(
                String.format("dumpsys gfxinfo %s", mProcess));
        BufferedReader stream = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(stdout)));

        // Wait until we enter the frame stats section
        String line;
        while ((line = stream.readLine()) != null) {
            if (line.startsWith("Frame stats:")) {
                break;
            }
        }
        Assert.assertTrue("Failed to locate frame stats in gfxinfo output",
                line != null && line.startsWith("Frame stats:"));

        // The frame stats section has the following output:
        // Frame stats:
        //   Total frames rendered: ###
        //   Janky frames: ### (##.##%)
        //    Number Missed Vsync: #
        //    Number High input latency: #
        //    Number Slow UI thread: #
        //    Number Slow bitmap uploads: #
        //    Number Slow draw: #

        // Get Total Frames
        String part;
        if ((part = getMatchGroup(stream.readLine(), TOTAL_FRAMES_PATTERN, 1)) == null) {
            Assert.fail("Failed to parse total frames");
        }
        int totalFrames = Integer.parseInt(part);

        // Get Num Janky
        if ((part = getMatchGroup(stream.readLine(), JANKY_FRAMES_PATTERN, 1)) == null) {
            Assert.fail("Failed to parse janky frames");
        }
        jankyFrames.add(Integer.parseInt(part));

        // Get Missed Vsync
        if ((part = getMatchGroup(stream.readLine(), MISSED_VSYNC_PATTERN, 1)) == null) {
            Assert.fail("Failed to parse number missed vsync");
        }
        missedVsync.add(Integer.parseInt(part));

        // Get High input latency
        if ((part = getMatchGroup(stream.readLine(), INPUT_LATENCY_PATTERN, 1)) == null) {
            Assert.fail("Failed to parse number high input latency");
        }
        highInputLatency.add(Integer.parseInt(part));

        // Get Slow UI thread
        if ((part = getMatchGroup(stream.readLine(), SLOW_UI_PATTERN, 1)) == null) {
            Assert.fail("Failed to parse number slow ui thread");
        }
        slowUiThread.add(Integer.parseInt(part));

        // Get Slow bitmap uploads
        if ((part = getMatchGroup(stream.readLine(), SLOW_BITMAP_PATTERN, 1)) == null) {
            Assert.fail("Failed to parse number slow bitmap uploads");
        }
        slowBitmapUploads.add(Integer.parseInt(part));

        // Get Slow draw
        if ((part = getMatchGroup(stream.readLine(), SLOW_DRAW_PATTERN, 1)) == null) {
            Assert.fail("Failed to parse number slow draw");
        }
        slowDraw.add(Integer.parseInt(part));

        return totalFrames;
    }

    public Bundle getMetrics() {
        Bundle metrics = new Bundle();
        mMetricsHelper.putSummaryMetrics(metrics, KEY_NUM_JANKY, jankyFrames);
        mMetricsHelper.putSummaryMetrics(metrics, KEY_MISSED_VSYNC, missedVsync);
        mMetricsHelper.putSummaryMetrics(metrics, KEY_HIGH_INPUT_LATENCY, highInputLatency);
        mMetricsHelper.putSummaryMetrics(metrics, KEY_SLOW_UI_THREAD, slowUiThread);
        mMetricsHelper.putSummaryMetrics(metrics, KEY_SLOW_BITMAP_UPLOADS, slowBitmapUploads);
        mMetricsHelper.putSummaryMetrics(metrics, KEY_SLOW_DRAW, slowDraw);

        return metrics;
    }

    private String getMatchGroup(String input, Pattern pattern, int groupIndex) {
        String ret = null;
        Matcher matcher = pattern.matcher(input);
        if (matcher.matches()) {
            ret = matcher.group(groupIndex);
        }
        return ret;
    }
}
