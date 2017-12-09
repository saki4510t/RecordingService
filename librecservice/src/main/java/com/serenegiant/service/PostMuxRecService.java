/*
 * Copyright (c) 2016-2017.  saki t_saki@serenegiant.com
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
package com.serenegiant.service;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.serenegiant.media.MediaRawFileMuxer;
import com.serenegiant.media.MediaReaper;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by saki on 2017/12/08.
 *
 */
public class PostMuxRecService extends AbstractRecorderService {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = PostMuxRecService.class.getSimpleName();

	public static final int STATE_MUXING = 100;
	
	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public PostMuxRecService getService() {
			return PostMuxRecService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();
	private MediaRawFileMuxer mMuxer;
	private int mVideoTrackIx = -1;
	private int mAudioTrackIx = -1;

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
	
	@Override
	protected void internalStart(@NonNull final String outputPath,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {
		
		if (DEBUG) Log.v(TAG, "internalStart:outputPath=" + outputPath);
		if (mMuxer == null) {
			mMuxer = new MediaRawFileMuxer(this, outputPath, videoFormat, audioFormat);
			if (videoFormat != null) {
				mVideoTrackIx = mMuxer.addTrack(videoFormat);
			}
			if (audioFormat != null) {
				mAudioTrackIx = mMuxer.addTrack(audioFormat);
			}
			mMuxer.start();
		}
	}
	
	@Override
	protected void internalStart(final int accessId,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {
		
		if (DEBUG) Log.v(TAG, "internalStart:accessId=" + accessId);
		// FIXME 未実装
		throw new UnsupportedOperationException("not implemented yet.");
	}
	
	@Override
	protected void internalStop() {
		if (DEBUG) Log.v(TAG, "internalStop:muxer=" + mMuxer);
		final MediaRawFileMuxer muxer = mMuxer;
		mMuxer = null;
		if (getState() == STATE_RECORDING) {
			releaseEncoder();
			if (muxer != null) {
				setState(STATE_MUXING);
				queueEvent(new Runnable() {
					@Override
					public void run() {
						try {
							muxer.build();
						} catch (final IOException e) {
							Log.w(TAG, e);
						}
						if (getState() == STATE_MUXING) {
							setState(STATE_READY);
						}
						muxer.release();
						checkStopSelf();
					}
				});
			} else {
				setState(STATE_READY);
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
		@NonNull final MediaCodec.BufferInfo bufferInfo, final long ptsUs)
			throws IOException {

		if (mMuxer != null) {
			switch (reaper.reaperType()) {
			case MediaReaper.REAPER_VIDEO:
				mMuxer.writeSampleData(mVideoTrackIx, byteBuf, bufferInfo);
				break;
			case MediaReaper.REAPER_AUDIO:
				mMuxer.writeSampleData(mAudioTrackIx, byteBuf, bufferInfo);
				break;
			}
		}
	}
	
}
