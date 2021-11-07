package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2019 saki t_saki@serenegiant.com
 *
 * File name: TimeShiftRecFragment.java
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

import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.service.AbstractServiceRecorder;
import com.serenegiant.service.TimeShiftRecService;
import com.serenegiant.service.TimeShiftRecorder;
import com.serenegiant.utils.FileUtils;

import java.io.File;
import java.io.IOException;

public class TimeShiftRecFragment extends AbstractCameraFragment {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = TimeShiftRecFragment.class.getSimpleName();
	
	
	private TimeShiftRecorder mRecorder;

	public TimeShiftRecFragment() {
		super();
		// need default constructor
	}
	
	@Override
	protected boolean isRecording() {
		return mRecorder != null && mRecorder.isRecording();
	}
	
	@Override
	public void internalOnResume() {
		super.internalOnResume();
		if (DEBUG) Log.v(TAG, "internalOnResume:");
		queueEvent(() -> {
			if (mRecorder == null) {
				mRecorder = new TimeShiftRecorder(requireContext(),
					TimeShiftRecService.class, mCallback);
			}
		},100);
	}
	
	@Override
	public void internalOnPause() {
		if (DEBUG) Log.v(TAG, "internalOnPause:");
		releaseRecorder();
		super.internalOnPause();
	}
	
	@SuppressWarnings("ResultOfMethodCallIgnored")
	@Override
	protected void internalStartRecording() {
		if (DEBUG) Log.v(TAG, "internalStartRecording:");
		if ((mRecorder != null) && mRecorder.isTimeShift()) {
			try {
				final File dir = new File(
					Environment.getExternalStoragePublicDirectory(
						Environment.DIRECTORY_MOVIES), APP_DIR_NAME);
				dir.mkdirs();
				mRecorder.start(dir.toString(), FileUtils.getDateTimeString());
			} catch (final Exception e) {
				Log.w(TAG, e);
				stopRecording();	// 非同期で呼ばないとデッドロックするかも
			}
		}
	}
	
	@Override
	protected void internalStopRecording() {
		if (DEBUG) Log.v(TAG, "internalStopRecording:");
		if (mRecorder != null) {
			mRecorder.stop();
		}
	}
	
	@Override
	protected void onFrameAvailable() {
		if (mRecorder != null) {
			mRecorder.frameAvailableSoon();
		}
	}
	
	private void releaseRecorder() {
		if (DEBUG) Log.v(TAG, "releaseRecorder:");
		if (mRecorder != null) {
			mRecorder.release();
			mRecorder = null;
		}
	}

	private int mRecordingSurfaceId = 0;
	private final AbstractServiceRecorder.Callback mCallback
		= new AbstractServiceRecorder.Callback() {
		@Override
		public void onConnected() {
			if (DEBUG) Log.v(TAG, "onConnected:");
			if (mRecordingSurfaceId != 0) {
				mCameraView.removeSurface(mRecordingSurfaceId);
				mRecordingSurfaceId = 0;
			}
			if (mRecorder != null) {
				try {
					mRecorder.setVideoSettings(VIDEO_WIDTH, VIDEO_HEIGHT, 30, 0.25f);
					mRecorder.setAudioSettings(SAMPLE_RATE, CHANNEL_COUNT);
					mRecorder.prepare();
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
					releaseRecorder();
				}
			}
		}
		
		@Override
		public void onPrepared() {
			if (DEBUG) Log.v(TAG, "onPrepared:");
			if (mRecorder != null) {
				try {
					final Surface surface = mRecorder.getInputSurface();	// API>=18
					if (surface != null) {
						mRecordingSurfaceId = surface.hashCode();
						mCameraView.addSurface(mRecordingSurfaceId, surface, true);
					} else {
						Log.w(TAG, "surface is null");
						stopRecording();	// 非同期で呼ばないとデッドロックするかも
						releaseRecorder();
					}
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
					releaseRecorder();
				}
			}
		}
		
		@Override
		public void onReady() {
			if (DEBUG) Log.v(TAG, "onReady:" + mRecorder);
			queueEvent(() -> {
				try {
					// バッファリング開始
					mRecorder.startTimeShift();
				} catch (final IOException e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
					releaseRecorder();
				}
			}, 10);
		}
		
		@Override
		public void onDisconnected() {
			if (DEBUG) Log.v(TAG, "onDisconnected:");
			if (mRecordingSurfaceId != 0) {
				mCameraView.removeSurface(mRecordingSurfaceId);
				mRecordingSurfaceId = 0;
			}
			stopRecording();
			releaseRecorder();
		}
	};

}
