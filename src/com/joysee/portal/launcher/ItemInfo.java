/**
 * =====================================================================
 *
 * @file  ItemInfo.java
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

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;

import com.joysee.portal.launcher.LauncherProvider.FavoritesColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ItemInfo {

    static final int NO_ID = -1;

    /**
     * The id in the settings database for this item
     */
    long id = NO_ID;

    /**
     * One of {@link LauncherSettings.Favorites#ITEM_TYPE_APPLICATION},
     * {@link LauncherSettings.Favorites#ITEM_TYPE_SHORTCUT},
     * {@link LauncherSettings.Favorites#ITEM_TYPE_FOLDER}, or
     * {@link LauncherSettings.Favorites#ITEM_TYPE_APPWIDGET}.
     */
    int itemType;

    /**
     * The id of the container that holds this item. For the desktop, this will
     * be {@link LauncherSettings.Favorites#CONTAINER_DESKTOP}. For the all
     * applications folder it will be {@link #NO_ID} (since it is not stored in
     * the settings DB). For user folders it will be the id of the folder.
     */
    long container = NO_ID;

    /**
     * Iindicates the screen in which the shortcut appears.
     */
    long screenId = -1;

    /**
     * Indicates the X position of the associated cell.
     */
    int cellX = -1;

    /**
     * Indicates the Y position of the associated cell.
     */
    int cellY = -1;

    /**
     * Indicates the X cell span.
     */
    int spanX = 1;

    /**
     * Indicates the Y cell span.
     */
    int spanY = 1;

    /**
     * Indicates the minimum X cell span.
     */
    int minSpanX = 1;

    /**
     * Indicates the minimum Y cell span.
     */
    int minSpanY = 1;

    /**
     * Indicates that this item needs to be updated in the db
     */
    boolean requiresDbUpdate = false;

    /**
     * Title of the item
     */
    CharSequence title;

    /**
     * The position of the item in a drag-and-drop operation.
     */
    int[] dropPos = null;

    ItemInfo() {
    }

    ItemInfo(ItemInfo info) {
        id = info.id;
        cellX = info.cellX;
        cellY = info.cellY;
        spanX = info.spanX;
        spanY = info.spanY;
        screenId = info.screenId;
        itemType = info.itemType;
        container = info.container;

        // TODO
        LauncherModel.checkItemInfo(this);
    }

    protected Intent getIntent() {
        throw new RuntimeException("Unexpected Intent");
    }

    /**
     * Write the fields of this item to the DB
     * 
     * @param values
     */
    void onAddToDatabase(ContentValues values) {
        values.put(FavoritesColumns.ITEMTYPE, itemType);
        values.put(FavoritesColumns.CONTAINER, container);
        values.put(FavoritesColumns.SCREEN, screenId);
        values.put(FavoritesColumns.CELLX, cellX);
        values.put(FavoritesColumns.CELLY, cellY);
        values.put(FavoritesColumns.CELLW, spanX);
        values.put(FavoritesColumns.CELLH, spanY);
    }

    void updateValuesWithCoordinates(ContentValues values, int cellX, int cellY) {
        values.put(FavoritesColumns.CELLX, cellX);
        values.put(FavoritesColumns.CELLY, cellY);
    }

    static byte[] flattenBitmap(Bitmap bitmap) {
        // Try go guesstimate how much space the icon will take when serialized
        // to avoid unnecessary allocations/copies during the write.
        int size = bitmap.getWidth() * bitmap.getHeight() * 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            return out.toByteArray();
        } catch (IOException e) {
            Log.w("Favorite", "Could not write icon");
            return null;
        }
    }

    static void writeBitmap(ContentValues values, Bitmap bitmap) {
        if (bitmap != null) {
            byte[] data = flattenBitmap(bitmap);
            values.put(FavoritesColumns.ICON, data);
        }
    }

    /**
     * It is very important that sub-classes implement this if they contain any
     * references to the activity (anything in the view hierarchy etc.). If not,
     * leaks can result since ItemInfo objects persist across rotation and can
     * hence leak by holding stale references to the old view hierarchy /
     * activity.
     */
    void unbind() {
    }

    @Override
    public String toString() {
        return "Item(id=" + this.id + " type=" + this.itemType + " container=" + this.container
                + " screen=" + screenId + " cellX=" + cellX + " cellY=" + cellY + " spanX=" + spanX
                + " spanY=" + spanY + " dropPos=" + dropPos + ")";
    }
}
