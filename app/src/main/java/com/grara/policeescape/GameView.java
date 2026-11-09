package com.grara.policeescape;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Paint;
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

/**
 * 3차선 도주 게임.
 * - 뒤에서 경찰차가 쫓아온다 (chase 값이 0 이 되면 체포).
 * - 앞에서 장애물이 내려온다. 부딪히면 경찰이 확 가까워진다.
 * - 코인을 먹으면 점수 + 경찰이 조금 멀어진다.
 */
public class GameView extends View {

    private static final int STATE_READY = 0, STATE_PLAY = 1, STATE_CRASH = 2, STATE_OVER = 3;
    private static final int OB_CONE = 0, OB_BARRIER = 1, OB_CAR = 2;

    private static class Obstacle {
        int lane, type, color;
        float y;
        boolean passed;
    }

    private static class Coin {
        int lane;
        float y;
    }

    private static class Spark {
        float x, y, vx, vy, life, maxLife, size, grow;
        int color;
        boolean smoke;
    }

    private static class FText {
        float x, y, life;
        String s;
        int color;
    }

    private static final int[] CIVIL_COLORS = {
            Color.rgb(60, 140, 255), Color.rgb(80, 200, 120), Color.rgb(170, 90, 230), Color.rgb(240, 210, 60)
    };

    private final Random rnd = new Random();
    private final ArrayList<Obstacle> obstacles = new ArrayList<>();
    private final ArrayList<Coin> coins = new ArrayList<>();
    private final ArrayList<Spark> sparks = new ArrayList<>();
    private final ArrayList<FText> ftexts = new ArrayList<>();
    private final Matrix mtx = new Matrix();
    private final Path path = new Path();
    private Shader glowShader, beamShader, vignette;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Vibrator vibrator;
    private final SharedPreferences prefs;

    private int state = STATE_READY;
    private boolean running;
    private long lastNanos;

    // 화면/도로 치수
    private float W, H, cx, roadW, laneW, carW, carH, playerY, coinR;

    // 플레이어
    private int lane = 1;
    private float playerX, tilt, invuln, slowTimer;

    // 경찰
    private float chase = 0.7f;      // 1 = 멀리, 0 = 체포
    private float policeX, policeY;
    private float sirenT;

    // 진행
    private float time, speed, roadOffset, spawnAcc, nextGap, crashTimer, shake, flashText, hitText;
    private int score, best, coinCount, totalCoins, lastLevel = 1;
    private int lastFreeLane = 1;

    // 입력
    private float downX, downY;
    private boolean swipeLock;
    private float rotAcc;
    private long lastRotEvent, lastRotMove;

    /** 자동 업데이트 (게임 중이 아닐 때만 확인/설치). */
    Updater updater;
    /** 효과음 (블루투스 오디오 연결 시에만 소리남). */
    SoundFx sfx;
    private float hitFlash, exhaustT, lightOffset;
    private String versionLabel;

