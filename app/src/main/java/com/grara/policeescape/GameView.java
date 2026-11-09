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

    private static final int STATE_HOME = 0, STATE_PLAY = 1, STATE_CRASH = 2, STATE_OVER = 3, STATE_SHOP = 4;
    private static final int OB_CONE = 0, OB_BARRIER = 1, OB_CAR = 2;
    private static final int PW_MAGNET = 0, PW_SHIELD = 1;

    // 상점 항목
    private static final int SH_REVIVE = 0, SH_START_SHIELD = 1, SH_START_MAGNET = 2,
            SH_UP_MAGNET = 3, SH_UP_SHIELD = 4, SH_UP_COIN = 5, SH_BACK = 6, SH_COUNT = 7;
    private static final String[] SH_NAME = {"부활", "시작 무적", "자석 시작", "자석 지속시간", "무적 지속시간", "코인 가치", "홈으로"};
    private static final String[] SH_KEY = {"inv_revive", "inv_shield", "inv_magnet", "up_magnet", "up_shield", "up_coin", null};
    private static final int[] SH_MAX = {0, 0, 0, 5, 5, 4, 0};
    private static final float START_SHIELD_SEC = 5f;

    private static class Obstacle {
        int lane, type, color;
        float y;
        boolean passed;
    }

    private static class Coin {
        int lane;
        float x, y;
    }

    private static class Power {
        int lane, type;
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
    private final ArrayList<Power> powers = new ArrayList<>();
    private final Matrix mtx = new Matrix();
    private final Path path = new Path();
    private Shader glowShader, beamShader, vignette;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Vibrator vibrator;
    private final SharedPreferences prefs;

    private int state = STATE_HOME;

    // 메뉴
    private int homeSel = 0, overSel = 0, shopSel = 0;
    private float shopAnim, shopMsgT, menuT;
    private String shopMsg = "";
    private int shopMsgColor;
    private final int[] owned = new int[SH_COUNT];   // 소모품 개수 / 업그레이드 레벨

    // 파워업
    private float magnetT, shieldT, magnetMax = 1, shieldMax = 1, powerCooldown, reviveText;
    private boolean reviveUsed;
    private float coinAcc;
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
        for (int i = 0; i < SH_COUNT; i++) if (SH_KEY[i] != null) owned[i] = prefs.getInt(SH_KEY[i], 0);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    // ---------------- 상점 데이터 ----------------

    private boolean isUpgrade(int i) {
        return SH_MAX[i] > 0;
    }

    /** 가격 (-1 = 최대 레벨). */
    private int price(int i) {
        switch (i) {
            case SH_REVIVE: return 40;
            case SH_START_SHIELD: return 15;
            case SH_START_MAGNET: return 12;
            case SH_UP_MAGNET:
            case SH_UP_SHIELD: return owned[i] >= SH_MAX[i] ? -1 : 30 + 20 * owned[i];
            case SH_UP_COIN: return owned[i] >= SH_MAX[i] ? -1 : 50 + 40 * owned[i];
            default: return 0;
        }
    }

    private float magnetDur(int lv) {
        return 6f + 1.5f * lv;
    }

    private float shieldDur(int lv) {
        return 5f + 1f * lv;
    }

    private float coinMul(int lv) {
        return 1f + 0.25f * lv;
    }

    private static String fmt(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.format(java.util.Locale.US, "%.1f", v);
    }

    private static String fmt2(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.format(java.util.Locale.US, "%.2f", v).replaceAll("0$", "");
    }

    private String shopDesc(int i) {
        switch (i) {
            case SH_REVIVE: return "체포 시 1번 부활 (판당 1회)";
            case SH_START_SHIELD: return "시작 후 " + fmt(START_SHIELD_SEC) + "초 무적";
            case SH_START_MAGNET: return "시작부터 자석 켜짐";
            case SH_UP_MAGNET: return owned[i] >= SH_MAX[i] ? "자석 " + fmt(magnetDur(owned[i])) + "초"
                    : "자석 " + fmt(magnetDur(owned[i])) + "초 → " + fmt(magnetDur(owned[i] + 1)) + "초";
            case SH_UP_SHIELD: return owned[i] >= SH_MAX[i] ? "무적 " + fmt(shieldDur(owned[i])) + "초"
                    : "무적 " + fmt(shieldDur(owned[i])) + "초 → " + fmt(shieldDur(owned[i] + 1)) + "초";
            case SH_UP_COIN: return owned[i] >= SH_MAX[i] ? "코인 x" + fmt2(coinMul(owned[i]))
                    : "코인 x" + fmt2(coinMul(owned[i])) + " → x" + fmt2(coinMul(owned[i] + 1));
            default: return "시작 화면으로 돌아가기";
        }
    }

    private void saveOwned(int i) {
        prefs.edit().putInt(SH_KEY[i], owned[i]).putInt("coins", totalCoins).apply();
    }

    private void buy(int i) {
        if (i == SH_BACK) {
            goHome();
            return;
        }
        int pr = price(i);
        if (pr < 0) {
            shopMessage("최대 레벨!", Color.rgb(140, 200, 255));
            if (sfx != null) sfx.denyFx();
            return;
        }
        if (totalCoins < pr) {
            shopMessage("코인 부족! (" + (pr - totalCoins) + "개 더)", Color.rgb(255, 90, 90));
            shopAnim = 1f;
            buzz(70);
            if (sfx != null) sfx.denyFx();
            return;
        }
        totalCoins -= pr;
        owned[i]++;
        saveOwned(i);
        shopMessage(isUpgrade(i) ? "레벨 업! Lv " + owned[i] : "구매 완료!", Color.rgb(120, 255, 140));
        buzz(25);
        if (sfx != null) sfx.buyFx();
    }

    private void shopMessage(String s, int color) {
        shopMsg = s;
        shopMsgColor = color;
        shopMsgT = 1.8f;
    }

    private void goHome() {
        state = STATE_HOME;
        homeSel = 0;
        menuT = 0;
        if (sfx != null) sfx.clickFx();
    }

    private void openShop() {
        state = STATE_SHOP;
        shopSel = 0;
        shopMsgT = 0;
        menuT = 0;
        if (sfx != null) sfx.clickFx();
    }

    /** 하드웨어 뒤로 버튼. true = 처리함. */
    boolean onBack() {
        if (state == STATE_SHOP || state == STATE_OVER) {
            goHome();
            return true;
        }
        return false;
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
        powers.clear();
        coinAcc = 0;
        reviveUsed = false;
        reviveText = 0;
        magnetT = 0;
        shieldT = 0;
        powerCooldown = 7f + rnd.nextFloat() * 4f;
        state = STATE_PLAY;
        buzz(30);
        if (sfx != null) sfx.startFx();
        // 소모품 자동 사용
        boolean used = false;
        if (owned[SH_START_SHIELD] > 0) {
            owned[SH_START_SHIELD]--;
            saveOwned(SH_START_SHIELD);
            shieldMax = shieldT = START_SHIELD_SEC;
            used = true;
        }
        if (owned[SH_START_MAGNET] > 0) {
            owned[SH_START_MAGNET]--;
            saveOwned(SH_START_MAGNET);
            magnetMax = magnetT = magnetDur(owned[SH_UP_MAGNET]);
            used = true;
        }
        if (used && sfx != null) sfx.powerFx();
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
        if (state == STATE_CRASH) return;
        if (now - lastRotEvent > 300) rotAcc = 0;
        lastRotEvent = now;
        rotAcc += delta;
        if (Math.abs(rotAcc) >= 0.9f) {
            int dir = rotAcc > 0 ? 1 : -1;
            rotAcc = 0;
            boolean menu = state != STATE_PLAY;
            if (now - lastRotMove > (menu ? 140 : 90)) {
                lastRotMove = now;
                if (menu) menuStep(dir);
                else move(dir);
            }
        }
    }

    /** 메뉴에서 선택 이동 (베젤/스와이프). */
    private void menuStep(int dir) {
        if (state == STATE_HOME) {
            int n = Math.max(0, Math.min(1, homeSel + dir));
            if (n == homeSel) return;
            homeSel = n;
        } else if (state == STATE_SHOP) {
            int n = Math.max(0, Math.min(SH_COUNT - 1, shopSel + dir));
            if (n == shopSel) return;
            shopSel = n;
            shopMsgT = 0;
        } else if (state == STATE_OVER) {
            if (crashTimer < 0.9f) return;
            int n = Math.max(0, Math.min(1, overSel + dir));
            if (n == overSel) return;
            overSel = n;
        } else {
            return;
        }
        buzz(8);
        if (sfx != null) sfx.clickFx();
    }

    // 메뉴 버튼 위치 (화면 비율)
    private static final float HOME_B1_T = 0.42f, HOME_B1_B = 0.545f, HOME_B2_T = 0.575f, HOME_B2_B = 0.70f;
    private static final float OVER_B_T = 0.68f, OVER_B_B = 0.80f;
    private static final float SHOP_CARD_T = 0.28f, SHOP_CARD_B = 0.72f;

    private void menuTap(float x, float y) {
        float fy = y / H;
        if (state == STATE_HOME) {
            int sel = homeSel;
            if (fy >= HOME_B1_T - 0.02f && fy <= HOME_B1_B + 0.01f) sel = 0;
            else if (fy >= HOME_B2_T - 0.01f && fy <= HOME_B2_B + 0.03f) sel = 1;
            homeSel = sel;
            if (sel == 0) startGame();
            else openShop();
        } else if (state == STATE_SHOP) {
            if (fy < SHOP_CARD_T) menuStep(-1);
            else if (fy > SHOP_CARD_B) menuStep(1);
            else buy(shopSel);
        } else if (state == STATE_OVER) {
            if (crashTimer < 0.9f) return;
            int sel = overSel;
            if (fy >= OVER_B_T - 0.03f && fy <= OVER_B_B + 0.05f) sel = x < cx ? 0 : 1;
            overSel = sel;
            if (sel == 0) startGame();
            else goHome();
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
                if (state == STATE_PLAY && Math.abs(dx) > W * 0.10f && Math.abs(dx) > Math.abs(dy)) {
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
                boolean wasSwipe = swipeLock;
                swipeLock = false;
                if (state == STATE_PLAY || state == STATE_CRASH || wasSwipe) return true;
                if (tap) {
                    menuTap(e.getX(), e.getY());
                } else if (Math.abs(dx) > W * 0.12f || Math.abs(dy) > W * 0.12f) {
                    if (Math.abs(dy) > Math.abs(dx)) {
                        // 위로 밀기 = 다음 항목
                        menuStep(dy < 0 ? 1 : -1);
                    } else if (state == STATE_SHOP && dx > 0) {
                        goHome();   // 오른쪽으로 밀기 = 뒤로
                    } else if (state == STATE_HOME || state == STATE_OVER) {
                        menuStep(dx > 0 ? 1 : -1);
                    }
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
        } else if (state == STATE_HOME || state == STATE_SHOP) {
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
        menuT += dt;
        shopAnim = Math.max(0, shopAnim - dt * 3f);
        shopMsgT = Math.max(0, shopMsgT - dt);
        reviveText = Math.max(0, reviveText - dt);
        if (state == STATE_PLAY) {
            magnetT = Math.max(0, magnetT - dt);
            shieldT = Math.max(0, shieldT - dt);
        }
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
        if (state == STATE_PLAY || state == STATE_HOME || state == STATE_SHOP) {
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

        if (updater != null && (state == STATE_HOME || state == STATE_SHOP || (state == STATE_OVER && crashTimer > 1.5f))) {
            updater.checkIfDue();
            if (updater.isReady()) updater.installIfReady();
        }

        float move = speed * dt;
        for (Obstacle o : obstacles) o.y += move;
        for (Coin c : coins) c.y += move;
        for (Power pw : powers) pw.y += move;

        if (state != STATE_PLAY) return;

        // 경찰과의 거리: 시간이 지나면 조금씩 회복, 레벨이 높을수록 회복이 느림
        float recover = Math.max(0.018f, 0.055f - level() * 0.003f);
        chase = Math.min(1f, chase + recover * dt);

        spawnAcc += move;
        if (spawnAcc >= nextGap) {
            spawnAcc = 0;
            spawnRow();
            float minGapTime = Math.max(0.6f, 1.2f - time * 0.01f);
            nextGap = speed * (minGapTime + rnd.nextFloat() * 0.45f);
        }

        // 장애물 충돌
        for (Iterator<Obstacle> it = obstacles.iterator(); it.hasNext(); ) {
            Obstacle o = it.next();
            float ow = obW(o.type), oh = obH(o.type);
            float ox = laneX(o.lane);
            if ((invuln <= 0 || shieldT > 0)
                    && Math.abs(ox - playerX) < (ow + carW) * 0.5f * 0.82f
                    && Math.abs(o.y - playerY) < (oh + carH) * 0.5f * 0.82f) {
                if (shieldT > 0) {
                    smash(ox, o.y, o.type);
                    it.remove();
                    continue;
                }
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
            if (magnetT > 0 && c.y > H * 0.02f && c.y < playerY + carH) {
                // 자석: 화면에 보이는 코인을 플레이어 쪽으로 끌어당긴다
                c.x += (playerX - c.x) * Math.min(1f, dt * 7f);
                c.y += (playerY - c.y) * Math.min(1f, dt * 3.5f);
            } else if (magnetT <= 0) {
                c.x += (laneX(c.lane) - c.x) * Math.min(1f, dt * 8f);
            }
            float cxl = c.x;
            if (Math.abs(cxl - playerX) < carW * 0.6f + coinR && Math.abs(c.y - playerY) < carH * 0.5f + coinR) {
                it.remove();
                coinAcc += coinMul(owned[SH_UP_COIN]);
                coinCount = (int) (coinAcc + 0.001f);
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

        // 파워업 줍기
        powerCooldown -= dt;
        for (Iterator<Power> it = powers.iterator(); it.hasNext(); ) {
            Power pw = it.next();
            float px = laneX(pw.lane);
            if (Math.abs(px - playerX) < carW * 0.6f + coinR * 1.4f && Math.abs(pw.y - playerY) < carH * 0.5f + coinR * 1.4f) {
                it.remove();
                if (pw.type == PW_MAGNET) {
                    magnetMax = magnetT = magnetDur(owned[SH_UP_MAGNET]);
                    floatText(px, pw.y, "자석!", Color.rgb(255, 110, 200));
                    burst(px, pw.y, Color.rgb(255, 110, 200), 14);
                } else {
                    shieldMax = shieldT = shieldDur(owned[SH_UP_SHIELD]);
                    floatText(px, pw.y, "무적!", Color.rgb(90, 220, 255));
                    burst(px, pw.y, Color.rgb(90, 220, 255), 14);
                }
                buzz(30);
                if (sfx != null) sfx.powerFx();
                continue;
            }
            if (pw.y - coinR * 2 > H) it.remove();
        }
    }

    /** 무적 상태로 장애물을 부순다. */
    private void smash(float x, float y, int type) {
        score += 15 * level();
        burst(x, y, Color.rgb(140, 230, 255), 12);
        burst(x, y, type == OB_CONE ? Color.rgb(255, 130, 20) : Color.rgb(255, 230, 120), 10);
        smoke(x, y, 3);
        shake = Math.max(shake, 0.3f);
        floatText(x, y, "+" + (15 * level()), Color.rgb(140, 230, 255));
        buzz(25);
        if (sfx != null) sfx.smashFx();
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
        boolean two = time > 12 && rnd.nextFloat() < Math.min(0.42f, 0.12f + time * 0.006f);
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
        if (powerCooldown <= 0 && time > 4) {
            // 빈 차선에 파워업 (이 줄에는 코인 대신)
            powerCooldown = 11f + rnd.nextFloat() * 7f;
            Power pw = new Power();
            pw.lane = free;
            pw.type = rnd.nextBoolean() ? PW_MAGNET : PW_SHIELD;
            pw.y = -carH;
            powers.add(pw);
        } else if (rnd.nextFloat() < 0.6f) {
            int n = 3 + rnd.nextInt(3);
            for (int i = 0; i < n; i++) {
                Coin c = new Coin();
                c.lane = free;
                c.x = laneX(free);
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
        for (Iterator<Power> it = powers.iterator(); it.hasNext(); ) {
            Power pw = it.next();
            if (pw.lane == ln && Math.abs(pw.y - o.y) < carH * 1.5f) it.remove();
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
        if (!reviveUsed && owned[SH_REVIVE] > 0) {
            revive();
            return;
        }
        chase = 0;
        state = STATE_CRASH;
        crashTimer = 0;
        shake = 1f;
        int total = totalScore();
        if (total > best) best = total;
        totalCoins += coinCount;
        overSel = 0;
        prefs.edit().putInt("best", best).putInt("coins", totalCoins).apply();
        if (sfx != null) sfx.caughtFx();
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 60, 120, 60, 250}, -1));
        }
    }

    /** 부활: 경찰을 밀어내고 잠깐 무적. */
    private void revive() {
        reviveUsed = true;
        owned[SH_REVIVE]--;
        saveOwned(SH_REVIVE);
        chase = 0.65f;
        invuln = 0;
        shieldT = Math.max(shieldT, 2.5f);
        shieldMax = Math.max(shieldMax, shieldT);
        hitText = 0;
        reviveText = 1.6f;
        shake = 0.5f;
        // 가까이 있는 장애물 치우기
        for (Iterator<Obstacle> it = obstacles.iterator(); it.hasNext(); ) {
            Obstacle o = it.next();
            if (o.y > playerY - carH * 2.5f) {
                burst(laneX(o.lane), o.y, Color.rgb(255, 230, 120), 8);
                it.remove();
            }
        }
        burst(playerX, playerY, Color.rgb(120, 255, 140), 24);
        burst(playerX, playerY, Color.rgb(255, 230, 90), 16);
        if (sfx != null) sfx.reviveFx();
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 60, 50, 60, 50, 160}, -1));
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
        for (Coin co : coins) drawCoin(c, co.x, co.y);
        for (Obstacle o : obstacles) drawObstacle(c, o);
        for (Power pw : powers) drawPower(c, pw);
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
        drawPlayerAura(c);
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

    // ---------------- 아이콘 ----------------

    private static final int COL_MAGNET = Color.rgb(255, 90, 170);
    private static final int COL_SHIELD = Color.rgb(80, 210, 255);
    private static final int COL_REVIVE = Color.rgb(110, 240, 130);
    private static final int COL_GOLD = Color.rgb(255, 215, 50);

    /** 말굽 자석. s = 반지름 정도의 크기. */
    private void drawMagnetIcon(Canvas c, float x, float y, float s) {
        float rr = s * 0.55f, sw = s * 0.36f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(Color.rgb(230, 50, 60));
        r.set(x - rr, y - rr * 0.6f, x + rr, y + rr * 1.4f);
        c.drawArc(r, 0, 180, false, p);
        float top = y - rr * 0.75f, mid = y + rr * 0.4f;
        c.drawLine(x - rr, mid + 1, x - rr, top + sw * 0.9f, p);
        c.drawLine(x + rr, mid + 1, x + rr, top + sw * 0.9f, p);
        p.setColor(Color.rgb(225, 230, 240));
        c.drawLine(x - rr, top + sw * 0.95f, x - rr, top, p);
        c.drawLine(x + rr, top + sw * 0.95f, x + rr, top, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** 방패. */
    private void drawShieldIcon(Canvas c, float x, float y, float s) {
        path.reset();
        path.moveTo(x, y - s * 0.85f);
        path.lineTo(x + s * 0.72f, y - s * 0.55f);
        path.quadTo(x + s * 0.7f, y + s * 0.45f, x, y + s * 0.9f);
        path.quadTo(x - s * 0.7f, y + s * 0.45f, x - s * 0.72f, y - s * 0.55f);
        path.close();
        p.setColor(COL_SHIELD);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.13f);
        p.setColor(Color.WHITE);
        c.drawPath(path, p);
        // 번개 표시
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(x + s * 0.12f, y - s * 0.5f);
        path.lineTo(x - s * 0.25f, y + s * 0.08f);
        path.lineTo(x - s * 0.02f, y + s * 0.08f);
        path.lineTo(x - s * 0.12f, y + s * 0.55f);
        path.lineTo(x + s * 0.25f, y - s * 0.05f);
        path.lineTo(x + s * 0.02f, y - s * 0.05f);
        path.close();
        p.setColor(Color.WHITE);
        c.drawPath(path, p);
    }

    /** 부활 (하트 + 십자). */
    private void drawReviveIcon(Canvas c, float x, float y, float s) {
        path.reset();
        path.moveTo(x, y + s * 0.8f);
        path.cubicTo(x - s * 1.25f, y - s * 0.05f, x - s * 0.55f, y - s * 1.0f, x, y - s * 0.35f);
        path.cubicTo(x + s * 0.55f, y - s * 1.0f, x + s * 1.25f, y - s * 0.05f, x, y + s * 0.8f);
        path.close();
        p.setColor(COL_REVIVE);
        c.drawPath(path, p);
        p.setColor(Color.WHITE);
        float a = s * 0.09f, b = s * 0.3f, oy = y - s * 0.05f;
        c.drawRect(x - a, oy - b, x + a, oy + b, p);
        c.drawRect(x - b, oy - a, x + b, oy + a, p);
    }

    private void drawCoinIcon(Canvas c, float x, float y, float s) {
        p.setColor(Color.rgb(200, 140, 10));
        c.drawCircle(x, y, s, p);
        p.setColor(COL_GOLD);
        c.drawCircle(x, y, s * 0.78f, p);
        text.setTextSize(s * 1.2f);
        text.setColor(Color.rgb(200, 140, 10));
        c.drawText("$", x, y + s * 0.42f, text);
    }

    private void drawShopIcon(Canvas c, int item, float x, float y, float s) {
        switch (item) {
            case SH_REVIVE: drawReviveIcon(c, x, y, s); break;
            case SH_START_SHIELD:
            case SH_UP_SHIELD: drawShieldIcon(c, x, y, s); break;
            case SH_START_MAGNET:
            case SH_UP_MAGNET: drawMagnetIcon(c, x, y, s); break;
            case SH_UP_COIN: drawCoinIcon(c, x, y, s * 0.85f); break;
            default: {
                // 집 모양
                p.setColor(Color.rgb(200, 205, 215));
                path.reset();
                path.moveTo(x, y - s * 0.8f);
                path.lineTo(x + s * 0.85f, y);
                path.lineTo(x - s * 0.85f, y);
                path.close();
                c.drawPath(path, p);
                c.drawRect(x - s * 0.55f, y - s * 0.05f, x + s * 0.55f, y + s * 0.7f, p);
                p.setColor(Color.rgb(40, 42, 52));
                c.drawRect(x - s * 0.15f, y + s * 0.2f, x + s * 0.15f, y + s * 0.7f, p);
            }
        }
        if (isUpgrade(item)) {
            // 업그레이드 표시 (위 화살표)
            float bx = x + s * 0.85f, by = y - s * 0.7f, br = s * 0.38f;
            p.setColor(Color.rgb(120, 230, 120));
            c.drawCircle(bx, by, br, p);
            p.setColor(Color.rgb(20, 60, 25));
            path.reset();
            path.moveTo(bx, by - br * 0.6f);
            path.lineTo(bx + br * 0.55f, by + br * 0.1f);
            path.lineTo(bx - br * 0.55f, by + br * 0.1f);
            path.close();
            c.drawPath(path, p);
            c.drawRect(bx - br * 0.2f, by, bx + br * 0.2f, by + br * 0.55f, p);
        }
    }

    /** 길 위의 파워업 아이템. */
    private void drawPower(Canvas c, Power pw) {
        float x = laneX(pw.lane), y = pw.y;
        float pulse = 1f + 0.08f * (float) Math.sin(sirenT * 7);
        float rad = coinR * 1.55f * pulse;
        int col = pw.type == PW_MAGNET ? COL_MAGNET : COL_SHIELD;
        glow(c, x, y, rad * 2.4f, rad * 2.4f, (col & 0x00FFFFFF) | (150 << 24));
        p.setColor(Color.rgb(20, 22, 30));
        c.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(rad * 0.16f);
        p.setColor(col);
        c.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.FILL);
        if (pw.type == PW_MAGNET) drawMagnetIcon(c, x, y, rad * 0.62f);
        else drawShieldIcon(c, x, y, rad * 0.6f);
    }

    /** 플레이어 주변 효과 (방패/자석). */
    private void drawPlayerAura(Canvas c) {
        if (state != STATE_PLAY && state != STATE_CRASH) return;
        if (magnetT > 0) {
            float k = (sirenT * 1.5f) % 1f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(W * 0.006f);
            for (int i = 0; i < 2; i++) {
                float kk = (k + i * 0.5f) % 1f;
                p.setColor((COL_MAGNET & 0x00FFFFFF) | ((int) (160 * (1f - kk)) << 24));
                c.drawCircle(playerX, playerY, carH * (0.55f + kk * 0.9f), p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        if (shieldT > 0) {
            boolean blink = shieldT < 1.5f && ((int) (shieldT * 8)) % 2 == 0;
            if (!blink) {
                float rad = carH * 0.66f * (1f + 0.04f * (float) Math.sin(sirenT * 9));
                glow(c, playerX, playerY, rad * 1.25f, rad * 1.25f, (COL_SHIELD & 0x00FFFFFF) | (90 << 24));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(W * 0.009f);
                p.setColor(Color.argb(210, 150, 235, 255));
                c.drawCircle(playerX, playerY, rad, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    // ---------------- HUD / 메뉴 ----------------

    /** 글자가 maxW 를 넘지 않도록 크기를 줄인다. */
    private void fitText(String s, float size, float maxW) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW) text.setTextSize(size * maxW / w);
    }

    private void drawButton(Canvas c, float l, float t, float rt, float b, String label, boolean sel, int color) {
        float rad = (b - t) / 2;
        if (sel) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(menuT * 5);
            glow(c, (l + rt) / 2, (t + b) / 2, (rt - l) * 0.7f, (b - t) * 1.1f,
                    (color & 0x00FFFFFF) | ((int) (50 + 40 * pulse) << 24));
        }
        r.set(l, t, rt, b);
        if (sel) {
            p.setColor(color);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(W * 0.008f);
            p.setColor(Color.WHITE);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            text.setColor(Color.rgb(25, 20, 15));
        } else {
            p.setColor(Color.argb(200, 30, 32, 42));
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(W * 0.006f);
            p.setColor(Color.argb(200, 120, 125, 140));
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            text.setColor(Color.rgb(220, 222, 230));
        }
        text.setTextSize((b - t) * 0.48f);
        c.drawText(label, (l + rt) / 2, (t + b) / 2 + (b - t) * 0.17f, text);
    }

    /** 코인 아이콘 + 숫자를 가운데 정렬로 그린다. */
    private void drawCoinAmount(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextSize(size);
        float tw = text.measureText(s);
        float ir = size * 0.38f;
        float total = ir * 2 + size * 0.25f + tw;
        float sx = x - total / 2;
        p.setColor(COL_GOLD);
        c.drawCircle(sx + ir, y - size * 0.36f, ir, p);
        p.setColor(Color.rgb(200, 140, 10));
        c.drawCircle(sx + ir, y - size * 0.36f, ir * 0.55f, p);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color);
        c.drawText(s, sx + ir * 2 + size * 0.25f, y, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** 활성 파워업 표시 (남은 시간 원호). */
    private void drawPowerSlot(Canvas c, float x, float y, int kind, float frac) {
        float rad = W * 0.045f;
        p.setColor(Color.argb(160, 0, 0, 0));
        c.drawCircle(x, y, rad * 1.12f, p);
        int col = kind == 0 ? COL_MAGNET : kind == 1 ? COL_SHIELD : COL_REVIVE;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(rad * 0.2f);
        p.setColor(Color.argb(70, 255, 255, 255));
        c.drawCircle(x, y, rad, p);
        p.setColor(col);
        r.set(x - rad, y - rad, x + rad, y + rad);
        c.drawArc(r, -90, 360 * Math.max(0, Math.min(1, frac)), false, p);
        p.setStyle(Paint.Style.FILL);
        if (kind == 0) drawMagnetIcon(c, x, y, rad * 0.5f);
        else if (kind == 1) drawShieldIcon(c, x, y, rad * 0.5f);
        else drawReviveIcon(c, x, y, rad * 0.5f);
    }

    private void drawHud(Canvas c) {
        if (state == STATE_PLAY || state == STATE_CRASH) {
            String sc = String.valueOf(totalScore());
            String line = " " + coinCount + "   Lv " + level();
            text.setTextSize(W * 0.085f);
            float sw = text.measureText(sc);
            text.setTextSize(W * 0.05f);
            float cw = text.measureText(line) + coinR * 1.6f;
            // 반투명 배경
            float bw = Math.max(sw, cw) + W * 0.09f;
            p.setColor(Color.argb(135, 0, 0, 0));
            r.set(cx - bw / 2, H * 0.078f, cx + bw / 2, H * 0.238f);
            c.drawRoundRect(r, W * 0.05f, W * 0.05f, p);

            text.setTextSize(W * 0.085f);
            text.setColor(Color.argb(150, 0, 0, 0));
            c.drawText(sc, cx + 2, H * 0.15f + 2, text);
            text.setColor(Color.WHITE);
            c.drawText(sc, cx, H * 0.15f, text);
            // 코인 + 레벨
            float iy = H * 0.215f;
            text.setTextSize(W * 0.05f);
            text.setTextAlign(Paint.Align.LEFT);
            float sx = cx - cw / 2;
            p.setColor(COL_GOLD);
            c.drawCircle(sx + coinR * 0.7f, iy - coinR * 0.55f, coinR * 0.7f, p);
            text.setColor(Color.rgb(255, 225, 90));
            c.drawText(line, sx + coinR * 1.6f, iy, text);
            text.setTextAlign(Paint.Align.CENTER);

            // 활성 파워업
            int n = 0;
            int[] kinds = new int[3];
            float[] fr = new float[3];
            if (magnetT > 0) { kinds[n] = 0; fr[n++] = magnetT / magnetMax; }
            if (shieldT > 0) { kinds[n] = 1; fr[n++] = shieldT / shieldMax; }
            if (!reviveUsed && owned[SH_REVIVE] > 0) { kinds[n] = 2; fr[n++] = 1f; }
            float gap = W * 0.115f;
            for (int i = 0; i < n; i++) {
                drawPowerSlot(c, cx + (i - (n - 1) / 2f) * gap, H * 0.295f, kinds[i], fr[i]);
            }

            if (flashText > 0) {
                text.setTextSize(W * 0.08f);
                text.setColor(Color.argb((int) (255 * Math.min(1f, flashText)), 255, 220, 60));
                c.drawText("속도 UP! Lv " + level(), cx, H * 0.42f, text);
            }
            if (hitText > 0 && state == STATE_PLAY) {
                text.setTextSize(W * 0.075f);
                text.setColor(Color.argb((int) (255 * Math.min(1f, hitText * 1.5f)), 255, 80, 80));
                c.drawText("쾅! 경찰 접근!", cx, H * 0.37f, text);
            }
            if (reviveText > 0) {
                float k = Math.min(1f, reviveText);
                float sz = W * (0.13f + 0.05f * (1.6f - reviveText) / 1.6f);
                text.setTextSize(sz);
                text.setColor(Color.argb((int) (200 * k), 0, 0, 0));
                c.drawText("부활!", cx + 3, H * 0.42f + 3, text);
                text.setColor((COL_REVIVE & 0x00FFFFFF) | ((int) (255 * k) << 24));
                c.drawText("부활!", cx, H * 0.42f, text);
            }
            return;
        }

        p.setColor(Color.argb(state == STATE_SHOP ? 200 : 165, 0, 0, 0));
        c.drawRect(0, 0, W, H, p);

        if (state != STATE_SHOP) {
            if (versionLabel == null) versionLabel = "v" + Updater.currentVersionName(getContext());
            String st = updater != null ? updater.status() : "";
            fitText(st.isEmpty() ? versionLabel : st, W * 0.042f, W * 0.5f);
            text.setColor(st.isEmpty() ? Color.GRAY : Color.rgb(120, 220, 255));
            c.drawText(st.isEmpty() ? versionLabel : st, cx, H * 0.93f, text);
        }

        if (state == STATE_HOME) drawHome(c);
        else if (state == STATE_SHOP) drawShop(c);
        else if (state == STATE_OVER) drawOver(c);
    }

    private void drawHome(Canvas c) {
        text.setColor(Color.argb(160, 0, 0, 0));
        text.setTextSize(W * 0.115f);
        c.drawText("경찰 피하기", cx + 3, H * 0.215f + 3, text);
        text.setColor(Color.rgb(255, 120, 20));
        c.drawText("경찰 피하기", cx, H * 0.215f, text);
        boolean on = (SystemClock.uptimeMillis() / 250) % 2 == 0;
        p.setColor(on ? Color.rgb(255, 50, 50) : Color.rgb(50, 110, 255));
        r.set(cx - W * 0.18f, H * 0.245f, cx + W * 0.18f, H * 0.262f);
        c.drawRoundRect(r, H, H, p);

        // 코인 · 최고
        String coinsS = String.valueOf(totalCoins);
        String bestS = "최고 " + best;
        float size = W * 0.055f;
        text.setTextSize(size);
        float ir = size * 0.38f;
        float w1 = ir * 2 + size * 0.25f + text.measureText(coinsS);
        float gapW = W * 0.07f;
        float w2 = text.measureText(bestS);
        float sx = cx - (w1 + gapW + w2) / 2;
        drawCoinAmount(c, coinsS, sx + w1 / 2, H * 0.345f, size, Color.rgb(255, 225, 90));
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.LTGRAY);
        c.drawText(bestS, sx + w1 + gapW, H * 0.345f, text);
        text.setTextAlign(Paint.Align.CENTER);

        float bw = W * 0.28f;
        drawButton(c, cx - bw, H * HOME_B1_T, cx + bw, H * HOME_B1_B, "시작", homeSel == 0, Color.rgb(255, 140, 40));
        drawButton(c, cx - bw, H * HOME_B2_T, cx + bw, H * HOME_B2_B, "상점", homeSel == 1, COL_GOLD);

        // 보유 아이템
        int[] items = {SH_REVIVE, SH_START_SHIELD, SH_START_MAGNET};
        int cnt = 0;
        for (int it : items) if (owned[it] > 0) cnt++;
        float iy = H * 0.775f;
        if (cnt > 0) {
            float slot = W * 0.15f;
            float x0 = cx - (cnt - 1) * slot / 2;
            int k = 0;
            text.setTextSize(W * 0.045f);
            text.setTextAlign(Paint.Align.LEFT);
            for (int it : items) {
                if (owned[it] <= 0) continue;
                float x = x0 + k * slot - W * 0.025f;
                drawShopIcon(c, it, x, iy - W * 0.015f, W * 0.026f);
                text.setColor(Color.WHITE);
                c.drawText("" + owned[it], x + W * 0.035f, iy, text);
                k++;
            }
            text.setTextAlign(Paint.Align.CENTER);
        } else {
            text.setTextSize(W * 0.04f);
            text.setColor(Color.rgb(150, 155, 170));
            c.drawText("상점에서 아이템을 사 보세요", cx, iy, text);
        }
        text.setTextSize(W * 0.038f);
        text.setColor(Color.rgb(130, 135, 150));
        c.drawText("베젤: 선택 · 탭: 확인", cx, H * 0.845f, text);
    }

    private void drawShop(Canvas c) {
        // 머리: 제목 + 코인
        text.setTextSize(W * 0.062f);
        text.setColor(Color.WHITE);
        c.drawText("상점", cx, H * 0.115f, text);
        drawCoinAmount(c, String.valueOf(totalCoins), cx, H * 0.19f, W * 0.062f, Color.rgb(255, 225, 90));

        // 이전 / 다음 항목 미리보기
        text.setTextSize(W * 0.04f);
        text.setColor(Color.rgb(120, 125, 140));
        if (shopSel > 0) c.drawText("▲ " + SH_NAME[shopSel - 1], cx, H * 0.255f, text);
        if (shopSel < SH_COUNT - 1) c.drawText("▼ " + SH_NAME[shopSel + 1], cx, H * 0.77f, text);

        // 카드
        int i = shopSel;
        float shakeX = shopAnim > 0 ? (float) Math.sin(shopAnim * 40) * W * 0.025f * shopAnim : 0;
        c.save();
        c.translate(shakeX, 0);
        float hw = W * 0.39f;
        float top = H * SHOP_CARD_T, bot = H * SHOP_CARD_B;
        int accent = i == SH_REVIVE ? COL_REVIVE
                : (i == SH_START_SHIELD || i == SH_UP_SHIELD) ? COL_SHIELD
                : (i == SH_START_MAGNET || i == SH_UP_MAGNET) ? COL_MAGNET
                : i == SH_UP_COIN ? COL_GOLD : Color.rgb(170, 175, 190);
        r.set(cx - hw, top, cx + hw, bot);
        p.setColor(Color.argb(235, 26, 28, 38));
        c.drawRoundRect(r, W * 0.07f, W * 0.07f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(W * 0.008f);
        p.setColor(accent);
        c.drawRoundRect(r, W * 0.07f, W * 0.07f, p);
        p.setStyle(Paint.Style.FILL);

        glow(c, cx, H * 0.35f, W * 0.13f, W * 0.13f, (accent & 0x00FFFFFF) | (70 << 24));
        drawShopIcon(c, i, cx, H * 0.35f, W * 0.055f);

        fitText(SH_NAME[i], W * 0.068f, hw * 1.8f);
        text.setColor(Color.WHITE);
        c.drawText(SH_NAME[i], cx, H * 0.46f, text);

        String info;
        if (i == SH_BACK) info = "";
        else if (isUpgrade(i)) info = "Lv " + owned[i] + " / " + SH_MAX[i];
        else info = "보유 " + owned[i] + "개";
        text.setTextSize(W * 0.048f);
        text.setColor(accent);
        c.drawText(info, cx, H * 0.525f, text);

        String desc = shopDesc(i);
        fitText(desc, W * 0.042f, hw * 1.8f);
        text.setColor(Color.rgb(200, 202, 212));
        c.drawText(desc, cx, H * 0.585f, text);

        // 가격 / 메시지
        float py = H * 0.65f;
        if (shopMsgT > 0) {
            fitText(shopMsg, W * 0.05f, hw * 1.8f);
            text.setColor((shopMsgColor & 0x00FFFFFF) | ((int) (255 * Math.min(1f, shopMsgT * 2)) << 24));
            c.drawText(shopMsg, cx, py + W * 0.018f, text);
        } else if (i == SH_BACK) {
            drawPill(c, py, "탭해서 이동", Color.rgb(170, 175, 190), Color.rgb(20, 20, 25), false);
        } else {
            int pr = price(i);
            if (pr < 0) drawPill(c, py, "MAX", Color.rgb(120, 190, 255), Color.rgb(10, 25, 45), false);
            else {
                boolean ok = totalCoins >= pr;
                drawPill(c, py, String.valueOf(pr), ok ? COL_GOLD : Color.rgb(90, 70, 70),
                        ok ? Color.rgb(60, 40, 0) : Color.rgb(255, 140, 140), true);
            }
        }
        c.restore();

        // 위치 점
        float dg = W * 0.034f;
        for (int k = 0; k < SH_COUNT; k++) {
            p.setColor(k == shopSel ? Color.WHITE : Color.argb(110, 255, 255, 255));
            c.drawCircle(cx + (k - (SH_COUNT - 1) / 2f) * dg, H * 0.825f, k == shopSel ? W * 0.011f : W * 0.007f, p);
        }
        text.setTextSize(W * 0.036f);
        text.setColor(Color.rgb(130, 135, 150));
        c.drawText("탭: 구매 · 베젤: 이동", cx, H * 0.885f, text);
    }

    private void drawPill(Canvas c, float y, String s, int bg, int fg, boolean coin) {
        float size = W * 0.05f;
        text.setTextSize(size);
        float tw = text.measureText(s);
        float ir = coin ? size * 0.36f : 0;
        float inner = tw + (coin ? ir * 2 + size * 0.25f : 0);
        float hw = inner / 2 + W * 0.05f, hh = H * 0.036f;
        r.set(cx - hw, y - hh, cx + hw, y + hh);
        p.setColor(bg);
        c.drawRoundRect(r, hh, hh, p);
        float sx = cx - inner / 2;
        if (coin) {
            p.setColor(fg);
            c.drawCircle(sx + ir, y, ir, p);
            p.setColor(bg);
            c.drawCircle(sx + ir, y, ir * 0.5f, p);
            sx += ir * 2 + size * 0.25f;
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(fg);
        c.drawText(s, sx, y + size * 0.36f, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void drawOver(Canvas c) {
        text.setColor(Color.rgb(255, 70, 70));
        text.setTextSize(W * 0.105f);
        c.drawText("체포됨!", cx, H * 0.26f, text);
        text.setColor(Color.WHITE);
        text.setTextSize(W * 0.13f);
        c.drawText(String.valueOf(totalScore()), cx, H * 0.415f, text);
        drawCoinAmount(c, "+" + coinCount + "  (총 " + totalCoins + ")", cx, H * 0.505f, W * 0.055f, Color.rgb(255, 225, 90));
        text.setTextSize(W * 0.052f);
        text.setColor(Color.LTGRAY);
        c.drawText((totalScore() >= best && best > 0 ? "최고 기록! " : "최고 ") + best, cx, H * 0.585f, text);
        if (crashTimer > 0.9f) {
            drawButton(c, cx - W * 0.34f, H * OVER_B_T, cx - W * 0.02f, H * OVER_B_B, "다시", overSel == 0, Color.rgb(255, 140, 40));
            drawButton(c, cx + W * 0.02f, H * OVER_B_T, cx + W * 0.34f, H * OVER_B_B, "홈", overSel == 1, Color.rgb(170, 200, 255));
        }
    }
}
