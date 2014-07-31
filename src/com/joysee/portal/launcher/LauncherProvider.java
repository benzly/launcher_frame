/**
 * =====================================================================
 *
 * @file  PortalProvider.java
 * @Module Name   com.joysee.dvb.portal.db
 * @author YueLiang_TP
 * @OS version  1.0
 * @Product type: JoySee
 * @date   2014年7月10日
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
 * YueLiang_TP         2014年7月10日            1.0          Check for NULL, 0 h/w
 * =====================================================================
 **/

package com.joysee.portal.launcher;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.joysee.common.utils.JLog;
import com.joysee.portal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class LauncherProvider extends ContentProvider {

    public static final class FavoritesType {
        /**
         * The gesture is an application
         */
        static final int ITEM_TYPE_APPLICATION = 0;
        /**
         * The gesture is an application created shortcut
         */
        static final int ITEM_TYPE_SHORTCUT = 1;
    }

    public static final class FavoritesIconType {
        /**
         * The icon is a resource identified by a package name and an integer id.
         */
        static final int ICON_TYPE_RESOURCE = 0;
        /**
         * The icon is a bitmap.
         */
        static final int ICON_TYPE_BITMAP = 1;
    }

    public static final class FavoritesContainerType {
        /**
         * The icon is a resource identified by a package name and an integer id.
         */
        static final int CONTAINER_DESKTOP = -100;
        static final int CONTAINER_HOTSEAT = -101;
    }

    public static final class FavoritesURI {
        /**
         * The content:// style URL for this table
         */
        static final Uri CONTENT_URI = Uri.parse("content://" +
                LauncherProvider.AUTHORITY + "/" + LauncherProvider.TABLE_FAVORITES +
                "?" + LauncherProvider.PARAMETER_NOTIFY + "=true");

        /**
         * The content:// style URL for this table. When this Uri is used, no
         * notification is sent if the content changes.
         */
        static final Uri CONTENT_URI_NO_NOTIFICATION = Uri.parse("content://" +
                LauncherProvider.AUTHORITY + "/" + LauncherProvider.TABLE_FAVORITES +
                "?" + LauncherProvider.PARAMETER_NOTIFY + "=false");

        /**
         * The content:// style URL for a given row, identified by its id.
         * 
         * @param id The row id.
         * @param notify True to send a notification is the content changes.
         * @return The unique content URL for the specified row.
         */
        static Uri getContentUri(long id, boolean notify) {
            return Uri.parse("content://" + LauncherProvider.AUTHORITY +
                    "/" + LauncherProvider.TABLE_FAVORITES + "/" + id + "?" +
                    LauncherProvider.PARAMETER_NOTIFY + "=" + notify);
        }

    }

    /**
     * Workspace Screens. Tracks the order of workspace screens.
     */
    public static final class WorkspaceScreensURI {
        static final Uri CONTENT_URI = Uri.parse("content://" +
                LauncherProvider.AUTHORITY + "/" + LauncherProvider.TABLE_WORKSPACE_SCREENS +
                "?" + LauncherProvider.PARAMETER_NOTIFY + "=true");

    }

    public static final class FavoritesColumns {
        public static final String _ID = "_id";
        public static final String TITLE = "title";
        public static final String INTENT = "intent";
        public static final String SCREEN = "screen";
        public static final String CELLX = "cellX";
        public static final String CELLY = "cellY";
        public static final String CELLW = "cellW";
        public static final String CELLH = "cellH";
        public static final String ITEMTYPE = "itemType";
        public static final String ISSHORTCUT = "isShortcut";
        public static final String ICONTYPE = "iconType";
        public static final String ICONPACKAGE = "iconPackage";
        public static final String ICONRESOURCE = "iconResource";
        public static final String URI = "_uri";
        public static final String CONTAINER = "container";
        public static final String ICON = "icon";
        public static final String DISPLAYMODE = "displayMode";
    }

    protected static final class FavoritesColumnsIndex {
        public static final int _ID = 0;
        public static final int TITLE = 1;
        public static final int INTENT = 2;
        public static final int SCREEN = 3;
        public static final int CELLX = 4;
        public static final int CELLY = 5;
        public static final int CELLW = 6;
        public static final int CELLH = 7;
        public static final int ITEMTYPE = 8;
        public static final int ISSHORTCUT = 9;
        public static final int ICONTYPE = 10;
        public static final int ICONPACKAGE = 11;
        public static final int ICONRESOURCE = 12;
        public static final int URI = 13;
        public static final int CONTAINER = 14;
        public static final int ICON = 15;
        public static final int DISPLAYMODE = 16;
    }

    public static final class WorkspacesColumns {
        public static final String _ID = "_id";
        public static final String SCREEN_RANK = "screen_rank";
        public static final String SCREEN_NAME = "screen_name";
        public static final String MODIFIED = "modified";
    }

    protected static final class WorkspacesColumnsIndex {
        public static final int _ID = 0;
        public static final int SCREEN_RANK = 1;
        public static final int SCREEN_NAME = 2;
        public static final int MODIFIED = 3;
    }

    private static final class DatabaseHelper extends SQLiteOpenHelper {
        private static final String TAG_FAVORITES = "favorites";
        private static final String TAG_FAVORITE = "favorite";
        private static final String TAG_SHORTCUT = "shortcut";
        private static final String TAG_EXTRA = "extra";
        private static final String TAG_INCLUDE = "include";

        private static final String tag_ = "";

        private Context mContext;
        private long mMaxItemId = -1;
        private long mMaxScreenId = -1;

        public DatabaseHelper(final Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mContext = context;
            if (mMaxItemId == -1) {
                mMaxItemId = initializeMaxItemId(getWritableDatabase());
            }
            if (mMaxScreenId == -1) {
                mMaxScreenId = initializeMaxScreenId(getWritableDatabase());
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            JLog.d(TAG, "PortalProvider DatabaseHelper onCreate");
            createFavoritesTable(db);
            createWorkspacesTable(db);
            setFlagEmptyDbCreated();
        }

        private void createFavoritesTable(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_FAVORITES);
            StringBuilder sqlCreateTable = new StringBuilder();
            sqlCreateTable.append("CREATE TABLE IF NOT EXISTS ")
                    .append(TABLE_FAVORITES)
                    .append(" (")
                    .append(FavoritesColumns._ID).append(" INTEGER PRIMARY KEY,")
                    .append(FavoritesColumns.TITLE).append(" TEXT,")
                    .append(FavoritesColumns.INTENT).append(" TEXT,")
                    .append(FavoritesColumns.SCREEN).append(" INTEGER,")
                    .append(FavoritesColumns.CELLX).append(" INTEGER,")
                    .append(FavoritesColumns.CELLY).append(" INTEGER,")
                    .append(FavoritesColumns.CELLW).append(" INTEGER,")
                    .append(FavoritesColumns.CELLH).append(" INTEGER,")
                    .append(FavoritesColumns.ITEMTYPE).append(" INTEGER,")
                    .append(FavoritesColumns.ISSHORTCUT).append(" INTEGER,")
                    .append(FavoritesColumns.ICONTYPE).append(" INTEGER,")
                    .append(FavoritesColumns.ICONPACKAGE).append(" TEXT,")
                    .append(FavoritesColumns.ICONRESOURCE).append(" TEXT,")
                    .append(FavoritesColumns.URI).append(" TEXT,")
                    .append(FavoritesColumns.CONTAINER).append(" INTEGER,")
                    .append(FavoritesColumns.ICON).append(" BLOB,")
                    .append(FavoritesColumns.DISPLAYMODE).append(" INTEGER")
                    .append(")");
            db.execSQL(sqlCreateTable.toString());
        }

        private void createWorkspacesTable(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORKSPACE_SCREENS);
            StringBuilder sqlCreateTable = new StringBuilder();
            sqlCreateTable.append("CREATE TABLE IF NOT EXISTS ")
                    .append(TABLE_WORKSPACE_SCREENS)
                    .append(" (")
                    .append(WorkspacesColumns._ID).append(" INTEGER PRIMARY KEY,")
                    .append(WorkspacesColumns.SCREEN_RANK).append(" INTEGER,")
                    .append(WorkspacesColumns.SCREEN_NAME).append(" TEXT,")
                    .append(WorkspacesColumns.MODIFIED).append(" INTEGER NOT NULL DEFAULT 0")
                    .append(")");
            db.execSQL(sqlCreateTable.toString());
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            JLog.d(TAG, "PortalProvider DatabaseHelper onUpgrade oldVersion = " + oldVersion + " newVersion = " + newVersion);
            int upgradeVersion = oldVersion;
            if (upgradeVersion != newVersion) {
                Log.w(TAG, "Got stuck trying to upgrade from version " + newVersion + ", must wipe the provider");
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_FAVORITES);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORKSPACE_SCREENS);
                onCreate(db);
            }
        }

        private long initializeMaxItemId(SQLiteDatabase db) {
            Cursor c = db.rawQuery("SELECT MAX(_id) FROM favorites", null);
            final int maxIdIndex = 0;
            long id = -1;
            if (c != null && c.moveToNext()) {
                id = c.getLong(maxIdIndex);
            }
            if (c != null) {
                c.close();
            }
            if (id == -1) {
                throw new RuntimeException("Error: could not query max item id");
            }
            return id;
        }

        private long initializeMaxScreenId(SQLiteDatabase db) {
            Cursor c = db.rawQuery("SELECT MAX(" + WorkspacesColumns._ID + ") FROM " + TABLE_WORKSPACE_SCREENS, null);
            final int maxIdIndex = 0;
            long id = -1;
            if (c != null && c.moveToNext()) {
                id = c.getLong(maxIdIndex);
            }
            if (c != null) {
                c.close();
            }
            if (id == -1) {
                throw new RuntimeException("Error: could not query max screen id");
            }
            return id;
        }

        /**
         * Generates a new ID to use for an workspace screen in your database.
         * This method should be only called from the main UI thread. As an
         * exception, we do call it when we call the constructor from the worker
         * thread; however, this doesn't extend until after the constructor is
         * called, and we only pass a reference to LauncherProvider to
         * LauncherApp after that point
         * 
         * @return
         */
        public long generateNewScreenId() {
            if (mMaxScreenId < 0) {
                throw new RuntimeException("Error: max screen id was not initialized");
            }
            mMaxScreenId += 1;
            return mMaxScreenId;
        }

        /**
         * Generates a new ID to use for an object in your database. This method
         * should be only called from the main UI thread. As an exception, we do
         * call it when we call the constructor from the worker thread; however,
         * this doesn't extend until after the constructor is called, and we
         * only pass a reference to LauncherProvider to LauncherApp after that
         * point
         * 
         * @return
         */
        public long generateNewItemId() {
            if (mMaxItemId < 0) {
                throw new RuntimeException("Error: max item id was not initialized");
            }
            mMaxItemId += 1;
            return mMaxItemId;
        }

        /**
         * Loads the default set of favorite packages from an xml file.
         * 
         * @param db The database to write the values into
         * @param filterContainerId The specific container id of items to load
         */
        private int loadFavorites(SQLiteDatabase db, int workspaceResourceId) {
            JLog.d(TAG, String.format("loadFavorites from resid=0x%08x", workspaceResourceId));

            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            ContentValues values = new ContentValues();
            PackageManager packageManager = mContext.getPackageManager();
            int i = 0;
            try {
                XmlResourceParser parser = mContext.getResources().getXml(workspaceResourceId);
                AttributeSet attrs = Xml.asAttributeSet(parser);
                beginDocument(parser, TAG_FAVORITES);
                final int depth = parser.getDepth();
                int type;
                while (((type = parser.next()) != XmlPullParser.END_TAG ||
                        parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) {
                        continue;
                    }
                    boolean added = false;
                    final String name = parser.getName();

                    if (TAG_INCLUDE.equals(name)) {
                        final TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.Include);
                        final int resId = a.getResourceId(R.styleable.Include_workspace, 0);
                        JLog.d(TAG, String.format(("%" + (2 * (depth + 1)) + "s<include workspace=%08x>"), "", resId));
                        if (resId != 0 && resId != workspaceResourceId) {
                            /** recursively load some more favorites, why not? */
                            i += loadFavorites(db, resId);
                            added = false;
                            mMaxItemId = -1;
                        } else {
                            Log.w(TAG, String.format("Skipping <include workspace=0x%08x>", resId));
                        }
                        a.recycle();
                        JLog.d(TAG, String.format(("%" + (2 * (depth + 1)) + "s</include>"), ""));
                        continue;
                    }

                    // Assuming it's a <favorite> at this point
                    TypedArray typedArray = mContext.obtainStyledAttributes(attrs, R.styleable.Favorite);

                    long container = FavoritesContainerType.CONTAINER_DESKTOP;
                    if (typedArray.hasValue(R.styleable.Favorite_container)) {
                        container = Long.valueOf(typedArray.getString(R.styleable.Favorite_container));
                    }

                    String screen = typedArray.getString(R.styleable.Favorite_screen);
                    String x = typedArray.getString(R.styleable.Favorite_x);
                    String y = typedArray.getString(R.styleable.Favorite_y);

                    values.clear();
                    values.put(FavoritesColumns.CONTAINER, container);
                    values.put(FavoritesColumns.SCREEN, screen);
                    values.put(FavoritesColumns.CELLX, x);
                    values.put(FavoritesColumns.CELLY, y);

                    final String title = typedArray.getString(R.styleable.Favorite_title);
                    final String pkg = typedArray.getString(R.styleable.Favorite_packageName);
                    final String something = title != null ? title : pkg;
                    Log.d(TAG, String.format(
                            ("%" + (2 * (depth + 1)) + "s<%s%s c=%d s=%s x=%s y=%s>"),
                            "", name,
                            (something == null ? "" : (" \"" + something + "\"")),
                            container, screen, x, y));

                    if (TAG_FAVORITE.equals(name)) {
                        long id = addAppShortcut(db, values, typedArray, packageManager, intent);
                        added = id >= 0;
                    } else if (TAG_SHORTCUT.equals(name)) {
                        addUriShortcut(db, values, typedArray);
                    }
                    if (added) {
                        i++;
                    }
                    typedArray.recycle();
                }
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Got exception parsing favorites.", e);
            } catch (IOException e) {
                Log.w(TAG, "Got exception parsing favorites.", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "Got exception parsing favorites.", e);
            }

            // Update the max item id after we have loaded the database
            if (mMaxItemId == -1) {
                mMaxItemId = initializeMaxItemId(db);
            }

            return i;
        }

        private static final void beginDocument(XmlPullParser parser, String firstElementName)
                throws XmlPullParserException, IOException {
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
                ;
            }
            if (type != XmlPullParser.START_TAG) {
                throw new XmlPullParserException("No start tag found");
            }
            if (!parser.getName().equals(firstElementName)) {
                throw new XmlPullParserException("Unexpected start tag: found " + parser.getName() +
                        ", expected " + firstElementName);
            }
        }

        private long addAppShortcut(SQLiteDatabase db, ContentValues values, TypedArray typeArray, PackageManager packageManager,
                Intent intent) {
            long id = -1;
            ActivityInfo info;
            String packageName = typeArray.getString(R.styleable.Favorite_packageName);
            String className = typeArray.getString(R.styleable.Favorite_className);
            try {
                ComponentName cn;
                try {
                    cn = new ComponentName(packageName, className);
                    info = packageManager.getActivityInfo(cn, 0);
                } catch (PackageManager.NameNotFoundException nnfe) {
                    String[] packages = packageManager.currentToCanonicalPackageNames(
                            new String[] {
                                    packageName
                            });
                    cn = new ComponentName(packages[0], className);
                    info = packageManager.getActivityInfo(cn, 0);
                }
                id = generateNewItemId();
                intent.setComponent(cn);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                values.put(FavoritesColumns.INTENT, intent.toUri(0));
                values.put(FavoritesColumns.TITLE, info.loadLabel(packageManager).toString());
                values.put(FavoritesColumns.ITEMTYPE, FavoritesType.ITEM_TYPE_APPLICATION);
                values.put(FavoritesColumns.CELLW, typeArray.getString(R.styleable.Favorite_w));
                values.put(FavoritesColumns.CELLH, typeArray.getString(R.styleable.Favorite_h));
                values.put(FavoritesColumns._ID, generateNewItemId());
                if (dbInsertAndCheck(this, db, TABLE_FAVORITES, null, values) < 0) {
                    return -1;
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Unable to add favorite: " + packageName + "/" + className, e);
            }
            return id;
        }

        private long addUriShortcut(SQLiteDatabase db, ContentValues values, TypedArray typeArray) {
            Resources r = mContext.getResources();

            final int iconResId = typeArray.getResourceId(R.styleable.Favorite_icon, 0);
            final int titleResId = typeArray.getResourceId(R.styleable.Favorite_title, 0);

            Intent intent;
            String uri = null;
            try {
                uri = typeArray.getString(R.styleable.Favorite_uri);
                intent = Intent.parseUri(uri, 0);
            } catch (URISyntaxException e) {
                Log.w(TAG, "Shortcut has malformed uri: " + uri);
                return -1;
            } catch (Exception e) {
                return -1;
            }

            if (iconResId == 0 || titleResId == 0) {
                Log.w(TAG, "Shortcut is missing title or icon resource ID");
                return -1;
            }

            long id = generateNewItemId();
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            values.put(FavoritesColumns._ID, id);
            values.put(FavoritesColumns.INTENT, intent.toUri(0));
            values.put(FavoritesColumns.TITLE, r.getString(titleResId));
            values.put(FavoritesColumns.ITEMTYPE, FavoritesType.ITEM_TYPE_SHORTCUT);
            values.put(FavoritesColumns.CELLW, typeArray.getString(R.styleable.Favorite_w));
            values.put(FavoritesColumns.CELLH, typeArray.getString(R.styleable.Favorite_h));
            values.put(FavoritesColumns.ICONTYPE, FavoritesIconType.ICON_TYPE_RESOURCE);
            values.put(FavoritesColumns.ICONPACKAGE, mContext.getPackageName());
            values.put(FavoritesColumns.ICONRESOURCE, r.getResourceName(iconResId));

            if (dbInsertAndCheck(this, db, TABLE_FAVORITES, null, values) < 0) {
                return -1;
            }
            return id;
        }

        private void setFlagJustLoadedOldDb() {
            String spKey = LauncherAppState.getSharedPreferencesKey();
            SharedPreferences sp = mContext.getSharedPreferences(spKey, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean(UPGRADED_FROM_OLD_DATABASE, true);
            editor.putBoolean(EMPTY_DATABASE_CREATED, false);
            editor.commit();
        }

        private void setFlagEmptyDbCreated() {
            String spKey = LauncherAppState.getSharedPreferencesKey();
            SharedPreferences sp = mContext.getSharedPreferences(spKey, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean(EMPTY_DATABASE_CREATED, true);
            editor.putBoolean(UPGRADED_FROM_OLD_DATABASE, false);
            editor.commit();
        }

        public void updateMaxScreenId(long maxScreenId) {
            mMaxScreenId = maxScreenId;
        }

        public void updateMaxItemId(long id) {
            mMaxItemId = id + 1;
        }
    }

    private static class SqlSelection {
        public StringBuilder mWhereClause = new StringBuilder();
        public List<String> mParameters = new ArrayList<String>();

        public <T> void appendClause(String newClause, final T... parameters) {
            if (newClause == null || newClause.isEmpty()) {
                return;
            }
            if (mWhereClause.length() != 0) {
                mWhereClause.append(" AND ");
            }
            mWhereClause.append("(");
            mWhereClause.append(newClause);
            mWhereClause.append(")");
            if (parameters != null) {
                for (Object parameter : parameters) {
                    mParameters.add(parameter.toString());
                }
            }
        }

        public String[] getParameters() {
            String[] array = new String[mParameters.size()];
            return mParameters.toArray(array);
        }

        public String getSelection() {
            return mWhereClause.toString();
        }
    }

    private static final String TAG = JLog.makeTag(LauncherProvider.class);

    public static final String EMPTY_DATABASE_CREATED = "EMPTY_DATABASE_CREATED";
    public static final String DEFAULT_WORKSPACE_RESOURCE_ID = "DEFAULT_WORKSPACE_RESOURCE_ID";
    public static final String UPGRADED_FROM_OLD_DATABASE = "UPGRADED_FROM_OLD_DATABASE";

    private DatabaseHelper mOpenHelper = null;
    private static boolean sJustLoadedFromOldDb;

    private static final String DATABASE_NAME = "portal.db";
    private static final int DATABASE_VERSION = 1;
    static final String AUTHORITY = "com.joysee.dvb.portal.db.PortalProvider";
    static final String PARAMETER_NOTIFY = "notify";

    static final String TABLE_FAVORITES = "favorites";
    static final String TABLE_WORKSPACE_SCREENS = "workspaceScreens";

    private static final int FAVORITES = 1;
    private static final int WORKSPACE_SCREENS = 2;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sURIMatcher.addURI(AUTHORITY, TABLE_FAVORITES, FAVORITES);
        sURIMatcher.addURI(AUTHORITY, TABLE_WORKSPACE_SCREENS, WORKSPACE_SCREENS);
    }

    public static final Uri CONTENT_URI_HISTORY = Uri.parse("content://" + AUTHORITY + "/"
            + TABLE_FAVORITES + "?" + PARAMETER_NOTIFY + "=true");

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int numValues = 0;
        try {
            numValues = values.length;
            db.beginTransaction();
            for (int i = 0; i < numValues; i++) {
                insert(uri, values[i]);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            JLog.d(TAG, "", e);
            numValues = 0;
        } finally {
            db.endTransaction();
        }
        return numValues;
    }

    @Override
    public boolean onCreate() {
        JLog.d(TAG, "----PortalProvider  onCreate----");
        mOpenHelper = new DatabaseHelper(getContext());
        LauncherAppState.setLauncherProvider(this);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        int match = sURIMatcher.match(uri);
        JLog.d(TAG, "PortalProvider query match = " + matchToTable(match));
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        String table = null;
        switch (match) {
            case FAVORITES:
                table = TABLE_FAVORITES;
                break;
            case WORKSPACE_SCREENS:
                table = TABLE_WORKSPACE_SCREENS;
                break;
        }

        SqlSelection fullSelection = getWhereClause(uri, selection, selectionArgs, 0);
        Cursor ret = db.query(table, projection, fullSelection.getSelection(),
                fullSelection.getParameters(), null, null, sortOrder);
        return ret;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values == null) {
            return null;
        }
        int match = sURIMatcher.match(uri);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        String table = null;
        switch (match) {
            case FAVORITES:
                table = TABLE_FAVORITES;
                break;
            case WORKSPACE_SCREENS:
                table = TABLE_WORKSPACE_SCREENS;
                break;
        }

        long rowID = db.insert(table, null, values);
        if (rowID == -1) {
            JLog.d(TAG, "couldn't insert into dvb database");
            return null;
        }
        Uri ret = ContentUris.withAppendedId(uri, rowID);
        sendNotify(uri);
        return ret;
    }

    private String matchToTable(int match) {
        String ret = null;
        switch (match) {
            case FAVORITES:
                ret = TABLE_FAVORITES;
                break;
            case WORKSPACE_SCREENS:
                ret = TABLE_WORKSPACE_SCREENS;
                break;
        }
        return ret;
    }

    private SqlSelection getWhereClause(final Uri uri, final String where,
            final String[] whereArgs,
            int uriMatch) {
        SqlSelection selection = new SqlSelection();
        selection.appendClause(where, whereArgs);
        return selection;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = sURIMatcher.match(uri);
        final long begin = JLog.methodBegin(TAG);
        JLog.d(TAG, "PortalProvider delete match = " + matchToTable(match));
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        String table = null;
        switch (match) {
            case FAVORITES:
                table = TABLE_FAVORITES;
                break;
            case WORKSPACE_SCREENS:
                table = TABLE_WORKSPACE_SCREENS;
                break;
        }

        int count;
        SqlSelection fullSelection = getWhereClause(uri, selection, selectionArgs, 0);
        count = db.delete(table, fullSelection.getSelection(), fullSelection.getParameters());
        JLog.methodEnd(TAG, begin, "delete uri = " + uri + " change " + count + " rows.");
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int match = sURIMatcher.match(uri);
        JLog.d(TAG, "PortalProvider update match = " + matchToTable(match));
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        String table = null;
        switch (match) {
            case FAVORITES:
                table = TABLE_FAVORITES;
                break;
            case WORKSPACE_SCREENS:
                table = TABLE_WORKSPACE_SCREENS;
                break;
        }

        SqlSelection fullSelection = getWhereClause(uri, selection, selectionArgs, 0);
        int count = db.update(table, values, fullSelection.getSelection(),
                fullSelection.getParameters());
        JLog.d(TAG, "update uri = " + uri + " change " + count + " rows.");
        if (count > 0) {
            sendNotify(uri);
        }
        return count;
    }

    private void sendNotify(Uri uri) {
        String notify = uri.getQueryParameter(PARAMETER_NOTIFY);
        if (notify == null || "true".equals(notify)) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    public long generateNewScreenId() {
        return mOpenHelper.generateNewScreenId();
    }

    public long generateNewItemId() {
        return mOpenHelper.generateNewItemId();
    }

    /**
     * @param workspaceResId that can be 0 to use default or non-zero for specific resource
     */
    public synchronized void loadDefaultFavoritesIfNecessary(int origWorkspaceResId) {
        JLog.d(TAG, "loadDefaultFavoritesIfNecessary " + origWorkspaceResId);
        String spKey = LauncherAppState.getSharedPreferencesKey();
        SharedPreferences sp = getContext().getSharedPreferences(spKey, Context.MODE_PRIVATE);

        if (sp.getBoolean(EMPTY_DATABASE_CREATED, false)) {
            int workspaceResId = origWorkspaceResId;

            /** 选择加载默认workspace XML */
            if (workspaceResId == 0) {
                workspaceResId = sp.getInt(DEFAULT_WORKSPACE_RESOURCE_ID, R.xml.default_workspace);
            }
            // Populate favorites table with initial favorites
            SharedPreferences.Editor editor = sp.edit();
            editor.remove(EMPTY_DATABASE_CREATED);
            if (origWorkspaceResId != 0) {
                editor.putInt(DEFAULT_WORKSPACE_RESOURCE_ID, origWorkspaceResId);
            }
            mOpenHelper.loadFavorites(mOpenHelper.getWritableDatabase(), workspaceResId);
            mOpenHelper.setFlagJustLoadedOldDb();
            editor.commit();
        }
    }

    private static long dbInsertAndCheck(DatabaseHelper helper, SQLiteDatabase db, String table,
            String nullColumnHack, ContentValues values) {
        if (!values.containsKey(FavoritesColumns._ID)) {
            throw new RuntimeException("Error: attempting to add item without specifying an id");
        }
        return db.insert(table, nullColumnHack, values);
    }

    /**
     * @param Should we load the old db for upgrade? first run only.
     */
    public synchronized boolean justLoadedOldDb() {
        String spKey = LauncherAppState.getSharedPreferencesKey();
        SharedPreferences sp = getContext().getSharedPreferences(spKey, Context.MODE_PRIVATE);
        boolean loadedOldDb = false || sJustLoadedFromOldDb;
        sJustLoadedFromOldDb = false;
        if (sp.getBoolean(UPGRADED_FROM_OLD_DATABASE, false)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.remove(UPGRADED_FROM_OLD_DATABASE);
            editor.commit();
            loadedOldDb = true;
        }
        return loadedOldDb;
    }

    /**
     * This is only required one time while loading the workspace during the upgrade path, 
     * and should never be called from anywhere else.
     * @param maxScreenId
     */
    public void updateMaxScreenId(long maxScreenId) {
        mOpenHelper.updateMaxScreenId(maxScreenId);
    }

    public void updateMaxItemId(long id) {
        mOpenHelper.updateMaxItemId(id);
    }
}