    public GameView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setFocusableInTouchMode(true);
        vibrator = ctx.getSystemService(Vibrator.class);
        prefs = ctx.getSharedPreferences("game", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        totalCoins = prefs.getInt("coins", 0);
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
        coinR = laneW * 0.17f;
        playerY = h * 0.58f;
        playerX = laneX(lane);
        policeX = playerX;
        policeY = targetPoliceY();
        glowShader = new RadialGradient(0, 0, 1, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        beamShader = new LinearGradient(0, 0, 0, 1, Color.argb(120, 255, 245, 200), Color.TRANSPARENT, Shader.TileMode.CLAMP);
        vignette = new RadialGradient(cx, h / 2f, w * 0.72f,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(200, 0, 0, 0)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }

    /** 색 있는 원형 빛 번짐 (셰이더 재사용). */
    private void glow(Canvas c, float x, float y, float rx, float ry, int color) {
        mtx.setScale(rx, ry);
        mtx.postTranslate(x, y);
        glowShader.setLocalMatrix(mtx);
        p.setShader(glowShader);
        p.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        r.set(x - rx, y - ry, x + rx, y + ry);
        c.drawOval(r, p);
        p.setShader(null);
        p.setColorFilter(null);
    }

    private float laneX(int i) {
        return cx + (i - 1) * laneW;
    }

    private float targetPoliceY() {
        return playerY + carH * 1.08f + Math.max(0, chase) * H * 0.24f;
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
        obstacles.clear();
        coins.clear();
        sparks.clear();
        lane = 1;
        playerX = laneX(1);
        tilt = 0;
        invuln = 0;
        slowTimer = 0;
        chase = 0.75f;
        time = 0;
        lastLevel = 1;
        score = 0;
        coinCount = 0;
        spawnAcc = 0;
        nextGap = H * 0.5f;
        crashTimer = 0;
        shake = 0;
        hitText = 0;
        hitFlash = 0;
        ftexts.clear();
        state = STATE_PLAY;
        buzz(30);
        if (sfx != null) sfx.startFx();
    }

    // ---------------- 입력 ----------------

    private void move(int dir) {
        if (state != STATE_PLAY) return;
        int n = Math.max(0, Math.min(2, lane + dir));
        if (n != lane) {
            lane = n;
            tilt = dir;
            buzz(12);
            if (sfx != null) sfx.swooshFx();
            // 차선 변경 시 타이어 연기
            smoke(playerX - dir * carW * 0.4f, playerY + carH * 0.35f, 2);
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
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > W * 0.10f && Math.abs(dx) > Math.abs(dy)) {
                    move(dx > 0 ? 1 : -1);
                    // 손을 떼지 않고 계속 밀면 한 칸 더
                    downX = e.getX();
                    downY = e.getY();
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

    // ---------------- 업데이트 ----------------

    private int level() {
        return 1 + (int) (time / 10f);
    }

    private void update(float dt) {
        sirenT += dt;
        if (state == STATE_PLAY) {
            time += dt;
            int lv = level();
            if (lv > lastLevel) {
                lastLevel = lv;
                flashText = 1.3f;
                buzz(40);
                if (sfx != null) sfx.levelFx();
            }
            float target = 0.55f + (lv - 1) * 0.16f + (time % 10f) * 0.006f;
            float want = H * Math.min(3.2f, target) * (slowTimer > 0 ? 0.55f : 1f);
            speed += (want - speed) * Math.min(1f, dt * 2.5f);
        } else if (state == STATE_READY) {
            speed = H * 0.35f;
        } else {
            speed = Math.max(0, speed - H * 2.5f * dt);
        }
        roadOffset = (roadOffset + speed * dt) % (H * 0.2f);

        playerX += (laneX(lane) - playerX) * Math.min(1f, dt * 16f);
        tilt += (0 - tilt) * Math.min(1f, dt * 8f);
        shake = Math.max(0, shake - dt * 2.5f);
        flashText = Math.max(0, flashText - dt);
        hitText = Math.max(0, hitText - dt);
        invuln = Math.max(0, invuln - dt);
        slowTimer = Math.max(0, slowTimer - dt);

        // 경찰은 플레이어 차선을 살짝 늦게 따라온다
        policeX += (playerX - policeX) * Math.min(1f, dt * 3.5f);
        float py = state == STATE_CRASH || state == STATE_OVER
                ? playerY + carH * 0.98f      // 체포: 바짝 붙음
                : targetPoliceY();
        policeY += (py - policeY) * Math.min(1f, dt * 4f);

        for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            s.x += s.vx * dt;
            s.y += s.vy * dt + speed * dt * (s.smoke ? 0.8f : 0.5f);
            s.size += s.grow * dt;
            s.life -= dt;
            if (s.life <= 0) it.remove();
        }
        for (Iterator<FText> it = ftexts.iterator(); it.hasNext(); ) {
            FText f = it.next();
            f.y -= H * 0.25f * dt;
            f.life -= dt;
            if (f.life <= 0) it.remove();
        }
        hitFlash = Math.max(0, hitFlash - dt * 3f);
        lightOffset = (lightOffset + speed * dt) % (H * 0.55f);

        // 배기가스
        if (state == STATE_PLAY || state == STATE_READY) {
            exhaustT += dt;
            float every = slowTimer > 0 ? 0.03f : 0.07f;
            while (exhaustT > every) {
                exhaustT -= every;
                Spark s = new Spark();
                s.smoke = true;
                s.x = playerX + carW * (rnd.nextBoolean() ? 0.22f : -0.22f);
                s.y = playerY + carH * 0.5f;
                s.vx = (rnd.nextFloat() - 0.5f) * W * 0.05f;
                s.vy = W * 0.05f;
                s.size = carW * 0.08f;
                s.grow = carW * (slowTimer > 0 ? 0.6f : 0.3f);
                s.life = s.maxLife = 0.45f;
                s.color = slowTimer > 0 ? Color.rgb(70, 70, 75) : Color.rgb(150, 150, 160);
                sparks.add(s);
            }
        }

        // 사이렌 소리: 경찰이 가까울수록 크게
        if (sfx != null) {
            float vol = 0;
            if (state == STATE_PLAY) vol = 0.12f + 0.65f * (1f - chase);
            else if (state == STATE_CRASH) vol = 0.85f;
            else if (state == STATE_OVER) vol = Math.max(0, 0.85f - (crashTimer - 0.9f) * 0.6f);
            sfx.siren(vol);
        }

        if (state == STATE_CRASH || state == STATE_OVER) {
            crashTimer += dt;
            if (state == STATE_CRASH && crashTimer > 0.9f) state = STATE_OVER;
        }

        if (updater != null && (state == STATE_READY || (state == STATE_OVER && crashTimer > 1.5f))) {
            updater.checkIfDue();
            if (updater.isReady()) updater.installIfReady();
        }

        float move = speed * dt;
        for (Obstacle o : obstacles) o.y += move;
        for (Coin c : coins) c.y += move;

        if (state != STATE_PLAY) return;

        // 경찰과의 거리: 시간이 지나면 조금씩 회복, 레벨이 높을수록 회복이 느림
        float recover = Math.max(0.018f, 0.055f - level() * 0.003f);
        chase = Math.min(1f, chase + recover * dt);

        spawnAcc += move;
        if (spawnAcc >= nextGap) {
            spawnAcc = 0;
            spawnRow();
            float minGapTime = Math.max(0.45f, 0.95f - time * 0.012f);
            nextGap = speed * (minGapTime + rnd.nextFloat() * 0.35f);
        }

        // 장애물 충돌
        for (Iterator<Obstacle> it = obstacles.iterator(); it.hasNext(); ) {
            Obstacle o = it.next();
            float ow = obW(o.type), oh = obH(o.type);
            float ox = laneX(o.lane);
            if (invuln <= 0
                    && Math.abs(ox - playerX) < (ow + carW) * 0.5f * 0.82f
                    && Math.abs(o.y - playerY) < (oh + carH) * 0.5f * 0.82f) {
                hit(ox, o.y, o.type);
                it.remove();
                if (state != STATE_PLAY) return;
                continue;
            }
            if (!o.passed && o.y - oh / 2 > playerY + carH / 2) {
                o.passed = true;
                score += 10 * level();
            }
            if (o.y - oh > H) it.remove();
        }

        // 코인
        for (Iterator<Coin> it = coins.iterator(); it.hasNext(); ) {
            Coin c = it.next();
            float cxl = laneX(c.lane);
            if (Math.abs(cxl - playerX) < carW * 0.6f + coinR && Math.abs(c.y - playerY) < carH * 0.5f + coinR) {
                it.remove();
                coinCount++;
                score += 5 * level();
                chase = Math.min(1f, chase + 0.025f);
                burst(cxl, c.y, Color.rgb(255, 215, 50), 7);
                floatText(cxl, c.y, "+" + (5 * level()), Color.rgb(255, 225, 90));
                buzz(8);
                if (sfx != null) sfx.coin();
                continue;
            }
            if (c.y - coinR > H) it.remove();
        }
    }

    private float obW(int type) {
        switch (type) {
            case OB_CONE: return laneW * 0.42f;
            case OB_BARRIER: return laneW * 0.86f;
            default: return carW;
        }
    }

    private float obH(int type) {
        switch (type) {
            case OB_CONE: return laneW * 0.42f;
            case OB_BARRIER: return carH * 0.34f;
            default: return carH;
        }
    }

    private void spawnRow() {
        boolean two = time > 8 && rnd.nextFloat() < Math.min(0.55f, 0.2f + time * 0.008f);
        int a = rnd.nextInt(3);
        int b = -1;
        addObstacle(a);
        if (two) {
            b = (a + 1 + rnd.nextInt(2)) % 3;
            addObstacle(b);
        }
        // 비어 있는 차선 중 하나에 코인 줄
        int free;
        do {
            free = rnd.nextInt(3);
        } while (free == a || free == b);
        lastFreeLane = free;
        if (rnd.nextFloat() < 0.6f) {
            int n = 3 + rnd.nextInt(3);
            for (int i = 0; i < n; i++) {
                Coin c = new Coin();
                c.lane = free;
                c.y = -carH - i * coinR * 3.2f;
                coins.add(c);
            }
        }
    }

    private void addObstacle(int ln) {
        Obstacle o = new Obstacle();
        o.lane = ln;
        float rr = rnd.nextFloat();
        o.type = rr < 0.4f ? OB_CONE : rr < 0.7f ? OB_BARRIER : OB_CAR;
        o.color = CIVIL_COLORS[rnd.nextInt(CIVIL_COLORS.length)];
        o.y = -carH;
        // 장애물과 겹치는 코인은 치운다
        for (Iterator<Coin> it = coins.iterator(); it.hasNext(); ) {
            Coin c = it.next();
            if (c.lane == ln && Math.abs(c.y - o.y) < carH * 1.2f) it.remove();
        }
        obstacles.add(o);
    }

    private void hit(float x, float y, int type) {
        float dmg = Math.min(0.5f, 0.36f + level() * 0.01f);
        chase -= dmg;
        shake = 0.7f;
        invuln = 1.1f;
        slowTimer = 0.7f;
        hitText = 0.9f;
        burst(x, y, type == OB_CONE ? Color.rgb(255, 130, 20) : Color.rgb(255, 230, 120), 16);
        smoke(x, y, 6);
        hitFlash = 1f;
        if (sfx != null) sfx.crashFx();
        if (chase <= 0) {
            caught();
        } else if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void caught() {
        chase = 0;
        state = STATE_CRASH;
        crashTimer = 0;
        shake = 1f;
        int total = totalScore();
        if (total > best) best = total;
        totalCoins += coinCount;
        prefs.edit().putInt("best", best).putInt("coins", totalCoins).apply();
        if (sfx != null) sfx.caughtFx();
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 60, 120, 60, 250}, -1));
        }
    }

