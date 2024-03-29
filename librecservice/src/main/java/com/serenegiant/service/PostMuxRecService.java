package com.serenegiant.service;
/*
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.IBinder;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import android.util.Log;

import com.serenegiant.librecservice.R;
import com.serenegiant.media.IMuxer;
import com.serenegiant.media.IPostMuxer;
import com.serenegiant.media.MediaRawChannelMuxer;
import com.serenegiant.media.MediaRawFileMuxer;
import com.serenegiant.media.MediaReaper;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;

/**
 * エンコードしたデータを一旦一時ファイルに書き出しておいて録画終了後に
 * 一括してmuxして動画ファイルを生成するためのAbstractRecorderService実装
 */
public class PostMuxRecService extends AbstractRecorderService {
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = PostMuxRecService.class.getSimpleName();

	public static final int STATE_MUXING = 100;
	
	public static final String KEY_MUX_INTERMEDIATE_TYPE = "MUX_INTERMEDIATE_TYPE";

	/**
	 * 中間ファイルの形式
	 * MediaRawFileMuxerを使う
	 */
	public static final int MUX_INTERMEDIATE_TYPE_FILE = 0;
	/**
	 * 中間ファイルの形式
	 * MediaRawChannelMuxerを使う
	 */
	public static final int MUX_INTERMEDIATE_TYPE_CHANNEL = 1;
	
	@IntDef({MUX_INTERMEDIATE_TYPE_FILE,
		MUX_INTERMEDIATE_TYPE_CHANNEL,
	})
	@Retention(RetentionPolicy.SOURCE)
	public @interface MuxIntermediateType {}
	
	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public PostMuxRecService getService() {
			return PostMuxRecService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();
	private IPostMuxer mMuxer;
	private int mVideoTrackIx = -1;
	private int mAudioTrackIx = -1;
	
	/**
	 * 録画サービスの処理を実行中かどうかを返す
	 * @return true: サービスの自己終了しない
	 */
	@Override
	public boolean isRunning() {
		synchronized (mSync) {
			return super.isRunning() || (getState() == STATE_MUXING);
		}
	}

	@Override
	protected IBinder getBinder() {
		return mBinder;
	}

	@NonNull
	@Override
	protected String getTitle() {
		return getString(R.string.service_post_mux);
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

		if (DEBUG) Log.v(TAG, "internalStart:output=" + output + ",uri=" + output.getUri() + ",video=" + videoFormat + ",audio=" + audioFormat);
		if (output == null) {
			throw new IOException("output is null");
		}
		if (mMuxer == null) {
			final Intent intent = getIntent();
			@MuxIntermediateType
			final int type = intent != null
				? intent.getIntExtra(KEY_MUX_INTERMEDIATE_TYPE, MUX_INTERMEDIATE_TYPE_FILE)
				: MUX_INTERMEDIATE_TYPE_FILE;
			switch (type) {
			case MUX_INTERMEDIATE_TYPE_CHANNEL:
				if (DEBUG) Log.v(TAG, "internalStart:create MediaRawChannelMuxer");
				mMuxer = new MediaRawChannelMuxer(this,
					requireConfig(),
					output,
					videoFormat, audioFormat);
				break;
			case MUX_INTERMEDIATE_TYPE_FILE:
			default:
				if (DEBUG) Log.v(TAG, "internalStart:create MediaRawFileMuxer");
				mMuxer = new MediaRawFileMuxer(this,
					requireConfig(),
					output,
					videoFormat, audioFormat);
				break;
			}
			mVideoTrackIx = videoFormat != null ? mMuxer.addTrack(videoFormat) : -1;
			mAudioTrackIx = audioFormat != null ? mMuxer.addTrack(audioFormat) : -1;
			mMuxer.start();
			synchronized (mSync) {
				mSync.notifyAll();
			}
		} else if (DEBUG) {
			Log.w(TAG, "internalStart:muxer already exists,muxer=" + mMuxer);
		}
	}
	
	/**
	 * 録画終了の実態, mSyncをロックして呼ばれる
	 */
	@Override
	protected void internalStop() {
		if (DEBUG) Log.v(TAG, "internalStop:muxer=" + mMuxer);
		final IPostMuxer muxer = mMuxer;
		mMuxer = null;
		if (getState() == STATE_RECORDING) {
			releaseEncoder();
			if (muxer != null) {
				setState(STATE_MUXING);
				queueEvent(new Runnable() {
					@Override
					public void run() {
						if (DEBUG) Log.v(TAG, "internalStop:start build");
						try {
							muxer.build();
						} catch (final IOException e) {
							Log.w(TAG, e);
						}
						if (getState() == STATE_MUXING) {
							setState(STATE_INITIALIZED);
						}
						muxer.release();
						checkStopSelf();
						if (DEBUG) Log.v(TAG, "internalStop:build finished");
					}
				});
			} else {
				setState(STATE_INITIALIZED);
			}
		}
	}
		
	/**
	 * エンコード済みのフレームデータを書き出す
	 * @param reaper
	 * @param byteBuf
	 * @param bufferInfo
	 * @param ptsUs
	 * @throws IOException
	 */
	@Override
	protected void onWriteSampleData(@NonNull final MediaReaper reaper,
		@NonNull final ByteBuffer byteBuf,
		@NonNull final MediaCodec.BufferInfo bufferInfo, final long ptsUs) {

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
				muxer.writeSampleData(mVideoTrackIx, byteBuf, bufferInfo);
				break;
			case MediaReaper.REAPER_AUDIO:
				muxer.writeSampleData(mAudioTrackIx, byteBuf, bufferInfo);
				break;
			}
		}
	}
	
}
