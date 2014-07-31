/**
 * =====================================================================
 *
 * @file  LauncherModel.java
 * @Module Name   com.joysee.portal.launcher
 * @author benz
 * @OS version  1.0
 * @Product type: JoySee
 * @date   2014-7-28
 * @brief  This file is the http **** implementation.
 * @This file is responsible by ANDROID TEAM.
 * @Comments: 
 * =====================================================================
 * Revision History:
 *
 *                   Modification  Tracking
 *
 * Author            Date            OS version        Reason 
 * ----------      ------------     -------------     -----------
 * benz          2014-7-28           1.0         Check for NULL, 0 h/w
 * =====================================================================
 **/

package com.joysee.portal.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.*;
import android.content.Intent.ShortcutIconResource;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import com.joysee.portal.launcher.LauncherProvider.FavoritesColumns;
import com.joysee.portal.launcher.LauncherProvider.FavoritesContainerType;
import com.joysee.portal.launcher.LauncherProvider.FavoritesIconType;
import com.joysee.portal.launcher.LauncherProvider.FavoritesType;
import com.joysee.portal.launcher.LauncherProvider.FavoritesURI;
import com.joysee.portal.launcher.LauncherProvider.WorkspaceScreensURI;
import com.joysee.portal.launcher.LauncherProvider.WorkspacesColumns;
import com.joysee.portal.launcher.utils.Utilities;