    private void burst(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) {
            Spark s = new Spark();
            s.x = x;
            s.y = y;
            double a = rnd.nextDouble() * Math.PI * 2;
            float v = W * (0.2f + rnd.nextFloat() * 0.5f);
            s.vx = (float) Math.cos(a) * v;
            s.vy = (float) Math.sin(a) * v;
            s.life = s.maxLife = 0.35f + rnd.nextFloat() * 0.3f;
            s.size = W * 0.012f;
            s.color = color;
            sparks.add(s);
        }
    }

    private void smoke(float x, float y, int n) {
        for (int i = 0; i < n; i++) {
            Spark s = new Spark();
            s.smoke = true;
            s.x = x + (rnd.nextFloat() - 0.5f) * carW * 0.5f;
            s.y = y + (rnd.nextFloat() - 0.5f) * carW * 0.5f;
            s.vx = (rnd.nextFloat() - 0.5f) * W * 0.15f;
            s.vy = (rnd.nextFloat() - 0.5f) * W * 0.15f;
            s.size = carW * 0.15f;
            s.grow = carW * 0.8f;
            s.life = s.maxLife = 0.5f + rnd.nextFloat() * 0.3f;
            s.color = Color.rgb(120, 120, 128);
            sparks.add(s);
        }
    }

    private void floatText(float x, float y, String str, int color) {
        FText f = new FText();
        f.x = x;
        f.y = y;
        f.s = str;
        f.color = color;
        f.life = 0.7f;
        ftexts.add(f);
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
        drawSpeedLines(c);
        for (Coin co : coins) drawCoin(c, laneX(co.lane), co.y);
        for (Obstacle o : obstacles) drawObstacle(c, o);
        // 연기는 차 밑에
        for (Spark s : sparks) {
            if (!s.smoke) continue;
            p.setColor(s.color);
            p.setAlpha((int) (110 * (s.life / s.maxLife)));
            c.drawCircle(s.x, s.y, s.size, p);
        }
        p.setAlpha(255);
        drawHeadlights(c);
        boolean blink = invuln > 0 && ((int) (invuln * 12)) % 2 == 0;
        if (!blink) drawPlayer(c, playerX, playerY);
        drawPolice(c, policeX, policeY, sirenT);
        for (Spark s : sparks) {
            if (s.smoke) continue;
            p.setColor(s.color);
            p.setAlpha((int) (255 * Math.min(1f, s.life * 3)));
            c.drawCircle(s.x, s.y, s.size, p);
        }
        p.setAlpha(255);
        for (FText f : ftexts) {
            text.setTextSize(W * 0.06f);
            text.setColor(f.color);
            text.setAlpha((int) (255 * Math.min(1f, f.life * 2)));
            c.drawText(f.s, f.x, f.y, text);
        }
        text.setAlpha(255);
        c.restore();

        drawLightWash(c);
        p.setShader(vignette);
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        if (hitFlash > 0) {
            p.setColor(Color.argb((int) (170 * hitFlash), 255, 240, 220));
            c.drawRect(0, 0, W, H, p);
        }
        drawDanger(c);
        drawHud(c);
        if (running) postInvalidateOnAnimation();
    }

    private void drawRoad(Canvas c) {
        c.drawColor(Color.rgb(14, 36, 22));
        p.setColor(Color.rgb(18, 44, 27));
        float stripe = H * 0.2f;
        for (float y = -stripe + roadOffset; y < H; y += stripe * 2) {
            c.drawRect(0, y, W, y + stripe, p);
        }
        float left = cx - roadW / 2, right = cx + roadW / 2;
        p.setColor(Color.rgb(36, 38, 45));
        c.drawRect(left, 0, right, H, p);
        // 아스팔트 질감 (도로와 같이 흐르는 얼룩)
        p.setColor(Color.rgb(42, 44, 52));
        float sp = H * 0.2f;
        for (float y = -sp + roadOffset; y < H + sp; y += sp) {
            c.drawCircle(left + laneW * 0.7f, y, laneW * 0.12f, p);
            c.drawCircle(left + laneW * 2.3f, y + sp * 0.5f, laneW * 0.09f, p);
        }
        p.setColor(Color.rgb(250, 200, 40));
        float edge = W * 0.012f;
        c.drawRect(left, 0, left + edge, H, p);
        c.drawRect(right - edge, 0, right, H, p);
        p.setColor(Color.argb(220, 255, 255, 255));
        float dash = H * 0.1f, lw = W * 0.01f;
        for (int i = 1; i <= 2; i++) {
            float x = left + laneW * i;
            for (float y = -dash * 2 + roadOffset; y < H; y += dash * 2) {
                c.drawRect(x - lw / 2, y, x + lw / 2, y + dash, p);
            }
        }
        // 가로등 + 나무 (양쪽 번갈아)
        float gap = H * 0.55f;
        int idx = 0;
        for (float y = -gap + lightOffset; y < H + gap; y += gap, idx++) {
            boolean leftSide = idx % 2 == 0;
            float lx = leftSide ? left - W * 0.035f : right + W * 0.035f;
            glow(c, leftSide ? left + laneW * 0.55f : right - laneW * 0.55f, y,
                    laneW * 1.2f, laneW * 1.0f, Color.argb(70, 255, 200, 120));
            p.setColor(Color.rgb(60, 62, 70));
            c.drawCircle(lx, y, W * 0.018f, p);
            p.setColor(Color.rgb(255, 230, 170));
            c.drawCircle(lx, y, W * 0.009f, p);
            float tx = leftSide ? right + W * 0.1f : left - W * 0.1f;
            float ty = y + gap * 0.3f;
            p.setColor(Color.rgb(8, 24, 14));
            c.drawCircle(tx + W * 0.01f, ty + W * 0.01f, W * 0.06f, p);
            p.setColor(Color.rgb(22, 60, 34));
            c.drawCircle(tx, ty, W * 0.06f, p);
            p.setColor(Color.rgb(30, 78, 44));
            c.drawCircle(tx - W * 0.015f, ty - W * 0.015f, W * 0.035f, p);
        }
    }

    /** 빠를 때 도로 위로 스치는 속도선. */
    private void drawSpeedLines(Canvas c) {
        if (speed < H * 0.9f) return;
        float k = Math.min(1f, (speed - H * 0.9f) / (H * 1.5f));
        int n = 3 + (int) (k * 8);
        p.setColor(Color.argb((int) (40 + 70 * k), 255, 255, 255));
        float len = speed * 0.06f;
        for (int i = 0; i < n; i++) {
            float x = cx + (rnd.nextFloat() - 0.5f) * W * 0.9f;
            float y = rnd.nextFloat() * H;
            c.drawRect(x, y, x + W * 0.004f, y + len, p);
        }
    }

    /** 플레이어 헤드라이트 빛줄기. */
    private void drawHeadlights(Canvas c) {
        float top = playerY - carH * 0.5f;
        float len = carH * 2.2f;
        mtx.setScale(1, -len);
        mtx.postTranslate(0, top);
        beamShader.setLocalMatrix(mtx);
        p.setShader(beamShader);
        for (int s = -1; s <= 1; s += 2) {
            float hx = playerX + s * carW * 0.3f;
            path.reset();
            path.moveTo(hx - carW * 0.08f, top);
            path.lineTo(hx + carW * 0.08f, top);
            path.lineTo(hx + carW * 0.45f + s * carW * 0.1f, top - len);
            path.lineTo(hx - carW * 0.45f + s * carW * 0.1f, top - len);
            path.close();
            c.drawPath(path, p);
        }
        p.setShader(null);
    }

    /** 경찰 경광등 빛이 화면 아래쪽을 물들인다. 체포 시 화면 전체가 번쩍인다. */
    private void drawLightWash(Canvas c) {
        boolean on = ((int) (sirenT * 8)) % 2 == 0;
        int col = on ? Color.rgb(255, 40, 40) : Color.rgb(40, 90, 255);
        if (state == STATE_CRASH || (state == STATE_OVER && crashTimer < 3f)) {
            float a = state == STATE_CRASH ? 0.35f : 0.18f;
            p.setColor((col & 0x00FFFFFF) | ((int) (255 * a) << 24));
            c.drawRect(0, 0, W, H, p);
            return;
        }
        if (state != STATE_PLAY) return;
        float k = 1f - chase;
        int a = (int) (40 + 120 * k);
        glow(c, on ? W * 0.15f : W * 0.85f, H, W * 0.6f, H * (0.35f + 0.25f * k),
                (col & 0x00FFFFFF) | (a << 24));
    }

    /** 위쪽이 앞인 차를 그린다. */
    private void drawCarBody(Canvas c, float x, float y, int body, int roof) {
        float w = carW, h = carH;
        p.setColor(Color.argb(90, 0, 0, 0));
        r.set(x - w / 2 + w * 0.06f, y - h / 2 + h * 0.05f, x + w / 2 + w * 0.06f, y + h / 2 + h * 0.05f);
        c.drawRoundRect(r, w * 0.25f, w * 0.25f, p);
        p.setColor(Color.rgb(15, 15, 18));
        float tw = w * 0.16f, th = h * 0.2f;
        c.drawRect(x - w / 2 - tw * 0.4f, y - h * 0.36f, x - w / 2 + tw * 0.6f, y - h * 0.36f + th, p);
        c.drawRect(x + w / 2 - tw * 0.6f, y - h * 0.36f, x + w / 2 + tw * 0.4f, y - h * 0.36f + th, p);
        c.drawRect(x - w / 2 - tw * 0.4f, y + h * 0.16f, x - w / 2 + tw * 0.6f, y + h * 0.16f + th, p);
        c.drawRect(x + w / 2 - tw * 0.6f, y + h * 0.16f, x + w / 2 + tw * 0.4f, y + h * 0.16f + th, p);
        p.setColor(body);
        r.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        c.drawRoundRect(r, w * 0.28f, w * 0.28f, p);
        p.setColor(roof);
        r.set(x - w * 0.36f, y - h * 0.24f, x + w * 0.36f, y + h * 0.2f);
        c.drawRoundRect(r, w * 0.15f, w * 0.15f, p);
        // 앞유리
        p.setColor(Color.rgb(130, 185, 230));
        r.set(x - w * 0.3f, y - h * 0.27f, x + w * 0.3f, y - h * 0.15f);
        c.drawRoundRect(r, w * 0.06f, w * 0.06f, p);
    }

    private void drawPlayer(Canvas c, float x, float y) {
        c.save();
        c.rotate(tilt * 10f, x, y);
        drawCarBody(c, x, y, Color.rgb(255, 120, 20), Color.rgb(40, 30, 30));
        float w = carW, h = carH;
        p.setColor(Color.rgb(25, 25, 30));
        c.drawRect(x - w * 0.07f, y - h / 2 + h * 0.02f, x + w * 0.07f, y - h * 0.28f, p);
        c.drawRect(x - w * 0.07f, y + h * 0.22f, x + w * 0.07f, y + h / 2 - h * 0.02f, p);
        p.setColor(Color.rgb(255, 245, 180));
        c.drawCircle(x - w * 0.3f, y - h * 0.45f, w * 0.08f, p);
        c.drawCircle(x + w * 0.3f, y - h * 0.45f, w * 0.08f, p);
        c.restore();
    }

    private void drawPolice(Canvas c, float x, float y, float phase) {
        float w = carW, h = carH;
        boolean on = ((int) (phase * 8)) % 2 == 0;
        float ly = y + h * 0.02f, lr = w * 0.15f, lx = w * 0.17f;
        // 빛 번짐 (차 밑에 깔기)
        float gx = on ? x - lx : x + lx;
        int gc = on ? Color.argb(130, 255, 60, 60) : Color.argb(130, 60, 120, 255);
        glow(c, gx, ly, w * 1.6f, w * 1.6f, gc);
        // 헤드라이트가 플레이어 쪽을 비춘다
        glow(c, x, y - h * 0.8f, w * 0.9f, h * 0.6f, Color.argb(50, 255, 250, 220));

        drawCarBody(c, x, y, Color.rgb(240, 240, 245), Color.rgb(25, 28, 38));
        // 보닛/트렁크 검정
        p.setColor(Color.rgb(25, 28, 38));
        r.set(x - w / 2, y - h / 2, x + w / 2, y - h * 0.32f);
        c.drawRoundRect(r, w * 0.28f, w * 0.28f, p);
        r.set(x - w / 2, y + h * 0.3f, x + w / 2, y + h / 2);
        c.drawRoundRect(r, w * 0.28f, w * 0.28f, p);
        // 경광등
        p.setColor(on ? Color.rgb(255, 50, 50) : Color.rgb(110, 20, 20));
        r.set(x - lx - lr, ly - lr * 0.55f, x - 0.02f * w, ly + lr * 0.55f);
        c.drawRoundRect(r, lr * 0.3f, lr * 0.3f, p);
        p.setColor(!on ? Color.rgb(50, 110, 255) : Color.rgb(20, 40, 110));
        r.set(x + 0.02f * w, ly - lr * 0.55f, x + lx + lr, ly + lr * 0.55f);
        c.drawRoundRect(r, lr * 0.3f, lr * 0.3f, p);
        // 헤드라이트
        p.setColor(Color.rgb(255, 250, 210));
        c.drawCircle(x - w * 0.3f, y - h * 0.45f, w * 0.08f, p);
        c.drawCircle(x + w * 0.3f, y - h * 0.45f, w * 0.08f, p);
    }

    private void drawObstacle(Canvas c, Obstacle o) {
        float x = laneX(o.lane), y = o.y;
        switch (o.type) {
            case OB_CONE: {
                float s = obW(OB_CONE);
                p.setColor(Color.argb(90, 0, 0, 0));
                c.drawCircle(x + s * 0.08f, y + s * 0.08f, s * 0.5f, p);
                p.setColor(Color.rgb(255, 120, 20));
                c.drawCircle(x, y, s * 0.5f, p);
                p.setColor(Color.WHITE);
                c.drawCircle(x, y, s * 0.32f, p);
                p.setColor(Color.rgb(255, 120, 20));
                c.drawCircle(x, y, s * 0.18f, p);
                break;
            }
            case OB_BARRIER: {
                float bw = obW(OB_BARRIER), bh = obH(OB_BARRIER);
                p.setColor(Color.argb(90, 0, 0, 0));
                r.set(x - bw / 2 + bh * 0.15f, y - bh / 2 + bh * 0.2f, x + bw / 2 + bh * 0.15f, y + bh / 2 + bh * 0.2f);
                c.drawRect(r, p);
                p.setColor(Color.WHITE);
                r.set(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2);
                c.drawRect(r, p);
                c.save();
                c.clipRect(r);
                p.setColor(Color.rgb(230, 40, 40));
                float st = bh * 0.9f;
                for (float sx = x - bw / 2 - bh; sx < x + bw / 2 + bh; sx += st * 2) {
                    android.graphics.Path path = new android.graphics.Path();
                    path.moveTo(sx, y + bh / 2);
                    path.lineTo(sx + st, y + bh / 2);
                    path.lineTo(sx + st + bh, y - bh / 2);
                    path.lineTo(sx + bh, y - bh / 2);
                    path.close();
                    c.drawPath(path, p);
                }
                c.restore();
                // 경고등
                boolean on = ((int) (sirenT * 4)) % 2 == 0;
                p.setColor(on ? Color.rgb(255, 220, 60) : Color.rgb(120, 100, 20));
                c.drawCircle(x - bw * 0.35f, y - bh / 2, bh * 0.22f, p);
                c.drawCircle(x + bw * 0.35f, y - bh / 2, bh * 0.22f, p);
                break;
            }
            default:
                // 같은 방향으로 가는 느린 일반 차량
                drawCarBody(c, x, y, o.color, Color.rgb(30, 32, 40));
                p.setColor(Color.rgb(220, 30, 30));
                c.drawRect(x - carW * 0.38f, y + carH * 0.44f, x - carW * 0.18f, y + carH * 0.49f, p);
                c.drawRect(x + carW * 0.18f, y + carH * 0.44f, x + carW * 0.38f, y + carH * 0.49f, p);
        }
    }

    private void drawCoin(Canvas c, float x, float y) {
        float sx = Math.abs((float) Math.cos(sirenT * 4 + y * 0.02f));
        float rx = coinR * Math.max(0.25f, sx);
        p.setColor(Color.rgb(200, 140, 10));
        r.set(x - rx, y - coinR, x + rx, y + coinR);
        c.drawOval(r, p);
        p.setColor(Color.rgb(255, 215, 50));
        r.set(x - rx * 0.78f, y - coinR * 0.78f, x + rx * 0.78f, y + coinR * 0.78f);
        c.drawOval(r, p);
        if (sx > 0.5f) {
            text.setTextSize(coinR * 1.2f);
            text.setColor(Color.rgb(200, 140, 10));
            c.drawText("$", x, y + coinR * 0.42f, text);
        }
    }

    /** 경찰이 가까우면 화면 가장자리가 붉게 깜빡인다. */
    private void drawDanger(Canvas c) {
        if (state != STATE_PLAY || chase > 0.35f) return;
        float k = 1f - chase / 0.35f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(sirenT * 10);
        int a = (int) (150 * k * (0.5f + 0.5f * pulse));
        p.setShader(new RadialGradient(cx, H / 2, W * 0.72f,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(a, 255, 0, 0)},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
    }

    private void drawHud(Canvas c) {
        if (state == STATE_PLAY || state == STATE_CRASH) {
            text.setTextSize(W * 0.085f);
            text.setColor(Color.argb(150, 0, 0, 0));
            c.drawText(String.valueOf(totalScore()), cx + 2, H * 0.15f + 2, text);
            text.setColor(Color.WHITE);
            c.drawText(String.valueOf(totalScore()), cx, H * 0.15f, text);
            // 코인 + 레벨
            float iy = H * 0.215f;
            text.setTextSize(W * 0.05f);
            text.setTextAlign(Paint.Align.LEFT);
            float cw = text.measureText(" " + coinCount + "   Lv " + level());
            float sx = cx - (cw + coinR * 1.6f) / 2;
            p.setColor(Color.rgb(255, 215, 50));
            c.drawCircle(sx + coinR * 0.7f, iy - coinR * 0.55f, coinR * 0.7f, p);
            text.setColor(Color.rgb(255, 225, 90));
            c.drawText(" " + coinCount + "   Lv " + level(), sx + coinR * 1.6f, iy, text);
            text.setTextAlign(Paint.Align.CENTER);

            if (flashText > 0) {
                text.setTextSize(W * 0.08f);
                text.setColor(Color.argb((int) (255 * Math.min(1f, flashText)), 255, 220, 60));
                c.drawText("속도 UP! Lv " + level(), cx, H * 0.36f, text);
            }
            if (hitText > 0 && state == STATE_PLAY) {
                text.setTextSize(W * 0.075f);
                text.setColor(Color.argb((int) (255 * Math.min(1f, hitText * 1.5f)), 255, 80, 80));
                c.drawText("쾅! 경찰 접근!", cx, H * 0.30f, text);
            }
            return;
        }

        p.setColor(Color.argb(170, 0, 0, 0));
        c.drawRect(0, 0, W, H, p);

        if (versionLabel == null) versionLabel = "v" + Updater.currentVersionName(getContext());
        String st = updater != null ? updater.status() : "";
        text.setTextSize(W * 0.045f);
        text.setColor(st.isEmpty() ? Color.GRAY : Color.rgb(120, 220, 255));
        c.drawText(st.isEmpty() ? versionLabel : st, cx, H * 0.94f, text);

        if (state == STATE_READY) {
            text.setColor(Color.rgb(255, 120, 20));
            text.setTextSize(W * 0.12f);
            c.drawText("경찰 피하기", cx, H * 0.27f, text);
            boolean on = (SystemClock.uptimeMillis() / 250) % 2 == 0;
            p.setColor(on ? Color.rgb(255, 50, 50) : Color.rgb(50, 110, 255));
            r.set(cx - W * 0.18f, H * 0.315f, cx + W * 0.18f, H * 0.335f);
            c.drawRoundRect(r, H, H, p);
            text.setColor(Color.WHITE);
            text.setTextSize(W * 0.058f);
            c.drawText("장애물 피하고 코인 모으기", cx, H * 0.42f, text);
            c.drawText("부딪히면 경찰이 따라붙어요", cx, H * 0.495f, text);
            text.setTextSize(W * 0.075f);
            text.setColor(Color.rgb(255, 230, 120));
            c.drawText("탭해서 시작", cx, H * 0.62f, text);
            text.setTextSize(W * 0.055f);
            text.setColor(Color.LTGRAY);
            c.drawText("최고 " + best + "  ·  코인 " + totalCoins, cx, H * 0.72f, text);
        } else if (state == STATE_OVER) {
            text.setColor(Color.rgb(255, 70, 70));
            text.setTextSize(W * 0.11f);
            c.drawText("체포됨!", cx, H * 0.3f, text);
            text.setColor(Color.WHITE);
            text.setTextSize(W * 0.14f);
            c.drawText(String.valueOf(totalScore()), cx, H * 0.47f, text);
            text.setTextSize(W * 0.058f);
            text.setColor(Color.rgb(255, 225, 90));
            c.drawText("코인 +" + coinCount + "  (총 " + totalCoins + ")", cx, H * 0.56f, text);
            text.setColor(Color.LTGRAY);
            c.drawText("최고 " + best, cx, H * 0.635f, text);
            if (crashTimer > 0.9f) {
                text.setTextSize(W * 0.075f);
                text.setColor(Color.rgb(255, 230, 120));
                c.drawText("탭해서 다시", cx, H * 0.76f, text);
            }
        }
    }
}
