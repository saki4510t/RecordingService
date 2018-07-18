package com.serenegiant.recordingservice;

import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.service.AbstractServiceRecorder;
import com.serenegiant.service.TimeShiftRecService;
import com.serenegiant.service.TimeShiftRecorder;
import com.serenegiant.utils.FileUtils;

import java.io.File;
import java.io.IOException;

public class TimeShiftRecFragment extends AbstractCameraFragment {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = TimeShiftRecFragment.class.getSimpleName();
	
	
	private TimeShiftRecorder mRecorder;

	public TimeShiftRecFragment() {
		super();
		// need default constructor
	}
	
	@Override
	protected boolean isRecording() {
		return mRecorder != null && mRecorder.isRecording();
	}
	
	@Override
	public void internalOnResume() {
		super.internalOnResume();
		if (DEBUG) Log.v(TAG, "internalOnResume:");
		queueEvent(() -> {
			if (mRecorder == null) {
				mRecorder = new TimeShiftRecorder(requireContext(),
					TimeShiftRecService.class, mCallback);
			}
		},100);
	}
	
	@Override
	public void internalOnPause() {
		if (DEBUG) Log.v(TAG, "internalOnPause:");
		releaseRecorder();
		super.internalOnPause();
	}
	
	@Override
	protected void internalStartRecording() throws IOException {
		if (DEBUG) Log.v(TAG, "internalStartRecording:");
		if ((mRecorder != null) && mRecorder.isTimeShift()) {
			try {
				final File dir = new File(
					Environment.getExternalStoragePublicDirectory(
						Environment.DIRECTORY_MOVIES), APP_DIR_NAME);
				dir.mkdirs();
				mRecorder.start(dir.toString(), FileUtils.getDateTimeString());
			} catch (final Exception e) {
				Log.w(TAG, e);
				stopRecording();	// 非同期で呼ばないとデッドロックするかも
			}
		}
	}
	
	@Override
	protected void internalStopRecording() {
		if (DEBUG) Log.v(TAG, "internalStopRecording:");
		if (mRecorder != null) {
			mRecorder.stop();
		}
	}
	
	@Override
	protected void onFrameAvailable() {
		if (mRecorder != null) {
			mRecorder.frameAvailableSoon();
		}
	}
	
	private void releaseRecorder() {
		if (DEBUG) Log.v(TAG, "releaseRecorder:");
		if (mRecorder != null) {
			mRecorder.release();
			mRecorder = null;
		}
	}

	private int mRecordingSurfaceId = 0;
	private AbstractServiceRecorder.Callback mCallback
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
					mRecorder.prepare();
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
					releaseRecorder();
				}
			}
		}
		
		@SuppressWarnings("ResultOfMethodCallIgnored")
		@Override
		public void onPrepared() {
			if (DEBUG) Log.v(TAG, "onPrepared:");
			if (mRecorder != null) {
				try {
					final Surface surface = mRecorder.getInputSurface();
					if (surface != null) {
						mRecordingSurfaceId = surface.hashCode();
						mCameraView.addSurface(mRecordingSurfaceId, surface, true);
					} else {
						Log.w(TAG, "surface is null");
						stopRecording();	// 非同期で呼ばないとデッドロックするかも
						releaseRecorder();
					}
				} catch (final Exception e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
					releaseRecorder();
				}
			}
		}
		
		@SuppressWarnings("ResultOfMethodCallIgnored")
		@Override
		public void onReady() {
			if (DEBUG) Log.v(TAG, "onReady:");
			queueEvent(() -> {
				try {
					// バッファリング開始
					mRecorder.startTimeShift();
				} catch (final IOException e) {
					Log.w(TAG, e);
					stopRecording();	// 非同期で呼ばないとデッドロックするかも
					releaseRecorder();
				}
			}, 10);
		}
		
		@Override
		public void onDisconnected() {
			if (DEBUG) Log.v(TAG, "onDisconnected:");
			if (mRecordingSurfaceId != 0) {
				mCameraView.removeSurface(mRecordingSurfaceId);
				mRecordingSurfaceId = 0;
			}
			stopRecording();
			releaseRecorder();
		}
	};

}
