/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.joysee.portal.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.TypedValue;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

class DeviceProfileQuery {
    float widthDps;
    float heightDps;
    float value;
    PointF dimens;

    DeviceProfileQuery(float w, float h, float v) {
        widthDps = w;
        heightDps = h;
        value = v;
        dimens = new PointF(w, h);
    }
}

class DeviceProfile {
    String name;
    float minWidthDps;
    float minHeightDps;
    float numRows;
    float numColumns;
    float iconSize;
    float iconTextSize;
    float numHotseatIcons;
    float hotseatIconSize;

    boolean isLandscape;
    boolean isTablet;
    boolean isLargeTablet;
    boolean transposeLayoutWithOrientation;

    int desiredWorkspaceLeftRightMarginPx;
    int edgeMarginPx;
    Rect defaultWidgetPadding;

    int widthPx;
    int heightPx;
    int availableWidthPx;
    int availableHeightPx;
    int iconSizePx;
    int iconTextSizePx;
    int cellWidthPx;
    int cellHeightPx;
    int folderBackgroundOffset;
    int folderIconSizePx;
    int folderCellWidthPx;
    int folderCellHeightPx;
    int hotseatCellWidthPx;
    int hotseatCellHeightPx;
    int hotseatIconSizePx;
    int hotseatBarHeightPx;
    int hotseatAllAppsRank;
    int allAppsNumRows;
    int allAppsNumCols;
    int searchBarSpaceWidthPx;
    int searchBarSpaceMaxWidthPx;
    int searchBarSpaceHeightPx;
    int searchBarHeightPx;
    int pageIndicatorHeightPx;

    DeviceProfile(String n, float w, float h, float r, float c, float is, float its, float hs, float his) {
        name = n;
        minWidthDps = w;
        minHeightDps = h;
        numRows = r;
        numColumns = c;
        iconSize = is;
        iconTextSize = its;
        numHotseatIcons = hs;
        hotseatIconSize = his;
    }

    DeviceProfile(Context context,
            ArrayList<DeviceProfile> profiles,
            float minWidth, float minHeight,
            int wPx, int hPx,
            int awPx, int ahPx,
            Resources resources) {
    }

    void updateFromConfiguration(Resources resources, int wPx, int hPx, int awPx, int ahPx) {
    }

    private float dist(PointF p0, PointF p1) {
        return (float) Math.sqrt((p1.x - p0.x) * (p1.x - p0.x) +
                (p1.y - p0.y) * (p1.y - p0.y));
    }

    private float weight(PointF a, PointF b,
            float pow) {
        float d = dist(a, b);
        if (d == 0f) {
            return Float.POSITIVE_INFINITY;
        }
        return (float) (1f / Math.pow(d, pow));
    }

    private float invDistWeightedInterpolate(float width, float height, ArrayList<DeviceProfileQuery> points) {
        float sum = 0;
        float weights = 0;
        float pow = 5;
        float kNearestNeighbors = 3;
        final PointF xy = new PointF(width, height);

        ArrayList<DeviceProfileQuery> pointsByNearness = points;
        Collections.sort(pointsByNearness, new Comparator<DeviceProfileQuery>() {
            public int compare(DeviceProfileQuery a, DeviceProfileQuery b) {
                return (int) (dist(xy, a.dimens) - dist(xy, b.dimens));
            }
        });

        for (int i = 0; i < pointsByNearness.size(); ++i) {
            DeviceProfileQuery p = pointsByNearness.get(i);
            if (i < kNearestNeighbors) {
                float w = weight(xy, p.dimens, pow);
                if (w == Float.POSITIVE_INFINITY) {
                    return p.value;
                }
                weights += w;
            }
        }

        for (int i = 0; i < pointsByNearness.size(); ++i) {
            DeviceProfileQuery p = pointsByNearness.get(i);
            if (i < kNearestNeighbors) {
                float w = weight(xy, p.dimens, pow);
                sum += w * p.value / weights;
            }
        }

        return sum;
    }

    Rect getWorkspacePadding(int orientation) {
        Rect padding = new Rect();
        return padding;
    }

    // The rect returned will be extended to below the system ui that covers the
    // workspace
    Rect getHotseatRect() {
        if (isVerticalBarLayout()) {
            return new Rect(availableWidthPx - hotseatBarHeightPx, 0,
                    Integer.MAX_VALUE, availableHeightPx);
        } else {
            return new Rect(0, availableHeightPx - hotseatBarHeightPx,
                    availableWidthPx, Integer.MAX_VALUE);
        }
    }

    int calculateCellWidth(int width, int countX) {
        return width / countX;
    }

    int calculateCellHeight(int height, int countY) {
        return height / countY;
    }

    boolean isPhone() {
        return !isTablet && !isLargeTablet;
    }

    boolean isTablet() {
        return isTablet;
    }

    boolean isLargeTablet() {
        return isLargeTablet;
    }

    boolean isVerticalBarLayout() {
        return isLandscape && transposeLayoutWithOrientation;
    }

    public void layout(Launcher launcher) {

    }
}

public class DynamicGrid {
    @SuppressWarnings("unused")
    private static final String TAG = "DynamicGrid";

    private DeviceProfile mProfile;
    private float mMinWidth;
    private float mMinHeight;

    public static float dpiFromPx(int size, DisplayMetrics metrics) {
        float densityRatio = (float) metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT;
        return (size / densityRatio);
    }

    public static int pxFromDp(float size, DisplayMetrics metrics) {
        return (int) Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                size, metrics));
    }

    public static int pxFromSp(float size, DisplayMetrics metrics) {
        return (int) Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                size, metrics));
    }

    public DynamicGrid(Context context, Resources resources,
            int minWidthPx, int minHeightPx,
            int widthPx, int heightPx,
            int awPx, int ahPx) {

        mProfile = new DeviceProfile(context, null,
                mMinWidth, mMinHeight,
                widthPx, heightPx,
                awPx, ahPx,
                resources);
    }

    DeviceProfile getDeviceProfile() {
        return mProfile;
    }

}
