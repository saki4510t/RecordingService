package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
 *
 * File name: SplitRecFragment3.java
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

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.service.AbstractServiceRecorder;
import com.serenegiant.service.SplitRecorderService;
import com.serenegiant.service.SplitServiceRecorder;

import java.io.IOException;

/**
 * MP4自動分割出力クラスのテスト用
 * SplitServiceRecorderを使いサービス上で録画を実行する
 */
public class SplitRecFragment3 extends AbstractCameraFragment {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = SplitRecFragment3.class.getSimpleName();

	private SplitServiceRecorder mRecorder;

	public SplitRecFragment3() {
		super();
		// need default constructor
	}

	@Override
	protected boolean isRecording() {
		return mRecorder != null;
	}

	@Override
	protected void internalStartRecording() throws IOException {
		if (DEBUG) Log.v(TAG, "internalStartRecording:mRecorder=" + mRecorder);
		if (mRecorder == null) {
			if (DEBUG) Log.v(TAG, "internalStartRecording:get SplitServiceRecorder");
			mRecorder = SplitServiceRecorder.newInstance(requireContext(),
				SplitRecorderService.class, mCallback);
		} else {
			Log.w(TAG, "internalStartRecording:recorder is not null, already start recording?");
		}
	}

	@Override
	protected void internalStopRecording() {
		if (DEBUG) Log.v(TAG, "internalStopRecording:mRecorder=" + mRecorder);
		if (mRecorder != null) {
			mRecorder.release();
			mRecorder = null;
		}
	}

	@Override
	protected void onFrameAvailable() {
		if (mRecorder != null) {
			mRecorder.frameAvailableSoon();
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
					mRecorder.setVideoSettings(VIDEO_WIDTH, VIDEO_HEIGHT, FPS, BPP);
					mRecorder.setAudioSettings(SAMPLE_RATE, CHANNEL_COUNT);
					mRecorder.prepare();
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
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
					}
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
				}
			}
		}

		@Override
		public void onReady() {
			if (DEBUG) Log.v(TAG, "onReady:");
			if (mRecorder != null) {
				final Context context = requireContext();
				try {
					mRecorder.start(null);
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
				}
			}
		}

		@Override
		public void onDisconnected() {
			if (DEBUG) Log.v(TAG, "onDisconnected:");
			if (mRecordingSurfaceId != 0) {
				mCameraView.removeSurface(mRecordingSurfaceId);
				mRecordingSurfaceId = 0;
			}
			stopRecording();
		}
	};
}
