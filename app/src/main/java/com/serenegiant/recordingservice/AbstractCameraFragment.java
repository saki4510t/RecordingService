package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
 *
 * File name: AbstractCameraFragment.java
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
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.serenegiant.utils.FileUtils;

import java.io.IOException;

/**
 * 内蔵カメラへアクセスして表示するための基本クラス
 * 録画の開始/停止の実際の処理以外を実装
 */
public abstract class AbstractCameraFragment extends BaseFragment {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = AbstractCameraFragment.class.getSimpleName();

	/**
	 * video resolution
	 */
	protected static final int VIDEO_WIDTH = 1280, VIDEO_HEIGHT = 720;
	/**
	 * Video frame rate per second
	 */
	protected static final int FPS = 30;
	/**
	 * bit per pixel for video recording
	 */
	protected static final float BPP = 0.25f;
	/**
	 * Audio recording settings
	 */
	protected static final int SAMPLE_RATE = 44100, CHANNEL_COUNT = 1;
	protected static final String APP_DIR_NAME = "RecordingService";
	/** access code for secondary storage etc. */
	protected static final int REQUEST_ACCESS_SD = 12345;
	
	/**
	 * for camera preview display
	 */
	protected CameraGLView mCameraView;
	/**
	 * for scale mode display
	 */
	private TextView mScaleModeView;
	/**
	 * button for start/stop recording
	 */
	private ImageButton mRecordButton;

	public AbstractCameraFragment() {
		super();
		// デフォルトコンストラクタが必要
	}
	
	@Override
	public void onAttach(@NonNull final Context context) {
		super.onAttach(context);
		requireActivity().setTitle(this.getClass().getSimpleName());
		FileUtils.DIR_NAME = APP_DIR_NAME;
	}
	
	@Override
	public View onCreateView(@NonNull final LayoutInflater inflater,
		final ViewGroup container, final Bundle savedInstanceState) {

		final LayoutInflater custom_inflater
			= getThemedLayoutInflater(inflater, R.style.AppTheme_Camera);
		return custom_inflater.inflate(R.layout.fragment_camera, container, false);
	}

	@Override
	public void onViewCreated(
		@NonNull final View view,
		@Nullable final Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		mCameraView = view.findViewById(R.id.cameraView);
		mCameraView.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT);
		mCameraView.setOnClickListener(mOnClickListener);
		mScaleModeView = view.findViewById(R.id.scalemode_textview);
		updateScaleModeText();
		mRecordButton = view.findViewById(R.id.record_button);
		mRecordButton.setOnClickListener(mOnClickListener);
	}

	@Override
	public void internalOnResume() {
		super.internalOnResume();
		if (DEBUG) Log.v(TAG, "internalOnResume:");
		mCameraView.onResume();
		mCameraView.addListener(mOnFrameAvailableListener);
		if (!hasPermission()) {
			popBackStack();
		}
	}

	@Override
	public void internalOnPause() {
		if (DEBUG) Log.v(TAG, "internalOnPause:");
		stopRecording();
		mCameraView.removeListener(mOnFrameAvailableListener);
		mCameraView.onPause();
		super.internalOnPause();
	}

//================================================================================
	/**
	 * method when touch record button
	 */
	private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
		@Override
		public void onClick(final View view) {
			final int id = view.getId();
			if (id == R.id.cameraView) {
				final int scale_mode = (mCameraView.getScaleMode() + 1) % 4;
				mCameraView.setScaleMode(scale_mode);
				updateScaleModeText();
			} else if (id == R.id.record_button) {
				if (!isRecording()) {
					startRecording();
				} else {
					stopRecording();
				}
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

	protected abstract boolean isRecording();
	
	/**
	 * start recording
	 * This is a sample project and call this on UI thread to avoid being complicated
	 * but basically this should be called on private thread because prepareing
	 * of encoder is heavy work
	 */
	protected void startRecording() {
		if (DEBUG) Log.v(TAG, "startRecording:");
		mRecordButton.setColorFilter(0xffff0000);	// turn red
		try {
			// FIXME 未実装 ちゃんとパーミッションのチェック＆要求をしないとだめ
			internalStartRecording();
		} catch (final Exception e) {
			mRecordButton.setColorFilter(0);
			Log.e(TAG, "startCapture:", e);
		}
	}

	protected abstract void internalStartRecording() throws IOException;
	
	/**
	 * request stop recording
	 */
	protected void stopRecording() {
		if (DEBUG) Log.v(TAG, "stopRecording:");
		mRecordButton.setColorFilter(0);	// return to default color
		internalStopRecording();
	}

	protected abstract void internalStopRecording();

	protected void clearRecordingState() {
		mRecordButton.setColorFilter(0);
	}
		
	private final CameraGLView.OnFrameAvailableListener
		mOnFrameAvailableListener = new CameraGLView.OnFrameAvailableListener() {
		@Override
		public void onFrameAvailable() {
			AbstractCameraFragment.this.onFrameAvailable();
		}
	};
	
	protected abstract void onFrameAvailable();
}
