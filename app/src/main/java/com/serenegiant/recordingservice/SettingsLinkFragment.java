package com.serenegiant.recordingservice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.serenegiant.recordingservice.databinding.FragmentSettingsLinkBinding;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

/**
 * ユーザーがアプリに権限付与をしなかったために機能を実行できない時に
 * 端末のアプリ設定画面への繊維ボタンを表示するFragment
 */
public class SettingsLinkFragment extends BaseFragment {

	private FragmentSettingsLinkBinding mBinding;

	public SettingsLinkFragment() {
		super();
	}

	public static SettingsLinkFragment newInstance() {
		SettingsLinkFragment fragment = new SettingsLinkFragment();
		final Bundle args = new Bundle();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public View onCreateView(
		@NonNull final LayoutInflater inflater,
		@Nullable final ViewGroup container,
		@Nullable final Bundle savedInstanceState) {

		mBinding = DataBindingUtil.inflate(inflater,
			R.layout.fragment_settings_link, container, false);
		init();
		return mBinding.getRoot();
	}

	private void init() {
		mBinding.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				// 端末のアプリ設定を開く
				final Context context = v.getContext();
				final String uriString = "package:" + context.getPackageName();
				final Intent intent
					= new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(uriString));
				context.startActivity(intent);
			}
		});
	}
}