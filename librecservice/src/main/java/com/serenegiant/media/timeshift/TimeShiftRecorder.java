/*
 * Copyright (c) 2016-2017.  saki t_saki@serenegiant.com
 */

package com.serenegiant.media.timeshift;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import com.serenegiant.media.AbstractRecorderService;
import com.serenegiant.media.AbstractServiceRecorder;

import java.io.IOException;

public class TimeShiftRecorder extends AbstractServiceRecorder {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = TimeShiftRecorder.class.getSimpleName();


	public TimeShiftRecorder(final Context context,
		@NonNull Class<? extends AbstractRecorderService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}

	protected void internalRelease() {
		stop();
		super.internalRelease();
	}

	/**
	 * タイムシフトバッファリング開始
	 */
	public void start() throws IOException {
		if (DEBUG) Log.v(TAG, "start:");
		final AbstractRecorderService service = getService();
		if (service instanceof AbstractTimeShiftRecService) {
			((AbstractTimeShiftRecService) service).start();
		}
	}

	/**
	 * タイムシフトバッファリング終了
	 */
	public void stop() {
		if (DEBUG) Log.v(TAG, "stop:");
		final AbstractRecorderService service = getService();
		if (service instanceof AbstractTimeShiftRecService) {
			((AbstractTimeShiftRecService) service).stop();
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
