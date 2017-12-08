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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.librecservice.R;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.media.VideoConfig;
import com.serenegiant.utils.FileUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.serenegiant.media.MediaCodecHelper.MIME_AVC;
import static com.serenegiant.media.MediaCodecHelper.selectVideoCodec;

/**
 * Created by saki on 2017/12/05.
 * TimeShiftRecServiceから流用できそうな部分を切り出し
 * FIXME 今は録画のみ。録音は未対応
 */
public abstract class AbstractRecorderService extends BaseService {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = AbstractRecorderService.class.getSimpleName();
	
	private static final int NOTIFICATION = R.string.notification_service;
	protected static final int TIMEOUT_MS = 10;

	// ステート定数, XXX 継承クラスは100以降を使う
	public static final int STATE_UNINITIALIZED = -1;
	public static final int STATE_INITIALIZED = 0;
	public static final int STATE_PREPARING = 1;
	public static final int STATE_READY = 2;
	public static final int STATE_RECORDING = 4;
	public static final int STATE_RELEASING = 9;
	
	public interface StateChangeListener {
		public void onStateChanged(@NonNull final AbstractRecorderService service,
			final int state);
	}
	
	protected final Object mSync = new Object();
	private final Set<StateChangeListener> mListeners
		= new CopyOnWriteArraySet<StateChangeListener>();
	private NotificationManager mNotificationManager;
	private Intent mIntent;
	private int mState = STATE_UNINITIALIZED;
	private boolean mIsBind;
	private MediaFormat mVideoFormat;
	private MediaCodec mVideoEncoder;
	private Surface mInputSurface;
	private MediaReaper.VideoReaper mVideoReaper;

	@Override
	public void onCreate() {
		super.onCreate();
		if (DEBUG) Log.v(TAG, "onCreate:");
		synchronized (mSync) {
			mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		}
	}

	@Override
	public void onDestroy() {
		if (DEBUG) Log.v(TAG, "onDestroy:");
		synchronized (mSync) {
			mState = STATE_UNINITIALIZED;
			mIsBind = false;
			stopForeground(true/*removeNotification*/);
			if (mNotificationManager != null) {
				mNotificationManager.cancel(NOTIFICATION);
				mNotificationManager = null;
			}
			mListeners.clear();
		}
		super.onDestroy();
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags, final int startId) {
		if (DEBUG) Log.i(TAG, "onStartCommand:startId=" + startId + ": " + intent);
		super.onStartCommand(intent, flags, startId);
		return START_STICKY;
	}

	@Nullable
	@Override
	public IBinder onBind(final Intent intent) {
		if (DEBUG) Log.v(TAG, "onBind:intent=" + intent);
		if (intent != null) {
			showNotification(getString(R.string.notification_service));
			synchronized (mSync) {
				if (mState == STATE_UNINITIALIZED) {
					setState(STATE_INITIALIZED);
				}
				mIsBind = true;
			}
		}
		synchronized (mSync) {
			mIntent = intent;
		}
		return getBinder();
	}

	@Override
	public boolean onUnbind(final Intent intent) {
		if (DEBUG) Log.d(TAG, "onUnbind:" + intent);
		synchronized (mSync) {
			mIsBind = false;
			mIntent = null;
			mListeners.clear();
		}
		checkStopSelf();
		if (DEBUG) Log.v(TAG, "onUnbind:finished");
		return false;	// onRebind使用不可
	}

	public void addListener(@Nullable final StateChangeListener listener) {
		if (listener != null) {
			mListeners.add(listener);
		}
	}
	
	public void removeListener(@Nullable final StateChangeListener listener) {
		mListeners.remove(listener);
	}
	
	@Override
	protected IntentFilter createIntentFilter() {
		return null;
	}

	@Override
	protected void onReceiveLocalBroadcast(final Context context, final Intent intent) {

	}
	
	protected abstract IBinder getBinder();
	
	@Nullable
	protected Intent getIntent() {
		synchronized (mSync) {
			return mIntent;
		}
	}
//================================================================================
	/**
	 * 録画中かどうかを取得
	 * @return
	 */
	public boolean isRecording() {
		synchronized (mSync) {
			return (getState() == STATE_RECORDING);
		}
	}

	public boolean isRunning() {
		synchronized (mSync) {
			return getState() == STATE_RECORDING;
		}
	}
	
