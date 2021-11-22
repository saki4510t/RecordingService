package com.serenegiant.service;
/*
 *
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
 *
 * File name: SplitRecorderService.java
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
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.serenegiant.librecservice.R;
import com.serenegiant.media.IMuxer;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.media.MediaSplitMuxerV2;

import java.io.IOException;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

/**
 * 分割録画機能をサービス側で実行するのサービス
 */
public class SplitRecorderService extends AbstractRecorderService {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = SplitRecorderService.class.getSimpleName();

	public static long MAX_FILE_SIZE = 1024 * 1024 * 10; // 10MB // 4000000000L;

	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public SplitRecorderService getService() {
			return SplitRecorderService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();
	private String mOutputPath;
	private IMuxer mMuxer;
	private int mVideoTrackIx = -1;
	private int mAudioTrackIx = -1;

	@Override
	protected IBinder getBinder() {
		return mBinder;
	}

	@NonNull
	@Override
	protected String getTitle() {
		return getString(R.string.service_simple);
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
		final IMuxer muxer = new MediaSplitMuxerV2(this,
			output, requireConfig(), null,
			null, MAX_FILE_SIZE);
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
