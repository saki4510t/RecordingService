package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2019 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
*/

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;
import androidx.fragment.app.Fragment;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.serenegiant.dialog.MessageDialogFragmentV4;
import com.serenegiant.utils.BuildCheck;
import com.serenegiant.utils.HandlerThreadHandler;
import com.serenegiant.utils.PermissionCheck;
import com.serenegiant.utils.SDUtils;
import com.serenegiant.utils.Stacktrace;

import java.util.Arrays;

public abstract class BaseFragment extends Fragment
	implements MessageDialogFragmentV4.MessageDialogListener {

	private static final boolean DEBUG = true; // set false on production
	private static final String TAG = BaseFragment.class.getSimpleName();

	/** タッチフィードバックでViewの色を変更した時に元に戻すまでの時間[ミリ秒] */
	private static final long APP_TOUCH_FEEDBACK_RESET_DELAY_MS = 200;
	/** 通知メッセージを非表示にするまでの時間 */
	private static final long APP_DURATION_NOTIFICATION_MESSAGE = 2500;
	/** 通知メッセージをフェードアウトするときのフェードアウト時間  */
	private static final long APP_DURATION_NOTIFICATION_MESSAGE_HIDE = 500;
	private static final int REQUEST_ACCESS_SD = 123456;

	protected volatile boolean isDestroyed;
	private Toast mToast;
	private final Object mHandlerSync = new Object();
	/** ワーカースレッド上で処理するためのHandler */
	private Handler mWorkerHandler;
	private long mWorkerThreadID = -1;
	/** UI操作のためのHandler */
	private final Handler mUIHandler = new Handler(Looper.getMainLooper());
	private final Thread mUiThread = mUIHandler.getLooper().getThread();


	public BaseFragment() {
		super();
		// デフォルトコンストラクタが必要
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (DEBUG) Log.v(TAG, "onCreate:");
		synchronized (mHandlerSync) {
			if (mWorkerHandler == null) {
				mWorkerHandler = HandlerThreadHandler.createHandler(TAG);
				mWorkerThreadID = mWorkerHandler.getLooper().getThread().getId();
			}
		}
	}

	@Override
	public final void onStart() {
		super.onStart();
		if (DEBUG) Log.v(TAG, "onStart:");
		if (BuildCheck.isAndroid7()) {
			internalOnResume();
		}
	}

	@Override
	public final void onResume() {
		super.onResume();
		if (DEBUG) Log.v(TAG, "onResume:");
		if (!BuildCheck.isAndroid7()) {
			internalOnResume();
		}
	}

	@Override
	public final void onPause() {
		if (DEBUG) Log.v(TAG, "onPause:");
		if (!BuildCheck.isAndroid7()) {
			internalOnPause();
		}
		super.onPause();
	}

	@Override
	public final void onStop() {
		if (DEBUG) Log.v(TAG, "onStop:");
		if (BuildCheck.isAndroid7()) {
			internalOnPause();
		}
		super.onStop();
	}

	@Override
	public synchronized void onDestroy() {
		if (DEBUG) Log.v(TAG, "onDestroy:");
		// ワーカースレッドを破棄
		synchronized (mHandlerSync) {
			mUIHandler.removeCallbacksAndMessages(null);
			if (mWorkerHandler != null) {
				mWorkerHandler.removeCallbacksAndMessages(null);
				mWorkerHandler.getLooper().quit();
				mWorkerHandler = null;
			}
		}
		super.onDestroy();
	}

	/**
	 * Android6未満でのonResume, Android7以上でのonStartの処理
	 * この中からonResumeやonStartを呼んでは行けない(無限ループになる)
	 * onResumeとonStartはこのクラスでfinalにしてあるので小クラスで
	 * 追加処理が必要な場合にはこのメソッドをoverrideすること
	 */
	protected void internalOnResume() {
		if (DEBUG) Log.v(TAG, "internalOnResume:");
	}

	/**
	 * Android6未満でのonPause, Android7以上でのonStopの処理
	 * この中からonPauseやonTopを呼んでは行けない(無限ループになる)
	 * onPauseとonStopはこのクラスでfinalにしてあるので小クラスで
	 * 追加処理が必要な場合にはこのメソッドをoverrideすること
	 */
	protected void internalOnPause() {
		if (DEBUG) Log.v(TAG, "internalOnPause:");
		clearToast();
		isDestroyed = true;
	}

	/**
	 * Viewにタッチした時のビジュアルフィードバック用
	 * ViewがImageViewまたはその子クラスだった時に単に色を一定時間変えるだけ
	 * @param view
	 * @param color
	 * @param reset_color
	 */
	protected void setTouchFeedback(final View view, final int color, final int reset_color) {
		if (DEBUG) Log.v(TAG, "setTouchFeedback:");
		if (view instanceof ImageView) {
			((ImageView)view).setColorFilter(color);
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					((ImageView)view).setColorFilter(reset_color);
				}
			}, APP_TOUCH_FEEDBACK_RESET_DELAY_MS);
		}
	}

	protected void popBackStack() {
		if (DEBUG) Log.v(TAG, "popBackStack:");
		try {
			getFragmentManager().popBackStack();
		} catch (final Exception e) {
			Log.w(TAG, e);
		}
	}

	@NonNull
	protected LayoutInflater getThemedLayoutInflater(
		@NonNull final LayoutInflater inflater, @StyleRes final int layout_style) {
		
		if (DEBUG) Log.v(TAG, "getThemedLayoutInflater:");
		final Activity context = getActivity();
		// create ContextThemeWrapper from the original Activity Context with the custom theme
		final Context contextThemeWrapper = new ContextThemeWrapper(context, layout_style);
		// clone the inflater using the ContextThemeWrapper
		return inflater.cloneInContext(contextThemeWrapper);
	}

