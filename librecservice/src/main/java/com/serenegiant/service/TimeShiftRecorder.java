/*
 * Copyright (c) 2016-2017.  saki t_saki@serenegiant.com
 */

package com.serenegiant.service;

import android.content.Context;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;

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