import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class LauncherModel extends BroadcastReceiver {
    static final String TAG = "Launcher.Model";

    /**
     * true is use a "More Apps" folder for non-workspace APPS on upgrade; false
     * is strew non-workspace apps across the workspace on upgrade
     */
    public static final boolean UPGRADE_USE_MORE_APPS_FOLDER = false;

    /** batch size for the workspace icons */
    private static final int ITEMS_CHUNK = 6;
    private final boolean mAppsCanBeOnRemoveableStorage;

    private final LauncherAppState mApp;
    private final Object mLock = new Object();
    private DeferredHandler mHandler = new DeferredHandler();
    private LoaderTask mLoaderTask;
    private boolean mIsLoaderTaskRunning;
    private volatile boolean mFlushingWorkerThread;

    private static final int MAIN_THREAD_NORMAL_RUNNABLE = 0;
    private static final int MAIN_THREAD_BINDING_RUNNABLE = 1;

    private static final HandlerThread sWorkerThread = new HandlerThread("launcher-loader");
    static {
        sWorkerThread.start();
    }
    private static final Handler sWorker = new Handler(sWorkerThread.getLooper());

    private boolean mWorkspaceLoaded;
    private boolean mAllAppsLoaded;

    /**
     * When we are loading pages synchronously, we can't just post the binding
     * of items on the side pages as this delays the rotation process. Instead,
     * we wait for a callback from the first draw (in Workspace) to initiate the
     * binding of the remaining side pages. Any time we start a normal load, we
     * also clear this set of Runnable.
     */
    static final ArrayList<Runnable> mDeferredBindRunnables = new ArrayList<Runnable>();

    private WeakReference<Callbacks> mCallbacks;

    /** only access in worker thread */
    AllAppsList mBgAllAppsList;

    /**
     * The lock that must be acquired before referencing any static bg data
     * structures. Unlike other locks, this one can generally be held long-term
     * because we never expect any of these static data structures to be
     * referenced outside of the worker thread except on the first load after
     * configuration change.
     */
    static final Object sBgLock = new Object();

    /**
     * sBgItemsIdMap maps *all* the ItemInfos (shortcuts, folders, and widgets)
     * created by LauncherModel to their ids
     */
    static final HashMap<Long, ItemInfo> sBgItemsIdMap = new HashMap<Long, ItemInfo>();

    /**
     * sBgWorkspaceItems is passed to bindItems, which expects a list of all
     * folders and shortcuts created by LauncherModel that are directly on the
     * home screen (however, no widgets or shortcuts within folders).
     */
    static final ArrayList<ItemInfo> sBgWorkspaceItems = new ArrayList<ItemInfo>();

    /**
     * sBgDbIconCache is the set of ItemInfos that need to have their icons
     * updated in the database
     */
    static final HashMap<Object, byte[]> sBgDbIconCache = new HashMap<Object, byte[]>();

    /**
     * sBgWorkspaceScreens is the ordered set of workspace screens.
     */
    static final ArrayList<Long> sBgWorkspaceScreens = new ArrayList<Long>();

    /** only access in worker thread */
    private IconCache mIconCache;
    private Bitmap mDefaultIcon;
    protected int mPreviousConfigMcc;
    
    private static final boolean DEBUG_TIME_USE = true;

    public interface Callbacks {
        public boolean setLoadOnResume();

        public int getCurrentWorkspaceScreen();

        public void startBinding();

        public void bindItems(ArrayList<ItemInfo> shortcuts, int start, int end,
                boolean forceAnimateIcons);

        public void bindScreens(ArrayList<Long> orderedScreenIds);

        public void bindAddScreens(ArrayList<Long> orderedScreenIds);

        public void finishBindingItems(boolean upgradePath);

        public void bindAllApplications(ArrayList<AppInfo> apps);

        public void bindAppsAdded(ArrayList<Long> newScreens,
                ArrayList<ItemInfo> addNotAnimated,
                ArrayList<ItemInfo> addAnimated,
                ArrayList<AppInfo> addedApps);

        public void bindAppsUpdated(ArrayList<AppInfo> apps);

        public void bindComponentsRemoved(ArrayList<String> packageNames,
                ArrayList<AppInfo> appInfos,
                boolean matchPackageNamesOnly);

        public void bindPackagesUpdated(ArrayList<Object> widgetsAndShortcuts);

        public void bindSearchablesChanged();

        public boolean isAllAppsButtonRank(int rank);

        public void onPageBoundSynchronously(int page);

        public void dumpLogsToLocalData();
    }

    public interface ItemInfoFilter {
        public boolean filterItem(ItemInfo parent, ItemInfo info, ComponentName cn);
    }

    LauncherModel(LauncherAppState app, IconCache iconCache, AppFilter appFilter) {
        final Context context = app.getContext();
        mAppsCanBeOnRemoveableStorage = Environment.isExternalStorageRemovable();
        mApp = app;
        mBgAllAppsList = new AllAppsList(iconCache, appFilter);
        mIconCache = iconCache;
        mDefaultIcon = Utilities.createIconBitmap(mIconCache.getFullResDefaultActivityIcon(), context);
        final Resources res = context.getResources();
        Configuration config = res.getConfiguration();
        mPreviousConfigMcc = config.mcc;
    }

    private void runOnMainThread(Runnable r) {
        runOnMainThread(r, 0);
    }

    private void runOnMainThread(Runnable r, int type) {
        if (sWorkerThread.getThreadId() == Process.myTid()) {
            mHandler.post(r);
        } else {
            r.run();
        }
    }

    private static void runOnWorkerThread(Runnable r) {
        if (sWorkerThread.getThreadId() == Process.myTid()) {
            r.run();
        } else {
            sWorker.post(r);
        }
    }

    static boolean findNextAvailableIconSpaceInScreen(ArrayList<ItemInfo> items, int[] xy, long screen) {
        LauncherAppState app = LauncherAppState.getInstance();
        DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
        final int xCount = (int) grid.numColumns;
        final int yCount = (int) grid.numRows;
        boolean[][] occupied = new boolean[xCount][yCount];

        int cellX, cellY, spanX, spanY;
        for (int i = 0; i < items.size(); ++i) {
            final ItemInfo item = items.get(i);
            if (item.container == FavoritesContainerType.CONTAINER_DESKTOP) {
                if (item.screenId == screen) {
                    cellX = item.cellX;
                    cellY = item.cellY;
                    spanX = item.spanX;
                    spanY = item.spanY;
                    for (int x = cellX; 0 <= x && x < cellX + spanX && x < xCount; x++) {
                        for (int y = cellY; 0 <= y && y < cellY + spanY && y < yCount; y++) {
                            occupied[x][y] = true;
                        }
                    }
                }
            }
        }
        return false;
        // return CellLayout.findVacantCell(xy, 1, 1, xCount, yCount, occupied);
    }

    static Pair<Long, int[]> findNextAvailableIconSpace(Context context, String name, Intent launchIntent, int firstScreenIndex,
            ArrayList<Long> workspaceScreens) {
        /**
         * Lock on the app so that we don't try and get the items while apps are
         * being added
         */
        LauncherAppState app = LauncherAppState.getInstance();
        LauncherModel model = app.getModel();
        boolean found = false;
        synchronized (app) {
            if (sWorkerThread.getThreadId() != Process.myTid()) {
                /**
                 * Flush the LauncherModel worker thread, so that if we just did
                 * another processInstallShortcut, we give it time for its
                 * shortcut to get added to the database
                 * (getItemsInLocalCoordinates reads the database)
                 */
                model.flushWorkerThread();
            }
            final ArrayList<ItemInfo> items = LauncherModel.getItemsInLocalCoordinates(context);
            /**
             * Try adding to the workspace screens incrementally, starting at
             * the default or center screen and alternating between +1, -1, +2,
             * -2, etc. (using ~ ceil(i/2f)*(-1)^(i-1))
             */
            firstScreenIndex = Math.min(firstScreenIndex, workspaceScreens.size());
            int count = workspaceScreens.size();
            for (int screen = firstScreenIndex; screen < count && !found; screen++) {
                int[] tmpCoordinates = new int[2];
                if (findNextAvailableIconSpaceInScreen(items, tmpCoordinates,
                        workspaceScreens.get(screen))) {
                    /** Update the Launcher db */
                    return new Pair<Long, int[]>(workspaceScreens.get(screen), tmpCoordinates);
                }
            }
        }
        return null;
    }

    public void addAndBindAddedApps(final Context context, final ArrayList<ItemInfo> workspaceApps,
            final ArrayList<AppInfo> allAppsApps) {
        Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;
        addAndBindAddedApps(context, workspaceApps, cb, allAppsApps);
    }

    public void addAndBindAddedApps(final Context context, final ArrayList<ItemInfo> workspaceApps,
            final Callbacks callbacks, final ArrayList<AppInfo> allAppsApps) {
        if (workspaceApps.isEmpty() && allAppsApps.isEmpty()) {
            return;
        }
        // Process the newly added applications and add them to the database
        // first
        Runnable r = new Runnable() {
            public void run() {
                final ArrayList<ItemInfo> addedShortcutsFinal = new ArrayList<ItemInfo>();
                final ArrayList<Long> addedWorkspaceScreensFinal = new ArrayList<Long>();

                // Get the list of workspace screens. We need to append to this
                // list and
                // can not use sBgWorkspaceScreens because loadWorkspace() may
                // not have been
                // called.
                ArrayList<Long> workspaceScreens = new ArrayList<Long>();
                TreeMap<Integer, Long> orderedScreens = loadWorkspaceScreensDb(context);
                for (Integer i : orderedScreens.keySet()) {
                    long screenId = orderedScreens.get(i);
                    workspaceScreens.add(screenId);
                }

                synchronized (sBgLock) {
                    Iterator<ItemInfo> iter = workspaceApps.iterator();
                    while (iter.hasNext()) {
                        ItemInfo a = iter.next();
                        final String name = a.title.toString();
                        final Intent launchIntent = a.getIntent();

                        // Short-circuit this logic if the icon exists somewhere
                        // on the workspace
                        if (LauncherModel.shortcutExists(context, name, launchIntent)) {
                            continue;
                        }

                        // Add this icon to the db, creating a new page if
                        // necessary. If there
                        // is only the empty page then we just add items to the
                        // first page.
                        // Otherwise, we add them to the next pages.
                        int startSearchPageIndex = workspaceScreens.isEmpty() ? 0 : 1;
                        Pair<Long, int[]> coords = LauncherModel.findNextAvailableIconSpace(context,
                                name, launchIntent, startSearchPageIndex, workspaceScreens);
                        if (coords == null) {
                            LauncherProvider lp = LauncherAppState.getLauncherProvider();

                            // If we can't find a valid position, then just add
                            // a new screen.
                            // This takes time so we need to re-queue the add
                            // until the new
                            // page is added. Create as many screens as
                            // necessary to satisfy
                            // the startSearchPageIndex.
                            int numPagesToAdd = Math.max(1, startSearchPageIndex + 1 -
                                    workspaceScreens.size());
                            while (numPagesToAdd > 0) {
                                long screenId = lp.generateNewScreenId();
                                // Save the screen id for binding in the
                                // workspace
                                workspaceScreens.add(screenId);
                                addedWorkspaceScreensFinal.add(screenId);
                                numPagesToAdd--;
                            }

                            // Find the coordinate again
                            coords = LauncherModel.findNextAvailableIconSpace(context,
                                    name, launchIntent, startSearchPageIndex, workspaceScreens);
                        }
                        if (coords == null) {
                            throw new RuntimeException("Coordinates should not be null");
                        }

                        ShortcutInfo shortcutInfo;
                        if (a instanceof ShortcutInfo) {
                            shortcutInfo = (ShortcutInfo) a;
                        } else if (a instanceof AppInfo) {
                            shortcutInfo = ((AppInfo) a).makeShortcut();
                        } else {
                            throw new RuntimeException("Unexpected info type");
                        }

                        // Add the shortcut to the db
                        addItemToDatabase(context, shortcutInfo,
                                FavoritesContainerType.CONTAINER_DESKTOP,
                                coords.first, coords.second[0], coords.second[1], false);
                        // Save the ShortcutInfo for binding in the workspace
                        addedShortcutsFinal.add(shortcutInfo);
                    }
                }

                // Update the workspace screens
                updateWorkspaceScreenOrder(context, workspaceScreens);

                if (!addedShortcutsFinal.isEmpty() || !allAppsApps.isEmpty()) {
                    runOnMainThread(new Runnable() {
                        public void run() {
                            Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;
                            if (callbacks == cb && cb != null) {
                                final ArrayList<ItemInfo> addAnimated = new ArrayList<ItemInfo>();
                                final ArrayList<ItemInfo> addNotAnimated = new ArrayList<ItemInfo>();
                                if (!addedShortcutsFinal.isEmpty()) {
                                    ItemInfo info = addedShortcutsFinal.get(addedShortcutsFinal.size() - 1);
                                    long lastScreenId = info.screenId;
                                    for (ItemInfo i : addedShortcutsFinal) {
                                        if (i.screenId == lastScreenId) {
                                            addAnimated.add(i);
                                        } else {
                                            addNotAnimated.add(i);
                                        }
                                    }
                                }
                                callbacks.bindAppsAdded(addedWorkspaceScreensFinal,
                                        addNotAnimated, addAnimated, allAppsApps);
                            }
                        }
                    });
                }
            }
        };
        runOnWorkerThread(r);
    }

    public Bitmap getFallbackIcon() {
        return Bitmap.createBitmap(mDefaultIcon);
    }

    public void unbindItemInfosAndClearQueuedBindRunnables() {
        if (sWorkerThread.getThreadId() == Process.myTid()) {
            throw new RuntimeException("Expected unbindLauncherItemInfos() to be called from the " +
                    "main thread");
        }

        // Clear any deferred bind runnables
        mDeferredBindRunnables.clear();
        // Remove any queued bind runnables
        mHandler.cancelAllRunnablesOfType(MAIN_THREAD_BINDING_RUNNABLE);
        // Unbind all the workspace items
        unbindWorkspaceItemsOnMainThread();
    }

    /** Unbinds all the sBgWorkspaceItems and sBgAppWidgets on the main thread */
    void unbindWorkspaceItemsOnMainThread() {
        // Ensure that we don't use the same workspace items data structure on
        // the main thread
        // by making a copy of workspace items first.
        final ArrayList<ItemInfo> tmpWorkspaceItems = new ArrayList<ItemInfo>();
        synchronized (sBgLock) {
            tmpWorkspaceItems.addAll(sBgWorkspaceItems);
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                for (ItemInfo item : tmpWorkspaceItems) {
                    item.unbind();
                }
            }
        };
        runOnMainThread(r);
    }

    /**
     * Adds an item to the DB if it was not created previously, or move it to a
     * new <container, screen, cellX, cellY>
     */
    static void addOrMoveItemInDatabase(Context context, ItemInfo item, long container,
            long screenId, int cellX, int cellY) {
        if (item.container == ItemInfo.NO_ID) {
            // From all apps
            addItemToDatabase(context, item, container, screenId, cellX, cellY, false);
        } else {
            // From somewhere else
            moveItemInDatabase(context, item, container, screenId, cellX, cellY);
        }
    }

    static void checkItemInfoLocked(
            final long itemId, final ItemInfo item, StackTraceElement[] stackTrace) {
        ItemInfo modelItem = sBgItemsIdMap.get(itemId);
        if (modelItem != null && item != modelItem) {
            // check all the data is consistent
            if (modelItem instanceof ShortcutInfo && item instanceof ShortcutInfo) {
                ShortcutInfo modelShortcut = (ShortcutInfo) modelItem;
                ShortcutInfo shortcut = (ShortcutInfo) item;
                if (modelShortcut.title.toString().equals(shortcut.title.toString()) &&
                        modelShortcut.intent.filterEquals(shortcut.intent) &&
                        modelShortcut.id == shortcut.id &&
                        modelShortcut.itemType == shortcut.itemType &&
                        modelShortcut.container == shortcut.container &&
                        modelShortcut.screenId == shortcut.screenId &&
                        modelShortcut.cellX == shortcut.cellX &&
                        modelShortcut.cellY == shortcut.cellY &&
                        modelShortcut.spanX == shortcut.spanX &&
                        modelShortcut.spanY == shortcut.spanY &&
                        ((modelShortcut.dropPos == null && shortcut.dropPos == null) ||
                        (modelShortcut.dropPos != null &&
                                shortcut.dropPos != null &&
                                modelShortcut.dropPos[0] == shortcut.dropPos[0] &&
                        modelShortcut.dropPos[1] == shortcut.dropPos[1]))) {
                    // For all intents and purposes, this is the same object
                    return;
                }
            }

            // the modelItem needs to match up perfectly with item if our model
            // is
            // to be consistent with the database-- for now, just require
            // modelItem == item or the equality check above
            String msg = "item: " + ((item != null) ? item.toString() : "null") +
                    "modelItem: " +
                    ((modelItem != null) ? modelItem.toString() : "null") +
                    "Error: ItemInfo passed to checkItemInfo doesn't match original";
            RuntimeException e = new RuntimeException(msg);
            if (stackTrace != null) {
                e.setStackTrace(stackTrace);
            }
            // TODO: something breaks this in the upgrade path
            // throw e;
        }
    }

    static void checkItemInfo(final ItemInfo item) {
        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        final long itemId = item.id;
        Runnable r = new Runnable() {
            public void run() {
                synchronized (sBgLock) {
                    checkItemInfoLocked(itemId, item, stackTrace);
                }
            }
        };
        runOnWorkerThread(r);
    }

    static void updateItemInDatabaseHelper(Context context, final ContentValues values,
            final ItemInfo item, final String callingFunction) {
        final long itemId = item.id;
        final Uri uri = FavoritesURI.getContentUri(itemId, false);
        final ContentResolver cr = context.getContentResolver();

        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        Runnable r = new Runnable() {
            public void run() {
                cr.update(uri, values, null, null);
                updateItemArrays(item, itemId, stackTrace);
            }
        };
        runOnWorkerThread(r);
    }

    static void updateItemsInDatabaseHelper(Context context, final ArrayList<ContentValues> valuesList,
            final ArrayList<ItemInfo> items, final String callingFunction) {
        final ContentResolver cr = context.getContentResolver();

        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        Runnable r = new Runnable() {
            public void run() {
                ArrayList<ContentProviderOperation> ops =
                        new ArrayList<ContentProviderOperation>();
                int count = items.size();
                for (int i = 0; i < count; i++) {
                    ItemInfo item = items.get(i);
                    final long itemId = item.id;
                    final Uri uri = FavoritesURI.getContentUri(itemId, false);
                    ContentValues values = valuesList.get(i);

                    ops.add(ContentProviderOperation.newUpdate(uri).withValues(values).build());
                    updateItemArrays(item, itemId, stackTrace);

                }
                try {
                    cr.applyBatch(LauncherProvider.AUTHORITY, ops);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        runOnWorkerThread(r);
    }

    static void updateItemArrays(ItemInfo item, long itemId, StackTraceElement[] stackTrace) {
        // Lock on mBgLock *after* the db operation
        synchronized (sBgLock) {
            checkItemInfoLocked(itemId, item, stackTrace);

            // Items are added/removed from the corresponding FolderInfo
            // elsewhere, such
            // as in Workspace.onDrop. Here, we just add/remove them from the
            // list of items
            // that are on the desktop, as appropriate
            ItemInfo modelItem = sBgItemsIdMap.get(itemId);
            if (modelItem.container == FavoritesContainerType.CONTAINER_DESKTOP ||
                    modelItem.container == FavoritesContainerType.CONTAINER_HOTSEAT) {
                switch (modelItem.itemType) {
                    case FavoritesType.ITEM_TYPE_APPLICATION:
                    case FavoritesType.ITEM_TYPE_SHORTCUT:
                        if (!sBgWorkspaceItems.contains(modelItem)) {
                            sBgWorkspaceItems.add(modelItem);
                        }
                        break;
                    default:
                        break;
                }
            } else {
                sBgWorkspaceItems.remove(modelItem);
            }
        }
    }

    public void flushWorkerThread() {
        mFlushingWorkerThread = true;
        Runnable waiter = new Runnable() {
            public void run() {
                synchronized (this) {
                    notifyAll();
                    mFlushingWorkerThread = false;
                }
            }
        };

        synchronized (waiter) {
            runOnWorkerThread(waiter);
            if (mLoaderTask != null) {
                synchronized (mLoaderTask) {
                    mLoaderTask.notify();
                }
            }
            boolean success = false;
            while (!success) {
                try {
                    waiter.wait();
                    success = true;
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Move an item in the DB to a new <container, screen, cellX, cellY>
     */
    static void moveItemInDatabase(Context context, final ItemInfo item, final long container,
            final long screenId, final int cellX, final int cellY) {
        item.container = container;
        item.cellX = cellX;
        item.cellY = cellY;

        // We store hotseat items in canonical form which is this orientation
        // invariant position
        // in the hotseat
        if (context instanceof Launcher && screenId < 0 &&
                container == FavoritesContainerType.CONTAINER_HOTSEAT) {
            // TODO getHotseat()
            // item.screenId = ((Launcher)
            // context).getHotseat().getOrderInHotseat(cellX, cellY);
        } else {
            item.screenId = screenId;
        }

        final ContentValues values = new ContentValues();
        values.put(FavoritesColumns.CONTAINER, item.container);
        values.put(FavoritesColumns.CELLX, item.cellX);
        values.put(FavoritesColumns.CELLY, item.cellY);
        values.put(FavoritesColumns.SCREEN, item.screenId);

        updateItemInDatabaseHelper(context, values, item, "moveItemInDatabase");
    }

    /**
     * Move items in the DB to a new <container, screen, cellX, cellY>. We
     * assume that the cellX, cellY have already been updated on the ItemInfos.
     */
    static void moveItemsInDatabase(Context context, final ArrayList<ItemInfo> items,
            final long container, final int screen) {

        ArrayList<ContentValues> contentValues = new ArrayList<ContentValues>();
        int count = items.size();

        for (int i = 0; i < count; i++) {
            ItemInfo item = items.get(i);
            item.container = container;

            // We store hotseat items in canonical form which is this
            // orientation invariant position
            // in the hotseat
            if (context instanceof Launcher && screen < 0 &&
                    container == FavoritesContainerType.CONTAINER_HOTSEAT) {
                // TODO getHotseat()
                // item.screenId = ((Launcher)
                // context).getHotseat().getOrderInHotseat(item.cellX,
                // item.cellY);
            } else {
                item.screenId = screen;
            }

            final ContentValues values = new ContentValues();
            values.put(FavoritesColumns.CONTAINER, item.container);
            values.put(FavoritesColumns.CELLX, item.cellX);
            values.put(FavoritesColumns.CELLY, item.cellY);
            values.put(FavoritesColumns.SCREEN, item.screenId);

            contentValues.add(values);
        }
        updateItemsInDatabaseHelper(context, contentValues, items, "moveItemInDatabase");
    }

    /**
     * Move and/or resize item in the DB to a new <container, screen, cellX,
     * cellY, spanX, spanY>
     */
    static void modifyItemInDatabase(Context context, final ItemInfo item, final long container,
            final long screenId, final int cellX, final int cellY, final int spanX, final int spanY) {
        item.container = container;
        item.cellX = cellX;
        item.cellY = cellY;
        item.spanX = spanX;
        item.spanY = spanY;

        // We store hotseat items in canonical form which is this orientation
        // invariant position
        // in the hotseat
        if (context instanceof Launcher && screenId < 0 &&
                container == FavoritesContainerType.CONTAINER_HOTSEAT) {
            // TODO getHotseat()
            // item.screenId = ((Launcher)
            // context).getHotseat().getOrderInHotseat(cellX, cellY);
        } else {
            item.screenId = screenId;
        }

        final ContentValues values = new ContentValues();
        values.put(FavoritesColumns.CONTAINER, item.container);
        values.put(FavoritesColumns.CELLX, item.cellX);
        values.put(FavoritesColumns.CELLY, item.cellY);
        values.put(FavoritesColumns.CELLW, item.spanX);
        values.put(FavoritesColumns.CELLH, item.spanY);
        values.put(FavoritesColumns.SCREEN, item.screenId);

        updateItemInDatabaseHelper(context, values, item, "modifyItemInDatabase");
    }

    /**
     * Update an item to the database in a specified container.
     */
    static void updateItemInDatabase(Context context, final ItemInfo item) {
        final ContentValues values = new ContentValues();
        item.onAddToDatabase(values);
        item.updateValuesWithCoordinates(values, item.cellX, item.cellY);
        updateItemInDatabaseHelper(context, values, item, "updateItemInDatabase");
    }

    /**
     * Returns true if the shortcuts already exists in the database. we identify
     * a shortcut by its title and intent.
     */
    static boolean shortcutExists(Context context, String title, Intent intent) {
        final ContentResolver cr = context.getContentResolver();
        Cursor c = cr.query(FavoritesURI.CONTENT_URI,
                new String[] {
                        "title", "intent"
                }, "title=? and intent=?",
                new String[] {
                        title, intent.toUri(0)
                }, null);
        boolean result = false;
        try {
            result = c.moveToFirst();
        } finally {
            c.close();
        }
        return result;
    }

    /**
     * Returns an ItemInfo array containing all the items in the LauncherModel.
     * The ItemInfo.id is not set through this function.
     */
    static ArrayList<ItemInfo> getItemsInLocalCoordinates(Context context) {
        ArrayList<ItemInfo> items = new ArrayList<ItemInfo>();
        final ContentResolver cr = context.getContentResolver();
        Cursor c = cr.query(FavoritesURI.CONTENT_URI, new String[] {
                FavoritesColumns.ITEMTYPE,
                FavoritesColumns.CONTAINER,
                FavoritesColumns.SCREEN,
                FavoritesColumns.CELLX,
                FavoritesColumns.CELLY,
                FavoritesColumns.CELLW,
                FavoritesColumns.CELLH
        }, null, null, null);

        final int itemTypeIndex = c.getColumnIndexOrThrow(FavoritesColumns.ITEMTYPE);
        final int containerIndex = c.getColumnIndexOrThrow(FavoritesColumns.CONTAINER);
        final int screenIndex = c.getColumnIndexOrThrow(FavoritesColumns.SCREEN);
        final int cellXIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLX);
        final int cellYIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLY);
        final int spanXIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLW);
        final int spanYIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLH);

        try {
            while (c.moveToNext()) {
                ItemInfo item = new ItemInfo();
                item.cellX = c.getInt(cellXIndex);
                item.cellY = c.getInt(cellYIndex);
                item.spanX = Math.max(1, c.getInt(spanXIndex));
                item.spanY = Math.max(1, c.getInt(spanYIndex));
                item.container = c.getInt(containerIndex);
                item.itemType = c.getInt(itemTypeIndex);
                item.screenId = c.getInt(screenIndex);

                items.add(item);
            }
        } catch (Exception e) {
            items.clear();
        } finally {
            c.close();
        }

        return items;
    }

    /**
     * Add an item to the database in a specified container. Sets the container,
     * screen, cellX and cellY fields of the item. Also assigns an ID to the
     * item.
     */
    static void addItemToDatabase(Context context, final ItemInfo item, final long container,
            final long screenId, final int cellX, final int cellY, final boolean notify) {
        item.container = container;
        item.cellX = cellX;
        item.cellY = cellY;
        // We store hotseat items in canonical form which is this orientation
        // invariant position
        // in the hotseat
        if (context instanceof Launcher && screenId < 0 &&
                container == FavoritesContainerType.CONTAINER_HOTSEAT) {
            // TODO getHotseat()
            // item.screenId = ((Launcher)
            // context).getHotseat().getOrderInHotseat(cellX, cellY);
        } else {
            item.screenId = screenId;
        }

        final ContentValues values = new ContentValues();
        final ContentResolver cr = context.getContentResolver();
        item.onAddToDatabase(values);

        item.id = LauncherAppState.getLauncherProvider().generateNewItemId();
        values.put(FavoritesColumns._ID, item.id);
        item.updateValuesWithCoordinates(values, item.cellX, item.cellY);

        Runnable r = new Runnable() {
            public void run() {
                cr.insert(notify ? FavoritesURI.CONTENT_URI : FavoritesURI.CONTENT_URI_NO_NOTIFICATION, values);

                // Lock on mBgLock *after* the db operation
                synchronized (sBgLock) {
                    checkItemInfoLocked(item.id, item, null);
                    sBgItemsIdMap.put(item.id, item);
                    switch (item.itemType) {
                        case FavoritesType.ITEM_TYPE_APPLICATION:
                        case FavoritesType.ITEM_TYPE_SHORTCUT:
                            if (item.container == FavoritesContainerType.CONTAINER_DESKTOP ||
                                    item.container == FavoritesContainerType.CONTAINER_HOTSEAT) {
                                sBgWorkspaceItems.add(item);
                            } else {
                                String msg = "adding item: " + item + " to a folder that " +
                                        " doesn't exist";
                                Log.e(TAG, msg);
                            }
                            break;
                    }
                }
            }
        };
        runOnWorkerThread(r);
    }

    /**
     * Creates a new unique child id, for a given cell span across all layouts.
     */
    static int getCellLayoutChildId(
            long container, long screen, int localCellX, int localCellY, int spanX, int spanY) {
        return (((int) container & 0xFF) << 24)
                | ((int) screen & 0xFF) << 16 | (localCellX & 0xFF) << 8 | (localCellY & 0xFF);
    }

    /**
     * Removes the specified item from the database
     * 
     * @param context
     * @param item
     */
    static void deleteItemFromDatabase(Context context, final ItemInfo item) {
        final ContentResolver cr = context.getContentResolver();
        final Uri uriToDelete = FavoritesURI.getContentUri(item.id, false);

        Runnable r = new Runnable() {
            public void run() {
                cr.delete(uriToDelete, null, null);

                // Lock on mBgLock *after* the db operation
                synchronized (sBgLock) {
                    switch (item.itemType) {
                        case FavoritesType.ITEM_TYPE_APPLICATION:
                        case FavoritesType.ITEM_TYPE_SHORTCUT:
                            sBgWorkspaceItems.remove(item);
                            break;
                    }
                    sBgItemsIdMap.remove(item.id);
                    sBgDbIconCache.remove(item);
                }
            }
        };
        runOnWorkerThread(r);
    }

    /**
     * Update the order of the workspace screens in the database. The array list
     * contains a list of screen ids in the order that they should appear.
     */
    void updateWorkspaceScreenOrder(Context context, final ArrayList<Long> screens) {
        final ArrayList<Long> screensCopy = new ArrayList<Long>(screens);
        final ContentResolver cr = context.getContentResolver();
        final Uri uri = WorkspaceScreensURI.CONTENT_URI;

        // Remove any negative screen ids -- these aren't persisted
        Iterator<Long> iter = screensCopy.iterator();
        while (iter.hasNext()) {
            long id = iter.next();
            if (id < 0) {
                iter.remove();
            }
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                // Clear the table
                cr.delete(uri, null, null);
                int count = screensCopy.size();
                ContentValues[] values = new ContentValues[count];
                for (int i = 0; i < count; i++) {
                    ContentValues v = new ContentValues();
                    long screenId = screensCopy.get(i);
                    v.put(WorkspacesColumns._ID, screenId);
                    v.put(WorkspacesColumns.SCREEN_RANK, i);
                    values[i] = v;
                }
                cr.bulkInsert(uri, values);

                synchronized (sBgLock) {
                    sBgWorkspaceScreens.clear();
                    sBgWorkspaceScreens.addAll(screensCopy);
                }
            }
        };
        runOnWorkerThread(r);
    }

    /**
     * Set this as the current Launcher activity object for the loader.
     */
    public void initialize(Callbacks callbacks) {
        synchronized (mLock) {
            mCallbacks = new WeakReference<Callbacks>(callbacks);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive intent=" + intent);
        final String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_CHANGED.equals(action)
                || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                || Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            final String packageName = intent.getData().getSchemeSpecificPart();
            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            int op = PackageUpdatedTask.OP_NONE;
            if (packageName == null || packageName.length() == 0) {
                return;
            }
            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                op = PackageUpdatedTask.OP_UPDATE;
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                if (!replacing) {
                    op = PackageUpdatedTask.OP_REMOVE;
                }
                // else, we are replacing the package, so a PACKAGE_ADDED will
                // be sent
                // later, we will update the package at this time
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                if (!replacing) {
                    op = PackageUpdatedTask.OP_ADD;
                } else {
                    op = PackageUpdatedTask.OP_UPDATE;
                }
            }
            if (op != PackageUpdatedTask.OP_NONE) {
                enqueuePackageUpdated(new PackageUpdatedTask(op, new String[] {
                        packageName
                }));
            }
        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
            String[] packages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            enqueuePackageUpdated(new PackageUpdatedTask(PackageUpdatedTask.OP_ADD, packages));
            startLoaderFromBackground();
        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
            String[] packages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            enqueuePackageUpdated(new PackageUpdatedTask(PackageUpdatedTask.OP_UNAVAILABLE, packages));
        } else if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
            forceReload();
        } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
            Configuration currentConfig = context.getResources().getConfiguration();
            if (mPreviousConfigMcc != currentConfig.mcc) {
                Log.d(TAG, "Reload apps on config change. curr_mcc:" + currentConfig.mcc + " prevmcc:" + mPreviousConfigMcc);
                forceReload();
            }
            mPreviousConfigMcc = currentConfig.mcc;
        }
    }

    private void forceReload() {
        resetLoadedState(true, true);
        startLoaderFromBackground();
    }

    public void resetLoadedState(boolean resetAllAppsLoaded, boolean resetWorkspaceLoaded) {
        synchronized (mLock) {
            /** Stop any existing loaders first, so they don't set mAllAppsLoaded or mWorkspaceLoaded to true later */
            stopLoaderLocked();
            if (resetAllAppsLoaded)
                mAllAppsLoaded = false;
            if (resetWorkspaceLoaded)
                mWorkspaceLoaded = false;
        }
    }

    /**
     * 当forceReload()时，如Launcher onPause, 不会执行startLoader(false, -1), 这个操作会放在onResume中 
     */
    public void startLoaderFromBackground() {
        boolean runLoader = false;
        if (mCallbacks != null) {
            Callbacks callbacks = mCallbacks.get();
            if (callbacks != null) {
                /** Only actually run the loader if they're not paused */
                if (callbacks.setLoadOnResume()) {
                    runLoader = true;
                }
            }
        }
        if (runLoader) {
            startLoader(false, -1);
        }
    }

    /**
     * @return has a oldTask running
     */
    private boolean stopLoaderLocked() {
        boolean isLaunching = false;
        LoaderTask oldTask = mLoaderTask;
        if (oldTask != null) {
            if (oldTask.isLaunching()) {
                isLaunching = true;
            }
            oldTask.stopLocked();
        }
        return isLaunching;
    }

    public void startLoader(boolean isLaunching, int synchronousBindPage) {
        synchronized (mLock) {
            Log.d(TAG, "startLoader isLaunching=" + isLaunching);
            mDeferredBindRunnables.clear();
            if (mCallbacks != null && mCallbacks.get() != null) {
                isLaunching = isLaunching || stopLoaderLocked();
                mLoaderTask = new LoaderTask(mApp.getContext(), isLaunching);
                if (synchronousBindPage > -1 && mAllAppsLoaded && mWorkspaceLoaded) {
                    mLoaderTask.runBindSynchronousPage(synchronousBindPage);
                } else {
                    sWorkerThread.setPriority(Thread.NORM_PRIORITY);
                    sWorker.post(mLoaderTask);
                }
            }
        }
    }

    /**
     * 绑定剩余的页面
     */
    void bindRemainingSynchronousPages() {
        if (!mDeferredBindRunnables.isEmpty()) {
            for (final Runnable r : mDeferredBindRunnables) {
                mHandler.post(r, MAIN_THREAD_BINDING_RUNNABLE);
            }
            mDeferredBindRunnables.clear();
        }
    }

    public void stopLoader() {
        synchronized (mLock) {
            if (mLoaderTask != null) {
                mLoaderTask.stopLocked();
            }
        }
    }

    /** Loads the workspace screens db into a map of Rank -> ScreenId */
    private static TreeMap<Integer, Long> loadWorkspaceScreensDb(Context context) {
        final ContentResolver contentResolver = context.getContentResolver();
        final Uri screensUri = WorkspaceScreensURI.CONTENT_URI;
        final Cursor sc = contentResolver.query(screensUri, null, null, null, null);
        TreeMap<Integer, Long> orderedScreens = new TreeMap<Integer, Long>();
        try {
            final int idIndex = sc.getColumnIndexOrThrow(WorkspacesColumns._ID);
            final int rankIndex = sc.getColumnIndexOrThrow(WorkspacesColumns.SCREEN_RANK);
            while (sc.moveToNext()) {
                try {
                    long screenId = sc.getLong(idIndex);
                    int rank = sc.getInt(rankIndex);
                    orderedScreens.put(rank, screenId);
                } catch (Exception e) {
                    Launcher.addDumpLog(TAG, "Desktop items loading interrupted - invalid screens: " + e, true);
                }
            }
        } finally {
            sc.close();
        }
        return orderedScreens;
    }

    public boolean isAllAppsLoaded() {
        return mAllAppsLoaded;
    }

    boolean isLoadingWorkspace() {
        synchronized (mLock) {
            if (mLoaderTask != null) {
                return mLoaderTask.isLoadingWorkspace();
            }
        }
        return false;
    }

    private class LoaderTask implements Runnable {
        private Context mContext;
        private boolean mIsLaunching;
        private boolean mIsLoadingAndBindingWorkspace;
        private boolean mStopped;
        private boolean mLoadAndBindStepFinished;

        private HashMap<Object, CharSequence> mLabelCache;
        private boolean dISABLE_ALL_APPS = false;

        LoaderTask(Context context, boolean isLaunching) {
            mContext = context;
            mIsLaunching = isLaunching;
            mLabelCache = new HashMap<Object, CharSequence>();
        }

        boolean isLaunching() {
            return mIsLaunching;
        }

        boolean isLoadingWorkspace() {
            return mIsLoadingAndBindingWorkspace;
        }

        /** return whether this is an upgrade path */
        private boolean loadAndBindWorkspace() {
            mIsLoadingAndBindingWorkspace = true;

            Log.d(TAG, "loadAndBindWorkspace mWorkspaceLoaded=" + mWorkspaceLoaded);

            boolean isUpgradePath = false;
            if (!mWorkspaceLoaded) {
                isUpgradePath = loadWorkspace();
                Log.d(TAG, "loadWorkspace result " + isUpgradePath + " mStopped=" + mStopped);
                synchronized (LoaderTask.this) {
                    if (mStopped) {
                        return isUpgradePath;
                    }
                    mWorkspaceLoaded = true;
                }
            }

            bindWorkspace(-1, isUpgradePath);
            return isUpgradePath;
        }

        private void waitForIdle() {
            synchronized (LoaderTask.this) {
                final long workspaceWaitTime = SystemClock.uptimeMillis();
                mHandler.postIdle(new Runnable() {
                    public void run() {
                        synchronized (LoaderTask.this) {
                            mLoadAndBindStepFinished = true;
                            Log.d(TAG, "done with previous binding step");
                            LoaderTask.this.notify();
                        }
                    }
                });

                while (!mStopped && !mLoadAndBindStepFinished && !mFlushingWorkerThread) {
                    try {
                        Log.d(TAG, "waitForIdle...");
                        this.wait(1000);
                    } catch (InterruptedException ex) {
                    }
                }
                Log.d(TAG, "waited " + (SystemClock.uptimeMillis() - workspaceWaitTime) + "ms for previous step to finish binding");
            }
        }

        void runBindSynchronousPage(int synchronousBindPage) {
            if (synchronousBindPage < 0) {
                throw new RuntimeException("Should not call runBindSynchronousPage() without " + "valid page index");
            }
            if (!mAllAppsLoaded || !mWorkspaceLoaded) {
                throw new RuntimeException("Expecting AllApps and Workspace to be loaded");
            }
            synchronized (mLock) {
                if (mIsLoaderTaskRunning) {
                    throw new RuntimeException("Error! Background loading is already running");
                }
            }
            /**
             * We need to ensure that all are executed before any synchronous binding work is done
             */
            mHandler.flush();
            bindWorkspace(synchronousBindPage, false);
            onlyBindAllApps();
        }

        public void run() {
            boolean isUpgrade = false;

            synchronized (mLock) {
                mIsLoaderTaskRunning = true;
            }
            /**
             * if the Launcher is up and running with the All APPS interface in the foreground, load All Apps first;
             * Otherwise, load the  workspace first (default)
             */
            keep_running: {
                synchronized (mLock) {
                    Log.d(TAG, "Setting thread priority to " + (mIsLaunching ? "# default #" : "# background #"));
                    android.os.Process.setThreadPriority(
                            mIsLaunching ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
                }

                Log.d(TAG, "step 1: loading workspace");
                isUpgrade = loadAndBindWorkspace();
                if (mStopped) {
                    break keep_running;
                }

                synchronized (mLock) {
                    if (mIsLaunching) {
                        Log.d(TAG, "Setting thread priority to # background #");
                        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    }
                }

                Log.d(TAG, "step 2: waitForIdle");
                waitForIdle();
                Log.d(TAG, "step 3: loading all apps");
                loadAndBindAllApps();

                synchronized (mLock) {
                    android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                }
            }

            synchronized (sBgLock) {
                Log.d(TAG, "check loaded app icons is need to update db");
                for (Object key : sBgDbIconCache.keySet()) {
                    updateSavedIcon(mContext, (ShortcutInfo) key, sBgDbIconCache.get(key));
                }
                sBgDbIconCache.clear();
            }

            if (dISABLE_ALL_APPS) {
                /** Ensure that all the applications that are in the system are represented on the home screen */
                if (!UPGRADE_USE_MORE_APPS_FOLDER || !isUpgrade) {
                    verifyApplications();
                }
            }

            /** 释放Context持有 */
            mContext = null;

            synchronized (mLock) {
                if (mLoaderTask == this) {
                    mLoaderTask = null;
                }
                mIsLoaderTaskRunning = false;
            }
        }

        public void stopLocked() {
            synchronized (LoaderTask.this) {
                mStopped = true;
                this.notify();
            }
        }

        Callbacks tryGetCallbacks(Callbacks oldCallbacks) {
            synchronized (mLock) {
                if (mStopped) {
                    return null;
                }
                if (mCallbacks == null) {
                    return null;
                }
                final Callbacks callbacks = mCallbacks.get();
                if (callbacks != oldCallbacks) {
                    return null;
                }
                if (callbacks == null) {
                    return null;
                }
                return callbacks;
            }
        }

        /**
         * 验证APPS是否一致
         */
        private void verifyApplications() {
            final Context context = mApp.getContext();
            ArrayList<ItemInfo> tmpInfos;
            ArrayList<ItemInfo> added = new ArrayList<ItemInfo>();
            synchronized (sBgLock) {
                for (AppInfo app : mBgAllAppsList.data) {
                    tmpInfos = getItemInfoForComponentName(app.componentName);
                    if (tmpInfos.isEmpty()) {
                        Log.e(TAG, "Missing Application on load: " + app);
                        /** We are missing an application icon, so add this to the workspace */
                        added.add(app);
                    }
                }
            }
            if (!added.isEmpty()) {
                Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;
                addAndBindAddedApps(context, added, cb, null);
            }
        }

        private boolean checkItemDimensions(ItemInfo info) {
            LauncherAppState app = LauncherAppState.getInstance();
            DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
            return (info.cellX + info.spanX) > (int) grid.numColumns ||
                    (info.cellY + info.spanY) > (int) grid.numRows;
        }

        /**
         * 检查item的放置
         * @param occupied 已占用
         * @param item
         * @param deleteOnItemOverlap 如重叠，刚删除
         * @return
         */
        private boolean checkItemPlacement(HashMap<Long, ItemInfo[][]> occupied, ItemInfo item,
                AtomicBoolean deleteOnItemOverlap) {
            LauncherAppState app = LauncherAppState.getInstance();
            DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
            int countX = (int) grid.numColumns;
            int countY = (int) grid.numRows;

            long containerIndex = item.screenId;
            if (item.container == FavoritesContainerType.CONTAINER_HOTSEAT) {
                /** Return early if we detect that an item is under the HOTSEAT button */
                if (mCallbacks == null || mCallbacks.get().isAllAppsButtonRank((int) item.screenId)) {
                    deleteOnItemOverlap.set(true);
                    return false;
                }
                if (occupied.containsKey(FavoritesContainerType.CONTAINER_HOTSEAT)) {
                    if (occupied.get(FavoritesContainerType.CONTAINER_HOTSEAT)[(int) item.screenId][0] != null) {
                        Log.e(TAG, "Error loading shortcut into hotseat " + item
                                + " into position (" + item.screenId + ":" + item.cellX + ","
                                + item.cellY + ") occupied by "
                                + occupied.get(FavoritesContainerType.CONTAINER_HOTSEAT)
                                [(int) item.screenId][0]);
                        return false;
                    }
                } else {
                    ItemInfo[][] items = new ItemInfo[countX + 1][countY + 1];
                    items[(int) item.screenId][0] = item;
                    occupied.put((long) FavoritesContainerType.CONTAINER_HOTSEAT, items);
                    return true;
                }
            } else if (item.container != FavoritesContainerType.CONTAINER_DESKTOP) {
                /** Skip further checking if it is not the HOTSEAT or workspace container */
                return true;
            }

            if (!occupied.containsKey(item.screenId)) {
                ItemInfo[][] items = new ItemInfo[countX + 1][countY + 1];
                occupied.put(item.screenId, items);
            }

            ItemInfo[][] screens = occupied.get(item.screenId);
            /** 检查是否重叠 */
            for (int x = item.cellX; x < (item.cellX + item.spanX); x++) {
                for (int y = item.cellY; y < (item.cellY + item.spanY); y++) {
                    if (screens[x][y] != null) {
                        Log.e(TAG, "Error loading shortcut " + item
                                + " into cell (" + containerIndex + "-" + item.screenId + ":"
                                + x + "," + y
                                + ") occupied by "
                                + screens[x][y]);
                        return false;
                    }
                }
            }
            for (int x = item.cellX; x < (item.cellX + item.spanX); x++) {
                for (int y = item.cellY; y < (item.cellY + item.spanY); y++) {
                    screens[x][y] = item;
                }
            }

            return true;
        }

        /** Clears all the sBg data structures */
        private void clearSBgDataStructures() {
            synchronized (sBgLock) {
                sBgWorkspaceItems.clear();
                sBgItemsIdMap.clear();
                sBgDbIconCache.clear();
                sBgWorkspaceScreens.clear();
            }
        }

        /** Returns whether this is an upgradge path */
        private boolean loadWorkspace() {
            final long t = SystemClock.uptimeMillis();

            final Context context = mContext;
            final ContentResolver contentResolver = context.getContentResolver();
            final PackageManager manager = context.getPackageManager();
            final boolean isSafeMode = manager.isSafeMode();

            LauncherAppState app = LauncherAppState.getInstance();
            DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
            int countX = (int) grid.numColumns;
            int countY = (int) grid.numRows;

            /** Make sure the default workspace is loaded */
            LauncherAppState.getLauncherProvider().loadDefaultFavoritesIfNecessary(0);
            /** Check if we need to do any upgrade-path logic */
            boolean loadedOldDb = LauncherAppState.getLauncherProvider().justLoadedOldDb();

            synchronized (sBgLock) {
                clearSBgDataStructures();

                final ArrayList<Long> itemsToRemove = new ArrayList<Long>();
                final Uri contentUri = FavoritesURI.CONTENT_URI;
                final Cursor c = contentResolver.query(contentUri, null, null, null, null);

                final HashMap<Long, ItemInfo[][]> occupied = new HashMap<Long, ItemInfo[][]>();
                try {
                    final int idIndex = c.getColumnIndexOrThrow(FavoritesColumns._ID);
                    final int intentIndex = c.getColumnIndexOrThrow(FavoritesColumns.INTENT);
                    final int titleIndex = c.getColumnIndexOrThrow(FavoritesColumns.TITLE);
                    final int iconTypeIndex = c.getColumnIndexOrThrow(FavoritesColumns.ICONTYPE);
                    final int iconIndex = c.getColumnIndexOrThrow(FavoritesColumns.ICON);
                    final int iconPackageIndex = c.getColumnIndexOrThrow(FavoritesColumns.ICONPACKAGE);
                    final int iconResourceIndex = c.getColumnIndexOrThrow(FavoritesColumns.ICONRESOURCE);
                    final int containerIndex = c.getColumnIndexOrThrow(FavoritesColumns.CONTAINER);
                    final int itemTypeIndex = c.getColumnIndexOrThrow(FavoritesColumns.ITEMTYPE);
                    final int screenIndex = c.getColumnIndexOrThrow(FavoritesColumns.SCREEN);
                    final int cellXIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLX);
                    final int cellYIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLY);
                    final int spanXIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLW);
                    final int spanYIndex = c.getColumnIndexOrThrow(FavoritesColumns.CELLH);

                    ShortcutInfo info;
                    String intentDescription;
                    int container;
                    long id;
                    Intent intent;

                    while (!mStopped && c.moveToNext()) {
                        AtomicBoolean deleteOnItemOverlap = new AtomicBoolean(false);
                        try {
                            int itemType = c.getInt(itemTypeIndex);

                            switch (itemType) {
                                case FavoritesType.ITEM_TYPE_APPLICATION:
                                case FavoritesType.ITEM_TYPE_SHORTCUT:
                                    id = c.getLong(idIndex);
                                    intentDescription = c.getString(intentIndex);
                                    try {
                                        intent = Intent.parseUri(intentDescription, 0);
                                        ComponentName cn = intent.getComponent();
                                        if (cn != null && !isValidPackageComponent(manager, cn)) {
                                            if (!mAppsCanBeOnRemoveableStorage) {
                                                /** Log the invalid package, and remove it from the db */
                                                Launcher.addDumpLog(TAG, "Invalid package removed: " + cn, true);
                                                itemsToRemove.add(id);
                                            } else {
                                                /** 留下可以对外部存储的应用程序 */
                                                Launcher.addDumpLog(TAG, "Invalid package found: " + cn, true);
                                            }
                                            continue;
                                        }
                                    } catch (URISyntaxException e) {
                                        Launcher.addDumpLog(TAG, "Invalid uri: " + intentDescription, true);
                                        continue;
                                    }

                                    if (itemType == FavoritesType.ITEM_TYPE_APPLICATION) {
                                        info = getShortcutInfo(manager, intent, context, c, iconIndex,
                                                titleIndex, mLabelCache);
                                    } else {
                                        info = getShortcutInfo(c, context, iconTypeIndex, iconPackageIndex, iconResourceIndex, iconIndex,
                                                titleIndex);
                                        /**
                                         * APP shortcuts that used to be automatically added to Launcher, didn't always have the 
                                         * correct intent flags set, so do that here
                                         */
                                        if (intent.getAction() != null &&
                                                intent.getCategories() != null &&
                                                intent.getAction().equals(Intent.ACTION_MAIN) &&
                                                intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                        }
                                    }

                                    if (info != null) {
                                        info.id = id;
                                        info.intent = intent;
                                        container = c.getInt(containerIndex);
                                        info.container = container;
                                        info.screenId = c.getInt(screenIndex);
                                        info.cellX = c.getInt(cellXIndex);
                                        info.cellY = c.getInt(cellYIndex);
                                        info.spanX = c.getInt(spanXIndex);
                                        info.spanY = c.getInt(spanYIndex);
                                        /** Skip loading items that are out of bounds */
                                        if (container == FavoritesContainerType.CONTAINER_DESKTOP) {
                                            if (checkItemDimensions(info)) {
                                                continue;
                                            }
                                        }
                                        /** check & update map of what's occupied */
                                        deleteOnItemOverlap.set(false);
                                        if (!checkItemPlacement(occupied, info, deleteOnItemOverlap)) {
                                            if (deleteOnItemOverlap.get()) {
                                                itemsToRemove.add(id);
                                            }
                                            break;
                                        }

                                        switch (container) {
                                            case FavoritesContainerType.CONTAINER_DESKTOP:
                                            case FavoritesContainerType.CONTAINER_HOTSEAT:
                                                sBgWorkspaceItems.add(info);
                                                break;
                                            default:
                                                break;
                                        }
                                        sBgItemsIdMap.put(info.id, info);
                                        /** now that we've loaded everything re-save it with the icon in case it disappears somehow */
                                        queueIconToBeChecked(sBgDbIconCache, info, c, iconIndex);
                                    } else {
                                        throw new RuntimeException("Unexpected null ShortcutInfo");
                                    }
                                    break;
                            }
                        } catch (Exception e) {
                            Launcher.addDumpLog(TAG, "Desktop items loading interrupted: " + e, true);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "loadWorkspace e = " + e);
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }

                if (mStopped) {
                    clearSBgDataStructures();
                    return false;
                }

                if (itemsToRemove.size() > 0) {
                    ContentProviderClient client = contentResolver.acquireContentProviderClient(FavoritesURI.CONTENT_URI);
                    /** Remove dead items */
                    for (long id : itemsToRemove) {
                        Log.d(TAG, "Remove dead items, id = " + id);
                        try {
                            /** Don't notify content observers */
                            client.delete(FavoritesURI.getContentUri(id, false), null, null);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Could not remove id = " + id);
                        }
                    }
                }

                if (loadedOldDb) {
                    long maxScreenId = 0;
                    /** If we're importing we use the old screen order */
                    for (ItemInfo item : sBgItemsIdMap.values()) {
                        long screenId = item.screenId;
                        if (item.container == FavoritesContainerType.CONTAINER_DESKTOP && !sBgWorkspaceScreens.contains(screenId)) {
                            sBgWorkspaceScreens.add(screenId);
                            if (screenId > maxScreenId) {
                                maxScreenId = screenId;
                            }
                        }
                    }
                    Collections.sort(sBgWorkspaceScreens);

                    LauncherAppState.getLauncherProvider().updateMaxScreenId(maxScreenId);
                    updateWorkspaceScreenOrder(context, sBgWorkspaceScreens);

                    /** Update the max item id after we load an old db */
                    long maxItemId = 0;
                    /**  If we're importing we use the old screen order */
                    for (ItemInfo item : sBgItemsIdMap.values()) {
                        maxItemId = Math.max(maxItemId, item.id);
                    }
                    LauncherAppState.getLauncherProvider().updateMaxItemId(maxItemId);
                } else {
                    TreeMap<Integer, Long> orderedScreens = loadWorkspaceScreensDb(mContext);
                    for (Integer i : orderedScreens.keySet()) {
                        sBgWorkspaceScreens.add(orderedScreens.get(i));
                    }
                    /** Remove any empty screens */
                    ArrayList<Long> unusedScreens = new ArrayList<Long>(sBgWorkspaceScreens);
                    for (ItemInfo item : sBgItemsIdMap.values()) {
                        long screenId = item.screenId;
                        if (item.container == FavoritesContainerType.CONTAINER_DESKTOP && unusedScreens.contains(screenId)) {
                            unusedScreens.remove(screenId);
                        }
                    }

                    /** If there are any empty screens remove them, and update */
                    if (unusedScreens.size() != 0) {
                        sBgWorkspaceScreens.removeAll(unusedScreens);
                        updateWorkspaceScreenOrder(context, sBgWorkspaceScreens);
                    }
                }

                Log.d(TAG, "loaded workspace in " + (SystemClock.uptimeMillis() - t) + "ms");
                int nScreens = occupied.size();
                Log.d(TAG, "workspace screen size = " + nScreens + " layout : \n");
                for (int y = 0; y < countY; y++) {
                    String line = "";
                    Iterator<Long> iter = occupied.keySet().iterator();
                    while (iter.hasNext()) {
                        long screenId = iter.next();
                        if (screenId > 0) {
                            line += " | ";
                        }
                        for (int x = 0; x < countX; x++) {
                            line += ((occupied.get(screenId)[x][y] != null) ? "#" : ".");
                        }
                    }
                    Log.d(TAG, "[ " + line + " ]");
                }
            }
            return loadedOldDb;
        }

        /**
         * Filters the set of items who are directly or indirectly (via another container) on the specified screen.
         */
        private void filterCurrentWorkspaceItems(int currentScreen,
                ArrayList<ItemInfo> allWorkspaceItems,
                ArrayList<ItemInfo> currentScreenItems,
                ArrayList<ItemInfo> otherScreenItems) {
            /** 清除空item */
            Iterator<ItemInfo> iter = allWorkspaceItems.iterator();
            while (iter.hasNext()) {
                ItemInfo i = iter.next();
                if (i == null) {
                    iter.remove();
                }
            }

            if (currentScreen < 0) {
                currentScreenItems.addAll(allWorkspaceItems);
            }

            Set<Long> itemsOnScreen = new HashSet<Long>();
            Collections.sort(allWorkspaceItems, new Comparator<ItemInfo>() {
                @Override
                public int compare(ItemInfo lhs, ItemInfo rhs) {
                    return (int) (lhs.container - rhs.container);
                }
            });
            for (ItemInfo info : allWorkspaceItems) {
                if (info.container == FavoritesContainerType.CONTAINER_DESKTOP) {
                    if (info.screenId == currentScreen) {
                        currentScreenItems.add(info);
                        itemsOnScreen.add(info.id);
                    } else {
                        otherScreenItems.add(info);
                    }
                } else if (info.container == FavoritesContainerType.CONTAINER_HOTSEAT) {
                    currentScreenItems.add(info);
                    itemsOnScreen.add(info.id);
                } else {
                    if (itemsOnScreen.contains(info.container)) {
                        currentScreenItems.add(info);
                        itemsOnScreen.add(info.id);
                    } else {
                        otherScreenItems.add(info);
                    }
                }
            }
        }

        /**
         * Sorts the set of items by HOTSEAT, workspace (spatially from top to bottom, left to right)
         */
        private void sortWorkspaceItemsSpatially(ArrayList<ItemInfo> workspaceItems) {
            final LauncherAppState app = LauncherAppState.getInstance();
            final DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
            Collections.sort(workspaceItems, new Comparator<ItemInfo>() {
                @Override
                public int compare(ItemInfo lhs, ItemInfo rhs) {
                    int cellCountX = (int) grid.numColumns;
                    int cellCountY = (int) grid.numRows;
                    int screenOffset = cellCountX * cellCountY;
                    int containerOffset = screenOffset * (Launcher.SCREEN_COUNT + 1);
                    long lr = (lhs.container * containerOffset + lhs.screenId * screenOffset +
                            lhs.cellY * cellCountX + lhs.cellX);
                    long rr = (rhs.container * containerOffset + rhs.screenId * screenOffset +
                            rhs.cellY * cellCountX + rhs.cellX);
                    return (int) (lr - rr);
                }
            });
        }

        private void bindWorkspaceScreens(final Callbacks oldCallbacks,
                final ArrayList<Long> orderedScreens) {
            final Runnable r = new Runnable() {
                @Override
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindScreens(orderedScreens);
                    }
                }
            };
            runOnMainThread(r, MAIN_THREAD_BINDING_RUNNABLE);
        }

        private void bindWorkspaceItems(final Callbacks oldCallbacks, final ArrayList<ItemInfo> workspaceItems,
                ArrayList<Runnable> deferredBindRunnables) {

            final boolean postOnMainThread = (deferredBindRunnables != null);

            /** Bind the workspace items */
            int N = workspaceItems.size();
            for (int i = 0; i < N; i += ITEMS_CHUNK) {
                final int start = i;
                final int chunkSize = (i + ITEMS_CHUNK <= N) ? ITEMS_CHUNK : (N - i);
                final Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                        if (callbacks != null) {
                            callbacks.bindItems(workspaceItems, start, start + chunkSize, false);
                        }
                    }
                };
                if (postOnMainThread) {
                    deferredBindRunnables.add(r);
                } else {
                    runOnMainThread(r, MAIN_THREAD_BINDING_RUNNABLE);
                }
            }
        }

        /**
         * Binds all loaded data to actual views on the main thread.
         */
        private void bindWorkspace(int synchronizeBindPage, final boolean isUpgradePath) {
            final long t = SystemClock.uptimeMillis();
            Runnable r;

            final Callbacks oldCallbacks = mCallbacks.get();
            if (oldCallbacks == null) {
                Log.w(TAG, "LoaderTask running with no launcher");
                return;
            }

            final boolean isLoadingSynchronously = (synchronizeBindPage > -1);
            final int currentScreen = isLoadingSynchronously ? synchronizeBindPage : oldCallbacks.getCurrentWorkspaceScreen();

            /**
             * Load all the items that are on the current page first ,
             * and in theprocess, unbind all the existing workspace items before we call startBinding() below
             */
            unbindWorkspaceItemsOnMainThread();
            ArrayList<ItemInfo> workspaceItems = new ArrayList<ItemInfo>();
            HashMap<Long, ItemInfo> itemsIdMap = new HashMap<Long, ItemInfo>();
            ArrayList<Long> orderedScreenIds = new ArrayList<Long>();
            synchronized (sBgLock) {
                workspaceItems.addAll(sBgWorkspaceItems);
                itemsIdMap.putAll(sBgItemsIdMap);
                orderedScreenIds.addAll(sBgWorkspaceScreens);
            }

            ArrayList<ItemInfo> currentWorkspaceItems = new ArrayList<ItemInfo>();
            ArrayList<ItemInfo> otherWorkspaceItems = new ArrayList<ItemInfo>();

            /**
             * Separate the items that are on the current screen, and all the other remaining items 
             */
            filterCurrentWorkspaceItems(currentScreen, workspaceItems, currentWorkspaceItems, otherWorkspaceItems);
            sortWorkspaceItemsSpatially(currentWorkspaceItems);
            sortWorkspaceItemsSpatially(otherWorkspaceItems);

            /** Tell the workspace that we're about to start binding items */
            r = new Runnable() {
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.startBinding();
                    }
                }
            };
            runOnMainThread(r, MAIN_THREAD_BINDING_RUNNABLE);

            bindWorkspaceScreens(oldCallbacks, orderedScreenIds);

            /** Load items on the current page */
            bindWorkspaceItems(oldCallbacks, currentWorkspaceItems, null);
            if (isLoadingSynchronously) {
                r = new Runnable() {
                    public void run() {
                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                        if (callbacks != null) {
                            callbacks.onPageBoundSynchronously(currentScreen);
                        }
                    }
                };
                runOnMainThread(r, MAIN_THREAD_BINDING_RUNNABLE);
            }

            /**
             * Load all the remaining pages,
             * if we are loading synchronously, we want to defer this work until after the first render
             */
            mDeferredBindRunnables.clear();
            bindWorkspaceItems(oldCallbacks, otherWorkspaceItems, (isLoadingSynchronously ? mDeferredBindRunnables : null));

            /** Tell the workspace that we're done binding items */
            r = new Runnable() {
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.finishBindingItems(isUpgradePath);
                    }
                    mIsLoadingAndBindingWorkspace = false;
                    Log.d(TAG, "bound workspace in " + (SystemClock.uptimeMillis() - t) + "ms");
                }
            };
            if (isLoadingSynchronously) {
                mDeferredBindRunnables.add(r);
            } else {
                runOnMainThread(r, MAIN_THREAD_BINDING_RUNNABLE);
            }
        }

        private void loadAndBindAllApps() {
            Log.d(TAG, "loadAndBindAllApps mAllAppsLoaded=" + mAllAppsLoaded);
            if (!mAllAppsLoaded) {
                loadAllApps();
                synchronized (LoaderTask.this) {
                    if (mStopped) {
                        return;
                    }
                    mAllAppsLoaded = true;
                }
            } else {
                onlyBindAllApps();
            }
        }

        private void onlyBindAllApps() {
            final Callbacks oldCallbacks = mCallbacks.get();
            if (oldCallbacks == null) {
                Log.w(TAG, "LoaderTask running with no launcher (onlyBindAllApps)");
                return;
            }

            @SuppressWarnings("unchecked")
            final ArrayList<AppInfo> list = (ArrayList<AppInfo>) mBgAllAppsList.data.clone();
            Runnable r = new Runnable() {
                public void run() {
                    final long t = SystemClock.uptimeMillis();
                    final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindAllApplications(list);
                    }
                    Log.d(TAG, "bound all " + list.size() + " apps from cache in " + (SystemClock.uptimeMillis() - t) + "ms");
                }
            };
            boolean isRunningOnMainThread = !(sWorkerThread.getThreadId() == Process.myTid());
            if (isRunningOnMainThread) {
                r.run();
            } else {
                mHandler.post(r);
            }
        }

        private void loadAllApps() {
            final long loadTime = SystemClock.uptimeMillis();

            final Callbacks oldCallbacks = mCallbacks.get();
            if (oldCallbacks == null) {
                Log.w(TAG, "LoaderTask running with no launcher (loadAllApps)");
                return;
            }

            final PackageManager packageManager = mContext.getPackageManager();
            final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            mBgAllAppsList.clear();

            final long qiaTime = SystemClock.uptimeMillis();
            List<ResolveInfo> apps = packageManager.queryIntentActivities(mainIntent, 0);
            Log.d(TAG, "queryIntentActivities took " + (SystemClock.uptimeMillis() - qiaTime) + "ms");
            Log.d(TAG, "queryIntentActivities got " + apps.size() + " apps");
            if (apps == null || apps.isEmpty()) {
                return;
            }
            /** Sort the applications by name */
            final long sortTime = SystemClock.uptimeMillis();
            Collections.sort(apps, new LauncherModel.ShortcutNameComparator(packageManager, mLabelCache));
            Log.d(TAG, "sort took " + (SystemClock.uptimeMillis() - sortTime) + "ms");

            /** Create the ApplicationInfos */
            for (int i = 0; i < apps.size(); i++) {
                ResolveInfo app = apps.get(i);
                /** This builds the icon bitmaps */
                mBgAllAppsList.add(new AppInfo(packageManager, app, mIconCache, mLabelCache));
            }

            final ArrayList<AppInfo> added = mBgAllAppsList.added;
            mBgAllAppsList.added = new ArrayList<AppInfo>();

            mHandler.post(new Runnable() {
                public void run() {
                    final long bindTime = SystemClock.uptimeMillis();
                    final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindAllApplications(added);
                        Log.d(TAG, "bound " + added.size() + " apps in " + (SystemClock.uptimeMillis() - bindTime) + "ms");
                    } else {
                        Log.i(TAG, "not binding apps: no Launcher activity");
                    }
                }
            });
            Log.d(TAG, "Icons processed in " + (SystemClock.uptimeMillis() - loadTime) + "ms");
        }

        public void dumpState() {
            synchronized (sBgLock) {
                Log.d(TAG, "mLoaderTask.mContext=" + mContext);
                Log.d(TAG, "mLoaderTask.mIsLaunching=" + mIsLaunching);
                Log.d(TAG, "mLoaderTask.mStopped=" + mStopped);
                Log.d(TAG, "mLoaderTask.mLoadAndBindStepFinished=" + mLoadAndBindStepFinished);
                Log.d(TAG, "mItems size=" + sBgWorkspaceItems.size());
            }
        }
    }

    void enqueuePackageUpdated(PackageUpdatedTask task) {
        sWorker.post(task);
    }

    private class PackageUpdatedTask implements Runnable {
        int mOp;
        String[] mPackages;

        public static final int OP_NONE = 0;
        public static final int OP_ADD = 1;
        public static final int OP_UPDATE = 2;
        /** unInstalled */
        public static final int OP_REMOVE = 3;
        /** external media unmounted */
        public static final int OP_UNAVAILABLE = 4;

        public PackageUpdatedTask(int op, String[] packages) {
            mOp = op;
            mPackages = packages;
        }

        public void run() {
            final Context context = mApp.getContext();

            final String[] packages = mPackages;
            final int N = packages.length;
            switch (mOp) {
                case OP_ADD:
                    for (int i = 0; i < N; i++) {
                        Log.d(TAG, "mAllAppsList.addPackage " + packages[i]);
                        mBgAllAppsList.addPackage(context, packages[i]);
                    }
                    break;
                case OP_UPDATE:
                    for (int i = 0; i < N; i++) {
                        Log.d(TAG, "mAllAppsList.updatePackage " + packages[i]);
                        mBgAllAppsList.updatePackage(context, packages[i]);
                    }
                    break;
                case OP_REMOVE:
                case OP_UNAVAILABLE:
                    for (int i = 0; i < N; i++) {
                        Log.d(TAG, "mAllAppsList.removePackage " + packages[i]);
                        mBgAllAppsList.removePackage(packages[i]);
                    }
                    break;
            }

            ArrayList<AppInfo> added = null;
            ArrayList<AppInfo> modified = null;
            final ArrayList<AppInfo> removedApps = new ArrayList<AppInfo>();

            if (mBgAllAppsList.added.size() > 0) {
                added = new ArrayList<AppInfo>(mBgAllAppsList.added);
                mBgAllAppsList.added.clear();
            }
            if (mBgAllAppsList.modified.size() > 0) {
                modified = new ArrayList<AppInfo>(mBgAllAppsList.modified);
                mBgAllAppsList.modified.clear();
            }
            if (mBgAllAppsList.removed.size() > 0) {
                removedApps.addAll(mBgAllAppsList.removed);
                mBgAllAppsList.removed.clear();
            }

            final Callbacks callbacks = mCallbacks != null ? mCallbacks.get() : null;
            if (callbacks == null) {
                Log.w(TAG, "Nobody to tell about the new app.  Launcher is probably loading.");
                return;
            }

            if (added != null) {
                /** Ensure that we add all the workspace applications to the db */
                Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;

                boolean DISABLE_ALL_APPS = false;
                if (!DISABLE_ALL_APPS) {
                    addAndBindAddedApps(context, new ArrayList<ItemInfo>(), cb, added);
                } else {
                    final ArrayList<ItemInfo> addedInfos = new ArrayList<ItemInfo>(added);
                    addAndBindAddedApps(context, addedInfos, cb, added);
                }
            }
            if (modified != null) {
                final ArrayList<AppInfo> modifiedFinal = modified;
                /** Update the launcher db to reflect the changes */
                for (AppInfo a : modifiedFinal) {
                    ArrayList<ItemInfo> infos =
                            getItemInfoForComponentName(a.componentName);
                    for (ItemInfo i : infos) {
                        if (isShortcutInfoUpdateable(i)) {
                            ShortcutInfo info = (ShortcutInfo) i;
                            info.title = a.title.toString();
                            updateItemInDatabase(context, info);
                        }
                    }
                }

                mHandler.post(new Runnable() {
                    public void run() {
                        Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;
                        if (callbacks == cb && cb != null) {
                            callbacks.bindAppsUpdated(modifiedFinal);
                        }
                    }
                });
            }
            /**
             * If a package has been removed, or an app has been removed as a result of an update (for example), 
             * make the removed callback
             */
            if (mOp == OP_REMOVE || !removedApps.isEmpty()) {
                final boolean packageRemoved = (mOp == OP_REMOVE);
                final ArrayList<String> removedPackageNames =
                        new ArrayList<String>(Arrays.asList(packages));
                /** Update the launcher db to reflect the removal of apps */
                if (packageRemoved) {
                    for (String pn : removedPackageNames) {
                        ArrayList<ItemInfo> infos = getItemInfoForPackageName(pn);
                        for (ItemInfo i : infos) {
                            deleteItemFromDatabase(context, i);
                        }
                    }
                    /** Remove any queued items from the install queue */
                    String spKey = LauncherAppState.getSharedPreferencesKey();
                    SharedPreferences sp = context.getSharedPreferences(spKey, Context.MODE_PRIVATE);
                    InstallShortcutReceiver.removeFromInstallQueue(sp, removedPackageNames);
                } else {
                    for (AppInfo a : removedApps) {
                        ArrayList<ItemInfo> infos = getItemInfoForComponentName(a.componentName);
                        for (ItemInfo i : infos) {
                            deleteItemFromDatabase(context, i);
                        }
                    }
                }
                mHandler.post(new Runnable() {
                    public void run() {
                        Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;
                        if (callbacks == cb && cb != null) {
                            callbacks.bindComponentsRemoved(removedPackageNames, removedApps, packageRemoved);
                        }
                    }
                });
            }

            final ArrayList<Object> widgetsAndShortcuts = getSortedWidgetsAndShortcuts(context);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;
                    if (callbacks == cb && cb != null) {
                        callbacks.bindPackagesUpdated(widgetsAndShortcuts);
                    }
                }
            });

            /** Write all the logs to disk */
            mHandler.post(new Runnable() {
                public void run() {
                    Callbacks cb = mCallbacks != null ? mCallbacks.get() : null;
                    if (callbacks == cb && cb != null) {
                        callbacks.dumpLogsToLocalData();
                    }
                }
            });
        }
    }

    /**
     * @param context
     * @return a list of ResolveInfos/AppWindowInfos in sorted order
     */
    public static ArrayList<Object> getSortedWidgetsAndShortcuts(Context context) {
        PackageManager packageManager = context.getPackageManager();
        final ArrayList<Object> widgetsAndShortcuts = new ArrayList<Object>();
        widgetsAndShortcuts.addAll(AppWidgetManager.getInstance(context).getInstalledProviders());
        Intent shortcutsIntent = new Intent(Intent.ACTION_CREATE_SHORTCUT);
        widgetsAndShortcuts.addAll(packageManager.queryIntentActivities(shortcutsIntent, 0));
        Collections.sort(widgetsAndShortcuts, new LauncherModel.WidgetAndShortcutNameComparator(packageManager));
        return widgetsAndShortcuts;
    }

    private boolean isValidPackageComponent(PackageManager pm, ComponentName cn) {
        if (cn == null) {
            return false;
        }
        try {
            /** Skip if the application is disabled */
            PackageInfo pi = pm.getPackageInfo(cn.getPackageName(), 0);
            if (!pi.applicationInfo.enabled) {
                return false;
            }
            /** Check the activity */
            return (pm.getActivityInfo(cn, 0) != null);
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    /**
     * This is called from the code that adds shortcuts from the intent
     * receiver. This doesn't have a Cursor, but
     */
    public ShortcutInfo getShortcutInfo(PackageManager manager, Intent intent, Context context) {
        return getShortcutInfo(manager, intent, context, null, -1, -1, null);
    }

    /**
     * Make an ShortcutInfo object for a shortcut that is an application. If c
     * is not null, then it will be used to fill in missing data like the title
     * and icon.
     */
    public ShortcutInfo getShortcutInfo(PackageManager manager, Intent intent, Context context,
            Cursor c, int iconIndex, int titleIndex, HashMap<Object, CharSequence> labelCache) {
        ComponentName componentName = intent.getComponent();
        final ShortcutInfo info = new ShortcutInfo();
        if (componentName != null && !isValidPackageComponent(manager, componentName)) {
            Log.d(TAG, "Invalid package found in getShortcutInfo: " + componentName);
            return null;
        } else {
            try {
                PackageInfo pi = manager.getPackageInfo(componentName.getPackageName(), 0);
                info.initFlagsAndFirstInstallTime(pi);
            } catch (NameNotFoundException e) {
                Log.d(TAG, "getPackInfo failed for package " + componentName.getPackageName());
            }
        }

        Bitmap icon = null;
        ResolveInfo resolveInfo = null;
        ComponentName oldComponent = intent.getComponent();
        Intent newIntent = new Intent(intent.getAction(), null);
        newIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        newIntent.setPackage(oldComponent.getPackageName());
        List<ResolveInfo> infos = manager.queryIntentActivities(newIntent, 0);
        for (ResolveInfo i : infos) {
            ComponentName cn = new ComponentName(i.activityInfo.packageName, i.activityInfo.name);
            if (cn.equals(oldComponent)) {
                resolveInfo = i;
            }
        }
        if (resolveInfo == null) {
            resolveInfo = manager.resolveActivity(intent, 0);
        }
        if (resolveInfo != null) {
            icon = mIconCache.getIcon(componentName, resolveInfo, labelCache);
        }
        if (icon == null) {
            if (c != null) {
                icon = getIconFromCursor(c, iconIndex, context);
            }
        }
        if (icon == null) {
            icon = getFallbackIcon();
            info.usingFallbackIcon = true;
        }
        info.setIcon(icon);
        if (resolveInfo != null) {
            ComponentName key = LauncherModel.getComponentNameFromResolveInfo(resolveInfo);
            if (labelCache != null && labelCache.containsKey(key)) {
                info.title = labelCache.get(key);
            } else {
                info.title = resolveInfo.activityInfo.loadLabel(manager);
                if (labelCache != null) {
                    labelCache.put(key, info.title);
                }
            }
        }
        if (info.title == null) {
            if (c != null) {
                info.title = c.getString(titleIndex);
            }
        }
        if (info.title == null) {
            info.title = componentName.getClassName();
        }
        info.itemType = FavoritesType.ITEM_TYPE_APPLICATION;
        return info;
    }

    static ArrayList<ItemInfo> filterItemInfos(Collection<ItemInfo> infos, ItemInfoFilter f) {
        HashSet<ItemInfo> filtered = new HashSet<ItemInfo>();
        for (ItemInfo i : infos) {
            if (i instanceof ShortcutInfo) {
                ShortcutInfo info = (ShortcutInfo) i;
                ComponentName cn = info.intent.getComponent();
                if (cn != null && f.filterItem(null, info, cn)) {
                    filtered.add(info);
                }
            }
        }
        return new ArrayList<ItemInfo>(filtered);
    }

    private ArrayList<ItemInfo> getItemInfoForPackageName(final String pn) {
        ItemInfoFilter filter = new ItemInfoFilter() {
            @Override
            public boolean filterItem(ItemInfo parent, ItemInfo info, ComponentName cn) {
                return cn.getPackageName().equals(pn);
            }
        };
        return filterItemInfos(sBgItemsIdMap.values(), filter);
    }

    private ArrayList<ItemInfo> getItemInfoForComponentName(final ComponentName cname) {
        ItemInfoFilter filter = new ItemInfoFilter() {
            @Override
            public boolean filterItem(ItemInfo parent, ItemInfo info, ComponentName cn) {
                return cn.equals(cname);
            }
        };
        return filterItemInfos(sBgItemsIdMap.values(), filter);
    }

    public static boolean isShortcutInfoUpdateable(ItemInfo i) {
        if (i instanceof ShortcutInfo) {
            ShortcutInfo info = (ShortcutInfo) i;
            Intent intent = info.intent;
            ComponentName name = intent.getComponent();
            if (info.itemType == FavoritesType.ITEM_TYPE_APPLICATION && Intent.ACTION_MAIN.equals(intent.getAction()) && name != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Make an ShortcutInfo object for a shortcut that isn't an application.
     */
    private ShortcutInfo getShortcutInfo(Cursor c, Context context,
            int iconTypeIndex, int iconPackageIndex, int iconResourceIndex, int iconIndex, int titleIndex) {

        Bitmap icon = null;
        final ShortcutInfo info = new ShortcutInfo();
        info.itemType = FavoritesType.ITEM_TYPE_SHORTCUT;

        info.title = c.getString(titleIndex);

        int iconType = c.getInt(iconTypeIndex);
        switch (iconType) {
            case FavoritesIconType.ICON_TYPE_RESOURCE:
                String packageName = c.getString(iconPackageIndex);
                String resourceName = c.getString(iconResourceIndex);
                PackageManager packageManager = context.getPackageManager();
                info.customIcon = false;
                try {
                    Resources resources = packageManager.getResourcesForApplication(packageName);
                    if (resources != null) {
                        final int id = resources.getIdentifier(resourceName, null, null);
                        icon = Utilities.createIconBitmap(mIconCache.getFullResIcon(resources, id), context);
                    }
                } catch (Exception e) {

                }
                if (icon == null) {
                    icon = getIconFromCursor(c, iconIndex, context);
                }
                if (icon == null) {
                    icon = getFallbackIcon();
                    info.usingFallbackIcon = true;
                }
                break;
            case FavoritesIconType.ICON_TYPE_BITMAP:
                icon = getIconFromCursor(c, iconIndex, context);
                if (icon == null) {
                    icon = getFallbackIcon();
                    info.customIcon = false;
                    info.usingFallbackIcon = true;
                } else {
                    info.customIcon = true;
                }
                break;
            default:
                icon = getFallbackIcon();
                info.usingFallbackIcon = true;
                info.customIcon = false;
                break;
        }
        info.setIcon(icon);
        return info;
    }

    Bitmap getIconFromCursor(Cursor c, int iconIndex, Context context) {
        Log.d(TAG, "getIconFromCursor app=" + c.getString(c.getColumnIndexOrThrow(FavoritesColumns.TITLE)));
        byte[] data = c.getBlob(iconIndex);
        try {
            return Utilities.createIconBitmap(BitmapFactory.decodeByteArray(data, 0, data.length), context);
        } catch (Exception e) {
            return null;
        }
    }

    ShortcutInfo addShortcut(Context context, Intent data, long container, int screen, int cellX, int cellY, boolean notify) {
        final ShortcutInfo info = infoFromShortcutIntent(context, data, null);
        if (info == null) {
            return null;
        }
        addItemToDatabase(context, info, container, screen, cellX, cellY, notify);
        return info;
    }

    /**
     * Attempts to find an AppWidgetProviderInfo that matches the given component.
     */
    AppWidgetProviderInfo findAppWidgetProviderInfoWithComponent(Context context, ComponentName component) {
        List<AppWidgetProviderInfo> widgets = AppWidgetManager.getInstance(context).getInstalledProviders();
        for (AppWidgetProviderInfo info : widgets) {
            if (info.provider.equals(component)) {
                return info;
            }
        }
        return null;
    }

    ShortcutInfo infoFromShortcutIntent(Context context, Intent data, Bitmap fallbackIcon) {
        Intent intent = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
        String name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
        Parcelable bitmap = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON);
        if (intent == null) {
            Log.e(TAG, "Can't construct ShorcutInfo with null intent");
            return null;
        }

        Bitmap icon = null;
        boolean customIcon = false;
        ShortcutIconResource iconResource = null;

        if (bitmap != null && bitmap instanceof Bitmap) {
            icon = Utilities.createIconBitmap(new FastBitmapDrawable((Bitmap) bitmap), context);
            customIcon = true;
        } else {
            Parcelable extra = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);
            if (extra != null && extra instanceof ShortcutIconResource) {
                try {
                    iconResource = (ShortcutIconResource) extra;
                    final PackageManager packageManager = context.getPackageManager();
                    Resources resources = packageManager.getResourcesForApplication(iconResource.packageName);
                    final int id = resources.getIdentifier(iconResource.resourceName, null, null);
                    icon = Utilities.createIconBitmap(mIconCache.getFullResIcon(resources, id), context);
                } catch (Exception e) {
                    Log.w(TAG, "Could not load shortcut icon: " + extra);
                }
            }
        }
        final ShortcutInfo info = new ShortcutInfo();
        if (icon == null) {
            if (fallbackIcon != null) {
                icon = fallbackIcon;
            } else {
                icon = getFallbackIcon();
                info.usingFallbackIcon = true;
            }
        }
        info.setIcon(icon);
        info.title = name;
        info.intent = intent;
        info.customIcon = customIcon;
        info.iconResource = iconResource;
        return info;
    }

    boolean queueIconToBeChecked(HashMap<Object, byte[]> cache, ShortcutInfo info, Cursor c, int iconIndex) {
        /** If apps can't be on SD, don't even bother */
        if (!mAppsCanBeOnRemoveableStorage) {
            return false;
        }
        if (!info.customIcon && !info.usingFallbackIcon) {
            cache.put(info, c.getBlob(iconIndex));
            return true;
        }
        return false;
    }

    void updateSavedIcon(Context context, ShortcutInfo info, byte[] data) {
        boolean needSave = false;
        try {
            if (data != null) {
                Bitmap saved = BitmapFactory.decodeByteArray(data, 0, data.length);
                Bitmap loaded = info.getIcon(mIconCache);
                needSave = !saved.sameAs(loaded);
            } else {
                needSave = true;
            }
        } catch (Exception e) {
            needSave = true;
        }
        if (needSave) {
            Log.d(TAG, "going to save icon bitmap for info=" + info);
            updateItemInDatabase(context, info);
        }
    }

    public static final Comparator<AppInfo> getAppNameComparator() {
        final Collator collator = Collator.getInstance();
        return new Comparator<AppInfo>() {
            public final int compare(AppInfo a, AppInfo b) {
                int result = collator.compare(a.title.toString().trim(), b.title.toString().trim());
                if (result == 0) {
                    result = a.componentName.compareTo(b.componentName);
                }
                return result;
            }
        };
    }

    public static final Comparator<AppInfo> APP_INSTALL_TIME_COMPARATOR = new Comparator<AppInfo>() {
        public final int compare(AppInfo a, AppInfo b) {
            if (a.firstInstallTime < b.firstInstallTime)
                return 1;
            if (a.firstInstallTime > b.firstInstallTime)
                return -1;
            return 0;
        }
    };

    public static final Comparator<AppWidgetProviderInfo> getWidgetNameComparator() {
        final Collator collator = Collator.getInstance();
        return new Comparator<AppWidgetProviderInfo>() {
            public final int compare(AppWidgetProviderInfo a, AppWidgetProviderInfo b) {
                return collator.compare(a.label.toString().trim(), b.label.toString().trim());
            }
        };
    }

    static ComponentName getComponentNameFromResolveInfo(ResolveInfo info) {
        if (info.activityInfo != null) {
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        } else {
            return new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
        }
    }

    public static class ShortcutNameComparator implements Comparator<ResolveInfo> {
        private Collator mCollator;
        private PackageManager mPackageManager;
        private HashMap<Object, CharSequence> mLabelCache;

        ShortcutNameComparator(PackageManager pm) {
            mPackageManager = pm;
            mLabelCache = new HashMap<Object, CharSequence>();
            mCollator = Collator.getInstance();
        }

        ShortcutNameComparator(PackageManager pm, HashMap<Object, CharSequence> labelCache) {
            mPackageManager = pm;
            mLabelCache = labelCache;
            mCollator = Collator.getInstance();
        }

        public final int compare(ResolveInfo a, ResolveInfo b) {
            CharSequence labelA, labelB;
            ComponentName keyA = LauncherModel.getComponentNameFromResolveInfo(a);
            ComponentName keyB = LauncherModel.getComponentNameFromResolveInfo(b);
            if (mLabelCache.containsKey(keyA)) {
                labelA = mLabelCache.get(keyA);
            } else {
                labelA = a.loadLabel(mPackageManager).toString().trim();

                mLabelCache.put(keyA, labelA);
            }
            if (mLabelCache.containsKey(keyB)) {
                labelB = mLabelCache.get(keyB);
            } else {
                labelB = b.loadLabel(mPackageManager).toString().trim();

                mLabelCache.put(keyB, labelB);
            }
            return mCollator.compare(labelA, labelB);
        }
    };

    public static class WidgetAndShortcutNameComparator implements Comparator<Object> {
        private Collator mCollator;
        private PackageManager mPackageManager;
        private HashMap<Object, String> mLabelCache;

        WidgetAndShortcutNameComparator(PackageManager pm) {
            mPackageManager = pm;
            mLabelCache = new HashMap<Object, String>();
            mCollator = Collator.getInstance();
        }

        public final int compare(Object a, Object b) {
            String labelA, labelB;
            if (mLabelCache.containsKey(a)) {
                labelA = mLabelCache.get(a);
            } else {
                labelA = (a instanceof AppWidgetProviderInfo) ?
                        ((AppWidgetProviderInfo) a).label : ((ResolveInfo) a).loadLabel(mPackageManager).toString().trim();
                mLabelCache.put(a, labelA);
            }
            if (mLabelCache.containsKey(b)) {
                labelB = mLabelCache.get(b);
            } else {
                labelB = (b instanceof AppWidgetProviderInfo) ?
                        ((AppWidgetProviderInfo) b).label : ((ResolveInfo) b).loadLabel(mPackageManager).toString().trim();
                mLabelCache.put(b, labelB);
            }
            return mCollator.compare(labelA, labelB);
        }
    };

    public void dumpState() {
        Log.d(TAG, "mCallbacks=" + mCallbacks);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.data", mBgAllAppsList.data);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.added", mBgAllAppsList.added);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.removed", mBgAllAppsList.removed);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.modified", mBgAllAppsList.modified);
        if (mLoaderTask != null) {
            mLoaderTask.dumpState();
        } else {
            Log.d(TAG, "mLoaderTask=null");
        }
    }
}