//================================================================================
	protected boolean isReleased() {
		synchronized (mHandlerSync) {
			return isDestroyed || (mWorkerHandler == null);
		}
	}
	
	protected void checkReleased() throws IllegalStateException {
		if (isReleased()) {
			Stacktrace.print();
			throw new IllegalStateException("already released");
		}
	}
	
	/**
	 * UIスレッドでRunnableを実行するためのヘルパーメソッド
	 * @param task
	 */
	public final void runOnUiThread(@NonNull final Runnable task) {
		runOnUiThread(task, 0L);
	}

	/**
	 * UIスレッドでRunnableを実行するためのヘルパーメソッド
	 * @param task
	 * @param duration
	 */
	public final void runOnUiThread(@NonNull final Runnable task, final long duration) {
		mUIHandler.removeCallbacks(task);
		if ((duration > 0) || Thread.currentThread() != mUiThread) {
			mUIHandler.postDelayed(task, duration);
		} else {
			try {
				task.run();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
		}
	}

	/**
	 * UIスレッド上で指定したRunnableが実行待ちしていれば実行待ちを解除する
	 * @param task
	 */
	public final void removeFromUiThread(final Runnable task) {
		if (task == null) return;
		mUIHandler.removeCallbacks(task);
	}

	/**
	 * ワーカースレッド上で指定したRunnableを実行する
	 * 未実行の同じRunnableがあればキャンセルされる(後から指定した方のみ実行される)
	 * @param task
	 * @param delayMillis
	 */
	protected final synchronized void queueEvent(final Runnable task, final long delayMillis) {
		synchronized (mHandlerSync) {
			if ((task == null) || (mWorkerHandler == null)) return;
			try {
				mWorkerHandler.removeCallbacks(task);
				if (delayMillis > 0) {
					mWorkerHandler.postDelayed(task, delayMillis);
				} else if (mWorkerThreadID == Thread.currentThread().getId()) {
					task.run();
				} else {
					mWorkerHandler.post(task);
				}
			} catch (final Exception e) {
				// ignore
			}
		}
	}

	/**
	 * 指定したRunnableをワーカースレッド上で実行予定であればキャンセルする
	 * @param task
	 */
	protected final synchronized void removeEvent(final Runnable task) {
		if (task == null) return;
		synchronized (mHandlerSync) {
			try {
				mWorkerHandler.removeCallbacks(task);
			} catch (final Exception e) {
				// ignore
			}
		}
	}

//================================================================================
	/**
	 * Toastでメッセージを表示
	 * @param msg
	 * @param args
	 */
	protected void showToast(final String msg, final Object... args) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					if (mToast != null) {
						mToast.cancel();
						mToast = null;
					}
					if (args != null) {
						mToast = Toast.makeText(getActivity(), String.format(msg, args), Toast.LENGTH_SHORT);
					} else {
						mToast = Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT);
					}
					mToast.show();
				} catch (final Exception e) {
					// ignore
				}
			}
		}, 0);
	}

	/**
	 * Toastでメッセージを表示
	 * @param msg
	 */
	protected void showToast(@StringRes final int msg, final Object... args) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					if (mToast != null) {
						mToast.cancel();
						mToast = null;
					}
					if (args != null) {
						final String _msg = getString(msg, args);
						mToast = Toast.makeText(getActivity(), _msg, Toast.LENGTH_SHORT);
					} else {
						mToast = Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT);
					}
					mToast.show();
				} catch (final Exception e) {
					// ignore
				}
			}
		}, 0);
	}

	/**
	 * Toastが表示されていればキャンセルする
	 */
	protected void clearToast() {
		try {
			if (mToast != null) {
				mToast.cancel();
				mToast = null;
			}
		} catch (final Exception e) {
			// ignore
		}
	}

