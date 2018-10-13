package com.smewise.camera2.module;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.smewise.camera2.Config;
import com.smewise.camera2.R;
import com.smewise.camera2.callback.CameraUiEvent;
import com.smewise.camera2.callback.MenuInfo;
import com.smewise.camera2.callback.RequestCallback;
import com.smewise.camera2.manager.CameraSettings;
import com.smewise.camera2.manager.Controller;
import com.smewise.camera2.manager.DeviceManager;
import com.smewise.camera2.manager.FocusOverlayManager;
import com.smewise.camera2.manager.Session;
import com.smewise.camera2.manager.SingleDeviceManager;
import com.smewise.camera2.manager.VideoSession;
import com.smewise.camera2.ui.CameraBaseMenu;
import com.smewise.camera2.ui.CameraMenu;
import com.smewise.camera2.ui.ShutterButton;
import com.smewise.camera2.ui.VideoUI;
import com.smewise.camera2.utils.CameraThread;
import com.smewise.camera2.utils.FileSaver;
import com.smewise.camera2.utils.MediaFunc;

/**
 * Created by wenzhe on 16-3-8.
 */
public class VideoModule extends CameraModule implements FileSaver.FileListener,
        CameraBaseMenu.OnMenuClickListener {

    private SurfaceTexture mSurfaceTexture;
    private VideoUI mUI;
    private VideoSession mSession;
    private SingleDeviceManager mDeviceMgr;
    private FocusOverlayManager mFocusManager;
    private CameraMenu mCameraMenu;

    private static final String TAG = Config.getTag(VideoModule.class);

    @Override
    protected void init() {
        mUI = new VideoUI(appContext, mainHandler, mCameraUiEvent);
        mUI.setCoverView(getCoverView());
        mDeviceMgr = new SingleDeviceManager(appContext, getCameraThread(), mCameraEvent);
        mFocusManager = new FocusOverlayManager(getBaseUI().getFocusView(), mainHandler.getLooper());
        mFocusManager.setListener(mCameraUiEvent);
        mCameraMenu = new CameraMenu(appContext, R.xml.menu_preference, mMenuInfo);
        mCameraMenu.setOnMenuClickListener(this);
        mSession = new VideoSession(appContext, mainHandler, getSettings());
    }

    @Override
    public void start() {
        String cameraId = getSettings().getGlobalPref(
                CameraSettings.KEY_VIDEO_ID, mDeviceMgr.getCameraIdList()[0]);
        mDeviceMgr.setCameraId(cameraId);
        mDeviceMgr.openCamera(mainHandler);
        // when module changed , need update listener
        fileSaver.setFileListener(this);
        getBaseUI().setCameraUiEvent(mCameraUiEvent);
        getBaseUI().setMenuView(mCameraMenu.getView());
        getBaseUI().setShutterMode(ShutterButton.VIDEO_MODE);
        addModuleView(mUI.getRootView());
        loadSettingFromPref();
        Log.d(TAG, "start module");
    }

    private DeviceManager.CameraEvent mCameraEvent = new DeviceManager.CameraEvent() {
        @Override
        public void onDeviceOpened(CameraDevice device) {
            super.onDeviceOpened(device);
            Log.d(TAG, "camera opened");
            mSession.applyRequest(Session.RQ_SET_DEVICE, device);
            enableState(Controller.CAMERA_STATE_OPENED);
            if (stateEnabled(Controller.CAMERA_STATE_UI_READY)) {
                mSession.applyRequest(Session.RQ_START_PREVIEW, mSurfaceTexture, mRequestCallback);
            }
        }

        @Override
        public void onDeviceClosed() {
            super.onDeviceClosed();
            disableState(Controller.CAMERA_STATE_OPENED);
            if (mUI != null) {
                mUI.resetFrameCount();
            }
            Log.d(TAG, "camera closed");
        }
    };

    private RequestCallback mRequestCallback = new RequestCallback() {
        @Override
        public void onViewChange(int width, int height) {
            super.onViewChange(width, height);
            getBaseUI().updateUiSize(width, height);
            mFocusManager.setPreviewSize(width, height);
        }

        @Override
        public void onAFStateChanged(int state) {
            super.onAFStateChanged(state);
            updateAFState(state, mFocusManager);
        }

        @Override
        public void onRecordStarted(boolean success) {
            super.onRecordStarted(success);
            Log.i(TAG, "start record onRecordStarted");
            getBaseUI().setUIClickable(true);
            if (success) {
                getBaseUI().setShutterMode(ShutterButton.VIDEO_RECORDING_MODE);
                mUI.startVideoTimer();
            } else {
                disableState(Controller.CAMERA_STATE_START_RECORD);
                getBaseUI().setShutterMode(ShutterButton.VIDEO_MODE);
            }
        }
    };

    @Override
    public void stop() {
        mCameraMenu.close();
        getBaseUI().setCameraUiEvent(null);
        getBaseUI().setShutterMode(ShutterButton.PHOTO_MODE);
        getCoverView().showCover();
        getBaseUI().removeMenuView();
        mFocusManager.removeDelayMessage();
        mFocusManager.hideFocusUI();
        if (stateEnabled(Controller.CAMERA_STATE_START_RECORD)) {
            stopVideoRecording();
        }
        mSession.release();
        mDeviceMgr.releaseCamera();
        Log.d(TAG, "stop module");
    }

    private void startVideoRecording() {
        enableState(Controller.CAMERA_STATE_START_RECORD);
        getBaseUI().setUIClickable(false);
        if (stateEnabled(Controller.CAMERA_STATE_UI_READY)) {
            Log.i(TAG, "start record start");
            getCameraThread().post(new Runnable() {
                @Override
                public void run() {
                    mSession.applyRequest(Session.RQ_START_RECORD,
                            getToolKit().getOrientation());
                }
            });
            Log.i(TAG, "start record end");
        }
    }

    private void stopVideoRecording() {
        disableState(Controller.CAMERA_STATE_START_RECORD);
        getBaseUI().setShutterMode(ShutterButton.VIDEO_MODE);
        getBaseUI().setUIClickable(false);
        Log.i(TAG, "stop record start");
        getCameraThread().post(new Runnable() {
            @Override
            public void run() {
                mSession.applyRequest(Session.RQ_STOP_RECORD);
            }
        }, new CameraThread.JobItem.JobCallback() {
            @Override
            public void onJobDone() {
                mUI.stopVideoTimer();
                getBaseUI().setUIClickable(true);
                Log.i(TAG, "stop record end");
            }
        });
    }

    /**
     * FileSaver.FileListener
     * @param uri image file uri
     * @param path image file path
     * @param thumbnail image thumbnail
     */
    @Override
    public void onFileSaved(Uri uri, String path, Bitmap thumbnail) {
        MediaFunc.setCurrentUri(uri);
        mUI.setUIClickable(true);
        getBaseUI().setUIClickable(true);
        getBaseUI().setThumbnail(thumbnail);
        Log.d(TAG, "uri:" + uri.toString());
    }

    /**
     * callback for file save error
     * @param msg error msg
     */
    @Override
    public void onFileSaveError(String msg) {
        Toast.makeText(appContext,msg, Toast.LENGTH_LONG).show();
        mUI.setUIClickable(true);
        getBaseUI().setUIClickable(true);
    }

    private CameraUiEvent mCameraUiEvent = new CameraUiEvent() {

        @Override
        public void onPreviewUiReady(SurfaceTexture mainSurface, SurfaceTexture auxSurface) {
            Log.d(TAG, "onSurfaceTextureAvailable");
            mSurfaceTexture = mainSurface;
            enableState(Controller.CAMERA_STATE_UI_READY);
            if (stateEnabled(Controller.CAMERA_STATE_OPENED)) {
                mSession.applyRequest(Session.RQ_START_PREVIEW, mSurfaceTexture, mRequestCallback);
            }
        }

        @Override
        public void onPreviewUiDestroy() {
            disableState(Controller.CAMERA_STATE_UI_READY);
            Log.d(TAG, "onSurfaceTextureDestroyed");
        }

        @Override
        public void onTouchToFocus(float x, float y) {
            mFocusManager.startFocus(x, y);
            CameraCharacteristics c = mDeviceMgr.getCharacteristics();
            MeteringRectangle focusRect = mFocusManager.getFocusArea(c, true);
            MeteringRectangle meterRect = mFocusManager.getFocusArea(c, false);
            mSession.applyRequest(Session.RQ_AF_AE_REGIONS, focusRect, meterRect);
        }

        @Override
        public void resetTouchToFocus() {
            if (stateEnabled(Controller.CAMERA_MODULE_RUNNING)) {
                mSession.applyRequest(Session.RQ_FOCUS_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
        }

        @Override
        public <T> void onSettingChange(CaptureRequest.Key<T> key, T value) {
            if (key == CaptureRequest.LENS_FOCUS_DISTANCE) {
                mSession.applyRequest(Session.RQ_FOCUS_DISTANCE, value);
            }
        }

        @Override
        public <T> void onAction(String type, T value) {
            switch (type) {
                case CameraUiEvent.ACTION_CLICK:
                    handleClick((View) value);
                    break;
                case CameraUiEvent.ACTION_CHANGE_MODULE:
                    setNewModule((Integer) value);
                    break;
                case CameraUiEvent.ACTION_SWITCH_CAMERA:
                    break;
                case CameraUiEvent.ACTION_PREVIEW_READY:
                    getCoverView().hideCoverWithAnimation();
                    break;
                default:
                    break;
            }
        }
    };

    private void loadSettingFromPref() {
        mSession.setRequest(Session.RQ_FLASH_MODE,
                mMenuInfo.getCurrentValue(CameraSettings.KEY_FLASH_MODE));
    }



    private MenuInfo mMenuInfo = new MenuInfo() {
        @Override
        public String[] getCameraIdList() {
            return mDeviceMgr.getCameraIdList();
        }

        @Override
        public String getCurrentCameraId() {
            return getSettings().getGlobalPref(CameraSettings.KEY_CAMERA_ID);
        }

        @Override
        public String getCurrentValue(String key) {
            return getSettings().getGlobalPref(key);
        }
    };

    private void handleClick(View view) {
        switch (view.getId()) {
            case R.id.btn_shutter:
                handleShutterClick();
                break;
            case R.id.btn_setting:
                showSetting();
                break;
            case R.id.thumbnail:
                MediaFunc.goToGallery(appContext);
                break;
            case R.id.ll_record_timer:
                // TODO for pause/resume video recording
                //handleTimerViewClick();
                break;
            default:
                break;
        }
    }

    private void handleShutterClick() {
        if (stateEnabled(Controller.CAMERA_STATE_START_RECORD)) {
            stopVideoRecording();
            mSession.applyRequest(Session.RQ_START_PREVIEW,
                    mSurfaceTexture, mRequestCallback);
        } else {
            startVideoRecording();
        }
    }

    private void handleTimerViewClick() {
        if (stateEnabled(Controller.CAMERA_STATE_PAUSE_RECORD)) {
            disableState(Controller.CAMERA_STATE_PAUSE_RECORD);
            mUI.refreshPauseButton(true);
        } else {
            enableState(Controller.CAMERA_STATE_PAUSE_RECORD);
            mUI.refreshPauseButton(false);
        }
    }

    /**
     * CameraBaseMenu.OnMenuClickListener
     * @param key clicked menu key
     * @param value clicked menu value
     */
    @Override
    public void onMenuClick(String key, String value) {
        switch (key) {
            case CameraSettings.KEY_SWITCH_CAMERA:
                switchCamera();
                break;
            case CameraSettings.KEY_FLASH_MODE:
                getSettings().setPrefValueById(mDeviceMgr.getCameraId(), key, value);
                mSession.applyRequest(Session.RQ_FLASH_MODE, value);
                break;
            default:
                break;
        }
    }

    private void switchCamera() {
        int currentId = Integer.parseInt(mDeviceMgr.getCameraId());
        currentId++;
        if (currentId >= mDeviceMgr.getCameraIdList().length) {
            currentId = 0;
        }
        String switchId = String.valueOf(currentId);
        mDeviceMgr.setCameraId(switchId);
        boolean ret = getSettings().setGlobalPref(CameraSettings.KEY_VIDEO_ID, switchId);
        if (ret) {
            stopModule();
            startModule();
        } else {
            Log.e(TAG, "set camera id pref fail");
        }
    }
}
