package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2017 saki t_saki@serenegiant.com
 *
 * File name: CameraFragment.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
*/

import android.app.Fragment;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

public class CameraFragment extends Fragment {
	private static final boolean DEBUG = false;	// TODO set false on release
	private static final String TAG = "CameraFragment";

	/**
	 * for camera preview display
	 */
	private CameraGLView mCameraView;
	/**
	 * for scale mode display
	 */
	private TextView mScaleModeView;
	/**
	 * button for start/stop recording
	 */
	private ImageButton mRecordButton;

	public CameraFragment() {
		// need default constructor
	}

	@Override
	public View onCreateView(final LayoutInflater inflater,
		final ViewGroup container, final Bundle savedInstanceState) {

		final View rootView = inflater.inflate(R.layout.fragment_main, container, false);
		mCameraView = rootView.findViewById(R.id.cameraView);
		mCameraView.setVideoSize(1280, 720);
		mCameraView.setOnClickListener(mOnClickListener);
		mScaleModeView = rootView.findViewById(R.id.scalemode_textview);
		updateScaleModeText();
		mRecordButton = rootView.findViewById(R.id.record_button);
		mRecordButton.setOnClickListener(mOnClickListener);
		return rootView;
	}

	@Override
	public void onResume() {
		super.onResume();
		if (DEBUG) Log.v(TAG, "onResume:");
		mCameraView.onResume();
	}

	@Override
	public void onPause() {
		if (DEBUG) Log.v(TAG, "onPause:");
		stopRecording();
		mCameraView.onPause();
		super.onPause();
	}

	/**
	 * method when touch record button
	 */
	private final OnClickListener mOnClickListener = new OnClickListener() {
		@Override
		public void onClick(final View view) {
			switch (view.getId()) {
			case R.id.cameraView:
				final int scale_mode = (mCameraView.getScaleMode() + 1) % 4;
				mCameraView.setScaleMode(scale_mode);
				updateScaleModeText();
				break;
			case R.id.record_button:
				if (!isRecording()) {
					startRecording();
				} else {
					stopRecording();
				}
				break;
			}
		}
	};

	private void updateScaleModeText() {
		final int scale_mode = mCameraView.getScaleMode();
		mScaleModeView.setText(
			scale_mode == 0 ? "scale to fit"
			: (scale_mode == 1 ? "keep aspect(viewport)"
			: (scale_mode == 2 ? "keep aspect(matrix)"
			: (scale_mode == 3 ? "keep aspect(crop center)" : ""))));
	}

	private boolean isRecording() {
		return false;	// FIXME 未実装
	}
	
	/**
	 * start recording
	 * This is a sample project and call this on UI thread to avoid being complicated
	 * but basically this should be called on private thread because prepareing
	 * of encoder is heavy work
	 */
	private void startRecording() {
		if (DEBUG) Log.v(TAG, "startRecording:");
		mRecordButton.setColorFilter(0xffff0000);	// turn red
		try {
			// FIXME 未実装
		} catch (final Exception e) {
			mRecordButton.setColorFilter(0);
			Log.e(TAG, "startCapture:", e);
		}
	}

	/**
	 * request stop recording
	 */
	private void stopRecording() {
		if (DEBUG) Log.v(TAG, "stopRecording:");
		mRecordButton.setColorFilter(0);	// return to default color
		// FIXME 未実装
	}

}
