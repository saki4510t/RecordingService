package com.serenegiant.service;

import android.content.Context;
import android.os.IBinder;

import androidx.annotation.NonNull;

public class SimpleServiceRecorder extends AbstractServiceRecorder {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = SimpleServiceRecorder.class.getSimpleName();

	public static SimpleServiceRecorder newInstance(@NonNull final Context context,
		@NonNull final Class<? extends SimpleRecorderService> serviceClazz,
		@NonNull final Callback callback) {

		return new SimpleServiceRecorder(context, serviceClazz, callback);
	}

//================================================================================

	protected SimpleServiceRecorder(@NonNull final Context context,
		@NonNull final Class<? extends SimpleRecorderService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}

	@NonNull
	@Override
	protected AbstractRecorderService getService(final IBinder service) {
		return ((SimpleRecorderService.LocalBinder)service).getService();
	}
}
