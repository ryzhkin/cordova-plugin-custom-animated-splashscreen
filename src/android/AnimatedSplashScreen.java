/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/

package com.applurk.animatedsplashscreen;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.concurrent.TimeUnit;

public class AnimatedSplashScreen extends CordovaPlugin {
    private static final String LOG_TAG = "AnimatedSplashScreen";
    // Cordova 3.x.x has a copy of this plugin bundled with it (SplashScreenInternal.java).
    // Enable functionality only if running on 4.x.x.
    private static final boolean HAS_BUILT_IN_SPLASH_SCREEN = Integer.valueOf(CordovaWebView.CORDOVA_VERSION.split("\\.")[0]) < 4;
    private static final int DEFAULT_SPLASHSCREEN_DURATION = 3000;
    private static final int DEFAULT_FADE_DURATION = 500;
    private static Dialog splashDialog;
    private static boolean firstShow = true;
    private static boolean lastHideAfterDelay; // https://issues.apache.org/jira/browse/CB-9094
    //    private int animationImageIndex = 0;
    private int repeatIndex = 0;
    private int drawableSlideId = 0;
    Handler animationHandler;

    /**
     * Displays the splash drawable.
     */
    private ImageView splashImageView;

    /**
     * Remember last device orientation to detect orientation changes.
     */
    private int orientation;

    // Helper to be compile-time compatible with both Cordova 3.x and 4.x.
    private View getView() {
        try {
            return (View) webView.getClass().getMethod("getView").invoke(webView);
        } catch (Exception e) {
            return (View) webView;
        }
    }

    private int getSplashId() {
        int drawableId = 0;
        String splashResource = preferences.getString("SplashScreen", "screen");
        splashResource = "screen";
        if (splashResource != null) {
            drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable", cordova.getActivity().getClass().getPackage().getName());
            if (drawableId == 0) {
                drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable", cordova.getActivity().getPackageName());
            }
        }
        return drawableId;
    }

