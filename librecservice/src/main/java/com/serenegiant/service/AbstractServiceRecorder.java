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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.provider.DocumentFile;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.utils.BuildCheck;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Created by saki on 2017/12/05.
 *
 */
public abstract class AbstractServiceRecorder {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = AbstractServiceRecorder.class.getSimpleName();

	private static final int STATE_UNINITIALIZED = 0;
	private static final int STATE_BINDING = 1;
	private static final int STATE_BIND = 2;
	private static final int STATE_UNBINDING = 3;

	public interface Callback {
		public void onConnected();
		public void onPrepared();
		public void onReady();
		public void onDisconnected();
	}

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
		final Intent serviceIntent = new Intent(serviceClazz.getName());
		serviceIntent.setPackage(context.getPackageName());
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
	public boolean isReady() {
		synchronized (mServiceSync) {
			return !mReleased && (mService != null);
		}
	}

	/**
	 * 録画中かどうかを取得
	 * @return
	 */
	public boolean isRecording() {
		final AbstractRecorderService service = peekService();
		return !mReleased && (service != null) && service.isRecording();
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

		final AbstractRecorderService service = getService();
		if (service != null) {
			service.prepare(width, height, frameRate, bpp);
		}
	}

	/**
	 * 録画開始
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void start(@NonNull final String outputDir, @NonNull final String name)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:outputDir=" + outputDir);
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.start(outputDir, name);
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
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.start(outputDir, name);
		}
	}

	/**
	 * 録画終了
	 */
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
	public Surface getInputSurface() {
		if (DEBUG) Log.v(TAG, "getInputSurface:");
		checkReleased();
		final AbstractRecorderService service = getService();
		return service != null ? service.getInputSurface() : null;
	}
	
	/**
	 * 録画用の映像フレームが準備できた時に呼び出す
	 */
	public void frameAvailableSoon() {
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.frameAvailableSoon();
		}
	}

//================================================================================
	protected void internalRelease() {
		mCallback.onDisconnected();
		stop();
		doUnBindService();
	}
	
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
//			if (mService != null) {
//				mService.removeCallback(mISensorCallback);
//			}
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

	protected AbstractRecorderService peekService() {
		synchronized (mServiceSync) {
			return mService;
		}
	}

	protected void checkReleased() {
		if (mReleased) {
			throw new IllegalStateException("already released");
		}
	}

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

	protected abstract AbstractRecorderService getService(final IBinder service);
	
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
