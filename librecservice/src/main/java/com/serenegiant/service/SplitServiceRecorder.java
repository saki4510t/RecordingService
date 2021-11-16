package com.serenegiant.service;

import android.content.Context;
import android.os.IBinder;

import androidx.annotation.NonNull;

public class SplitServiceRecorder extends AbstractServiceRecorder {
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = SplitServiceRecorder.class.getSimpleName();

	public static SplitServiceRecorder newInstance(@NonNull final Context context,
		@NonNull final Class<? extends SplitRecorderService> serviceClazz,
		@NonNull final Callback callback) {

		return new SplitServiceRecorder(context, serviceClazz, callback);
	}

//================================================================================

	protected SplitServiceRecorder(@NonNull final Context context,
		@NonNull final Class<? extends SplitRecorderService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}

	@NonNull
	@Override
	protected AbstractRecorderService getService(final IBinder service) {
		return ((SplitRecorderService.LocalBinder)service).getService();
	}
}
