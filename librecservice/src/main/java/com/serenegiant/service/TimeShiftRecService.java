package com.serenegiant.service;
/*
 * Copyright (c) 2016-2019.  saki t_saki@serenegiant.com
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
import android.media.MediaMuxer;
import android.os.Binder;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import android.text.TextUtils;
import android.util.Log;

import com.serenegiant.librecservice.BuildConfig;
import com.serenegiant.librecservice.R;
import com.serenegiant.media.IMuxer;
import com.serenegiant.media.MediaMuxerWrapper;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.mediastore.MediaStoreOutputStream;
import com.serenegiant.system.BuildCheck;
import com.serenegiant.utils.UriHelper;

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
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = TimeShiftRecService.class.getSimpleName();

	/**
	 * タイムシフトバッファリング中ステート
	 */
	public static final int STATE_BUFFERING = 100;

	/**
	 * インテントのextra(long)で最大タイムシフト時間[ミリ秒]を指定するときのキー文字列
	 * デフォルトの最大タイムシフト時間は10000[ミリ秒](10秒)
	 */
	public static final String EXTRA_MAX_SHIFT_MS = "extra_max_shift_ms";

	/**
	 * 最大キャッシュサイズ[バイト]
	 */
	private static final long CACHE_SIZE = 1024 * 1024 * 20; // 20MB... 1920x1080@15fpsで20秒強ぐらい

	/**
	 * 最大タイムシフト時間[ミリ秒]
	 */
	private static final long DEFAULT_MAX_SHIFT_MS = 10000L;	// 10秒

	/**
	 * MediaStoreOutputStreamを使って出力するかどうか(Android8以降のみ有効)
	 */
	private static final boolean USE_MEDIASTORE_OUTPUT_STREAM = false;

	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public TimeShiftRecService getService() {
			return TimeShiftRecService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();

	private TimeShiftDiskCache mVideoCache;
	private TimeShiftDiskCache mAudioCache;
	private long mCacheSize = CACHE_SIZE;
	private String mCacheDir;
	private RecordingTask mRecordingTask;

	/**
	 * デフォルトコンストラクタ
	 */
	public TimeShiftRecService() {
		super();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		mCacheDir = getFilesDir().getAbsolutePath();
	}

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
		// FIXME キャッシュをクリアする？ 未実装
		setState(STATE_BUFFERING);
	}

	/**
	 * タイムシフトバッファリングを終了
	 * 一度タイムシフトバッファリングを終了するとSTATE_INITIALIZEDになるので
	 * サービスを再生成するか#prepareからやり直す必要がある
	 */
	public void stopTimeShift() {
		if (DEBUG) Log.v(TAG, "stopTimeShift:");
		stop();
		internalStopTimeShift();
		checkStopSelf();
	}
	
	/**
	 * 録画サービスの処理を実行中かどうかを返す
	 * @return true: サービスの自己終了しない
	 */
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

	@NonNull
	@Override
	protected String getTitle() {
		return getString(R.string.service_time_shift);
	}

