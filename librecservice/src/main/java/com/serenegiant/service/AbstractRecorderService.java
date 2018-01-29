package com.serenegiant.service;
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

import android.annotation.SuppressLint;
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
import android.support.v4.provider.DocumentFile;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.librecservice.R;
import com.serenegiant.media.AudioSampler;
import com.serenegiant.media.IAudioSampler;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.media.VideoConfig;
import com.serenegiant.utils.BuildCheck;
import com.serenegiant.utils.FileUtils;

import java.io.File;
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
	private static final boolean DEBUG = false; // FIXME set false on production
	private static final String TAG = AbstractRecorderService.class.getSimpleName();
	
	private static final int NOTIFICATION = R.string.notification_service;
	protected static final int TIMEOUT_MS = 10;
	protected static final int TIMEOUT_USEC = 10000;	// 10ミリ秒

	// ステート定数, XXX 継承クラスは100以降を使う
	public static final int STATE_UNINITIALIZED = -1;
	public static final int STATE_INITIALIZED = 0;
	public static final int STATE_PREPARING = 1;
	public static final int STATE_PREPARED = 2;
	public static final int STATE_READY = 3;
	public static final int STATE_RECORDING = 4;
	public static final int STATE_RELEASING = 9;
	
	public interface StateChangeListener {
		public void onStateChanged(@NonNull final AbstractRecorderService service,
			final int state);
	}
	
	private final Set<StateChangeListener> mListeners
		= new CopyOnWriteArraySet<StateChangeListener>();
	private Intent mIntent;
	private int mState = STATE_UNINITIALIZED;
	private boolean mIsBind;
	/** 動画のサイズ(録画する場合) */
	private int mWidth, mHeight;
	private int mFrameRate;
	private float mBpp;
	private MediaFormat mVideoFormat;
	private MediaCodec mVideoEncoder;
	private Surface mInputSurface;
	private MediaReaper.VideoReaper mVideoReaper;

	private IAudioSampler mAudioSampler;
	private int mSampleRate, mChannelCount;
	private MediaFormat mAudioFormat;
	private MediaCodec mAudioEncoder;
	private MediaReaper.AudioReaper mAudioReaper;
	private volatile boolean mIsEos;

	@Override
	public void onCreate() {
		super.onCreate();
		if (DEBUG) Log.v(TAG, "onCreate:");
		internalResetSettings();
	}

	@Override
	public void onDestroy() {
		if (DEBUG) Log.v(TAG, "onDestroy:");
		synchronized (mSync) {
			mState = STATE_UNINITIALIZED;
			mIsBind = false;
			releaseNotification();
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
			showNotification(NOTIFICATION,
				getString(R.string.notification_service),
				R.mipmap.ic_recording_service, R.mipmap.ic_recording_service,
				R.string.notification_service, R.string.time_shift,
				contextIntent());
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
	
	public void setVideoSettings(final int width, final int height,
		final int frameRate, final float bpp) {
		
		mWidth = width;
		mHeight = height;
		mFrameRate = frameRate;
		mBpp = bpp;
	}

	/**
	 * 録音用のIAudioSamplerをセット
	 * #writeAudioFrameと排他使用
	 * @param sampler
	 */
	public void setAudioSampler(@NonNull final IAudioSampler sampler) {
		// FIXME 未実装
	}
	
	/**
	 * 録音用の音声データを書き込む
	 * #setAudioSamplerと排他使用
	 * @param buffer position/limitを正しくセットしておくこと
	 * @param presentationTimeUs
	 */
	public void writeAudioFrame(@NonNull final ByteBuffer buffer,
		final long presentationTimeUs) {
		
		// FIXME 未実装
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
	 * 録画中かどうかを取得する。
	 * このクラスでは#isRunningと同じ値を返すがこちらは
	 * オーバーライド不可
	 * @return
	 */
	public final boolean isRecording() {
		synchronized (mSync) {
			return (getState() == STATE_RECORDING);
		}
	}
	
	/**
	 * 録画サービスの処理を実行中かどうかを返す
	 * このクラスでは#isRecordingと同じクラスを返す。
	 * こちらはオーバーライド可能でサービスの自己終了判定に使う。
	 * 録画中では無いが録画の前後処理中などで録画サービスを
	 * 終了しないようにしたい時に下位クラスでオーバーライドする。
	 * @return true: サービスの自己終了しない
	 */
	public boolean isRunning() {
		synchronized (mSync) {
			return getState() == STATE_RECORDING;
		}
	}
	
	/**
	 * 録画サービスの状態をセット
	 * 状態が変化したときにはコールバックを呼び出す
	 * @param newState
	 */
	protected void setState(final int newState) {
		boolean changed;
		synchronized (mSync) {
			changed = mState != newState;
			mState = newState;
			mSync.notifyAll();
		}
		if (changed && !isDestroyed()) {
			try {
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
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
		}
	}
	
	/**
	 * 録画サービスの現在の状態フラグを取得
	 * @return
	 */
	protected int getState() {
		synchronized (mSync) {
			return mState;
		}
	}
	
	/**
	 * 録画サービスを自己終了できるかどうかを確認して
	 * 終了可能であればService#stopSelfを呼び出す
	 */
	protected void checkStopSelf() {
		if (DEBUG) Log.v(TAG, "checkStopSelf:");
		synchronized (mSync) {
			if (!isDestroyed() && canStopSelf(mIsBind | isRunning())) {
				if (DEBUG) Log.v(TAG, "stopSelf");
				setState(STATE_RELEASING);
				internalResetSettings();
				try {
					queueEvent(new Runnable() {
						@Override
						public void run() {
							releaseNotification(NOTIFICATION,
								getString(R.string.notification_service),
								R.mipmap.ic_recording_service, R.mipmap.ic_recording_service,
								R.string.notification_service, R.string.time_shift);
							stopSelf();
						}
					});
				} catch (final Exception e) {
					setState(STATE_UNINITIALIZED);
					Log.w(TAG, e);
				}
			}
		}
	}
	
	/**
	 * サービスを終了可能かどうかを確認
	 * @return 終了可能であればtrue
	 */
	protected boolean canStopSelf(final boolean isRunning) {
		if (DEBUG) Log.v(TAG, "canStopSelf:isRunning=" + isRunning
			+ ",isDestroyed=" + isDestroyed());
		return !isRunning;
	}
	
	/**
	 * サービスノティフィケーションを選択した時に実行されるPendingIntentの生成
	 * 普通はMainActivityを起動させる。
	 * デフォルトはnullを返すだけでノティフィケーションを選択しても何も実行されない。
	 * @return
	 */
	@Override
	protected PendingIntent contextIntent() {
		return null;
	}

	/**
	 * 録画の準備
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void prepare()
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "prepare:");
		synchronized (mSync) {
			if (getState() != STATE_INITIALIZED) {
				throw new IllegalStateException();
			}
			setState(STATE_PREPARING);
			try {
				if ((mWidth > 0) && (mHeight > 0)) {
					// 録画する時
					if (mFrameRate <= 0) {
						mFrameRate = VideoConfig.getCaptureFps();
					}
					if (mBpp <= 0) {
						mBpp = VideoConfig.getBitrate(mWidth, mHeight);
					}
					internalPrepare(mWidth, mHeight, mFrameRate, mBpp);
					createEncoder(mWidth, mHeight, mFrameRate, mBpp);
				}
				if (mAudioSampler != null) {
					mSampleRate = mAudioSampler.getSamplingFrequency();
					mChannelCount = mAudioSampler.getChannels();
				}
				if ((mSampleRate > 0)
					&& (mChannelCount == 1) || (mChannelCount == 2)) {
					// 録音する時
					internalPrepare(mSampleRate, mChannelCount);
					createEncoder(mSampleRate, mChannelCount);
				}
				setState(STATE_PREPARED);
			} catch (final IllegalStateException | IOException e) {
				releaseEncoder();
				throw e;
			}
		}
	}
	
	/**
	 * 録画準備の実態, mSyncをロックして呼び出される
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	protected void internalPrepare(final int width, final int height,
		final int frameRate, final float bpp)
			throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "internalPrepare:");
	}
	
	/**
	 * 録音準備の実態, mSyncをロックして呼び出される
	 * @param sampleRate
	 * @param channelCount
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	protected void internalPrepare(final int sampleRate, final int channelCount)
		throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "internalPrepare:");
	}
	
	/**
	 * 録画用のMediaCodecのエンコーダーを生成
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
		if (DEBUG) Log.v(TAG, "createEncoder:finished");
	}
	
	private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
	/**
	 * 録音用のMediaCodecエンコーダーを生成
	 * @param sampleRate
	 * @param channelCount
	 */
	protected void createEncoder(final int sampleRate, final int channelCount)
		throws IOException {

		if (DEBUG) Log.v(TAG, "createEncoder:");
		final MediaCodecInfo codecInfo = selectVideoCodec(AUDIO_MIME_TYPE);
		if (codecInfo == null) {
			throw new IOException("Unable to find an appropriate codec for " + AUDIO_MIME_TYPE);
		}
		final MediaFormat format = MediaFormat.createAudioFormat(
			AUDIO_MIME_TYPE, sampleRate, channelCount);
		// MediaCodecに適用するパラメータを設定する。
		// 誤った設定をするとMediaCodec#configureが復帰不可能な例外を生成する
		if (DEBUG) Log.d(TAG, "format: " + format);

		// 設定したフォーマットに従ってMediaCodecのエンコーダーを生成する
		mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
		mAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mAudioEncoder.start();
		mAudioReaper = new MediaReaper.AudioReaper(mAudioEncoder, mReaperListener,
			sampleRate, channelCount);
		if (DEBUG) Log.v(TAG, "createEncoder:finished");
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
		if (mAudioReaper != null) {
			mAudioReaper.release();
			mAudioReaper = null;
		}
		mAudioEncoder = null;
		internalResetSettings();
		if (DEBUG) Log.v(TAG, "releaseEncoder:finished");
	}

	/**
	 * 録画開始
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	public void start(@NonNull final String outputDir, @NonNull final String name)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:outputDir=" + outputDir);
		synchronized (mSync) {
			if ((mVideoFormat != null) || (mAudioFormat != null)) {
				if (checkFreeSpace(this, 0)) {
					final File dir = new File(outputDir);
					dir.mkdirs();
					internalStart(outputDir, name, mVideoFormat, mAudioFormat);
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
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void start(@NonNull final DocumentFile outputDir, @NonNull final String name)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:");
		synchronized (mSync) {
			if ((mVideoFormat != null) || (mAudioFormat != null)) {
				if (checkFreeSpace(this, 0)) {
					internalStart(outputDir, name, mVideoFormat, mAudioFormat);
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
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	protected abstract void internalStart(@NonNull final String outputDir,
		@NonNull final String name,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException;
	
	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	protected abstract void internalStart(@NonNull final DocumentFile outputDir,
		@NonNull final String name,
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
			switch (reaper.reaperType()) {
			case MediaReaper.REAPER_VIDEO:
				mVideoFormat = format;
				break;
			case MediaReaper.REAPER_AUDIO:
				mAudioFormat = format;
				break;
			}
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
			if (mAudioEncoder != null) {
				signalEndOfInputStream(mAudioEncoder);
			}
			internalStop();
			internalResetSettings();
		}
	}
	
	/**
	 * 録画終了の実態, mSyncをロックして呼ばれる
	 */
	protected abstract void internalStop();
	
	protected void internalResetSettings() {
		mWidth = mHeight = mFrameRate = -1;
		mBpp = -1.0f;
		mAudioSampler = null;
		mSampleRate = mChannelCount = -1;
		mIsEos = false;
	}
	
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
			if (mState == STATE_PREPARED) {
				frameAvailableSoon();
				return mInputSurface;
			} else {
				throw new IllegalStateException();
			}
		}
	}

	/**
	 * 入力映像が準備できた時に録画サービスへ通知するためのメソッド
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

	/**
	 * AudioSampleからのコールバックリスナー
	 */
	private final AudioSampler.SoundSamplerCallback mSoundSamplerCallback
		= new AudioSampler.SoundSamplerCallback() {

		@Override
		public void onData(final ByteBuffer buffer, final int size, final long presentationTimeUs) {
			final MediaReaper.AudioReaper reaper;
			final MediaCodec encoder;
    		synchronized (mSync) {
    			// 既に終了しているか終了指示が出てれば何もしない
				reaper = mAudioReaper;
				encoder = mAudioEncoder;
        		if (!isRunning() || (reaper == null) || (encoder == null)) return;
    		}
			if (size > 0) {
				// 音声データを受け取った時はエンコーダーへ書き込む
				try {
					encode(encoder, buffer, size, presentationTimeUs);
					reaper.frameAvailableSoon();
				} catch (final Exception e) {
					//
				}
			}
		}

		@Override
		public void onError(final Exception e) {
		}
	};

	/**
	 * バイト配列をエンコードする場合
	 * @param buffer
	 * @param length　書き込むバイト配列の長さ。0ならBUFFER_FLAG_END_OF_STREAMフラグをセットする
	 * @param presentationTimeUs [マイクロ秒]
	 */
	private void encode(@NonNull final MediaCodec encoder,
		@Nullable final ByteBuffer buffer, final int length, final long presentationTimeUs) {

		if (BuildCheck.isLollipop()) {
			encodeV21(encoder, buffer, length, presentationTimeUs);
		} else {
			@SuppressWarnings("deprecation")
			final ByteBuffer[] inputBuffers = encoder.getInputBuffers();
			for ( ; isRunning() && !mIsEos ;) {
				final int inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
				if (inputBufferIndex >= 0) {
					final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
					inputBuffer.clear();
					if (buffer != null) {
						inputBuffer.put(buffer);
					}
//	            	if (DEBUG) Log.v(TAG, "encode:queueInputBuffer");
					if (length <= 0) {
					// エンコード要求サイズが0の時はEOSを送信
						mIsEos = true;
//		            	if (DEBUG) Log.i(TAG, "send BUFFER_FLAG_END_OF_STREAM");
						encoder.queueInputBuffer(inputBufferIndex, 0, 0,
							presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					} else {
						encoder.queueInputBuffer(inputBufferIndex, 0, length,
							presentationTimeUs, 0);
					}
					break;
//				} else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					// 送れるようになるまでループする
					// MediaCodec#dequeueInputBufferにタイムアウト(10ミリ秒)をセットしているのでここでは待機しない
				}
			}
		}
	}
	
	/**
	 * バイト配列をエンコードする場合(API21/Android5以降)
	 * @param buffer
	 * @param length　書き込むバイト配列の長さ。0ならBUFFER_FLAG_END_OF_STREAMフラグをセットする
	 * @param presentationTimeUs [マイクロ秒]
	 */
	@SuppressLint("NewApi")
	private void encodeV21(@NonNull final MediaCodec encoder,
		@Nullable final ByteBuffer buffer, final int length, final long presentationTimeUs) {
	
		for ( ; isRunning() && !mIsEos ;) {
			final int inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
			if (inputBufferIndex >= 0) {
				final ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
				inputBuffer.clear();
				if (buffer != null) {
					inputBuffer.put(buffer);
				}
//	            if (DEBUG) Log.v(TAG, "encode:queueInputBuffer");
				if (length <= 0) {
				// エンコード要求サイズが0の時はEOSを送信
					mIsEos = true;
//	            	if (DEBUG) Log.i(TAG, "send BUFFER_FLAG_END_OF_STREAM");
					encoder.queueInputBuffer(inputBufferIndex, 0, 0,
						presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
				} else {
					encoder.queueInputBuffer(inputBufferIndex, 0, length,
						presentationTimeUs, 0);
				}
				break;
//			} else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
				// 送れるようになるまでループする
				// MediaCodec#dequeueInputBufferにタイムアウト(10ミリ秒)をセットしているのでここでは待機しない
			}
		}
	}

	/**
	 * 指定したMediaCodecエンコーダーへEOSを送る(音声エンコーダー用)
	 * @param encoder
	 */
	private void signalEndOfInputStream(@NonNull final MediaCodec encoder) {
//		if (DEBUG) Log.i(TAG, "signalEndOfInputStream:encoder=" + this);
        // MediaCodec#signalEndOfInputStreamはBUFFER_FLAG_END_OF_STREAMフラグを付けて
        // 空のバッファをセットするのと等価である
    	// ・・・らしいので空バッファを送る。encode内でBUFFER_FLAG_END_OF_STREAMを付けてセットする
        encode(encoder, null, 0, getInputPTSUs());
	}

}
