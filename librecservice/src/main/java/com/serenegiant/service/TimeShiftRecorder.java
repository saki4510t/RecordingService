package com.serenegiant.service;
/*
 * Copyright (c) 2016-2018.  saki t_saki@serenegiant.com
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

import android.content.Context;
import android.os.IBinder;
import androidx.annotation.NonNull;
import android.util.Log;

import java.io.IOException;

/**
 * タイムシフト録画サービスアクセス用のヘルパークラス
 * #prepare => #startTimeShift => [#start => #stop] => #stopTimeShift => #release
 */
public class TimeShiftRecorder extends AbstractServiceRecorder
	implements ITimeShiftRecorder {

	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = TimeShiftRecorder.class.getSimpleName();
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param serviceClazz
	 * @param callback
	 */
	public TimeShiftRecorder(final Context context,
		@NonNull Class<? extends TimeShiftRecService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}

	/**
	 * タイムシフトバッファリング開始
	 */
	@Override
	public void startTimeShift() throws IOException {
		if (DEBUG) Log.v(TAG, "startTimeShift:");
		final AbstractRecorderService service = getService();
		if (service instanceof TimeShiftRecService) {
			((TimeShiftRecService) service).startTimeShift();
		}
	}

	/**
	 * タイムシフトバッファリング終了
	 */
	@Override
	public void stopTimeShift() {
		if (DEBUG) Log.v(TAG, "stopTimeShift:");
		final AbstractRecorderService service = getService();
		if (service instanceof TimeShiftRecService) {
			((TimeShiftRecService) service).stopTimeShift();
		}
	}

	/**
	 * タイムシフトバッファリング中かどうかを取得
	 * @return
	 */
	@Override
	public boolean isTimeShift() {
		final AbstractRecorderService service = getService();
		return (service instanceof TimeShiftRecService)
			&& ((TimeShiftRecService) service).isTimeShift();
	}

	/**
	 * キャッシュサイズを指定
	 * @param cacheSize
	 * @throws IllegalStateException
	 * @throws IllegalArgumentException
	 */
	@Override
	public void setCacheSize(final int cacheSize)
		throws IllegalStateException, IllegalArgumentException {

		if (DEBUG) Log.v(TAG, "setCacheSize:");
		final AbstractRecorderService service = getService();
		if (service instanceof TimeShiftRecService) {
			((TimeShiftRecService) service).setCacheSize(cacheSize);
		}
	}

	/**
	 * キャッシュ場所を指定, パーミッションが有ってアプリから書き込めること
	 * @param cacheDir
	 * @throws IllegalStateException prepareよりも後には呼べない
	 * @throws IllegalArgumentException パーミッションが無い
	 * 									あるいは存在しない場所など書き込めない時
	 */
	@Override
	public void setCacheDir(@NonNull final String cacheDir)
		throws IllegalStateException, IllegalArgumentException {

		if (DEBUG) Log.v(TAG, "setCacheDir:");
		final AbstractRecorderService service = getService();
		if (service instanceof TimeShiftRecService) {
			((TimeShiftRecService) service).setCacheDir(cacheDir);
		}
	}

//================================================================================
	@Override
	protected void internalRelease() {
		stopTimeShift();
		super.internalRelease();
	}
	
	/**
	 * 録画サービスと接続した際にIBinderからAbstractRecorderService
	 * (またはその継承クラス)を取得するためのメソッド
	 * @param service
	 * @return
	 */
	@NonNull
	@Override
	protected AbstractRecorderService getService(final IBinder service) {
		return ((TimeShiftRecService.LocalBinder)service).getService();
	}

}
