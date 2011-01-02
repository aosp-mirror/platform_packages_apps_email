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

package com.android.email.activity.setup;

import com.android.email.AccountBackupRestore;
import com.android.email.Email;
import com.android.email.ExchangeUtils;
import com.android.email.R;
import com.android.email.mail.Store;
import com.android.email.mail.store.ExchangeStore;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Button;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class AccountSetupColor extends Activity implements OnClickListener, SeekBar.OnSeekBarChangeListener {
	private static final String EXTRA_ACCOUNT = "account";
	private static final String EXTRA_COLOR = "color";
	private static final String EXTRA_EAS_FLOW = "easFlow";
	
	private static final String TAG = "AccountSetupColor";
	
	private static final int r = 0;
	private static final int g = 1;
	private static final int b = 2;
	private static final int RGB_COMP_COUNT = 3;
	
	private static String COMPONENT_NAMES[] = 	{ "RED", "GREEN", "BLUE"};
	
	final int RGB_SEEKBAR_MAX = 255;
	
	private static final int SLIDER_IDS[] = {
		R.id.color_R,
		R.id.color_G,
		R.id.color_B
	};
	
	private static final int RGB_VAL_IDS[] = {
		R.id.color_R_val,
		R.id.color_G_val,
		R.id.color_B_val
	};
	
	private View mSampleChip;			// sample_chip
	private View mColorSample; 			// color_sample
	private TextView mHtmlCode;			// color_html_code
	private TextView mRGBValue[];		// color_R_val
	private SeekBar mRGBSlider [];		// color_R
	
	private Button mNext;
	
	private EmailContent.Account mAccount;
	
	private int mCurrentColor;
	
	private boolean mEasFlowMode;
	
	private boolean mEditMode;
	
	public static void actionSetColor(Activity fromActivity, EmailContent.Account account,
            boolean easFlowMode) {
        Intent i = new Intent(fromActivity, AccountSetupColor.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_EAS_FLOW, easFlowMode);
        fromActivity.startActivity(i);
    }
	
	public static void actionEditColorSettings(Activity fromActivity, EmailContent.Account account)
    {
		Intent i = new Intent(fromActivity, AccountSetupColor.class);
		i.setAction(Intent.ACTION_EDIT);
		i.putExtra(EXTRA_ACCOUNT, account);
		fromActivity.startActivity(i);
	}
	
	private void initComponents ()
	{
		setContentView(R.layout.account_setup_color);
		
		mSampleChip = (View)findViewById(R.id.sample_chip);
		mColorSample = (View)findViewById(R.id.color_sample);
		mHtmlCode = (TextView)findViewById(R.id.color_html_code);
		
		mRGBValue = new TextView[RGB_COMP_COUNT];
		mRGBSlider = new SeekBar[RGB_COMP_COUNT];
		
		for (int i = r; i < RGB_COMP_COUNT; i++)
		{
			mRGBValue[i] = (TextView)findViewById(RGB_VAL_IDS[i]);
			mRGBSlider[i] = (SeekBar)findViewById(SLIDER_IDS[i]);
			initSeeKbar(mRGBSlider[i]);
		}
		
		mNext = (Button)findViewById(R.id.next);
		mNext.setOnClickListener(this);
	}
	
	private void initSeeKbar (SeekBar item)
	{
		item.setMax(RGB_SEEKBAR_MAX);
		item.setKeyProgressIncrement(1);
		
		if (!mEditMode)
		{
			item.setProgress((int)(Math.random() * RGB_SEEKBAR_MAX));
			item.invalidate();
		}
		
		item.setOnSeekBarChangeListener(this);
	}
	
	private void updateColor ()
	{
		int rgb[] = new int [RGB_COMP_COUNT];
		
		for (int i = r; i < RGB_COMP_COUNT; i++)
		{
			rgb[i] = mRGBSlider[i].getProgress();
			mRGBValue[i].setText(rgb[i] + "");
		}
		
		mCurrentColor = Color.argb(RGB_SEEKBAR_MAX, rgb[r], rgb[g], rgb[b]);
		
		mSampleChip.setBackgroundColor(mCurrentColor);
		mColorSample.setBackgroundColor(mCurrentColor);
		mHtmlCode.setTextColor(mCurrentColor);
		mHtmlCode.setText("0x" + Integer.toHexString(mCurrentColor).toUpperCase());
	}
	
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        initComponents ();
        mAccount = (EmailContent.Account) getIntent().getParcelableExtra(EXTRA_ACCOUNT);        
        mEditMode = Intent.ACTION_EDIT.equals(getIntent().getAction());
              
        if (mEditMode) {
        	mAccount = Account.restoreAccountWithId(this, mAccount.mId);
        	mCurrentColor =  mAccount.getAccountColor();
        }
        else
        {
            // Setup any additional items to support EAS & EAS flow mode
            mEasFlowMode = getIntent().getBooleanExtra(EXTRA_EAS_FLOW, false);
        }
        
        int rgb[] = {Color.red(mCurrentColor), Color.green(mCurrentColor), Color.blue(mCurrentColor)};

        for (int i = r; i < RGB_COMP_COUNT; i++)
        {
        	mRGBSlider[i].setProgress(rgb[i]);
        }
        
        updateColor ();
	}

	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) 
	{
		updateColor ();
	}

	public void onStartTrackingTouch(SeekBar seekBar) 
	{
		// do nothing
	}

	public void onStopTrackingTouch(SeekBar seekBar) 
	{	
		// do nothing
	}
	
	private void onDone() {
		updateColor ();
		mAccount.setAccountColor(mCurrentColor);
        
        if (mEditMode)
        {
        	if (mAccount.isSaved()) {
                mAccount.update(this, mAccount.toContentValues());
            } else {
                mAccount.save(this);
            }
            // Update the backup (side copy) of the accounts
            AccountBackupRestore.backupAccounts(this);
            finish();
        }
        else {
        	// Clear the incomplete flag now
            mAccount.mFlags &= ~Account.FLAGS_INCOMPLETE;
            AccountSettingsUtils.commitSettings(this, mAccount);
        	Email.setServicesEnabled(this);
            AccountSetupNames.actionSetNames(this, mAccount.mId, mEasFlowMode);
            // Start up SyncManager (if it isn't already running)
            ExchangeUtils.startExchangeService(this);
        }
	}
	
	public void onClick(View v) {
		switch (v.getId()) {
		case R.id.next:
			onDone();
			break;
		}
		
	}
	
}