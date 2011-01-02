package com.android.email.activity.setup;

import com.android.email.R;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ColorPreference extends Preference {
	private static final int DEFAULT_COLOR = 0xffffffff;
	
	private int mColor;
	private View mColorChip;
	private TextView mSummary;
	
	
	public ColorPreference(Context context) {
		  super(context);
		  mColor = DEFAULT_COLOR;
 	}
		 
	public ColorPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
		mColor = DEFAULT_COLOR;
	}
	 
	public ColorPreference(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		mColor = DEFAULT_COLOR;
	}
	   
	@Override
	protected View onCreateView(ViewGroup parent){
		Context context = getContext();
		LayoutInflater li = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		
		View v = li.inflate(R.layout.color_preference, null);
		
		mColorChip = v.findViewById(R.id.color_prefs_chip);
		TextView title = (TextView)v.findViewById(R.id.color_prefs_title);
		mSummary = (TextView)v.findViewById(R.id.color_prefs_summary);
		
		setChipColor(mColor);
		title.setText(getTitle());
		setSummary(getSummary());
		
		v.setId(android.R.id.widget_frame);
		return v;
	}
	
	public void setChipColor (int color)
	{
		mColor = color;
		notifyChanged();
	}

	@Override
	public void setSummary(CharSequence summary) {
		super.setSummary(summary);
		notifyChanged();
	}

	@Override
	protected void notifyChanged() {
		super.notifyChanged();
		persistInt(mColor);
		
		if (null != mSummary) mSummary.setText(getSummary());
		if (null != mColorChip) mColorChip.setBackgroundColor(mColor);
	}

	@Override
	protected void onSetInitialValue(boolean restorePersistedValue,
			Object defaultValue) {
		mColor = restorePersistedValue ? getPersistedInt(mColor): (Integer)defaultValue; 
		
		if (shouldPersist())
			persistInt(mColor);
		
		notifyChanged();
	}

	@Override 
	protected Object onGetDefaultValue(TypedArray ta,int index){
		int val = ta.getInt(index,DEFAULT_COLOR);
		notifyChanged();
		return val; 
	}
}
