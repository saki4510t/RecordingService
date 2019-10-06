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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.documentfile.provider.DocumentFile;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.media.IAudioSampler;
import com.serenegiant.utils.BuildCheck;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * 録画サービスへアクセスするためのヘルパークラスのベースクラス
 *
 */
public abstract class AbstractServiceRecorder implements IServiceRecorder {
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = AbstractServiceRecorder.class.getSimpleName();

	private final WeakReference<Context> mWeakContext;
	private final Callback mCallback;
	private final Class<? extends AbstractRecorderService> mServiceClazz;
	protected final Object mServiceSync = new Object();
	private int mState = STATE_UNINITIALIZED;
	private volatile boolean mReleased = false;
	private AbstractRecorderService mService;
	
	@SuppressLint("NewApi")
	protected AbstractServiceRecorder(@NonNull final Context context,
		@NonNull Class<? extends AbstractRecorderService> serviceClazz,
		@NonNull final Callback callback) {

		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		mWeakContext = new WeakReference<Context>(context);
		mServiceClazz = serviceClazz;
		mCallback = callback;
		final Intent serviceIntent = createServiceIntent(context, serviceClazz);
		if (BuildCheck.isOreo()) {
			context.startForegroundService(serviceIntent);
		} else {
			context.startService(serviceIntent);
		}
		doBindService();
	}

	/**
	 * デストラクタ
	 * @throws Throwable
	 */
	@Override
	protected void finalize() throws Throwable {
		try {
			release();
		} finally {
			super.finalize();
		}
	}

	/**
	 * 関係するリソースを破棄する
	 * 実際に破棄される時に１回だけ呼び出す時は#releaseの代わりに
	 * #internalReleaseを使う
	 */
	@Override
	public void release() {
		if (!mReleased) {
			if (DEBUG) Log.v(TAG, "release:");
			mReleased = true;
			internalRelease();
		}
	}
	
	/**
	 * サービスとバインドして使用可能になっているかどうかを取得
	 * @return
	 */
	@Override
	public boolean isReady() {
		synchronized (mServiceSync) {
			return !mReleased && (mService != null);
		}
	}

	/**
	 * 録画中かどうかを取得
	 * @return
	 */
	@Override
	public boolean isRecording() {
		final AbstractRecorderService service = peekService();
		return !mReleased && (service != null) && service.isRecording();
	}

	/**
	 * 動画設定をセット
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @throws IllegalStateException
	 */
	@Override
	public void setVideoSettings(final int width, final int height,
		final int frameRate, final float bpp) throws IllegalStateException {

		if (DEBUG) Log.v(TAG, "setVideoSettings:");

		final AbstractRecorderService service = getService();
		if (service != null) {
			service.setVideoSettings(width, height, frameRate, bpp);
		}
	}

	/**
	 * 音声用のIAudioSamplerをセット
	 * #writeAudioFrameと排他使用
	 * @param sampler
	 * @throws IllegalStateException
	 */
	@Override
	public void setAudioSampler(@NonNull final IAudioSampler sampler)
		throws IllegalStateException {

		if (DEBUG) Log.v(TAG, "setAudioSampler:");
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.setAudioSampler(sampler);
		}
	}

	/**
	 * 音声設定をセット
	 * @param sampleRate
	 * @param channelCount
	 * @throws IllegalStateException
	 */
	@Override
	public void setAudioSettings(final int sampleRate, final int channelCount)
		throws IllegalStateException {

		if (DEBUG) Log.v(TAG, "setAudioSettings:");
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.setAudioSettings(sampleRate, channelCount);
		}
	}

	/**
	 * 録画録音の準備
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	@Override
	public void prepare() throws IllegalStateException, IOException {
		if (DEBUG) Log.v(TAG, "prepare:");

		final AbstractRecorderService service = getService();
		if (service != null) {
			service.prepare();
		}
	}
	
	/**
	 * 録画開始
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	@Override
	public void start(@NonNull final String outputDir, @NonNull final String name)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:outputDir=" + outputDir);
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.start(outputDir, name);
		} else {
			throw new IllegalStateException("start:service is not ready");
		}
	}

	/**
	 * 録画開始
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	@Override
	public void start(@NonNull final DocumentFile outputDir, @NonNull final String name)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:outputDir=" + outputDir);
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.start(outputDir, name);
		} else {
			throw new IllegalStateException("start:service is not ready");
		}
	}

	/**
	 * 録画終了
	 */
	@Override
	public void stop() {
		if (DEBUG) Log.v(TAG, "stop:");
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.stop();
		}
	}

	/**
	 * 録画用の映像を入力するためのSurfaceを取得
	 * @return
	 */
	@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
	@Nullable
	@Override
	public Surface getInputSurface() {
		if (DEBUG) Log.v(TAG, "getInputSurface:");
		checkReleased();
		final AbstractRecorderService service = getService();
		return service != null ? service.getInputSurface() : null;
	}
	
	/**
	 * 録画用の映像フレームが準備できた時に録画サービスへ通知するためのメソッド
	 */
	@Override
	public void frameAvailableSoon() {
//		if (DEBUG) Log.v(TAG, "frameAvailableSoon:");
		final AbstractRecorderService service = getService();
		if (!mReleased && (service != null)) {
			service.frameAvailableSoon();
		}
	}
	
	@Override
	public void writeAudioFrame(@NonNull final ByteBuffer buffer,
		final long presentationTimeUs) {

//		if (DEBUG) Log.v(TAG, "writeAudioFrame:");
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.writeAudioFrame(buffer, presentationTimeUs);
		}
	}
	
