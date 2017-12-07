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

import android.content.Context;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;

/**
 * タイムシフト録画サービスアクセス用のヘルパークラス
 * #startTimeShift => [#start => #stop] => #stopTimeShift
 */
public class TimeShiftRecorder extends AbstractServiceRecorder {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = TimeShiftRecorder.class.getSimpleName();


	public TimeShiftRecorder(final Context context,
		@NonNull Class<? extends TimeShiftRecService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}

	/**
	 * タイムシフトバッファリング開始
	 */
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
	public boolean isTimeShift() {
		final AbstractRecorderService service = getService();
		return (service instanceof TimeShiftRecService)
			&& ((TimeShiftRecService) service).isTimeShift();
	}

//================================================================================
	protected void internalRelease() {
		stopTimeShift();
		super.internalRelease();
	}
	
	@Override
	protected AbstractRecorderService getService(final IBinder service) {
		return ((TimeShiftRecService.LocalBinder)service).getService();
	}
	

}
