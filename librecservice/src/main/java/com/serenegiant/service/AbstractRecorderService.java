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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.documentfile.provider.DocumentFile;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.librecservice.R;
import com.serenegiant.media.AbstractAudioEncoder;
import com.serenegiant.media.AudioSampler;
import com.serenegiant.media.IAudioSampler;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.media.VideoConfig;
import com.serenegiant.system.BuildCheck;
import com.serenegiant.utils.FileUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.serenegiant.media.MediaCodecUtils.*;

/**
 * TimeShiftRecServiceから流用できそうな部分を切り出し
 */
public abstract class AbstractRecorderService extends BaseService {
	private static final boolean DEBUG = false; // FIXME set false on production
	private static final String TAG = AbstractRecorderService.class.getSimpleName();

	private static final int NOTIFICATION = R.string.notification_service;
	protected static final long TIMEOUT_MS = 10;
	protected static final long TIMEOUT_USEC = TIMEOUT_MS * 1000L;	// 10ミリ秒

	// ステート定数, XXX 継承クラスは100以降を使う
	public static final int STATE_UNINITIALIZED = -1;
	public static final int STATE_INITIALIZED = 0;
	public static final int STATE_PREPARING = 1;
	public static final int STATE_PREPARED = 2;
	public static final int STATE_READY = 3;
	public static final int STATE_RECORDING = 4;
	public static final int STATE_RELEASING = 9;