	protected void setState(final int newState) {
		boolean changed;
		synchronized (mSync) {
			changed = mState != newState;
			mState = newState;
			mSync.notifyAll();
		}
		if (changed) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					for (final StateChangeListener listener: mListeners) {
						try {
							listener.onStateChanged(AbstractRecorderService.this, newState);
						} catch (final Exception e) {
							mListeners.remove(listener);
						}
					}
				}
			});
		}
	}

	protected int getState() {
		synchronized (mSync) {
			return mState;
		}
	}

	protected void checkStopSelf() {
		if (DEBUG) Log.v(TAG, "checkStopSelf:");
		synchronized (mSync) {
			if (canStopSelf(mIsBind | isRunning())) {
				if (DEBUG) Log.v(TAG, "stopSelf");
				setState(STATE_RELEASING);
				queueEvent(new Runnable() {
					@Override
					public void run() {
						showNotification(getString(R.string.notification_service));
						stopSelf();
					}
				});
			}
		}
	}
	
	/**
	 * サービスを終了可能かどうかを確認
	 * @return 終了可能であればtrue
	 */
	protected boolean canStopSelf(final boolean isRunning) {
		return !isRunning;
	}
	
	@SuppressWarnings("deprecation")
	protected void showNotification(final CharSequence text) {
		final PendingIntent intent = createPendingIntent();
		final Notification.Builder builder
		 	= new Notification.Builder(this)
			.setSmallIcon(R.mipmap.ic_recording_service)  // the status icon
			.setTicker(text)  // the status text
			.setWhen(System.currentTimeMillis())  // the time stamp
			.setContentTitle(getText(R.string.time_shift))  // the label of the entry
			.setContentText(text);  // the contents of the entry
		if (intent != null) {
			builder.setContentIntent(intent);  // The intent to send when the entry is clicked
		}
		// Send the notification.
		final Notification notification = builder.build();
		synchronized (mSync) {
			if (mNotificationManager != null) {
				startForeground(NOTIFICATION, notification);
				mNotificationManager.notify(NOTIFICATION, notification);
			}
		}
	}

	/**
	 * サービスのティフィケーションを選択した時に実行されるPendingIntentの生成
	 * 普通はMainActivityを起動させる。
	 * デフォルトはnullを返すだけでノティフィケーションを選択しても何も実行されない。
	 * @return
	 */
	@Nullable
	protected PendingIntent createPendingIntent() {
		return null;
	}

	/**
	 * 録画の準備
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void prepare(final int width, final int height,
		final int frameRate, final float bpp)
			throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "prepare:");
		synchronized (mSync) {
			if (getState() != STATE_INITIALIZED) {
				throw new IllegalStateException();
			}
			setState(STATE_PREPARING);
			try {
				internalPrepare(width, height, frameRate, bpp);
				createEncoder(width, height, frameRate, bpp);
				// MediaReaper.ReaperListener#onOutputFormatChangedへ移動
//				setState(STATE_READY);
			} catch (final IllegalStateException | IOException e) {
				releaseEncoder();
				throw e;
			}
		}
	}
	
	protected void internalPrepare(final int width, final int height,
		final int frameRate, final float bpp)
			throws IllegalStateException, IOException {
	}
	
	/**
	 * MediaCodecのエンコーダーを生成
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @throws IOException
	 */
	protected void createEncoder(final int width, final int height,
		final int frameRate, final float bpp) throws IOException {

		if (DEBUG) Log.v(TAG, "createEncoder:");
		final MediaCodecInfo codecInfo = selectVideoCodec(MIME_AVC);
		if (codecInfo == null) {
			throw new IOException("Unable to find an appropriate codec for " + MIME_AVC);
		}
		final MediaFormat format = MediaFormat.createVideoFormat(MIME_AVC, width, height);
		// MediaCodecに適用するパラメータを設定する。
		// 誤った設定をするとMediaCodec#configureが復帰不可能な例外を生成する
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
			MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);	// API >= 18
		format.setInteger(MediaFormat.KEY_BIT_RATE,
			VideoConfig.getBitrate(width, height, frameRate, bpp));
		format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);	// IFrameの間隔は1秒にする
		if (DEBUG) Log.d(TAG, "format: " + format);

		// 設定したフォーマットに従ってMediaCodecのエンコーダーを生成する
		mVideoEncoder = MediaCodec.createEncoderByType(MIME_AVC);
		mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		// エンコーダーへの入力に使うSurfaceを取得する
		mInputSurface = mVideoEncoder.createInputSurface();	// API >= 18
		mVideoEncoder.start();
		mVideoReaper = new MediaReaper.VideoReaper(mVideoEncoder, mReaperListener,
			width, height);
	}

	/**
	 * 前回MediaCodecへのエンコード時に使ったpresentationTimeUs
	 */
	private long prevInputPTSUs = -1;

	/**
	 * 今回の書き込み用のpresentationTimeUs値を取得
     * System.nanoTime()を1000で割ってマイクロ秒にしただけ(切り捨て)
	 * @return
	 */
    protected long getInputPTSUs() {
		long result = System.nanoTime() / 1000L;
		if (result <= prevInputPTSUs) {
			result = prevInputPTSUs + 9643;
		}
		prevInputPTSUs = result;
		return result;
    }

	/**
	 * エンコーダーを破棄
	 */
	protected void releaseEncoder() {
		if (DEBUG) Log.v(TAG, "releaseEncoder:");
		if (mVideoReaper != null) {
			mVideoReaper.release();
			mVideoReaper = null;
		}
		mVideoEncoder = null;
		mInputSurface = null;
		if (DEBUG) Log.v(TAG, "releaseEncoder:finished");
	}

	/**
	 * 録画開始
	 * @param outputPath
	 */
	public void start(@NonNull final String outputPath) throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "start:");
		synchronized (mSync) {
			// FIXME 録音は未対応
			if (mVideoFormat != null) {
				if (checkFreeSpace(this, 0)) {
					internalStart(outputPath, mVideoFormat, null);
				} else {
					throw new IOException();
				}
			} else {
				throw new IllegalStateException("there is no MediaFormat received.");
			}
			setState(STATE_RECORDING);
		}
	}

	/**
	 * 録画開始
	 * @param accessId
	 */
	public void start(final int accessId) throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "start:");
		synchronized (mSync) {
			// FIXME 録音は未対応
			if (mVideoFormat != null) {
				if (checkFreeSpace(this, 0)) {
					internalStart(accessId, mVideoFormat, null);
				} else {
					throw new IOException();
				}
			} else {
				throw new IllegalStateException("there is no MediaFormat received.");
			}
			setState(STATE_RECORDING);
		}
	}

	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param outputPath
	 * @throws IOException
	 */
	protected abstract void internalStart(@NonNull final String outputPath,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException;
	
	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param accessId
	 * @throws IOException
	 */
	protected abstract void internalStart(final int accessId,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException;
	
	/**
	 * 空き容量をチェック
	 * @param context
	 * @param accessId
	 * @return
	 */
	protected boolean checkFreeSpace(final Context context, final int accessId) {
		return FileUtils.checkFreeSpace(context,
			VideoConfig.maxDuration, System.currentTimeMillis(), accessId);
	}
	
	/**
	 * エンコード済みのフレームデータを書き出す
	 * @param reaper
	 * @param byteBuf
	 * @param bufferInfo
	 * @param ptsUs
	 * @throws IOException
	 */
	protected abstract void onWriteSampleData(@NonNull MediaReaper reaper,
		@NonNull final ByteBuffer byteBuf,
		@NonNull final MediaCodec.BufferInfo bufferInfo, final long ptsUs)
			throws IOException;

	/**
	 * MediaReaperからのコールバックリスナーの実装
	 */
	private final MediaReaper.ReaperListener
		mReaperListener = new MediaReaper.ReaperListener() {

		@Override
		public void writeSampleData(@NonNull final MediaReaper reaper,
			final ByteBuffer byteBuf, final MediaCodec.BufferInfo bufferInfo) {

//			if (DEBUG) Log.v(TAG, "writeSampleData:");
			try {
				final long ptsUs = getInputPTSUs();
				synchronized (mSync) {
					onWriteSampleData(reaper, byteBuf, bufferInfo, ptsUs);
				}
			} catch (final IOException e) {
				AbstractRecorderService.this.onError(e);
			}
		}

		@Override
		public void onOutputFormatChanged(@NonNull final MediaReaper reaper,
			@NonNull final MediaFormat format) {

			if (DEBUG) Log.v(TAG, "onOutputFormatChanged:");
			// FIXME 録音は未対応
			mVideoFormat = format;	// そのまま代入するだけでいいんかなぁ
			setState(STATE_READY);
		}

		@Override
		public void onStop(@NonNull final MediaReaper reaper) {
			if (DEBUG) Log.v(TAG, "onStop:");
			releaseEncoder();
		}

		@Override
		public void onError(@NonNull final MediaReaper reaper, final Exception e) {
			AbstractRecorderService.this.onError(e);
		}
	};

	protected void onError(final Exception e) {
		if (DEBUG) Log.v(TAG, "onError:");
		Log.w(TAG, e);
	}
	
	/**
	 * 録画終了
	 */
	public void stop() {
		if (DEBUG) Log.v(TAG, "stop:");
		synchronized (mSync) {
			internalStop();
		}
	}
	
	/**
	 * 録画終了の実態, mSyncをロックして呼ばれる
	 */
	protected abstract void internalStop();
	
	/**
	 * MediaScannerConnection.scanFileを呼び出す
 	 * @param outputPath
	 */
	protected void scanFile(@NonNull final String outputPath) {
		try {
			MediaScannerConnection.scanFile(this, new String[] {outputPath}, null, null);
		} catch (final Exception e) {
			Log.w(TAG, e);
		}
	}
	
	/**
	 * 映像入力用のSurfaceを取得する
	 * @return
	 * @throws IllegalStateException #prepareと#startの間以外で呼ぶとIllegalStateExceptionを投げる
	 */
	public Surface getInputSurface() throws IllegalStateException {
		if (DEBUG) Log.v(TAG, "getInputSurface:");
		synchronized (mSync) {
			if (mState == STATE_READY) {
				return mInputSurface;
			} else {
				throw new IllegalStateException();
			}
		}
	}

	/**
	 * MediaReaper#frameAvailableSoonを呼ぶためのヘルパーメソッド
	 */
	public void frameAvailableSoon() {
		synchronized (mSync) {
			if (mVideoReaper != null) {
				mVideoReaper.frameAvailableSoon();
			}
			mSync.notifyAll();
		}
	}
}
