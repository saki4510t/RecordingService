package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
 *
 * File name: SplitRecFragment.java
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.media.AbstractAudioEncoder;
import com.serenegiant.media.AudioSampler;
import com.serenegiant.media.AudioSamplerEncoder;
import com.serenegiant.media.Encoder;
import com.serenegiant.media.EncoderListener;
import com.serenegiant.media.IAudioEncoder;
import com.serenegiant.media.IAudioSampler;
import com.serenegiant.media.IRecorder;
import com.serenegiant.media.IVideoEncoder;
import com.serenegiant.media.MediaAVSplitRecorderV2;
import com.serenegiant.media.Recorder;
import com.serenegiant.media.SurfaceEncoder;
import com.serenegiant.system.BuildCheck;
import com.serenegiant.system.PermissionCheck;
import com.serenegiant.system.SAFUtils;
import com.serenegiant.utils.FileUtils;

import java.io.File;
import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

/**
 * MP4自動分割出力クラスのテスト用
 * XXX これは録画サービスを使わずにUI側で直接録画する
 */
public class SplitRecFragment2 extends AbstractCameraFragment {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = SplitRecFragment2.class.getSimpleName();

	private static final long MAX_FILE_SIZE = 1024 * 1024 * 10; // 10MB // 4000000000L;

	@NonNull
	private final Object mSync = new Object();

	public SplitRecFragment2() {
		super();
		// need default constructor
	}
	
	@Override
	protected boolean isRecording() {
		return mRecorder != null;
	}
	
	@Override
	protected void internalStartRecording() throws IOException {
		if (DEBUG) Log.v(TAG, "internalStartRecording:");
		startEncoder( 2, CHANNEL_COUNT, false);
		if (DEBUG) Log.v(TAG, "internalStartRecording:finished");
	}
	
	@Override
	protected void internalStopRecording() {
		if (DEBUG) Log.v(TAG, "internalStopRecording:");
		stopEncoder();
		if (mRecorder != null) {
			mRecorder.stopRecording();
			// you should not wait and should not clear mRecorder here
		}
		if (DEBUG) Log.v(TAG, "internalStopRecording:finished");
	}
	
	@Override
	protected void onFrameAvailable() {
		if (mRecorder != null) {
			mRecorder.frameAvailableSoon();
		}
	}
	
	private Surface mEncoderSurface;
	private IAudioSampler mAudioSampler;
	private IRecorder mRecorder;
	private volatile IVideoEncoder mVideoEncoder;
	private volatile IAudioEncoder mAudioEncoder;

	/**
	 * create IRecorder instance for recording and prepare, start
	 * @param audio_source
	 * @param audio_channels
	 * @param align16
	 */
	private void startEncoder(
		final int audio_source, final int audio_channels, final boolean align16)
			throws IOException {

		IRecorder recorder = mRecorder;
		if (DEBUG) Log.d(TAG, "startEncoder:recorder=" + recorder);

		if (recorder == null) {
			try {
				recorder = createRecorder(audio_source, audio_channels, align16);
				recorder.prepare();
				recorder.startRecording();
				mRecorder = recorder;
			} catch (final Exception e) {
				Log.w(TAG, "startEncoder:", e);
				stopEncoder();
				if (recorder != null) {
					recorder.stopRecording();
				}
				mRecorder = null;
				throw e;
			}
		}
		if (DEBUG) Log.v(TAG, "startEncoder:finished");
	}

	private void stopEncoder() {
		if (DEBUG) Log.v(TAG, "stopEncoder:");
		if (mEncoderSurface != null) {
			removeSurface(mEncoderSurface);
			mEncoderSurface = null;
		}
		mVideoEncoder = null;
		mAudioEncoder = null;
		if (mAudioSampler != null) {
			mAudioSampler.release();
			mAudioSampler = null;
		}
		if (DEBUG) Log.v(TAG, "stopEncoder:finished");
	}

