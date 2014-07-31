
package com.joysee.portal.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;

import com.joysee.common.utils.JLog;
import com.joysee.portal.MainActivity;
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
        JLog.d(TAG, "setLoadOnResume");
        return true;
    }

    @Override
    public int getCurrentWorkspaceScreen() {
        JLog.d(TAG, "getCurrentWorkspaceScreen ");
        return 0;
    }

    @Override
    public void startBinding() {
        JLog.d(TAG, "startBinding");
    }

    @Override
    public void bindItems(ArrayList<ItemInfo> shortcuts, int start, int end, boolean forceAnimateIcons) {
        JLog.d(TAG, "bindItems");
        for (int i = 0; i < shortcuts.size(); i++) {
            JLog.d(TAG, " ### item "+shortcuts.get(i).title);
        }
    }

    @Override
    public void bindScreens(ArrayList<Long> orderedScreenIds) {
        JLog.d(TAG, "bindScreens");
        for (int i = 0; i < orderedScreenIds.size(); i++) {
            JLog.d(TAG, " ### screen "+orderedScreenIds.get(i));
        }
    }

    @Override
    public void bindAddScreens(ArrayList<Long> orderedScreenIds) {
        JLog.d(TAG, "bindAddScreens");
        for (int i = 0; i < orderedScreenIds.size(); i++) {
            JLog.d(TAG, " ### add screen "+orderedScreenIds.get(i));
        }
    }

    @Override
    public void finishBindingItems(boolean upgradePath) {
        JLog.d(TAG, "finishBindingItems "+upgradePath);
    }

    @Override
    public void bindAllApplications(ArrayList<AppInfo> apps) {
        JLog.d(TAG, "bindAllApplications");
        for (int i = 0; i < apps.size(); i++) {
            JLog.d(TAG, " ### app "+apps.get(i).title);
        }
    }

    @Override
    public void bindAppsAdded(ArrayList<Long> newScreens, ArrayList<ItemInfo> addNotAnimated, ArrayList<ItemInfo> addAnimated,
            ArrayList<AppInfo> addedApps) {
        JLog.d(TAG, "bindAppsAdded");
        for (int i = 0; i < newScreens.size(); i++) {
            JLog.d(TAG, " ### newScreens "+newScreens.get(i));
        }
        for (int i = 0; i < addedApps.size(); i++) {
            JLog.d(TAG, " ### app "+addedApps.get(i).title);
        }

    }

    @Override
    public void bindAppsUpdated(ArrayList<AppInfo> apps) {
        JLog.d(TAG, "bindAppsUpdated");
    }

    @Override
    public void bindComponentsRemoved(ArrayList<String> packageNames, ArrayList<AppInfo> appInfos, boolean matchPackageNamesOnly) {
        JLog.d(TAG, "bindComponentsRemoved");
    }

    @Override
    public void bindPackagesUpdated(ArrayList<Object> widgetsAndShortcuts) {
        JLog.d(TAG, "bindPackagesUpdated");
    }

    @Override
    public void bindSearchablesChanged() {
        JLog.d(TAG, "bindSearchablesChanged");
    }

    @Override
    public boolean isAllAppsButtonRank(int rank) {
        JLog.d(TAG, "isAllAppsButtonRank");
        return false;
    }

    @Override
    public void onPageBoundSynchronously(int page) {
        JLog.d(TAG, "onPageBoundSynchronously");
    }

    @Override
    public void dumpLogsToLocalData() {
        JLog.d(TAG, "dumpLogsToLocalData");
    }

    // #######################################################################################################

    public static final String TAG = JLog.makeTag(Launcher.class)+"#";
    static final boolean DEBUG_STRICT_MODE = false;
    static final boolean STARTUP_TRACING_PROFILE = false;
    
    private LauncherModel mLauncherModel;

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
        if (STARTUP_TRACING_PROFILE) {
            android.os.Debug.stopMethodTracing();
        }

        mPaused = false;
        setContentView(R.layout.launcher);

        
        Button init = (Button) findViewById(R.id.init_onCreate);
        init.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                create();
            }
        });
        Button load = (Button) findViewById(R.id.start_load);
        load.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isCreate) {
                    mLauncherModel.startLoader(true, -1);
                }
            }
        });
    }
    
    private boolean isCreate = false;
    private void create() {
        long begin = JLog.methodBegin(TAG);
        
        isCreate = true;
        LauncherAppState.setApplicationContext(this);
        LauncherAppState app = LauncherAppState.getInstance();
        mLauncherModel = app.setLauncher(this);
        
        Point smallestSize = new Point();
        Point largestSize = new Point();
        Point realSize = new Point();
        Display display = getWindowManager().getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getMetrics(dm);
        DeviceProfile grid = app.initDynamicGrid(this,Math.min(smallestSize.x, smallestSize.y),
                Math.min(largestSize.x, largestSize.y),
                realSize.x, realSize.y,
                dm.widthPixels, dm.heightPixels);
        
        JLog.methodEnd(TAG, begin, "to create");
        
        Intent intent = new Intent(this, MainActivity.class);
        Log.d(TAG, "intent uri = "+intent.toUri(0));
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
