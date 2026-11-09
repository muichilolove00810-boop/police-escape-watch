package com.grara.policeescape;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * 효과음. 블루투스 오디오 기기(스피커/이어폰)가 연결돼 있을 때만 소리를 낸다.
 * 소리는 모두 코드로 합성해서 WAV 로 만든 뒤 SoundPool 로 재생한다.
 */
public class SoundFx {
    private static final int SR = 22050;

    private final AudioManager am;
    private final SoundPool pool;
    private final Random rnd = new Random(7);
    private boolean bt;
    private int sCoin, sCrash, sSwoosh, sLevel, sSiren, sCaught, sStart, sPower, sDeny, sClick, sRevive, sBuy;
    private int sirenStream;
    private boolean released;

    private final AudioDeviceCallback cb = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            bt = checkBt();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            bt = checkBt();
            if (!bt) stopSiren();
        }
    };

    public SoundFx(Context ctx) {
        am = ctx.getSystemService(AudioManager.class);
        pool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        File dir = new File(ctx.getCacheDir(), "sfx");
        dir.mkdirs();
        sCoin = load(dir, "coin", genCoin());
        sCrash = load(dir, "crash", crash());
        sSwoosh = load(dir, "swoosh", swoosh());
        sLevel = load(dir, "level", levelUp());
        sSiren = load(dir, "siren", siren());
        sCaught = load(dir, "caught", caught());
        sStart = load(dir, "start", start());
        sPower = load(dir, "power", powerUp());
        sDeny = load(dir, "deny", deny());
        sClick = load(dir, "click", click());
        sRevive = load(dir, "revive", revive());
        sBuy = load(dir, "buy", buy());
        bt = checkBt();
        am.registerAudioDeviceCallback(cb, new Handler(Looper.getMainLooper()));
    }

    public boolean enabled() {
        return bt && !released;
    }

    private boolean checkBt() {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || t == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    || t == 30 /* TYPE_BLE_BROADCAST */) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 재생 ----------------

    private void play(int id, float vol, float rate) {
        if (enabled() && id != 0) pool.play(id, vol, vol, 1, 0, rate);
    }

    public void coin() { play(sCoin, 0.6f, 0.95f + rnd.nextFloat() * 0.1f); }
    public void crashFx() { play(sCrash, 1f, 0.9f + rnd.nextFloat() * 0.2f); }
    public void swooshFx() { play(sSwoosh, 0.35f, 0.9f + rnd.nextFloat() * 0.25f); }
    public void levelFx() { play(sLevel, 0.7f, 1f); }
    public void caughtFx() { play(sCaught, 1f, 1f); }
    public void startFx() { play(sStart, 0.7f, 1f); }
    public void powerFx() { play(sPower, 0.7f, 1f); }
    public void denyFx() { play(sDeny, 0.6f, 1f); }
    public void clickFx() { play(sClick, 0.4f, 1f); }
    public void reviveFx() { play(sRevive, 0.8f, 1f); }
    public void buyFx() { play(sBuy, 0.7f, 1f); }
    public void smashFx() { play(sCrash, 0.55f, 1.3f + rnd.nextFloat() * 0.2f); }

    /** 사이렌 볼륨 (0 이면 정지). 경찰이 가까울수록 크게. */
    public void siren(float vol) {
        if (!enabled() || vol <= 0.01f) {
            stopSiren();
            return;
        }
        if (sirenStream == 0) sirenStream = pool.play(sSiren, vol, vol, 2, -1, 1f);
        else pool.setVolume(sirenStream, vol, vol);
    }

    public void stopSiren() {
        if (sirenStream != 0) {
            pool.stop(sirenStream);
            sirenStream = 0;
        }
    }

    public void release() {
        released = true;
        stopSiren();
        am.unregisterAudioDeviceCallback(cb);
        pool.release();
    }

    // ---------------- 합성 ----------------

    private int load(File dir, String name, short[] pcm) {
        try {
            File f = new File(dir, name + ".wav");
            writeWav(f, pcm);
            return pool.load(f.getPath(), 1);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeWav(File f, short[] pcm) throws Exception {
        int dataLen = pcm.length * 2;
        byte[] b = new byte[44 + dataLen];
        putStr(b, 0, "RIFF");
        putInt(b, 4, 36 + dataLen);
        putStr(b, 8, "WAVEfmt ");
        putInt(b, 16, 16);
        putShort(b, 20, 1);
        putShort(b, 22, 1);
        putInt(b, 24, SR);
        putInt(b, 28, SR * 2);
        putShort(b, 32, 2);
        putShort(b, 34, 16);
        putStr(b, 36, "data");
        putInt(b, 40, dataLen);
        for (int i = 0; i < pcm.length; i++) putShort(b, 44 + i * 2, pcm[i]);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
    }

    private static void putStr(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static short[] toPcm(float[] f) {
        short[] s = new short[f.length];
        for (int i = 0; i < f.length; i++) {
            float v = Math.max(-1f, Math.min(1f, f[i]));
            s[i] = (short) (v * 32000);
        }
        return s;
    }

    /** 부드러운 사각파 느낌 (기본음 + 홀수 배음). */
    private static float buzzy(double ph) {
        return (float) (Math.sin(ph) + Math.sin(3 * ph) / 3 + Math.sin(5 * ph) / 6);
    }

    private static void tone(float[] out, double startSec, double dur, double f0, double f1, float amp, boolean square) {
        int s = (int) (startSec * SR), n = (int) (dur * SR);
        double ph = 0;
        for (int i = 0; i < n && s + i < out.length; i++) {
            double t = (double) i / n;
            double f = f0 + (f1 - f0) * t;
            ph += 2 * Math.PI * f / SR;
            float env = (float) Math.min(1, i / (SR * 0.004)) * (float) Math.pow(1 - t, 1.5);
            float v = square ? buzzy(ph) * 0.6f : (float) Math.sin(ph);
            out[s + i] += v * amp * env;
        }
    }

    private short[] genCoin() {
        float[] o = new float[(int) (SR * 0.3)];
        tone(o, 0, 0.07, 988, 988, 0.5f, true);
        tone(o, 0.06, 0.22, 1319, 1319, 0.5f, true);
        return toPcm(o);
    }

    private short[] crash() {
        int n = (int) (SR * 0.6);
        float[] o = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float noise = rnd.nextFloat() * 2 - 1;
            lp += (noise - lp) * (0.35f - t * 0.3f);
            float env = (float) Math.exp(-t * 6);
            float thump = (float) Math.sin(2 * Math.PI * (70 - 30 * t) * i / SR) * (float) Math.exp(-t * 9);
            o[i] = lp * env * 1.2f + thump * 0.8f;
        }
        return toPcm(o);
    }

    private short[] swoosh() {
        int n = (int) (SR * 0.16);
        float[] o = new float[n];
        float lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float noise = rnd.nextFloat() * 2 - 1;
            lp += (noise - lp) * (0.1f + t * 0.3f);
            lp2 += (lp - lp2) * 0.5f;
            float env = (float) Math.sin(Math.PI * t);
            o[i] = (lp - lp2) * env * 2.5f;
        }
        return toPcm(o);
    }

    private short[] levelUp() {
        float[] o = new float[(int) (SR * 0.45)];
        double[] notes = {523, 659, 784, 1047};
        for (int i = 0; i < notes.length; i++) tone(o, i * 0.075, i == 3 ? 0.2 : 0.09, notes[i], notes[i], 0.45f, true);
        return toPcm(o);
    }

    /** 한국 경찰차 같은 오르내리는 사이렌 (1.6초, 이음매 없이 반복). */
    private short[] siren() {
        int n = (int) (SR * 1.6);
        float[] o = new float[n];
        // 위상 합이 정수 주기가 되도록: 평균 주파수 * 길이 = 정수
        double ph = 0;
        double[] freqs = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            freqs[i] = 950 + 350 * Math.sin(2 * Math.PI * t - Math.PI / 2) * -1;
            sum += freqs[i];
        }
        double cycles = sum / SR;
        double scale = Math.round(cycles) / cycles;
        for (int i = 0; i < n; i++) {
            ph += 2 * Math.PI * freqs[i] * scale / SR;
            o[i] = buzzy(ph) * 0.38f;
        }
        return toPcm(o);
    }

    private short[] caught() {
        float[] o = new float[(int) (SR * 0.75)];
        tone(o, 0, 0.2, 620, 600, 0.5f, true);
        tone(o, 0.2, 0.2, 470, 450, 0.5f, true);
        tone(o, 0.4, 0.35, 330, 250, 0.5f, true);
        return toPcm(o);
    }

    private short[] start() {
        float[] o = new float[(int) (SR * 0.35)];
        tone(o, 0, 0.1, 660, 660, 0.4f, true);
        tone(o, 0.13, 0.2, 990, 990, 0.4f, true);
        return toPcm(o);
    }

    /** 파워업 획득: 반짝이는 상승 아르페지오. */
    private short[] powerUp() {
        float[] o = new float[(int) (SR * 0.5)];
        double[] notes = {784, 988, 1175, 1568, 1976};
        for (int i = 0; i < notes.length; i++) tone(o, i * 0.05, 0.25, notes[i], notes[i] * 1.01, 0.3f, false);
        return toPcm(o);
    }

    /** 코인 부족 등 거절음. */
    private short[] deny() {
        float[] o = new float[(int) (SR * 0.3)];
        tone(o, 0, 0.12, 220, 210, 0.45f, true);
        tone(o, 0.13, 0.15, 180, 170, 0.45f, true);
        return toPcm(o);
    }

    /** 메뉴 이동 틱. */
    private short[] click() {
        float[] o = new float[(int) (SR * 0.04)];
        tone(o, 0, 0.035, 1800, 1500, 0.35f, false);
        return toPcm(o);
    }

    /** 부활: 낮은 음에서 높은 화음으로. */
    private short[] revive() {
        float[] o = new float[(int) (SR * 0.8)];
        tone(o, 0, 0.5, 300, 900, 0.35f, true);
        tone(o, 0.35, 0.4, 1047, 1047, 0.3f, false);
        tone(o, 0.35, 0.4, 1319, 1319, 0.25f, false);
        tone(o, 0.35, 0.4, 1568, 1568, 0.2f, false);
        return toPcm(o);
    }

    /** 구매 완료: 계산대 딩동. */
    private short[] buy() {
        float[] o = new float[(int) (SR * 0.4)];
        tone(o, 0, 0.08, 1319, 1319, 0.4f, true);
        tone(o, 0.07, 0.08, 1568, 1568, 0.4f, true);
        tone(o, 0.14, 0.25, 2093, 2093, 0.35f, true);
        return toPcm(o);
    }
}