//================================================================================
	/**
	 * MessageDialogFragmentメッセージダイアログからのコールバックリスナー
	 * @param dialog
	 * @param requestCode
	 * @param permissions
	 * @param result
	 */
	@SuppressLint("NewApi")
	@Override
	public void onMessageDialogResult(final MessageDialogFragmentV4 dialog,
		final int requestCode, final String[] permissions, final boolean result) {

		if (DEBUG) Log.v(TAG, "onMessageDialogResult:" + result + "," + Arrays.toString(permissions));
		if (result) {
			// メッセージダイアログでOKを押された時はパーミッション要求する
			if (BuildCheck.isMarshmallow()) {
				requestPermissions(permissions, requestCode);
				return;
			}
		}
		// メッセージダイアログでキャンセルされた時とAndroid6でない時は自前でチェックして#checkPermissionResultを呼び出す
		final Context context = getActivity();
		for (final String permission: permissions) {
			checkPermissionResult(requestCode, permission,
				PermissionCheck.hasPermission(context, permission));
		}
	}

	/**
	 * パーミッション要求結果を受け取るためのメソッド
	 * @param requestCode
	 * @param permissions
	 * @param grantResults
	 */
	@Override
	public void onRequestPermissionsResult(final int requestCode,
		@NonNull final String[] permissions, @NonNull final int[] grantResults) {

		if (DEBUG) Log.v(TAG, "onRequestPermissionsResult:" + Arrays.toString(permissions));
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);	// 何もしてないけど一応呼んどく
		final int n = Math.min(permissions.length, grantResults.length);
		for (int i = 0; i < n; i++) {
			checkPermissionResult(requestCode, permissions[i],
				grantResults[i] == PackageManager.PERMISSION_GRANTED);
		}
	}

	/**
	 * パーミッション要求の結果をチェック
	 * ここではパーミッションを取得できなかった時にToastでメッセージ表示するだけ
	 * @param requestCode
	 * @param permission
	 * @param result
	 */
	protected void checkPermissionResult(final int requestCode, final String permission, final boolean result) {
		// パーミッションがないときにはメッセージを表示する
		if (!result && (permission != null)) {
			if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
				showToast(R.string.permission_audio);
			}
			if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
				showToast(R.string.permission_ext_storage);
			}
			if (Manifest.permission.CAMERA.equals(permission)) {
				showToast(R.string.permission_camera);
			}
			if (Manifest.permission.INTERNET.equals(permission)) {
				showToast(R.string.permission_network);
			}
		}
	}

	// 動的パーミッション要求時の要求コード
	protected static final int REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE = 0x1234;
	protected static final int REQUEST_PERMISSION_AUDIO_RECORDING = 0x2345;
	protected static final int REQUEST_PERMISSION_NETWORK = 0x3456;
	protected static final int REQUEST_PERMISSION_CAMERA = 0x5678;

	/**
	 * 外部ストレージへの書き込みパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true 外部ストレージへの書き込みパーミッションが有る
	 */
	protected boolean checkPermissionWriteExternalStorage() {
		if (!PermissionCheck.hasWriteExternalStorage(getActivity())) {
			MessageDialogFragmentV4.showDialog(this, REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE,
				R.string.permission_title, R.string.permission_ext_storage_request,
				new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
			return false;
		}
		return true;
	}

	/**
	 * 録音のパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true 録音のパーミッションが有る
	 */
	protected boolean checkPermissionAudio() {
		if (!PermissionCheck.hasAudio(getActivity())) {
			MessageDialogFragmentV4.showDialog(this, REQUEST_PERMISSION_AUDIO_RECORDING,
				R.string.permission_title, R.string.permission_audio_recording_request,
				new String[]{Manifest.permission.RECORD_AUDIO});
			return false;
		}
		return true;
	}

	/**
	 * カメラのパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true カメラのパーミッションが有る
	 */
	protected boolean checkPermissionCamera() {
		if (!PermissionCheck.hasCamera(getActivity())) {
			MessageDialogFragmentV4.showDialog(this, REQUEST_PERMISSION_CAMERA,
				R.string.permission_title, R.string.permission_camera,
				new String[]{Manifest.permission.CAMERA});
			return false;
		}
		return true;
	}

	/**
	 * ネットワークアクセスのパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true ネットワークアクセスのパーミッションが有る
	 */
	protected boolean checkPermissionNetwork() {
		if (!PermissionCheck.hasNetwork(getActivity())) {
			MessageDialogFragmentV4.showDialog(this, REQUEST_PERMISSION_NETWORK,
				R.string.permission_title, R.string.permission_network_request,
				new String[]{Manifest.permission.INTERNET});
			return false;
		}
		return true;
	}

	/**
	 * 録画に必要なパーミッションを持っているかどうか
	 * パーミッションの要求はしない
	 * @return
	 */
	protected boolean hasPermission() {
		final Activity activity = getActivity();

		if ((activity == null) || activity.isFinishing()) {
			return false;
		}

		return (SDUtils.hasStorageAccess(activity, REQUEST_ACCESS_SD)
			|| PermissionCheck.hasWriteExternalStorage(activity))
			&& PermissionCheck.hasAudio(activity)
			&& PermissionCheck.hasCamera(activity);
	}
}
