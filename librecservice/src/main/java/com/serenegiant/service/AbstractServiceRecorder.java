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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

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
		public void onReady();
		public void onRelease();
	}

	private final WeakReference<Context> mWeakContext;
	private final Callback mCallback;
	private final Class<? extends AbstractRecorderService> mServiceClazz;
	protected final Object mServiceSync = new Object();
	private int mState = STATE_UNINITIALIZED;
	private volatile boolean mReleased = false;
	private AbstractRecorderService mService;
	
	protected AbstractServiceRecorder(@NonNull final Context context,
		@NonNull Class<? extends AbstractRecorderService> serviceClazz,
		@NonNull final Callback callback) {

		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		mWeakContext = new WeakReference<Context>(context);
		mServiceClazz = serviceClazz;
		mCallback = callback;
		doBindService();
	}

	/**
	 * デストラクタ
	 * @throws Throwable
	 */
	@Override
	protected void finalize() throws Throwable {
		if (DEBUG) Log.v(TAG, "finalize:");
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
		if (DEBUG) Log.v(TAG, "release:" + this);
		if (!mReleased) {
			mReleased = true;
			internalRelease();
		}
	}
	
	/**
	 * サービスとバインとして使用可能になっているかどうかを取得
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
	 * 録画開始
	 * @param outputPath 出力ファイル名
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void start(final String outputPath)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:");
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.start(outputPath);
		}
	}

	/**
	 * 録画開始
	 * @param accessId 出力ファイル名
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void start(final int accessId)
		throws IllegalStateException, IOException {

		if (DEBUG) Log.v(TAG, "start:");
		checkReleased();
		final AbstractRecorderService service = getService();
		if (service != null) {
			service.start(accessId);
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

//================================================================================
	protected void internalRelease() {
		mCallback.onRelease();
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
			}
			mCallback.onReady();
		}

		@Override
		public void onServiceDisconnected(final ComponentName name) {
			if (DEBUG) Log.v(TAG, "onServiceDisconnected:name=" + name);
			mCallback.onRelease();
			synchronized (mServiceSync) {
				mState = STATE_UNINITIALIZED;
				mService = null;
				mServiceSync.notifyAll();
			}
		}
	};

	protected abstract AbstractRecorderService getService(final IBinder service);
}
