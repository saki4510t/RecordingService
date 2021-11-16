package com.serenegiant.recordingservice;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.service.AbstractServiceRecorder;
import com.serenegiant.service.SplitRecorderService;
import com.serenegiant.service.SplitServiceRecorder;

import java.io.IOException;

public class SplitRecFragment3 extends AbstractCameraFragment {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = SplitRecFragment3.class.getSimpleName();

	private SplitServiceRecorder mRecorder;

	public SplitRecFragment3() {
		super();
		// need default constructor
	}

	@Override
	protected boolean isRecording() {
		return mRecorder != null;
	}

	@Override
	protected void internalStartRecording() throws IOException {
		if (DEBUG) Log.v(TAG, "internalStartRecording:mRecorder=" + mRecorder);
		if (mRecorder == null) {
			if (DEBUG) Log.v(TAG, "internalStartRecording:get SplitServiceRecorder");
			mRecorder = SplitServiceRecorder.newInstance(requireContext(),
				SplitRecorderService.class, mCallback);
		} else {
			Log.w(TAG, "internalStartRecording:recorder is not null, already start recording?");
		}
	}

	@Override
	protected void internalStopRecording() {
		if (DEBUG) Log.v(TAG, "internalStopRecording:mRecorder=" + mRecorder);
		if (mRecorder != null) {
			mRecorder.release();
			mRecorder = null;
		}
	}

	@Override
	protected void onFrameAvailable() {
		if (mRecorder != null) {
			mRecorder.frameAvailableSoon();
		}
	}

	private int mRecordingSurfaceId = 0;
	private final AbstractServiceRecorder.Callback mCallback
		= new AbstractServiceRecorder.Callback() {
		@Override
		public void onConnected() {
			if (DEBUG) Log.v(TAG, "onConnected:");
			if (mRecordingSurfaceId != 0) {
				mCameraView.removeSurface(mRecordingSurfaceId);
				mRecordingSurfaceId = 0;
			}
			if (mRecorder != null) {
				try {
					mRecorder.setVideoSettings(VIDEO_WIDTH, VIDEO_HEIGHT, 30, 0.25f);
					mRecorder.setAudioSettings(SAMPLE_RATE, CHANNEL_COUNT);
					mRecorder.prepare();
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
				}
			}
		}

		@Override
		public void onPrepared() {
			if (DEBUG) Log.v(TAG, "onPrepared:");
			if (mRecorder != null) {
				try {
					final Surface surface = mRecorder.getInputSurface();	// API>=18
					if (surface != null) {
						mRecordingSurfaceId = surface.hashCode();
						mCameraView.addSurface(mRecordingSurfaceId, surface, true);
					} else {
						Log.w(TAG, "surface is null");
						stopRecording();	// 非同期で呼ばないとデッドロックするかも
					}
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
				}
			}
		}

		@Override
		public void onReady() {
			if (DEBUG) Log.v(TAG, "onReady:");
			if (mRecorder != null) {
				final Context context = requireContext();
				try {
					mRecorder.start(null);
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
				}
			}
		}

		@Override
		public void onDisconnected() {
			if (DEBUG) Log.v(TAG, "onDisconnected:");
			if (mRecordingSurfaceId != 0) {
				mCameraView.removeSurface(mRecordingSurfaceId);
				mRecordingSurfaceId = 0;
			}
			stopRecording();
		}
	};
}
