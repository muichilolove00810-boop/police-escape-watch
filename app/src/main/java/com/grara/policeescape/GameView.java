package com.grara.policeescape;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/** 3차선 도로에서 내려오는 경찰차를 피하는 게임. */
public class GameView extends View {

    private static final int STATE_READY = 0, STATE_PLAY = 1, STATE_CRASH = 2, STATE_OVER = 3;

    private static class Police {
        int lane;
        float y;
        float phase;
        boolean passed;
    }

    private final Random rnd = new Random();
    private final ArrayList<Police> police = new ArrayList<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Vibrator vibrator;
    private final SharedPreferences prefs;

    private int state = STATE_READY;
    private boolean running;
    private long lastNanos;

    // 화면/도로 치수
    private float W, H, cx, roadW, laneW, carW, carH, playerY;

    // 플레이어
    private int lane = 1;
    private float playerX;
    private float tilt;

    // 게임 진행
    private float time, speed, roadOffset, spawnAcc, nextGap, crashTimer, shake;
    private int score, best, dodged;
    private float flashText;

    // 입력
    private float downX, downY;
    private boolean swipeUsed;
    private float rotAcc;
    private long lastRotEvent, lastRotMove;

    public GameView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setFocusableInTouchMode(true);
        vibrator = ctx.getSystemService(Vibrator.class);
        prefs = ctx.getSharedPreferences("game", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        cx = w / 2f;
        roadW = w * 0.66f;
        laneW = roadW / 3f;
        carW = laneW * 0.62f;
        carH = carW * 1.75f;
        playerY = h * 0.76f;
        playerX = laneX(lane);
    }

    private float laneX(int i) {
        return cx + (i - 1) * laneW;
    }

    // ---------------- 생명주기 ----------------

    void resume() {
        running = true;
        lastNanos = System.nanoTime();
        invalidate();
    }

    void pause() {
        running = false;
    }

    private void startGame() {
        police.clear();
        lane = 1;
        playerX = laneX(1);
        tilt = 0;
        time = 0;
        score = 0;
        dodged = 0;
        spawnAcc = 0;
        nextGap = H * 0.6f;
        crashTimer = 0;
        shake = 0;
        state = STATE_PLAY;
        buzz(30);
    }

    // ---------------- 입력 ----------------

    private void move(int dir) {
        if (state != STATE_PLAY) return;
        int n = Math.max(0, Math.min(2, lane + dir));
        if (n != lane) {
            lane = n;
            tilt = dir;
            buzz(12);
        }
    }

