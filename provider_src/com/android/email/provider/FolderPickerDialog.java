/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.email.provider;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnMultiChoiceClickListener;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;

import com.android.mail.R;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.FolderSelectorAdapter;
import com.android.mail.ui.FolderSelectorAdapter.FolderRow;
import com.android.mail.ui.SeparatedFolderListAdapter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

public class FolderPickerDialog implements OnClickListener, OnMultiChoiceClickListener,
        OnCancelListener {
    private final AlertDialog mDialog;
    private final HashMap<Folder, Boolean> mCheckedState;
    private final SeparatedFolderListAdapter mAdapter;
    private final FolderPickerCallback mCallback;

    public FolderPickerDialog(final Context context, Uri uri,
            FolderPickerCallback callback, String header, boolean cancelable) {
        mCallback = callback;
        // Mapping of a folder's uri to its checked state
        mCheckedState = new HashMap<Folder, Boolean>();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(header);
        builder.setPositiveButton(R.string.ok, this);
        builder.setCancelable(cancelable);
        builder.setOnCancelListener(this);
        // TODO: Do this on a background thread
        final Cursor foldersCursor = context.getContentResolver().query(
                uri, UIProvider.FOLDERS_PROJECTION, null, null, null);
        try {
            mAdapter = new SeparatedFolderListAdapter();
            mAdapter.addSection(new FolderPickerSelectorAdapter(context, foldersCursor,
                    new HashSet<String>(), R.layout.multi_folders_view));
            builder.setAdapter(mAdapter, this);
        } finally {
            foldersCursor.close();
        }
        mDialog = builder.create();
    }

    public void show() {
        mDialog.show();
        mDialog.getListView().setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Object item = mAdapter.getItem(position);
                if (item instanceof FolderRow) {
                    update((FolderRow) item);
                }
            }
        });

        final Button button = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (mCheckedState.size() == 0) {
            // No items are selected, so disable the OK button.
            button.setEnabled(false);
        }
    }

    /**
     * Call this to update the state of folders as a result of them being
     * selected / de-selected.
     *
     * @param row The item being updated.
     */
    public void update(FolderSelectorAdapter.FolderRow row) {
        // Update the UI
        final boolean add = !row.isSelected();
        if (!add) {
            // This would remove the check on a single radio button, so just
            // return.
            return;
        }
        // Clear any other checked items.
        mAdapter.getCount();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            Object item = mAdapter.getItem(i);
            if (item instanceof FolderRow) {
                ((FolderRow)item).setIsSelected(false);
            }
        }
        mCheckedState.clear();
        row.setIsSelected(add);
        mAdapter.notifyDataSetChanged();
        mCheckedState.put(row.getFolder(), add);

        // Since we know that an item is selected in the list, enable the OK button
        final Button button = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        button.setEnabled(true);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                Folder folder = null;
                for (Entry<Folder, Boolean> entry : mCheckedState.entrySet()) {
                    if (entry.getValue()) {
                        folder = entry.getKey();
                        break;
                    }
                }
                mCallback.select(folder);
                break;
            default:
                onClick(dialog, which, true);
                break;
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
        final FolderRow row = (FolderRow) mAdapter.getItem(which);
        // Clear any other checked items.
        mCheckedState.clear();
        isChecked = true;
        mCheckedState.put(row.getFolder(), isChecked);
        mDialog.getListView().setItemChecked(which, false);
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        mCallback.cancel();
    }
}