//================================================================================
	@NonNull
	protected Intent createServiceIntent(
		@NonNull Context context,
		@NonNull final Class<? extends AbstractRecorderService> serviceClazz) {
		return new Intent(serviceClazz.getName())
			.setPackage(context.getPackageName());
	}

	protected void internalRelease() {
		mCallback.onDisconnected();
		stop();
		doUnBindService();
	}
	
	/**
	 * Contextを取得する。
	 * @return
	 */
	@Nullable
	protected Context getContext() {
		return mWeakContext.get();
	}

	/** Bind client to camera connection service */
	protected void doBindService() {
		if (DEBUG) Log.v(TAG, "doBindService:");
		final Context context = mWeakContext.get();
		if (context != null) {
			synchronized (mServiceSync) {
				if ((mState == STATE_UNINITIALIZED) && (mService == null)) {
					mState = STATE_BINDING;
					final Intent intent = new Intent(mServiceClazz.getName());
					intent.setPackage(context.getPackageName());
					if (DEBUG) Log.v(TAG, "call Context#bindService");
					final boolean result = context.bindService(intent,
						mServiceConnection, Context.BIND_AUTO_CREATE);
					if (!result) {
						mState = STATE_UNINITIALIZED;
						Log.w(TAG, "failed to bindService");
					}
				}
			}
		}
	}

	/**
	 * Unbind from camera service
	 */
	protected void doUnBindService() {
		final boolean needUnbind;
		synchronized (mServiceSync) {
			needUnbind = mService != null;
			mService = null;
			if (mState == STATE_BIND) {
				mState = STATE_UNBINDING;
			}
		}
		if (needUnbind) {
			if (DEBUG) Log.v(TAG, "doUnBindService:");
			final Context context = mWeakContext.get();
			if (context != null) {
				if (DEBUG) Log.v(TAG, "call Context#unbindService");
				context.unbindService(mServiceConnection);
			}
		}
	}
	
	/**
	 * 接続中の録画サービスを取得する。
	 * バインド中なら待機する。
	 * @return
	 */
	@Nullable
	protected AbstractRecorderService getService() {
//		if (DEBUG) Log.v(TAG, "getService:");
		AbstractRecorderService result = null;
		synchronized (mServiceSync) {
			if ((mState == STATE_BINDING) || (mState == STATE_BIND)) {
				if (mService == null) {
					try {
						mServiceSync.wait();
					} catch (final InterruptedException e) {
						Log.w(TAG, e);
					}
				}
				result = mService;
			}
		}
//		if (DEBUG) Log.v(TAG, "getService:finished:" + result);
		return result;
	}
	
	/**
	 * 接続中の録画サービスを取得する。
	 * 接続中でも待機しない。
	 * @return
	 */
	@Nullable
	protected AbstractRecorderService peekService() {
		synchronized (mServiceSync) {
			return mService;
		}
	}
	
	/**
	 * Releaseされたかどうかをチェックして、
	 * ReleaseされていればIllegalStateExceptionを投げる
	 * @throws IllegalStateException
	 */
	protected void checkReleased() throws IllegalStateException {
		if (mReleased) {
			throw new IllegalStateException("already released");
		}
	}
	
	/**
	 * 録画サービスとの接続状態取得用のリスナーの実装
	 */
	private final ServiceConnection mServiceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(final ComponentName name, final IBinder service) {
			if (DEBUG) Log.v(TAG, "onServiceConnected:name=" + name);
			synchronized (mServiceSync) {
				if (mState == STATE_BINDING) {
					mState = STATE_BIND;
				}
				mService = getService(service);
				mServiceSync.notifyAll();
				if (mService != null) {
					mService.addListener(mStateChangeListener);
				}
			}
			mCallback.onConnected();
		}

		@Override
		public void onServiceDisconnected(final ComponentName name) {
			if (DEBUG) Log.v(TAG, "onServiceDisconnected:name=" + name);
			mCallback.onDisconnected();
			synchronized (mServiceSync) {
				if (mService != null) {
					mService.removeListener(mStateChangeListener);
				}
				mState = STATE_UNINITIALIZED;
				mService = null;
				mServiceSync.notifyAll();
			}
		}
	};
	
	/**
	 * 録画サービスと接続した際にIBinderからAbstractRecorderService
	 * (またはその継承クラス)を取得するためのメソッド
	 * @param service
	 * @return
	 */
	@NonNull
	protected abstract AbstractRecorderService getService(final IBinder service);
	
	/**
	 * 録画サービスの状態が変化したときのコールバックリスナーの実装
	 */
	private final AbstractRecorderService.StateChangeListener
		mStateChangeListener = new AbstractRecorderService.StateChangeListener() {
		@Override
		public void onStateChanged(
			@NonNull final AbstractRecorderService service, final int state) {
			
			switch (state) {
			case AbstractRecorderService.STATE_INITIALIZED:
				if (DEBUG) Log.v(TAG, "onStateChanged:STATE_INITIALIZED");
				break;
			case AbstractRecorderService.STATE_PREPARING:
				if (DEBUG) Log.v(TAG, "onStateChanged:STATE_PREPARING");
				break;
			case AbstractRecorderService.STATE_PREPARED:
				mCallback.onPrepared();
				break;
			case AbstractRecorderService.STATE_READY:
				if (DEBUG) Log.v(TAG, "onStateChanged:STATE_READY");
				mCallback.onReady();
				break;
			case AbstractRecorderService.STATE_RECORDING:
				if (DEBUG) Log.v(TAG, "onStateChanged:STATE_RECORDING");
				break;
			case AbstractRecorderService.STATE_RELEASING:
				if (DEBUG) Log.v(TAG, "onStateChanged:STATE_RELEASING");
				break;
			default:
				break;
			}
		}
	};
}
