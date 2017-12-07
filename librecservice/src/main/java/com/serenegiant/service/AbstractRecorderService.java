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
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.librecservice.R;
import com.serenegiant.media.IMuxer;
import com.serenegiant.media.MediaMuxerWrapper;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.media.VideoConfig;
import com.serenegiant.media.VideoMuxer;
import com.serenegiant.utils.FileUtils;
import com.serenegiant.utils.SDUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.serenegiant.media.MediaCodecHelper.MIME_AVC;
import static com.serenegiant.media.MediaCodecHelper.selectVideoCodec;

/**
 * Created by saki on 2017/12/05.
 *
 */
public abstract class AbstractRecorderService extends BaseService {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = AbstractRecorderService.class.getSimpleName();
	
	private static final int NOTIFICATION = R.string.notification_service;
	private static final int TIMEOUT_MS = 10;

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
	private RecordingTask mRecordingTask;

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
		mIsBind = false;
		mIntent = null;
		checkStopSelf();
		if (DEBUG) Log.v(TAG, "onUnbind:finished");
		return false;	// onRebind使用不可
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
		final int state = getState();
		return (state == STATE_RECORDING);
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

	public boolean isRunning() {
		return getState() == STATE_RECORDING;
	}
	
	protected void checkStopSelf() {
		if (DEBUG) Log.v(TAG, "checkStopSelf:");
		boolean isRunning = mIsBind | isRunning();
		// FIXME ここで終了できるかどうか確認
		synchronized (mSync) {
			if (!isRunning()) {
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

	@SuppressWarnings("deprecation")
	protected void showNotification(final CharSequence text) {
		final Notification notification = new Notification.Builder(this)
			.setSmallIcon(R.mipmap.ic_recording_service)  // the status icon
			.setTicker(text)  // the status text
			.setWhen(System.currentTimeMillis())  // the time stamp
			.setContentTitle(getText(R.string.time_shift))  // the label of the entry
			.setContentText(text)  // the contents of the entry
			.setContentIntent(createPendingIntent())  // The intent to send when the entry is clicked
			.build();
		// Send the notification.
		synchronized (mSync) {
			if (mNotificationManager != null) {
				startForeground(NOTIFICATION, notification);
				mNotificationManager.notify(NOTIFICATION, notification);
			}
		}
	}

	/**
	 * サービスのティフィケーションを選択した時に実行されるPendingIntentの生成
	 * 普通はMainActivityを起動させる
	 * @return
	 */
	@NonNull
	protected abstract PendingIntent createPendingIntent();

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
				setState(STATE_READY);
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
		mVideoReaper = new MediaReaper.VideoReaper(mVideoEncoder, mReaperListener, width, height);
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
	public void start(final String outputPath) throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "start:");
		synchronized (mSync) {
			internalStart(outputPath);
		}
	}

	/**
	 * 録画開始
	 * @param accessId
	 */
	public void start(final int accessId) throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "start:");
		synchronized (mSync) {
			internalStart(accessId);
		}
	}

	private static final String EXT_VIDEO = ".mp4";
	private String mOutputPath;

	protected void internalStart(final String outputPath) throws IOException {
		if (DEBUG) Log.v(TAG, "internalStart:");
		if (mVideoFormat != null) {
			if (checkFreeSpace(this, 0)) {
				final IMuxer muxer = new MediaMuxerWrapper(
					outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
				final int trackIndex = muxer.addTrack(mVideoFormat);
				mRecordingTask = new RecordingTask(muxer, trackIndex);
				new Thread(mRecordingTask, "RecordingTask").start();
			} else {
				throw new IOException();
			}
		} else {
			throw new IllegalStateException("there is no MediaFormat received.");
		}
		setState(STATE_RECORDING);
	}
	
	protected void internalStart(final int accessId) throws IOException {
		if (DEBUG) Log.v(TAG, "internalStart:");
		if (mVideoFormat != null) {
			if (checkFreeSpace(this, accessId)) {
				// 録画開始
				final IMuxer muxer;
				if ((accessId > 0) && SDUtils.hasStorageAccess(this, accessId)) {
					// FIXME Oreoの場合の処理を追加
					mOutputPath = FileUtils.getCaptureFile(this,
						Environment.DIRECTORY_MOVIES, null, EXT_VIDEO, accessId).toString();
					final String file_name = FileUtils.getDateTimeString() + EXT_VIDEO;
					final int fd = SDUtils.createStorageFileFD(this, accessId, "*/*", file_name);
					muxer = new VideoMuxer(fd);
				} else {
					// 通常のファイルパスへの出力にフォールバック
					try {
						mOutputPath = FileUtils.getCaptureFile(this,
							Environment.DIRECTORY_MOVIES, null, EXT_VIDEO, 0).toString();
					} catch (final Exception e) {
						throw new IOException("This app has no permission of writing external storage");
					}
					muxer = new MediaMuxerWrapper(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
				}
				final int trackIndex = muxer.addTrack(mVideoFormat);
				mRecordingTask = new RecordingTask(muxer, trackIndex);
				new Thread(mRecordingTask, "RecordingTask").start();
			} else {
				throw new IOException();
			}
		} else {
			throw new IllegalStateException("there is no MediaFormat received.");
		}
		setState(STATE_RECORDING);
	}
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

	protected abstract void onWriteSampleData(final int reaperType,
		final ByteBuffer byteBuf, final MediaCodec.BufferInfo bufferInfo,
		final long pts) throws IOException;

	/**
	 * MediaReaperからのコールバックリスナーの実装
	 */
	private final MediaReaper.ReaperListener
		mReaperListener = new MediaReaper.ReaperListener() {
		@Override
		public void writeSampleData(final int reaperType,
			final ByteBuffer byteBuf, final MediaCodec.BufferInfo bufferInfo) {

//			if (DEBUG) Log.v(TAG, "writeSampleData:");
			if (reaperType == MediaReaper.REAPER_VIDEO) {
				try {
					final long ptsUs = getInputPTSUs();
					synchronized (mSync) {
						onWriteSampleData(reaperType, byteBuf, bufferInfo, ptsUs);
					}
				} catch (final IOException e) {
					AbstractRecorderService.this.onError(e);
				}
			}
		}

		@Override
		public void onOutputFormatChanged(final MediaFormat format) {
			if (DEBUG) Log.v(TAG, "onOutputFormatChanged:");
			mVideoFormat = format;	// そのまま代入するだけでいいんかなぁ
		}

		@Override
		public void onStop() {
			if (DEBUG) Log.v(TAG, "onStop:");
			releaseEncoder();
		}

		@Override
		public void onError(final Exception e) {
			AbstractRecorderService.this.onError(e);
		}
	};

	protected void onError(final Exception e) {
		if (DEBUG) Log.v(TAG, "onError:");
		Log.w(TAG, e);
	}
	
	protected abstract byte[] processFrame(final MediaCodec.BufferInfo info)
		throws IOException;
	
	/**
	 * 非同期でディスクキャッシュからエンコード済みの動画フレームを取得して
	 * mp4ファイルへ書き出すためのRunnable
	 */
	private class RecordingTask implements Runnable {
		private final IMuxer muxer;
		private final int trackIndex;
		public RecordingTask(final IMuxer muxer, final int trackIndex) {
			this.muxer = muxer;
			this.trackIndex = trackIndex;
		}

		@SuppressWarnings("WrongConstant")
		@Override
		public void run() {
			if (DEBUG) Log.v(TAG, "RecordingTask#run");
			final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			int frames = 0, error = 0;
			byte[] buf = null;
			muxer.start();
			boolean iFrame = false;
			for ( ; ; ) {
				synchronized (mSync) {
					if (mState != STATE_RECORDING) break;
					try {
						buf = processFrame(info);
						if (buf != null) {
							if (!iFrame) {
								if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != MediaCodec.BUFFER_FLAG_KEY_FRAME) {
									continue;
								} else {
									iFrame = true;
								}
							}
						} else {
							info.size = 0;
						}
					} catch (final IOException e) {
						info.size = 0;
					}
					if (info.size == 0) {
						try {
							mSync.wait(TIMEOUT_MS);
						} catch (final InterruptedException e) {
							break;
						}
						continue;
					}
				} // synchronized (mSync)
				if (DEBUG) Log.v(TAG, "writeSampleData:size=" + info.size + ", presentationTimeUs=" + info.presentationTimeUs);
				try {
					frames++;
					muxer.writeSampleData(trackIndex, ByteBuffer.wrap(buf, 0, info.size), info);
				} catch (final Exception e) {
					Log.w(TAG, e);
					error++;
				}
			} // for ( ; ; )
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
			if (DEBUG) Log.v(TAG, "RecordingTask#run:finished, cnt=" + frames);
		}

	}

	/**
	 * 録画終了, バッファリングは継続
	 */
	public void stop() {
		if (DEBUG) Log.v(TAG, "stop:");
		synchronized (mSync) {
			internalStop();
		}
	}

	protected void internalStop() {
		if (DEBUG) Log.v(TAG, "internalStop:");
		mRecordingTask = null;
		if (!TextUtils.isEmpty(mOutputPath)) {
			final String path = mOutputPath;
			mOutputPath = null;
			try {
				MediaScannerConnection.scanFile(this, new String[] {path}, null, null);
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
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
