
package com.joysee.portal.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.joysee.common.utils.JLog;
import com.joysee.portal.R;
import com.joysee.portal.launcher.LauncherModel.Callbacks;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

public class Launcher extends Activity implements Callbacks {

    interface LauncherTransitionable {
        View getContent();

        void onLauncherTransitionPrepare(Launcher l, boolean animated, boolean toWorkspace);

        void onLauncherTransitionStart(Launcher l, boolean animated, boolean toWorkspace);

        void onLauncherTransitionStep(Launcher l, float t);

        void onLauncherTransitionEnd(Launcher l, boolean animated, boolean toWorkspace);
    }

    @Override
    public boolean setLoadOnResume() {
        return false;
    }

    @Override
    public int getCurrentWorkspaceScreen() {
        return 0;
    }

    @Override
    public void startBinding() {

    }

    @Override
    public void bindItems(ArrayList<ItemInfo> shortcuts, int start, int end, boolean forceAnimateIcons) {

    }

    @Override
    public void bindScreens(ArrayList<Long> orderedScreenIds) {

    }

    @Override
    public void bindAddScreens(ArrayList<Long> orderedScreenIds) {

    }

    @Override
    public void finishBindingItems(boolean upgradePath) {

    }

    @Override
    public void bindAllApplications(ArrayList<AppInfo> apps) {

    }

    @Override
    public void bindAppsAdded(ArrayList<Long> newScreens, ArrayList<ItemInfo> addNotAnimated, ArrayList<ItemInfo> addAnimated,
            ArrayList<AppInfo> addedApps) {

    }

    @Override
    public void bindAppsUpdated(ArrayList<AppInfo> apps) {

    }

    @Override
    public void bindComponentsRemoved(ArrayList<String> packageNames, ArrayList<AppInfo> appInfos, boolean matchPackageNamesOnly) {

    }

    @Override
    public void bindPackagesUpdated(ArrayList<Object> widgetsAndShortcuts) {

    }

    @Override
    public void bindSearchablesChanged() {

    }

    @Override
    public boolean isAllAppsButtonRank(int rank) {
        return false;
    }

    @Override
    public void onPageBoundSynchronously(int page) {

    }

    @Override
    public void dumpLogsToLocalData() {

    }

    // #######################################################################################################

    public static final String TAG = JLog.makeTag(Launcher.class);
    static final boolean DEBUG_STRICT_MODE = false;
    static final boolean STARTUP_TRACING_PROFILE = false;

    private boolean mPaused = true;
    static Date sDateStamp = new Date();
    static final int SCREEN_COUNT = 5;
    static final ArrayList<String> sDumpLogs = new ArrayList<String>();
    static DateFormat sDateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG_STRICT_MODE) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .penaltyDeath()
                    .build());
        }
        if (STARTUP_TRACING_PROFILE) {
            android.os.Debug.startMethodTracing(Environment.getExternalStorageDirectory() + "/joysee_launcher");
        }

        mPaused = false;
        setContentView(R.layout.launcher);

        // setup views

        if (STARTUP_TRACING_PROFILE) {
            android.os.Debug.stopMethodTracing();
        }

        /**
         * 1.load DB
         * 2.
         * 
         * 
         */
    }

    public static void addDumpLog(String tag, String log, boolean debugLog) {
        if (debugLog) {
            Log.d(tag, log);
        }
        if (true) {
            sDateStamp.setTime(System.currentTimeMillis());
            synchronized (sDumpLogs) {
                sDumpLogs.add(sDateFormat.format(sDateStamp) + ": " + tag + ", " + log);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Log.d(TAG, "dispatchKeyEvent");
        return super.dispatchKeyEvent(event);
    }

}