//================================================================================
	/**
	 * タイムシフトバッファリング終了の実体
	 */
	private void internalStopTimeShift() {
		if (DEBUG) Log.v(TAG, "internalStopTimeShift:state=" + getState());
		if (getState() == STATE_BUFFERING) {
			setState(STATE_INITIALIZED);
			synchronized (mSync) {
				releaseEncoder();
			}
		} else if (DEBUG) {
			Log.v(TAG, "internalStopTimeShift: not buffering now");
		}
	}
	
	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param outputPath 出力先ファイルパス
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	protected void internalStart(@NonNull final String outputPath,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {

		if (DEBUG) Log.v(TAG, "internalStart:");
		if (getState() != STATE_BUFFERING) {
			throw new IllegalStateException("not started");
		}
		if (!TextUtils.isEmpty(outputPath)) {
			@SuppressLint("InlinedApi")
			final IMuxer muxer = new MediaMuxerWrapper(
				outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			final int videoTrackIx = videoFormat != null ? muxer.addTrack(videoFormat) : -1;
			final int audioTrackIx = audioFormat != null ? muxer.addTrack(audioFormat) : -1;
			mRecordingTask = new RecordingTask(muxer, videoTrackIx, audioTrackIx);
			new Thread(mRecordingTask, "RecordingTask").start();
		} else {
			throw new IOException("invalid output dir or name");
		}
	}
	
	private String mOutputPath;

	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param output 出力ファイル
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	@SuppressLint("NewApi")
	protected void internalStart(@NonNull final DocumentFile output,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {
	
		if (DEBUG) Log.v(TAG, "internalStart:");
		if (getState() != STATE_BUFFERING) {
			throw new IllegalStateException("not started");
		}
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
		final int videoTrackIx = videoFormat != null ? muxer.addTrack(videoFormat) : -1;
		final int audioTrackIx = audioFormat != null ? muxer.addTrack(audioFormat) : -1;
		mRecordingTask = new RecordingTask(muxer, videoTrackIx, audioTrackIx);
		new Thread(mRecordingTask, "RecordingTask").start();
	}

	@Override
	protected void internalStop() {
		if (DEBUG) Log.v(TAG, "internalStop:");
		mRecordingTask = null;
		if (getState() == STATE_RECORDING) {
			setState(STATE_BUFFERING);
		}
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
	public void setCacheDir(@NonNull final String cacheDir)
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
		if (DEBUG) Log.v(TAG, "internalPrepare:video");

		mVideoCache = createCache("video");
		super.internalPrepare(width, height, frameRate, bpp);
	}

	@Override
	protected void internalPrepare(final int sampleRate, final int channelCount)
		throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "internalPrepare:audio");
		
		mAudioCache = createCache("audio");
		super.internalPrepare(sampleRate, channelCount);
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private File getTimeShiftCacheDir() throws IOException {
		File cacheDir = null;
		if (!TextUtils.isEmpty(mCacheDir)) {
			// キャッシュディレクトリが指定されている時
			cacheDir = new File(mCacheDir);
			cacheDir.mkdirs();
		}
		if ((cacheDir == null) || !cacheDir.canWrite()) {
			// キャッシュディレクトリが指定されていないか書き込めない時は
			// 外部ストレージのキャッシュディレクトリを試みる
			cacheDir = getExternalCacheDir();
			cacheDir.mkdirs();
		}
		if ((cacheDir == null) || !cacheDir.canWrite()) {
			// 内部ストレージのキャッシュディレクトリを試みる
			cacheDir = getCacheDir();
			cacheDir.mkdirs();
		}
		if ((cacheDir == null) || !cacheDir.canWrite()) {
			throw new IOException("can't write cache dir");
		}
		return cacheDir;
	}

	@Override
	protected void stopEncoder() {
		if (DEBUG) Log.v(TAG, "stopEncoder:");
		// ここではsuperを呼ばない
	}

	@Override
	protected void releaseEncoder() {
		if (DEBUG) Log.v(TAG, "releaseEncoder:");
		if (getState() != STATE_BUFFERING) {
			super.stopEncoder();
			super.releaseEncoder();
		}
	}
	
	/**
	 * 録画サービス起動時のインテントに最大タイムシフト時間の指定があれば
	 * その値を返す。指定がなければDEFAULT_MAX_SHIFT_MS(10秒)を返す。
	 * @return
	 */
	private long getMaxShiftMs() {
		final Intent intent = getIntent();
		return (intent != null)
			? intent.getLongExtra(EXTRA_MAX_SHIFT_MS, DEFAULT_MAX_SHIFT_MS)
			: DEFAULT_MAX_SHIFT_MS;
	}

	/**
	 * typeで指定したサブディレクトリを使うTimeShiftDiskCacheインスタンスを生成する
	 * @param type
	 * @return
	 * @throws IOException
	 */
	private TimeShiftDiskCache createCache(
		@NonNull final String type) throws IOException {

		final File cacheDir = new File(getTimeShiftCacheDir(), type);
		if (cacheDir.mkdirs() && DEBUG) {
			Log.v(TAG, "create new cache dir for video");
		}
		final long maxShiftMs = getMaxShiftMs();
		requireConfig().setMaxDuration(maxShiftMs);
		return TimeShiftDiskCache.open(cacheDir,
			BuildConfig.VERSION_CODE, 2, mCacheSize, maxShiftMs);
	}

	/**
	 * ストレージ上のキャッシュを削除
	 */
	private void releaseCache() {
		if (DEBUG) Log.v(TAG, "releaseCache:");
		if (mVideoCache != null) {
			try {
				mVideoCache.delete();
			} catch (final IOException e) {
				Log.w(TAG, e);
			}
			mVideoCache = null;
		}
		if (mAudioCache != null) {
			try {
				mAudioCache.delete();
			} catch (final IOException e) {
				Log.w(TAG, e);
			}
			mAudioCache = null;
		}
	}
	
	byte[] videoBuf = null;
	byte[] audioBuf = null;
	long prevVideoPtsUs = 0;
	long prevAudioPtsUs = 0;
	TimeShiftDiskCache.Snapshot oldestVideo;
	TimeShiftDiskCache.Snapshot oldestAudio;

	/**
	 * 非同期でエンコード済みの動画フレームを取得して
	 * mp4ファイルへ書き出すためのRunnable
	 */
	private class RecordingTask implements Runnable {
		private final IMuxer muxer;
		private final int videoTrackIx;
		private final int audioTrackIx;
		public RecordingTask(@NonNull final IMuxer muxer,
			final int videoTrackIx, final int audioTrackIx) {
			this.muxer = muxer;
			this.videoTrackIx = videoTrackIx;
			this.audioTrackIx = audioTrackIx;
		}

		@SuppressWarnings("WrongConstant")
		@Override
		public void run() {
			if (DEBUG) Log.v(TAG, "RecordingTask#run");
			final MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
			final MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
			int error = 0;
			int videoFrames = 0;
			int audioFrames = 0;
			ByteBuffer videoBuf = null;
			ByteBuffer audioBuf = null;
			muxer.start();
			boolean iFrame = false;
			for ( ; ; ) {
				synchronized (mSync) {
					if (getState() != STATE_RECORDING) break;
					try {
						videoBuf = processVideoFrame(videoInfo);
						if (videoBuf != null) {
							if (!iFrame) {
								if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME)
									!= MediaCodec.BUFFER_FLAG_KEY_FRAME) {

									videoInfo.size = 0;
								} else {
									iFrame = true;
								}
							}
						}
					} catch (final IOException e) {
						videoInfo.size = 0;
					}
					try {
						audioBuf = processAudioFrame(audioInfo);
					} catch (final IOException e) {
						audioInfo.size = 0;
					}
					
					if ((videoInfo.size <= 0) && (audioInfo.size <= 0)) {
						try {
							mSync.wait(TIMEOUT_MS);
						} catch (final InterruptedException e) {
							break;
						}
						continue;
					}
				} // synchronized (mSync)
				if ((videoTrackIx >= 0) && (videoInfo.size > 0)) {
//					if (DEBUG) Log.v(TAG, "writeSampleData/Video:size="+ videoInfo.size
//						+ ", presentationTimeUs=" + videoInfo.presentationTimeUs);
					try {
						videoFrames++;
						muxer.writeSampleData(videoTrackIx, videoBuf, videoInfo);
					} catch (final Exception e) {
						Log.w(TAG, e);
						error++;
					}
				}
				if ((audioTrackIx >= 0) && (audioInfo.size > 0)) {
//					if (DEBUG) Log.v(TAG, "writeSampleData/Audio:size=" + audioInfo.size
//						+ ", presentationTimeUs=" + audioInfo.presentationTimeUs);
					try {
						audioFrames++;
						muxer.writeSampleData(audioTrackIx, audioBuf, audioInfo);
					} catch (final Exception e) {
						Log.w(TAG, e);
						error++;
					}
				}
			} // for ( ; ; )
			try {
				muxer.stop();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
			final String outputPath;
			if (muxer instanceof MediaMuxerWrapper) {
				outputPath = ((MediaMuxerWrapper) muxer).getOutputPath();
			} else {
				outputPath = null;
			}
			try {
				muxer.release();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
			if (!TextUtils.isEmpty(outputPath)) {
				try {
					final File out = new File(outputPath);
					if (out.exists() && out.canRead()) {
						if (DEBUG) Log.v(TAG, "RecordingTask#run::scanFile " + outputPath);
						scanFile(outputPath);
					}
				} catch (final Exception e) {
					Log.w(TAG, e);
				}
			}
			if (DEBUG) Log.v(TAG, String.format("RecordingTask#run:finished, video=%d,audio=%d,err=%d",
				videoFrames, audioFrames, error));
		}

	}

	/**
	 * ビデオフレームデータが準備できているかどうか確認して準備できていれば
	 * BufferInfoを設定してByteBufferを返す
	 * @param info
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("WrongConstant")
	protected ByteBuffer processVideoFrame(
		@NonNull final MediaCodec.BufferInfo info) throws IOException {

		if (mVideoCache.size() > 0) {
			oldestVideo = mVideoCache.getOldest();
			info.size = oldestVideo != null ? oldestVideo.available(0) : 0;
			if (info.size > 0) {
				info.presentationTimeUs = oldestVideo.getKey();
				videoBuf = oldestVideo.getBytes(0, videoBuf);
				info.flags = oldestVideo.getInt(1);
				oldestVideo.close();
				mVideoCache.remove(info.presentationTimeUs);
				if (info.presentationTimeUs == prevVideoPtsUs) {
					Log.w(TAG, "duplicated frame data");
					info.size = 0;
				}
				prevVideoPtsUs = info.presentationTimeUs;
			}
			return ByteBuffer.wrap(videoBuf, 0, info.size);
		} else {
			info.size = 0;
		}
		return null;
	}
	
	/**
	 * オーディオフレームデータが準備できているかどうか確認して準備できていれば
	 * BufferInfoを設定してByteBufferを返す
	 * @param info
	 * @return
	 * @throws IOException
	 */
	protected ByteBuffer processAudioFrame(
		@NonNull final MediaCodec.BufferInfo info) throws IOException {

		if (mAudioCache.size() > 0) {
			oldestAudio = mAudioCache.getOldest();
			info.size = oldestAudio != null ? oldestAudio.available(0) : 0;
			if (info.size > 0) {
				info.presentationTimeUs = oldestAudio.getKey();
				audioBuf = oldestAudio.getBytes(0, audioBuf);
				info.flags = oldestAudio.getInt(1);
				oldestAudio.close();
				mAudioCache.remove(info.presentationTimeUs);
				if (info.presentationTimeUs == prevAudioPtsUs) {
					Log.w(TAG, "duplicated frame data");
					info.size = 0;
				}
				prevAudioPtsUs = info.presentationTimeUs;
			}
			return ByteBuffer.wrap(audioBuf, 0, info.size);
		} else {
			info.size = 0;
		}
		return null;
	}
	
	/**
	 * エンコード済みのフレームデータをキャッシュへ書き出す
	 * @param reaper
	 * @param byteBuf
	 * @param bufferInfo
	 * @param ptsUs
	 * @throws IOException
	 */
	protected void onWriteSampleData(@NonNull final MediaReaper reaper,
		@NonNull final ByteBuffer byteBuf,
		@NonNull final MediaCodec.BufferInfo bufferInfo, final long ptsUs)
			throws IOException {
	
		final TimeShiftDiskCache cache;
		synchronized (mSync) {
			switch (reaper.reaperType()) {
			case MediaReaper.REAPER_VIDEO:
				cache = mVideoCache;
				break;
			case MediaReaper.REAPER_AUDIO:
				cache = mAudioCache;
				break;
			default:
				cache = null;
			}
		}
		if ((cache != null) && !cache.isClosed()) {
			final TimeShiftDiskCache.Editor editor = cache.edit(ptsUs);
			editor.set(0, byteBuf, bufferInfo.offset, bufferInfo.size);
			editor.set(1, bufferInfo.flags);
			editor.commit();
		}
	}

}
