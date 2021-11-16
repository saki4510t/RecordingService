package com.serenegiant.service;
/*
 *
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
 *
 * File name: PostMuxRecFragment.java
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
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.serenegiant.librecservice.R;
import com.serenegiant.media.IAudioSampler;
import com.serenegiant.media.IMuxer;
import com.serenegiant.media.MediaMuxerWrapper;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.mediastore.MediaStoreOutputStream;
import com.serenegiant.system.BuildCheck;
import com.serenegiant.utils.UriHelper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

/**
 * タイムラプス録画機能をサービス側で実行するサービス
 */
public class TimelapseRecService extends AbstractRecorderService {
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = TimelapseRecService.class.getSimpleName();

	/**
	 * MediaStoreOutputStreamを使って出力するかどうか(Android8以降のみ有効)
	 */
	private static final boolean USE_MEDIASTORE_OUTPUT_STREAM = false;
	private static final long DEFAULT_FRAME_INTERVALS_US = 1000000L / 30;

	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public TimelapseRecService getService() {
			return TimelapseRecService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();
	private String mOutputPath;
	private IMuxer mMuxer;
	private int mVideoTrackIx = -1;
	private int mAudioTrackIx = -1;
	/**
	 * フレームインターバル(30fps)
	 */
	private long mFrameIntervalsUs = DEFAULT_FRAME_INTERVALS_US;

	@Override
	protected IBinder getBinder() {
		return mBinder;
	}

	@NonNull
	@Override
	protected String getTitle() {
		return getString(R.string.service_simple);
	}

	@Override
	void setVideoSettings(final int width, final int height,
		final int frameRate, final float bpp) throws IllegalStateException {
		super.setVideoSettings(width, height, frameRate, bpp);
		if (frameRate > 0) {
			mFrameIntervalsUs = 1000000L / frameRate;
		}
	}

	@Override
	void setAudioSampler(@NonNull final IAudioSampler sampler)
		throws IllegalStateException {
		throw new UnsupportedOperationException("TimelapseRecService does not support audio recording");
	}

	@Override
	void setAudioSettings(final int sampleRate, final int channelCount) {
		throw new UnsupportedOperationException("TimelapseRecService does not support audio recording");
	}

	/**
	 * 受け取ったフレーム数
	 */
	private long mFrameCounts;

	@Override
	protected long getInputPTSUs() {
		if (DEBUG && ((mFrameCounts % 100) == 0)) Log.v(TAG, "getInputPTSUs:" + mFrameCounts);
		if (mFrameIntervalsUs <= 0) {
			mFrameIntervalsUs = DEFAULT_FRAME_INTERVALS_US;
		}
		// リアルタイム録画時間の代わりに受け取ったフレーム数に応じてPTSをセットする
		return (mFrameCounts++) * mFrameIntervalsUs;
	}

	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param output 出力ファイル
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	@SuppressLint("NewApi")
	@Override
	protected void internalStart(
		@Nullable final DocumentFile output,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {

		if (DEBUG) Log.v(TAG, "internalStart:");
		if (output == null) {
			throw new IOException("output is null");
		}
		mFrameCounts = 0;
		IMuxer muxer = null;
		if (BuildCheck.isAPI29()) {
			// API29以上は対象範囲別ストレージなのでMediaStoreOutputStreamを使って出力終了時にIS_PENDINGの更新を自動でする
			if (DEBUG) Log.v(TAG, "internalStart:create MediaMuxerWrapper using MediaStoreOutputStream");
			muxer = new MediaMuxerWrapper(
				new MediaStoreOutputStream(this, output),
				MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		} else if (BuildCheck.isAPI26()) {
			if (USE_MEDIASTORE_OUTPUT_STREAM) {
				if (DEBUG) Log.v(TAG, "internalStart:create MediaMuxerWrapper using MediaStoreOutputStream");
				muxer = new MediaMuxerWrapper(
					new MediaStoreOutputStream(this, output),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			} else {
				if (DEBUG) Log.v(TAG, "internalStart:create MediaMuxerWrapper using ContentResolver");
				muxer = new MediaMuxerWrapper(getContentResolver()
					.openFileDescriptor(output.getUri(), "rw").getFileDescriptor(),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			}
		} else {
			final String path = UriHelper.getPath(this, output.getUri());
			final File f = new File(UriHelper.getPath(this, output.getUri()));
			if (/*!f.exists() &&*/ f.canWrite()) {
				// 書き込めるファイルパスを取得できればそれを使う
				muxer = new MediaMuxerWrapper(path,
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			} else {
				Log.w(TAG, "cant't write to the file, try to use VideoMuxer instead");
			}
		}
		if (muxer == null) {
			throw new IllegalArgumentException();
		}
		mMuxer = muxer;
		mVideoTrackIx = videoFormat != null ? muxer.addTrack(videoFormat) : -1;
		mAudioTrackIx = audioFormat != null ? muxer.addTrack(audioFormat) : -1;
		mMuxer.start();
		synchronized (mSync) {
			mSync.notifyAll();
		}
	}

	@Override
	protected void internalStop() {
		if (DEBUG) Log.v(TAG, "internalStop:");
		if (mMuxer != null) {
			final IMuxer muxer = mMuxer;
			mMuxer = null;
			try {
				muxer.stop();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
			try {
				muxer.release();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
		}
		if (getState() == STATE_RECORDING) {
			setState(STATE_INITIALIZED);
			if (!TextUtils.isEmpty(mOutputPath)) {
				final String path = mOutputPath;
				mOutputPath = null;
				scanFile(path);
			}
		}
	}

	@Override
	protected void onWriteSampleData(
		@NonNull final MediaReaper reaper,
		@NonNull final ByteBuffer buffer,
		@NonNull final MediaCodec.BufferInfo info,
		final long ptsUs) {

//		if (DEBUG) Log.v(TAG, "onWriteSampleData:");
		IMuxer muxer;
		synchronized (mSync) {
			if (mMuxer == null) {
				for (int i = 0; isRecording() && (i < 100); i++) {
					if (mMuxer == null) {
						try {
							mSync.wait(10);
						} catch (final InterruptedException e) {
							break;
						}
					} else {
						break;
					}
				}
			}
			muxer = mMuxer;
		}
		if (muxer != null) {
			// ptsUs(#getInputPTSUsの返り値)で上書きする
			info.presentationTimeUs = ptsUs;
			switch (reaper.reaperType()) {
			case MediaReaper.REAPER_VIDEO:
				muxer.writeSampleData(mVideoTrackIx, buffer, info);
				break;
			case MediaReaper.REAPER_AUDIO:
				muxer.writeSampleData(mAudioTrackIx, buffer, info);
				break;
			default:
				if (DEBUG) Log.v(TAG, "onWriteSampleData:unexpected reaper type");
				break;
			}
		} else {
			if (DEBUG) Log.v(TAG, "onWriteSampleData:muxer is not set yet, " +
				"state=" + getState() + ",reaperType=" + reaper.reaperType());
		}
	}

}
