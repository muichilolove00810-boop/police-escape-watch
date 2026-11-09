package com.grara.policeescape;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.window.OnBackInvokedDispatcher;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;

public class MainActivity extends Activity {
    private GameView game;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game = new GameView(this);
        game.updater = new Updater(this);
        game.sfx = new SoundFx(this);
        setContentView(game);
        game.requestFocus();
        if (Build.VERSION.SDK_INT >= 33) {
            // 최신 Wear OS 는 onBackPressed 대신 이 콜백으로 뒤로 버튼을 전달한다
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        if (!game.onBack()) finish();
                    });
        }
    }

    // 베젤(로터리) 입력: 갤럭시 워치 회전 베젤 / 픽셀 워치 크라운
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL
                && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            game.onRotary(delta);
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        // 상점/게임오버 화면에서는 홈으로, 그 외에는 종료
        if (game != null && game.onBack()) return;
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        game.resume();
    }

    @Override
    protected void onPause() {
        game.pause();
        if (game.sfx != null) game.sfx.stopSiren();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (game.sfx != null) game.sfx.release();
        super.onDestroy();
    }
}
