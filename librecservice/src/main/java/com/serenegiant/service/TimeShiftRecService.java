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

import android.content.Intent;
import android.media.MediaCodec;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.serenegiant.librecservice.BuildConfig;
import com.serenegiant.media.VideoConfig;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * タイムシフト録画サービス
 * #startTimeShiftを呼ぶと常時エンコードをしながら一定間分を保持
 * #startを呼ぶと保持しているところまで遡って実際の録画を開始
 * #stopを呼ぶと録画を終了、エンコード自体は継続
 * #stopTimeShiftを呼ぶとエンコードを終了
 * #startTimeShift => [#start => #stop] => #stopTimeShift
 */
public class TimeShiftRecService extends AbstractRecorderService {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = TimeShiftRecService.class.getSimpleName();

	private static final long CACHE_SIZE = 1024 * 1024 * 20; // 20MB... 1920x1080@15fpsで20秒強ぐらい
	private static final String MIME_TYPE = "video/avc";

	public static final int STATE_BUFFERING = 3;
	public static final String EXTRA_MAX_SHIFT_MS = "extra_max_shift_ms";
	
	private static final long DEFAULT_MAX_SHIFT_MS = 10000L;	// 10秒

	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public TimeShiftRecService getService() {
			return TimeShiftRecService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();

	private TimeShiftDiskCache mVideoCache;
	private long mCacheSize = CACHE_SIZE;
	private String mCacheDir;

	@Override
	public boolean onUnbind(final Intent intent) {
		stopTimeShift();
		return super.onUnbind(intent);
	}
	
	/**
	 * タイムシフトバッファリングを開始
	 */
	public void startTimeShift() throws IllegalStateException {
		if (DEBUG) Log.v(TAG, "startTimeShift:");
		synchronized (mSync) {
			if (getState() != STATE_READY) {
				throw new IllegalStateException();
			}
		}
		// FIXME 未実装
		setState(STATE_BUFFERING);
	}

	/**
	 * タイムシフトバッファリングを終了
	 */
	public void stopTimeShift() {
		if (DEBUG) Log.v(TAG, "stopTimeShift:");
		stop();
		internalStopTimeShift();
		checkStopSelf();
	}

	@Override
	public boolean isRunning() {
		synchronized (mSync) {
			return super.isRunning() || (getState() == STATE_BUFFERING);
		}
	}

	/**
	 * タイムシフトバッファリング中かどうかを取得
	 * @return
	 */
	public boolean isTimeShift() {
		synchronized (mSync) {
			final int state = getState();
			return (state == STATE_BUFFERING) || (state == STATE_RECORDING);
		}
	}

	/**
	 * タイムシフト処理を全て停止して初期状態に戻す
	 * 一度#prepareを呼ぶと#clearを呼ぶまではキャッシュディレクトリやキャッシュサイズは変更できない
	 */
	public void clear() {
		if (DEBUG) Log.v(TAG, "clear:");
		stop();
		internalStopTimeShift();
		setState(STATE_INITIALIZED);
	}

	@Override
	protected IBinder getBinder() {
		return mBinder;
	}
	
//================================================================================
	/**
	 * タイムシフトバッファリングを終了の実体
	 */
	private void internalStopTimeShift() {
		if (getState() == STATE_BUFFERING) {
			setState(STATE_READY);
			releaseEncoder();
		}
	}
	
	@Override
	protected void internalStart(final String outputPath) throws IOException {
		if (getState() != STATE_BUFFERING) {
			throw new IllegalStateException("not started");
		}
		super.internalStart(outputPath);
	}

	@Override
	protected void internalStart(final int accessId) throws IOException {
		if (getState() != STATE_BUFFERING) {
			throw new IllegalStateException("not started");
		}
		super.internalStart(accessId);
	}

	@Override
	protected void internalStop() {
		if (getState() == STATE_RECORDING) {
			setState(STATE_BUFFERING);
		} else {
			return;
		}
		super.internalStop();
	}
	
	/**
	 * 非同期で#stopTimeShiftを呼ぶ
	 */
	private void stopTimeShiftAsync() {
		if (DEBUG) Log.v(TAG, "stopTimeShiftAsync:");
		queueEvent(new Runnable() {
			@Override
			public void run() {
				if (DEBUG) Log.v(TAG, "stopTimeShiftAsync#run:");
				stopTimeShift();
			}
		});
	}

	@Override
	protected void onError(final Exception e) {
		super.onError(e);
		stopTimeShiftAsync();
	}
	
	/**
	 * キャッシュサイズを指定
	 * @param cacheSize
	 * @throws IllegalStateException
	 * @throws IllegalArgumentException
	 */
	public void setCacheSize(final int cacheSize)
		throws IllegalStateException, IllegalArgumentException {

		synchronized (mSync) {
			if (getState() != STATE_INITIALIZED) {
				throw new IllegalStateException();
			}
			if (cacheSize < 0) {
				throw new IllegalArgumentException("cache size should be greater than zero");
			}
			mCacheSize = cacheSize;
		}
	}

	/**
	 * キャッシュ場所を指定, パーミッションが有ってアプリから書き込めること
	 * @param cacheDir
	 * @throws IllegalStateException prepareよりも後には呼べない
	 * @throws IllegalArgumentException パーミッションが無い
	 * 									あるいは存在しない場所など書き込めない時
	 */
	public void setCacheDir(final String cacheDir)
		throws IllegalStateException, IllegalArgumentException {

		synchronized (mSync) {
			if (getState() != STATE_INITIALIZED) {
				throw new IllegalStateException();
			}
			if (TextUtils.isEmpty(cacheDir)) {
				mCacheDir = null;
			} else {
				try {
					final File dir = new File(cacheDir);
					if (!dir.canWrite()) {
						throw new IllegalArgumentException();
					}
				} catch (final Exception e) {
					throw new IllegalArgumentException(
						"can't write to the directory:" + cacheDir);
				}
			}
		}
	}

//--------------------------------------------------------------------------------
	@Override
	protected void internalPrepare(final int width, final int height,
		final int frameRate, final float bpp)
			throws IllegalStateException, IOException {

		File cacheDir = null;
		if (!TextUtils.isEmpty(mCacheDir)) {
			// キャッシュディレクトリが指定されている時
			cacheDir = new File(mCacheDir);
		}
		if ((cacheDir == null) || !cacheDir.canWrite()) {
			// キャッシュディレクトリが指定されていないか書き込めない時は
			// 外部ストレージのキャッシュディレクトリを試みる
			cacheDir = getExternalCacheDir();
			if ((cacheDir == null) || !cacheDir.canWrite()) {
				// 内部ストレージのキャッシュディレクトリを試みる
				cacheDir = getCacheDir();
			}
		}
		if ((cacheDir == null) || !cacheDir.canWrite()) {
			throw new IOException("can't write cache dir");
		}
		final long maxShiftMs = getMaxShiftMs();
		VideoConfig.maxDuration = maxShiftMs;
		mVideoCache = TimeShiftDiskCache.open(cacheDir,
			BuildConfig.VERSION_CODE, 2, mCacheSize, maxShiftMs);
		super.internalPrepare(width, height, frameRate, bpp);
	}

	@Override
	protected void releaseEncoder() {
		releaseCache();
		super.releaseEncoder();
	}

	private long getMaxShiftMs() {
		final Intent intent = getIntent();
		return (intent != null)
			? intent.getLongExtra(EXTRA_MAX_SHIFT_MS, DEFAULT_MAX_SHIFT_MS)
			: DEFAULT_MAX_SHIFT_MS;
	}
		
	/**
	 * ストレージ上のキャッシュを削除
	 */
	private void releaseCache() {
		if (DEBUG) Log.v(TAG, "releaseCache:");
		if (mVideoCache != null) {
			try {
				mVideoCache.delete();
				mVideoCache.close();
			} catch (final IOException e) {
				Log.w(TAG, e);
			}
			mVideoCache = null;
		}
	}
	
	byte[] buf = null;
	long prevPtsUs = 0;
	TimeShiftDiskCache.Snapshot oldest;
	@SuppressWarnings("WrongConstant")
	@Override
	protected ByteBuffer processFrame(final MediaCodec.BufferInfo info)
		throws IOException {

		if (mVideoCache.size() > 0) {
			oldest = mVideoCache.getOldest();
			info.size = oldest != null ? oldest.available(0) : 0;
			if (info.size > 0) {
				info.presentationTimeUs = oldest.getKey();
				buf = oldest.getBytes(0, buf);
				info.flags = oldest.getInt(1);
				oldest.close();
				mVideoCache.remove(info.presentationTimeUs);
				if (info.presentationTimeUs == prevPtsUs) {
					Log.w(TAG, "duplicated frame data");
					info.size = 0;
				}
				prevPtsUs = info.presentationTimeUs;
			}
			return ByteBuffer.wrap(buf, 0, info.size);
		} else {
			info.size = 0;
		}
		return null;
	}
	
	protected void onWriteSampleData(final int reaperType,
		final ByteBuffer byteBuf, final MediaCodec.BufferInfo bufferInfo,
		final long ptsUs) throws IOException {
	
		if (mVideoCache != null) {
			final TimeShiftDiskCache.Editor editor = mVideoCache.edit(ptsUs);
			editor.set(0, byteBuf, bufferInfo.offset, bufferInfo.size);
			editor.set(1, bufferInfo.flags);
			editor.commit();
		}
	}

}
