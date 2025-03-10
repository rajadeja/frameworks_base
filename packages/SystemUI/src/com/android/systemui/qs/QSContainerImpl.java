/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.app.ActivityManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.Dependency;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.statusbar.CommandQueue;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link BaseStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout {

    private static final String TAG = "QSContainerImpl";

    private final Point mSizePoint = new Point();

    private int mHeightOverride = -1;
    private QSPanel mQSPanel;
    private View mQSDetail;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private View mQSFooter;

    private View mBackground;
    private View mBackgroundGradient;
    private View mStatusBarBackground;

    private int mSideMargins;
    private boolean mQsDisabled;

    private Drawable mQsBackGround;
    private int mQsBackGroundAlpha;
    private int mQsBackGroundColor;
    private int mQsBackGroundColorWall;
    private int mCurrentColor;
    private int mUserThemeSetting;
    private boolean mSetQsFromWall;
    private boolean mUseBlackTheme = false;
    private boolean mUseDarkTheme = false;
    private boolean mSetQsFromResources;
    private SysuiColorExtractor mColorExtractor;

    private IOverlayManager mOverlayManager;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        Handler handler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(handler);
        settingsObserver.observe();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanel = findViewById(R.id.quick_settings_panel);
        mQSDetail = findViewById(R.id.qs_detail);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = findViewById(R.id.qs_customize);
        mQSFooter = findViewById(R.id.qs_footer);
        mBackground = findViewById(R.id.quick_settings_background);
        mStatusBarBackground = findViewById(R.id.quick_settings_status_bar_background);
        mBackgroundGradient = findViewById(R.id.quick_settings_gradient_view);
        mSideMargins = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        mQsBackGround = getContext().getDrawable(R.drawable.qs_background_primary);
        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        updateSettings();

        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMargins();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Hide the backgrounds when in landscape mode.
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mBackgroundGradient.setVisibility(View.INVISIBLE);
            mStatusBarBackground.setVisibility(View.INVISIBLE);
        } else {
            mBackgroundGradient.setVisibility(View.VISIBLE);
            mStatusBarBackground.setVisibility(View.VISIBLE);
        }

        updateResources();
        mSizePoint.set(0, 0); // Will be retrieved on next measure pass.
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            getContext().getContentResolver().registerContentObserver(Settings.System
                            .getUriFor(Settings.System.QS_PANEL_BG_ALPHA), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_COLOR), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_COLOR_WALL), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_USE_WALL), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_USE_FW), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.Secure
                    .getUriFor(Settings.Secure.THEME_MODE), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.PREFER_BLACK_THEMES), false,
                    this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private void updateSettings() {
        int userQsWallColorSetting = Settings.System.getIntForUser(getContext().getContentResolver(),
                    Settings.System.QS_PANEL_BG_USE_WALL, 0, UserHandle.USER_CURRENT);
        mSetQsFromWall = userQsWallColorSetting == 1;
        int userQsFwSetting = Settings.System.getIntForUser(getContext().getContentResolver(),
                    Settings.System.QS_PANEL_BG_USE_FW, 1, UserHandle.USER_CURRENT);
        mSetQsFromResources = userQsFwSetting == 1;
        mQsBackGroundAlpha = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_ALPHA, 255,
                UserHandle.USER_CURRENT);
        mQsBackGroundColor = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_COLOR, Color.WHITE,
                UserHandle.USER_CURRENT);
        mQsBackGroundColorWall = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_COLOR_WALL, Color.WHITE,
                UserHandle.USER_CURRENT);
        mUserThemeSetting = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.THEME_MODE, 0, ActivityManager.getCurrentUser());
        boolean blackTheme = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PREFER_BLACK_THEMES, 0, ActivityManager.getCurrentUser()) == 1;
        if (mUserThemeSetting == 0) {
            // The system wallpaper defines if system theme should be light or dark.
            WallpaperColors systemColors = null;
            if (mColorExtractor != null)
                 systemColors = mColorExtractor.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            mUseDarkTheme = systemColors != null
                    && (systemColors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0;
        } else {
            mUseDarkTheme = mUserThemeSetting == 2;
            mUseBlackTheme = blackTheme && mUseDarkTheme;
            // Make sure we turn off dark theme if we plan using black
            if (mUseBlackTheme)
                mUseDarkTheme = false;
        }
        mCurrentColor = mSetQsFromWall ? mQsBackGroundColorWall : mQsBackGroundColor;
        setQsBackground();
        setQsOverlay();
    }

    private void setQsBackground() {

        if (mSetQsFromResources) {
            mQsBackGround = getContext().getDrawable(R.drawable.qs_background_primary);
        } else {
            if (mQsBackGround != null) {
                mQsBackGround.setColorFilter(mCurrentColor, PorterDuff.Mode.SRC_ATOP);
                mQsBackGround.setAlpha(mQsBackGroundAlpha);
            }
        }
        if (mQsBackGround != null && mBackground != null) {
            mBackground.setBackground(mQsBackGround);
        }
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Since we control our own bottom, be whatever size we want.
        // Otherwise the QSPanel ends up with 0 height when the window is only the
        // size of the status bar.
        mQSPanel.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.UNSPECIFIED));
        int width = mQSPanel.getMeasuredWidth();
        LayoutParams layoutParams = (LayoutParams) mQSPanel.getLayoutParams();
        int height = layoutParams.topMargin + layoutParams.bottomMargin
                + mQSPanel.getMeasuredHeight();
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));

        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion();
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mBackgroundGradient.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mBackground.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
    }

    private void updateResources() {
        LayoutParams layoutParams = (LayoutParams) mQSPanel.getLayoutParams();
        layoutParams.topMargin = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);

        mQSPanel.setLayoutParams(layoutParams);
    }

    /**
     * Overrides the height of this view (post-layout), so that the content is clipped to that
     * height and the background is set to that height.
     *
     * @param heightOverride the overridden height
     */
    public void setHeightOverride(int heightOverride) {
        mHeightOverride = heightOverride;
        updateExpansion();
    }

    public void updateExpansion() {
        int height = calculateContainerHeight();
        setBottom(getTop() + height);
        mQSDetail.setBottom(getTop() + height);
        // Pin QS Footer to the bottom of the panel.
        mQSFooter.setTranslationY(height - mQSFooter.getHeight());
        mBackground.setTop(mQSPanel.getTop());
        mBackground.setBottom(height);
    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight();
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        updateExpansion();
    }

    private void setMargins() {
        setMargins(mQSDetail);
        setMargins(mBackground);
        setMargins(mQSFooter);
        mQSPanel.setMargins(mSideMargins);
        mHeader.setMargins(mSideMargins);
    }

    private void setMargins(View view) {
        FrameLayout.LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.rightMargin = mSideMargins;
        lp.leftMargin = mSideMargins;
    }

    private int getDisplayHeight() {
        if (mSizePoint.y == 0) {
            getDisplay().getRealSize(mSizePoint);
        }
        return mSizePoint.y;
    }

    private boolean isColorDark(int color) {
        double darkness = 1 - ( 0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color))/255;
        if (darkness < 0.5) {
            return false; // It's a light color
        } else {
            return true; // It's a dark color
        }
    }

    public void setQsOverlay() {

        // This is being done here so that we don't have issues with one class
        // enabling and other disabling the same function. We can manage QS
        // in one place entirely. Let's not bother StatusBar.java at all
        //
        // TODO: Commonise isUsingDarkTheme and isUsingDarkTheme to a new class.
        // Perhaps even more checks if necessary.

        String qsthemeDark = "com.android.systemui.qstheme.dark";
        String qsthemeBlack = "com.android.systemui.qstheme.black";
        String qsAccentBlack = "com.android.system.theme.qs_black";
        String qsAccentWhite = "com.android.system.theme.qs_white";
        String qstheme = null;

        if (mSetQsFromResources) {
            try {
                mOverlayManager.setEnabled(qsthemeDark, mUseDarkTheme, ActivityManager.getCurrentUser());
                mOverlayManager.setEnabled(qsthemeBlack, mUseBlackTheme, ActivityManager.getCurrentUser());
                mOverlayManager.setEnabled(qsAccentBlack, false, ActivityManager.getCurrentUser());
                mOverlayManager.setEnabled(qsAccentWhite, false, ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change dark/black qs overlays", e);
            }
        } else {
            // Only set black qs for black themes
            if (mUseBlackTheme)
                qstheme = qsthemeBlack;
            else
                qstheme = qsthemeDark;
            try {
                mOverlayManager.setEnabled(qstheme, isColorDark(mCurrentColor), ActivityManager.getCurrentUser());
                mOverlayManager.setEnabled(qsAccentBlack, !isColorDark(mCurrentColor), ActivityManager.getCurrentUser());
                mOverlayManager.setEnabled(qsAccentWhite, isColorDark(mCurrentColor), ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change qs theme", e);
            }
        }
    }
}
