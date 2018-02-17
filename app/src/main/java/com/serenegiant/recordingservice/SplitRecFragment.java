package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2018 saki t_saki@serenegiant.com
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

import android.content.Context;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.provider.DocumentFile;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.media.Encoder;
import com.serenegiant.media.EncoderListener;
import com.serenegiant.media.IRecorder;
import com.serenegiant.media.IVideoEncoder;
import com.serenegiant.media.Recorder;
import com.serenegiant.media.SplitMediaAVRecorder;
import com.serenegiant.media.SurfaceEncoder;
import com.serenegiant.utils.FileUtils;
import com.serenegiant.utils.PermissionCheck;
import com.serenegiant.utils.SDUtils;

import java.io.File;
import java.io.IOException;

/**
 * MP4自動分割出力クラスのテスト用
 */
public class SplitRecFragment extends AbstractCameraFragment {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = SplitRecFragment.class.getSimpleName();

	/** access code for secondary storage etc. */
	private static final int REQUEST_ACCESS_SD = 12345;

	private static final long MAX_FILE_SIZE = 4000000000L;

	private final Object mSync = new Object();

	public SplitRecFragment() {
		super();
		// デフォルトコンストラクタが必要
		if (DEBUG) Log.v(TAG, "コンストラクタ:");
	}
	
	@Override
	protected boolean isRecording() {
		return mRecorder != null;
	}
	
	@Override
	protected void internalStartRecording() throws IOException {
		if (DEBUG) Log.v(TAG, "internalStartRecording:");
		final DocumentFile recRoot = getRecordingRoot(getActivity());
		if (recRoot != null) {
			startEncoder(recRoot, 0, 0, false);
		} else {
			throw new IOException("could not access storage");
		}
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
		
	}
	
	private Surface mEncoderSurface;
	private IRecorder mRecorder;
	private volatile IVideoEncoder mVideoEncoder;

	public static DocumentFile getRecordingRoot(@NonNull final Context context) {
		if (DEBUG) Log.v(TAG, "getRecordingRoot:");
		DocumentFile root = null;
		if (SDUtils.hasStorageAccess(context, REQUEST_ACCESS_SD)) {
			try {
				root = SDUtils.getStorage(context, REQUEST_ACCESS_SD);
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
			SDUtils.releaseStorageAccessPermission(context, REQUEST_ACCESS_SD);
		}
		if ((root == null) && PermissionCheck.hasWriteExternalStorage(context)) {
			// fallback to primary external storage if app has permission
			final File captureDir
				= FileUtils.getCaptureDir(context, Environment.DIRECTORY_MOVIES, 0);
			if ((captureDir != null) && captureDir.canWrite()) {
				root = DocumentFile.fromFile(captureDir);
			}
		}
		if (DEBUG) Log.v(TAG, "getRecordingRoot:finished" + root);
		return root;
	}

	/**
	 * create IRecorder instance for recording and prepare, start
	 * @param basePath
	 * @param audio_source
	 * @param audio_channels
	 * @param align16
	 */
	private void startEncoder(@NonNull final DocumentFile basePath,
		final int audio_source, final int audio_channels, final boolean align16)
			throws IOException {

		IRecorder recorder = mRecorder;
		if (DEBUG) Log.d(TAG, "startEncoder:recorder=" + recorder);

		if (recorder == null) {
			try {
				recorder = createRecorder(basePath,
					audio_source, audio_channels, align16, REQUEST_ACCESS_SD);
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
	}

	private void stopEncoder() {
		if (DEBUG) Log.v(TAG, "stopEncoder:");
		if (mEncoderSurface != null) {
			removeSurface(mEncoderSurface);
			mEncoderSurface = null;
		}
		mVideoEncoder = null;
	}

	/**
	 * create recorder and related encoder
	 * @param basePath
	 * @param audio_source
	 * @param audio_channels
	 * @param align16
	 * @return
	 * @throws IOException
	 */
	protected Recorder createRecorder(@NonNull final DocumentFile basePath,
		final int audio_source, final int audio_channels,
		final boolean align16, final int saveTreeId) throws IOException {

		if (DEBUG) Log.v(TAG, "createRecorder:basePath=" + basePath);
		final SplitMediaAVRecorder recorder;
		recorder = new SplitMediaAVRecorder(getActivity(),
		mRecorderCallback, basePath, FileUtils.getDateTimeString(), MAX_FILE_SIZE);
		// create encoder for video recording
		if (DEBUG) Log.v(TAG, "create SurfaceEncoder");
		mVideoEncoder = new SurfaceEncoder(recorder, mEncoderListener); // API>=18
		mVideoEncoder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT);
		((SurfaceEncoder)mVideoEncoder).setVideoConfig(-1, 30, 10);
		return recorder;
	}

	protected void addSurface(final Surface surface) {
		final int id = surface != null ? surface.hashCode() : 0;
		if (DEBUG) Log.d(TAG, "addSurface:id=" + id);
		synchronized (mSync) {
			if (isReleased()) return;
			if (mCameraView != null) {
				mCameraView.addSurface(id, surface, false);
			}
		}
		if (DEBUG) Log.v(TAG, "removeSurface:finished");
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
		}
		
		@Override
		public void onStarted(final IRecorder recorder) {
			if (DEBUG) Log.v(TAG, "mRecorderCallback#onStarted:" + recorder);
		}
		
		@Override
		public void onStopped(final IRecorder recorder) {
			if (DEBUG) Log.v(TAG, "mRecorderCallback#onStopped:" + recorder);
			if ((recorder instanceof SplitMediaAVRecorder)
				&& ((SplitMediaAVRecorder)recorder).check()) {
				
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
	
}