	/**
	 * create recorder and related encoder
	 * @param audio_source
	 * @param audio_channels
	 * @param align16
	 * @return
	 * @throws IOException
	 */
	protected Recorder createRecorder(final int audio_source, final int audio_channels,
		final boolean align16) throws IOException {

		if (DEBUG) Log.v(TAG, "createRecorder:");
		final MediaAVSplitRecorderV2 recorder
			= new MediaAVSplitRecorderV2(getActivity(),
				mRecorderCallback, null, null, null, null, MAX_FILE_SIZE);
		// create encoder for video recording
		if (DEBUG) Log.v(TAG, "create SurfaceEncoder");
		mVideoEncoder = new SurfaceEncoder(recorder, mEncoderListener); // API>=18
		mVideoEncoder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT);
		((SurfaceEncoder)mVideoEncoder).setVideoConfig(-1, 30, 10);
		if (audio_source >= 0) {
			mAudioSampler = new AudioSampler(audio_source,
				audio_channels, SAMPLE_RATE,
				AbstractAudioEncoder.SAMPLES_PER_FRAME,
				AbstractAudioEncoder.FRAMES_PER_BUFFER);
			mAudioSampler.start();
			mAudioEncoder = new AudioSamplerEncoder(recorder, mEncoderListener, 2, mAudioSampler);
		}
		if (DEBUG) Log.v(TAG, "createRecorder:finished");
		return recorder;
	}

	protected void addSurface(final Surface surface) {
		final int id = surface != null ? surface.hashCode() : 0;
		if (DEBUG) Log.d(TAG, "addSurface:id=" + id);
		synchronized (mSync) {
			if (isReleased()) {
				Log.d(TAG, "already released!");
				return;
			}
			if (mCameraView != null) {
				mCameraView.addSurface(id, surface, true);
			}
		}
		if (DEBUG) Log.v(TAG, "addSurface:finished");
	}
	
	/**
	 * request remove Surface
	 * @param surface // id usually use Surface#hashCode
	 */
	public void removeSurface(final Surface surface) {
		final int id = surface != null ? surface.hashCode() : 0;
		if (DEBUG) Log.d(TAG, "removeSurface:id=" + id);
		synchronized (mSync) {
			if (isReleased()) return;
			if (mCameraView != null) {
				mCameraView.removeSurface(id);
			}
		}
		if (DEBUG) Log.v(TAG, "removeSurface:finished");
	}

	private final IRecorder.RecorderCallback
		mRecorderCallback = new IRecorder.RecorderCallback() {
		@Override
		public void onPrepared(final IRecorder recorder) {
			if (DEBUG) Log.v(TAG, "mRecorderCallback#onPrepared:" + recorder
				+ ",mEncoderSurface=" + mEncoderSurface);
			final Encoder encoder = recorder.getVideoEncoder();
			try {
				if (encoder instanceof SurfaceEncoder) {
					if (mEncoderSurface == null) {
						final Surface surface = recorder.getInputSurface();
						if (surface != null) {
							if (DEBUG) Log.v(TAG, "use SurfaceEncoder");
							mEncoderSurface = surface;
							try {
								addSurface(surface);
							} catch (final Exception e) {
								mEncoderSurface = null;
								throw e;
							}
						}
					}
				} else if (encoder instanceof IVideoEncoder)  {
					mEncoderSurface = null;
					throw new RuntimeException("unknown video encoder " + encoder);
				}
			} catch (final Exception e) {
				if (DEBUG) Log.w(TAG, e);
				try {
					stopRecording();
				} catch (final Exception e1) {
					// unrecoverable exception
					Log.w(TAG, e1);
				}
			}
			if (DEBUG) Log.v(TAG, "mRecorderCallback#onPrepared:finished");
		}
		
		@Override
		public void onStarted(final IRecorder recorder) {
			if (DEBUG) Log.v(TAG, "mRecorderCallback#onStarted:" + recorder);
		}
		
		@Override
		public void onStopped(final IRecorder recorder) {
			if (DEBUG) Log.v(TAG, "mRecorderCallback#onStopped:" + recorder);
			if ((recorder instanceof MediaAVSplitRecorderV2)
				&& ((MediaAVSplitRecorderV2)recorder).check()) {
				
				Log.v(TAG, "File size exceed limit or Free space is too low");
			}
			stopEncoder();
			final IRecorder _recorder = mRecorder;
			mRecorder = null;
			try {
				queueEvent(new Runnable() {
					@Override
					public void run() {
						if (_recorder != null) {
							try {
								_recorder.release();
							} catch (final Exception e) {
								Log.w(TAG, e);
							}
						}
					}
				}, 1000);
			} catch (final IllegalStateException e) {
				// ignore, will be already released
				Log.w(TAG, e);
			}
			clearRecordingState();
			if (DEBUG) Log.v(TAG, "mRecorderCallback#onStopped:finished");
		}
		
		@Override
		public void onError(final Exception e) {
			Log.w(TAG, e);
			final IRecorder recorder = mRecorder;
			mRecorder = null;
			if ((recorder != null) && (recorder.isReady() || recorder.isStarted())) {
				recorder.stopRecording();
			}
		}
	};
	
	private final EncoderListener mEncoderListener = new EncoderListener() {
		@Override
		public void onStartEncode(final Encoder encoder, final Surface source,
			final int captureFormat, final boolean mayFail) {
			
			if (DEBUG) Log.v(TAG, "mEncoderListener#onStartEncode:" + encoder);
		}

		@Override
		public void onStopEncode(final Encoder encoder) {
			if (DEBUG) Log.v(TAG, "mEncoderListener#onStopEncode:" + encoder);
		}

		@Override
		public void onDestroy(final Encoder encoder) {
			if (DEBUG) Log.v(TAG, "mEncoderListener#onDestroy:"
				+ encoder + ",mRecorder=" + mRecorder);
			if (DEBUG) Log.v(TAG, "mEncoderListener#onDestroy:finished");
		}

		@Override
		public void onError(final Exception e) {
			Log.w(TAG, e);
			final IRecorder recorder = mRecorder;
			mRecorder = null;
			if ((recorder != null) && (recorder.isReady() || recorder.isStarted())) {
				recorder.stopRecording();
			}
		}
	};

	@SuppressLint("NewApi")
	private static DocumentFile getRecordingRoot(@NonNull final Context context) {
		if (DEBUG) Log.v(TAG, "getRecordingRoot:");
		DocumentFile root = null;
		if (BuildCheck.isAPI21() && SAFUtils.hasPermission(context, REQUEST_ACCESS_SD)) {
			try {
				root = SAFUtils.getDir(context, REQUEST_ACCESS_SD, null);
				if ((root != null) && root.exists() && root.canWrite()) {
					final DocumentFile appDir = root.findFile(APP_DIR_NAME);
					if (appDir == null) {
						// create app dir if it does not exist yet
						root = root.createDirectory(APP_DIR_NAME);	// "${document root}/Pupil Mobile"
					} else {
						root = appDir;
					}
				} else {
					root = null;
					Log.d(TAG, "path will be wrong, will already be removed,"
						+ (root != null ? root.getUri() : null));
				}
			} catch (final IOException | IllegalStateException e) {
				root = null;
				Log.d(TAG, "path is wrong, will already be removed.", e);
			}
		}
		if (root == null) {
			// remove permission to access secondary (external) storage,
			// because app can't access it and it will already be removed.
			SAFUtils.releasePersistableUriPermission(context, REQUEST_ACCESS_SD);
		}
		if ((root == null) && PermissionCheck.hasWriteExternalStorage(context)) {
			// fallback to primary external storage if app has permission
			final File captureDir
				= FileUtils.getCaptureDir(context, Environment.DIRECTORY_MOVIES);
			if ((captureDir != null) && captureDir.canWrite()) {
				root = DocumentFile.fromFile(captureDir);
			}
		}
		if (DEBUG) Log.v(TAG, "getRecordingRoot:finished," + root);
		return root;
	}

}
