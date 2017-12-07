/*
 * Copyright (c) 2016-2017.  saki t_saki@serenegiant.com
 */

package com.serenegiant.media;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import com.serenegiant.service.AbstractRecorderService;
import com.serenegiant.service.AbstractServiceRecorder;
import com.serenegiant.service.AbstractTimeShiftRecService;

import java.io.IOException;

public class TimeShiftRecorder extends AbstractServiceRecorder {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = TimeShiftRecorder.class.getSimpleName();


	public TimeShiftRecorder(final Context context,
		@NonNull Class<? extends AbstractTimeShiftRecService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}

	protected void internalRelease() {
		stopTimeShift();
		super.internalRelease();
	}

	/**
	 * タイムシフトバッファリング開始
	 */
	public void startTimeShift() throws IOException {
		if (DEBUG) Log.v(TAG, "startTimeShift:");
		final AbstractRecorderService service = getService();
		if (service instanceof AbstractTimeShiftRecService) {
			((AbstractTimeShiftRecService) service).startTimeShift();
		}
	}

	/**
	 * タイムシフトバッファリング終了
	 */
	public void stopTimeShift() {
		if (DEBUG) Log.v(TAG, "stopTimeShift:");
		final AbstractRecorderService service = getService();
		if (service instanceof AbstractTimeShiftRecService) {
			((AbstractTimeShiftRecService) service).stopTimeShift();
		}
	}

//================================================================================
	/**
	 * タイムシフトバッファリング中かどうかを取得
	 * @return
	 */
	public boolean isTimeShift() {
		final AbstractRecorderService service = getService();
		return (service instanceof AbstractTimeShiftRecService)
			&& ((AbstractTimeShiftRecService) service).isTimeShift();
	}

}