    /** 베젤/크라운 회전. 시계 방향 = 오른쪽. */
    void onRotary(float delta) {
        long now = SystemClock.uptimeMillis();
        if (state == STATE_READY || state == STATE_OVER) {
            boolean canStart = state == STATE_READY || crashTimer > 1.2f;
            if (Math.abs(delta) > 0 && now - lastRotMove > 400 && canStart) {
                startGame();
                lastRotMove = now;
            }
            return;
        }
        if (now - lastRotEvent > 300) rotAcc = 0;
        lastRotEvent = now;
        rotAcc += delta;
        if (Math.abs(rotAcc) >= 0.9f) {
            if (now - lastRotMove > 90) {
                move(rotAcc > 0 ? 1 : -1);
                lastRotMove = now;
            }
            rotAcc = 0;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                swipeUsed = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!swipeUsed && Math.abs(dx) > W * 0.10f && Math.abs(dx) > Math.abs(dy)) {
                    move(dx > 0 ? 1 : -1);
                    swipeUsed = true;
                    // 손을 떼지 않고 계속 밀면 한 칸 더
                    downX = e.getX();
                    downY = e.getY();
                    swipeUsed = false;
                    swipeLock = true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                boolean tap = !swipeLock && Math.abs(dx) < W * 0.06f && Math.abs(dy) < W * 0.06f;
                swipeLock = false;
                if (tap) {
                    if (state == STATE_READY) startGame();
                    else if (state == STATE_OVER && crashTimer > 0.9f) startGame();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                swipeLock = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private boolean swipeLock;

    /** 자동 업데이트 (게임 중이 아닐 때만 확인/설치). */
    Updater updater;
    private String versionLabel;

    // ---------------- 업데이트 ----------------

    private void update(float dt) {
        // 대기 화면에서도 도로는 천천히 흐른다
        float baseSpeed = H * 0.35f;
        if (state == STATE_PLAY) {
            time += dt;
            speed = H * Math.min(1.7f, 0.55f + time * 0.028f);
        } else if (state == STATE_READY) {
            speed = baseSpeed;
        } else {
            speed = Math.max(0, speed - H * 2.5f * dt);
        }
        roadOffset = (roadOffset + speed * dt) % (H * 0.2f);

        // 플레이어 위치 보간
        float tx = laneX(lane);
        playerX += (tx - playerX) * Math.min(1f, dt * 16f);
        tilt += (0 - tilt) * Math.min(1f, dt * 8f);
        shake = Math.max(0, shake - dt * 2.5f);
        flashText = Math.max(0, flashText - dt);

        if (state == STATE_CRASH || state == STATE_OVER) {
            crashTimer += dt;
            if (state == STATE_CRASH && crashTimer > 0.7f) state = STATE_OVER;
        }

        if (updater != null && (state == STATE_READY || (state == STATE_OVER && crashTimer > 1.5f))) {
            updater.checkIfDue();
            if (updater.isReady()) updater.installIfReady();
        }

        for (Police c : police) {
            c.y += speed * (state == STATE_PLAY ? 1f : 0.6f) * dt;
            c.phase += dt;
        }

        if (state != STATE_PLAY) return;

        // 스폰: 한 줄에 최대 2대 -> 항상 빠져나갈 차선이 있다
        spawnAcc += speed * dt;
        if (spawnAcc >= nextGap) {
            spawnAcc = 0;
            spawnRow();
            float minGapTime = Math.max(0.42f, 0.95f - time * 0.012f);
            nextGap = speed * (minGapTime + rnd.nextFloat() * 0.35f);
        }

        // 충돌 / 점수
        Iterator<Police> it = police.iterator();
        while (it.hasNext()) {
            Police c = it.next();
            float px = laneX(c.lane);
            if (Math.abs(px - playerX) < carW * 0.82f && Math.abs(c.y - playerY) < carH * 0.82f) {
                crash();
                return;
            }
            if (!c.passed && c.y - carH / 2 > playerY + carH / 2) {
                c.passed = true;
                dodged++;
                score += 10;
                if (dodged % 25 == 0) flashText = 1.2f;
            }
            if (c.y - carH > H) it.remove();
        }
    }

    private void spawnRow() {
        boolean two = time > 8 && rnd.nextFloat() < Math.min(0.55f, 0.2f + time * 0.008f);
        int a = rnd.nextInt(3);
        addPolice(a);
        if (two) {
            int b = (a + 1 + rnd.nextInt(2)) % 3;
            addPolice(b);
        }
    }

    private void addPolice(int ln) {
        Police c = new Police();
        c.lane = ln;
        c.y = -carH;
        c.phase = rnd.nextFloat();
        police.add(c);
    }

    private void crash() {
        state = STATE_CRASH;
        crashTimer = 0;
        shake = 1f;
        int total = totalScore();
        if (total > best) {
            best = total;
            prefs.edit().putInt("best", best).apply();
        }
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 60, 200}, -1));
        }
    }

    private int totalScore() {
        return score + (int) (time * 5);
    }

    private void buzz(int ms) {
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    // ---------------- 그리기 ----------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (running && W > 0) update(dt);

        c.save();
        if (shake > 0) {
            float s = shake * W * 0.03f;
            c.translate((rnd.nextFloat() - 0.5f) * s, (rnd.nextFloat() - 0.5f) * s);
        }
        drawRoad(c);
        for (Police pc : police) drawPolice(c, laneX(pc.lane), pc.y, pc.phase);
        if (state != STATE_OVER || crashTimer < 0.7f) drawPlayer(c, playerX, playerY);
        if (state == STATE_CRASH || state == STATE_OVER) drawBoom(c);
        c.restore();

        drawHud(c);
        if (running) postInvalidateOnAnimation();
    }

    private void drawRoad(Canvas c) {
        c.drawColor(Color.rgb(28, 70, 40)); // 잔디
        // 잔디 줄무늬
        p.setColor(Color.rgb(34, 82, 46));
        float stripe = H * 0.2f;
        for (float y = -stripe + roadOffset; y < H; y += stripe * 2) {
            c.drawRect(0, y, W, y + stripe, p);
        }
        float left = cx - roadW / 2, right = cx + roadW / 2;
        p.setColor(Color.rgb(52, 55, 62));
        c.drawRect(left, 0, right, H, p);
        // 갓길 선
        p.setColor(Color.rgb(250, 200, 40));
        float edge = W * 0.012f;
        c.drawRect(left, 0, left + edge, H, p);
        c.drawRect(right - edge, 0, right, H, p);
        // 차선 점선
        p.setColor(Color.argb(220, 255, 255, 255));
        float dash = H * 0.1f, lw = W * 0.01f;
        for (int i = 1; i <= 2; i++) {
            float x = left + laneW * i;
            for (float y = -dash * 2 + roadOffset; y < H; y += dash * 2) {
                c.drawRect(x - lw / 2, y, x + lw / 2, y + dash, p);
            }
        }
    }

    private void drawCarBody(Canvas c, float x, float y, int body, int roof) {
        float w = carW, h = carH;
        // 그림자
        p.setColor(Color.argb(90, 0, 0, 0));
        r.set(x - w / 2 + w * 0.06f, y - h / 2 + h * 0.05f, x + w / 2 + w * 0.06f, y + h / 2 + h * 0.05f);
        c.drawRoundRect(r, w * 0.25f, w * 0.25f, p);
        // 바퀴
        p.setColor(Color.rgb(15, 15, 18));
        float tw = w * 0.16f, th = h * 0.2f;
        c.drawRect(x - w / 2 - tw * 0.4f, y - h * 0.36f, x - w / 2 + tw * 0.6f, y - h * 0.36f + th, p);
        c.drawRect(x + w / 2 - tw * 0.6f, y - h * 0.36f, x + w / 2 + tw * 0.4f, y - h * 0.36f + th, p);
        c.drawRect(x - w / 2 - tw * 0.4f, y + h * 0.16f, x - w / 2 + tw * 0.6f, y + h * 0.16f + th, p);
        c.drawRect(x + w / 2 - tw * 0.6f, y + h * 0.16f, x + w / 2 + tw * 0.4f, y + h * 0.16f + th, p);
        // 차체
        p.setColor(body);
        r.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        c.drawRoundRect(r, w * 0.28f, w * 0.28f, p);
        // 지붕
        p.setColor(roof);
        r.set(x - w * 0.36f, y - h * 0.2f, x + w * 0.36f, y + h * 0.24f);
        c.drawRoundRect(r, w * 0.15f, w * 0.15f, p);
    }

    private void drawPolice(Canvas c, float x, float y, float phase) {
        // 경찰차: 위에서 내려오므로 앞이 아래쪽
        drawCarBody(c, x, y, Color.rgb(240, 240, 245), Color.rgb(25, 28, 38));
        float w = carW, h = carH;
        // 앞유리 (아래쪽)
        p.setColor(Color.rgb(120, 170, 220));
        r.set(x - w * 0.3f, y + h * 0.14f, x + w * 0.3f, y + h * 0.26f);
        c.drawRoundRect(r, w * 0.06f, w * 0.06f, p);
        // 검은 보닛 줄
        p.setColor(Color.rgb(25, 28, 38));
        r.set(x - w / 2, y + h * 0.3f, x + w / 2, y + h / 2);
        c.drawRoundRect(r, w * 0.28f, w * 0.28f, p);
        r.set(x - w / 2, y - h / 2, x + w / 2, y - h * 0.3f);
        c.drawRoundRect(r, w * 0.28f, w * 0.28f, p);
        // 경광등
        boolean on = ((int) (phase * 8)) % 2 == 0;
        int red = Color.rgb(255, 50, 50), blue = Color.rgb(50, 110, 255);
        float lx = w * 0.17f, ly = y - h * 0.02f, lr = w * 0.15f;
        p.setColor(on ? red : Color.rgb(110, 20, 20));
        r.set(x - lx - lr, ly - lr * 0.55f, x - 0.02f * w, ly + lr * 0.55f);
        c.drawRoundRect(r, lr * 0.3f, lr * 0.3f, p);
        p.setColor(!on ? blue : Color.rgb(20, 40, 110));
        r.set(x + 0.02f * w, ly - lr * 0.55f, x + lx + lr, ly + lr * 0.55f);
        c.drawRoundRect(r, lr * 0.3f, lr * 0.3f, p);
        // 빛 번짐
        float gx = on ? x - lx : x + lx;
        int gc = on ? Color.argb(120, 255, 60, 60) : Color.argb(120, 60, 120, 255);
        p.setShader(new RadialGradient(gx, ly, w * 0.9f, gc, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawCircle(gx, ly, w * 0.9f, p);
        p.setShader(null);
    }

    private void drawPlayer(Canvas c, float x, float y) {
        c.save();
        c.rotate(tilt * 10f, x, y);
        drawCarBody(c, x, y, Color.rgb(255, 120, 20), Color.rgb(40, 30, 30));
        float w = carW, h = carH;
        // 앞유리 (위쪽)
        p.setColor(Color.rgb(130, 190, 235));
        r.set(x - w * 0.3f, y - h * 0.26f, x + w * 0.3f, y - h * 0.14f);
        c.drawRoundRect(r, w * 0.06f, w * 0.06f, p);
        // 레이싱 줄
        p.setColor(Color.rgb(25, 25, 30));
        c.drawRect(x - w * 0.07f, y - h / 2 + h * 0.02f, x + w * 0.07f, y - h * 0.27f, p);
        c.drawRect(x - w * 0.07f, y + h * 0.25f, x + w * 0.07f, y + h / 2 - h * 0.02f, p);
        // 헤드라이트
        p.setColor(Color.rgb(255, 245, 180));
        c.drawCircle(x - w * 0.3f, y - h * 0.45f, w * 0.08f, p);
        c.drawCircle(x + w * 0.3f, y - h * 0.45f, w * 0.08f, p);
        c.restore();
    }

    private void drawBoom(Canvas c) {
        float t = Math.min(1f, crashTimer / 0.7f);
        float rad = carW * (0.6f + t * 1.4f);
        int a = (int) (230 * (1f - t * 0.7f));
        p.setShader(new RadialGradient(playerX, playerY - carH * 0.3f, rad,
                new int[]{Color.argb(a, 255, 255, 200), Color.argb(a, 255, 140, 0), Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(playerX, playerY - carH * 0.3f, rad, p);
        p.setShader(null);
    }

    private void drawHud(Canvas c) {
        if (state == STATE_PLAY || state == STATE_CRASH) {
            // 상단 점수 (둥근 화면 고려해서 가운데 위)
            text.setTextSize(W * 0.09f);
            text.setColor(Color.argb(150, 0, 0, 0));
            c.drawText(String.valueOf(totalScore()), cx + 2, H * 0.17f + 2, text);
            text.setColor(Color.WHITE);
            c.drawText(String.valueOf(totalScore()), cx, H * 0.17f, text);
            if (flashText > 0) {
                text.setTextSize(W * 0.07f);
                text.setColor(Color.argb((int) (255 * Math.min(1f, flashText)), 255, 220, 60));
                c.drawText("속도 UP!", cx, H * 0.4f, text);
            }
            return;
        }

        // 오버레이 (READY / OVER)
        p.setColor(Color.argb(170, 0, 0, 0));
        c.drawRect(0, 0, W, H, p);

        // 하단: 버전 / 업데이트 상태
        if (versionLabel == null) versionLabel = "v" + Updater.currentVersionName(getContext());
        String st = updater != null ? updater.status() : "";
        text.setTextSize(W * 0.05f);
        text.setColor(st.isEmpty() ? Color.GRAY : Color.rgb(120, 220, 255));
        c.drawText(st.isEmpty() ? versionLabel : st, cx, H * 0.88f, text);

        if (state == STATE_READY) {
            text.setColor(Color.rgb(255, 120, 20));
            text.setTextSize(W * 0.12f);
            c.drawText("경찰 피하기", cx, H * 0.36f, text);
            // 경광등 색 바
            long ms = SystemClock.uptimeMillis();
            boolean on = (ms / 250) % 2 == 0;
            p.setColor(on ? Color.rgb(255, 50, 50) : Color.rgb(50, 110, 255));
            r.set(cx - W * 0.18f, H * 0.41f, cx + W * 0.18f, H * 0.43f);
            c.drawRoundRect(r, H, H, p);
            text.setColor(Color.WHITE);
            text.setTextSize(W * 0.065f);
            c.drawText("스와이프 / 베젤로 차선 이동", cx, H * 0.53f, text);
            text.setTextSize(W * 0.075f);
            text.setColor(Color.rgb(255, 230, 120));
            c.drawText("탭해서 시작", cx, H * 0.66f, text);
            if (best > 0) {
                text.setTextSize(W * 0.06f);
                text.setColor(Color.LTGRAY);
                c.drawText("최고 " + best, cx, H * 0.77f, text);
            }
        } else if (state == STATE_OVER) {
            text.setColor(Color.rgb(255, 70, 70));
            text.setTextSize(W * 0.11f);
            c.drawText("체포됨!", cx, H * 0.32f, text);
            text.setColor(Color.WHITE);
            text.setTextSize(W * 0.14f);
            c.drawText(String.valueOf(totalScore()), cx, H * 0.5f, text);
            text.setTextSize(W * 0.06f);
            text.setColor(Color.LTGRAY);
            c.drawText("피한 경찰차 " + dodged + "대 · 최고 " + best, cx, H * 0.6f, text);
            if (crashTimer > 0.9f) {
                text.setTextSize(W * 0.075f);
                text.setColor(Color.rgb(255, 230, 120));
                c.drawText("탭해서 다시", cx, H * 0.73f, text);
            }
        }
    }
}