	/**
	 * 録画サービスの状態が変更されたときのコールバックリスナー
	 */
	public interface StateChangeListener {
		public void onStateChanged(@NonNull final AbstractRecorderService service,
			final int state);
	}

//================================================================================
	@NonNull
	private final Set<StateChangeListener> mListeners
		= new CopyOnWriteArraySet<StateChangeListener>();
	private VideoConfig mVideoConfig;
	private Intent mIntent;
	private int mState = STATE_UNINITIALIZED;
	private boolean mIsBind;
	private volatile boolean mIsEos;
	private long mStartTime;
// 動画関係
	private volatile boolean mUseVideo;
	/** 動画のサイズ(録画する場合) */
	private int mWidth, mHeight;
	private int mFrameRate;
	private float mBpp;
	@Nullable
	private MediaFormat mVideoFormat;
	@Nullable
	private MediaCodec mVideoEncoder;
	@Nullable
	private Surface mInputSurface;
	@Nullable
	private MediaReaper.VideoReaper mVideoReaper;
// 音声関係
	private volatile boolean mUseAudio;
	@Nullable
	private IAudioSampler mAudioSampler;
	private boolean mIsOwnAudioSampler;
	private int mSampleRate, mChannelCount;
	@Nullable
	private MediaFormat mAudioFormat;
	@Nullable
	private MediaCodec mAudioEncoder;
	@Nullable
	private MediaReaper.AudioReaper mAudioReaper;

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
	public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
		if (DEBUG) Log.i(TAG, "onStartCommand:startId=" + startId + ": " + intent);
		super.onStartCommand(intent, flags, startId);
		return START_STICKY;
	}

	@Nullable
	@Override
	public IBinder onBind(@NonNull final Intent intent) {
		super.onBind(intent);
		if (DEBUG) Log.v(TAG, "onBind:intent=" + intent);
		if (intent != null) {
			showNotification(NOTIFICATION,
				getString(R.string.notification_service),
				R.mipmap.ic_recording_service, R.mipmap.ic_recording_service,
				getString(R.string.notification_service),
				getTitle(),
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

	/**
	 * UI側からサービスへアクセスするためのIBinder生成のヘルパーメソッド
	 * @return
	 */
	protected abstract IBinder getBinder();

	@Nullable
	protected Intent getIntent() {
		synchronized (mSync) {
			return mIntent;
		}
	}

	/**
	 * 通知領域に表示するコンテツタイトル取得のヘルパーメソッド
	 * @return
	 */
	@NonNull
	protected abstract String getTitle();

	public long getStartTimeMs() {
		return mStartTime;
	}
//--------------------------------------------------------------------------------
// BaseServiceの抽象メソッドをoverride
	@Override
	protected IntentFilter createIntentFilter() {
		return null;
	}

	@Override
	protected void onReceiveLocalBroadcast(final Context context, final Intent intent) {

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

//--------------------------------------------------------------------------------
	/**
	 * ステート変更時のコールバックリスナーを登録する
	 * @param listener
	 */
	void addListener(@Nullable final StateChangeListener listener) {
		if (DEBUG) Log.v(TAG, "addListener:" + listener);
		if (listener != null) {
			mListeners.add(listener);
		}
	}
	
	/**
	 * ステート変更時のコールバックリスナーを登録解除する
	 * @param listener
	 */
	void removeListener(@Nullable final StateChangeListener listener) {
		if (DEBUG) Log.v(TAG, "removeListener:" + listener);
		mListeners.remove(listener);
	}
	
	/**
	 * 録画設定
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 */
	void setVideoSettings(final int width, final int height,
		final int frameRate, final float bpp) throws IllegalStateException {
		
		if (DEBUG) Log.v(TAG,
			String.format("setVideoSettings:(%dx%d)@%d", width, height, frameRate));
		if (getState() != STATE_INITIALIZED) {
			throw new IllegalStateException();
		}
		mWidth = width;
		mHeight = height;
		mFrameRate = frameRate;
		mBpp = bpp;
		mUseVideo = true;
	}

	/**
	 * 録音用のIAudioSamplerをセット
	 * #writeAudioFrameと排他使用
	 * @param sampler
	 */
	void setAudioSampler(@NonNull final IAudioSampler sampler)
		throws IllegalStateException {

		if (DEBUG) Log.v(TAG, "setAudioSampler:" + sampler);
		if (getState() != STATE_INITIALIZED) {
			throw new IllegalStateException();
		}
		releaseOwnAudioSampler();
		if (mAudioSampler != sampler) {
			if (mAudioSampler != null) {
				mAudioSampler.removeCallback(mSoundSamplerCallback);
			}
			mAudioSampler = sampler;
			mSampleRate = mChannelCount = 0;
		}
	}
	
	/**
	 * 録音設定
	 * #setAudioSamplerで設置したIAudioSamplerの設定が優先される
	 * @param sampleRate
	 * @param channelCount
	 */
	void setAudioSettings(final int sampleRate, final int channelCount)
		throws IllegalStateException {

		if (DEBUG) Log.v(TAG,
			String.format("setAudioSettings:sampling=%d,channelCount=%d", sampleRate, channelCount));
		if (getState() != STATE_INITIALIZED) {
			throw new IllegalStateException();
		}
		if ((mSampleRate != sampleRate) || (mChannelCount != channelCount)) {
			createOwnAudioSampler(sampleRate, channelCount);
			mSampleRate = sampleRate;
			mChannelCount = channelCount;
		}
		mUseAudio = true;
	}

	/**
	 * 録音用の音声データを書き込む
	 * #setAudioSamplerと排他使用
	 * @param buffer position/limitを正しくセットしておくこと
	 * @param presentationTimeUs
	 */
	void writeAudioFrame(
		@NonNull final ByteBuffer buffer, final long presentationTimeUs)
			throws IllegalStateException, UnsupportedOperationException {
		
//		if (DEBUG) Log.v(TAG, "writeAudioFrame:");
		if (getState() < STATE_PREPARED) {
			throw new IllegalStateException();
		}
		if (mAudioSampler != null) {
			throw new UnsupportedOperationException("audioSampler is already set");
		}
		encodeAudio(buffer, buffer.limit(), presentationTimeUs);
	}

//================================================================================
	/**
	 * 録画設定を取得
	 * @return
	 */
	@NonNull
	protected VideoConfig requireConfig() {
		if (mVideoConfig == null) {
			mVideoConfig = new VideoConfig();
		}
		return mVideoConfig;
	}

	/**
	 * 録画中かどうかを取得する。
	 * このクラスでは#isRunningと同じ値を返すがこちらは
	 * オーバーライド不可
	 * @return
	 */
	public final boolean isRecording() {
		final int state = getState();
		return (state == STATE_PREPARING)
			|| (state == STATE_PREPARED)
			|| (state == STATE_READY)
			|| (state == STATE_RECORDING);
	}
	
	/**
	 * 録画サービスの処理を実行中かどうかを返す
	 * このクラスでは#isRecordingと同じ値を返す。
	 * こちらはオーバーライド可能でサービスの自己終了判定に使う。
	 * 録画中では無いが録画の前後処理中などで録画サービスを
	 * 終了しないようにしたい時に下位クラスでオーバーライドする。
	 * @return true: サービスの自己終了しない
	 */
	public boolean isRunning() {
		return isRecording();
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
				queueEvent(new Runnable() {
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
				try {
					queueEvent(new Runnable() {
						@Override
						public void run() {
							releaseNotification(NOTIFICATION,
								getString(R.string.notification_service),
								R.mipmap.ic_recording_service, R.mipmap.ic_recording_service,
								getString(R.string.notification_service),
								getTitle());
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
				if (DEBUG) Log.v(TAG, "prepare:start");
				if ((mWidth > 0) && (mHeight > 0)) {
					// 録画する時
					if (mFrameRate <= 0) {
						mFrameRate = requireConfig().captureFps();
					}
					if (mBpp <= 0) {
						mBpp = requireConfig().getBitrate(mWidth, mHeight);
					}
					internalPrepare(mWidth, mHeight, mFrameRate, mBpp);
					createEncoder(mWidth, mHeight, mFrameRate, mBpp);
				}
				if (mAudioSampler != null) {
					mSampleRate = mAudioSampler.getSamplingFrequency();
					mChannelCount = mAudioSampler.getChannels();
					mAudioSampler.addCallback(mSoundSamplerCallback);
				}
				if ((mSampleRate > 0)
					&& (mChannelCount == 1) || (mChannelCount == 2)) {
					// 録音する時
					internalPrepare(mSampleRate, mChannelCount);
					createEncoder(mSampleRate, mChannelCount);
				}
				if ((mAudioSampler != null)
					&& !mAudioSampler.isStarted()) {

					if (DEBUG) Log.v(TAG, "prepare:start audio sampler");
					mAudioSampler.start();
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
		if (DEBUG) Log.v(TAG, "internalPrepare:video");
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
		if (DEBUG) Log.v(TAG, "internalPrepare:audio");
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

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			createEncoderAPI18(width, height, frameRate, bpp);	// API >= 18
		} else {
			createEncoderAPI16(width, height, frameRate, bpp);	// API >= 16
		}
	}

	/**
	 * 録画用のMediaCodecのエンコーダーを生成(API>=16, ByteBufferを使う)
	 * FIXME 未実装
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @throws IOException
	 * @throws UnsupportedEncodingException
	 */
	protected void createEncoderAPI16(final int width, final int height,
		final int frameRate, final float bpp) throws IOException {

		throw new UnsupportedEncodingException("Not implement now for less than API18");
	}

	/**
	 * 録画用のMediaCodecのエンコーダーを生成(API>=18, 映像入力用Surfaceを使う)
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @throws IOException
	 */
	@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
	protected void createEncoderAPI18(final int width, final int height,
		final int frameRate, final float bpp) throws IOException {

		if (DEBUG) Log.v(TAG, "createEncoder:video");
		final MediaCodecInfo codecInfo = selectVideoEncoder(MIME_VIDEO_AVC);
		if (codecInfo == null) {
			throw new IOException("Unable to find an appropriate codec for " + MIME_VIDEO_AVC);
		}
		final MediaFormat format = createVideoFormatAPI18(width, height, frameRate, bpp);
		if (DEBUG) Log.d(TAG, "createEncoder:video format:" + format);
		// 設定したフォーマットに従ってMediaCodecのエンコーダーを生成する
		mVideoEncoder = MediaCodec.createEncoderByType(MIME_VIDEO_AVC);
		mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		// エンコーダーへの入力に使うSurfaceを取得する
		mInputSurface = mVideoEncoder.createInputSurface();	// API >= 18
		mVideoEncoder.start();
		mVideoReaper = new MediaReaper.VideoReaper(
			mVideoEncoder, mReaperListener, width, height);
		if (DEBUG) Log.v(TAG, "createEncoder:finished");
	}

	/**
	 * API>=18の録画用MediaFormatを生成する
	 * MIME_VIDEO_AVCを指定して生成すること
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @return
	 */
	@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
	protected MediaFormat createVideoFormatAPI18(
		final int width, final int height,
		final int frameRate, final float bpp) {

		final MediaFormat format = MediaFormat.createVideoFormat(MIME_VIDEO_AVC, width, height);
		// MediaCodecに適用するパラメータを設定する。
		// 誤った設定をするとMediaCodec#configureが復帰不可能な例外を生成する
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
			MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);	// API >= 18
		format.setInteger(MediaFormat.KEY_BIT_RATE,
			requireConfig().getBitrate(width, height, frameRate, bpp));
		format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);	// IFrameの間隔は1秒にする
		return format;
	}

	/**
	 * 録音用のMediaCodecエンコーダーを生成
	 * @param sampleRate
	 * @param channelCount
	 */
	protected void createEncoder(final int sampleRate, final int channelCount)
		throws IOException {

		if (DEBUG) Log.v(TAG, "createEncoder:audio");
		final MediaCodecInfo codecInfo = selectAudioEncoder(MIME_AUDIO_AAC);
		if (codecInfo == null) {
			throw new IOException("Unable to find an appropriate codec for " + MIME_AUDIO_AAC);
		}
		final MediaFormat format = createAudioFormat(sampleRate, channelCount);
		if (DEBUG) Log.d(TAG, "createEncoder:audio format:" + format);

		// 設定したフォーマットに従ってMediaCodecのエンコーダーを生成する
		mAudioEncoder = MediaCodec.createEncoderByType(MIME_AUDIO_AAC);
		mAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mAudioEncoder.start();
		mAudioReaper = new MediaReaper.AudioReaper(
			mAudioEncoder, mReaperListener, sampleRate, channelCount);
		if (DEBUG) Log.v(TAG, "createEncoder:finished");
	}

	/**
	 * 録音用エンコーダー初期化用のMediaFormatを生成する
	 * MIME_AUDIO_AACを指定して生成すること
	 * @param sampleRate
	 * @param channelCount
	 * @return
	 */
	protected MediaFormat createAudioFormat(final int sampleRate, final int channelCount) {
		final MediaFormat format = MediaFormat.createAudioFormat(
			MIME_AUDIO_AAC, sampleRate, channelCount);
		format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		format.setInteger(MediaFormat.KEY_CHANNEL_MASK,
			mChannelCount == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO);
		format.setInteger(MediaFormat.KEY_BIT_RATE, 64000/*FIXMEパラメータにする*/);
		format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, mChannelCount);
		// MediaCodecに適用するパラメータを設定する。
		// 誤った設定をするとMediaCodec#configureが復帰不可能な例外を生成する
		return format;
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
	 * エンコーダーへ終了要求
	 * 今は音声エンコーダーのみ#signalEndOfInputStreamを呼び出す
	 */
	protected void stopEncoder() {
		if (mAudioEncoder != null) {
			signalEndOfInputStream(mAudioEncoder);
		}
	}

	/**
	 * エンコーダーを破棄
	 */
	protected void releaseEncoder() {
		if (DEBUG) Log.v(TAG, "releaseEncoder:");
		final MediaReaper.VideoReaper videoReaper;
		final MediaReaper.AudioReaper audioReaper;
		synchronized (mSync) {
			videoReaper = mVideoReaper;
			mVideoReaper = null;
			audioReaper = mAudioReaper;
			mAudioReaper = null;
		}
		if (videoReaper != null) {
			videoReaper.release();
		}
		mVideoEncoder = null;
		mInputSurface = null;
		if (audioReaper != null) {
			audioReaper.release();
		}
		mAudioEncoder = null;
		releaseOwnAudioSampler();
		internalResetSettings();
		if (DEBUG) Log.v(TAG, "releaseEncoder:finished");
	}

	protected void createOwnAudioSampler(final int sampleRate, final int channelCount) {
		if (DEBUG) Log.v(TAG,
			String.format("createOwnAudioSampler:sampling=%d,channelCount=%d",
				sampleRate, channelCount));
		releaseOwnAudioSampler();
		if (sampleRate > 0 && ((channelCount == 1) || (channelCount == 2))) {
			if (DEBUG) Log.v(TAG, "createOwnAudioSampler:create AudioSampler");
			mAudioSampler = new AudioSampler(2,
				channelCount, sampleRate,
				AbstractAudioEncoder.SAMPLES_PER_FRAME,
				AbstractAudioEncoder.FRAMES_PER_BUFFER);
			mIsOwnAudioSampler = true;
		}
	}

	protected void releaseOwnAudioSampler() {
		if (DEBUG) Log.v(TAG,
			"releaseOwnAudioSampler:own=" + mIsOwnAudioSampler + "," + mAudioSampler);
		if (mAudioSampler != null) {
			mAudioSampler.removeCallback(mSoundSamplerCallback);
			if (mIsOwnAudioSampler) {
				mAudioSampler.release();
				mAudioSampler = null;
				mSampleRate = mChannelCount = 0;
			}
		}
		mIsOwnAudioSampler = false;
	}

	/**
	 * 録画開始
	 * @param output 出力ファイル
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void start(@Nullable final DocumentFile output)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:");
		synchronized (mSync) {
			if ((!mUseVideo || (mVideoFormat != null)) && (!mUseAudio || (mAudioFormat != null))) {
				mStartTime = System.currentTimeMillis();
				if (checkFreeSpace(this, 0)) {
					internalStart(output, mVideoFormat, mAudioFormat);
				} else {
					throw new IOException("No enough storage space!");
				}
			} else {
				throw new IllegalStateException("Not all MediaFormat received.");
			}
			setState(STATE_RECORDING);
		}
	}

	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param output 出力ファイル
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	protected abstract void internalStart(
		@Nullable final DocumentFile output,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException;
	
	/**
	 * 空き容量をチェック
	 * @param context
	 * @param accessId
	 * @return
	 */
	protected boolean checkFreeSpace(final Context context, final int accessId) {
		return BuildCheck.isAPI29()	// API29以降は対象範囲別ストレージなので容量のチェックができない
			|| FileUtils.checkFreeSpace(context,
				requireConfig().maxDuration(), System.currentTimeMillis(), accessId);
	}
	
	/**
	 * エンコード済みのフレームデータを書き出す
	 * mSyncをロックしていないので必要に応じでロックすること
	 * @param reaper
	 * @param byteBuf
	 * @param bufferInfo
	 * @param ptsUs この値は#getInputPTSUsから取得した値,
	 * 				BufferInfo#presentationTimeUsの値とは違うので
	 * 				必要に応じて#getInputPTSUsをoverrideして使い分けること
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
		public void writeSampleData(
			@NonNull final MediaReaper reaper,
			@NonNull final ByteBuffer byteBuf,
			@NonNull final MediaCodec.BufferInfo bufferInfo) {

//			if (DEBUG) Log.v(TAG, "writeSampleData:");
			try {
				final long ptsUs = getInputPTSUs();
				onWriteSampleData(reaper, byteBuf, bufferInfo, ptsUs);
			} catch (final IOException e) {
				AbstractRecorderService.this.onError(e);
			}
		}

		@Override
		public void onOutputFormatChanged(@NonNull final MediaReaper reaper,
			@NonNull final MediaFormat format) {

			if (DEBUG) Log.v(TAG, "onOutputFormatChanged:" + format);
			switch (reaper.reaperType()) {
			case MediaReaper.REAPER_VIDEO:
				mVideoFormat = format;
				break;
			case MediaReaper.REAPER_AUDIO:
				mAudioFormat = format;
				break;
			}
			if ((!mUseVideo || (mVideoFormat != null))
				&& (!mUseAudio || (mAudioFormat != null))) {

				// 映像と音声のMediaFormatがそろった
				setState(STATE_READY);
			}
		}

		@Override
		public void onStop(@NonNull final MediaReaper reaper) {
			if (DEBUG) Log.v(TAG, "onStop:");
			synchronized (mSync) {
				releaseEncoder();
			}
		}

		@Override
		public void onError(@NonNull final MediaReaper reaper, final Throwable t) {
			AbstractRecorderService.this.onError(t);
		}
	};

	protected void onError(final Throwable t) {
		if (DEBUG) Log.v(TAG, "onError:");
		Log.w(TAG, t);
	}
	
	/**
	 * 録画終了
	 */
	public void stop() {
		if (DEBUG) Log.v(TAG, "stop:");
		synchronized (mSync) {
			stopEncoder();
			internalStop();
			internalResetSettings();
		}
	}
	
	/**
	 * 録画終了の実態, mSyncをロックして呼ばれる
	 */
	protected abstract void internalStop();
	
	/**
	 * mSyncをロックして呼ばれる
	 */
	protected void internalResetSettings() {
		if (DEBUG) Log.v(TAG, "internalResetSettings:");
		mWidth = mHeight = mFrameRate = -1;
		mBpp = -1.0f;
		mIsEos = mUseVideo = mUseAudio = false;
	}
	
	/**
	 * MediaScannerConnection.scanFileを呼び出す
 	 * @param outputPath
	 */
	protected void scanFile(@NonNull final String outputPath) {
		if (DEBUG) Log.v(TAG, "scanFile:" + outputPath);
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
	@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
	@Nullable
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
		}
	}

	/**
	 * AudioSampleからのコールバックリスナー
	 */
	private final AudioSampler.SoundSamplerCallback mSoundSamplerCallback
		= new AudioSampler.SoundSamplerCallback() {

		@Override
		public void onData(
			@NonNull final ByteBuffer buffer, final int size,
			final long presentationTimeUs) {

			encodeAudio(buffer, size, presentationTimeUs);
		}

		@Override
		public void onError(final Exception e) {
		}
	};

	/**
	 * 音声データをエンコード
	 * 既に終了しているか終了指示が出てれば何もしない
	 * @param buffer
	 * @param size
	 * @param presentationTimeUs
	 */
	protected void encodeAudio(
		@NonNull final ByteBuffer buffer, final int size,
		final long presentationTimeUs) {

		final MediaReaper.AudioReaper reaper;
		final MediaCodec encoder;
   		synchronized (mSync) {
   			// 既に終了しているか終了指示が出てれば何もしない
			reaper = mAudioReaper;
			encoder = mAudioEncoder;
   		}
		if (!isRunning() || (reaper == null) || (encoder == null)) {
			if (DEBUG) Log.d(TAG,
				"encodeAudio:isRunning=" + isRunning()
				+ ",reaper=" + reaper + ",encoder=" + encoder);
			return;
		}
		if (size > 0) {
			// 音声データを受け取った時はエンコーダーへ書き込む
			try {
				encode(encoder, buffer, size, presentationTimeUs);
				reaper.frameAvailableSoon();
			} catch (final Exception e) {
				if (DEBUG) Log.w(TAG, e);
				// ignore
			}
		} else if (DEBUG) {
			Log.v(TAG, "encodeAudio:zero length");
		}
	}
	
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
			final ByteBuffer[] inputBuffers = encoder.getInputBuffers();
			while (isRunning() && !mIsEos) {
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
			} // end of while
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
	
		while (isRunning() && !mIsEos) {
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
		} // end of while
	}

	/**
	 * 指定したMediaCodecエンコーダーへEOSを送る(音声エンコーダー用)
	 * @param encoder
	 */
	private void signalEndOfInputStream(@NonNull final MediaCodec encoder) {
		if (DEBUG) Log.i(TAG, "signalEndOfInputStream:encoder=" + encoder);
        // MediaCodec#signalEndOfInputStreamはBUFFER_FLAG_END_OF_STREAMフラグを付けて
        // 空のバッファをセットするのと等価である
    	// ・・・らしいので空バッファを送る。encode内でBUFFER_FLAG_END_OF_STREAMを付けてセットする
        encode(encoder, null, 0, getInputPTSUs());
	}

}