    @Override
    protected void pluginInitialize() {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // Make WebView invisible while loading URL
        // CB-11326 Ensure we're calling this on UI thread
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getView().setVisibility(View.INVISIBLE);
            }
        });
        int drawableId = getSplashId();

        // Save initial orientation.
        orientation = cordova.getActivity().getResources().getConfiguration().orientation;

        if (firstShow) {
            boolean autoHide = preferences.getBoolean("AutoHideSplashScreen", true);
            showSplashScreen(autoHide);
        }

        if (preferences.getBoolean("SplashShowOnlyFirstTime", true)) {
            firstShow = false;
        }
    }

    /**
     * Shorter way to check value of "SplashMaintainAspectRatio" preference.
     */
    private boolean isMaintainAspectRatio() {
        return preferences.getBoolean("SplashMaintainAspectRatio", false);
    }

    private int getFadeDuration() {
        int fadeSplashScreenDuration = preferences.getBoolean("FadeSplashScreen", true) ?
                preferences.getInteger("FadeSplashScreenDuration", DEFAULT_FADE_DURATION) : 0;

        if (fadeSplashScreenDuration < 30) {
            // [CB-9750] This value used to be in decimal seconds, so we will assume that if someone specifies 10
            // they mean 10 seconds, and not the meaningless 10ms
            fadeSplashScreenDuration *= 1000;
        }

        return fadeSplashScreenDuration;
    }

    @Override
    public void onPause(boolean multitasking) {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
    }

    @Override
    public void onDestroy() {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
        // If we set this to true onDestroy, we lose track when we go from page to page!
        //firstShow = true;
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("hide")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("animatedsplashscreen", "hide");
                }
            });
        } else if (action.equals("show")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("animatedsplashscreen", "show");
                }
            });
        } else {
            return false;
        }

        callbackContext.success();
        return true;
    }

    @Override
    public Object onMessage(String id, Object data) {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return null;
        }
        if ("animatedsplashscreen".equals(id)) {
            if ("hide".equals(data.toString())) {
                this.removeSplashScreen(false);
            } else {
                this.showSplashScreen(false);
            }
        } else if ("spinner".equals(id)) {
            if ("stop".equals(data.toString())) {
                getView().setVisibility(View.VISIBLE);
            }
        } else if ("onReceivedError".equals(id)) {
        }
        return null;
    }

    // Don't add @Override so that plugin still compiles on 3.x.x for a while
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.orientation != orientation) {
            orientation = newConfig.orientation;

            // Splash drawable may change with orientation, so reload it.
            if (splashImageView != null) {
                int drawableId = getSplashId();
                if (drawableId != 0) {
                    splashImageView.setImageDrawable(cordova.getActivity().getResources().getDrawable(drawableId));
                }
            }
        }
    }

    private void removeSplashScreen(final boolean forceHideImmediately) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (splashDialog != null && splashDialog.isShowing()) {
                    final int fadeSplashScreenDuration = getFadeDuration();
                    // CB-10692 If the plugin is being paused/destroyed, skip the fading and hide it immediately
                    if (fadeSplashScreenDuration > 0 && forceHideImmediately == false) {
                        AlphaAnimation fadeOut = new AlphaAnimation(1, 0);
                        fadeOut.setInterpolator(new DecelerateInterpolator());
                        fadeOut.setDuration(fadeSplashScreenDuration);

                        splashImageView.setAnimation(fadeOut);
                        splashImageView.startAnimation(fadeOut);

                        fadeOut.setAnimationListener(new Animation.AnimationListener() {
                            @Override
                            public void onAnimationStart(Animation animation) {
                                /*
                                 * @TODO
                                 */
                            }

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                if (splashDialog != null && splashDialog.isShowing()) {
                                    splashDialog.dismiss();
                                    splashDialog = null;
                                    splashImageView = null;
                                }
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {
                            }
                        });
                    } else {
                        splashDialog.dismiss();
                        splashDialog = null;
                        splashImageView = null;
                    }
                }
            }
        });
    }

    /**
     * Shows the splash screen over the full Activity
     */
    @SuppressWarnings("deprecation")
    private void showSplashScreen(final boolean hideAfterDelay) {
        int splashscreenTime = preferences.getInteger("AnimatedSplashScreenAnimationDuration", 5);
        if (splashscreenTime > 90) {
            splashscreenTime = 90;
        }

        final int splashscreenTimeFinal = splashscreenTime;

        int animationRepeatCount = preferences.getInteger("AnimatedSplashScreenAnimationRepeatCount", 1);
        if (animationRepeatCount > 10) {
            animationRepeatCount = 10;
        }

        final int animationRepeatCountFinal = animationRepeatCount;
//        final int animationRepeatCount = 2;
        final String splashScreenImagesString = preferences.getString("AnimatedSplashScreenAndroidImages", "");
        final String[] imagesArray = splashScreenImagesString.isEmpty() ? null : splashScreenImagesString.split(",");
        final int drawableId = getSplashId();

        this.animationHandler = new Handler();

        final int fadeSplashScreenDuration = getFadeDuration();

        lastHideAfterDelay = hideAfterDelay;

        // Prevent to show the splash dialog if the activity is in the process of finishing
        if (cordova.getActivity().isFinishing()) {
            return;
        }
        // If the splash dialog is showing don't try to show it again
        if (splashDialog != null && splashDialog.isShowing()) {
            return;
        }
        if (drawableId == 0 || (splashscreenTime <= 0 && hideAfterDelay)) {
            return;
        }

        drawableSlideId = 0;
        this.repeatIndex = 0;

        Display display = cordova.getActivity().getWindowManager().getDefaultDisplay();
        Context context = webView.getContext();

        // Use an ImageView to render the image because of its flexible scaling options.
        splashImageView = new ImageView(context);
        LayoutParams layoutParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        splashImageView.setLayoutParams(layoutParams);

        splashImageView.setMinimumHeight(display.getHeight());
        splashImageView.setMinimumWidth(display.getWidth());
        splashImageView.setMaxWidth(display.getWidth());

        // TODO: Use the background color of the webView's parent instead of using the preference.
        splashImageView.setBackgroundColor(preferences.getInteger("backgroundColor", Color.BLACK));


        splashImageView.setScaleType(ImageView.ScaleType.FIT_XY);
//        if (isMaintainAspectRatio()) {
//            // CENTER_CROP scale mode is equivalent to CSS "background-size:cover"
//            splashImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
//        } else {
//            // FIT_XY scales image non-uniformly to fit into image view.
//            splashImageView.setScaleType(ImageView.ScaleType.FIT_XY);
//        }

        // Create and show the dialog
        splashDialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
        // check to see if the splash screen should be full screen
        if ((cordova.getActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN)
                == WindowManager.LayoutParams.FLAG_FULLSCREEN) {
            splashDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    if ((imagesArray != null) && imagesArray.length > 0) {
                        while (true) {
                            for (int i = 0; i < imagesArray.length; i++) {
                                String splashResource = imagesArray[i];
                                int drawableId = 0;
                                if (splashResource != null) {
                                    drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable", cordova.getActivity().getClass().getPackage().getName());
                                    if (drawableId != 0) {
                                        drawableSlideId = drawableId;
                                        animationHandler.post(changeSlide);
                                        int timeoutValue = Math.round(splashscreenTimeFinal / imagesArray.length);
                                        if (timeoutValue <= 0) {
                                            timeoutValue = 1;
                                        }

                                        timeoutValue = timeoutValue * 1000;

                                        Log.d(LOG_TAG, "Display splash slide: " + splashResource + " in " + timeoutValue);

                                        TimeUnit.MILLISECONDS.sleep(timeoutValue);
                                    }
                                }
                            }
                            repeatIndex++;

                            if ((animationRepeatCountFinal > 0) && (repeatIndex >= animationRepeatCountFinal)) {
                                Log.d(LOG_TAG, "Max animation repeats: " + repeatIndex);
                                animationHandler.removeCallbacks(changeSlide);
                                removeSplashScreen(false);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();


        splashDialog.setContentView(splashImageView);
        splashDialog.setCancelable(false);
        splashDialog.show();
    }

    Runnable changeSlide = new Runnable() {
        public void run() {
            if (drawableSlideId == 0) {
                Log.w(LOG_TAG, "drawableSlideId is 0!");
            } else if (splashImageView == null) {
                Log.d(LOG_TAG, "No splashImageView found!");
            } else {
                Log.d(LOG_TAG, "changeSlide: " + drawableSlideId);
                splashImageView.setImageResource(drawableSlideId);
            }
        }
    };
}
