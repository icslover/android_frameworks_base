/*
 * Copyright (C) 2008 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2012, ParanoidAndroid Project.
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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.database.ContentObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.ColorUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ImageView;

import java.math.BigInteger;
import com.android.internal.util.ArrayUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NavbarEditor;
import com.android.systemui.statusbar.phone.NavbarEditor.ButtonInfo;
import com.android.systemui.statusbar.phone.NavigationBarView;

public class KeyButtonView extends ImageView {
    private static final String TAG = "StatusBar.KeyButtonView";

    final float GLOW_MAX_SCALE_FACTOR = 1.8f;
    float BUTTON_QUIESCENT_ALPHA = 1.0f;

    long mDownTime;
    int mCode;
    int mTouchSlop;
    Drawable mGlowBG;
    int mGlowWidth, mGlowHeight;
    float mGlowAlpha = 0f, mGlowScale = 1f, mDrawingAlpha = 1f, mOldDrawingAlpha = 1f;
    boolean mSupportsLongpress = true;
    RectF mRect = new RectF(0f,0f,0f,0f);
    AnimatorSet mPressedAnim;
    Context mContext;

    private ColorUtils.ColorSettingInfo mLastButtonColor;
    private ColorUtils.ColorSettingInfo mLastGlowColor;

    Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {
                // Slog.d("KeyButtonView", "longpressed: " + this);
                if (mCode != 0) {
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                } else {
                    // Just an old-fashioned ImageView
                    performLongClick();
                }
            }
        }
    };

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        mContext = context;

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyButtonView,
                defStyle, 0);

        mCode = a.getInteger(R.styleable.KeyButtonView_keyCode, 0);
        
        mSupportsLongpress = a.getBoolean(R.styleable.KeyButtonView_keyRepeat, true);

        mGlowBG = a.getDrawable(R.styleable.KeyButtonView_glowBackground);
        if (mGlowBG != null) {
            setDrawingAlpha(BUTTON_QUIESCENT_ALPHA);
            mGlowWidth = mGlowBG.getIntrinsicWidth();
            mGlowHeight = mGlowBG.getIntrinsicHeight();
        }
        
        a.recycle();

        setClickable(true);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        clearColorFilter();
        BUTTON_QUIESCENT_ALPHA = 0.70f;
        setDrawingAlpha(BUTTON_QUIESCENT_ALPHA);

        // Only watch for per app color changes when the setting is in check
        if (ColorUtils.getPerAppColorState(mContext)) {

            mLastGlowColor = ColorUtils.getColorSettingInfo(mContext, Settings.System.NAV_GLOW_COLOR);
            mLastButtonColor = ColorUtils.getColorSettingInfo(mContext, Settings.System.NAV_BUTTON_COLOR);

            updateButtonColor();

            mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.NAV_BUTTON_COLOR), false, new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateButtonColor();
                    }});

            mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.NAV_GLOW_COLOR), false, new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateGlowColor();
                    }});
        }
    }

    private void updateButtonColor() {
        ColorUtils.ColorSettingInfo colorInfo = ColorUtils.getColorSettingInfo(mContext,
                Settings.System.NAV_BUTTON_COLOR);
        if (!colorInfo.lastColorString.equals(mLastButtonColor.lastColorString)) {
            if (colorInfo.isLastColorNull) {
                clearColorFilter();
                BUTTON_QUIESCENT_ALPHA = 0.70f;
                setDrawingAlpha(BUTTON_QUIESCENT_ALPHA);
            } else {
                setColorFilter(ColorUtils.extractRGB(colorInfo.lastColor) | 0xFF000000, PorterDuff.Mode.SRC_ATOP);
                BUTTON_QUIESCENT_ALPHA = (float)ColorUtils.extractAlpha(colorInfo.lastColor) / 255f;
                setDrawingAlpha(BUTTON_QUIESCENT_ALPHA);
            }
            mLastButtonColor = colorInfo;
        }
    }

    private void updateGlowColor() {
        ColorUtils.ColorSettingInfo colorInfo = ColorUtils.getColorSettingInfo(mContext,
                Settings.System.NAV_GLOW_COLOR);
        if (!colorInfo.lastColorString.equals(mLastGlowColor.lastColorString)) {
            if (colorInfo.isLastColorNull) {
                mGlowBG.clearColorFilter();
            } else {
                mGlowBG.setColorFilter(colorInfo.lastColor, PorterDuff.Mode.SRC_ATOP);
            }
            mLastGlowColor = colorInfo;
        }
     }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mGlowBG != null) {
            canvas.save();
            final int w = getWidth();
            final int h = getHeight();
            final float aspect = (float)mGlowWidth / mGlowHeight;
            final int drawW = (int)(h*aspect);
            final int drawH = h;
            final int margin = (drawW-w)/2;
            canvas.scale(mGlowScale, mGlowScale, w*0.5f, h*0.5f);
            mGlowBG.setBounds(-margin, 0, drawW-margin, drawH);
            mGlowBG.setAlpha((int)(mDrawingAlpha * mGlowAlpha * 255));
            mGlowBG.draw(canvas);
            canvas.restore();
            mRect.right = w;
            mRect.bottom = h;
        }
        super.onDraw(canvas);
    }

    public float getDrawingAlpha() {
        if (mGlowBG == null) return 0;
        return mDrawingAlpha;
    }

    public void setDrawingAlpha(float x) {
        if (mGlowBG == null) return;
        // Calling setAlpha(int), which is an ImageView-specific
        // method that's different from setAlpha(float). This sets
        // the alpha on this ImageView's drawable directly
        setAlpha((int) (x * 255));
        mDrawingAlpha = x;
    }

    public float getGlowAlpha() {
        if (mGlowBG == null) return 0;
        return mGlowAlpha;
    }

    public void setGlowAlpha(float x) {
        if (mGlowBG == null) return;
        mGlowAlpha = x;
        invalidate();
    }

    public float getGlowScale() {
        if (mGlowBG == null) return 0;
        return mGlowScale;
    }

    public void setGlowScale(float x) {
        if (mGlowBG == null) return;
        mGlowScale = x;
        final float w = getWidth();
        final float h = getHeight();
        if (GLOW_MAX_SCALE_FACTOR <= 1.0f) {
            // this only works if we know the glow will never leave our bounds
            invalidate();
        } else {
            final float rx = (w * (GLOW_MAX_SCALE_FACTOR - 1.0f)) / 2.0f + 1.0f;
            final float ry = (h * (GLOW_MAX_SCALE_FACTOR - 1.0f)) / 2.0f + 1.0f;
            com.android.systemui.SwipeHelper.invalidateGlobalRegion(
                    this,
                    new RectF(getLeft() - rx,
                              getTop() - ry,
                              getRight() + rx,
                              getBottom() + ry));

            // also invalidate our immediate parent to help avoid situations where nearby glows
            // interfere
            ((View)getParent()).invalidate();
        }
    }

    public void setPressed(boolean pressed) {
        if (mGlowBG != null) {
            if (pressed != isPressed()) {
                if (mPressedAnim != null && mPressedAnim.isRunning()) {
                    mPressedAnim.cancel();
                }
                final AnimatorSet as = mPressedAnim = new AnimatorSet();
                if (pressed) {
                    if (mGlowScale < GLOW_MAX_SCALE_FACTOR) 
                        mGlowScale = GLOW_MAX_SCALE_FACTOR;
                    if (mGlowAlpha < BUTTON_QUIESCENT_ALPHA)
                        mGlowAlpha = BUTTON_QUIESCENT_ALPHA;
                    setDrawingAlpha(1f);
                    as.playTogether(
                        ObjectAnimator.ofFloat(this, "glowAlpha", 1f),
                        ObjectAnimator.ofFloat(this, "glowScale", GLOW_MAX_SCALE_FACTOR)
                    );
                    as.setDuration(50);
                } else {
                    mOldDrawingAlpha = BUTTON_QUIESCENT_ALPHA;
                    as.playTogether(
                        ObjectAnimator.ofFloat(this, "glowAlpha", 0f),
                        ObjectAnimator.ofFloat(this, "glowScale", 1f),
                        ObjectAnimator.ofFloat(this, "drawingAlpha", BUTTON_QUIESCENT_ALPHA)
                    );
                    as.addListener( new Animator.AnimatorListener() {
                        @Override
                        public void onAnimationStart(Animator animation) { }
                        @Override
                        public void onAnimationCancel(Animator animation) { }
                        @Override
                        public void onAnimationRepeat(Animator animation) { }
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            setDrawingAlpha(BUTTON_QUIESCENT_ALPHA);
                        }});
                    as.setDuration(500);
                }
                as.start();
            }
        }
        super.setPressed(pressed);
    }

    public void setInfo (String itemKey, boolean isVertical) {
        ButtonInfo item = NavbarEditor.buttonMap.get(itemKey);
        setTag(itemKey);
        final Resources res = getResources();
        setContentDescription(res.getString(item.contentDescription));
        mCode = item.keyCode;
        boolean isSmallButton = ArrayUtils.contains(NavbarEditor.smallButtonIds, getId());
        Drawable keyD;
        if (isSmallButton) {
            keyD = res.getDrawable(item.sideResource);
        } else if (!isVertical) {
            keyD = res.getDrawable(item.portResource);
        } else {
            keyD = res.getDrawable(item.landResource);
        }
        //Reason for setImageDrawable vs setImageResource is because setImageResource calls relayout() w/o
        //any checks. setImageDrawable performs size checks and only calls relayout if necessary. We rely on this
        //because otherwise the setX/setY attributes which are post layout cause it to mess up the layout.
        setImageDrawable(keyD);
        if (itemKey.equals(NavbarEditor.NAVBAR_EMPTY)) {
            if (isSmallButton) {
                setVisibility(NavigationBarView.getEditMode() ? View.VISIBLE : View.INVISIBLE);
            } else {
                setVisibility(NavigationBarView.getEditMode() ? View.VISIBLE : View.GONE);
            }
        } else if (itemKey.equals(NavbarEditor.NAVBAR_CONDITIONAL_MENU)) {
            setVisibility(NavigationBarView.getEditMode() ? View.VISIBLE : View.INVISIBLE);
        } else if (itemKey.equals(NavbarEditor.NAVBAR_HOME)) {
            mSupportsLongpress = false;
        }
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (NavigationBarView.getEditMode()) {
            return false;
        }
        final int action = ev.getAction();
        int x, y;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                //Slog.d("KeyButtonView", "press");
                mDownTime = SystemClock.uptimeMillis();
                setPressed(true);
                if (mCode != 0) {
                    sendEvent(KeyEvent.ACTION_DOWN, 0, mDownTime);
                } else {
                    // Provide the same haptic feedback that the system offers for virtual keys.
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                if (mSupportsLongpress) {
                    removeCallbacks(mCheckLongPress);
                    postDelayed(mCheckLongPress, ViewConfiguration.getLongPressTimeout());
                }
                break;
            case MotionEvent.ACTION_MOVE:
                x = (int)ev.getX();
                y = (int)ev.getY();
                setPressed(x >= -mTouchSlop
                        && x < getWidth() + mTouchSlop
                        && y >= -mTouchSlop
                        && y < getHeight() + mTouchSlop);
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mCode != 0) {
                    sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                }
                if (mSupportsLongpress) {
                    removeCallbacks(mCheckLongPress);
                }
                break;
            case MotionEvent.ACTION_UP:
                final boolean doIt = isPressed();
                setPressed(false);
                if (mCode != 0) {
                    if (doIt) {
                        sendEvent(KeyEvent.ACTION_UP, 0);
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                        playSoundEffect(SoundEffectConstants.CLICK);
                    } else {
                        sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                    }
                } else {
                    // no key code, just a regular ImageView
                    if (doIt) {
                        performClick();
                    }
                }
                if (mSupportsLongpress) {
                    removeCallbacks(mCheckLongPress);
                }
                break;
        }

        return true;
    }

    public void sendEventSequence(int[][] actions) {
        for(int[] action : actions) {
            sendEvent(action[0], action[1],
                SystemClock.uptimeMillis());
        }
    }   

    void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    void sendEvent(int action, int flags, long when) {
        final int repeatCount = (flags & KeyEvent.FLAG_LONG_PRESS) != 0 ? 1 : 0;
        final KeyEvent ev = new KeyEvent(mDownTime, when, action, mCode, repeatCount,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags | KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        InputManager.getInstance().injectInputEvent(ev,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}


