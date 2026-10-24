package kr.lane.guide;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 화면이 꺼지거나 다른 앱(티맵)이 앞에 있어도 GPS로 진행 위치를 계산해 합류 지점 전에 음성 안내 */
public class GuideService extends Service implements LocationListener, TextToSpeech.OnInitListener {
    static final String ACTION_START = "start", ACTION_STOP = "stop";
    static final String CH = "guide";
    static final int NID = 7;
    static volatile String pending;

    private double[][] poly; private double[] cum;
    private String[] tName; private double[] tAlong;
    private double distM = 2000; private String phrase = "{IC} 진입 차량 많은 구간입니다. 왼쪽 차로로 이동하세요.";
    private final Set<Integer> announced = new HashSet<>();
    private TextToSpeech tts; private boolean ttsReady; private String queued;
    private LocationManager lm;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopAll(); return START_NOT_STICKY; }
        startFg("안내 준비 중");
        try { load(pending); } catch (Exception e) { update("경로 데이터 오류"); }
        if (tts == null) tts = new TextToSpeech(this, this);
        say("백그라운드 안내를 시작합니다");
        startGps();
        return START_STICKY;
    }

    private void load(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray p = o.getJSONArray("poly"), c = o.getJSONArray("cum"), t = o.getJSONArray("targets");
        poly = new double[p.length()][2];
        for (int i = 0; i < p.length(); i++) { JSONArray q = p.getJSONArray(i); poly[i][0] = q.getDouble(0); poly[i][1] = q.getDouble(1); }
        cum = new double[c.length()];
        for (int i = 0; i < c.length(); i++) cum[i] = c.getDouble(i);
        tName = new String[t.length()]; tAlong = new double[t.length()];
        for (int i = 0; i < t.length(); i++) { JSONObject x = t.getJSONObject(i); tName[i] = x.getString("name"); tAlong[i] = x.getDouble("along"); }
        distM = o.optDouble("dist", 2000);
        phrase = o.optString("phrase", phrase);
        announced.clear();
    }

    private void startGps() {
        if (lm != null) return;
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException e) { update("위치 권한 없음"); }
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (poly == null || poly.length < 2) return;
        double[] pr = project(loc.getLongitude(), loc.getLatitude());
        if (pr[0] > 250) { update(String.format(Locale.KOREA, "경로에서 %.0fm 벗어남", pr[0])); return; }
        double along = pr[1];
        int next = -1;
        for (int i = 0; i < tAlong.length; i++) if (tAlong[i] > along - 300) { next = i; break; }
        if (next < 0) { update("남은 합류 지점 없음"); return; }
        double gap = tAlong[next] - along;
        update(String.format(Locale.KOREA, "%s까지 %.1fkm", tName[next], gap / 1000));
        if (gap <= distM && gap > 200 && !announced.contains(next)) {
            announced.add(next);
            say(phrase.replace("{IC}", tName[next]));
        }
    }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) { update("GPS 꺼짐"); }

    /** 반환 {수직거리m, 누적거리m} — 웹앱 project()와 같은 계산 */
    private double[] project(double lon, double lat) {
        final double R = 6371000, k = Math.PI / 180, lat0 = poly[0][1];
        final double cx = k * R * Math.cos(lat0 * k), cy = k * R;
        double px = lon * cx, py = lat * cy, bestD = Double.MAX_VALUE, bestA = 0;
        for (int i = 0; i < poly.length - 1; i++) {
            double ax = poly[i][0] * cx, ay = poly[i][1] * cy, bx = poly[i + 1][0] * cx, by = poly[i + 1][1] * cy;
            double vx = bx - ax, vy = by - ay, L2 = vx * vx + vy * vy;
            double t = L2 == 0 ? 0 : ((px - ax) * vx + (py - ay) * vy) / L2;
            t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
            if (d < bestD) { bestD = d; bestA = cum[i] + t * (cum[i + 1] - cum[i]); }
        }
        return new double[]{bestD, bestA};
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.KOREAN);
            ttsReady = true;
            if (queued != null) { say(queued); queued = null; }
        }
    }

    private void say(String s) {
        if (!ttsReady) { queued = s; return; }
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "g" + System.currentTimeMillis());
    }

    private Notification build(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(new NotificationChannel(CH, "합류 안내", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, GuideService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return nb.setContentTitle("합류 차로 안내 중").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_directions).setOngoing(true)
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "종료", stop).build()).build();
    }

    private void startFg(String text) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NID, build(text));
    }

    private void update(String text) {
        getSystemService(NotificationManager.class).notify(NID, build(text));
    }

    private void stopAll() {
        if (lm != null) { lm.removeUpdates(this); lm = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; ttsReady = false; }
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (lm != null) lm.removeUpdates(this);
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
