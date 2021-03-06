package com.tomer.alwayson.Services;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.Settings.System;
import android.speech.tts.TextToSpeech;
import android.support.annotation.Nullable;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import com.tomer.alwayson.Constants;
import com.tomer.alwayson.ContextConstatns;
import com.tomer.alwayson.Globals;
import com.tomer.alwayson.Prefs;
import com.tomer.alwayson.R;
import com.tomer.alwayson.Receivers.ScreenReceiver;
import com.tomer.alwayson.Receivers.UnlockReceiver;
import com.tomer.alwayson.Views.FontAdapter;
import com.tomerrosenfeld.customanalogclockview.CustomAnalogClock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import eu.chainfire.libsuperuser.Shell;

public class MainService extends Service implements SensorEventListener, ContextConstatns, TextToSpeech.OnInitListener {

    TextToSpeech tts;
    boolean demo;
    boolean toStopTTS;
    private Prefs prefs;
    private int originalBrightness = 100;
    private int originalAutoBrightnessStatus;
    private TextView calendarTV, batteryTV;
    private ImageView batteryIV;
    private WindowManager windowManager;
    private FrameLayout frameLayout;
    private boolean refreshing;
    private View mainView;
    private WindowManager.LayoutParams windowParams;
    private LinearLayout iconWrapper;
    private PowerManager.WakeLock stayAwakeWakeLock;
    private UnlockReceiver unlockReceiver;
    private int originalCapacitiveButtonsState = 1500;
    private int height, width;
    private int originalTimeout;
    CustomAnalogClock analog24HClock;
    private SensorManager sensorManager;
    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctxt, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            boolean charging = plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB;
            Log.d(MAIN_SERVICE_LOG_TAG, "Battery level " + level);
            Log.d(MAIN_SERVICE_LOG_TAG, "Battery charging " + charging);
            batteryTV.setText(String.valueOf(level) + "%");
            int res;
            if (charging)
                res = R.drawable.ic_battery_charging;
            else {
                if (level > 90)
                    res = R.drawable.ic_battery_full;
                else if (level > 70)
                    res = R.drawable.ic_battery_90;
                else if (level > 50)
                    res = R.drawable.ic_battery_60;
                else if (level > 30)
                    res = R.drawable.ic_battery_30;
                else if (level > 20)
                    res = R.drawable.ic_battery_20;
                else if (level > 0)
                    res = R.drawable.ic_battery_alert;
                else
                    res = R.drawable.ic_battery_unknown;
            }
            batteryIV.setImageResource(res);
        }
    };

    @SuppressWarnings("WeakerAccess")
    private double randInt(double min, double max) {
        return new Random().nextInt((int) ((max - min) + 1)) + min;
    }

    @Override
    public int onStartCommand(Intent origIntent, int flags, int startId) {
        if (windowParams == null) {
            windowParams = new WindowManager.LayoutParams(-1, -1, 2003, 65794, -2);
            if (origIntent != null) {
                demo = origIntent.getBooleanExtra("demo", false);
                windowParams.type = origIntent.getBooleanExtra("demo", false) ? WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            } else
                windowParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            if (prefs.orientation.equals("horizontal"))//Setting screen orientation if horizontal
                windowParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            try {
                windowManager.addView(frameLayout, windowParams);
            } catch (Exception e) {
                e.printStackTrace();
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            }
        }
        return super.onStartCommand(origIntent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(MAIN_SERVICE_LOG_TAG, "Main service has started");
        Globals.isServiceRunning = true;
        prefs = new Prefs(getApplicationContext());
        prefs.apply();
        stayAwakeWakeLock = ((PowerManager) getApplicationContext().getSystemService(POWER_SERVICE)).newWakeLock(268435482, WAKE_LOCK_TAG);
        stayAwakeWakeLock.setReferenceCounted(false);
        originalAutoBrightnessStatus = System.getInt(getContentResolver(), System.SCREEN_BRIGHTNESS_MODE, System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        originalBrightness = System.getInt(getContentResolver(), System.SCREEN_BRIGHTNESS, 100);
        originalTimeout = System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 120000);
        Log.d("Original timeout", String.valueOf(originalTimeout));
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (prefs.stopOnCamera)
                    if (isCameraUsedByApp()) //Check if user just opened the camera, if so, dismiss
                        stopSelf();
            }
        }, 700);//Delay: Because it takes some time to start the camera on some devices

        // Setup UI
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setTheme(R.style.AppTheme);

        LayoutInflater layoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        frameLayout = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if ((event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    if (prefs.getStringByKey(VOLUME_KEYS, "off").equals("speak")) {
                        speakCurrentStatus();
                        return true;
                    } else if (prefs.volumeToStop) {
                        stopSelf();
                        return true;
                    } else return prefs.disableVolumeKeys;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (prefs.backButtonToStop)
                        stopSelf();
                    if (prefs.getStringByKey(BACK_BUTTON, "off").equals("speak")) {
                        speakCurrentStatus();
                    }
                    return false;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
                    stopSelf();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        if (!prefs.getStringByKey(DOUBLE_TAP, "off").equals("off") || !prefs.getStringByKey(SWIPE_UP, "off").equals("off"))
            frameLayout.setOnTouchListener(new OnDismissListener(this));
        frameLayout.setBackgroundColor(Color.BLACK);
        frameLayout.setForegroundGravity(Gravity.CENTER);
        mainView = layoutInflater.inflate(prefs.orientation.equals("vertical") ? R.layout.clock_widget : R.layout.clock_widget_horizontal, frameLayout);
        setUpElements((LinearLayout) mainView.findViewById(R.id.watchface_wrapper), (LinearLayout) mainView.findViewById(R.id.clock_wrapper), (LinearLayout) mainView.findViewById(R.id.date_wrapper), (LinearLayout) mainView.findViewById(prefs.clockStyle != S7_DIGITAL ? R.id.battery_wrapper : R.id.s7_battery_wrapper));

        LinearLayout.LayoutParams mainLayoutParams = new LinearLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        mainLayoutParams.gravity = prefs.moveWidget == DISABLED ? Gravity.CENTER : Gravity.CENTER_HORIZONTAL;

        mainView.setLayoutParams(mainLayoutParams);
        iconWrapper = (LinearLayout) mainView.findViewById(R.id.icons_wrapper);

        unlockReceiver = new UnlockReceiver();
        IntentFilter intentFilter = new IntentFilter();

        //Adding the intent from the pre-defined array filters
        for (String filter : Constants.unlockFilters) {
            intentFilter.addAction(filter);
        }
        try {
            unregisterReceiver(unlockReceiver);
        } catch (Exception ignored) {
        }
        registerReceiver(unlockReceiver, intentFilter);

        // Sensor handling
        if (prefs.proximityToLock != DISABLED || prefs.autoNightMode) //If any sensor is required
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        //If proximity option is on, set it up
        if (prefs.proximityToLock != DISABLED) {
            Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (proximitySensor != null) {
                Log.d(MAIN_SERVICE_LOG_TAG, "STARTING PROXIMITY SENSOR");
                sensorManager.registerListener(this, proximitySensor, (int) TimeUnit.MILLISECONDS.toMicros(900), 100000);
            }
        }
        //If auto night mode option is on, set it up
        if (prefs.autoNightMode) {
            Sensor lightSensor;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT, false);
            } else {
                lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            }
            if (lightSensor != null) {
                Log.d(MAIN_SERVICE_LOG_TAG, "STARTING LIGHT SENSOR");
                sensorManager.registerListener(this, lightSensor, (int) TimeUnit.SECONDS.toMicros(15), 500000);
            }
        }

        //Delay to stop
        if (prefs.stopDelay > DISABLED) {
            final int delayInMilliseconds = prefs.stopDelay * 1000 * 60;
            Log.d(MAIN_SERVICE_LOG_TAG, "Setting delay to stop in minutes " + prefs.stopDelay);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                    Globals.killedByDelay = true;
                    Log.d(MAIN_SERVICE_LOG_TAG, "Stopping service after delay");
                }
            }, delayInMilliseconds);
        }

        //Finding height and width of screen to later move the display
        Display display = windowManager.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        height = prefs.orientation.equals("vertical") ? size.y : size.x;
        width = prefs.orientation.equals("vertical") ? size.x : size.y;

        // UI refreshing
        Globals.notificationChanged = true; //Show notifications at first launch
        if (prefs.notificationsAlerts)
            startService(new Intent(getApplicationContext(), NotificationListener.class)); //Starting notification listener service
        refresh();
        refreshLong(true);

        //All Samsung's stuff
        if (!prefs.hasSoftKeys) {
            try {
                originalCapacitiveButtonsState = System.getInt(getContentResolver(), "button_key_light");
            } catch (Settings.SettingNotFoundException e) {
                Log.d(MAIN_SERVICE_LOG_TAG, "First method of getting the buttons status failed.");
                try {
                    originalCapacitiveButtonsState = (int) System.getLong(getContentResolver(), "button_key_light");
                } catch (Exception ignored) {
                    Log.d(MAIN_SERVICE_LOG_TAG, "Second method of getting the buttons status failed.");
                    try {
                        originalCapacitiveButtonsState = Settings.Secure.getInt(getContentResolver(), "button_key_light");
                    } catch (Exception ignored3) {
                        Log.d(MAIN_SERVICE_LOG_TAG, "Third method of getting the buttons status failed.");
                    }
                }
            }
        }

        //Turn capacitive buttons lights off
        setButtonsLight(OFF);

        //Turn lights on
        setLights(ON, false, true);

        //Turn screen on
        new Handler().postDelayed(
                new Runnable() {
                    public void run() {
                        //Greenify integration
                        if (!demo)
                            if (isPackageInstalled("com.oasisfeng.greenify", getApplicationContext())) {
                                Intent i = new Intent();
                                i.setComponent(new ComponentName("com.oasisfeng.greenify", "com.oasisfeng.greenify.GreenifyShortcut"));
                                i.putExtra("noop-toast", true);
                                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            }
                        //Turn on the display
                        stayAwakeWakeLock.acquire();
                    }
                },
                500);
    }

    private boolean isCameraUsedByApp() {
        Camera camera = null;
        try {
            camera = Camera.open();
        } catch (RuntimeException e) {
            return true;
        } finally {
            if (camera != null) camera.release();
        }
        if (camera != null) camera.release();
        return false;
    }

    private void setUpElements(LinearLayout watchfaceWrapper, LinearLayout clockWrapper, LinearLayout dateWrapper, LinearLayout batteryWrapper) {
        Log.d("Font to apply ", String.valueOf(prefs.font));
        Typeface font = FontAdapter.getFontByNumber(this, prefs.font);
        calendarTV = (TextView) dateWrapper.findViewById(R.id.date_tv);
        batteryIV = (ImageView) batteryWrapper.findViewById(prefs.clockStyle != S7_DIGITAL ? R.id.battery_percentage_icon : R.id.s7_battery_percentage_icon);
        batteryTV = (TextView) batteryWrapper.findViewById(prefs.clockStyle != S7_DIGITAL ? R.id.battery_percentage_tv : R.id.s7_battery_percentage_tv);
        ViewGroup.LayoutParams lp;
        if (prefs.clockStyle >= ANALOG_CLOCK)
            analog24HClock = (CustomAnalogClock) clockWrapper.findViewById(R.id.custom_analog_clock);

        switch (prefs.clockStyle) {
            case DISABLED:
                watchfaceWrapper.removeView(clockWrapper);
                break;
            case DIGITAL_CLOCK:
                TextClock textClock = (TextClock) clockWrapper.findViewById(R.id.digital_clock);
                textClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSize);
                textClock.setTextColor(prefs.textColor);
                if (!prefs.showAmPm)
                    textClock.setFormat12Hour("h:mm");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    textClock.setTextLocale(getApplicationContext().getResources().getConfiguration().getLocales().get(0));
                } else {
                    textClock.setTextLocale(getApplicationContext().getResources().getConfiguration().locale);
                }
                textClock.setTypeface(font);

                clockWrapper.removeView(clockWrapper.findViewById(R.id.custom_analog_clock));
                clockWrapper.removeView(clockWrapper.findViewById(R.id.s7_digital));
                break;
            case ANALOG_CLOCK:
                clockWrapper.removeView(clockWrapper.findViewById(R.id.digital_clock));
                clockWrapper.removeView(clockWrapper.findViewById(R.id.s7_digital));

                lp = clockWrapper.findViewById(R.id.custom_analog_clock).getLayoutParams();
                lp.height = (int) (prefs.textSize * 10);
                lp.width = (int) (prefs.textSize * 9.5);
                clockWrapper.findViewById(R.id.custom_analog_clock).setLayoutParams(lp);
                analog24HClock.init(this, R.drawable.default_face, R.drawable.default_hour_hand, R.drawable.default_minute_hand, 225, false, false);
                break;
            case ANALOG24_CLOCK:
                clockWrapper.removeView(clockWrapper.findViewById(R.id.digital_clock));
                clockWrapper.removeView(clockWrapper.findViewById(R.id.s7_digital));

                lp = clockWrapper.findViewById(R.id.custom_analog_clock).getLayoutParams();
                lp.height = (int) (prefs.textSize * 10);
                lp.width = (int) (prefs.textSize * 9.5);
                clockWrapper.findViewById(R.id.custom_analog_clock).setLayoutParams(lp);
                analog24HClock.init(this, R.drawable.clock_face, R.drawable.hour_hand, R.drawable.minute_hand, 0, true, false);
                break;
            case S7_CLOCK:
                clockWrapper.removeView(clockWrapper.findViewById(R.id.digital_clock));
                clockWrapper.removeView(clockWrapper.findViewById(R.id.s7_digital));

                lp = clockWrapper.findViewById(R.id.custom_analog_clock).getLayoutParams();
                lp.height = (int) (prefs.textSize * 10);
                lp.width = (int) (prefs.textSize * 9.5);
                clockWrapper.findViewById(R.id.custom_analog_clock).setLayoutParams(lp);
                analog24HClock.init(this, R.drawable.s7_face, R.drawable.s7_hour_hand, R.drawable.s7_minute_hand, 0, false, false);
                break;
            case PEBBLE_CLOCK:
                clockWrapper.removeView(clockWrapper.findViewById(R.id.digital_clock));
                clockWrapper.removeView(clockWrapper.findViewById(R.id.s7_digital));

                lp = clockWrapper.findViewById(R.id.custom_analog_clock).getLayoutParams();
                lp.height = (int) (prefs.textSize * 10);
                lp.width = (int) (prefs.textSize * 9.5);
                clockWrapper.findViewById(R.id.custom_analog_clock).setLayoutParams(lp);
                analog24HClock.init(this, R.drawable.pebble_face, R.drawable.pebble_hour_hand, R.drawable.pebble_minute_hand, 225, false, true);
                break;
            case S7_DIGITAL:
                clockWrapper.removeView(clockWrapper.findViewById(R.id.digital_clock));
                clockWrapper.removeView(clockWrapper.findViewById(R.id.custom_analog_clock));
                ((TextView) mainView.findViewById(R.id.s7_hour_tv)).setTypeface(font);
                ((TextView) mainView.findViewById(R.id.s7_date_tv)).setTypeface(font);
                ((TextView) mainView.findViewById(R.id.s7_minute_tv)).setTypeface(font);

                ((TextView) mainView.findViewById(R.id.s7_hour_tv)).setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) (prefs.textSize * 0.2 * 9.2));
                ((TextView) mainView.findViewById(R.id.s7_date_tv)).setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) (prefs.textSize * 0.2 * 1));
                ((TextView) mainView.findViewById(R.id.s7_minute_tv)).setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) (prefs.textSize * 0.2 * 3.5));
                batteryTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) (prefs.textSize * 0.2 * 1));
                ViewGroup.LayoutParams batteryIVlp = batteryIV.getLayoutParams();
                batteryIVlp.height = (int) (prefs.textSize);
                batteryIV.setLayoutParams(batteryIVlp);

                prefs.dateStyle = DISABLED;
                prefs.batteryStyle = 1;

                watchfaceWrapper.removeView(dateWrapper);
                watchfaceWrapper.removeView(batteryWrapper);
                break;
            case FLAT_CLOCK:
                clockWrapper.removeView(clockWrapper.findViewById(R.id.digital_clock));
                clockWrapper.removeView(clockWrapper.findViewById(R.id.s7_digital));

                lp = clockWrapper.findViewById(R.id.custom_analog_clock).getLayoutParams();
                lp.height = (int) (prefs.textSize * 10);
                lp.width = (int) (prefs.textSize * 9.5);
                clockWrapper.findViewById(R.id.custom_analog_clock).setLayoutParams(lp);
                analog24HClock.init(this, R.drawable.flat_face, R.drawable.flat_hour_hand, R.drawable.flat_minute_hand, 235, false, false);
                break;
        }
        switch (prefs.batteryStyle) {
            case 0:
                watchfaceWrapper.removeView(batteryWrapper);
                break;
            case 1:
                if (prefs.clockStyle != S7_DIGITAL) {
                    batteryTV.setTextColor(prefs.textColor);
                    batteryIV.setColorFilter(prefs.textColor, PorterDuff.Mode.SRC_ATOP);
                }

                batteryTV.setTypeface(font);
                batteryTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) (prefs.textSize * 0.2 * 1));
                ViewGroup.LayoutParams batteryIVlp = batteryIV.getLayoutParams();
                batteryIVlp.height = (int) (prefs.textSize);
                batteryIV.setLayoutParams(batteryIVlp);
                registerReceiver(mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                break;
        }
        switch (prefs.dateStyle) {
            case DISABLED:
                watchfaceWrapper.removeView(dateWrapper);
                break;
            case 1:
                calendarTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, (prefs.textSize / 5));
                calendarTV.setTextColor(prefs.textColor);
                calendarTV.setTypeface(font);
                break;
        }
        Log.d("Date", String.valueOf(prefs.dateStyle));
    }

    private void refresh() {
        Log.d(MAIN_SERVICE_LOG_TAG, "Refresh");
        if (Globals.notificationChanged && prefs.notificationsAlerts) {
            iconWrapper.removeAllViews();
            for (Map.Entry<String, Drawable> entry : Globals.notificationsDrawables.entrySet()) {
                Drawable drawable = entry.getValue();
                drawable.setColorFilter(prefs.textColor, PorterDuff.Mode.SRC_ATOP);
                ImageView icon = new ImageView(getApplicationContext());
                icon.setImageDrawable(drawable);
                icon.setColorFilter(prefs.textColor, PorterDuff.Mode.SRC_ATOP);
                FrameLayout.LayoutParams iconLayoutParams = new FrameLayout.LayoutParams(96, 96, Gravity.CENTER);
                icon.setPadding(12, 0, 12, 0);
                icon.setLayoutParams(iconLayoutParams);
                iconWrapper.addView(icon);
            }
            Globals.notificationChanged = false;
        }

        if (Globals.newNotification != null && prefs.notificationPreview) {
            showMessage(Globals.newNotification);
        }

        if (analog24HClock != null) {
            analog24HClock.setTime(Calendar.getInstance());
        }

        if (prefs.clockStyle == S7_DIGITAL) {
            String hour;
            SimpleDateFormat sdf;
            if (DateFormat.is24HourFormat(this)) {
                sdf = new SimpleDateFormat("HH", Locale.getDefault());
            } else
                sdf = new SimpleDateFormat("h", Locale.getDefault());

            hour = sdf.format(new Date());

            sdf = new SimpleDateFormat("mm", Locale.getDefault());
            String minute = sdf.format(new Date());

            ((TextView) mainView.findViewById(R.id.s7_hour_tv)).setText(hour);
            ((TextView) mainView.findViewById(R.id.s7_minute_tv)).setText(minute);
        }
        refreshing = true;
        new Handler().postDelayed(
                new Runnable() {
                    public void run() {
                        if (Globals.isShown)
                            refresh();
                        else
                            refreshing = false;
                    }
                },
                6000);

    }

    private void refreshLong(boolean first) {
        Log.d(MAIN_SERVICE_LOG_TAG, "Long Refresh");
        if (!first)
            switch (prefs.moveWidget) {
                case MOVE_NO_ANIMATION:
                    if (prefs.orientation.equals("vertical"))
                        mainView.setY((float) (height - randInt(height / 1.3, height * 1.2)));
                    else
                        mainView.setX((float) (width - randInt(width / 1.3, width * 1.3)));
                    break;
                case MOVE_WITH_ANIMATION:
                    if (prefs.orientation.equals("vertical"))
                        mainView.animate().translationY((float) (height - randInt(height / 1.3, height * 1.2))).setDuration(2000).setInterpolator(new FastOutSlowInInterpolator());
                    else
                        mainView.animate().translationX((float) (width - randInt(width / 1.3, width * 1.3))).setDuration(2000).setInterpolator(new FastOutSlowInInterpolator());
                    break;
            }
        if (prefs.dateStyle != DISABLED) {
            Calendar calendar = Calendar.getInstance();
            Date date = calendar.getTime();
            String dayOfWeek = new SimpleDateFormat("EEEE", Locale.getDefault()).format(date.getTime()).toUpperCase();
            String month = new SimpleDateFormat("MMMM").format(date.getTime()).toUpperCase();
            String currentDate = new SimpleDateFormat("dd", Locale.getDefault()).format(new Date());
            calendarTV.setText(dayOfWeek + "," + " " + month + " " + currentDate);
        }
        if (prefs.clockStyle == S7_DIGITAL) {
            Calendar calendar = Calendar.getInstance();
            Date date = calendar.getTime();
            String dayOfWeek = new SimpleDateFormat("EEE", Locale.getDefault()).format(date.getTime()) + ".";
            String month = new SimpleDateFormat("MMM", Locale.getDefault()).format(date.getTime()).toUpperCase();
            String currentDate = new SimpleDateFormat("dd", Locale.getDefault()).format(new Date());
            ((TextView) mainView.findViewById(R.id.s7_date_tv)).setText(dayOfWeek + "\n" + currentDate + " " + month);
        }

        new Handler().postDelayed(
                new Runnable() {
                    public void run() {
                        if (Globals.isShown)
                            refreshLong(false);
                        else
                            refreshing = false;
                    }
                },
                16000);
    }

    private void setLights(boolean state, boolean nightMode, boolean first) {
        try {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, state ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : originalAutoBrightnessStatus);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, state ? (nightMode ? 0 : prefs.brightness) : originalBrightness);
        } catch (Exception e) {
            Toast.makeText(MainService.this, getString(R.string.warning_3_allow_system_modification), Toast.LENGTH_SHORT).show();
        }

        if (state && mainView != null) {
            AlphaAnimation old = (AlphaAnimation) mainView.getAnimation();
            if (old != null && first) {
                mainView.clearAnimation();
                // Finish old animation
                try {
                    Field f = old.getClass().getField("mToAlpha");
                    f.setAccessible(true);
                    mainView.setAlpha(f.getFloat(old));
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            boolean opaque = mainView.getAlpha() == 1f;
            if (nightMode && opaque) {
                AlphaAnimation alpha = new AlphaAnimation(1f, NIGHT_MODE_ALPHA);
                alpha.setDuration(400);
                mainView.startAnimation(alpha);
            } else if (!nightMode && !opaque) {
                AlphaAnimation alpha = new AlphaAnimation(NIGHT_MODE_ALPHA, 1f);
                alpha.setDuration(400);
                mainView.startAnimation(alpha);
            }
        }
    }

    private void speakCurrentStatus() {
        tts = new TextToSpeech(getApplicationContext(), MainService.this);
        tts.setLanguage(Locale.getDefault());
        tts.speak("", TextToSpeech.QUEUE_FLUSH, null);
    }

    private void showMessage(final NotificationListener.NotificationHolder notification) {
        if (!notification.getTitle().equals("null")) {
            //Clear previous animation
            if (mainView.findViewById(R.id.message_box).getAnimation() != null)
                mainView.findViewById(R.id.message_box).clearAnimation();
            //Fade in animation
            Animation fadeIn = new AlphaAnimation(0, 1);
            fadeIn.setInterpolator(new DecelerateInterpolator());
            fadeIn.setDuration(1000);
            //Fade out animation
            Animation fadeOut = new AlphaAnimation(1, 0);
            fadeOut.setInterpolator(new AccelerateInterpolator());
            fadeOut.setStartOffset(40000);
            fadeOut.setDuration(1000);
            //Set the notification text and icon
            ((TextView) mainView.findViewById(R.id.message_box).findViewById(R.id.message_box_title)).setText(notification.getTitle());
            ((TextView) mainView.findViewById(R.id.message_box).findViewById(R.id.message_box_message)).setText(notification.getMessage());
            ((ImageView) mainView.findViewById(R.id.message_box).findViewById(R.id.message_box_icon)).setImageDrawable(notification.getIcon());
            Globals.newNotification = null;
            //Run animations
            AnimationSet animation = new AnimationSet(false);
            animation.addAnimation(fadeIn);
            animation.addAnimation(fadeOut);
            mainView.findViewById(R.id.message_box).setAnimation(animation);
        }
    }

    @Override
    public void onDestroy() {
        //Dismissing the wakelock holder
        Globals.isServiceRunning = false;
        stayAwakeWakeLock.release();
        Log.d(MAIN_SERVICE_LOG_TAG, "Main service has stopped");
        //Stopping tts if it's running
        toStopTTS = true;
        TextToSpeech tts = new TextToSpeech(getApplicationContext(), MainService.this);
        tts.setLanguage(Locale.getDefault());
        tts.speak("", TextToSpeech.QUEUE_FLUSH, null);

        //Resetting the timeout
        Log.d("Original timeout", String.valueOf(originalTimeout));
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, originalTimeout);

        //Unregister receivers
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        unregisterReceiver(unlockReceiver);
        if (prefs.batteryStyle != 0)
            unregisterReceiver(mBatInfoReceiver);

        super.onDestroy();
        setButtonsLight(ON);
        setLights(OFF, false, false);
        try {
            windowManager.removeView(frameLayout);
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), getString(R.string.error_0_unknown_error), Toast.LENGTH_SHORT).show();
        }
        Globals.isShown = false;

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Globals.killedByDelay = false;
            }
        }, 15000);
    }

    private void setButtonsLight(boolean state) {
        state = !state;
        if (!prefs.hasSoftKeys) {
            try {
                System.putInt(getContentResolver(), "button_key_light", state ? 0 : originalCapacitiveButtonsState);
            } catch (RuntimeException e) {
                Log.d(MAIN_SERVICE_LOG_TAG, "First method of settings the buttons state failed.");
                try {
                    Runtime r = Runtime.getRuntime();
                    r.exec("echo" + (state ? 0 : originalCapacitiveButtonsState) + "> /system/class/leds/keyboard-backlight/brightness");
                } catch (IOException e1) {
                    Log.d(MAIN_SERVICE_LOG_TAG, "Second method of settings the buttons state failed.");
                    try {
                        System.putLong(getContentResolver(), "button_key_light", state ? 0 : originalCapacitiveButtonsState);
                    } catch (Exception ignored) {
                        Log.d(MAIN_SERVICE_LOG_TAG, "Third method of settings the buttons state failed.");
                        try {
                            Settings.Secure.putInt(getContentResolver(), "button_key_light", state ? 0 : originalCapacitiveButtonsState);
                        } catch (Exception ignored3) {
                            Log.d(MAIN_SERVICE_LOG_TAG, "Fourth method of settings the buttons state failed.");
                        }
                    }
                }
            }
            if (isPackageInstalled("tomer.com.alwaysonamoledplugin", getApplicationContext())) {
                try {
                    Intent i = new Intent();
                    i.setComponent(new ComponentName("tomer.com.alwaysonamoledplugin", "tomer.com.alwaysonamoledplugin.CapacitiveButtons"));
                    i.putExtra("state", state);
                    i.putExtra("originalCapacitiveButtonsState", originalCapacitiveButtonsState);
                    ComponentName c = startService(i);
                    Log.d(MAIN_SERVICE_LOG_TAG, "Started plugin to control the buttons lights");
                } catch (Exception e) {
                    Log.d(MAIN_SERVICE_LOG_TAG, "Fifth (plugin) method of settings the buttons state failed.");
                    Toast.makeText(getApplicationContext(), getString(R.string.error_2_plugin_not_installed), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private boolean isPackageInstalled(String packagename, Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(packagename, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public float getBatteryLevel() {
        Intent batteryIntent = getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        assert batteryIntent != null;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level == -1 || scale == -1) {
            return 50.0f;
        }
        return ((float) level / (float) scale) * 100.0f;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onInit(int status) {
        if (toStopTTS) {
            try {
                tts.speak(" ", TextToSpeech.QUEUE_FLUSH, null);
            } catch (NullPointerException ignored) {
            }
            return;
        }
        if (status == TextToSpeech.SUCCESS) {
            tts.speak("The time is " + (String) DateFormat.format("hh:mm aaa", Calendar.getInstance().getTime()), TextToSpeech.QUEUE_FLUSH, null);
            if (Globals.notificationsDrawables.size() > 0)
                tts.speak("You have " + Globals.notificationsDrawables.size() + " Notifications", TextToSpeech.QUEUE_ADD, null);
            tts.speak("Battery is at " + (int) getBatteryLevel() + " percent", TextToSpeech.QUEUE_ADD, null);
        }
    }

    @Override
    public void onSensorChanged(final SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_PROXIMITY:
                if (event.values[0] < 1) {
                    // Sensor distance smaller than 1cm
                    stayAwakeWakeLock.release();
                    Globals.isShown = false;
                    Globals.sensorIsScreenOff = true;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (Shell.SU.available())
                                Shell.SU.run("input keyevent 26"); // Screen off using root
                            else {
                                if (prefs.proximityToLock == PROXIMITY_NORMAL_MODE) {
                                    stayAwakeWakeLock.release();
                                    Log.d(MAIN_SERVICE_LOG_TAG, "Set auto lock timeout - " + Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 1000)); //Screen off using timeout
                                    stayAwakeWakeLock.release();
                                } else if (prefs.proximityToLock == PROXIMITY_DEVICE_ADMIN_MODE)
                                    ((DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow(); //Screen off using device admin
                            }
                        }
                    }).start();
                } else {
                    Log.d(MAIN_SERVICE_LOG_TAG, "Turning screen on");
                    if (!Globals.sensorIsScreenOff) {
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                onSensorChanged(event);
                            }
                        }, 200);
                        return;
                    }
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            ScreenReceiver.turnScreenOn(getApplicationContext(), false);
                            Globals.isShown = true;
                            if (!refreshing) {
                                refresh();
                                refreshLong(true);
                            }
                            if (Globals.isServiceRunning)
                                stayAwakeWakeLock.acquire();
                        }
                    }, prefs.proximityToLock != PROXIMITY_NORMAL_MODE ? 1000 : 5000);
                }
                break;
            case Sensor.TYPE_LIGHT:
                setLights(ON, event.values[0] < 2, false);
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private class OnDismissListener implements View.OnTouchListener {
        private final GestureDetector gestureDetector;

        OnDismissListener(Context ctx) {
            gestureDetector = new GestureDetector(ctx, new GestureListener());
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return gestureDetector.onTouchEvent(event);
        }

        private final class GestureListener extends GestureDetector.SimpleOnGestureListener {

            private static final int SWIPE_THRESHOLD = 150;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!isInCenter(e1)) {
                    return false;
                }
                float diffY = e2.getY() - e1.getY();
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            Log.d(MAIN_SERVICE_LOG_TAG, "Swipe right");
                        } else {
                            Log.d(MAIN_SERVICE_LOG_TAG, "Swipe left");
                        }
                    }
                } else if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) {
                        Log.d(MAIN_SERVICE_LOG_TAG, "Swipe bottom");
                    } else {
                        Log.d(MAIN_SERVICE_LOG_TAG, "Swipe top");
                        if (prefs.swipeToStop) {
                            stopSelf();
                            return true;
                        }
                        if (prefs.getStringByKey(SWIPE_UP, "off").equals("speak")) {
                            speakCurrentStatus();
                            return true;
                        }
                    }

                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Log.d(MAIN_SERVICE_LOG_TAG, "Double tap" + prefs.getStringByKey(DOUBLE_TAP, ""));
                if (prefs.doubleTapToStop) {
                    stopSelf();
                    return true;
                }
                if (prefs.getStringByKey(DOUBLE_TAP, "unlock").equals("speak")) {
                    speakCurrentStatus();
                    return true;
                }
                return false;
            }

            private boolean isInCenter(MotionEvent e) {
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                return e.getX() > width / 4 && e.getX() < width * 3 / 4 && e.getY() > height / 2.5 && e.getY() < height * 4 / 5;
            }
        }
    }
}
